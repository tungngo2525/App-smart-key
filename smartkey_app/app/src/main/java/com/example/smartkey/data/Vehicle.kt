package com.example.smartkey.data

data class Vehicle(
    val id: String = "",
    val name: String = "",
    val plate: String = "",
    val tbDeviceId: String = "",
    val ecuVehicleId: String = "",  // 8-byte hardware ID từ ECU (hex string)
    val ecuSignerPublicKeyHex: String = "",
    val needsSetup: Boolean = false,  // true nếu chưa điền đầy đủ thông tin
    val location: String = "",
    val isActive: Boolean = true,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val ownerUid: String = "",
    val bleDeviceName: String = ""  // Tên thiết bị BLE của ECU; rỗng = dùng mặc định
)
