package com.example.smartkey.data

import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class ShareRepository {
    private val db = Firebase.firestore
    private val functions = Firebase.functions("us-central1")

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
                    ecuVehicleId = doc.getString("ecuVehicleId") ?: "",
                    location = doc.getString("vehicleLocation") ?: "",
                    isActive = doc.getBoolean("vehicleActive") ?: true,
                    latitude = doc.getDouble("vehicleLatitude") ?: 0.0,
                    longitude = doc.getDouble("vehicleLongitude") ?: 0.0,
                    ownerUsername = doc.getString("ownerUsername") ?: "",
                    granteeIdentifier = doc.getString("granteeEmail") ?: "",
                    granteeUid = doc.getString("granteeUid") ?: "",
                    expiresAt = doc.getLong("expiresAt") ?: 0L,
                    permissions = (doc.getLong("permissions") ?: 0xFFL).toInt()
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
                    ecuVehicleId = doc.getString("ecuVehicleId") ?: "",
                    location = doc.getString("vehicleLocation") ?: "",
                    isActive = doc.getBoolean("vehicleActive") ?: true,
                    latitude = doc.getDouble("vehicleLatitude") ?: 0.0,
                    longitude = doc.getDouble("vehicleLongitude") ?: 0.0,
                    ownerUsername = doc.getString("ownerUsername") ?: "",
                    granteeIdentifier = doc.getString("granteeEmail") ?: "",
                    granteeUid = doc.getString("granteeUid") ?: "",
                    expiresAt = doc.getLong("expiresAt") ?: 0L,
                    permissions = (doc.getLong("permissions") ?: 0xFFL).toInt()
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
                    ecuVehicleId = doc.getString("ecuVehicleId") ?: "",
                    location = doc.getString("vehicleLocation") ?: "",
                    isActive = doc.getBoolean("vehicleActive") ?: true,
                    latitude = doc.getDouble("vehicleLatitude") ?: 0.0,
                    longitude = doc.getDouble("vehicleLongitude") ?: 0.0,
                    ownerUsername = doc.getString("ownerUsername") ?: "",
                    granteeIdentifier = doc.getString("granteeEmail") ?: "",
                    granteeUid = doc.getString("granteeUid") ?: "",
                    expiresAt = doc.getLong("expiresAt") ?: 0L,
                    permissions = (doc.getLong("permissions") ?: 0xFFL).toInt()
                )
            }?.filterNot { it.isExpired } ?: emptyList()
            trySend(list)
        }
        awaitClose { reg.remove() }
    }

    suspend fun createShare(payload: SharePayload): String {
        val request = hashMapOf(
            "vehicleId" to payload.vehicleId,
            "targetUid" to payload.granteeUid,
            "targetEmail" to payload.granteeEmail,
            "targetDeviceId" to payload.granteeDeviceId,
            "permissions" to (payload.permissions and 0xFF),
            "expiresAt" to payload.expiresAt,
            "ownerUsername" to payload.ownerUsername
        )

        val result = functions
            .getHttpsCallable("issueVehicleCredential")
            .call(request)
            .await()

        val data = result.data as? Map<*, *> ?: emptyMap<String, Any?>()
        val ok = data["ok"] as? Boolean ?: false
        if (!ok) {
            throw IllegalStateException("Không thể cấp ShareKey từ máy chủ")
        }

        return data["shareId"] as? String
            ?: throw IllegalStateException("Máy chủ không trả về shareId")
    }

    suspend fun revokeShare(shareId: String, vehicleId: String, granteeUid: String) {
        val request = hashMapOf(
            "vehicleId" to vehicleId,
            "shareId" to shareId,
            "targetUid" to granteeUid,
            "reason" to "owner_revoked"
        )

        val result = functions
            .getHttpsCallable("revokeVehicleCredential")
            .call(request)
            .await()

        val data = result.data as? Map<*, *> ?: emptyMap<String, Any?>()
        val ok = data["ok"] as? Boolean ?: false
        if (!ok) {
            throw IllegalStateException("Không thể thu hồi ShareKey từ máy chủ")
        }
    }
}

data class SharePayload(
    val vehicleId: String,
    val vehicleName: String,
    val vehiclePlate: String,
    val tbDeviceId: String,
    val ecuVehicleId: String,
    val vehicleLocation: String,
    val vehicleActive: Boolean,
    val vehicleLatitude: Double,
    val vehicleLongitude: Double,
    val ownerUid: String,
    val ownerUsername: String,
    val granteeUid: String,
    val granteeEmail: String,
    val granteePublicKeyHex: String,
    val granteeDeviceId: String = "android_default",
    val expiresAt: Long,
    val permissions: Int
)
