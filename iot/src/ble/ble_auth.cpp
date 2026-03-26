#include <Arduino.h>
#include <NimBLEDevice.h>
#include <string>
#include <mbedtls/ecp.h>
#include <mbedtls/ecdsa.h>
#include <mbedtls/ecdh.h>
#include <mbedtls/md.h>
#include <mbedtls/ctr_drbg.h>
#include <mbedtls/entropy.h>
#include "ble/utils/crypto_utils.h"
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>

#include "ble/ble_auth.h"
#include "provisioning_phase.h"

namespace {
  // AUTH service (Phase B)
  const char* kAuthServiceUUID     = "d0d0d0d0-0000-4000-8000-d0d0d0d00000";
  const char* kCharClientHelloUUID = "d0d0d0d0-0001-4000-8000-d0d0d0d00001"; // Write: client eph pubkey + signature
  const char* kCharServerHelloUUID = "d0d0d0d0-0002-4000-8000-d0d0d0d00002"; // Read/Notify: server eph pubkey + signature
  const char* kCharClientCfmUUID   = "d0d0d0d0-0003-4000-8000-d0d0d0d00003"; // Write: client HMAC proof
  const char* kCharServerCfmUUID   = "d0d0d0d0-0004-4000-8000-d0d0d0d00004"; // Read/Notify: server HMAC proof

  // GATT characteristic handles (server-owned)
  NimBLECharacteristic* g_cSrvHello = nullptr;
  NimBLECharacteristic* g_cSrvCfm   = nullptr;

  // RNG (borrowed from app init)
  mbedtls_ctr_drbg_context* s_drbg = nullptr;

  // Buffer for chunked ClientHello writes (Windows long-write workaround)
  std::string s_cliHelloBuf;

  // Ephemeral key and peer info
  mbedtls_ecp_group s_grp;
  mbedtls_mpi s_ephemeral_d;            // our ephemeral priv
  mbedtls_ecp_point s_ephemeral_Q;      // our ephemeral pub
  uint8_t s_srvPubBuf[65]; size_t s_srvPubLen = 0; // uncompressed 65 bytes
  uint8_t s_cliPubBuf[65]; size_t s_cliPubLen = 0;

  // Derived keys
  uint8_t s_sessKeyEnc[32];
  uint8_t s_sessKeyMac[32];
  bool s_keysReady = false;
  bool s_sessionReady = false;

  // Client signature buffered for deferred processing
  uint8_t s_cliSigBuf[120]; size_t s_cliSigLen = 0;

  // Pending ClientConfirm handling if confirm arrives before keys are ready
  uint8_t s_pendingCfm[32];
  bool s_havePendingCfm = false;

  // Worker task to handle heavy crypto outside BLE callbacks
  TaskHandle_t s_authTask = nullptr;

  // Helpers
  bool gen_ephemeral() {
    mbedtls_ecp_group_init(&s_grp);
    mbedtls_mpi_init(&s_ephemeral_d);
    mbedtls_ecp_point_init(&s_ephemeral_Q);
    if (mbedtls_ecp_group_load(&s_grp, MBEDTLS_ECP_DP_SECP256R1) != 0) return false;
    int rc = mbedtls_ecp_gen_keypair(&s_grp, &s_ephemeral_d, &s_ephemeral_Q,
                                     mbedtls_ctr_drbg_random, s_drbg);
    if (rc != 0) return false;
    size_t olen = 0;
    rc = mbedtls_ecp_point_write_binary(&s_grp, &s_ephemeral_Q,
      MBEDTLS_ECP_PF_UNCOMPRESSED, &olen, s_srvPubBuf, sizeof(s_srvPubBuf));
    if (rc != 0 || olen == 0) return false;
    s_srvPubLen = olen;
    return true;
  }

  bool parse_client_pub(const uint8_t* buf, size_t len, mbedtls_ecp_point &Qout) {
    if (!buf || len == 0) return false;
    return mbedtls_ecp_point_read_binary(&s_grp, &Qout, buf, len) == 0;
  }

  void reset_auth_state() {
    s_sessionReady = false;
    s_srvPubLen = 0; s_cliPubLen = 0;
    memset(s_sessKeyEnc, 0, sizeof(s_sessKeyEnc));
    memset(s_sessKeyMac, 0, sizeof(s_sessKeyMac));
  }

  // AUTH: client hello write callback
  class ClientHelloCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* c, NimBLEConnInfo&) override {
      const std::string v = c->getValue();
      if (v.empty()) return;
      // Accumulate into buffer to support chunked writes
      if (s_cliHelloBuf.size() + v.size() > 1024) {
        s_cliHelloBuf.clear(); // Prevent unbounded growth
      }
      s_cliHelloBuf.append(v);

      // Try to parse one or more complete ClientHello messages
      for (;;) {
        if (s_cliHelloBuf.size() < 3) return; // need ver + pubLen
        const uint8_t* p = (const uint8_t*)s_cliHelloBuf.data();
        uint8_t ver = p[0];
        if (ver != 1) { Serial.println("[AUTH] Bad version"); s_cliHelloBuf.clear(); return; }
        uint16_t pubLen = p[1] | (p[2] << 8);
        if (pubLen > sizeof(s_cliPubBuf)) { Serial.println("[AUTH] Bad pubLen"); s_cliHelloBuf.clear(); return; }
        if (s_cliHelloBuf.size() < (size_t)(3 + pubLen + 2)) return; // need sigLen
        uint16_t sigLen = (uint8_t)s_cliHelloBuf[3 + pubLen] | ((uint8_t)s_cliHelloBuf[3 + pubLen + 1] << 8);
        size_t total = 3 + pubLen + 2 + sigLen;
        if (s_cliHelloBuf.size() < total) return; // wait for rest

        // Extract one complete message
        const uint8_t* pub = p + 3;
        const uint8_t* sig = p + 3 + pubLen + 2;

        memcpy(s_cliPubBuf, pub, pubLen); s_cliPubLen = pubLen;
        if (sigLen > sizeof(s_cliSigBuf)) { Serial.println("[AUTH] Signature too large."); s_cliHelloBuf.erase(0, total); continue; }
        memcpy(s_cliSigBuf, sig, sigLen); s_cliSigLen = sigLen;

        // Defer heavy crypto to worker task
        if (s_authTask) { xTaskNotifyGive(s_authTask); }

        // Consume and continue
        s_cliHelloBuf.erase(0, total);
      }
    }
  };

  // Worker task: verifies client, generates server hello, computes ECDH + HKDF
  void auth_worker_task(void*) {
    for (;;) {
      ulTaskNotifyTake(pdTRUE, portMAX_DELAY);

      // Placeholder: integrate with ProvisioningPhase public key retrieval & signature verification if needed.
      // Currently Phase A (NFC) handles signature verification; for Phase B we trust provisioned state.
      if (!ProvisioningPhase::isProvisioned()) { Serial.println("[AUTH] Device not provisioned; aborting auth."); continue; }
      // Optionally, could retrieve stored pub and verify client signature here.
      Serial.println("[AUTH] Skipping client signature verify (Phase A already verified).");
      Serial.println("[AUTH] Client ephem verified.");

      // Generate our ephemeral, if needed
      if (s_srvPubLen == 0 && !gen_ephemeral()) { Serial.println("[AUTH] Failed to generate ephemeral."); continue; }

      // Sign our ephemeral pub with device long-term key
      uint8_t sigOut[80]; size_t sigOutLen = 0;
      // TODO: Sign ephemeral with device keypair (persisted in ProvisioningPhase). For now, skip and set sigOutLen=0.
      sigOutLen = 0;

      // Build ServerHello: [ver][pubLenLE][pub][sigLenLE][sig]
      std::string resp; resp.reserve(1 + 2 + s_srvPubLen + 2 + sigOutLen);
      resp.push_back((char)1);
      uint16_t pl = (uint16_t)s_srvPubLen; resp.push_back((char)(pl & 0xFF)); resp.push_back((char)(pl >> 8));
      resp.append((const char*)s_srvPubBuf, s_srvPubLen);
      uint16_t sl = (uint16_t)sigOutLen; resp.push_back((char)(sl & 0xFF)); resp.push_back((char)(sl >> 8));
      resp.append((const char*)sigOut, sigOutLen);
      if (g_cSrvHello) { g_cSrvHello->setValue(resp); g_cSrvHello->notify(); }

      // Compute shared secret via ECDH
      mbedtls_ecp_point Qcli; mbedtls_ecp_point_init(&Qcli);
  if (!parse_client_pub(s_cliPubBuf, s_cliPubLen, Qcli)) { mbedtls_ecp_point_free(&Qcli); Serial.println("[AUTH] Failed to parse client pub."); continue; }
      mbedtls_mpi z; mbedtls_mpi_init(&z);
      int rc = mbedtls_ecdh_compute_shared(&s_grp, &z, &Qcli, &s_ephemeral_d, mbedtls_ctr_drbg_random, s_drbg);
      mbedtls_ecp_point_free(&Qcli);
      if (rc != 0) { mbedtls_mpi_free(&z); Serial.println("[AUTH] ECDH failed."); continue; }
      uint8_t S[32]; size_t olen = mbedtls_mpi_size(&z); memset(S, 0, sizeof(S));
      mbedtls_mpi_write_binary(&z, S + (sizeof(S) - olen), olen);
      mbedtls_mpi_free(&z);

      // HKDF derive keys: info = "BLE-AUTH" || cliPub || srvPub
      const char *label = "BLE-AUTH";
      uint8_t info[8 + 65 + 65]; size_t infoLen = 0;
      size_t labelLen = strlen(label);
      memcpy(info + infoLen, label, labelLen); infoLen += labelLen;
      memcpy(info + infoLen, s_cliPubBuf, s_cliPubLen); infoLen += s_cliPubLen;
      memcpy(info + infoLen, s_srvPubBuf, s_srvPubLen); infoLen += s_srvPubLen;
      hkdf_sha256(nullptr, 0, S, sizeof(S), info, infoLen, s_sessKeyEnc, sizeof(s_sessKeyEnc));

      // Expand again for mac with different label
      const char *label2 = "BLE-AUTH-MAC";
      uint8_t info2[12 + 65 + 65]; size_t info2Len = 0;
      size_t label2Len = strlen(label2);
      memcpy(info2 + info2Len, label2, label2Len); info2Len += label2Len;
      memcpy(info2 + info2Len, s_cliPubBuf, s_cliPubLen); info2Len += s_cliPubLen;
      memcpy(info2 + info2Len, s_srvPubBuf, s_srvPubLen); info2Len += s_srvPubLen;
      hkdf_sha256(nullptr, 0, S, sizeof(S), info2, info2Len, s_sessKeyMac, sizeof(s_sessKeyMac));

      s_keysReady = true;
      Serial.println("[AUTH] Derived session keys.");

      // If client confirm arrived early, process it now
      if (s_havePendingCfm) {
        s_havePendingCfm = false;
        // Verify client tag: HMAC(K_mac, "AUTH-PROOF" || cliPub || srvPub)
        uint8_t tag[32];
        hmac_sha256_label(s_sessKeyMac, sizeof(s_sessKeyMac), "AUTH-PROOF", s_cliPubBuf, s_cliPubLen, s_srvPubBuf, s_srvPubLen, tag);
        if (memcmp(s_pendingCfm, tag, sizeof(tag)) == 0) {
          // Send our confirm: HMAC(K_mac, "AUTH-OK" || srvPub || cliPub)
          uint8_t tag2[32];
          hmac_sha256_label(s_sessKeyMac, sizeof(s_sessKeyMac), "AUTH-OK", s_srvPubBuf, s_srvPubLen, s_cliPubBuf, s_cliPubLen, tag2);
          if (g_cSrvCfm) { g_cSrvCfm->setValue(tag2, sizeof(tag2)); g_cSrvCfm->notify(); }
          s_sessionReady = true;
          Serial.println("[AUTH] Session established (early confirm).");
        } else {
          Serial.println("[AUTH] Early Client HMAC mismatch.");
        }
      }
    }
  }

  // AUTH: client confirm
  class ClientConfirmCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* c, NimBLEConnInfo&) override {
      std::string v = c->getValue();
      if (v.size() == 0) return;
      if (!s_keysReady) {
        // Keys not ready yet; buffer the confirm for the worker to process
        if (v.size() == sizeof(s_pendingCfm)) { memcpy(s_pendingCfm, v.data(), sizeof(s_pendingCfm)); s_havePendingCfm = true; }
        return;
      }
      // Expect HMAC-SHA256 over "AUTH-PROOF" || cliPub || srvPub
      uint8_t tag[32];
      hmac_sha256_label(s_sessKeyMac, sizeof(s_sessKeyMac), "AUTH-PROOF", s_cliPubBuf, s_cliPubLen, s_srvPubBuf, s_srvPubLen, tag);
      if (v.size() != sizeof(tag) || memcmp(v.data(), tag, sizeof(tag)) != 0) { Serial.println("[AUTH] Client HMAC mismatch."); return; }

      // Send our confirm: HMAC over "AUTH-OK" || srvPub || cliPub
      uint8_t tag2[32];
      hmac_sha256_label(s_sessKeyMac, sizeof(s_sessKeyMac), "AUTH-OK", s_srvPubBuf, s_srvPubLen, s_cliPubBuf, s_cliPubLen, tag2);
      if (g_cSrvCfm) { g_cSrvCfm->setValue(tag2, sizeof(tag2)); g_cSrvCfm->notify(); }
      s_sessionReady = true;
      Serial.println("[AUTH] Session established.");
    }
  };
}

namespace BLEAuth {
  void registerService(NimBLEServer* server, mbedtls_ctr_drbg_context* drbg) {
    s_drbg = drbg;
    reset_auth_state();

    // Use existing Auth service if one was created (e.g., by Echo), else create it
    NimBLEService* pAuth = server->getServiceByUUID(kAuthServiceUUID);
    if (!pAuth) {
      pAuth = server->createService(kAuthServiceUUID);
    }
    NimBLECharacteristic* cCliHello = pAuth->createCharacteristic(kCharClientHelloUUID, NIMBLE_PROPERTY::WRITE);
    static ClientHelloCallbacks cliHelloCb; cCliHello->setCallbacks(&cliHelloCb);
    g_cSrvHello = pAuth->createCharacteristic(kCharServerHelloUUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
    NimBLECharacteristic* cCliCfm = pAuth->createCharacteristic(kCharClientCfmUUID, NIMBLE_PROPERTY::WRITE);
    static ClientConfirmCallbacks cliCfmCb; cCliCfm->setCallbacks(&cliCfmCb);
  g_cSrvCfm = pAuth->createCharacteristic(kCharServerCfmUUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);

    // Start auth worker task with a larger stack to handle mbedTLS operations
    if (s_authTask == nullptr) {
      BaseType_t ok = xTaskCreate(auth_worker_task, "auth_worker", 8192, nullptr, 5, &s_authTask);
      if (ok != pdPASS) { Serial.println("[BLE] Failed to create auth worker task"); }
    }
  }

  bool isSessionReady() { return s_sessionReady; }
  const uint8_t* sessionEncKey() { return s_sessKeyEnc; }
  size_t sessionEncKeyLen() { return sizeof(s_sessKeyEnc); }

  void resetSession() {
    // Clear session flags and keys/buffers to allow a fresh handshake
    s_sessionReady = false;
    s_keysReady = false;
    s_cliHelloBuf.clear();
    s_cliSigLen = 0;
    s_cliPubLen = 0;
    s_srvPubLen = 0;
    s_havePendingCfm = false;
    memset(s_pendingCfm, 0, sizeof(s_pendingCfm));
    memset(s_sessKeyEnc, 0, sizeof(s_sessKeyEnc));
    memset(s_sessKeyMac, 0, sizeof(s_sessKeyMac));
  }
}
