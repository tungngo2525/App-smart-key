package com.example.smartkey.ui.vehicles

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.viewmodel.compose.viewModel
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
    val shares by shareRepository.sharesOfVehicle(vehicleId).collectAsState(initial = emptyList())
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val vehiclesVm: VehicleListViewModel = viewModel()
    var showShareDialog by remember { mutableStateOf(false) }
    var pendingShare by remember { mutableStateOf<DetailShareRequest?>(null) }
    var pendingLockAction by remember { mutableStateOf<LockAction?>(null) }
    var lockInProgress by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
                    onShare = { showShareDialog = true },
                    onLock = { pendingLockAction = LockAction.Lock },
                    onUnlock = { pendingLockAction = LockAction.Unlock },
                    lockInProgress = lockInProgress
                )
                VehicleLocationCard(vehicle = current, context = context)
                HceSection(vehicle = current, token = token, context = context)
                AccessorsSection(
                    shares = shares,
                    onRevoke = { share -> vehiclesVm.revokeShare(share) }
                )
                Spacer(Modifier.height(8.dp))
            } ?: Text("Đang tải thông tin xe...", style = MaterialTheme.typography.bodyMedium)
        }
    }

    vehicle?.let { current ->
        if (showShareDialog) {
            ShareVehicleDialog(
                vehicle = current,
                onDismiss = { showShareDialog = false },
                onSubmit = { email, hours, unlimited ->
                    pendingShare = DetailShareRequest(current, email, hours, unlimited)
                    showShareDialog = false
                }
            )
        }
    }

    pendingShare?.let { request ->
        SecurityVerificationDialog(
            title = "Xác thực chia sẻ xe",
            description = if (request.unlimited) {
                "Chia sẻ không giới hạn cho ${request.email}. Nhập lại mật khẩu và xác nhận vân tay."
            } else {
                "Nhập lại mật khẩu và xác nhận vân tay trước khi chia sẻ xe cho ${request.email}."
            },
            confirmLabel = "Chia sẻ",
            onDismiss = { pendingShare = null },
            onVerified = { password ->
                vehiclesVm.shareVehicleSecure(
                    vehicle = request.vehicle,
                    targetEmail = request.email,
                    durationHours = request.hours,
                    unlimited = request.unlimited,
                    password = password
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
                    lockInProgress = true
                    scope.launch {
                        try {
                            val setActive = action == LockAction.Unlock
                            if (!setActive && password.isNotBlank()) {
                                SecurityService.reauthenticate(password)
                            }
                            repo.setVehicleActive(current.id, setActive)
                            if (setActive) {
                                val prefs = context.getSharedPreferences("hce_prefs", Context.MODE_PRIVATE)
                                prefs.edit()
                                    .putString("vehicle_id", current.id)
                                    .putString("vehicle_token", token)
                                    .putString("pending_action", "2") // 2 = UNLOCK -> LED ON
                                    .apply()
                                Toast.makeText(context, "Đã gửi thông tin mở khóa qua HCE", Toast.LENGTH_SHORT).show()
                            } else {
                                val prefs = context.getSharedPreferences("hce_prefs", Context.MODE_PRIVATE)
                                prefs.edit()
                                    .putString("pending_action", "1") // 1 = LOCK -> LED OFF
                                    .apply()
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
        Text("AID hiện tại: F00102030405", style = MaterialTheme.typography.bodyMedium)
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
            modifier = Modifier.weight(1f)
        ) {
            Text("Chia sẻ")
        }
        Button(
            onClick = onLock,
            enabled = isActive && !lockInProgress,
            modifier = Modifier.weight(1f)
        ) {
            Text("Khóa xe")
        }
        Button(
            onClick = onUnlock,
            enabled = !isActive && !lockInProgress,
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
    val unlimited: Boolean
)
