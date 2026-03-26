#pragma once
#include <Arduino.h>

class PN532; // forward decl

namespace ProvisioningPhase {
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

  // Verify ECDSA-P256 DER signature over data using uncompressed 65-byte public key
  bool verifySignatureP256(const uint8_t* pub65,
                           const uint8_t* data, size_t dataLen,
                           const uint8_t* sigDer, size_t sigLen);

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
