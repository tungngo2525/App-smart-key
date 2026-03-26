package com.example.smartkey.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await

class UserRepository {
    private val db = Firebase.firestore

    suspend fun createOrUpdateProfile(
        uid: String,
        username: String,
        email: String
    ) {
        val normalizedUsername = username.trim()
        val normalizedEmail = email.trim()
        val payload = mutableMapOf<String, Any?>(
            "uid" to uid,
            "email" to normalizedEmail,
            "emailLower" to normalizedEmail.lowercase()
        )
        if (normalizedUsername.isNotEmpty()) {
            payload["username"] = normalizedUsername
            payload["usernameLower"] = normalizedUsername.lowercase()
        }
        db.collection("users").document(uid).set(payload).await()
    }

    suspend fun profileOf(uid: String): UserProfile? {
        val snap = db.collection("users").document(uid).get().await()
        return snap.toObject(UserProfile::class.java)
    }

    suspend fun findByUsername(username: String): UserProfile? {
        val normalized = username.trim().lowercase()
        if (normalized.isEmpty()) return null
        val snap = db.collection("users")
            .whereEqualTo("usernameLower", normalized)
            .limit(1)
            .get()
            .await()
        return snap.documents.firstOrNull()?.toObject(UserProfile::class.java)
    }

    suspend fun findByEmail(email: String): UserProfile? {
        val normalized = email.trim().lowercase()
        if (normalized.isEmpty()) return null
        val snap = db.collection("users")
            .whereEqualTo("emailLower", normalized)
            .limit(1)
            .get()
            .await()
        return snap.documents.firstOrNull()?.toObject(UserProfile::class.java)
    }

    suspend fun saveMessagingToken(uid: String, token: String) {
        val payload = mapOf(
            "token" to token,
            "updatedAt" to Timestamp.now()
        )
        db.collection("users").document(uid)
            .collection("tokens").document(token)
            .set(payload)
            .await()
    }

    suspend fun removeMessagingToken(uid: String, token: String) {
        db.collection("users").document(uid)
            .collection("tokens").document(token)
            .delete()
            .await()
    }
}
