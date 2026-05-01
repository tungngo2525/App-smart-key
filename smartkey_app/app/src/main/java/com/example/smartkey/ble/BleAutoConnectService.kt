package com.example.smartkey.ble

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.example.smartkey.MainActivity
import com.example.smartkey.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * BleAutoConnectService — CCC Passive Entry background service.
 *
 * Theo chuẩn CCC Digital Key R3:
 * - Chạy ngầm dưới dạng Foreground Service, không cần mở app
 * - Tự động scan → kết nối → ECDH handshake khi vào vùng phủ sóng
 * - Tự reconnect khi mất kết nối
 * - Start on boot qua BootReceiver
 *
 * Điều khiển:
 *   startService(Intent(ctx, BleAutoConnectService::class.java).also { it.action = ACTION_START })
 *   startService(Intent(ctx, BleAutoConnectService::class.java).also { it.action = ACTION_STOP })
 */
class BleAutoConnectService : Service() {

    companion object {
        private const val TAG = "BleAutoConnectService"
        private const val CHANNEL_ID = "ble_auto_connect"
        private const val NOTIF_ID = 1001

        const val ACTION_START = "com.example.smartkey.BLE_START"
        const val ACTION_STOP  = "com.example.smartkey.BLE_STOP"

        fun startIntent(context: Context) =
            Intent(context, BleAutoConnectService::class.java).apply { action = ACTION_START }

        fun stopIntent(context: Context) =
            Intent(context, BleAutoConnectService::class.java).apply { action = ACTION_STOP }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var bleManager: BleManager

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        bleManager = BleManager.get(applicationContext)

        // Khi kết nối thành công → tự động chạy ECDH handshake
        bleManager.onConnectedCallback = {
            Log.i(TAG, "Connected — starting auto handshake")
            bleManager.runAuthHandshake()
        }

        // Theo dõi state để cập nhật notification
        bleManager.state.onEach { s ->
            updateNotification(s)
        }.launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stop requested")
                bleManager.disableAutoConnect()
                bleManager.onConnectedCallback = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> { // ACTION_START or boot
                if (!hasBlePermissions()) {
                    Log.w(TAG, "BLE permissions not granted — not starting scan")
                    stopSelf()
                    return START_NOT_STICKY
                }
                Log.i(TAG, "Start: enabling auto-connect")
                startForeground(NOTIF_ID, buildNotification("Đang tìm xe…"))
                bleManager.enableAutoConnect()
            }
        }
        // Sticky: Android restart service nếu bị kill
        return START_STICKY
    }

    override fun onDestroy() {
        bleManager.onConnectedCallback = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    // --- Notification ---

    private fun updateNotification(s: BleManager.BleState) {
        val text = when {
            s.session == BleManager.SessionState.READY ->
                "Đã xác thực — ${BleManager.DEVICE_NAME}"
            s.conn == BleManager.ConnState.CONNECTED &&
            s.session == BleManager.SessionState.HANDSHAKING ->
                "Đang xác thực ECDH…"
            s.conn == BleManager.ConnState.CONNECTED ->
                "Đã kết nối — ${BleManager.DEVICE_NAME}"
            s.conn == BleManager.ConnState.SCANNING ||
            s.conn == BleManager.ConnState.CONNECTING ->
                "Đang tìm xe…"
            s.autoConnect ->
                "Chờ tín hiệu BLE…"
            else -> "Dịch vụ BLE đã tắt"
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("SmartKey BLE")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(tapIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BLE Auto-Connect",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Kết nối BLE tự động với xe theo chuẩn CCC"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }
}
