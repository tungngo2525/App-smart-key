package com.example.smartkey.data

data class UserProfile(
    val uid: String = "",
    val username: String = "",
    val usernameLower: String = "",
    val email: String = "",
    val emailLower: String = "",
    val digitalKeyPublicHex: String = ""
)
