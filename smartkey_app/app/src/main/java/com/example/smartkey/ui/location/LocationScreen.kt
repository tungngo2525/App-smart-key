package com.example.smartkey.ui.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.preference.PreferenceManager
import com.example.smartkey.data.Vehicle
import com.example.smartkey.ui.vehicles.VehicleListViewModel
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class SimpleLatLng(val latitude: Double, val longitude: Double)

data class DistanceResult(
    val vehicle: Vehicle,
    val haversineMeters: Double,
    val planarMeters: Double,
    val smoothedMeters: Double,
    val sampleCount: Int
)

private class DistanceSmoother(private val windowSize: Int = 5) {
    private val buckets = mutableMapOf<String, ArrayDeque<Double>>()

    fun update(vehicleId: String, distance: Double): Double {
        if (vehicleId.isBlank()) return distance
        val bucket = buckets.getOrPut(vehicleId) { ArrayDeque() }
        if (bucket.size >= windowSize) {
            bucket.removeFirst()
        }
        bucket.addLast(distance)
        return bucket.averageValue()
    }

    fun sampleCount(vehicleId: String): Int = buckets[vehicleId]?.size ?: 0

    fun prune(validIds: Set<String>) {
        val iterator = buckets.keys.iterator()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (!validIds.contains(key)) {
                iterator.remove()
            }
        }
    }

    private fun ArrayDeque<Double>.averageValue(): Double {
        if (isEmpty()) return 0.0
        var total = 0.0
        for (value in this) total += value
        return total / size
    }
}

@Composable
fun LocationScreen(
    vm: VehicleListViewModel = viewModel()
) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsState()
    val vehicles = ui.owned.filter { it.latitude != 0.0 || it.longitude != 0.0 }
    var hasPermission by remember { mutableStateOf(hasLocationPermission(context)) }
    var deviceLocation by remember { mutableStateOf<SimpleLatLng?>(null) }
    var locationError by remember { mutableStateOf<String?>(null) }
    val fusedClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val distanceSmoother = remember { DistanceSmoother(windowSize = 6) }
    var distanceResults by remember { mutableStateOf<List<DistanceResult>>(emptyList()) }

    LaunchedEffect(Unit) {
        Configuration.getInstance().load(
            context,
            PreferenceManager.getDefaultSharedPreferences(context)
        )
        Configuration.getInstance().userAgentValue = context.packageName
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPermission = result.entries.any { it.value }
    }

    LaunchedEffect(hasPermission, vehicles.size) {
        if (hasPermission) {
            try {
                val tokenSource = CancellationTokenSource()
                val location = fusedClient.getCurrentLocation(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    tokenSource.token
                ).await()
                deviceLocation = location?.let { SimpleLatLng(it.latitude, it.longitude) }
                locationError = null
            } catch (e: SecurityException) {
                locationError = "Chưa cấp quyền vị trí cho ứng dụng."
            } catch (e: Exception) {
                locationError = e.message
            }
        }
    }

    LaunchedEffect(vehicles) {
        distanceSmoother.prune(vehicles.map { it.id }.toSet())
    }

    LaunchedEffect(deviceLocation, vehicles) {
        val origin = deviceLocation
        if (origin == null || vehicles.isEmpty()) {
            distanceResults = emptyList()
            return@LaunchedEffect
        }
        val results = vehicles.map { vehicle ->
            val target = SimpleLatLng(vehicle.latitude, vehicle.longitude)
            val haversine = haversineDistanceMeters(origin, target)
            val planar = planarApproxDistanceMeters(origin, target)
            val smoothed = distanceSmoother.update(vehicle.id, haversine)
            DistanceResult(
                vehicle = vehicle,
                haversineMeters = haversine,
                planarMeters = planar,
                smoothedMeters = smoothed,
                sampleCount = distanceSmoother.sampleCount(vehicle.id)
            )
        }.sortedBy { it.haversineMeters }
        distanceResults = results
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Tọa độ của bạn",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (!hasPermission) {
            PermissionRationale { permissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )) }
        } else {
            MapCard(deviceLocation = deviceLocation, vehicles = vehicles)
            DeviceInfoCard(deviceLocation = deviceLocation, error = locationError)
            DistanceAnalysisCard(
                deviceLocation = deviceLocation,
                hasVehicles = vehicles.isNotEmpty(),
                results = distanceResults
            )
        }
        VehicleCoordinatesList(vehicles = vehicles)
    }
}

@Composable
private fun PermissionRationale(onRequestPermission: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Ứng dụng cần quyền truy cập vị trí để hiển thị tọa độ của bạn trên bản đồ.",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
                Text("Cấp quyền vị trí")
            }
        }
    }
}

@Composable
private fun MapCard(
    deviceLocation: SimpleLatLng?,
    vehicles: List<Vehicle>
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
                factory = { context ->
                    MapView(context).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                    }
                },
                update = { mapView ->
                    val controller = mapView.controller
                    val center = deviceLocation?.let { GeoPoint(it.latitude, it.longitude) }
                        ?: vehicles.firstOrNull()?.let { GeoPoint(it.latitude, it.longitude) }
                    center?.let {
                        controller.setZoom(15.0)
                        controller.setCenter(it)
                    }
                    mapView.overlays.clear()
                    deviceLocation?.let {
                        mapView.overlays.add(
                            Marker(mapView).apply {
                                position = GeoPoint(it.latitude, it.longitude)
                                title = "Bạn đang ở đây"
                            }
                        )
                    }
                    vehicles.forEach { vehicle ->
                        mapView.overlays.add(
                            Marker(mapView).apply {
                                position = GeoPoint(vehicle.latitude, vehicle.longitude)
                                title = vehicle.name.ifBlank { vehicle.plate }
                                snippet = vehicle.location
                            }
                        )
                    }
                    mapView.invalidate()
                }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Chạm và kéo bản đồ để xem thêm khu vực",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun DeviceInfoCard(deviceLocation: SimpleLatLng?, error: String?) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Vị trí thiết bị", style = MaterialTheme.typography.titleMedium)
            when {
                error != null -> Text(error, color = MaterialTheme.colorScheme.error)
                deviceLocation != null -> {
                    Text("Lat: ${deviceLocation.latitude}")
                    Text("Lng: ${deviceLocation.longitude}")
                }
                else -> Text("Đang lấy tọa độ ...")
            }
        }
    }
}

@Composable
private fun VehicleCoordinatesList(vehicles: List<Vehicle>) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Tọa độ xe", style = MaterialTheme.typography.titleMedium)
            if (vehicles.isEmpty()) {
                Text("Chưa có dữ liệu tọa độ xe.")
            } else {
                vehicles.forEach { vehicle ->
                    Surface(
                        tonalElevation = 1.dp,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(vehicle.name.ifBlank { vehicle.plate }, fontWeight = FontWeight.Medium)
                            Text("Lat: ${vehicle.latitude}, Lng: ${vehicle.longitude}", style = MaterialTheme.typography.bodySmall)
                            if (vehicle.location.isNotBlank()) {
                                Text(vehicle.location, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DistanceAnalysisCard(
    deviceLocation: SimpleLatLng?,
    hasVehicles: Boolean,
    results: List<DistanceResult>
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Khoảng cách thiết bị - xe",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Giá trị được tính bằng công thức Haversine (cầu) và xấp xỉ phẳng, " +
                    "sau đó lọc bằng trung bình động để giảm nhiễu GPS.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            when {
                deviceLocation == null -> Text("Chưa xác định được tọa độ của thiết bị.")
                !hasVehicles -> Text("Chưa có xe nào được lưu tọa độ GPS.")
                results.isEmpty() -> Text("Đang chờ dữ liệu mới để tính khoảng cách...")
                else -> results.forEachIndexed { index, result ->
                    DistanceResultRow(result)
                    if (index != results.lastIndex) {
                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DistanceResultRow(result: DistanceResult) {
    val title = result.vehicle.name.ifBlank { result.vehicle.plate }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontWeight = FontWeight.Medium)
        DistanceMetricRow(label = "Haversine", value = formatDistance(result.haversineMeters))
        DistanceMetricRow(label = "Xấp xỉ phẳng", value = formatDistance(result.planarMeters))
        DistanceMetricRow(
            label = "Sai khác",
            value = formatDistance(abs(result.haversineMeters - result.planarMeters))
        )
        val samples = result.sampleCount
        val filterLabel = if (samples > 0) "Trung bình $samples mẫu" else "Trung bình động"
        DistanceMetricRow(
            label = filterLabel,
            value = formatDistance(result.smoothedMeters)
        )
    }
}

@Composable
private fun DistanceMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

private fun haversineDistanceMeters(from: SimpleLatLng, to: SimpleLatLng): Double {
    val dLat = degreesToRadians(to.latitude - from.latitude)
    val dLon = degreesToRadians(to.longitude - from.longitude)
    val startLat = degreesToRadians(from.latitude)
    val endLat = degreesToRadians(to.latitude)
    val a = sin(dLat / 2).pow2() + sin(dLon / 2).pow2() * cos(startLat) * cos(endLat)
    val c = 2 * kotlin.math.asin(min(1.0, sqrt(a)))
    return EARTH_RADIUS_METERS * c
}

private fun planarApproxDistanceMeters(from: SimpleLatLng, to: SimpleLatLng): Double {
    val dLat = degreesToRadians(to.latitude - from.latitude)
    val avgLat = degreesToRadians((from.latitude + to.latitude) / 2)
    val dLon = degreesToRadians(to.longitude - from.longitude) * cos(avgLat)
    val distanceRad = sqrt(dLat * dLat + dLon * dLon)
    return EARTH_RADIUS_METERS * distanceRad
}

private fun degreesToRadians(value: Double) = value * PI / 180.0

private fun Double.pow2() = this * this

private fun formatDistance(meters: Double): String {
    if (meters.isNaN() || meters.isInfinite()) return "—"
    val clamped = max(0.0, meters)
    return if (clamped >= 1000) {
        String.format("%.2f km", clamped / 1000)
    } else {
        String.format("%.1f m", clamped)
    }
}

private const val EARTH_RADIUS_METERS = 6_371_000.0
