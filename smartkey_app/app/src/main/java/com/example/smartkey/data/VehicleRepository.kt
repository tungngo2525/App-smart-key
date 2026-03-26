package com.example.smartkey.data

import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class VehicleRepository {
    private val db = Firebase.firestore

    fun vehiclesOf(uid: String) = callbackFlow<List<Vehicle>> {
        val query = db.collection("vehicles")
            .whereEqualTo("profile.ownerUid", uid)
            .orderBy("profile.name", Query.Direction.ASCENDING)

        val reg = query.addSnapshotListener { snap, error ->
            if (error != null) {
                error.printStackTrace()
                trySend(emptyList())
                return@addSnapshotListener
            }
            if (snap == null) {
                trySend(emptyList())
                return@addSnapshotListener
            }

            val list = snap.documents.map { doc ->
                val profile = doc.get("profile") as? Map<*, *> ?: emptyMap<String, Any>()
                val tb = doc.get("tb") as? Map<*, *> ?: emptyMap<String, Any>()
                Vehicle(
                    id = doc.id,
                    name = profile["name"] as? String ?: "",
                    plate = profile["plate"] as? String ?: "",
                    tbDeviceId = tb["deviceId"] as? String ?: "",
                    location = profile["location"] as? String ?: "",
                    isActive = profile["active"] as? Boolean ?: true,
                    latitude = (profile["latitude"] as? Number)?.toDouble() ?: 0.0,
                    longitude = (profile["longitude"] as? Number)?.toDouble() ?: 0.0
                )
            }
            trySend(list)
        }
        awaitClose { reg.remove() }
    }

    fun vehicleFlow(vehicleId: String) = callbackFlow<Vehicle?> {
        val reg = db.collection("vehicles").document(vehicleId)
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    error.printStackTrace()
                    trySend(null)
                    return@addSnapshotListener
                }
                if (snap == null || !snap.exists()) {
                    trySend(null)
                    return@addSnapshotListener
                }
                val profile = snap.get("profile") as? Map<*, *> ?: emptyMap<String, Any>()
                val tb = snap.get("tb") as? Map<*, *> ?: emptyMap<String, Any>()
                val vehicle = Vehicle(
                    id = snap.id,
                    name = profile["name"] as? String ?: "",
                    plate = profile["plate"] as? String ?: "",
                    tbDeviceId = tb["deviceId"] as? String ?: "",
                    location = profile["location"] as? String ?: "",
                    isActive = profile["active"] as? Boolean ?: true,
                    latitude = (profile["latitude"] as? Number)?.toDouble() ?: 0.0,
                    longitude = (profile["longitude"] as? Number)?.toDouble() ?: 0.0
                )
                trySend(vehicle)
            }
        awaitClose { reg.remove() }
    }

    suspend fun addVehicle(
        uid: String,
        name: String,
        plate: String,
        tbDeviceId: String,
        location: String,
        isActive: Boolean,
        latitude: Double,
        longitude: Double
    ) {
        val doc = mapOf(
            "profile" to mapOf(
                "name" to name,
                "plate" to plate,
                "ownerUid" to uid,
                "location" to location,
                "active" to isActive,
                "latitude" to latitude,
                "longitude" to longitude
            ),
            "tb" to mapOf("deviceId" to tbDeviceId)
        )
        db.collection("vehicles").add(doc).await()
    }

    suspend fun deleteVehicle(vehicleDocId: String) {
        db.collection("vehicles").document(vehicleDocId).delete().await()
    }

    suspend fun setVehicleActive(vehicleDocId: String, isActive: Boolean) {
        db.collection("vehicles").document(vehicleDocId)
            .update(mapOf("profile.active" to isActive))
            .await()
    }
}
