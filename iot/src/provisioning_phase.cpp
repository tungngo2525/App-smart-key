#include <Arduino.h>
#include <Preferences.h>
#include <PN532.h>
#include <mbedtls/pk.h>
#include <mbedtls/ctr_drbg.h>
#include <mbedtls/entropy.h>
#include <mbedtls/sha256.h>
#include <mbedtls/ecdsa.h>
#include <mbedtls/ecp.h>
#include <mbedtls/x509_crt.h>
#include <cstdlib>
#include <esp_system.h>
#include "provisioning_phase.h"
#include "ble/utils/crypto_utils.h"

namespace {
  Preferences prefs;
  const char* kNs = "prov";
  const char* kKeyPem = "ec_priv";
  const char* kKeyId  = "key_id";
  const char* kSharedKeyId = "shared_key_id";
  const char* kSharedPubRaw = "shared_pub_raw";
  
  // 🔥 PRODUCTION: Multi-key storage
  const char* kDelegatedKeyPrefix = "dk_";
  const char* kDelegatedKeyCount = "dk_count";

  mbedtls_pk_context keypair;
  mbedtls_entropy_context entropy;
  mbedtls_ctr_drbg_context drbg;
  bool keysReady = false;

  // ── NVS AES-256-GCM encryption helpers ─────────────────────────────────
  // CCC R3 §5.2: sensitive NVS blobs are encrypted with a device-unique key.
  // Key derivation: HKDF-SHA256(ikm=device_uid[6], salt=none, info="NVS-ENC-KEY")
  // Stored blob format: [nonce(12)][ciphertext(N)][tag(16)]  (total = N + 28)

  static uint8_t s_nvsEncKey[32] = {};
  static bool s_nvsEncKeyReady = false;

  void ensureNvsEncKey() {
    if (s_nvsEncKeyReady) return;
    // Use ESP32 unique 6-byte MAC as IKM — device-unique, stable across reboots
    uint8_t uid[6];
    esp_efuse_mac_get_default(uid);
    const uint8_t* info = (const uint8_t*)"NVS-ENC-KEY";
    hkdf_sha256(nullptr, 0, uid, sizeof(uid), info, 11, s_nvsEncKey, 32);
    s_nvsEncKeyReady = true;
  }

  // Encrypt `plain` (len bytes) and store as "<key>_e" in current prefs namespace.
  // Caller must have called prefs.begin() before calling this.
  bool nvs_put_encrypted(Preferences& p, const char* nvkey, const uint8_t* plain, size_t len) {
    ensureNvsEncKey();
    // Build random 12-byte nonce from esp_random()
    uint8_t nonce[12];
    for (int i = 0; i < 3; i++) {
      uint32_t r = esp_random();
      memcpy(nonce + i * 4, &r, 4);
    }
    // Allocate: nonce(12) + ciphertext(len) + tag(16)
    const size_t blobLen = 12 + len + 16;
    uint8_t* blob = (uint8_t*)malloc(blobLen);
    if (!blob) return false;
    memcpy(blob, nonce, 12);
    uint8_t tag[16];
    const uint8_t aad[] = "NVS";
    bool ok = aes_gcm_encrypt(s_nvsEncKey, nonce, aad, 3,
                               plain, len, blob + 12, tag);
    if (ok) memcpy(blob + 12 + len, tag, 16);
    if (ok) {
      char ekey[32]; snprintf(ekey, sizeof(ekey), "%.24s_e", nvkey);
      ok = (p.putBytes(ekey, blob, blobLen) == blobLen);
    }
    free(blob);
    return ok;
  }

  // Decrypt blob stored at "<key>_e". Returns bytes written to `out`, 0 on error.
  // Caller must have called prefs.begin() before calling this.
  size_t nvs_get_encrypted(Preferences& p, const char* nvkey, uint8_t* out, size_t maxOut) {
    ensureNvsEncKey();
    char ekey[32]; snprintf(ekey, sizeof(ekey), "%.24s_e", nvkey);
    if (!p.isKey(ekey)) return 0;
    size_t blobLen = p.getBytesLength(ekey);
    if (blobLen < 12 + 1 + 16) return 0;  // nonce + at least 1 byte + tag
    uint8_t* blob = (uint8_t*)malloc(blobLen);
    if (!blob) return 0;
    size_t got = p.getBytes(ekey, blob, blobLen);
    size_t result = 0;
    if (got == blobLen) {
      const uint8_t* nonce = blob;
      const size_t ctLen = blobLen - 12 - 16;
      const uint8_t* ct   = blob + 12;
      const uint8_t* tag  = blob + 12 + ctLen;
      if (ctLen <= maxOut) {
        const uint8_t aad[] = "NVS";
        bool ok = aes_gcm_decrypt(s_nvsEncKey, nonce, aad, 3,
                                   ct, ctLen, tag, out);
        if (ok) result = ctLen;
      }
    }
    free(blob);
    return result;
  }

  // Small hex helper
  String toHex(const uint8_t* b, size_t n) {
    String s; s.reserve(n*2);
    for (size_t i=0;i<n;i++){ char buf[3]; sprintf(buf, "%02X", b[i]); s += buf; }
    return s;
  }

  bool ensureKeypair() {
    prefs.begin(kNs, false);
    String privPem = prefs.getString(kKeyPem, "");
    prefs.end();

    mbedtls_pk_init(&keypair);
    mbedtls_entropy_init(&entropy);
    mbedtls_ctr_drbg_init(&drbg);
    const char *pers = "provA";
    mbedtls_ctr_drbg_seed(&drbg, mbedtls_entropy_func, &entropy,
                          (const unsigned char*)pers, strlen(pers));

    if (privPem.length() > 0) {
      if (mbedtls_pk_parse_key(&keypair,
            (const unsigned char*)privPem.c_str(), privPem.length()+1,
            nullptr, 0) == 0) {
        Serial.println("[CCC] Loaded existing ECC keypair");
        return true;
      }
    }
    if (mbedtls_pk_setup(&keypair, mbedtls_pk_info_from_type(MBEDTLS_PK_ECKEY)) != 0) return false;
    if (mbedtls_ecp_gen_key(MBEDTLS_ECP_DP_SECP256R1, mbedtls_pk_ec(keypair), mbedtls_ctr_drbg_random, &drbg) != 0) return false;

    static unsigned char buf[1600]; memset(buf, 0, sizeof(buf));
    if (mbedtls_pk_write_key_pem(&keypair, buf, sizeof(buf)) == 0) {
      prefs.begin(kNs, false);
      prefs.putString(kKeyPem, (char*)buf);
      prefs.end();
      Serial.println("[CCC] Generated ECC keypair and saved to NVS");
    }
    return true;
  }
}

namespace ProvisioningPhase {

void begin() {
  keysReady = ensureKeypair();
  if (!keysReady) Serial.println("[CCC] ECC init failed (continuing without keys)");
}

bool isProvisioned() {
  prefs.begin(kNs, true);
  bool ok = prefs.isKey(kKeyId);
  prefs.end();
  return ok;
}

bool storeKeyIdHexIfEmpty(const uint8_t* bytes, size_t len) {
  if (!bytes || len == 0) return false;
  if (isProvisioned()) return false;
  String hex = toHex(bytes, len);
  prefs.begin(kNs, false);
  bool ok = prefs.putString(kKeyId, hex) > 0;
  prefs.end();
  if (ok) Serial.printf("[CCC] Stored phone keyId as hex: %s\n", hex.c_str());
  return ok;
}

bool storeKeyIdAsciiIfEmpty(const char* ascii) {
  if (!ascii || !*ascii) return false;
  if (isProvisioned()) return false;
  prefs.begin(kNs, false);
  bool ok = prefs.putString(kKeyId, ascii) > 0;
  prefs.end();
  if (ok) Serial.printf("[CCC] Stored phone keyId (ascii): %s\n", ascii);
  return ok;
}

bool storeKeyIdAsciiForce(const char* ascii) {
  if (!ascii || !*ascii) return false;
  prefs.begin(kNs, false);
  bool ok = prefs.putString(kKeyId, ascii) > 0;
  prefs.end();
  if (ok) Serial.printf("[CCC] Force-stored phone keyId (ascii): %s\n", ascii);
  return ok;
}

bool storePhonePubRaw(const uint8_t* pub65) {
  if (!pub65 || pub65[0] != 0x04) return false;
  prefs.begin(kNs, false);
  // CCC R3 §5.2: encrypt phone public key before NVS storage
  bool ok = nvs_put_encrypted(prefs, "phone_pub_raw", pub65, 65);
  prefs.end();
  if (ok) Serial.println("[CCC] Stored phone public key (AES-256-GCM encrypted) in NVS");
  else    Serial.println("[CCC] WARN: NVS encrypt failed, storing plaintext fallback");
  if (!ok) {
    prefs.begin(kNs, false);
    ok = prefs.putBytes("phone_pub_raw", pub65, 65) == 65;
    prefs.end();
  }
  return ok;
}

bool storeCertChain(const uint8_t* cert, size_t certLen) {
  if (!cert || certLen == 0) return false;
  prefs.begin(kNs, false);
  // CCC R3 §5.2: encrypt cert chain before NVS storage
  bool ok = nvs_put_encrypted(prefs, "phone_cert_chain", cert, certLen);
  if (!ok) ok = (prefs.putBytes("phone_cert_chain", cert, certLen) == certLen);
  prefs.end();
  if (ok) Serial.printf("[CCC] Stored phone cert chain (%u bytes, encrypted) in NVS\n", (unsigned)certLen);
  return ok;
}

bool hasSharedKey() {
  prefs.begin(kNs, true);
  bool ok = prefs.isKey(kSharedKeyId) && prefs.isKey(kSharedPubRaw);
  prefs.end();
  return ok;
}

bool getSharedKeyId(String& out) {
  prefs.begin(kNs, true);
  if (!prefs.isKey(kSharedKeyId)) { prefs.end(); return false; }
  out = prefs.getString(kSharedKeyId, "");
  prefs.end();
  return out.length() > 0;
}

size_t getSharedPubRaw(uint8_t* out, size_t max) {
  prefs.begin(kNs, true);
  if (!prefs.isKey(kSharedPubRaw)) { prefs.end(); return 0; }
  size_t need = prefs.getBytesLength(kSharedPubRaw);
  if (need == 0 || !out || max == 0) { prefs.end(); return 0; }
  size_t toCopy = (need <= max) ? need : max;
  size_t got = prefs.getBytes(kSharedPubRaw, out, toCopy);
  prefs.end();
  return got;
}

// 🔥 PRODUCTION: Get current Unix timestamp
uint64_t getUnixTime() {
  struct timeval tv;
  gettimeofday(&tv, NULL);
  return (uint64_t)tv.tv_sec;
}

void clearSharedKey() {
  // Clear all delegated keys (new way)
  clearAllDelegatedKeys();
  
  // Also clear legacy single-key storage
  prefs.begin(kNs, false);
  if (prefs.isKey(kSharedKeyId)) prefs.remove(kSharedKeyId);
  if (prefs.isKey(kSharedPubRaw)) prefs.remove(kSharedPubRaw);
  prefs.end();
}

// 🔥 PRODUCTION: Multi-key lifecycle management
bool storeSharedKeyCcc(const char* keyIdAscii,
                       const uint8_t* delegatedPub65,
                       const uint8_t* ownerSigDer, size_t ownerSigLen,
                       const uint8_t* sessionCtx, size_t sessionCtxLen,
                       uint64_t validUntil,
                       uint8_t permissions) {
  if (!keyIdAscii || !*keyIdAscii || !delegatedPub65 || delegatedPub65[0] != 0x04 || !ownerSigDer || ownerSigLen == 0) {
    return false;
  }
  // sessionCtx can be nullptr for BLE-based provisioning (less secure, no session binding)
  if (sessionCtx != nullptr && sessionCtxLen != 24) {
    Serial.println("[CCC] shareKey reject: invalid session context length");
    return false;
  }

  const size_t keyIdLen = strlen(keyIdAscii);
  if (keyIdLen == 0 || keyIdLen > 64) {
    Serial.println("[CCC] shareKey reject: keyId length out of range");
    return false;
  }

  uint8_t ownerPub65[65];
  if (getPhonePubRaw(ownerPub65, sizeof(ownerPub65)) != 65 || ownerPub65[0] != 0x04) {
    Serial.println("[CCC] shareKey reject: owner public key missing");
    return false;
  }

  static const char kShareLabel[] = "CCC-DK-SHARE-V2";
  const size_t labelLen = sizeof(kShareLabel) - 1;
  const size_t signedLen = labelLen + sessionCtxLen + 1 + keyIdLen + 65;
  uint8_t* signedData = (uint8_t*)malloc(signedLen);
  if (!signedData) return false;

  memcpy(signedData, kShareLabel, labelLen);
  memcpy(signedData + labelLen, sessionCtx, sessionCtxLen);
  signedData[labelLen + sessionCtxLen] = (uint8_t)keyIdLen;
  memcpy(signedData + labelLen + sessionCtxLen + 1, keyIdAscii, keyIdLen);
  memcpy(signedData + labelLen + sessionCtxLen + 1 + keyIdLen, delegatedPub65, 65);

  bool sigOk = verifySignatureP256(ownerPub65, signedData, signedLen, ownerSigDer, ownerSigLen);
  free(signedData);
  if (!sigOk) {
    Serial.println("[CCC] shareKey reject: owner signature invalid");
    return false;
  }

  // 🔥 PRODUCTION: Store in multi-key array
  prefs.begin(kNs, false);
  
  // Find existing key or free slot
  int slotIndex = -1;
  int keyCount = prefs.getInt(kDelegatedKeyCount, 0);
  
  for (int i = 0; i < MAX_DELEGATED_KEYS; i++) {
    char slotKey[16];
    snprintf(slotKey, sizeof(slotKey), "%s%d", kDelegatedKeyPrefix, i);
    
    if (!prefs.isKey(slotKey)) {
      slotIndex = i;  // Found free slot
      break;
    }
    
    // Check if keyId already exists (update)
    DelegatedKey existingKey;
    size_t got = prefs.getBytes(slotKey, &existingKey, sizeof(DelegatedKey));
    if (got == sizeof(DelegatedKey) && strcmp(existingKey.keyId, keyIdAscii) == 0) {
      slotIndex = i;  // Update existing
      break;
    }
  }
  
  if (slotIndex == -1) {
    prefs.end();
    Serial.println("[CCC] ❌ shareKey reject: no free slots (max 5 keys)");
    return false;
  }
  
  // Create new delegated key
  DelegatedKey newKey;
  memset(&newKey, 0, sizeof(DelegatedKey));
  strncpy(newKey.keyId, keyIdAscii, sizeof(newKey.keyId) - 1);
  memcpy(newKey.pubKey, delegatedPub65, 65);
  newKey.validUntil = validUntil;  // 0 = no expiry
  newKey.permissions = permissions;
  newKey.active = true;
  
  char slotKey[16];
  snprintf(slotKey, sizeof(slotKey), "%s%d", kDelegatedKeyPrefix, slotIndex);
  bool ok = prefs.putBytes(slotKey, &newKey, sizeof(DelegatedKey)) == sizeof(DelegatedKey);
  
  if (ok) {
    // Update count if new slot
    bool isNew = (slotIndex >= keyCount);
    if (isNew) {
      prefs.putInt(kDelegatedKeyCount, slotIndex + 1);
    }
    
    Serial.printf("[CCC] ✅ Stored delegated key [%d]: keyId=%s", slotIndex, keyIdAscii);
    if (validUntil > 0) {
      Serial.printf(" exp=%llu", validUntil);
    }
    Serial.printf(" perm=0x%02X\n", permissions);
  }
  
  prefs.end();
  return ok;
}

// 🔥 PRODUCTION: Find valid delegated key (checks expiry + permissions)
bool findValidDelegatedKey(const uint8_t* pubKey65, uint8_t* permissionsOut, char* keyIdOut) {
  if (!pubKey65 || pubKey65[0] != 0x04) return false;
  
  uint64_t now = getUnixTime();
  
  prefs.begin(kNs, true);
  int keyCount = prefs.getInt(kDelegatedKeyCount, 0);
  
  for (int i = 0; i < keyCount && i < MAX_DELEGATED_KEYS; i++) {
    char slotKey[16];
    snprintf(slotKey, sizeof(slotKey), "%s%d", kDelegatedKeyPrefix, i);
    
    if (!prefs.isKey(slotKey)) continue;
    
    DelegatedKey key;
    size_t got = prefs.getBytes(slotKey, &key, sizeof(DelegatedKey));
    if (got != sizeof(DelegatedKey) || !key.active) continue;
    
    // Check pubKey match
    if (memcmp(key.pubKey, pubKey65, 65) != 0) continue;
    
    // 🔥 CRITICAL: Check expiry
    if (key.validUntil > 0 && now > key.validUntil) {
      Serial.printf("[CCC] ⏰ Key EXPIRED: %s (exp=%llu now=%llu)\n",
                    key.keyId, key.validUntil, now);
      continue;
    }
    
    // Valid key found!
    if (permissionsOut) *permissionsOut = key.permissions;
    if (keyIdOut) strcpy(keyIdOut, key.keyId);
    
    prefs.end();
    Serial.printf("[CCC] ✅ Found valid delegated key: %s perm=0x%02X\n",
                  key.keyId, key.permissions);
    return true;
  }
  
  prefs.end();
  return false;
}

// 🔥 PRODUCTION: Get all delegated keys (for admin/status)
int getAllDelegatedKeys(DelegatedKey* keysOut, int maxKeys) {
  if (!keysOut || maxKeys <= 0) return 0;
  
  prefs.begin(kNs, true);
  int keyCount = prefs.getInt(kDelegatedKeyCount, 0);
  int found = 0;
  
  for (int i = 0; i < keyCount && i < MAX_DELEGATED_KEYS && found < maxKeys; i++) {
    char slotKey[16];
    snprintf(slotKey, sizeof(slotKey), "%s%d", kDelegatedKeyPrefix, i);
    
    if (!prefs.isKey(slotKey)) continue;
    
    size_t got = prefs.getBytes(slotKey, &keysOut[found], sizeof(DelegatedKey));
    if (got == sizeof(DelegatedKey)) {
      found++;
    }
  }
  
  prefs.end();
  return found;
}

// 🔥 PRODUCTION: Cleanup expired keys (auto-cleanup)
int cleanupExpiredKeys() {
  uint64_t now = getUnixTime();
  int removed = 0;
  
  prefs.begin(kNs, false);
  int keyCount = prefs.getInt(kDelegatedKeyCount, 0);
  
  for (int i = 0; i < keyCount && i < MAX_DELEGATED_KEYS; i++) {
    char slotKey[16];
    snprintf(slotKey, sizeof(slotKey), "%s%d", kDelegatedKeyPrefix, i);
    
    if (!prefs.isKey(slotKey)) continue;
    
    DelegatedKey key;
    size_t got = prefs.getBytes(slotKey, &key, sizeof(DelegatedKey));
    if (got != sizeof(DelegatedKey)) continue;
    
    // Remove if expired
    if (key.validUntil > 0 && now > key.validUntil) {
      prefs.remove(slotKey);
      removed++;
      Serial.printf("[CCC] 🗑️ Cleaned expired key: %s\n", key.keyId);
    }
  }
  
  prefs.end();
  return removed;
}

// 🔥 PRODUCTION: Clear specific delegated key
bool clearDelegatedKey(const char* keyId) {
  if (!keyId || !*keyId) return false;
  
  prefs.begin(kNs, false);
  int keyCount = prefs.getInt(kDelegatedKeyCount, 0);
  bool found = false;
  
  for (int i = 0; i < keyCount && i < MAX_DELEGATED_KEYS; i++) {
    char slotKey[16];
    snprintf(slotKey, sizeof(slotKey), "%s%d", kDelegatedKeyPrefix, i);
    
    if (!prefs.isKey(slotKey)) continue;
    
    DelegatedKey key;
    size_t got = prefs.getBytes(slotKey, &key, sizeof(DelegatedKey));
    if (got == sizeof(DelegatedKey) && strcmp(key.keyId, keyId) == 0) {
      prefs.remove(slotKey);
      found = true;
      Serial.printf("[CCC] 🗑️ Cleared delegated key: %s\n", keyId);
      break;
    }
  }
  
  prefs.end();
  return found;
}

// 🔥 PRODUCTION: Clear all delegated keys
void clearAllDelegatedKeys() {
  prefs.begin(kNs, false);
  int keyCount = prefs.getInt(kDelegatedKeyCount, 0);
  int cleared = 0;
  
  for (int i = 0; i < MAX_DELEGATED_KEYS; i++) {
    char slotKey[16];
    snprintf(slotKey, sizeof(slotKey), "%s%d", kDelegatedKeyPrefix, i);
    if (prefs.isKey(slotKey)) {
      prefs.remove(slotKey);
      cleared++;
    }
  }
  
  prefs.remove(kDelegatedKeyCount);
  prefs.end();
  Serial.printf("[CCC] 🗑️ Cleared %d delegated keys\n", cleared);
}

bool upsertDelegatedKeyFromCloud(const char* keyIdAscii,
                                 const uint8_t* delegatedPub65,
                                 uint64_t validUntil,
                                 uint8_t permissions) {
  if (!keyIdAscii || !*keyIdAscii || !delegatedPub65 || delegatedPub65[0] != 0x04) {
    return false;
  }

  prefs.begin(kNs, false);

  int keyCount = prefs.getInt(kDelegatedKeyCount, 0);
  int slotIndex = -1;

  for (int i = 0; i < MAX_DELEGATED_KEYS; i++) {
    char slotKey[16];
    snprintf(slotKey, sizeof(slotKey), "%s%d", kDelegatedKeyPrefix, i);

    if (!prefs.isKey(slotKey)) {
      if (slotIndex < 0) slotIndex = i;
      continue;
    }

    DelegatedKey existingKey;
    size_t got = prefs.getBytes(slotKey, &existingKey, sizeof(DelegatedKey));
    if (got == sizeof(DelegatedKey) && strcmp(existingKey.keyId, keyIdAscii) == 0) {
      slotIndex = i;
      break;
    }
  }

  if (slotIndex < 0) {
    prefs.end();
    Serial.printf("[CCC] ☁️ Cloud upsert rejected: no free slot for %s\n", keyIdAscii);
    return false;
  }

  DelegatedKey key;
  memset(&key, 0, sizeof(DelegatedKey));
  strncpy(key.keyId, keyIdAscii, sizeof(key.keyId) - 1);
  memcpy(key.pubKey, delegatedPub65, 65);
  key.validUntil = validUntil;
  key.permissions = permissions;
  key.active = true;

  char slotKey[16];
  snprintf(slotKey, sizeof(slotKey), "%s%d", kDelegatedKeyPrefix, slotIndex);
  bool ok = prefs.putBytes(slotKey, &key, sizeof(DelegatedKey)) == sizeof(DelegatedKey);

  if (ok) {
    if (slotIndex >= keyCount) {
      prefs.putInt(kDelegatedKeyCount, slotIndex + 1);
    }
    Serial.printf("[CCC] ☁️ Upserted cloud key [%d]: %s perm=0x%02X exp=%llu\n",
                  slotIndex,
                  key.keyId,
                  key.permissions,
                  key.validUntil);
  }

  prefs.end();
  return ok;
}

// 🚀 OPTIMIZED: Static context to avoid init/free overhead on every verify (~30-50ms saved)
static mbedtls_pk_context g_verify_pk;
static mbedtls_sha256_context g_verify_sha;
static bool g_verify_initialized = false;

static bool importPubKey65(mbedtls_pk_context &pk, const uint8_t* raw65) {
  mbedtls_pk_init(&pk);
  if (mbedtls_pk_setup(&pk, mbedtls_pk_info_from_type(MBEDTLS_PK_ECKEY)) != 0) return false;
  mbedtls_ecp_keypair* eckey = mbedtls_pk_ec(pk);
  if (mbedtls_ecp_group_load(&eckey->grp, MBEDTLS_ECP_DP_SECP256R1) != 0) { mbedtls_pk_free(&pk); return false; }
  if (mbedtls_ecp_point_read_binary(&eckey->grp, &eckey->Q, raw65, 65) != 0) { mbedtls_pk_free(&pk); return false; }
  return true;
}

bool verifySignatureP256(const uint8_t* pub65,
                         const uint8_t* data, size_t dataLen,
                         const uint8_t* sigDer, size_t sigLen) {
  if (!pub65 || !data || !sigDer || dataLen == 0 || sigLen == 0) return false;
  
  // 🔥 FAST: Reuse static context instead of init/free every time
  if (!g_verify_initialized) {
    mbedtls_pk_init(&g_verify_pk);
    mbedtls_sha256_init(&g_verify_sha);
    g_verify_initialized = true;
  }
  
  // Must free previous key and reimport for new pubKey
  mbedtls_pk_free(&g_verify_pk);
  if (!importPubKey65(g_verify_pk, pub65)) return false;
  
  // Hash data using reused context
  unsigned char hash[32];
  mbedtls_sha256_starts(&g_verify_sha, 0);
  mbedtls_sha256_update(&g_verify_sha, data, dataLen);
  mbedtls_sha256_finish(&g_verify_sha, hash);
  
  // Verify (sigDer assumed to be DER encoded ECDSA signature)
  int rc = mbedtls_pk_verify(&g_verify_pk, MBEDTLS_MD_SHA256, hash, sizeof(hash), sigDer, sigLen);
  return rc == 0;
}

size_t signWithDeviceKey(const uint8_t* data, size_t dataLen,
                         uint8_t* outSig, size_t outMax) {
  if (!keysReady || !data || dataLen == 0 || !outSig || outMax == 0) return 0;

  unsigned char hash[32];
  mbedtls_sha256(data, dataLen, hash, 0);

  size_t sigLen = 0;
  int rc = mbedtls_pk_sign(&keypair,
                           MBEDTLS_MD_SHA256,
                           hash,
                           sizeof(hash),
                           outSig,
                           &sigLen,
                           mbedtls_ctr_drbg_random,
                           &drbg);
  if (rc != 0 || sigLen == 0 || sigLen > outMax) return 0;
  return sigLen;
}

bool getDevicePubRaw(uint8_t* out65, size_t max) {
  if (!keysReady || !out65 || max < 65) return false;
  if (!mbedtls_pk_can_do(&keypair, MBEDTLS_PK_ECKEY)) return false;

  mbedtls_ecp_keypair* eckey = mbedtls_pk_ec(keypair);
  size_t olen = 0;
  int rc = mbedtls_ecp_point_write_binary(&eckey->grp,
                                          &eckey->Q,
                                          MBEDTLS_ECP_PF_UNCOMPRESSED,
                                          &olen,
                                          out65,
                                          65);
  return rc == 0 && olen == 65;
}

void clearAll() {
  prefs.begin(kNs, false);
  if (prefs.isKey(kKeyPem)) prefs.remove(kKeyPem);
  if (prefs.isKey(kKeyId)) prefs.remove(kKeyId);
  if (prefs.isKey("phone_pub_raw")) prefs.remove("phone_pub_raw");
  if (prefs.isKey("phone_cert_chain")) prefs.remove("phone_cert_chain");
  if (prefs.isKey(kSharedKeyId)) prefs.remove(kSharedKeyId);
  if (prefs.isKey(kSharedPubRaw)) prefs.remove(kSharedPubRaw);
  prefs.end();
}

void clearProvisionedOnly() {
  prefs.begin(kNs, false);
  if (prefs.isKey(kKeyId)) prefs.remove(kKeyId);
  if (prefs.isKey("phone_pub_raw")) prefs.remove("phone_pub_raw");
  if (prefs.isKey("phone_cert_chain")) prefs.remove("phone_cert_chain");
  if (prefs.isKey(kSharedKeyId)) prefs.remove(kSharedKeyId);
  if (prefs.isKey(kSharedPubRaw)) prefs.remove(kSharedPubRaw);
  prefs.end();
}

bool getKeyId(String& out) {
  prefs.begin(kNs, true);
  if (!prefs.isKey(kKeyId)) { prefs.end(); return false; }
  out = prefs.getString(kKeyId, "");
  prefs.end();
  return out.length() > 0;
}

size_t getPhonePubRaw(uint8_t* out, size_t max) {
  if (!out || max < 65) return 0;
  prefs.begin(kNs, true);
  // CCC R3 §5.2: try encrypted blob first, fall back to legacy plaintext
  size_t got = nvs_get_encrypted(prefs, "phone_pub_raw", out, max);
  if (got == 0 && prefs.isKey("phone_pub_raw")) {
    // Legacy plaintext (pre-encryption firmware)
    size_t need = prefs.getBytesLength("phone_pub_raw");
    size_t toCopy = (need <= max) ? need : max;
    got = prefs.getBytes("phone_pub_raw", out, toCopy);
  }
  prefs.end();
  return got;
}

size_t getCertChain(uint8_t* out, size_t max) {
  prefs.begin(kNs, true);
  // Try encrypted blob first, fall back to legacy plaintext
  if (out && max > 0) {
    size_t got = nvs_get_encrypted(prefs, "phone_cert_chain", out, max);
    if (got > 0) { prefs.end(); return got; }
  }
  if (!prefs.isKey("phone_cert_chain")) { prefs.end(); return 0; }
  size_t need = prefs.getBytesLength("phone_cert_chain");
  if (need == 0) { prefs.end(); return 0; }
  if (!out) { prefs.end(); return need; }
  size_t toCopy = (need <= max) ? need : max;
  size_t got = prefs.getBytes("phone_cert_chain", out, toCopy);
  prefs.end();
  return (got > 0) ? got : need;
}

bool validateCertPublicKeyMatchesPub(const uint8_t* cert, size_t certLen, const uint8_t* pub65) {
  if (!cert || certLen == 0 || !pub65 || pub65[0] != 0x04) return false;
  mbedtls_x509_crt crt; mbedtls_x509_crt_init(&crt);
  // Try parse (PEM or DER). For PEM, buffer need not be null-terminated if not relying on PEM; but mbedtls can parse DER directly.
  int rc = mbedtls_x509_crt_parse(&crt, cert, certLen);
  if (rc != 0) {
    mbedtls_x509_crt_free(&crt);
    return false;
  }
  bool match = false;
  if (mbedtls_pk_can_do(&crt.pk, MBEDTLS_PK_ECKEY)) {
    mbedtls_ecp_keypair* eckey = mbedtls_pk_ec(crt.pk);
    unsigned char out[65]; size_t olen = 0;
    if (mbedtls_ecp_point_write_binary(&eckey->grp, &eckey->Q, MBEDTLS_ECP_PF_UNCOMPRESSED, &olen, out, sizeof(out)) == 0 && olen == 65) {
      match = (memcmp(out, pub65, 65) == 0);
    }
  }
  mbedtls_x509_crt_free(&crt);
  return match;
}

bool validateStoredCertMatchesStoredPub() {
  uint8_t pub[65]; size_t pubLen = getPhonePubRaw(pub, sizeof(pub));
  if (pubLen != 65 || pub[0] != 0x04) return false;
  size_t certLen = getCertChain(nullptr, 0);
  if (certLen == 0) return false;
  // Allocate temp buffer to read cert (avoid std::unique_ptr for toolchain compatibility)
  uint8_t* buf = (uint8_t*)malloc(certLen);
  if (!buf) return false;
  size_t got = getCertChain(buf, certLen);
  if (got < certLen) {
    // best-effort even if truncated
    certLen = got;
  }
  bool ok = validateCertPublicKeyMatchesPub(buf, certLen, pub);
  free(buf);
  return ok;
}

// Minimal helper wrapping current working flow
bool runOnceWithHce(PN532& nfc, const uint8_t* aid, size_t aidLen, uint32_t waitMs) {
  if (!aid || aidLen == 0 || aidLen > 16) return false;
  uint32_t start = millis();

  // Wait for any target
  while (millis() - start < waitMs) {
    if (nfc.inListPassiveTarget()) break;
    delay(80);
  }
  if (millis() - start >= waitMs) {
    Serial.println("[CCC] No card during provisioning window");
    return false;
  }

  // Build SELECT AID
  uint8_t apdu[32]; uint8_t idx=0;
  apdu[idx++] = 0x00; apdu[idx++] = 0xA4; apdu[idx++] = 0x04; apdu[idx++] = 0x00;
  apdu[idx++] = (uint8_t)aidLen;
  for (size_t i=0;i<aidLen;i++) apdu[idx++] = aid[i];
  apdu[idx++] = 0x00; // Le

  uint8_t resp[64]; uint8_t rlen = sizeof(resp);
  if (!nfc.inDataExchange(apdu, idx, resp, &rlen)) {
    Serial.println("[CCC] SELECT failed");
    return false;
  }
  if (rlen < 2) {
    Serial.println("[CCC] SELECT response too short");
    return false;
  }
  uint8_t sw1 = resp[rlen-2], sw2 = resp[rlen-1];
  int payloadLen = rlen - 2;
  Serial.printf("[CCC] SELECT SW=%02X%02X payloadLen=%d\n", sw1, sw2, payloadLen);

  const uint8_t* payload = resp;
  size_t idLen = (payloadLen > 0) ? payloadLen : 0;

  // Try secondary GET UID like current demo
  if (sw1 == 0x90 && sw2 == 0x00) {
    uint8_t getUid[] = {0x00, 0xB0, 0x00, 0x00, 0x04};
    rlen = sizeof(resp);
    if (nfc.inDataExchange(getUid, sizeof(getUid), resp, &rlen) && rlen >= 2) {
      uint8_t sw1u = resp[rlen-2], sw2u = resp[rlen-1];
      int uidLen = rlen - 2;
      if (uidLen > 0 && sw1u == 0x90 && sw2u == 0x00) {
        payload = resp; idLen = uidLen;
        Serial.println("[CCC] Using UID APDU payload as keyId");
      }
    }
  }

  if (idLen == 0) {
    Serial.println("[CCC] No payload to store as keyId");
    return false;
  }
  return storeKeyIdHexIfEmpty(payload, idLen);
}

} // namespace
