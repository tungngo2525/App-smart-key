package com.example.smartkey.card

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.nfc.cardemulation.HostApduService
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.smartkey.MainActivity
import com.example.smartkey.R
import com.example.smartkey.data.VehicleRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * MyHostApduService - HCE service tương thích với IoT NFC reader
 * Implement giao thức provisioning 2-step từ iot/src/nfc_session.cpp
 */
class MyHostApduService : HostApduService() {

    companion object {
        private const val TAG = "MyHostApduService"
        private const val CHANNEL_ID = "vehicle_provision"
        
        // Status words
        private val SW_SUCCESS = byteArrayOf(0x90.toByte(), 0x00)
        private val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D.toByte(), 0x00)
        private val SW_FILE_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val SW_WRONG_LENGTH = byteArrayOf(0x67.toByte(), 0x00)
        private val SW_WRONG_DATA = byteArrayOf(0x6A.toByte(), 0x80.toByte())
        private val SW_CONDITIONS_NOT_SATISFIED = byteArrayOf(0x69.toByte(), 0x85.toByte())

        private const val ACTION_LOCK: Byte = 0x01
        private const val ACTION_UNLOCK: Byte = 0x02
        private const val PENDING_ACTION_TTL_MS = 60_000L
        private const val SHARE_META_VERSION: Byte = 0x11
        
        // CCC Digital Key R3 AID: D2760000850101
        // RID D276000085 (Bundesdruckerei / CCC-registered) + PIX 0101 (Digital Key service)
        private const val CCC_AID = "D2760000850101"
    }

    private lateinit var provisioningManager: ProvisioningManager
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val vehicleRepo = VehicleRepository()

    // CCC R3 session state: set during INTERNAL_AUTHENTICATE, consumed by GET_ACTION
    @Volatile private var sessionNonce: ByteArray? = null
    @Volatile private var sessionEcuId: String? = null

    override fun onCreate() {
        super.onCreate()
        provisioningManager = ProvisioningManager(applicationContext)
        provisioningManager.initialize()
        createNotificationChannel()
        Log.i(TAG, "HCE Service created, provisioned=${provisioningManager.isProvisioned()}")
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || commandApdu.size < 4) {
            Log.w(TAG, "Invalid APDU")
            return SW_INS_NOT_SUPPORTED
        }

        logApdu("RX", commandApdu)

        val cla = commandApdu[0]
        val ins = commandApdu[1]
        val p1 = commandApdu[2]
        val p2 = commandApdu[3]

        // SELECT AID: 00 A4 04 00 Lc [AID] Le
        if (cla == 0x00.toByte() && ins == 0xA4.toByte() && p1 == 0x04.toByte() && p2 == 0x00.toByte()) {
            return handleSelectAid(commandApdu)
        }

        // CCC R3 INTERNAL_AUTHENTICATE: 00 88 00 00 Lc [challenge] Le
        if (cla == 0x00.toByte() && ins == 0x88.toByte() && p1 == 0x00.toByte() && p2 == 0x00.toByte()) {
            return handleInternalAuthenticate(commandApdu)
        }

        // GET DATA variants: 00 CA P1 00 00
        if (cla == 0x00.toByte() && ins == 0xCA.toByte() && p2 == 0x00.toByte()) {
            return when (p1.toInt() and 0xFF) {
                0x01 -> handleGetCertificate()  // Certificate retrieval
                0x02 -> handleGetAction()         // Action authorization
                0x03 -> handleGetShareKey()       // Delegated key package (CCC share-key)
                else -> SW_INS_NOT_SUPPORTED
            }
        }

        Log.w(TAG, "Unsupported command: CLA=$cla INS=$ins")
        return SW_INS_NOT_SUPPORTED
    }

    /**
     * Handle SELECT AID command.
     * The real action is now authorized in signed GET_CHALLENGE, not in SELECT.
     */
    private fun handleSelectAid(apdu: ByteArray): ByteArray {
        if (apdu.size < 5) return SW_WRONG_LENGTH
        
        val lc = apdu[4].toInt() and 0xFF
        if (apdu.size < 5 + lc) return SW_WRONG_LENGTH
        
        val receivedAid = apdu.copyOfRange(5, 5 + lc).toHexString()
        Log.i(TAG, "SELECT AID received: $receivedAid")
        
        return if (receivedAid.equals(CCC_AID, ignoreCase = true)) {
            Log.i(TAG, "CCC R3 AID matched, ready for INTERNAL_AUTHENTICATE")
            SW_SUCCESS
        } else {
            Log.w(TAG, "SELECT AID not matched, expected: $CCC_AID")
            SW_FILE_NOT_FOUND
        }
    }

    /**
     * CCC R3 INTERNAL_AUTHENTICATE (INS=0x88)
     * Input: Lc=24 → [vehicleId(8)][nonce(16)]
     * Response: [pubKey65(65)][action(1)][sigLen(2)][sigDER(N)][SW1 SW2]
     * The vehicle signs the challenge+action so the reader can verify before acting.
     */
    /**
     * CCC R3 INTERNAL_AUTHENTICATE (INS=0x88) - OPTIMIZED SINGLE-TAP
     * Input: Lc=24 → [vehicleId(8)][nonce(16)]
     * Response: [action(1)][sigLen(2)][sig(N)][SW(2)]
     * 
     * SIGNATURE COVERS: nonce(16) + vehicleId(8) + action(1) = 25 bytes
     * → Single verify, single APDU, instant unlock! ⚡
     */
    private fun handleInternalAuthenticate(apdu: ByteArray): ByteArray {
        if (apdu.size < 5) return SW_WRONG_LENGTH
        val lc = apdu[4].toInt() and 0xFF
        if (lc != 24 || apdu.size < 5 + 24) {
            Log.w(TAG, "[CCC] INTERNAL_AUTHENTICATE unexpected Lc=$lc")
            return SW_WRONG_LENGTH
        }
        val challenge = apdu.copyOfRange(5, 29)
        val vehicleId = challenge.copyOfRange(0, 8)
        val nonce = challenge.copyOfRange(8, 24)
        val ecuVehicleIdHex = vehicleId.toHexString()

        Log.i(TAG, "[CCC] Fast transaction: ecu=$ecuVehicleIdHex")

        // Store for fallback GET_ACTION (backward compat)
        sessionNonce = nonce
        sessionEcuId = ecuVehicleIdHex

        val prefs = applicationContext.getSharedPreferences("hce_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("last_ecu_vehicle_id", ecuVehicleIdHex)
            .putLong("last_ecu_tap_time", System.currentTimeMillis())
            .apply()

        // ⚡ FAST: Determine action immediately
        val normalizedEcuId = normalizeEcuId(ecuVehicleIdHex)
        var actionByte: Byte? = null
        
        // Priority 1: pending_action (user explicit)
        var rawAction = prefs.getString("pending_action", null)
        val actionSetAtMs = prefs.getLong("pending_action_set_at_ms", 0L)
        val pendingEcu = normalizeEcuId(prefs.getString("pending_action_vehicle_ecu_id", null))
        
        if (rawAction != null) {
            val now = System.currentTimeMillis()
            if (actionSetAtMs > 0L && now - actionSetAtMs <= PENDING_ACTION_TTL_MS &&
                (pendingEcu.isNullOrBlank() || pendingEcu.equals(normalizedEcuId, ignoreCase = true))) {
                actionByte = when (rawAction) {
                    "1" -> ACTION_LOCK
                    "2" -> ACTION_UNLOCK
                    else -> null
                }
                if (actionByte != null) {
                    prefs.edit()
                        .remove("pending_action")
                        .remove("pending_action_set_at_ms")
                        .remove("pending_action_vehicle_ecu_id")
                        .apply()
                }
            }
        }
        
        // Priority 2: cached isActive
        if (actionByte == null) {
            val cachedActive = prefs.getBoolean("cached_vehicle_is_active", true)
            val cachedEcu = normalizeEcuId(prefs.getString("cached_vehicle_ecu_id", null))
            if (cachedEcu.isNullOrBlank() || cachedEcu.equals(normalizedEcuId, ignoreCase = true)) {
                actionByte = if (cachedActive) ACTION_UNLOCK else ACTION_LOCK
            }
        }
        
        // Default fallback
        if (actionByte == null) {
            actionByte = ACTION_UNLOCK
        }

        // Async Firestore sync (non-blocking)
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            serviceScope.launch {
                try {
                    val existingVehicle = vehicleRepo.findByEcuVehicleId(ecuVehicleIdHex)
                    if (existingVehicle == null) {
                        val vehicleDocId = vehicleRepo.createOrUpdateFromEcu(
                            uid = currentUser.uid,
                            ecuVehicleId = ecuVehicleIdHex,
                            name = "",
                            plate = ""
                        )
                        prefs.edit()
                            .putString("pending_vehicle_id", vehicleDocId)
                            .putString("pending_vehicle_ecu_id", ecuVehicleIdHex)
                            .apply()
                        showVehicleConfigNotification(vehicleDocId, ecuVehicleIdHex)
                    } else {
                        vehicleRepo.createOrUpdateFromEcu(
                            uid = currentUser.uid,
                            ecuVehicleId = ecuVehicleIdHex,
                            name = existingVehicle.name,
                            plate = existingVehicle.plate
                        )
                        if (existingVehicle.needsSetup) showVehicleConfigNotification(existingVehicle.id, ecuVehicleIdHex)
                    }
                    val cachedActive = existingVehicle?.isActive ?: true
                    prefs.edit()
                        .putBoolean("cached_vehicle_is_active", cachedActive)
                        .putString("cached_vehicle_ecu_id", ecuVehicleIdHex)
                        .apply()
                } catch (e: Exception) {
                    Log.e(TAG, "[CCC] Firestore sync failed", e)
                }
            }
        }

        // 🔥 SIGN ONCE: nonce(16) + vehicleId(8) + action(1) = 25 bytes
        val signedData = nonce + vehicleId + byteArrayOf(actionByte)
        val signature = provisioningManager.signData(signedData) ?: run {
            Log.e(TAG, "[CCC] Failed to sign")
            return SW_INS_NOT_SUPPORTED
        }

        // Response: [action][sigLen][sig][SW] - No pubKey needed (already provisioned!)
        val sigLenBytes = ByteBuffer.allocate(2).putShort(signature.size.toShort()).array()
        val response = ByteBuffer.allocate(1 + 2 + signature.size + 2)
            .put(actionByte)
            .put(sigLenBytes)
            .put(signature)
            .put(SW_SUCCESS)
            .array()

        Log.i(TAG, "[CCC] ⚡ Fast: action=0x${actionByte.toUByte().toString(16)} sigLen=${signature.size}")
        logApdu("TX", response)
        return response
    }

    /**
     * CCC GET_ACTION (INS=0xCA P1=0x02, Phase 3)
     * Requires a valid session from INTERNAL_AUTHENTICATE in this same NFC connection.
     * Action derived from pending_action pref (queued via UI) or cached vehicle isActive.
     * Response: [action(1)][sigLen(2)][actionSigDER(N)][SW(2)]
     * Signature covers nonce(16)+action(1) — binds action to this session.
     */
    private fun handleGetAction(): ByteArray {
        val nonce = sessionNonce ?: run {
            Log.w(TAG, "[CCC] GET_ACTION: no active session (INTERNAL_AUTHENTICATE required first)")
            return SW_CONDITIONS_NOT_SATISFIED
        }
        val ecuId = sessionEcuId ?: ""
        val normalizedEcuId = normalizeEcuId(ecuId)
        // Clear session: one action per tap
        sessionNonce = null
        sessionEcuId = null

        val prefs = applicationContext.getSharedPreferences("hce_prefs", Context.MODE_PRIVATE)
        var rawAction = prefs.getString("pending_action", null)
        var actionSetAtMs = prefs.getLong("pending_action_set_at_ms", 0L)
        val pendingEcu = normalizeEcuId(prefs.getString("pending_action_vehicle_ecu_id", null))

        // Validate pending_action TTL and vehicle match
        if (rawAction != null) {
            val now = System.currentTimeMillis()
            if (actionSetAtMs <= 0L || now - actionSetAtMs > PENDING_ACTION_TTL_MS) {
                Log.w(TAG, "[CCC] pending_action expired (age=${now - actionSetAtMs}ms)")
                rawAction = null
            } else if (!pendingEcu.isNullOrBlank() && !pendingEcu.equals(normalizedEcuId, ignoreCase = true)) {
                Log.w(TAG, "[CCC] pending_action vehicle mismatch queued=$pendingEcu tapped=$normalizedEcuId")
                rawAction = null
            }
        }

        // Fallback: derive action from cached vehicle isActive state
        if (rawAction == null) {
            val cachedActive = prefs.getBoolean("cached_vehicle_is_active", false)
            val cachedEcu = normalizeEcuId(prefs.getString("cached_vehicle_ecu_id", null))
            if (cachedEcu.isNullOrBlank() || cachedEcu.equals(normalizedEcuId, ignoreCase = true)) {
                rawAction = if (cachedActive) "2" else "1"
                actionSetAtMs = System.currentTimeMillis()
                Log.i(TAG, "[CCC] No pending_action; fallback isActive=$cachedActive → action=$rawAction")
            } else {
                Log.w(TAG, "[CCC] No valid action: cache mismatch cachedEcu=$cachedEcu tapped=$normalizedEcuId")
                return SW_CONDITIONS_NOT_SATISFIED
            }
        }

        val actionByte: Byte = when (rawAction) {
            "1" -> ACTION_LOCK
            "2" -> ACTION_UNLOCK
            else -> { Log.w(TAG, "[CCC] Invalid rawAction=$rawAction"); return SW_WRONG_DATA }
        }

        // Sign nonce(16) + action(1) — binds this authorization to the current session
        val signedData: ByteArray = nonce + byteArrayOf(actionByte)
        val signature = provisioningManager.signData(signedData) ?: run {
            Log.e(TAG, "[CCC] Failed to sign action data")
            return SW_INS_NOT_SUPPORTED
        }

        // Clear pending action from prefs
        prefs.edit()
            .remove("pending_action")
            .remove("pending_action_set_at_ms")
            .remove("pending_action_vehicle_ecu_id")
            .apply()

        // Response: [action(1)][sigLen(2)][actionSigDER(N)][SW(2)]
        val sigLenBytes = ByteBuffer.allocate(2).putShort(signature.size.toShort()).array()
        val response = ByteBuffer.allocate(1 + 2 + signature.size + 2)
            .put(actionByte)
            .put(sigLenBytes)
            .put(signature)
            .put(SW_SUCCESS)
            .array()
        Log.i(TAG, "[CCC] Action response: action=0x${actionByte.toUByte().toString(16).padStart(2,'0')} sigLen=${signature.size}")
        logApdu("TX", response)
        return response
    }

    /**
     * CCC GET_CERTIFICATE (INS=0xCA P1=0x01)
     * Returns the device's Android KeyStore X.509 self-signed certificate (DER).
     * Response: [certLen(2)][certDER(N)][SW(2)]
     */
    private fun handleGetCertificate(): ByteArray {
        val certDer = provisioningManager.getCertificateDer() ?: run {
            Log.w(TAG, "[CCC] Certificate not available")
            return SW_FILE_NOT_FOUND
        }
        val certLenBytes = ByteBuffer.allocate(2).putShort(certDer.size.toShort()).array()
        val response = ByteBuffer.allocate(2 + certDer.size + 2)
            .put(certLenBytes)
            .put(certDer)
            .put(SW_SUCCESS)
            .array()
        Log.i(TAG, "[CCC] Certificate response: certLen=${certDer.size}")
        logApdu("TX", response)
        return response
    }

    /**
     * CCC GET_SHARE_KEY (INS=0xCA P1=0x03)
     * Response payload:
     *   [keyIdLen(1)][keyIdAscii(N)][delegatedPub65(65)][metaVer(1)][validUntilSec(8)][permissions(1)][ownerSigLen(2)][ownerSigDer(M)][SW(2)]
     * Data is prepared by app side and stored in SharedPreferences before tap.
     */
    private fun handleGetShareKey(): ByteArray {
        val prefs = applicationContext.getSharedPreferences("hce_prefs", Context.MODE_PRIVATE)
        val keyId = prefs.getString("pending_share_key_id", null)?.trim().orEmpty()
        val delegatedPubHex = prefs.getString("pending_share_pub65_hex", null)?.trim().orEmpty()
        val validUntilSeconds = prefs.getLong("pending_share_valid_until", 0L).coerceAtLeast(0L)
        val permissions = prefs.getInt("pending_share_permissions", 0xFF) and 0xFF
        val nonce = sessionNonce
        val ecuIdHex = sessionEcuId.orEmpty()

        if (keyId.isEmpty() || delegatedPubHex.isEmpty()) {
            Log.i(TAG, "[CCC] GET_SHARE_KEY: no pending package")
            return SW_CONDITIONS_NOT_SATISFIED
        }
        if (nonce == null || nonce.size != 16) {
            Log.w(TAG, "[CCC] GET_SHARE_KEY: missing session nonce")
            return SW_CONDITIONS_NOT_SATISFIED
        }
        if (keyId.length > 64) {
            Log.w(TAG, "[CCC] GET_SHARE_KEY: keyId too long")
            return SW_WRONG_DATA
        }

        val vehicleId = ecuIdHex.hexToByteArrayOrNull()
        if (vehicleId == null || vehicleId.size != 8) {
            Log.w(TAG, "[CCC] GET_SHARE_KEY: invalid ecuId context")
            return SW_WRONG_DATA
        }

        val delegatedPub = delegatedPubHex.hexToByteArrayOrNull()
        if (delegatedPub == null || delegatedPub.size != 65 || delegatedPub[0] != 0x04.toByte()) {
            Log.w(TAG, "[CCC] GET_SHARE_KEY: invalid delegated pubkey")
            return SW_WRONG_DATA
        }

        val keyIdBytes = keyId.toByteArray(Charsets.US_ASCII)
        val label = "CCC-DK-SHARE-V3".toByteArray(Charsets.US_ASCII)
        val signedData = ByteBuffer.allocate(label.size + 8 + 16 + 1 + keyIdBytes.size + 65 + 1 + 8 + 1)
            .put(label)
            .put(vehicleId)
            .put(nonce)
            .put(keyIdBytes.size.toByte())
            .put(keyIdBytes)
            .put(delegatedPub)
            .put(SHARE_META_VERSION)
            .putLong(validUntilSeconds)
            .put(permissions.toByte())
            .array()

        val ownerSigDer = provisioningManager.signData(signedData) ?: run {
            Log.w(TAG, "[CCC] GET_SHARE_KEY: sign failed")
            return SW_INS_NOT_SUPPORTED
        }

        val sigLenBytes = ByteBuffer.allocate(2).putShort(ownerSigDer.size.toShort()).array()
        val response = ByteBuffer.allocate(1 + keyIdBytes.size + 65 + 1 + 8 + 1 + 2 + ownerSigDer.size + 2)
            .put(keyIdBytes.size.toByte())
            .put(keyIdBytes)
            .put(delegatedPub)
            .put(SHARE_META_VERSION)
            .putLong(validUntilSeconds)
            .put(permissions.toByte())
            .put(sigLenBytes)
            .put(ownerSigDer)
            .put(SW_SUCCESS)
            .array()

        prefs.edit()
            .remove("pending_share_key_id")
            .remove("pending_share_pub65_hex")
            .remove("pending_share_valid_until")
            .remove("pending_share_permissions")
            .remove("pending_share_vehicle_id")
            .remove("pending_share_vehicle_ecu_id")
            .remove("pending_share_set_at_ms")
            .apply()

        Log.i(TAG, "[CCC] GET_SHARE_KEY response: keyId=$keyId exp=$validUntilSeconds perm=0x${permissions.toString(16)} sigLen=${ownerSigDer.size}")
        logApdu("TX", response)
        return response
    }

    override fun onDeactivated(reason: Int) {
        val reasonStr = when (reason) {
            DEACTIVATION_LINK_LOSS -> "LINK_LOSS"
            DEACTIVATION_DESELECTED -> "DESELECTED"
            else -> "UNKNOWN($reason)"
        }
        Log.i(TAG, "Deactivated: $reasonStr")
        // Clear per-tap session state on disconnect
        sessionNonce = null
        sessionEcuId = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Cấu hình xe"
            val description = "Thông báo khi phát hiện xe mới qua NFC"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                this.description = description
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showVehicleConfigNotification(vehicleDocId: String, ecuVehicleIdHex: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("action", "config_vehicle")
            putExtra("vehicle_id", vehicleDocId)
            putExtra("ecu_vehicle_id", ecuVehicleIdHex)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("🚗 Phát hiện xe mới!")
            .setContentText("Tap để cấu hình thông tin xe (tên, biển số)")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(ecuVehicleIdHex.hashCode(), notification)
        
        Log.i(TAG, "Notification shown for new vehicle: $ecuVehicleIdHex")
    }

    // Helper functions
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02X".format(it) }
    }

    private fun String.hexToByteArrayOrNull(): ByteArray? {
        if (isEmpty() || length % 2 != 0) return null
        return try {
            chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeEcuId(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return value.uppercase().replace(Regex("[^0-9A-F]"), "")
    }
    
    private fun Byte.toHex(): String {
        return "%02X".format(this)
    }
    
    private fun logApdu(direction: String, apdu: ByteArray) {
        val hex = apdu.toHexString()
        val preview = if (hex.length > 64) hex.take(64) + "..." else hex
        Log.d(TAG, "[$direction] ${apdu.size} bytes: $preview")
    }
}
