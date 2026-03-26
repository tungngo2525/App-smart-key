package com.example.smartkey.ui.vehicles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartkey.data.SharePayload
import com.example.smartkey.data.ShareRepository
import com.example.smartkey.data.SharedVehicle
import com.example.smartkey.data.UserRepository
import com.example.smartkey.data.Vehicle
import com.example.smartkey.data.VehicleRepository
import com.example.smartkey.security.SecurityService
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class VehicleListViewModel(
    private val repo: VehicleRepository = VehicleRepository(),
    private val shareRepository: ShareRepository = ShareRepository(),
    private val userRepository: UserRepository = UserRepository(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val owned: List<Vehicle> = emptyList(),
        val shared: List<SharedVehicle> = emptyList(),
        val sharesByVehicle: Map<String, List<SharedVehicle>> = emptyMap(),
        val error: String? = null,
        val message: String? = null
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var uid: String? = null
    private var currentUsername: String? = null
    private var observeJob: Job? = null

    init { refresh() }

    fun refresh() {
        uid = auth.currentUser?.uid
        val currentUid = uid
        if (currentUid == null) {
            _ui.value = UiState(
                loading = false,
                owned = emptyList(),
                shared = emptyList(),
                error = "Chưa đăng nhập"
            )
            return
        }
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            combine(
                repo.vehiclesOf(currentUid),
                shareRepository.sharesFor(currentUid),
                shareRepository.sharesCreatedBy(currentUid)
            ) { owned, shared, ownerShares ->
                Triple(owned, shared, ownerShares)
            }
                .onStart { _ui.update { it.copy(loading = true) } }
                .catch { e -> _ui.update { it.copy(loading = false, error = e.message) } }
                .collect { (owned, shared, ownerShares) ->
                    _ui.update {
                        it.copy(
                            loading = false,
                            owned = owned,
                            shared = shared,
                            sharesByVehicle = ownerShares.groupBy { share -> share.vehicleId }
                        )
                    }
                }
        }
        viewModelScope.launch {
            currentUsername = userRepository.profileOf(currentUid)?.username
        }
    }

    fun add(
        name: String,
        plate: String,
        tbDeviceId: String,
        location: String,
        isActive: Boolean,
        latitude: Double,
        longitude: Double
    ) {
        val owner = uid ?: return
        viewModelScope.launch {
            try {
                repo.addVehicle(
                    owner,
                    name.trim(),
                    plate.trim(),
                    tbDeviceId.trim(),
                    location.trim(),
                    isActive,
                    latitude,
                    longitude
                )
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message) }
            }
        }
    }

    fun delete(vehicleDocId: String) {
        viewModelScope.launch {
            try {
                repo.deleteVehicle(vehicleDocId)
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message) }
            }
        }
    }

    fun shareVehicleSecure(
        vehicle: Vehicle,
        targetEmail: String,
        durationHours: Int,
        unlimited: Boolean,
        password: String
    ) {
        val ownerUid = uid ?: return
        val trimmed = targetEmail.trim()
        if (trimmed.isEmpty()) {
            _ui.update { it.copy(error = "Vui lòng nhập email người nhận") }
            return
        }
        val hours = durationHours.coerceAtLeast(1)
        viewModelScope.launch {
            try {
                SecurityService.reauthenticate(password)
                val ownerProfile = currentUsername
                    ?: userRepository.profileOf(ownerUid)?.username
                    ?: auth.currentUser?.email
                    ?: ""
                val grantee = userRepository.findByEmail(trimmed)
                    ?: throw IllegalArgumentException("Không tìm thấy email $trimmed")
                val expiresAt = if (unlimited) 0L else System.currentTimeMillis() + hours * 60L * 60L * 1000L
                val payload = SharePayload(
                    vehicleId = vehicle.id,
                    vehicleName = vehicle.name,
                    vehiclePlate = vehicle.plate,
                    tbDeviceId = vehicle.tbDeviceId,
                    vehicleLocation = vehicle.location,
                    vehicleActive = vehicle.isActive,
                    vehicleLatitude = vehicle.latitude,
                    vehicleLongitude = vehicle.longitude,
                    ownerUid = ownerUid,
                    ownerUsername = ownerProfile,
                    granteeUid = grantee.uid,
                    granteeEmail = grantee.email,
                    expiresAt = expiresAt
                )
                shareRepository.createShare(payload)
                val message = if (unlimited) {
                    "Đã chia sẻ không giới hạn cho ${grantee.email}"
                } else {
                    "Đã chia sẻ cho ${grantee.email}"
                }
                _ui.update { it.copy(message = message) }
            } catch (e: Exception) {
                e.printStackTrace()
                _ui.update { it.copy(error = e.message ?: "Chia sẻ thất bại") }
            }
        }
    }

    fun revokeShare(share: SharedVehicle) {
        viewModelScope.launch {
            try {
                shareRepository.revokeShare(
                    share.shareId,
                    share.vehicleId,
                    share.granteeUid
                )
                _ui.update { it.copy(message = "Đã thu hồi ${share.granteeIdentifier}") }
            } catch (e: Exception) {
                e.printStackTrace()
                _ui.update { it.copy(error = e.message ?: "Thu hồi thất bại") }
            }
        }
    }

    fun clearError() { _ui.update { it.copy(error = null) } }
    fun clearMessage() { _ui.update { it.copy(message = null) } }
}
