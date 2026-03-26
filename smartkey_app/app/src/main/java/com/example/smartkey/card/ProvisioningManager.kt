package com.example.smartkey.card

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.*
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement

/**
 * ProvisioningManager - quản lý khóa và certificate cho HCE provisioning
 * Tương thích với IoT provisioning protocol từ folder iot/
 */
class ProvisioningManager(private val context: Context) {
    
    companion object {
        private const val TAG = "ProvisioningManager"
        private const val PREFS_NAME = "provisioning_prefs"
        private const val KEY_ALIAS = "smartkey_ec_key"
        private const val KEY_ID = "key_id"
        private const val PHONE_PUB_KEY = "phone_pub_raw"
        private const val CERT_CHAIN = "phone_cert_chain"
        
        // AID theo chuẩn trong IoT code
        val HCE_AID = byteArrayOf(0xF0.toByte(), 0x01, 0x02, 0x03, 0x04, 0x05)
    }
    
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * Khởi tạo hoặc load keypair ECDSA P-256
     */
    fun initialize() {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                generateKeyPair()
            } else {
                Log.i(TAG, "Loaded existing EC keypair")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing provisioning", e)
        }
    }
    
    /**
     * Tạo EC keypair mới (secp256r1/P-256)
     */
    private fun generateKeyPair() {
        try {
            val keyPairGenerator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                "AndroidKeyStore"
            )
            
            val parameterSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()
            
            keyPairGenerator.initialize(parameterSpec)
            keyPairGenerator.generateKeyPair()
            
            Log.i(TAG, "Generated new EC P-256 keypair")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate keypair", e)
        }
    }
    
    /**
     * Lấy public key dưới dạng uncompressed 65 bytes (0x04||X||Y)
     */
    fun getPublicKey65(): ByteArray? {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            val publicKey = entry?.certificate?.publicKey as? java.security.interfaces.ECPublicKey
            
            if (publicKey != null) {
                val w = publicKey.w
                val x = w.affineX.toByteArray().let { 
                    if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it.padStart(32) 
                }
                val y = w.affineY.toByteArray().let { 
                    if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it.padStart(32) 
                }
                
                return byteArrayOf(0x04) + x + y
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting public key", e)
        }
        return null
    }
    
    /**
     * Ký dữ liệu với private key
     */
    fun signData(data: ByteArray): ByteArray? {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            val privateKey = entry?.privateKey ?: return null
            
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(privateKey)
            signature.update(data)
            
            return signature.sign() // DER encoded signature
        } catch (e: Exception) {
            Log.e(TAG, "Error signing data", e)
            return null
        }
    }
    
    /**
     * Kiểm tra đã provisioned chưa
     */
    fun isProvisioned(): Boolean {
        return prefs.contains(KEY_ID)
    }
    
    /**
     * Lưu keyId (ASCII) nếu chưa provisioned
     */
    fun storeKeyIdIfEmpty(keyId: String): Boolean {
        if (isProvisioned()) return false
        
        prefs.edit().putString(KEY_ID, keyId).apply()
        Log.i(TAG, "Stored keyId: $keyId")
        return true
    }
    
    /**
     * Force lưu keyId (cho testing)
     */
    fun forceStoreKeyId(keyId: String): Boolean {
        prefs.edit().putString(KEY_ID, keyId).apply()
        Log.i(TAG, "Force stored keyId: $keyId")
        return true
    }
    
    /**
     * Lấy keyId đã lưu
     */
    fun getKeyId(): String? {
        return prefs.getString(KEY_ID, null)
    }
    
    /**
     * Lưu phone public key raw (65 bytes)
     */
    fun storePhonePubRaw(pub65: ByteArray): Boolean {
        if (pub65.size != 65 || pub65[0] != 0x04.toByte()) return false
        
        prefs.edit().putString(PHONE_PUB_KEY, pub65.toHexString()).apply()
        Log.i(TAG, "Stored phone public key (65 bytes)")
        return true
    }
    
    /**
     * Lưu certificate chain
     */
    fun storeCertChain(cert: ByteArray): Boolean {
        if (cert.isEmpty()) return false
        
        prefs.edit().putString(CERT_CHAIN, cert.toHexString()).apply()
        Log.i(TAG, "Stored cert chain (${cert.size} bytes)")
        return true
    }
    
    /**
     * Clear tất cả provisioning data (giữ device keypair)
     */
    fun clearProvisionedOnly() {
        prefs.edit()
            .remove(KEY_ID)
            .remove(PHONE_PUB_KEY)
            .remove(CERT_CHAIN)
            .apply()
        Log.i(TAG, "Cleared phone provisioning data")
    }
    
    /**
     * Clear toàn bộ (bao gồm device keypair)
     */
    fun clearAll() {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting keypair", e)
        }
        
        prefs.edit().clear().apply()
        Log.i(TAG, "Cleared ALL provisioning data")
    }
    
    /**
     * Lấy certificate cho provisioning response
     * Trong production nên dùng X.509 cert thực, đây là placeholder
     */
    fun getCertificate(): ByteArray {
        // Placeholder: trong production cần implement đúng X.509 cert
        // hoặc load từ server/secure storage
        return byteArrayOf() // empty cert for now
    }
    
    // Extension function để convert ByteArray to hex string
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02X".format(it) }
    }
    
    // Extension function để convert hex string to ByteArray
    private fun String.hexToByteArray(): ByteArray {
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    
    // Pad ByteArray to specified length
    private fun ByteArray.padStart(length: Int): ByteArray {
        if (size >= length) return this
        return ByteArray(length - size) + this
    }
}
