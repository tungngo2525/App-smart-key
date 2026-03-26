package com.example.smartkey.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class UsageRepository {
    private val db = Firebase.firestore

    fun usageLogsForUser(uid: String, limit: Long = 100) = callbackFlow<List<UsageLog>> {
        if (uid.isBlank()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val query = db.collection("usageLogs")
            .whereArrayContains("participants", uid)
            .limit(limit)

        val reg = query.addSnapshotListener { snap, error ->
            if (error != null) {
                error.printStackTrace()
                trySend(emptyList())
                return@addSnapshotListener
            }
            val list = snap?.documents?.map { doc ->
                UsageLog(
                    id = doc.id,
                    vehicleId = doc.getString("vehicleId") ?: "",
                    vehicleName = doc.getString("vehicleName") ?: "",
                    action = when (doc.getString("action") ?: "open") {
                        "close" -> UsageAction.CLOSE
                        else -> UsageAction.OPEN
                    },
                    actorUid = doc.getString("actorUid") ?: "",
                    actorName = doc.getString("actorName") ?: "",
                    timestamp = doc.getLong("timestamp") ?: 0L
                )
            }?.sortedByDescending { it.timestamp } ?: emptyList()
            trySend(list)
        }
        awaitClose { reg.remove() }
    }

    fun bookingsForUser(uid: String) = callbackFlow<List<UsageBooking>> {
        if (uid.isBlank()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val query = db.collection("usageBookings")
            .whereArrayContains("participants", uid)

        val reg = query.addSnapshotListener { snap, error ->
            if (error != null) {
                error.printStackTrace()
                trySend(emptyList())
                return@addSnapshotListener
            }
            val list = snap?.documents?.map { doc ->
                UsageBooking(
                    id = doc.id,
                    vehicleId = doc.getString("vehicleId") ?: "",
                    vehicleName = doc.getString("vehicleName") ?: "",
                    ownerUid = doc.getString("ownerUid") ?: "",
                    requesterUid = doc.getString("requesterUid") ?: "",
                    requesterName = doc.getString("requesterName") ?: "",
                    startTime = doc.getLong("startTime") ?: 0L,
                    endTime = doc.getLong("endTime") ?: 0L,
                    status = when (doc.getString("status") ?: "pending") {
                        "approved" -> BookingStatus.APPROVED
                        "rejected" -> BookingStatus.REJECTED
                        else -> BookingStatus.PENDING
                    }
                )
            }?.sortedBy { it.startTime } ?: emptyList()
            trySend(list)
        }
        awaitClose { reg.remove() }
    }

    suspend fun scheduleUsage(input: UsageBookingInput) {
        val doc = mapOf(
            "vehicleId" to input.vehicleId,
            "vehicleName" to input.vehicleName,
            "ownerUid" to input.ownerUid,
            "requesterUid" to input.requesterUid,
            "requesterName" to input.requesterName,
            "startTime" to input.startTime,
            "endTime" to input.endTime,
            "status" to "pending",
            "participants" to listOf(input.ownerUid, input.requesterUid).distinct(),
            "createdAt" to FieldValue.serverTimestamp()
        )
        db.collection("usageBookings").add(doc).await()
    }

    suspend fun cancelBooking(bookingId: String) {
        if (bookingId.isBlank()) return
        db.collection("usageBookings").document(bookingId).delete().await()
    }
}
