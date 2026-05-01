package com.example.smartkey.data

data class SharedVehicle(
    val shareId: String = "",
    val vehicleId: String = "",
    val name: String = "",
    val plate: String = "",
    val tbDeviceId: String = "",
    val ecuVehicleId: String = "",
    val location: String = "",
    val isActive: Boolean = true,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val ownerUsername: String = "",
    val granteeIdentifier: String = "",
    val granteeUid: String = "",
    val expiresAt: Long = 0L,
    val permissions: Int = 0xFF
) {
    val isExpired: Boolean
        get() = expiresAt in 1..System.currentTimeMillis()

    val permissionLabel: String
        get() = when (permissions and 0xFF) {
            0x03 -> "Valet (khóa/mở khóa)"
            0x01 -> "Chỉ mở khóa"
            0x02 -> "Chỉ khóa"
            else -> "Toàn quyền"
        }

    val canUnlock: Boolean
        get() = (permissions and 0xFF) == 0xFF || (permissions and 0x01) != 0

    val canLock: Boolean
        get() = (permissions and 0xFF) == 0xFF || (permissions and 0x02) != 0

    val canAdmin: Boolean
        get() = (permissions and 0xFF) == 0xFF
}
