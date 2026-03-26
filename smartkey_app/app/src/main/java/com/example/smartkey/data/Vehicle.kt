package com.example.smartkey.data

data class Vehicle(
    val id: String = "",
    val name: String = "",
    val plate: String = "",
    val tbDeviceId: String = "",
    val location: String = "",
    val isActive: Boolean = true,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)
