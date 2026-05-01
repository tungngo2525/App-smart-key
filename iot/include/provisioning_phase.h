#pragma once
#include <Arduino.h>

class PN532; // forward decl

namespace ProvisioningPhase {
  // 🔐 PRODUCTION: Key Lifecycle Management
  // Multi-key support with expiry and permission levels
  
  #define MAX_DELEGATED_KEYS 5  // Support up to 5 delegated keys
  
  // Permission levels for delegated keys
  enum KeyPermission {
    PERM_FULL = 0xFF,      // Full access (same as owner)
    PERM_VALET = 0x03,     // Valet mode: lock/unlock only (no trunk, no admin)
    PERM_UNLOCK = 0x01,    // Unlock only
    PERM_LOCK = 0x02       // Lock only
  };
  
  // Delegated key structure with lifecycle metadata
  struct DelegatedKey {
    char keyId[65];        // Unique key identifier (ASCII)
    uint8_t pubKey[65];    // ECDSA P-256 public key (0x04||X||Y)
    uint64_t validUntil;   // Unix timestamp (0 = no expiry)
    uint8_t permissions;   // KeyPermission bitfield
    bool active;           // Is this slot active?
  };
  // Initialize provisioning (e.g., device keypair generation). Safe to call once at boot.
  void begin();

  // True if a phone keyId has already been stored.
  bool isProvisioned();

  // Store a keyId as hex if nothing is provisioned yet. Returns true on first-time set.
  bool storeKeyIdHexIfEmpty(const uint8_t* bytes, size_t len);
  // Store ASCII keyId (as provided by phone) if not already provisioned.
  bool storeKeyIdAsciiIfEmpty(const char* ascii);
  // Overwrite stored keyId unconditionally (for testing provisioning repeatedly)
  bool storeKeyIdAsciiForce(const char* ascii);

  // Persist phone long-term public key (raw uncompressed 65 bytes: 0x04||X||Y)
  bool storePhonePubRaw(const uint8_t* pub65);
  // Persist optional certificate chain blob
  bool storeCertChain(const uint8_t* cert, size_t certLen);

  // 🔐 PRODUCTION: Multi-key lifecycle management
  // Store delegated key with expiry and permissions
  bool storeSharedKeyCcc(const char* keyIdAscii,
                         const uint8_t* delegatedPub65,
                         const uint8_t* ownerSigDer, size_t ownerSigLen,
                         const uint8_t* sessionCtx, size_t sessionCtxLen,
                         uint64_t validUntil = 0,
                         uint8_t permissions = PERM_FULL);
  
  // Find valid delegated key (checks expiry + permissions)
  bool findValidDelegatedKey(const uint8_t* pubKey65, uint8_t* permissionsOut, char* keyIdOut);
  
  // Get all delegated keys (for admin/status)
  int getAllDelegatedKeys(DelegatedKey* keysOut, int maxKeys);
  
  // Remove expired keys (auto-cleanup)
  int cleanupExpiredKeys();
  
  // Clear specific key by keyId
  bool clearDelegatedKey(const char* keyId);
  
  // Clear all delegated keys
  void clearAllDelegatedKeys();

  // Trusted cloud sync path: upsert a delegated key issued by backend
  bool upsertDelegatedKeyFromCloud(const char* keyIdAscii,
                                   const uint8_t* delegatedPub65,
                                   uint64_t validUntil = 0,
                                   uint8_t permissions = PERM_FULL);
  
  // Legacy compatibility (deprecated)
  bool hasSharedKey();
  bool getSharedKeyId(String& out);
  size_t getSharedPubRaw(uint8_t* out, size_t max);
  void clearSharedKey();

  // Verify ECDSA-P256 DER signature over data using uncompressed 65-byte public key
  bool verifySignatureP256(const uint8_t* pub65,
                           const uint8_t* data, size_t dataLen,
                           const uint8_t* sigDer, size_t sigLen);

  // Sign data using the ECU long-term keypair generated in provisioning.
  // Returns DER signature length written to outSig, or 0 on failure.
  size_t signWithDeviceKey(const uint8_t* data, size_t dataLen,
                           uint8_t* outSig, size_t outMax);

  // Read ECU long-term public key as uncompressed 65-byte point.
  bool getDevicePubRaw(uint8_t* out65, size_t max);

  // Clear all provisioning-related state (keys and key id)
  void clearAll();
  // Clear only phone provisioning artifacts (keyId, phone pub, cert), keep device keypair
  void clearProvisionedOnly();

  // Read-back helpers for diagnostics
  bool getKeyId(String& out);
  // Returns number of bytes copied (0 if missing). Expects max>=65 for full key.
  size_t getPhonePubRaw(uint8_t* out, size_t max);
  // Returns cert chain length (0 if missing). If 'out' provided and max>=len, copies into out.
  size_t getCertChain(uint8_t* out, size_t max);

  // Validate that a provided or stored certificate's subject public key matches the 65-byte pubkey
  bool validateCertPublicKeyMatchesPub(const uint8_t* cert, size_t certLen, const uint8_t* pub65);
  bool validateStoredCertMatchesStoredPub();

  // Optional: run a simple provisioning exchange using the currently working HCE flow.
  // Uses the SELECT payload or subsequent UID APDU payload as the persistent ID.
  // Returns true if a new keyId was stored.
  bool runOnceWithHce(PN532& nfc, const uint8_t* aid, size_t aidLen, uint32_t waitMs = 15000);
}
