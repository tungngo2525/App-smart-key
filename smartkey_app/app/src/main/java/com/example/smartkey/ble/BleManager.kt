package com.example.smartkey.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.example.smartkey.card.ProvisioningManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.UUID
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * BleManager - CCC-compatible BLE client
 *
 * Giao thức:
 * 1. Scan device name "ESP-Smart-Car-ECU" hoặc Auth service UUID
 * 2. Kết nối GATT
 * 3. ECDH handshake (Phase B):
 *    - Enable notify ServerHello + ServerConfirm
 *    - Tạo ephemeral key pair (P-256)
 *    - Ký ephemeral pub với long-term key (ProvisioningManager)
 *    - Write ClientHello: [ver=1][pubLen LE2][pub65][sigLen LE2][sig]
 *    - Nhận ServerHello → ephemeral pub của ECU
 *    - ECDH + HKDF → K_enc, K_mac
 *    - Write ClientConfirm: HMAC(K_mac, "AUTH-PROOF"||cliPub||srvPub)
 *    - Nhận ServerConfirm → verify → session ready
 * 4. Admin commands qua Admin service
 */
@SuppressLint("MissingPermission")
class BleManager private constructor(private val context: Context) {

    companion object {
        @Volatile private var INSTANCE: BleManager? = null

        /** Lấy singleton instance, tạo nếu chưa có. */
        fun get(context: Context): BleManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: BleManager(context.applicationContext).also { INSTANCE = it }
            }

        private const val TAG = "BleManager"
        const val DEVICE_NAME = "ESP-Smart-Car-ECU"
        const val DEFAULT_DEVICE_NAME = DEVICE_NAME
        private const val CLIENT_HELLO_SIG_LABEL = "BLE-CLIENT-HELLO"
        private const val SERVER_HELLO_SIG_LABEL = "BLE-SERVER-HELLO"
        private const val SERVER_HELLO_V2: Byte = 0x02

        // Auth service + characteristics
        val AUTH_SERVICE_UUID: UUID = UUID.fromString("d0d0d0d0-0000-4000-8000-d0d0d0d00000")
        val CHAR_CLIENT_HELLO: UUID = UUID.fromString("d0d0d0d0-0001-4000-8000-d0d0d0d00001")
        val CHAR_SERVER_HELLO: UUID = UUID.fromString("d0d0d0d0-0002-4000-8000-d0d0d0d00002")
        val CHAR_CLIENT_CFM: UUID = UUID.fromString("d0d0d0d0-0003-4000-8000-d0d0d0d00003")
        val CHAR_SERVER_CFM: UUID = UUID.fromString("d0d0d0d0-0004-4000-8000-d0d0d0d00004")

        // Admin service + characteristics
        val ADMIN_SERVICE_UUID: UUID = UUID.fromString("9a9b9c9d-0000-4000-8000-9a9b9c9d0000")
        val CHAR_ADMIN_MODE: UUID = UUID.fromString("9a9b9c9d-0001-4000-8000-9a9b9c9d0001")
        val CHAR_ADMIN_CMD: UUID = UUID.fromString("9a9b9c9d-0002-4000-8000-9a9b9c9d0002")
        val CHAR_ADMIN_INFO: UUID = UUID.fromString("9a9b9c9d-0003-4000-8000-9a9b9c9d0003")

        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Admin commands (mirror của ECU constants)
        const val CMD_CLEAR_PROVISIONED: Byte = 0x01
        const val CMD_CLEAR_ALL: Byte = 0x02
        const val CMD_NFC_PROVISION: Byte = 0x20
        const val CMD_FORCE_PERSIST_ON: Byte = 0x30
        const val CMD_FORCE_PERSIST_OFF: Byte = 0x31
        const val CMD_FORCE_ONESHOT: Byte = 0x32
        const val CMD_STATUS: Byte = 0x33
        const val CMD_VALIDATE_CERT: Byte = 0x34
        const val CMD_PUB_PRESENT: Byte = 0x35
        const val CMD_KEYID: Byte = 0x36

        // UWB commands (CCC Phase C)
        const val CMD_UWB_STATUS: Byte  = 0x40
        const val CMD_UWB_ENABLE: Byte  = 0x41
        const val CMD_UWB_DISABLE: Byte = 0x42
    }

    enum class ConnState { IDLE, SCANNING, CONNECTING, CONNECTED, DISCONNECTED }
    enum class SessionState { NONE, HANDSHAKING, READY, FAILED }

    data class BleState(
        val conn: ConnState = ConnState.IDLE,
        val session: SessionState = SessionState.NONE,
        val adminInfo: String = "",
        val log: List<String> = emptyList(),
        val autoConnect: Boolean = false,
        val rssi: Int = 0,                     // RSSI đo được gần nhất (dBm)
        val proximityUnlocked: Boolean = false, // đã mở cửa qua passive entry
        val uwbDistance: Float = -1f,           // khoảng cách UWB (m), -1 = không có data
        val uwbEnabled: Boolean = false         // ECU đã bật UWB ranging
    )

    private val _state = MutableStateFlow(BleState())
    val state: StateFlow<BleState> = _state.asStateFlow()

    // RSSI hysteresis config (CCC Passive Entry)
    private val RSSI_UNLOCK_THRESHOLD = -70   // dBm: unlock khi RSSI ≥ ngưỡng này
    private val RSSI_LOCK_THRESHOLD   = -78   // dBm: lock khi RSSI < ngưỡng này (hysteresis 8dBm)
    private val RSSI_CONFIRM_COUNT    = 3     // số readings liên tiếp cần xác nhận
    private val RSSI_POLL_MS          = 2_000L  // 2s để tránh GATT congestion
    private var rssiJob: kotlinx.coroutines.Job? = null

    /** Mutex serialize mọi GATT operation — Android BLE chỉ xử lý 1 op một lúc */
    private val gattMutex = Mutex()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val trustPrefs: SharedPreferences = context.getSharedPreferences("ble_trust", Context.MODE_PRIVATE)
    private val btAdapter get() = btManager.adapter
    private var gatt: BluetoothGatt? = null
    private var currentDeviceAddress: String? = null

    // Auto-connect / reconnect
    private var autoConnectJob: kotlinx.coroutines.Job? = null
    /** Callback gọi khi GATT connect + services discovered thành công (dùng bởi Service) */
    var onConnectedCallback: (() -> Unit)? = null

    // Pending coroutine callbacks
    private var onConnectedCb: (() -> Unit)? = null
    private var onDisconnectedCb: (() -> Unit)? = null
    private var onServicesDiscoveredCb: (() -> Unit)? = null
    private var onMtuCb: ((Int) -> Unit)? = null
    private var pendingWriteCb: ((Boolean) -> Unit)? = null
    private var pendingDescWriteCb: ((Boolean) -> Unit)? = null
    private var pendingReadCb: ((ByteArray?) -> Unit)? = null
    private var onRssiCb: ((Int) -> Unit)? = null

    // Session crypto state
    private var myEphemeralPub65: ByteArray? = null
    private var myEphemeralPrivKey: java.security.PrivateKey? = null
    private var sessionKMac: ByteArray? = null
    var sessionKEnc: ByteArray? = null
        private set

    // CCC R3 Replay Protection: Admin channel sequence counters
    private val adminTxSeq = AtomicInteger(0)   // Android→ECU command counter
    private var adminRxSeq = -1                  // last received ECU seq (replay guard)

    // Notify listeners
    private var serverHelloListener: ((ByteArray) -> Unit)? = null
    private var serverCfmListener: ((ByteArray) -> Unit)? = null
    private var adminInfoListener: ((String) -> Unit)? = null

    // --- GATT Callback ---

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        // Kết nối thất bại ngay — close và retry
                        log("GATT connect error status=$status, retrying…")
                        scheduleGattClose(g, delayMs = 600)
                        return
                    }
                    log("GATT connected, requesting high priority…")
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    g.discoverServices()
                    updateConn(ConnState.CONNECTED)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    // status 133 = GATT_ERROR (Android BLE stack bug): cần delay dài hơn trước khi close
                    val closeDelay = if (status == 133 || status == 8) 800L else 300L
                    log("GATT disconnected (status=$status), close in ${closeDelay}ms")
                    updateConn(ConnState.DISCONNECTED)
                    resetSession()
                    rssiJob?.cancel(); rssiJob = null
                    _state.value = _state.value.copy(rssi = 0, proximityUnlocked = false)
                    onDisconnectedCb?.invoke()
                    onDisconnectedCb = null
                    scheduleGattClose(g, closeDelay)
                }
            }
        }

        private fun scheduleGattClose(g: BluetoothGatt, delayMs: Long = 300) {
            scope.launch {
                kotlinx.coroutines.delay(delayMs)
                g.close()
                if (gatt === g) gatt = null
                if (_state.value.autoConnect) {
                    val retryDelay = if (delayMs >= 800) 2_000L else 1_500L
                    log("Auto-reconnect in ${retryDelay}ms…")
                    kotlinx.coroutines.delay(retryDelay)
                    if (_state.value.autoConnect &&
                        _state.value.conn != ConnState.CONNECTED &&
                        _state.value.conn != ConnState.SCANNING &&
                        _state.value.conn != ConnState.CONNECTING) {
                        scanAndConnect()
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("Services discovered OK")
                scope.launch {
                    try {
                        enableNotify(ADMIN_SERVICE_UUID, CHAR_ADMIN_INFO)
                        log("Admin notify enabled")
                    } catch (e: Exception) {
                        log("Admin notify setup failed: ${e.message}")
                    }
                }
                onServicesDiscoveredCb?.invoke()
                onServicesDiscoveredCb = null
                onConnectedCallback?.invoke()
            } else {
                log("Service discovery failed: $status — disconnecting")
                g.disconnect()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log("MTU = $mtu")
            onMtuCb?.invoke(mtu)
            onMtuCb = null
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _state.value = _state.value.copy(rssi = rssi)
                onRssiCb?.invoke(rssi)
                onRssiCb = null
            } else {
                onRssiCb?.invoke(Int.MIN_VALUE)
                onRssiCb = null
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            char: BluetoothGattCharacteristic,
            status: Int
        ) {
            val value = if (status == BluetoothGatt.GATT_SUCCESS) char.value else null
            pendingReadCb?.invoke(value)
            pendingReadCb = null
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            char: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            val data = if (status == BluetoothGatt.GATT_SUCCESS) value else null
            pendingReadCb?.invoke(data)
            pendingReadCb = null
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            char: BluetoothGattCharacteristic,
            status: Int
        ) {
            pendingWriteCb?.invoke(status == BluetoothGatt.GATT_SUCCESS)
            pendingWriteCb = null
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            pendingDescWriteCb?.invoke(status == BluetoothGatt.GATT_SUCCESS)
            pendingDescWriteCb = null
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            char: BluetoothGattCharacteristic
        ) {
            handleNotify(char.uuid, char.value ?: return)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            char: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleNotify(char.uuid, value)
        }
    }

    private fun handleNotify(uuid: UUID, value: ByteArray) {
        when (uuid) {
            CHAR_SERVER_HELLO -> serverHelloListener?.invoke(value)
            CHAR_SERVER_CFM -> serverCfmListener?.invoke(value)
            CHAR_ADMIN_INFO -> {
                // CCC R3: decrypt AES-256-GCM if session live
                // Encrypted format: [0xAE][seq_be4][ciphertext][tag16]
                val plaintext: String? = if (value.isNotEmpty() && value[0] == 0xAE.toByte()) {
                    val kEnc = sessionKEnc
                    if (kEnc != null && value.size >= 1 + 4 + 1 + 16) {
                        val seq = ((value[1].toInt() and 0xFF) shl 24) or
                                  ((value[2].toInt() and 0xFF) shl 16) or
                                  ((value[3].toInt() and 0xFF) shl  8) or
                                   (value[4].toInt() and 0xFF)
                        if (seq > adminRxSeq) {
                            adminRxSeq = seq
                            val nonce = ByteArray(12).also { n ->
                                n[0] = value[1]; n[1] = value[2]; n[2] = value[3]; n[3] = value[4]
                            }
                            val ctWithTag = value.copyOfRange(5, value.size)
                            aesGcmDecrypt(kEnc, nonce, ctWithTag)?.let { String(it) }
                                ?: run { log("Admin decrypt failed"); null }
                        } else {
                            log("Admin notify REPLAY seq=$seq <= last=$adminRxSeq")
                            null
                        }
                    } else null
                } else {
                    String(value)  // Plain text (pre-session or legacy)
                }
                if (plaintext != null) {
                    log("AdminInfo: $plaintext")
                    updateAdminInfo(plaintext)
                    adminInfoListener?.invoke(plaintext)
                }
            }
        }
    }

    // --- Public API ---

    /** Tên thiết bị BLE đang nhắm đến — có thể đặt per-vehicle qua setTargetDevice() */
    @Volatile var currentDeviceTarget: String = DEVICE_NAME

    /** Đặt tên thiết bị BLE cần scan; để trống = dùng mặc định */
    fun setTargetDevice(name: String) {
        currentDeviceTarget = name.ifBlank { DEVICE_NAME }
    }

    fun scanAndConnect() {
        if (_state.value.conn == ConnState.SCANNING || _state.value.conn == ConnState.CONNECTING) return
        updateConn(ConnState.SCANNING)
        log("Scanning for $currentDeviceTarget…")

        val adapter = btAdapter
        if (adapter == null || !adapter.isEnabled) {
            log("Bluetooth is OFF")
            updateConn(ConnState.IDLE)
            return
        }

        val scanner = btAdapter?.bluetoothLeScanner ?: run {
            log("BLE not available")
            updateConn(ConnState.IDLE)
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                val advertisedName = record?.deviceName ?: result.device.name
                val hasAuthUuid = record?.serviceUuids?.contains(ParcelUuid(AUTH_SERVICE_UUID)) == true
                val hasAdminUuid = record?.serviceUuids?.contains(ParcelUuid(ADMIN_SERVICE_UUID)) == true

                if (advertisedName == currentDeviceTarget || hasAuthUuid || hasAdminUuid) {
                    scanner.stopScan(this)
                    log("Found device: ${result.device.address}, name=${advertisedName ?: "<none>"}")
                    connectTo(result.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                log("Scan failed: $errorCode")
                updateConn(ConnState.IDLE)
            }
        }

        // Do not pre-filter at scanner level because some phones/firmware expose
        // UUID/name only in scan response; we filter manually in callback instead.
        scanner.startScan(null, settings, scanCallback)

        // Scan timeout: dừng sau 30s tránh drain battery + nhiễu RF
        scope.launch {
            kotlinx.coroutines.delay(30_000)
            if (_state.value.conn == ConnState.SCANNING) {
                scanner.stopScan(scanCallback)
                log("Scan timeout — ECU not found")
                updateConn(ConnState.IDLE)
                // Retry sau 10s nếu auto-connect còn bật
                if (_state.value.autoConnect) {
                    kotlinx.coroutines.delay(10_000)
                    if (_state.value.autoConnect && _state.value.conn == ConnState.IDLE) {
                        scanAndConnect()
                    }
                }
            }
        }
    }

    fun disconnect() {
        gatt?.disconnect()
    }

    /** Bật auto-connect: scan ngay và tự reconnect khi mất tín hiệu */
    fun enableAutoConnect() {
        _state.value = _state.value.copy(autoConnect = true)
        if (_state.value.conn == ConnState.IDLE || _state.value.conn == ConnState.DISCONNECTED) {
            scanAndConnect()
        }
    }

    /** Tắt auto-connect và ngắt kết nối hiện tại */
    fun disableAutoConnect() {
        _state.value = _state.value.copy(autoConnect = false)
        disconnect()
    }

    fun sendAdminCommand(cmd: Byte) {
        scope.launch {
            try {
                writeChar(ADMIN_SERVICE_UUID, CHAR_ADMIN_CMD, buildSecureAdminCmd(cmd))
                log("Sent cmd 0x${cmd.toHex()}")
            } catch (e: Exception) {
                log("Admin cmd failed: ${e.message}")
            }
        }
    }

    fun runAuthHandshake() {
        scope.launch {
            try {
                doAuthHandshake()
            } catch (e: Exception) {
                log("Handshake failed: ${e.message}")
                _state.value = _state.value.copy(session = SessionState.FAILED)
            }
        }
    }

    fun setAdminInfoListener(listener: ((String) -> Unit)?) {
        adminInfoListener = listener
    }

    fun destroy() {
        rssiJob?.cancel(); rssiJob = null
        gatt?.close()
        gatt = null
        scope.cancel()
    }

    // --- Connection ---

    private fun connectTo(device: BluetoothDevice) {
        updateConn(ConnState.CONNECTING)
        currentDeviceAddress = device.address
        log("Connecting to ${device.address}…")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    // --- Auth Handshake ---

    private suspend fun doAuthHandshake() {
        _state.value = _state.value.copy(session = SessionState.HANDSHAKING)
        log("Starting ECDH handshake…")

        // 1. Request larger MTU
        requestMtu(185)

        // 2. Enable notifications on ServerHello and ServerConfirm
        enableNotify(AUTH_SERVICE_UUID, CHAR_SERVER_HELLO)
        enableNotify(AUTH_SERVICE_UUID, CHAR_SERVER_CFM)

        // 3. Generate ephemeral P-256 key pair (software key, not AndroidKeyStore)
        val ephemeralPair = generateEphemeralKeyPair()
        val ephPub65 = encodePublicKey65(ephemeralPair.public as ECPublicKey)
        myEphemeralPub65 = ephPub65
        myEphemeralPrivKey = ephemeralPair.private

        // 4. Sign client hello payload with long-term phone key
        val provMgr = ProvisioningManager(context)
        val clientSignedData = CLIENT_HELLO_SIG_LABEL.toByteArray() + ephPub65
        val sig = provMgr.signData(clientSignedData) ?: ByteArray(0)

        // 5. Build and write ClientHello: [ver=1][pubLen LE2][pub][sigLen LE2][sig]
        val clientHello = buildHello(ephPub65, sig)
        log("Writing ClientHello (${clientHello.size} bytes)…")

        // 6. Await ServerHello via notification
        val serverHello = suspendCancellableCoroutine<ByteArray> { cont ->
            serverHelloListener = { value -> serverHelloListener = null; cont.resume(value) }
            scope.launch {
                try {
                    writeChar(AUTH_SERVICE_UUID, CHAR_CLIENT_HELLO, clientHello)
                } catch (e: Exception) {
                    serverHelloListener = null
                    cont.resumeWithException(e)
                }
            }
        }

        // 7. Parse and verify server hello from ECU long-term key
        val parsedHello = parseServerHello(serverHello, ephPub65)
        val srvPub65 = parsedHello.ephemeralPub
        log("Got ServerHello, srvPub=${srvPub65.toHex().take(16)}…")

        // 8. ECDH shared secret
        val sharedSecret = computeEcdh(ephemeralPair.private, srvPub65)

        // 9. HKDF derive K_enc and K_mac
        val infoEnc = "BLE-AUTH".toByteArray() + ephPub65 + srvPub65
        val infoMac = "BLE-AUTH-MAC".toByteArray() + ephPub65 + srvPub65
        val kEnc = hkdfSha256(sharedSecret, infoEnc, 32)
        val kMac = hkdfSha256(sharedSecret, infoMac, 32)
        sessionKEnc = kEnc
        sessionKMac = kMac

        // 10. ClientConfirm: HMAC(K_mac, "AUTH-PROOF" || cliPub || srvPub)
        val clientProof = hmacSha256Label(kMac, "AUTH-PROOF", ephPub65, srvPub65)

        // 11. Await ServerConfirm
        val serverCfm = suspendCancellableCoroutine<ByteArray> { cont ->
            serverCfmListener = { value -> serverCfmListener = null; cont.resume(value) }
            scope.launch {
                try {
                    writeChar(AUTH_SERVICE_UUID, CHAR_CLIENT_CFM, clientProof)
                } catch (e: Exception) {
                    serverCfmListener = null
                    cont.resumeWithException(e)
                }
            }
        }

        // 12. Verify ServerConfirm: HMAC(K_mac, "AUTH-OK" || srvPub || cliPub)
        val expectedSrvCfm = hmacSha256Label(kMac, "AUTH-OK", srvPub65, ephPub65)
        if (!serverCfm.contentEquals(expectedSrvCfm)) {
            throw SecurityException("ServerConfirm verification failed")
        }

        log("Session established!")
        _state.value = _state.value.copy(session = SessionState.READY)
        startRssiMonitoring()
        enableUwb()  // CCC Phase C: bật UWB ranging trên ECU
    }

    /** Đo RSSI một lần, trả null nếu thất bại */
    private suspend fun readRssi(): Int? = gattMutex.withLock {
        withTimeout(3_000) {
            suspendCancellableCoroutine { cont ->
                onRssiCb = { rssi ->
                    if (rssi == Int.MIN_VALUE) cont.resume(null)
                    else cont.resume(rssi)
                }
                if (gatt?.readRemoteRssi() != true) {
                    onRssiCb = null
                    cont.resume(null)
                }
            }
        }
    }

    /**
     * RSSI hysteresis loop — chạy sau khi session READY.
     * Unlock khi RSSI ≥ RSSI_UNLOCK_THRESHOLD × RSSI_CONFIRM_COUNT readings liên tiếp.
     * Lock lại khi RSSI < RSSI_LOCK_THRESHOLD × RSSI_CONFIRM_COUNT readings liên tiếp.
     */
    private fun startRssiMonitoring() {
        rssiJob?.cancel()
        rssiJob = scope.launch {
            var aboveCount = 0
            var belowCount = 0
            log("RSSI monitoring started (unlock≥${RSSI_UNLOCK_THRESHOLD}, lock<${RSSI_LOCK_THRESHOLD})")
            while (currentCoroutineContext().isActive &&
                   _state.value.conn == ConnState.CONNECTED &&
                   _state.value.session == SessionState.READY) {

                val rssi = readRssi() ?: break

                if (!_state.value.proximityUnlocked) {
                    // Chưa mở: đếm readings trên ngưỡng unlock
                    if (rssi >= RSSI_UNLOCK_THRESHOLD) {
                        aboveCount++; belowCount = 0
                        log("RSSI $rssi dBm (đang lại gần $aboveCount/${RSSI_CONFIRM_COUNT})")
                        if (aboveCount >= RSSI_CONFIRM_COUNT) {
                            log("🔓 Passive Entry: RSSI $rssi dBm — UNLOCK")
                            writeChar(ADMIN_SERVICE_UUID, CHAR_ADMIN_CMD, buildSecureAdminCmd(CMD_FORCE_PERSIST_ON))
                            _state.value = _state.value.copy(proximityUnlocked = true)
                            aboveCount = 0
                        }
                    } else {
                        if (aboveCount > 0) aboveCount--
                    }
                } else {
                    // Đã mở: đếm readings dưới ngưỡng lock
                    if (rssi < RSSI_LOCK_THRESHOLD) {
                        belowCount++; aboveCount = 0
                        log("RSSI $rssi dBm (đang ra xa $belowCount/${RSSI_CONFIRM_COUNT})")
                        if (belowCount >= RSSI_CONFIRM_COUNT) {
                            log("🔒 Passive Entry: RSSI $rssi dBm — LOCK")
                            writeChar(ADMIN_SERVICE_UUID, CHAR_ADMIN_CMD, buildSecureAdminCmd(CMD_FORCE_PERSIST_OFF))
                            _state.value = _state.value.copy(proximityUnlocked = false)
                            belowCount = 0
                        }
                    } else {
                        if (belowCount > 0) belowCount--
                    }
                }

                kotlinx.coroutines.delay(RSSI_POLL_MS)
            }
            log("RSSI monitoring stopped")
        }
    }

    private suspend fun requestMtu(mtu: Int): Int = gattMutex.withLock {
        withTimeout(5_000) {
            suspendCancellableCoroutine { cont ->
                onMtuCb = { cont.resume(it) }
                if (gatt?.requestMtu(mtu) != true) {
                    onMtuCb = null
                    cont.resume(mtu)
                }
            }
        }
    }

    private suspend fun discoverServices() = withTimeout(10_000) {
        suspendCancellableCoroutine<Unit> { cont ->
            onServicesDiscoveredCb = { cont.resume(Unit) }
            if (gatt?.discoverServices() != true) {
                onServicesDiscoveredCb = null
                cont.resume(Unit)
            }
        }
    }

    private suspend fun enableNotify(serviceUuid: UUID, charUuid: UUID) {
        val g = gatt ?: throw IllegalStateException("Not connected")
        val svc = g.getService(serviceUuid) ?: throw IllegalStateException("Service $serviceUuid not found")
        val char = svc.getCharacteristic(charUuid) ?: throw IllegalStateException("Char $charUuid not found")

        g.setCharacteristicNotification(char, true)

        val desc = char.getDescriptor(CCCD_UUID) ?: return
        gattMutex.withLock {
            withTimeout(5_000) {
                suspendCancellableCoroutine<Unit> { cont ->
                    pendingDescWriteCb = { cont.resume(Unit) }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(desc)
                    }
                }
            }
        }
    }

    private suspend fun writeChar(serviceUuid: UUID, charUuid: UUID, data: ByteArray) {
        val g = gatt ?: throw IllegalStateException("Not connected")
        val char = g.getService(serviceUuid)?.getCharacteristic(charUuid)
            ?: throw IllegalStateException("Char $charUuid not found")

        gattMutex.withLock {
            withTimeout(5_000) {
                suspendCancellableCoroutine<Unit> { cont ->
                    pendingWriteCb = { ok ->
                        if (ok) cont.resume(Unit)
                        else cont.resumeWithException(Exception("Write failed"))
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    } else {
                        @Suppress("DEPRECATION")
                        char.value = data
                        @Suppress("DEPRECATION")
                        g.writeCharacteristic(char)
                    }
                }
            }
        }
    }

    private suspend fun readChar(serviceUuid: UUID, charUuid: UUID): ByteArray? {
        val g = gatt ?: throw IllegalStateException("Not connected")
        val char = g.getService(serviceUuid)?.getCharacteristic(charUuid)
            ?: throw IllegalStateException("Char $charUuid not found")

        return gattMutex.withLock {
            withTimeout(5_000) {
                suspendCancellableCoroutine { cont ->
                    pendingReadCb = { data -> cont.resume(data) }
                    if (!g.readCharacteristic(char)) {
                        pendingReadCb = null
                        cont.resume(null)
                    }
                }
            }
        }
    }

    // --- Crypto helpers ---

    /** AES-256-GCM decrypt; ciphertext includes 16-byte GCM tag appended at end. */
    private fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertextWithTag: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD("ADM".toByteArray())
            cipher.doFinal(ciphertextWithTag)
        } catch (_: Exception) { null }
    }

    /** Build a sequenced Admin command for replay protection.
     *  If session READY: [0xAD][seq_be4][cmd]  else: [cmd] (legacy) */
    private fun buildSecureAdminCmd(cmd: Byte): ByteArray {
        val kEnc = sessionKEnc
        return if (_state.value.session == SessionState.READY && kEnc != null) {
            val seq = adminTxSeq.incrementAndGet()
            byteArrayOf(
                0xAD.toByte(),
                (seq shr 24).toByte(), (seq shr 16).toByte(),
                (seq shr  8).toByte(),  seq.toByte(),
                cmd
            )
        } else {
            byteArrayOf(cmd)
        }
    }

    private fun generateEphemeralKeyPair(): java.security.KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    private fun encodePublicKey65(pub: ECPublicKey): ByteArray {
        val w = pub.w
        val x = w.affineX.toByteArray().let {
            if (it.size > 32) it.copyOfRange(it.size - 32, it.size)
            else it.padStart(32)
        }
        val y = w.affineY.toByteArray().let {
            if (it.size > 32) it.copyOfRange(it.size - 32, it.size)
            else it.padStart(32)
        }
        return byteArrayOf(0x04) + x + y
    }

    private fun ByteArray.padStart(size: Int): ByteArray {
        if (this.size >= size) return this
        return ByteArray(size - this.size) + this
    }

    /** Parse ECPublicKey from uncompressed 65-byte point */
    private fun decodePublicKey65(bytes: ByteArray): ECPublicKey {
        require(bytes.size == 65 && bytes[0] == 0x04.toByte()) { "Invalid uncompressed EC point" }
        val x = java.math.BigInteger(1, bytes.copyOfRange(1, 33))
        val y = java.math.BigInteger(1, bytes.copyOfRange(33, 65))
        val kf = KeyFactory.getInstance("EC")
        val params = (generateEphemeralKeyPair().public as ECPublicKey).params
        return kf.generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) as ECPublicKey
    }

    private fun computeEcdh(priv: java.security.PrivateKey, peerPub65: ByteArray): ByteArray {
        val peerPub = decodePublicKey65(peerPub65)
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(peerPub, true)
        val raw = ka.generateSecret() // may be 32 or 33 bytes
        return if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw.padStart(32)
    }

    /**
     * HKDF-SHA256: Extract (zero salt) + Expand
     * Mirrors hkdf_sha256(null, 0, ikm, len, info, infoLen, out, outLen) from crypto_utils.cpp
     */
    private fun hkdfSha256(ikm: ByteArray, info: ByteArray, outLen: Int): ByteArray {
        // Extract: PRK = HMAC-SHA256(zeros[32], IKM)
        val prk = hmacSha256(ByteArray(32), ikm)
        // Expand
        val out = ByteArray(outLen)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < outLen) {
            t = hmacSha256(prk, t + info + byteArrayOf(counter.toByte()))
            val copy = minOf(t.size, outLen - offset)
            t.copyInto(out, offset, 0, copy)
            offset += copy
            counter++
        }
        return out
    }

    /**
     * HMAC-SHA256(key, label_bytes || a || b)
     * Mirrors hmac_sha256_label from crypto_utils.cpp
     */
    private fun hmacSha256Label(key: ByteArray, label: String, a: ByteArray, b: ByteArray): ByteArray {
        return hmacSha256(key, label.toByteArray() + a + b)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun buildHello(pub: ByteArray, sig: ByteArray): ByteArray {
        val pubLen = pub.size.toLE2()
        val sigLen = sig.size.toLE2()
        return byteArrayOf(0x01) + pubLen + pub + sigLen + sig
    }

    private data class ParsedServerHello(
        val ephemeralPub: ByteArray,
        val signerPub: ByteArray,
        val signature: ByteArray
    )

    private suspend fun parseServerHello(data: ByteArray, clientPub65: ByteArray): ParsedServerHello {
        if (data.size < 5) throw IllegalStateException("ServerHello too short")

        return if (data[0] == SERVER_HELLO_V2) {
            val ephLen = data.readLe2(1)
            var offset = 3
            if (data.size < offset + ephLen + 2) throw IllegalStateException("Invalid ServerHello v2 pub len")
            val ephPub = data.copyOfRange(offset, offset + ephLen)
            offset += ephLen

            val signerLen = data.readLe2(offset)
            offset += 2
            if (data.size < offset + signerLen + 2) throw IllegalStateException("Invalid ServerHello v2 signer len")
            val signerPub = data.copyOfRange(offset, offset + signerLen)
            offset += signerLen

            val sigLen = data.readLe2(offset)
            offset += 2
            if (data.size < offset + sigLen) throw IllegalStateException("Invalid ServerHello v2 sig len")
            val signature = data.copyOfRange(offset, offset + sigLen)

            verifyAndPinServerSigner(clientPub65, ephPub, signerPub, signature)
            ParsedServerHello(ephPub, signerPub, signature)
        } else {
            val ephPub = parseLegacyHelloPub(data)
                ?: throw IllegalStateException("Invalid legacy ServerHello")
            log("Legacy ServerHello without ECU signer key; skipping signature verification")
            ParsedServerHello(ephPub, ByteArray(0), ByteArray(0))
        }
    }

    /** Legacy format: [ver][pubLen LE2][pub][sigLen LE2][sig] */
    private fun parseLegacyHelloPub(data: ByteArray): ByteArray? {
        if (data.size < 3) return null
        val pubLen = data.readLe2(1)
        if (data.size < 3 + pubLen) return null
        return data.copyOfRange(3, 3 + pubLen)
    }

    private suspend fun verifyAndPinServerSigner(
        clientPub65: ByteArray,
        serverPub65: ByteArray,
        signerPub65: ByteArray,
        signature: ByteArray
    ) {
        if (signerPub65.size != 65 || signerPub65.firstOrNull() != 0x04.toByte()) {
            throw SecurityException("ServerHello signer key invalid")
        }

        val trustKey = currentDeviceAddress?.let { "ecu_signer_${it.replace(':', '_')}" }
            ?: throw SecurityException("Missing BLE device address for signer pinning")
        val pinnedHex = trustPrefs.getString(trustKey, null)
        val signerHex = signerPub65.toHex()
        val trustedProfileKeys = loadTrustedSignerKeysFromCloud()

        if (pinnedHex == null) {
            if (trustedProfileKeys.isNotEmpty()) {
                if (!trustedProfileKeys.contains(signerHex)) {
                    throw SecurityException("ECU signer key is not present in trusted cloud profiles")
                }
                trustPrefs.edit().putString(trustKey, signerHex).apply()
                log("Pinned ECU signer key from cloud profile for ${currentDeviceAddress}")
            } else {
                trustPrefs.edit().putString(trustKey, signerHex).apply()
                log("Pinned ECU signer key for ${currentDeviceAddress} via TOFU fallback")
            }
        } else if (!pinnedHex.equals(signerHex, ignoreCase = true)) {
            throw SecurityException("ECU signer key mismatch for ${currentDeviceAddress}")
        }

        val signedData = SERVER_HELLO_SIG_LABEL.toByteArray() + clientPub65 + serverPub65 + signerPub65
        if (!verifySignatureP256(signerPub65, signedData, signature)) {
            throw SecurityException("ServerHello signature invalid")
        }
    }

    private suspend fun loadTrustedSignerKeysFromCloud(): Set<String> {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return emptySet()
        val vehiclesRef = Firebase.firestore.collection("vehicles")
            .whereEqualTo("profile.ownerUid", uid)

        val snapshot = try {
            vehiclesRef.get(Source.SERVER).await()
        } catch (_: Exception) {
            vehiclesRef.get(Source.CACHE).await()
        }

        return snapshot.documents.mapNotNull { doc ->
            val tb = doc.get("tb") as? Map<*, *> ?: return@mapNotNull null
            val hex = (tb["devicePublicKeyHex"] as? String)?.trim()?.uppercase().orEmpty()
            hex.takeIf { it.length == 130 && it.startsWith("04") }
        }.toSet()
    }

    private fun verifySignatureP256(pub65: ByteArray, data: ByteArray, signatureDer: ByteArray): Boolean {
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(decodePublicKey65(pub65))
        verifier.update(data)
        return verifier.verify(signatureDer)
    }

    private fun ByteArray.readLe2(offset: Int): Int {
        return (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun Int.toLE2(): ByteArray = byteArrayOf((this and 0xFF).toByte(), ((this shr 8) and 0xFF).toByte())
    private fun Byte.toHex() = "%02X".format(this)
    private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }

    // --- State helpers ---

    private fun resetSession() {
        rssiJob?.cancel(); rssiJob = null
        sessionKEnc = null
        sessionKMac = null
        myEphemeralPub65 = null
        myEphemeralPrivKey = null
        serverHelloListener = null
        serverCfmListener = null
        adminTxSeq.set(0)
        adminRxSeq = -1
        _state.value = _state.value.copy(
            session = SessionState.NONE,
            rssi = 0,
            proximityUnlocked = false,
            uwbDistance = -1f,
            uwbEnabled = false
        )
    }

    private fun updateConn(conn: ConnState) {
        _state.value = _state.value.copy(conn = conn)
    }

    private fun updateAdminInfo(msg: String) {
        // Parse UWB distance: "UWB:1.23m"
        val uwbMatch = Regex("""^UWB:([\d.]+)m$""").find(msg)
        if (uwbMatch != null) {
            val dist = uwbMatch.groupValues[1].toFloatOrNull() ?: -1f
            _state.value = _state.value.copy(adminInfo = msg, uwbDistance = dist)
            return
        }
        // Parse UWB state events
        when (msg) {
            "UWB:ENABLED"  -> { _state.value = _state.value.copy(adminInfo = msg, uwbEnabled = true);  return }
            "UWB:DISABLED" -> { _state.value = _state.value.copy(adminInfo = msg, uwbEnabled = false, uwbDistance = -1f); return }
            "UWB:TAG_LOST" -> { _state.value = _state.value.copy(adminInfo = msg, uwbDistance = -1f);  return }
        }
        _state.value = _state.value.copy(adminInfo = msg)
    }

    /** Gửi CMD_UWB_ENABLE lên ECU — gọi sau khi BLE handshake READY */
    fun enableUwb() {
        scope.launch {
            try {
                writeChar(ADMIN_SERVICE_UUID, CHAR_ADMIN_CMD, buildSecureAdminCmd(CMD_UWB_ENABLE))
                log("UWB enable sent")
            } catch (e: Exception) {
                log("UWB enable failed: ${e.message}")
            }
        }
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        val logs = (_state.value.log + msg).takeLast(50)
        _state.value = _state.value.copy(log = logs)
    }
}
