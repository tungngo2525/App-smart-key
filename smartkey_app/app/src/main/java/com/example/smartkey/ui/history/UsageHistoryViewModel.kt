package com.example.smartkey.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartkey.data.UsageBooking
import com.example.smartkey.data.UsageBookingInput
import com.example.smartkey.data.UsageLog
import com.example.smartkey.data.UsageRepository
import com.example.smartkey.data.UserRepository
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

class UsageHistoryViewModel(
    private val repo: UsageRepository = UsageRepository(),
    private val userRepository: UserRepository = UserRepository(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val logs: List<UsageLog> = emptyList(),
        val bookings: List<UsageBooking> = emptyList(),
        val error: String? = null,
        val message: String? = null
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var observeJob: Job? = null
    private var uid: String? = null
    private var displayName: String? = null

    init {
        subscribe()
    }

    fun refresh() = subscribe()

    private fun subscribe() {
        uid = auth.currentUser?.uid
        val currentUid = uid
        if (currentUid == null) {
            _ui.value = UiState(loading = false, error = "Chưa đăng nhập")
            return
        }
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            combine(
                repo.usageLogsForUser(currentUid),
                repo.bookingsForUser(currentUid)
            ) { logs, bookings ->
                logs to bookings
            }
                .onStart { _ui.update { it.copy(loading = true) } }
                .catch { e -> _ui.update { it.copy(loading = false, error = e.message) } }
                .collect { (logs, bookings) ->
                    _ui.update {
                        it.copy(
                            loading = false,
                            logs = logs,
                            bookings = bookings
                        )
                    }
                }
        }
        viewModelScope.launch {
            displayName = userRepository.profileOf(currentUid)?.username
                ?: auth.currentUser?.email
        }
    }

    fun scheduleUsage(
        vehicleId: String,
        vehicleName: String,
        ownerUid: String,
        startTime: Long,
        endTime: Long
    ) {
        val requesterUid = uid ?: return
        if (startTime <= System.currentTimeMillis()) {
            _ui.update { it.copy(error = "Thời gian bắt đầu phải ở tương lai.") }
            return
        }
        if (endTime <= startTime) {
            _ui.update { it.copy(error = "Thời gian kết thúc phải lớn hơn bắt đầu.") }
            return
        }
        val requesterName = displayName ?: auth.currentUser?.email ?: "Bạn"
        viewModelScope.launch {
            try {
                repo.scheduleUsage(
                    UsageBookingInput(
                        vehicleId = vehicleId,
                        vehicleName = vehicleName,
                        ownerUid = ownerUid,
                        requesterUid = requesterUid,
                        requesterName = requesterName,
                        startTime = startTime,
                        endTime = endTime
                    )
                )
                _ui.update { it.copy(message = "Đã gửi yêu cầu đặt lịch sử dụng.") }
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: "Không thể đặt lịch") }
            }
        }
    }

    fun cancelBooking(bookingId: String) {
        viewModelScope.launch {
            try {
                repo.cancelBooking(bookingId)
                _ui.update { it.copy(message = "Đã hủy lịch.") }
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: "Không thể hủy lịch") }
            }
        }
    }

    fun clearError() {
        _ui.update { it.copy(error = null) }
    }

    fun clearMessage() {
        _ui.update { it.copy(message = null) }
    }

    val currentUserId: String?
        get() = uid
}
