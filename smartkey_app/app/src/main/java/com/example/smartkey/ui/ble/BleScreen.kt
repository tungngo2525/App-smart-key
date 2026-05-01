package com.example.smartkey.ui.ble

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.smartkey.ble.BleManager

@Composable
fun BleScreen(
    vm: BleViewModel = viewModel(),
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val state by vm.state.collectAsState()

    // Yêu cầu quyền BLE rồi mới start service
    val blePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) vm.startAutoConnect()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Status card
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status dot
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            when (state.conn) {
                                BleManager.ConnState.CONNECTED -> Color(0xFF2E7D32)
                                BleManager.ConnState.SCANNING,
                                BleManager.ConnState.CONNECTING -> Color(0xFFE65100)
                                    else -> if (state.autoConnect) Color(0xFF1565C0) else MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    when (state.conn) {
                        BleManager.ConnState.SCANNING,
                        BleManager.ConnState.CONNECTING -> CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        BleManager.ConnState.CONNECTED -> Icon(
                            Icons.Default.BluetoothConnected, null,
                            tint = Color.White, modifier = Modifier.size(24.dp)
                        )
                        BleManager.ConnState.DISCONNECTED -> Icon(
                            Icons.Default.BluetoothDisabled, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                        else -> Icon(
                            Icons.Default.Bluetooth, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column {
                    Text(
                        text = when (state.conn) {
                            BleManager.ConnState.IDLE -> "Chưa kết nối"
                            BleManager.ConnState.SCANNING -> "Đang quét BLE…"
                            BleManager.ConnState.CONNECTING -> "Đang kết nối GATT…"
                            BleManager.ConnState.CONNECTED -> "Đã kết nối — ${BleManager.DEVICE_NAME}"
                            BleManager.ConnState.DISCONNECTED ->
                                if (state.autoConnect) "Mất kết nối — đang thử lại…" else "Mất kết nối"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = when (state.session) {
                            BleManager.SessionState.NONE -> "Chưa xác thực"
                            BleManager.SessionState.HANDSHAKING -> "Đang handshake ECDH (Phase B)…"
                            BleManager.SessionState.READY -> "🔐 Session CCC sẵn sàng"
                            BleManager.SessionState.FAILED -> "❌ Xác thực thất bại"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (state.session) {
                            BleManager.SessionState.READY -> Color(0xFF2E7D32)
                            BleManager.SessionState.FAILED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }

        // Admin info
        if (state.adminInfo.isNotBlank()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = state.adminInfo,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Connect / Disconnect
        // CCC Passive Entry toggle
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "CCC Passive Entry",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (state.autoConnect)
                            "Tự động kết nối khi gần xe"
                        else
                            "Bật để kết nối ngầm không cần mở app",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                if (state.autoConnect) {
                    Button(
                        onClick = { vm.stopAutoConnect() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Stop, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Tắt")
                    }
                } else {
                    Button(
                        onClick = { permissionLauncher.launch(blePermissions) }
                    ) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Bật")
                    }
                }
            }
        }

        // Admin commands — chỉ hiện khi đã connect
        if (state.conn == BleManager.ConnState.CONNECTED) {
            Text(
                "Lệnh Admin",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            AdminCommandGrid(vm)
        }

        // Log
        Spacer(Modifier.height(4.dp))
        Text("Log", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        LogView(logs = state.log, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AdminCommandGrid(vm: BleViewModel) {
    val commands = listOf(
        "Trạng thái" to { vm.queryStatus() },
        "NFC Provisioning" to { vm.triggerNfcProvision() },
        "Force ON" to { vm.forcePersistOn() },
        "Force OFF" to { vm.forcePersistOff() },
        "One-shot" to { vm.forceOneShot() }
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        commands.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (label, action) ->
                    FilledTonalButton(
                        onClick = action,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(label, fontSize = 12.sp, maxLines = 1)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LogView(logs: List<String>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(logs) { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
