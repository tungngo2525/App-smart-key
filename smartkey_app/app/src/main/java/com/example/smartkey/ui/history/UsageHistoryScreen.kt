package com.example.smartkey.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.smartkey.data.SharedVehicle
import com.example.smartkey.data.UsageBooking
import com.example.smartkey.data.UsageLog
import com.example.smartkey.data.Vehicle
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun UsageHistoryScreen(
    ownerVehicles: List<Vehicle>,
    unlimitedShared: List<SharedVehicle>,
    vm: UsageHistoryViewModel = viewModel()
) {
    val ui by vm.ui.collectAsState()
    val scheduleVehicles = remember(ownerVehicles, unlimitedShared) {
        buildList {
            ownerVehicles.forEach { vehicle ->
                add(
                    SchedulableVehicle(
                        id = vehicle.id,
                        name = vehicle.name.ifBlank { vehicle.plate },
                        ownerUid = vm.currentUserId.orEmpty(),
                        isShared = false
                    )
                )
            }
            unlimitedShared.forEach { shared ->
                add(
                    SchedulableVehicle(
                        id = shared.vehicleId,
                        name = shared.name.ifBlank { shared.plate },
                        ownerUid = shared.granteeUid,
                        isShared = true
                    )
                )
            }
        }
    }

    var selectedVehicleId by remember(scheduleVehicles) {
        mutableStateOf(scheduleVehicles.firstOrNull()?.id)
    }
    LaunchedEffect(scheduleVehicles) {
        if (scheduleVehicles.none { it.id == selectedVehicleId }) {
            selectedVehicleId = scheduleVehicles.firstOrNull()?.id
        }
    }

    val selectedVehicle = scheduleVehicles.firstOrNull { it.id == selectedVehicleId }
    var currentMonth by remember { mutableStateOf(YearMonth.now()) }
    var scheduleDate by remember { mutableStateOf<LocalDate?>(null) }
    var inspectDate by remember { mutableStateOf<LocalDate?>(null) }

    val bookingsByVehicle = remember(ui.bookings) { ui.bookings.groupBy { it.vehicleId } }
    val selectedBookings = selectedVehicle?.let { bookingsByVehicle[it.id] }.orEmpty()

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Quản lý lịch sử sử dụng",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        InfoBanner(
            visible = ui.error != null,
            text = ui.error.orEmpty(),
            color = MaterialTheme.colorScheme.errorContainer,
            onClose = vm::clearError
        )
        InfoBanner(
            visible = ui.message != null,
            text = ui.message.orEmpty(),
            color = MaterialTheme.colorScheme.secondaryContainer,
            onClose = vm::clearMessage
        )

        CalendarCard(
            vehicles = scheduleVehicles,
            selectedVehicleId = selectedVehicleId,
            onSelectVehicle = { selectedVehicleId = it },
            currentMonth = currentMonth,
            bookings = selectedBookings,
            onMonthChange = { currentMonth = it },
            onDayClick = { date, hasBooking ->
                if (selectedVehicle == null) return@CalendarCard
                if (hasBooking) {
                    inspectDate = date
                } else {
                    scheduleDate = date
                }
            }
        )

        BookingSection(
            bookings = ui.bookings.filter { it.endTime >= System.currentTimeMillis() },
            currentUid = vm.currentUserId.orEmpty(),
            onCancel = { vm.cancelBooking(it) }
        )

        UsageLogSection(logs = ui.logs)
    }

    val zoneId = remember { ZoneId.systemDefault() }
    val formatter = remember { DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.getDefault()) }
    if (scheduleDate != null && selectedVehicle != null) {
        UsageScheduleDialog(
            vehicle = selectedVehicle,
            date = scheduleDate!!,
            formatter = formatter,
            onDismiss = { scheduleDate = null },
            onSchedule = { startMillis, endMillis ->
                vm.scheduleUsage(
                    vehicleId = selectedVehicle.id,
                    vehicleName = selectedVehicle.name,
                    ownerUid = selectedVehicle.ownerUid,
                    startTime = startMillis,
                    endTime = endMillis
                )
                scheduleDate = null
            }
        )
    }
    if (inspectDate != null) {
        val dayBookings = selectedBookings.filter { it.isSameDay(inspectDate!!, zoneId) }
        DayBookingsDialog(
            date = inspectDate!!,
            bookings = dayBookings,
            formatter = formatter,
            onDismiss = { inspectDate = null }
        )
    }
}

@Composable
private fun CalendarCard(
    vehicles: List<SchedulableVehicle>,
    selectedVehicleId: String?,
    onSelectVehicle: (String) -> Unit,
    currentMonth: YearMonth,
    bookings: List<UsageBooking>,
    onMonthChange: (YearMonth) -> Unit,
    onDayClick: (LocalDate, Boolean) -> Unit
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Đặt lịch sử dụng", style = MaterialTheme.typography.titleMedium)
            if (vehicles.isEmpty()) {
                Text(
                    "Chưa có xe nào đủ điều kiện đặt lịch. Hãy thêm xe hoặc yêu cầu chủ xe chia sẻ không giới hạn.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                VehicleSelectorRow(
                    vehicles = vehicles,
                    selectedId = selectedVehicleId,
                    onSelect = onSelectVehicle
                )
                MonthHeader(currentMonth = currentMonth, onMonthChange = onMonthChange)
                CalendarGrid(
                    month = currentMonth,
                    bookings = bookings,
                    onDayClick = onDayClick
                )
            }
        }
    }
}

@Composable
private fun VehicleSelectorRow(
    vehicles: List<SchedulableVehicle>,
    selectedId: String?,
    onSelect: (String) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(vehicles) { vehicle ->
            val isSelected = vehicle.id == selectedId
            Surface(
                modifier = Modifier
                    .width(IntrinsicSize.Min)
                    .clickable { onSelect(vehicle.id) },
                shape = RoundedCornerShape(24.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = if (isSelected) 4.dp else 0.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        vehicle.name,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (vehicle.isShared) "Được chia sẻ" else "Chủ xe",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthHeader(currentMonth: YearMonth, onMonthChange: (YearMonth) -> Unit) {
    val formatter = remember { DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(onClick = { onMonthChange(currentMonth.minusMonths(1)) }) {
            Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Tháng trước")
        }
        Text(
            formatter.format(currentMonth.atDay(1)).replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        IconButton(onClick = { onMonthChange(currentMonth.plusMonths(1)) }) {
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Tháng sau")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CalendarGrid(
    month: YearMonth,
    bookings: List<UsageBooking>,
    onDayClick: (LocalDate, Boolean) -> Unit
) {
    val zoneId = remember { ZoneId.systemDefault() }
    val days = remember(month) { buildMonthDays(month) }
    val bookingMap = remember(bookings) {
        bookings.groupBy { Instant.ofEpochMilli(it.startTime).atZone(zoneId).toLocalDate() }
    }
    val today = remember { LocalDate.now() }

    LazyVerticalGrid(
        columns = GridCells.Fixed(7),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.height(320.dp)
    ) {
        items(days) { date ->
            if (date == null) {
                Box(modifier = Modifier.height(44.dp))
            } else {
                val dailyBookings = bookingMap[date].orEmpty()
                CalendarDayCell(
                    date = date,
                    isToday = date == today,
                    bookings = dailyBookings,
                    onClick = { onDayClick(date, dailyBookings.isNotEmpty()) }
                )
            }
        }
    }
}

@Composable
private fun CalendarDayCell(
    date: LocalDate,
    isToday: Boolean,
    bookings: List<UsageBooking>,
    onClick: () -> Unit
) {
    val hasBooking = bookings.isNotEmpty()
    val background = when {
        hasBooking -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        isToday -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    }
    Column(
        modifier = Modifier
            .height(44.dp)
            .background(background, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = date.dayOfMonth.toString(),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (hasBooking) {
                Spacer(Modifier.width(4.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = bookings.size.toString(),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
        if (hasBooking) {
            Text(
                text = bookings.first().requesterName,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else if (isToday) {
            Text(
                "Hôm nay",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun BookingSection(
    bookings: List<UsageBooking>,
    currentUid: String,
    onCancel: (String) -> Unit
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Lịch sắp diễn ra", style = MaterialTheme.typography.titleMedium)
            if (bookings.isEmpty()) {
                Text("Chưa có lịch nào trong tương lai.", style = MaterialTheme.typography.bodySmall)
            } else {
                bookings.forEachIndexed { index, booking ->
                    BookingRow(
                        booking = booking,
                        canCancel = booking.requesterUid == currentUid,
                        onCancel = onCancel
                    )
                    if (index != bookings.lastIndex) Divider()
                }
            }
        }
    }
}

@Composable
private fun BookingRow(
    booking: UsageBooking,
    canCancel: Boolean,
    onCancel: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(booking.vehicleName, fontWeight = FontWeight.SemiBold)
        Text("Người đặt: ${booking.requesterName}", style = MaterialTheme.typography.bodySmall)
        Text(
            "Thời gian: ${formatDateTime(booking.startTime)} - ${formatDateTime(booking.endTime)}",
            style = MaterialTheme.typography.bodySmall
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            StatusBadge(text = booking.status.name.lowercase().replaceFirstChar { it.titlecase(Locale.getDefault()) })
            Spacer(Modifier.weight(1f))
            if (canCancel) {
                IconButton(onClick = { onCancel(booking.id) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Hủy lịch")
                }
            }
        }
    }
}

@Composable
private fun UsageLogSection(logs: List<UsageLog>) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Nhật ký gần đây", style = MaterialTheme.typography.titleMedium)
            if (logs.isEmpty()) {
                Text("Chưa có dữ liệu mở/đóng xe.", style = MaterialTheme.typography.bodySmall)
            } else {
                logs.take(20).forEachIndexed { index, log ->
                    UsageLogRow(log = log)
                    if (index != 19 && index != logs.lastIndex) Divider()
                }
            }
        }
    }
}

@Composable
private fun UsageLogRow(log: UsageLog) {
    val icon = if (log.action == com.example.smartkey.data.UsageAction.OPEN) Icons.Default.LockOpen else Icons.Default.Lock
    val brush = if (log.action == com.example.smartkey.data.UsageAction.OPEN) {
        Brush.horizontalGradient(openGradient)
    } else {
        Brush.horizontalGradient(closeGradient)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(brush, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = Color.White.copy(alpha = 0.2f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.padding(8.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                log.vehicleName,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${formatDateTime(log.timestamp)} • ${log.actorName}",
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun StatusBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun UsageScheduleDialog(
    vehicle: SchedulableVehicle,
    date: LocalDate,
    formatter: DateTimeFormatter,
    onDismiss: () -> Unit,
    onSchedule: (Long, Long) -> Unit
) {
    var startText by remember {
        mutableStateOf(formatter.format(date.atTime(8, 0)))
    }
    var endText by remember {
        mutableStateOf(formatter.format(date.atTime(9, 0)))
    }
    var validationError by remember { mutableStateOf<String?>(null) }

    fun parse(text: String): LocalDateTime? =
        runCatching { LocalDateTime.parse(text, formatter) }.getOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Đặt lịch sử dụng") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Xe: ${vehicle.name}", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = startText,
                    onValueChange = { startText = it },
                    label = { Text("Bắt đầu (dd/MM/yyyy HH:mm)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = endText,
                    onValueChange = { endText = it },
                    label = { Text("Kết thúc (dd/MM/yyyy HH:mm)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth()
                )
                validationError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val start = parse(startText)
                val end = parse(endText)
                validationError = when {
                    start == null || end == null -> "Định dạng thời gian chưa đúng."
                    end <= start -> "Kết thúc phải lớn hơn bắt đầu."
                    else -> null
                }
                if (validationError == null && start != null && end != null) {
                    val zone = ZoneId.systemDefault()
                    onSchedule(
                        start.atZone(zone).toInstant().toEpochMilli(),
                        end.atZone(zone).toInstant().toEpochMilli()
                    )
                }
            }) {
                Text("Đặt lịch")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } }
    )
}

@Composable
private fun DayBookingsDialog(
    date: LocalDate,
    bookings: List<UsageBooking>,
    formatter: DateTimeFormatter,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Đóng") } },
        title = { Text("Lịch ngày ${date.dayOfMonth}/${date.monthValue}") },
        text = {
            if (bookings.isEmpty()) {
                Text("Ngày này chưa có ai đặt xe.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    bookings.forEach { booking ->
                        Text(
                            "${booking.requesterName}: ${formatter.format(Instant.ofEpochMilli(booking.startTime).atZone(ZoneId.systemDefault()))} - ${formatter.format(Instant.ofEpochMilli(booking.endTime).atZone(ZoneId.systemDefault()))}"
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun InfoBanner(
    visible: Boolean,
    text: String,
    color: Color,
    onClose: () -> Unit
) {
    if (!visible) return
    Surface(
        color = color,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.History, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(text, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Đóng") }
        }
    }
}

private fun buildMonthDays(month: YearMonth): List<LocalDate?> {
    val firstDay = month.atDay(1)
    val leadingEmpty = (firstDay.dayOfWeek.value + 6) % 7 // start on Monday
    val totalDays = month.lengthOfMonth()
    val result = mutableListOf<LocalDate?>()
    repeat(leadingEmpty) { result.add(null) }
    repeat(totalDays) { day ->
        result.add(month.atDay(day + 1))
    }
    while (result.size % 7 != 0) {
        result.add(null)
    }
    return result
}

private fun UsageBooking.isSameDay(date: LocalDate, zoneId: ZoneId): Boolean =
    Instant.ofEpochMilli(startTime).atZone(zoneId).toLocalDate() == date

private fun formatDateTime(millis: Long): String {
    if (millis <= 0) return "Không xác định"
    val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.getDefault())
    return formatter.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
}

private data class SchedulableVehicle(
    val id: String,
    val name: String,
    val ownerUid: String,
    val isShared: Boolean
)

private val openGradient = listOf(Color(0xFF4CC2FF), Color(0xFF639BFF))
private val closeGradient = listOf(Color(0xFF9FA4B4), Color(0xFF7C7F91))
