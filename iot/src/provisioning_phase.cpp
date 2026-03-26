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
#include "provisioning_phase.h"

namespace {
  Preferences prefs;
  const char* kNs = "prov";
  const char* kKeyPem = "ec_priv";
  const char* kKeyId  = "key_id";

  mbedtls_pk_context keypair;
  mbedtls_entropy_context entropy;
  mbedtls_ctr_drbg_context drbg;
  bool keysReady = false;

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
        Serial.println("[PhaseA] Loaded existing ECC keypair");
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
      Serial.println("[PhaseA] Generated ECC keypair and saved to NVS");
    }
    return true;
  }
}

namespace ProvisioningPhase {

void begin() {
  keysReady = ensureKeypair();
  if (!keysReady) Serial.println("[PhaseA] ECC init failed (continuing without keys)");
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
  if (ok) Serial.printf("[PhaseA] Stored phone keyId as hex: %s\n", hex.c_str());
  return ok;
}

bool storeKeyIdAsciiIfEmpty(const char* ascii) {
  if (!ascii || !*ascii) return false;
  if (isProvisioned()) return false;
  prefs.begin(kNs, false);
  bool ok = prefs.putString(kKeyId, ascii) > 0;
  prefs.end();
  if (ok) Serial.printf("[PhaseA] Stored phone keyId (ascii): %s\n", ascii);
  return ok;
}

bool storeKeyIdAsciiForce(const char* ascii) {
  if (!ascii || !*ascii) return false;
  prefs.begin(kNs, false);
  bool ok = prefs.putString(kKeyId, ascii) > 0;
  prefs.end();
  if (ok) Serial.printf("[PhaseA] Force-stored phone keyId (ascii): %s\n", ascii);
  return ok;
}

bool storePhonePubRaw(const uint8_t* pub65) {
  if (!pub65 || pub65[0] != 0x04) return false;
  prefs.begin(kNs, false);
  bool ok = prefs.putBytes("phone_pub_raw", pub65, 65) == 65;
  prefs.end();
  if (ok) Serial.println("[PhaseA] Stored phone public key raw (65 bytes) in NVS");
  return ok;
}

bool storeCertChain(const uint8_t* cert, size_t certLen) {
  if (!cert || certLen == 0) return false;
  prefs.begin(kNs, false);
  bool ok = prefs.putBytes("phone_cert_chain", cert, certLen) == certLen;
  prefs.end();
  if (ok) Serial.printf("[PhaseA] Stored phone cert chain (%u bytes) in NVS\n", (unsigned)certLen);
  return ok;
}

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
  mbedtls_pk_context pk; if (!importPubKey65(pk, pub65)) return false;
  // Hash data
  unsigned char hash[32];
  mbedtls_sha256_context c; mbedtls_sha256_init(&c);
  mbedtls_sha256_starts(&c, 0);
  mbedtls_sha256_update(&c, data, dataLen);
  mbedtls_sha256_finish(&c, hash);
  mbedtls_sha256_free(&c);
  // Verify (sigDer assumed to be DER encoded ECDSA signature)
  int rc = mbedtls_pk_verify(&pk, MBEDTLS_MD_SHA256, hash, sizeof(hash), sigDer, sigLen);
  mbedtls_pk_free(&pk);
  return rc == 0;
}

void clearAll() {
  prefs.begin(kNs, false);
  if (prefs.isKey(kKeyPem)) prefs.remove(kKeyPem);
  if (prefs.isKey(kKeyId)) prefs.remove(kKeyId);
  if (prefs.isKey("phone_pub_raw")) prefs.remove("phone_pub_raw");
  if (prefs.isKey("phone_cert_chain")) prefs.remove("phone_cert_chain");
  prefs.end();
}

void clearProvisionedOnly() {
  prefs.begin(kNs, false);
  if (prefs.isKey(kKeyId)) prefs.remove(kKeyId);
  if (prefs.isKey("phone_pub_raw")) prefs.remove("phone_pub_raw");
  if (prefs.isKey("phone_cert_chain")) prefs.remove("phone_cert_chain");
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
  prefs.begin(kNs, true);
  if (!prefs.isKey("phone_pub_raw")) { prefs.end(); return 0; }
  size_t need = prefs.getBytesLength("phone_pub_raw");
  if (need == 0 || !out || max == 0) { prefs.end(); return 0; }
  size_t toCopy = (need <= max) ? need : max;
  size_t got = prefs.getBytes("phone_pub_raw", out, toCopy);
  prefs.end();
  return got;
}

size_t getCertChain(uint8_t* out, size_t max) {
  prefs.begin(kNs, true);
  if (!prefs.isKey("phone_cert_chain")) { prefs.end(); return 0; }
  size_t need = prefs.getBytesLength("phone_cert_chain");
  if (need == 0) { prefs.end(); return 0; }
  if (!out) { prefs.end(); return need; }
  size_t toCopy = (need <= max) ? need : max;
  size_t got = prefs.getBytes("phone_cert_chain", out, toCopy);
  prefs.end();
  return (got > 0) ? got : need; // if truncated, caller sees at least required length via earlier branch
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
    Serial.println("[PhaseA] No card during provisioning window");
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
    Serial.println("[PhaseA] SELECT failed");
    return false;
  }
  if (rlen < 2) {
    Serial.println("[PhaseA] SELECT response too short");
    return false;
  }
  uint8_t sw1 = resp[rlen-2], sw2 = resp[rlen-1];
  int payloadLen = rlen - 2;
  Serial.printf("[PhaseA] SELECT SW=%02X%02X payloadLen=%d\n", sw1, sw2, payloadLen);

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
        Serial.println("[PhaseA] Using UID APDU payload as keyId");
      }
    }
  }

  if (idLen == 0) {
    Serial.println("[PhaseA] No payload to store as keyId");
    return false;
  }
  return storeKeyIdHexIfEmpty(payload, idLen);
}

} // namespace
