package com.example.smartkey.ui.vehicles

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.PinDrop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smartkey.data.ShareRepository
import com.example.smartkey.data.SharedVehicle
import com.example.smartkey.data.Vehicle
import com.example.smartkey.data.VehicleRepository
import com.example.smartkey.security.SecurityService
import com.example.smartkey.ui.common.SecurityVerificationDialog
import com.example.smartkey.ble.BleAutoConnectService
import com.example.smartkey.ble.BleManager
import com.example.smartkey.ui.ble.BleViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
fun VehicleDetailScreen(
    vehicleId: String,
    token: String,
    onBack: () -> Unit,
    repo: VehicleRepository = VehicleRepository(),
    shareRepository: ShareRepository = ShareRepository()
) {
    val vehicle by repo.vehicleFlow(vehicleId).collectAsState(initial = null)
    val context = LocalContext.current
    val currentUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
    val mySharesFlow = remember(currentUid) {
        if (currentUid.isBlank()) flowOf(emptyList()) else shareRepository.sharesFor(currentUid)
    }
    val myShares by mySharesFlow.collectAsState(initial = emptyList())
    val myShare = myShares.firstOrNull { it.vehicleId == vehicleId }
    val isOwner = vehicle?.ownerUid == currentUid
    val canAdminister = isOwner || (myShare?.canAdmin == true)
    val sharesFlow = remember(vehicleId, canAdminister) {
        if (canAdminister) shareRepository.sharesOfVehicle(vehicleId) else flowOf(emptyList())
    }
    val shares by sharesFlow.collectAsState(initial = emptyList())
    val scrollState = rememberScrollState()
    val vehiclesVm: VehicleListViewModel = viewModel()
    val bleVm: BleViewModel = viewModel()
    var showShareDialog by remember { mutableStateOf(false) }
    var pendingShare by remember { mutableStateOf<DetailShareRequest?>(null) }
    var pendingLockAction by remember { mutableStateOf<LockAction?>(null) }
    var lockInProgress by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Pre-cache vehicle isActive so HCE can do tap-to-act without requiring explicit Lock/Unlock press
    LaunchedEffect(vehicle) {
        vehicle?.let { v ->
            val ecuId = v.ecuVehicleId.trim().uppercase().replace(Regex("[^0-9A-F]"), "")
            context.getSharedPreferences("hce_prefs", Context.MODE_PRIVATE).edit()
                .putBoolean("cached_vehicle_is_active", v.isActive)
                .putString("cached_vehicle_ecu_id", ecuId)
                .apply()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(scrollState)
                .padding(vertical = 18.dp, horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            TextButton(onClick = onBack) { Text("← Quay lại") }

            vehicle?.let { current ->
                VehicleHeroCard(current)
                ActionButtonsRow(
                    isActive = current.isActive,
                    canShare = canAdminister,
                    canLock = isOwner || (myShare?.canLock == true),
                    canUnlock = isOwner || (myShare?.canUnlock == true),
                    onShare = { showShareDialog = true },
                    onLock = { pendingLockAction = LockAction.Lock },
                    onUnlock = { pendingLockAction = LockAction.Unlock },
                    lockInProgress = lockInProgress
                )
                VehicleLocationCard(vehicle = current, context = context)
                HceSection(vehicle = current, token = token, context = context)
                BleSection(vehicle = current, context = context, bleVm = bleVm)
                if (canAdminister) {
                    AccessorsSection(
                        shares = shares,
                        onRevoke = { share -> vehiclesVm.revokeShare(share) }
                    )
                } else {
                    MyAccessSection(myShare)
                }
                Spacer(Modifier.height(8.dp))
            } ?: Text("Đang tải thông tin xe...", style = MaterialTheme.typography.bodyMedium)
        }
    }

    vehicle?.let { current ->
        if (showShareDialog) {
            ShareVehicleDialog(
                vehicle = current,
                onDismiss = { showShareDialog = false },
                onSubmit = { email, hours, unlimited, permission ->
                    pendingShare = DetailShareRequest(current, email, hours, unlimited, permission)
                    showShareDialog = false
                }
            )
        }
    }

    pendingShare?.let { request ->
        SecurityVerificationDialog(
            title = "Xác thực chia sẻ xe",
            description = if (request.unlimited) {
                "Chia sẻ ${request.permission.title.lowercase()} không giới hạn cho ${request.email}. Nhập lại mật khẩu và xác nhận vân tay."
            } else {
                "Nhập lại mật khẩu và xác nhận vân tay trước khi chia sẻ ${request.permission.title.lowercase()} cho ${request.email}."
            },
            confirmLabel = "Chia sẻ",
            onDismiss = { pendingShare = null },
            onVerified = { password ->
                vehiclesVm.shareVehicleSecure(
                    vehicle = request.vehicle,
                    targetEmail = request.email,
                    durationHours = request.hours,
                    unlimited = request.unlimited,
                    permissions = request.permission.value,
                    password = password,
                    appContext = context.applicationContext
                )
                pendingShare = null
            }
        )
    }

    vehicle?.let { current ->
        pendingLockAction?.let { action ->
            SecurityVerificationDialog(
                title = if (action == LockAction.Lock) "Khóa xe" else "Mở khóa xe",
                description = if (action == LockAction.Lock) {
                    "Xác thực vân tay để khóa ${current.name}. Sau khi quét thành công xe sẽ được khóa ngay."
                } else {
                    "Xác thực vân tay để mở khóa ${current.name}. Sau khi quét thành công xe sẽ được mở ngay."
                },
                confirmLabel = if (action == LockAction.Lock) "Khóa xe" else "Mở khóa",
                onDismiss = { pendingLockAction = null },
                requirePassword = false,
                requireBiometric = true,
                autoConfirmOnBiometric = true,
                onVerified = { password ->
                    val setActive = action == LockAction.Unlock
                    val prefs = context.getSharedPreferences("hce_prefs", Context.MODE_PRIVATE)
                    val nowMs = System.currentTimeMillis()
                    val ecuVehicleId = current.ecuVehicleId
                        .trim()
                        .uppercase()
                        .replace(Regex("[^0-9A-F]"), "")
                    val queuedOk = if (setActive) {
                        prefs.edit()
                            .putString("vehicle_id", current.id)
                            .putString("vehicle_token", token)
                            .putString("pending_action", "2") // 2 = UNLOCK -> LED ON
                            .putLong("pending_action_set_at_ms", nowMs)
                            .putString("pending_action_vehicle_ecu_id", ecuVehicleId)
                            .commit()
                    } else {
                        prefs.edit()
                            .putString("pending_action", "1") // 1 = LOCK -> LED OFF
                            .putLong("pending_action_set_at_ms", nowMs)
                            .putString("pending_action_vehicle_ecu_id", ecuVehicleId)
                            .commit()
                    }

                    if (!queuedOk) {
                        Toast.makeText(context, "Không thể queue lệnh NFC", Toast.LENGTH_SHORT).show()
                        pendingLockAction = null
                        return@SecurityVerificationDialog
                    }

                    lockInProgress = true
                    scope.launch {
                        try {
                            if (!setActive && password.isNotBlank()) {
                                SecurityService.reauthenticate(password)
                            }

                            repo.setVehicleActive(current.id, setActive)

                            if (setActive) {
                                Toast.makeText(context, "Đã gửi thông tin mở khóa qua HCE", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Đã khóa xe ${current.name}", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, e.message ?: "Thao tác thất bại", Toast.LENGTH_SHORT).show()
                        } finally {
                            lockInProgress = false
                            pendingLockAction = null
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun VehicleHeroCard(vehicle: Vehicle) {
    val gradient = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.secondary
        )
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .background(gradient)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.25f)) {
                    Icon(
                        imageVector = Icons.Rounded.DirectionsCar,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.padding(14.dp)
                    )
                }
                StatusChip(isActive = vehicle.isActive)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    vehicle.name.ifBlank { "Xe chưa đặt tên" },
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    vehicle.plate.ifBlank { "Biển số chưa cập nhật" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
            InfoRow(
                label = "Thiết bị TB",
                value = vehicle.tbDeviceId.ifBlank { "Chưa gán thiết bị" },
                labelColor = Color.White.copy(alpha = 0.7f),
                textColor = Color.White
            )
        }
    }
}

@Composable
private fun VehicleLocationCard(vehicle: Vehicle, context: Context) {
    val hasCoordinates = vehicle.latitude != 0.0 || vehicle.longitude != 0.0
    InfoSectionCard(title = "Vị trí xe", icon = Icons.Rounded.LocationOn) {
        InfoRow(label = "Địa điểm", value = vehicle.location.ifBlank { "Chưa cung cấp địa điểm" })
        InfoRow(
            label = "Tọa độ GPS",
            value = if (hasCoordinates) {
                "${formatCoordinate(vehicle.latitude)}, ${formatCoordinate(vehicle.longitude)}"
            } else {
                "Chưa cung cấp tọa độ"
            }
        )
        if (hasCoordinates) {
            TextButton(onClick = {
                val encodedQuery = if (vehicle.location.isNotBlank()) {
                    Uri.encode(vehicle.location)
                } else {
                    "${vehicle.latitude},${vehicle.longitude}"
                }
                val uri = Uri.parse("geo:${vehicle.latitude},${vehicle.longitude}?q=$encodedQuery")
                val mapIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.google.android.apps.maps")
                }
                if (mapIntent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(mapIntent)
                } else {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
            }) {
                Text("Mở trên bản đồ")
            }
        }
    }
}

@Composable
private fun HceSection(vehicle: Vehicle, token: String, context: Context) {
    var status by remember { mutableStateOf("Ready") }
    val prefs = context.getSharedPreferences("hce_prefs", Context.MODE_PRIVATE)
    var showSecurity by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun persist(password: String) {
        scope.launch {
            try {
                isSaving = true
                SecurityService.reauthenticate(password)
                prefs.edit()
                    .putString("vehicle_id", vehicle.id)
                    .putString("vehicle_token", token)
                    .apply()
                status = "Đã lưu cấu hình cho ${vehicle.name}"
                Toast.makeText(context, "Đã lưu thông tin HCE", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                status = "Xác thực thất bại"
                Toast.makeText(context, e.message ?: "Không thể lưu HCE", Toast.LENGTH_SHORT).show()
            } finally {
                isSaving = false
            }
        }
    }

    InfoSectionCard(title = "Cấu hình HCE", icon = Icons.Rounded.PinDrop) {
        Text("Xe: ${vehicle.name} • TB Token: ${token.takeIf { it.isNotBlank() } ?: "Chưa có"}")
        Text("AID: D27600008543434301 (CCC-style)", style = MaterialTheme.typography.bodyMedium)
        Text("Trạng thái: $status", style = MaterialTheme.typography.bodySmall)
        Button(
            enabled = !isSaving,
            onClick = { showSecurity = true }
        ) {
            Text(if (isSaving) "Đang lưu..." else "Lưu thông tin HCE")
        }
    }

    if (showSecurity) {
        SecurityVerificationDialog(
            title = "Xác thực cấu hình HCE",
            description = "Nhập lại mật khẩu và xác nhận vân tay trước khi lưu cấu hình HCE cho xe này.",
            confirmLabel = "Lưu cấu hình",
            onDismiss = { showSecurity = false },
            onVerified = { password ->
                showSecurity = false
                persist(password)
            }
        )
    }
}

@Composable
private fun BleSection(vehicle: Vehicle, context: Context, bleVm: BleViewModel) {
    val state by bleVm.state.collectAsState()

    // Lưu trạng thái auto-connect per-vehicle trong SharedPreferences
    val blePrefs = remember { context.getSharedPreferences("ble_prefs", Context.MODE_PRIVATE) }
    var autoConnect by remember { mutableStateOf(blePrefs.getBoolean("autoconnect_${vehicle.id}", false)) }

    // Đặt target device khi vào màn hình xe
    LaunchedEffect(vehicle.bleDeviceName) {
        BleManager.get(context).setTargetDevice(vehicle.bleDeviceName)
    }

    // Auto-start service nếu đã bật trước đó và xe đã được provisioned
    LaunchedEffect(vehicle.id, autoConnect) {
        if (autoConnect && vehicle.ecuVehicleId.isNotBlank()) {
            bleVm.startAutoConnect()
        }
    }

    // Permission launcher để yêu cầu quyền BLE khi bật toggle
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it }
        if (granted) {
            bleVm.startAutoConnect()
        } else {
            autoConnect = false
            blePrefs.edit().putBoolean("autoconnect_${vehicle.id}", false).apply()
        }
    }

    val connText = when (state.conn) {
        BleManager.ConnState.IDLE -> "Chưa kết nối"
        BleManager.ConnState.SCANNING -> "Đang quét ECU..."
        BleManager.ConnState.CONNECTING -> "Đang kết nối..."
        BleManager.ConnState.CONNECTED -> "Đã kết nối"
        BleManager.ConnState.DISCONNECTED -> "Mất kết nối"
        else -> "Không xác định"
    }
    val statusColor = when (state.conn) {
        BleManager.ConnState.CONNECTED -> android.graphics.Color.rgb(76, 175, 80)
        else -> null
    }

    InfoSectionCard(title = "BLE — Passive Entry", icon = Icons.Default.Bluetooth) {
        // Trạng thái kết nối
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        color = if (state.conn == BleManager.ConnState.CONNECTED)
                            Color(0xFF4CAF50)
                        else if (state.conn == BleManager.ConnState.SCANNING || state.conn == BleManager.ConnState.CONNECTING)
                            MaterialTheme.colorScheme.tertiary
                        else
                            MaterialTheme.colorScheme.outline,
                        shape = androidx.compose.foundation.shape.CircleShape
                    )
            )
            Text(connText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }

        // RSSI + passive entry state khi đang kết nối
        if (state.conn == BleManager.ConnState.CONNECTED && state.session == BleManager.SessionState.READY) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val rssiColor = when {
                    state.rssi >= -70 -> Color(0xFF4CAF50)  // xanh lá: đủ gần
                    state.rssi >= -80 -> Color(0xFFFF9800)  // cam: trung bình
                    else              -> MaterialTheme.colorScheme.outline
                }
                Icon(
                    imageVector = Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = rssiColor,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "RSSI: ${if (state.rssi != 0) "${state.rssi} dBm" else "—"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = rssiColor
                )
                if (state.proximityUnlocked) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "🔓 Passive Entry ON",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF4CAF50)
                    )
                }
            }

            // UWB distance row
            if (state.uwbEnabled || state.uwbDistance >= 0f) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val uwbColor = when {
                        state.uwbDistance in 0f..1.5f  -> Color(0xFF4CAF50)
                        state.uwbDistance in 1.5f..2.5f -> Color(0xFFFF9800)
                        state.uwbDistance > 2.5f        -> MaterialTheme.colorScheme.error
                        else                            -> MaterialTheme.colorScheme.outline
                    }
                    Icon(
                        imageVector = Icons.Default.Bluetooth,
                        contentDescription = null,
                        tint = uwbColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "UWB: ${if (state.uwbDistance >= 0f) "${"%.2f".format(state.uwbDistance)} m" else "—"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = uwbColor
                    )
                    if (state.uwbDistance in 0f..1.5f) {
                        Text(
                            text = "📡 Trong vùng",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF4CAF50)
                        )
                    }
                }
            }
        }

        // Toggle tự động kết nối
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Tự động kết nối nền", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    "ECU: ${vehicle.bleDeviceName.ifBlank { BleManager.DEFAULT_DEVICE_NAME }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = autoConnect,
                onCheckedChange = { enabled ->
                    if (enabled) {
                        autoConnect = true
                        blePrefs.edit().putBoolean("autoconnect_${vehicle.id}", true).apply()
                        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            arrayOf(
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            )
                        } else {
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                        permLauncher.launch(perms)
                    } else {
                        autoConnect = false
                        blePrefs.edit().putBoolean("autoconnect_${vehicle.id}", false).apply()
                        bleVm.stopAutoConnect()
                    }
                }
            )
        }

    
    }
}

@Composable
private fun MyAccessSection(share: SharedVehicle?) {
    InfoSectionCard(title = "Quyền của bạn", icon = Icons.Rounded.DirectionsCar) {
        if (share == null) {
            Text("Bạn đang xem xe được chia sẻ, nhưng ứng dụng chưa nhận được metadata quyền truy cập.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text("Quyền hiện tại: ${share.permissionLabel}", style = MaterialTheme.typography.bodyMedium)
            Text("Hiệu lực đến: ${formatExpiration(share.expiresAt)}", style = MaterialTheme.typography.bodySmall)
            Text(
                if (share.canAdmin) {
                    "Bạn có thể khóa, mở khóa và chia sẻ tiếp quyền truy cập này."
                } else {
                    "Bạn có thể thao tác xe trong phạm vi quyền được cấp; các tác vụ quản trị vẫn thuộc chủ xe."
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun AccessorsSection(
    shares: List<SharedVehicle>,
    onRevoke: ((SharedVehicle) -> Unit)? = null
) {
    InfoSectionCard(title = "Ai đang truy cập xe này", icon = Icons.Rounded.DirectionsCar) {
        if (shares.isEmpty()) {
            Text("Chỉ có bạn đang nắm quyền sử dụng xe này.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                shares.forEach { share ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(16.dp),
                        tonalElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("• ${share.granteeIdentifier}", fontWeight = FontWeight.Medium)
                            Text("Quyền: ${share.permissionLabel}", style = MaterialTheme.typography.bodySmall)
                            Text("Hết hạn: ${formatExpiration(share.expiresAt)}", style = MaterialTheme.typography.bodySmall)
                            if (onRevoke != null) {
                                TextButton(onClick = { onRevoke(share) }) {
                                    Text("Thu hồi quyền", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoSectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(10.dp))
                }
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    textColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = labelColor)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = textColor, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StatusChip(isActive: Boolean) {
    val container = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
    val textColor = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
    val label = if (isActive) "Đang hoạt động" else "Ngừng hoạt động"
    Surface(
        color = container.copy(alpha = 0.9f),
        shape = RoundedCornerShape(50),
        shadowElevation = 0.dp
    ) {
        Text(label, color = textColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
    }
}

private fun formatExpiration(expiresAt: Long): String {
    if (expiresAt <= 0) return "Không giới hạn"
    return try {
        val date = java.util.Date(expiresAt)
        val formatter = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
        formatter.format(date)
    } catch (_: Exception) {
        "Không rõ"
    }
}

private fun formatCoordinate(value: Double): String =
    String.format(java.util.Locale.getDefault(), "%.5f", value)

@Composable
private fun ActionButtonsRow(
    isActive: Boolean,
    canShare: Boolean,
    canLock: Boolean,
    canUnlock: Boolean,
    onShare: () -> Unit,
    onLock: () -> Unit,
    onUnlock: () -> Unit,
    lockInProgress: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = onShare,
            enabled = canShare,
            modifier = Modifier.weight(1f)
        ) {
            Text("Chia sẻ")
        }
        Button(
            onClick = onLock,
            enabled = canLock && isActive && !lockInProgress,
            modifier = Modifier.weight(1f)
        ) {
            Text("Khóa xe")
        }
        Button(
            onClick = onUnlock,
            enabled = canUnlock && !isActive && !lockInProgress,
            modifier = Modifier.weight(1f)
        ) {
            Text("Mở khóa")
        }
    }
}

private enum class LockAction { Lock, Unlock }

private data class DetailShareRequest(
    val vehicle: Vehicle,
    val email: String,
    val hours: Int,
    val unlimited: Boolean,
    val permission: SharePermissionOption
)
