package com.example.smartkey.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * BootReceiver — tự động khởi động BleAutoConnectService sau khi thiết bị boot.
 * Theo chuẩn CCC: passive entry phải hoạt động ngay khi bật máy, không cần mở app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return

        Log.i("BootReceiver", "Boot completed — starting BleAutoConnectService")
        val svc = BleAutoConnectService.startIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc)
        } else {
            context.startService(svc)
        }
    }
}
