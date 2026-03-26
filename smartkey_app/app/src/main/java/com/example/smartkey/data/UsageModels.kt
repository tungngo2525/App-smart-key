package com.example.smartkey.data

enum class UsageAction { OPEN, CLOSE }

data class UsageLog(
    val id: String = "",
    val vehicleId: String = "",
    val vehicleName: String = "",
    val action: UsageAction = UsageAction.OPEN,
    val actorUid: String = "",
    val actorName: String = "",
    val timestamp: Long = 0L
)

enum class BookingStatus { PENDING, APPROVED, REJECTED }

data class UsageBooking(
    val id: String = "",
    val vehicleId: String = "",
    val vehicleName: String = "",
    val ownerUid: String = "",
    val requesterUid: String = "",
    val requesterName: String = "",
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val status: BookingStatus = BookingStatus.PENDING
)

data class UsageBookingInput(
    val vehicleId: String,
    val vehicleName: String,
    val ownerUid: String,
    val requesterUid: String,
    val requesterName: String,
    val startTime: Long,
    val endTime: Long
)
