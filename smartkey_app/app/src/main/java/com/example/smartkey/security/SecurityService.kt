package com.example.smartkey.security

import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

object SecurityService {
    private val auth: FirebaseAuth
        get() = FirebaseAuth.getInstance()

    suspend fun reauthenticate(password: String) {
        val user = auth.currentUser ?: throw IllegalStateException("Chưa đăng nhập")
        val email = user.email ?: throw IllegalStateException("Tài khoản không có email")
        val credential = EmailAuthProvider.getCredential(email, password)
        user.reauthenticate(credential).await()
    }
}
