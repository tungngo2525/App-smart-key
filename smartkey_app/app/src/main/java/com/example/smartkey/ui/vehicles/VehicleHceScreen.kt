package com.example.smartkey.ui.vehicles

import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun VehicleHceScreen(vehicleId: String, token: String) {
    val context = LocalContext.current
    val nfcAdapter = remember { NfcAdapter.getDefaultAdapter(context) }
    val nfcEnabled = remember { mutableStateOf(nfcAdapter?.isEnabled == true) }

    val PREFS_NAME = "hce_prefs"
    val PREF_VEHICLE_ID = "vehicle_id"
    val PREF_TOKEN = "vehicle_token"

    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    var statusText by remember { mutableStateOf("Ready") }

    // Lưu HCE cho xe đã chọn
    LaunchedEffect(vehicleId, token) {
        prefs.edit().putString(PREF_VEHICLE_ID, vehicleId)
            .putString(PREF_TOKEN, token)
            .apply()
        statusText = "Cấu hình HCE cho xe $vehicleId đã lưu. Đưa điện thoại gần reader để test."
    }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.Top
    ) {
        Text("HCE SmartKey cho xe: $vehicleId", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("NFC:", modifier = Modifier.weight(1f))
            Text(if (nfcEnabled.value) "Bật" else "Tắt")
            Spacer(Modifier.width(8.dp))
            Button(onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_NFC_SETTINGS)) }) {
                Text("Cài đặt NFC")
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("AID đang dùng: D27600008543434301 (CCC-style)", style = MaterialTheme.typography.bodyMedium)
        Text("Legacy AID: F00102030405 (IoT compat)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(8.dp))
        Text("Trạng thái: $statusText", style = MaterialTheme.typography.bodySmall)
    }
}
