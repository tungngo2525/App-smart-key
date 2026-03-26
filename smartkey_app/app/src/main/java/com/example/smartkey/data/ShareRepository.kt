package com.example.smartkey.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class ShareRepository {
    private val db = Firebase.firestore

    fun sharesFor(uid: String) = callbackFlow<List<SharedVehicle>> {
        val query = db.collection("shares")
            .whereEqualTo("granteeUid", uid)
            .whereEqualTo("status", "active")

        val listenerRegistration = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                error.printStackTrace()
                trySend(emptyList())
                return@addSnapshotListener
            }
            val list = snapshot?.documents?.map { doc ->
                SharedVehicle(
                    shareId = doc.id,
                    vehicleId = doc.getString("vehicleId") ?: "",
                    name = doc.getString("vehicleName") ?: "",
                    plate = doc.getString("vehiclePlate") ?: "",
                    tbDeviceId = doc.getString("tbDeviceId") ?: "",
                    location = doc.getString("vehicleLocation") ?: "",
                    isActive = doc.getBoolean("vehicleActive") ?: true,
                    latitude = doc.getDouble("vehicleLatitude") ?: 0.0,
                    longitude = doc.getDouble("vehicleLongitude") ?: 0.0,
                    ownerUsername = doc.getString("ownerUsername") ?: "",
                    granteeIdentifier = doc.getString("granteeEmail") ?: "",
                    granteeUid = doc.getString("granteeUid") ?: "",
                    expiresAt = doc.getLong("expiresAt") ?: 0L
                )
            }?.filterNot { it.isExpired } ?: emptyList()
            trySend(list)
        }
        awaitClose { listenerRegistration.remove() }
    }

    fun sharesCreatedBy(ownerUid: String) = callbackFlow<List<SharedVehicle>> {
        val query = db.collection("shares")
            .whereEqualTo("ownerUid", ownerUid)
            .whereEqualTo("status", "active")

        val reg = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                error.printStackTrace()
                trySend(emptyList())
                return@addSnapshotListener
            }
            val list = snapshot?.documents?.map { doc ->
                SharedVehicle(
                    shareId = doc.id,
                    vehicleId = doc.getString("vehicleId") ?: "",
                    name = doc.getString("vehicleName") ?: "",
                    plate = doc.getString("vehiclePlate") ?: "",
                    tbDeviceId = doc.getString("tbDeviceId") ?: "",
                    location = doc.getString("vehicleLocation") ?: "",
                    isActive = doc.getBoolean("vehicleActive") ?: true,
                    latitude = doc.getDouble("vehicleLatitude") ?: 0.0,
                    longitude = doc.getDouble("vehicleLongitude") ?: 0.0,
                    ownerUsername = doc.getString("ownerUsername") ?: "",
                    granteeIdentifier = doc.getString("granteeEmail") ?: "",
                    granteeUid = doc.getString("granteeUid") ?: "",
                    expiresAt = doc.getLong("expiresAt") ?: 0L
                )
            }?.filterNot { it.isExpired } ?: emptyList()
            trySend(list)
        }
        awaitClose { reg.remove() }
    }

    fun sharesOfVehicle(vehicleId: String) = callbackFlow<List<SharedVehicle>> {
        val query = db.collection("shares")
            .whereEqualTo("vehicleId", vehicleId)
            .whereEqualTo("status", "active")

        val reg = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                error.printStackTrace()
                trySend(emptyList())
                return@addSnapshotListener
            }
            val list = snapshot?.documents?.map { doc ->
                SharedVehicle(
                    shareId = doc.id,
                    vehicleId = doc.getString("vehicleId") ?: "",
                    name = doc.getString("vehicleName") ?: "",
                    plate = doc.getString("vehiclePlate") ?: "",
                    tbDeviceId = doc.getString("tbDeviceId") ?: "",
                    location = doc.getString("vehicleLocation") ?: "",
                    isActive = doc.getBoolean("vehicleActive") ?: true,
                    latitude = doc.getDouble("vehicleLatitude") ?: 0.0,
                    longitude = doc.getDouble("vehicleLongitude") ?: 0.0,
                    ownerUsername = doc.getString("ownerUsername") ?: "",
                    granteeIdentifier = doc.getString("granteeEmail") ?: "",
                    granteeUid = doc.getString("granteeUid") ?: "",
                    expiresAt = doc.getLong("expiresAt") ?: 0L
                )
            }?.filterNot { it.isExpired } ?: emptyList()
            trySend(list)
        }
        awaitClose { reg.remove() }
    }

    suspend fun createShare(payload: SharePayload) {
        val doc = mapOf(
            "vehicleId" to payload.vehicleId,
            "vehicleName" to payload.vehicleName,
            "vehiclePlate" to payload.vehiclePlate,
            "tbDeviceId" to payload.tbDeviceId,
            "vehicleLocation" to payload.vehicleLocation,
            "vehicleActive" to payload.vehicleActive,
            "vehicleLatitude" to payload.vehicleLatitude,
            "vehicleLongitude" to payload.vehicleLongitude,
            "ownerUid" to payload.ownerUid,
            "ownerUsername" to payload.ownerUsername,
            "granteeUid" to payload.granteeUid,
            "granteeEmail" to payload.granteeEmail,
            "expiresAt" to payload.expiresAt,
            "status" to "active",
            "createdAt" to FieldValue.serverTimestamp()
        )
        db.collection("shares").add(doc).await()
        db.collection("shareLookup")
            .document(payload.vehicleId)
            .collection("recipients")
            .document(payload.granteeUid)
            .set(
                mapOf(
                    "vehicleId" to payload.vehicleId,
                    "ownerUid" to payload.ownerUid,
                    "granteeUid" to payload.granteeUid
                )
            )
            .await()
    }

    suspend fun revokeShare(shareId: String, vehicleId: String, granteeUid: String) {
        db.collection("shares").document(shareId)
            .update(mapOf("status" to "revoked"))
            .await()
        db.collection("shareLookup")
            .document(vehicleId)
            .collection("recipients")
            .document(granteeUid)
            .delete()
            .await()
    }
}

data class SharePayload(
    val vehicleId: String,
    val vehicleName: String,
    val vehiclePlate: String,
    val tbDeviceId: String,
    val vehicleLocation: String,
    val vehicleActive: Boolean,
    val vehicleLatitude: Double,
    val vehicleLongitude: Double,
    val ownerUid: String,
    val ownerUsername: String,
    val granteeUid: String,
    val granteeEmail: String,
    val expiresAt: Long
)
