package com.example.smartkey.data

data class SharedVehicle(
    val shareId: String = "",
    val vehicleId: String = "",
    val name: String = "",
    val plate: String = "",
    val tbDeviceId: String = "",
    val location: String = "",
    val isActive: Boolean = true,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val ownerUsername: String = "",
    val granteeIdentifier: String = "",
    val granteeUid: String = "",
    val expiresAt: Long = 0L
) {
    val isExpired: Boolean
        get() = expiresAt in 1..System.currentTimeMillis()
}
