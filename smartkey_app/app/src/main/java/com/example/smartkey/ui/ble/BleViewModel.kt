package com.example.smartkey.ui.ble

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import com.example.smartkey.ble.BleAutoConnectService
import com.example.smartkey.ble.BleManager

class BleViewModel(app: Application) : AndroidViewModel(app) {

    private val bleManager = BleManager.get(app.applicationContext)
    val state = bleManager.state

    /** Bật service → tự scan + connect + handshake, không cần app mở */
    fun startAutoConnect() {
        val ctx = getApplication<Application>().applicationContext
        val intent = BleAutoConnectService.startIntent(ctx)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
    }

    /** Tắt service và ngắt BLE */
    fun stopAutoConnect() {
        val ctx = getApplication<Application>().applicationContext
        ctx.startService(BleAutoConnectService.stopIntent(ctx))
    }

    fun sendCmd(cmd: Byte) = bleManager.sendAdminCommand(cmd)

    fun queryStatus() = sendCmd(BleManager.CMD_STATUS)
    fun triggerNfcProvision() = sendCmd(BleManager.CMD_NFC_PROVISION)
    fun forcePersistOn() = sendCmd(BleManager.CMD_FORCE_PERSIST_ON)
    fun forcePersistOff() = sendCmd(BleManager.CMD_FORCE_PERSIST_OFF)
    fun forceOneShot() = sendCmd(BleManager.CMD_FORCE_ONESHOT)

}
