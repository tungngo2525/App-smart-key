package com.example.smartkey.ui.vehicles

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.smartkey.data.SharedVehicle
import com.example.smartkey.data.Vehicle
import com.example.smartkey.ui.common.SecurityVerificationDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehiclesScreen(
    vm: VehicleListViewModel = viewModel(),
    onVehicleClick: (Vehicle) -> Unit = {},
    title: String = "Xe của tôi",
    showOwned: Boolean = true,
    showShared: Boolean = true,
    allowAdd: Boolean = true,
    enableShareAction: Boolean = true,
    showTopBar: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    var showAdd by remember { mutableStateOf(false) }
    var vehicleForShare by remember { mutableStateOf<Vehicle?>(null) }
    var pendingShare by remember { mutableStateOf<PendingShareRequest?>(null) }
    // ID của xe mà người dùng đã bấm "Để sau" trong session này
    var dismissedSetupId by remember { mutableStateOf<String?>(null) }

    // Tự động hiển thị dialog setup cho vehicle đầu tiên cần setup (chưa bị dismiss)
    val vehicleNeedsSetup = ui.owned.firstOrNull { it.needsSetup && it.id != dismissedSetupId }

    Scaffold(
        topBar = {
            if (showTopBar) CenterAlignedTopAppBar(title = { Text(title) })
        },
        floatingActionButton = {
            if (allowAdd) {
                FloatingActionButton(onClick = { showAdd = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Thêm xe")
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(contentPadding)
        ) {
            when {
                ui.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                showOwned && ui.owned.isEmpty() && (!showShared || ui.shared.isEmpty()) -> Text(
                    "Chưa có xe. Nhấn + để thêm.",
                    modifier = Modifier.align(Alignment.Center)
                )
                showShared && !showOwned && ui.shared.isEmpty() -> Text(
                    "Chưa có xe được chia sẻ.",
                    modifier = Modifier.align(Alignment.Center)
                )
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (showOwned && ui.owned.isNotEmpty()) {
                            item {
                                Text(
                                    "Xe của bạn",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                            items(ui.owned, key = { it.id }) { v ->
                                VehicleRow(
                                    v = v,
                                    onClick = { onVehicleClick(v) },
                                    onDelete = { vm.delete(v.id) },
                                    onShare = { vehicleForShare = v },
                                    sharing = ui.sharesByVehicle[v.id].orEmpty(),
                                    onRevokeShare = { share -> vm.revokeShare(share) },
                                    showShareButton = enableShareAction
                                )
                            }
                        }
                        if (showShared && ui.shared.isNotEmpty()) {
                            item {
                                Text(
                                    "Được chia sẻ",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                            items(ui.shared, key = { it.shareId }) { shared ->
                                SharedVehicleRow(shared = shared, onClick = {
                                    val veh = Vehicle(
                                        id = shared.vehicleId,
                                        name = shared.name,
                                        plate = shared.plate,
                                        tbDeviceId = shared.tbDeviceId,
                                        ecuVehicleId = shared.ecuVehicleId,
                                        location = shared.location,
                                        isActive = shared.isActive,
                                        latitude = shared.latitude,
                                        longitude = shared.longitude
                                    )
                                    onVehicleClick(veh)
                                })
                            }
                        }
                    }
                }
            }

            ui.error?.let {
                Snackbar(
                    action = { TextButton(onClick = { vm.clearError() }) { Text("Đóng") } },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                ) { Text(it) }
            }
            ui.message?.let {
                Snackbar(
                    action = { TextButton(onClick = { vm.clearMessage() }) { Text("OK") } },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                ) { Text(it) }
            }
        }
    }

    // Dialog thiết lập xe mới từ ECU (tự động hiện khi detect needsSetup=true)
    vehicleNeedsSetup?.let { vehicle ->
        SetupVehicleDialog(
            vehicle = vehicle,
            onDismiss = { dismissedSetupId = vehicle.id },
            onSubmit = { vehicleId, name, plate, location ->
                vm.completeSetup(vehicleId, name, plate, location)
            }
        )
    }

    if (allowAdd && showAdd) {
        AddVehicleDialog(
            onDismiss = { showAdd = false },
            onSubmit = { name, plate, tbId, location, isActive, lat, lng ->
                vm.add(name, plate, tbId, location, isActive, lat, lng)
                showAdd = false
            }
        )
    }

    if (enableShareAction) {
        vehicleForShare?.let { vehicle ->
            ShareVehicleDialog(
                vehicle = vehicle,
                onDismiss = { vehicleForShare = null },
                onSubmit = { email, hours, unlimited, permission ->
                    pendingShare = PendingShareRequest(vehicle, email, hours, unlimited, permission)
                    vehicleForShare = null
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
                vm.shareVehicleSecure(
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
}

@Composable
private fun VehicleRow(
    v: Vehicle,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    sharing: List<SharedVehicle>,
    onRevokeShare: (SharedVehicle) -> Unit,
    showShareButton: Boolean
) {
    val brush = remember(v.isActive) { Brush.linearGradient(vehicleCardColors(v.isActive)) }
    ElevatedCard(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .background(brush)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            v.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Biển số: ${v.plate}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (v.ecuVehicleId.isNotBlank()) {
                            Text(
                                "🔑 ECU ID: ${v.ecuVehicleId}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                "⚠️ Chưa liên kết với ECU (tap NFC để liên kết)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (v.location.isNotBlank()) {
                            Text(
                                "Vị trí: ${v.location}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (v.tbDeviceId.isNotBlank()) {
                            Text(
                                "TB Device: ${v.tbDeviceId}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    CardChipDecoration()
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    StatusChip(isActive = v.isActive)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (v.isActive) "Khóa thông minh sẵn sàng" else "Đang khóa",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (showShareButton) {
                        VehicleActionChip(
                            label = "Chia sẻ",
                            icon = Icons.Default.Share,
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            contentColor = MaterialTheme.colorScheme.primary,
                            onClick = onShare
                        )
                    }
                    VehicleActionChip(
                        label = "Xóa xe",
                        icon = Icons.Default.Delete,
                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                        contentColor = MaterialTheme.colorScheme.error,
                        onClick = onDelete
                    )
                }
                if (sharing.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Đang chia sẻ", style = MaterialTheme.typography.bodyMedium)
                        sharing.forEach { share ->
                            ShareRecipientRow(
                                share = share,
                                onRevoke = { onRevokeShare(share) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SharedVehicleRow(shared: SharedVehicle, onClick: () -> Unit) {
    val brush = remember { Brush.linearGradient(sharedCardColors) }
    ElevatedCard(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .background(brush)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(shared.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Biển số: ${shared.plate}", style = MaterialTheme.typography.bodyMedium)
                        if (shared.ecuVehicleId.isNotBlank()) {
                            Text(
                                "🔑 ECU ID: ${shared.ecuVehicleId}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                            )
                        }
                        Text(
                            "Chủ xe: ${shared.ownerUsername.ifBlank { "Không rõ" }}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text("Hết hạn: ${formatExpiration(shared.expiresAt)}", style = MaterialTheme.typography.bodySmall)
                    }
                    CardChipDecoration()
                }
                StatusChip(isActive = shared.isActive)
            }
        }
    }
}

@Composable
private fun ShareRecipientRow(
    share: SharedVehicle,
    onRevoke: () -> Unit
) {
    val container = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(container)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(share.granteeIdentifier, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text("Hết hạn: ${formatExpiration(share.expiresAt)}", style = MaterialTheme.typography.bodySmall)
        }
        IconButton(
            onClick = onRevoke,
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Icon(Icons.Default.Delete, contentDescription = "Thu hồi")
        }
    }
}

@Composable
private fun VehicleActionChip(
    label: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(40))
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = contentColor)
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            color = contentColor,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun CardChipDecoration() {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .width(56.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.35f))
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.35f))
            )
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.15f))
            )
        }
    }
}

private fun vehicleCardColors(isActive: Boolean): List<Color> =
    if (isActive) activeVehicleGradient else inactiveVehicleGradient

private val activeVehicleGradient = listOf(
    Color(0xFFEAF1FF),
    Color(0xFFD6E4FF),
    Color(0xFFCADBFF)
)

private val inactiveVehicleGradient = listOf(
    Color(0xFFF3F3F5),
    Color(0xFFE7E7EB)
)

private val sharedCardColors = listOf(
    Color(0xFFEFF5FF),
    Color(0xFFDCEAFF)
)

@Composable
private fun SetupVehicleDialog(
    vehicle: Vehicle,
    onDismiss: () -> Unit,
    onSubmit: (vehicleId: String, name: String, plate: String, location: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var plate by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("❗ Thiết lập xe mới") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Phát hiện xe mới từ ECU:\n🔑 ID: ${vehicle.ecuVehicleId.take(16)}...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Tên xe *") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
                OutlinedTextField(
                    value = plate,
                    onValueChange = { plate = it },
                    label = { Text("Biển số *") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("Vị trí xe (VD: Nhà, Công ty) *") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && plate.isNotBlank() && location.isNotBlank(),
                onClick = {
                    onSubmit(vehicle.id, name.trim(), plate.trim(), location.trim())
                }
            ) { Text("Lưu") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Để sau") } }
    )
}

@Composable
private fun AddVehicleDialog(
    onDismiss: () -> Unit,
    onSubmit: (String, String, String, String, Boolean, Double, Double) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var plate by remember { mutableStateOf("") }
    var tbId by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var isActive by remember { mutableStateOf(true) }
    var latitude by remember { mutableStateOf("") }
    var longitude by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Thêm xe mới") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Tên xe") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
                OutlinedTextField(
                    value = plate,
                    onValueChange = { plate = it },
                    label = { Text("Biển số") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
                OutlinedTextField(
                    value = tbId,
                    onValueChange = { tbId = it },
                    label = { Text("ThingsBoard Device ID (tùy chọn)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("Vị trí xe") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
                OutlinedTextField(
                    value = latitude,
                    onValueChange = { latitude = it.filter { ch -> ch.isDigit() || ch == '.' || ch == '-' } },
                    label = { Text("Latitude (VD: 10.762622)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
                )
                OutlinedTextField(
                    value = longitude,
                    onValueChange = { longitude = it.filter { ch -> ch.isDigit() || ch == '.' || ch == '-' } },
                    label = { Text("Longitude") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Trạng thái hoạt động")
                    Spacer(Modifier.weight(1f))
                    Switch(checked = isActive, onCheckedChange = { isActive = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && plate.isNotBlank() && location.isNotBlank(),
                onClick = {
                    onSubmit(
                        name.trim(),
                        plate.trim(),
                        tbId.trim(),
                        location.trim(),
                        isActive,
                        latitude.toDoubleOrNull() ?: 0.0,
                        longitude.toDoubleOrNull() ?: 0.0
                    )
                }
            ) { Text("Lưu") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } }
    )
}

@Composable
private fun StatusChip(isActive: Boolean) {
    val background = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val textColor = MaterialTheme.colorScheme.onPrimary
    val text = if (isActive) "Đang hoạt động" else "Ngừng hoạt động"
    Surface(
        color = background,
        shape = RoundedCornerShape(50),
        modifier = Modifier.padding(top = 4.dp)
    ) {
        Text(
            text,
            color = textColor,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

private fun formatExpiration(expiresAt: Long): String {
    if (expiresAt <= 0) return "Không giới hạn"
    return try {
        val date = java.util.Date(expiresAt)
        val formatter = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
        formatter.format(date)
    } catch (e: Exception) {
        "Không rõ"
    }
}

private data class PendingShareRequest(
    val vehicle: Vehicle,
    val email: String,
    val hours: Int,
    val unlimited: Boolean,
    val permission: SharePermissionOption
)
