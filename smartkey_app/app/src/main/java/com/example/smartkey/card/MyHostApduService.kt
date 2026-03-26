package com.example.smartkey.card

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import java.nio.ByteBuffer

/**
 * MyHostApduService - HCE service tương thích với IoT NFC reader
 * Implement giao thức provisioning 2-step từ iot/src/nfc_session.cpp
 */
class MyHostApduService : HostApduService() {

    companion object {
        private const val TAG = "MyHostApduService"
        
        // Status words
        private val SW_SUCCESS = byteArrayOf(0x90.toByte(), 0x00)
        private val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D.toByte(), 0x00)
        private val SW_FILE_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val SW_WRONG_LENGTH = byteArrayOf(0x67.toByte(), 0x00)
        
        // Expected AID từ IoT code: {0xF0, 0x01, 0x02, 0x03, 0x04, 0x05}
        private val EXPECTED_AID = "F00102030405"
    }

    private lateinit var provisioningManager: ProvisioningManager
    
    override fun onCreate() {
        super.onCreate()
        provisioningManager = ProvisioningManager(applicationContext)
        provisioningManager.initialize()
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

        // GET_CHALLENGE: 00 CA 00 00 [Lc] [challenge data] Le
        if (cla == 0x00.toByte() && ins == 0xCA.toByte() && p1 == 0x00.toByte() && p2 == 0x00.toByte()) {
            return handleGetChallenge(commandApdu)
        }

        Log.w(TAG, "Unsupported command: CLA=$cla INS=$ins")
        return SW_INS_NOT_SUPPORTED
    }

    /**
     * Handle SELECT AID command
     * IoT reader expects: SELECT -> [actionByte?] 9000
     * actionByte: 0x01 = LOCK (LED off), 0x02 = UNLOCK (LED on)
     */
    private fun handleSelectAid(apdu: ByteArray): ByteArray {
        if (apdu.size < 5) return SW_WRONG_LENGTH
        
        val lc = apdu[4].toInt() and 0xFF
        if (apdu.size < 5 + lc) return SW_WRONG_LENGTH
        
        val receivedAid = apdu.copyOfRange(5, 5 + lc).toHexString()
        Log.i(TAG, "SELECT AID received: $receivedAid")
        
        return if (receivedAid.equals(EXPECTED_AID, ignoreCase = true)) {
            val prefs = applicationContext.getSharedPreferences("hce_prefs", android.content.Context.MODE_PRIVATE)
            val pendingAction = prefs.getString("pending_action", null)
            when (pendingAction) {
                "1" -> {
                    Log.i(TAG, "SELECT AID matched, pending=LOCK, returning [0x01] 9000")
                    byteArrayOf(0x01, 0x90.toByte(), 0x00)
                }
                "2" -> {
                    Log.i(TAG, "SELECT AID matched, pending=UNLOCK, returning [0x02] 9000")
                    byteArrayOf(0x02, 0x90.toByte(), 0x00)
                }
                else -> {
                    Log.i(TAG, "SELECT AID matched, returning 9000")
                    SW_SUCCESS
                }
            }
        } else {
            Log.w(TAG, "SELECT AID not matched, expected: $EXPECTED_AID")
            SW_FILE_NOT_FOUND
        }
    }

    /**
     * Handle GET_CHALLENGE command - 2 steps theo IoT protocol
     * 
     * Step 1: Lc=0 -> Base GET_CHALLENGE
     *   Response: [keyIdLen][keyId..][pubKey65..][certLen(2)][cert..]
     * 
     * Step 2: Lc=24 -> Signature GET_CHALLENGE
     *   Input: [vehicleId(8)][nonce(16)]
     *   Response: [sigLen(2)][sigDER..]
     */
    private fun handleGetChallenge(apdu: ByteArray): ByteArray {
        if (apdu.size < 5) return SW_WRONG_LENGTH
        
        val lc = apdu[4].toInt() and 0xFF
        
        return when (lc) {
            0 -> handleBaseGetChallenge()
            24 -> handleSignatureGetChallenge(apdu)
            else -> {
                Log.w(TAG, "GET_CHALLENGE with unexpected Lc=$lc")
                SW_WRONG_LENGTH
            }
        }
    }

    /**
     * Step 1: Base GET_CHALLENGE (Lc=0)
     * Trả về keyId, public key, và certificate
     */
    private fun handleBaseGetChallenge(): ByteArray {
        Log.i(TAG, "[PhaseA] Step1: Base GET_CHALLENGE (Lc=0)")
        
        // Tạo hoặc lấy keyId
        var keyId = provisioningManager.getKeyId()
        if (keyId == null) {
            // Generate keyId từ device ID hoặc random
            keyId = android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ).take(16) // Lấy 16 ký tự đầu
            provisioningManager.forceStoreKeyId(keyId)
        }
        
        // Lấy public key 65 bytes
        val pubKey65 = provisioningManager.getPublicKey65() ?: run {
            Log.e(TAG, "Failed to get public key")
            return SW_INS_NOT_SUPPORTED
        }
        
        // Lấy certificate (placeholder - trong production nên có cert thật)
        val cert = provisioningManager.getCertificate()
        
        // Build response theo format IoT
        val keyIdBytes = keyId.toByteArray()
        val certLenBytes = ByteBuffer.allocate(2).putShort(cert.size.toShort()).array()
        
        val response = ByteBuffer.allocate(
            1 + keyIdBytes.size + 65 + 2 + cert.size + 2
        )
            .put(keyIdBytes.size.toByte()) // keyIdLen
            .put(keyIdBytes)                // keyId
            .put(pubKey65)                  // pubKey65
            .put(certLenBytes)              // certLen (2 bytes)
            .put(cert)                      // cert
            .put(SW_SUCCESS)                // SW1 SW2
            .array()
        
        Log.i(TAG, "[PhaseA] Base response: keyId=$keyId pubKeyFirst=${pubKey65[0].toHex()} certLen=${cert.size}")
        logApdu("TX", response)
        
        return response
    }

    /**
     * Step 2: Signature GET_CHALLENGE (Lc=24)
     * Nhận challenge 24 bytes, ký bằng private key, trả về signature
     */
    private fun handleSignatureGetChallenge(apdu: ByteArray): ByteArray {
        if (apdu.size < 5 + 24) return SW_WRONG_LENGTH
        
        val challenge = apdu.copyOfRange(5, 5 + 24)
        val vehicleId = challenge.copyOfRange(0, 8)
        val nonce = challenge.copyOfRange(8, 24)
        
        Log.i(TAG, "[PhaseA] Step2: Signature GET_CHALLENGE (Lc=24)")
        Log.i(TAG, "[PhaseA] vehicleId: ${vehicleId.toHexString()}")
        Log.i(TAG, "[PhaseA] nonce: ${nonce.toHexString()}")
        
        // Ký challenge bằng private key
        val signature = provisioningManager.signData(challenge)
        
        if (signature == null) {
            Log.e(TAG, "[PhaseA] Failed to sign challenge")
            return SW_INS_NOT_SUPPORTED
        }
        
        // Build response: [sigLen(2)][sigDER..][SW1 SW2]
        val sigLenBytes = ByteBuffer.allocate(2).putShort(signature.size.toShort()).array()
        
        val response = ByteBuffer.allocate(2 + signature.size + 2)
            .put(sigLenBytes)
            .put(signature)
            .put(SW_SUCCESS)
            .array()
        
        Log.i(TAG, "[PhaseA] Signature response: sigLen=${signature.size}")
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
    }

    // Helper functions
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02X".format(it) }
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
