#include <Arduino.h>
#include <NimBLEDevice.h>
#include <string>
#include <cstring>

#include "../../include/ble/ble_admin.h"
#include "../../include/ble/ble_auth.h"
#include "../../include/ble/utils/crypto_utils.h"
#include "provisioning_phase.h"
#include "../../include/nfc_session.h"
#include "../../include/uwb_ranging.h"


namespace {
  // Admin service (preserved for NFC admin operations)
  const char* kAdminServiceUUID   = "9a9b9c9d-0000-4000-8000-9a9b9c9d0000";
  const char* kAdminModeCharUUID  = "9a9b9c9d-0001-4000-8000-9a9b9c9d0001"; // 0=normal,1=enroll,2=remove
  const char* kAdminCmdCharUUID   = "9a9b9c9d-0002-4000-8000-9a9b9c9d0002"; // write-only small commands
  const char* kAdminInfoCharUUID  = "9a9b9c9d-0003-4000-8000-9a9b9c9d0003"; // read/notify info
  const char* kAdminPhoneKeyUUID  = "9a9b9c9d-0004-4000-8000-9a9b9c9d0004"; // write-only: phone PEM (supports chunking)

  // Admin state
  BLEMod::AdminMode s_adminMode = BLEMod::ADMIN_NORMAL;
  NimBLECharacteristic* g_cAdminMode = nullptr;
  NimBLECharacteristic* g_cAdminInfo = nullptr;

  // CCC R3 Replay Protection: monotonic sequence counters
  static uint32_t s_txSeq = 0;   // ECU→Android notification counter
  static uint32_t s_rxSeq = 0;   // Android→ECU command counter

  // Secure notify: AES-256-GCM when BLE session is live, plain text otherwise.
  // Encrypted format: [0xAE][seq_be4][ciphertext][tag16]
  // Receiver reconstructs nonce as: [seq_be4][0x00 * 8]
  static void sendSecureNotify(NimBLECharacteristic* c, const char* msg) {
    if (!c || !msg) return;
    const uint8_t* kenc = BLEAuth::sessionEncKey();
    if (BLEAuth::isSessionReady() && kenc != nullptr) {
      s_txSeq++;
      uint8_t nonce[12] = {};
      nonce[0] = (s_txSeq >> 24) & 0xFF;
      nonce[1] = (s_txSeq >> 16) & 0xFF;
      nonce[2] = (s_txSeq >>  8) & 0xFF;
      nonce[3] = (s_txSeq      ) & 0xFF;
      const uint8_t aad[3] = { 'A', 'D', 'M' };
      size_t msgLen = strlen(msg);
      // buf: [0xAE=1][seq=4][ciphertext=msgLen][tag=16]
      size_t outLen = 1 + 4 + msgLen + 16;
      uint8_t* buf = (uint8_t*)malloc(outLen);
      if (!buf) { c->setValue(msg); c->notify(); return; }
      buf[0] = 0xAE;
      buf[1] = nonce[0]; buf[2] = nonce[1]; buf[3] = nonce[2]; buf[4] = nonce[3];
      uint8_t tag[16];
      bool ok = aes_gcm_encrypt(kenc, nonce, aad, 3,
                                (const uint8_t*)msg, msgLen,
                                buf + 5, tag);
      if (ok) {
        memcpy(buf + 5 + msgLen, tag, 16);
        c->setValue(buf, outLen);
        c->notify();
      } else {
        c->setValue(msg);
        c->notify();
      }
      free(buf);
    } else {
      c->setValue(msg);
      c->notify();
    }
  }

  // Admin mode characteristic: read/write 1 byte
  class AdminModeCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* c, NimBLEConnInfo&) override {
      std::string v = c->getValue(); if (v.empty()) return;
      uint8_t m = static_cast<uint8_t>(v[0]); if (m > 2) m = 0;
      s_adminMode = static_cast<BLEMod::AdminMode>(m);
      Serial.printf("[BLE-Admin] Mode set to %u\n", (unsigned)m);
      if (g_cAdminMode) g_cAdminMode->setValue(&m, 1);
    }
  };

  // Admin command characteristic
  class AdminCmdCallbacks : public NimBLECharacteristicCallbacks {
    NimBLECharacteristic* _info;
   public:
    explicit AdminCmdCallbacks(NimBLECharacteristic* info): _info(info) {}
    void onWrite(NimBLECharacteristic* c, NimBLEConnInfo&) override {
      std::string v = c->getValue(); if (v.empty()) return;

      // Robust command parsing:
      // - If user writes a single byte, use that as the command.
      // - If user writes ASCII hex ("01","02","10","20","33".."36"), parse to the corresponding byte.
      // - Also accept "0xNN"/"0XNN" form.
      auto isHex = [](char c){ return (c>='0'&&c<='9')||(c>='a'&&c<='f')||(c>='A'&&c<='F'); };
      auto hexVal = [](char c)->uint8_t{
        if (c>='0'&&c<='9') return (uint8_t)(c-'0');
        if (c>='a'&&c<='f') return (uint8_t)(10 + (c-'a'));
        if (c>='A'&&c<='F') return (uint8_t)(10 + (c-'A'));
        return 0;
      };
      uint8_t raw0 = static_cast<uint8_t>(v[0]);
      uint8_t cmd = raw0;
      bool asciiHexParsed = false;
      if (v.size() == 2 && isHex(v[0]) && isHex(v[1])) {
        cmd = (uint8_t)((hexVal(v[0]) << 4) | hexVal(v[1]));
        asciiHexParsed = true;
      } else if (v.size() == 4 && v[0] == '0' && (v[1] == 'x' || v[1] == 'X') && isHex(v[2]) && isHex(v[3])) {
        cmd = (uint8_t)((hexVal(v[2]) << 4) | hexVal(v[3]));
        asciiHexParsed = true;
      }
      Serial.printf("[BLE-Admin] Cmd write: raw0=0x%02X len=%u parsedHex=%s -> cmd=0x%02X\n", raw0, (unsigned)v.size(), asciiHexParsed?"YES":"NO", cmd);

      // CCC R3 Replay Protection: sequenced secure command [0xAD][seq_be4][cmd_byte]
      if (v.size() == 6 && static_cast<uint8_t>(v[0]) == 0xAD) {
        uint32_t rxSeq = ((uint8_t)v[1] << 24) | ((uint8_t)v[2] << 16) |
                         ((uint8_t)v[3] <<  8) |  (uint8_t)v[4];
        if (rxSeq <= s_rxSeq) {
          Serial.printf("[BLE-Admin] REPLAY REJECTED: seq=%u <= last=%u\n", rxSeq, s_rxSeq);
          sendSecureNotify(_info, "ERR:REPLAY");
          return;
        }
        s_rxSeq = rxSeq;
        cmd = static_cast<uint8_t>(v[5]);
        Serial.printf("[BLE-Admin] Seq cmd: seq=%u cmd=0x%02X\n", rxSeq, cmd);
      }

      switch (cmd) {
        case 0x01:
          // Clear phone provisioning only (equivalent to 'r')
          ProvisioningPhase::clearProvisionedOnly();
          sendSecureNotify(_info, "CLEARED_PROVISIONED");
          break;
        case 0x02:
          // Clear everything, including device keypair (equivalent to 'C')
          ProvisioningPhase::clearAll();
          sendSecureNotify(_info, "CLEARED_ALL");
          break;
        case 0x10:
          // Listing tags not supported in new provisioning module
          sendSecureNotify(_info, "NOT_SUPPORTED");
          break;
        case 0x20:
          // Trigger NFC provisioning flow: our NFC loop auto-runs; we just inform the app
          sendSecureNotify(_info, "NFC_TAP_TO_PROVISION");
          Serial.println("[BLE Admin] Notify: Tap phone at reader to provision.");
          break;
        case 0x30: // Persistent force ON
          NfcSession::setPersistentForce(true);
          sendSecureNotify(_info, "FORCE_PERSIST_ON");
          break;
        case 0x31: // Persistent force OFF
          NfcSession::setPersistentForce(false);
          sendSecureNotify(_info, "FORCE_PERSIST_OFF");
          break;
        case 0x32: // Arm one-shot force
          NfcSession::armOneShotForce();
          sendSecureNotify(_info, "FORCE_ONESHOT_ARMED");
          break;
        case 0x33: { // Status query
          bool prov = ProvisioningPhase::isProvisioned();
          bool pf = NfcSession::getPersistentForce();
          bool os = NfcSession::isOneShotArmed();
          char buf[48];
          snprintf(buf, sizeof(buf), "STATUS P=%s PF=%s OS=%s", prov?"YES":"NO", pf?"ON":"OFF", os?"ARMED":"NO");
          sendSecureNotify(_info, buf);
          Serial.printf("[BLE-Admin] %s\n", buf);
          break; }
        case 0x34: { // Validate cert vs pub
          bool ok = ProvisioningPhase::validateStoredCertMatchesStoredPub();
          sendSecureNotify(_info, ok?"CERT_MATCH":"CERT_MISMATCH");
          Serial.printf("[BLE-Admin] Cert vs pub: %s\n", ok?"MATCH":"MISMATCH/NA");
          break; }
        case 0x35: { // Pub key presence
          uint8_t pub[65]; size_t len = ProvisioningPhase::getPhonePubRaw(pub, sizeof(pub));
          sendSecureNotify(_info, (len==65 && pub[0]==0x04)?"PUB_PRESENT":"PUB_NONE");
          Serial.printf("[BLE-Admin] Pub presence: %s\n", (len==65 && pub[0]==0x04)?"PRESENT":"NONE");
          break; }
        case 0x36: { // KeyId summary
          String kid; bool haveKid = ProvisioningPhase::getKeyId(kid);
          char buf[40];
          if (!haveKid) {
            strncpy(buf, "KEYID_NONE", sizeof(buf));
          } else {
            size_t copyLen = kid.length() < 24 ? kid.length() : 24;
            snprintf(buf, sizeof(buf), "KEYID:%.*s", (int)copyLen, kid.c_str());
          }
          sendSecureNotify(_info, buf);
          Serial.printf("[BLE-Admin] %s\n", buf);
          break; }
        case 0x37: { // All delegated keys status
          ProvisioningPhase::DelegatedKey keys[5];
          int count = ProvisioningPhase::getAllDelegatedKeys(keys, 5);
          char buf[64];
          if (count == 0) {
            strncpy(buf, "SHAREKEY_NONE", sizeof(buf));
          } else {
            uint64_t now = millis() / 1000;
            bool expired = (keys[0].validUntil > 0 && now > keys[0].validUntil);
            snprintf(buf, sizeof(buf), "KEYS:%d ID0:%.*s EXP:%s PERM:0x%02X",
                     count, 16, keys[0].keyId,
                     expired ? "YES" : "NO", keys[0].permissions);
            for (int i = 0; i < count; i++) {
              expired = (keys[i].validUntil > 0 && now > keys[i].validUntil);
              Serial.printf("[BLE-Admin]   Key %d: %s exp=%llu (%s) perm=0x%02X\n",
                            i, keys[i].keyId, keys[i].validUntil,
                            expired ? "EXPIRED" : "VALID", keys[i].permissions);
            }
          }
          sendSecureNotify(_info, buf);
          Serial.printf("[BLE-Admin] %s\n", buf);
          break; }
        case 0x38: // Clear all delegated keys
          ProvisioningPhase::clearSharedKey();
          sendSecureNotify(_info, "SHAREKEY_CLEARED");
          Serial.println("[BLE-Admin] SHAREKEY_CLEARED (all delegated keys)");
          break;
        case 0x39: { // 🔥 PRODUCTION: Cleanup expired keys
          int cleaned = ProvisioningPhase::cleanupExpiredKeys();
          char buf[32];
          snprintf(buf, sizeof(buf), "CLEANED:%d", cleaned);
          sendSecureNotify(_info, buf);
          Serial.printf("[BLE-Admin] Cleaned %d expired keys\n", cleaned);
          break; }
        case 0x40: { // UWB status query
          char buf[48];
          float dist = UWBRanging::getLastDistance();
          snprintf(buf, sizeof(buf), "UWB HW=%s EN=%s D=%.2fm",
                   UWBRanging::isHardwareOK() ? "OK" : "FAIL",
                   UWBRanging::isEnabled()    ? "ON" : "OFF",
                   dist >= 0.0f ? dist : -1.0f);
          sendSecureNotify(_info, buf);
          Serial.printf("[BLE-Admin] %s\n", buf);
          break; }
        case 0x41: // UWB enable (gọi sau BLE auth xong)
          UWBRanging::enable();
          sendSecureNotify(_info, "UWB:ENABLED");
          break;
        case 0x42: // UWB disable
          UWBRanging::disable();
          sendSecureNotify(_info, "UWB:DISABLED");
          break;
        default:
          sendSecureNotify(_info, "UNSUPPORTED");
          Serial.printf("[BLE-Admin] Unsupported cmd: 0x%02X (len=%u)\n", cmd, (unsigned)v.size());
          break;
      }
    }
  };

  // Admin phone public key upload (PEM). Supports chunked writes; commit when END line detected.
  class AdminPhoneKeyCallbacks : public NimBLECharacteristicCallbacks {
    NimBLECharacteristic* _info; std::string _buf;
   public:
    explicit AdminPhoneKeyCallbacks(NimBLECharacteristic* info): _info(info) {}
    void onWrite(NimBLECharacteristic* c, NimBLEConnInfo&) override {
      // New provisioning flow does not accept phone pubkey via BLE; instruct to use NFC provisioning.
      (void)c; _buf.clear();
      sendSecureNotify(_info, "UNSUPPORTED_USE_NFC");
    }
  };
}

namespace BLEAdmin {
  void registerService(NimBLEServer* server) {
    NimBLEService* pAdmin = server->createService(kAdminServiceUUID);
    // Mode
    NimBLECharacteristic* cAdminMode = pAdmin->createCharacteristic(kAdminModeCharUUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE);
    static AdminModeCallbacks adminModeCb; cAdminMode->setCallbacks(&adminModeCb);
    uint8_t initAdmin = (uint8_t)s_adminMode; cAdminMode->setValue(&initAdmin, 1); g_cAdminMode = cAdminMode;
    // Info
    NimBLECharacteristic* cAdminInfo = pAdmin->createCharacteristic(kAdminInfoCharUUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
    cAdminInfo->setValue("READY"); g_cAdminInfo = cAdminInfo;
    // Commands
    NimBLECharacteristic* cAdminCmd = pAdmin->createCharacteristic(kAdminCmdCharUUID, NIMBLE_PROPERTY::WRITE);
    static AdminCmdCallbacks adminCmdCb(cAdminInfo); cAdminCmd->setCallbacks(&adminCmdCb);
    // Phone public key upload
    NimBLECharacteristic* cPhoneKey = pAdmin->createCharacteristic(kAdminPhoneKeyUUID, NIMBLE_PROPERTY::WRITE);
    static AdminPhoneKeyCallbacks phoneKeyCb(cAdminInfo); cPhoneKey->setCallbacks(&phoneKeyCb);
    pAdmin->start();
  }

  BLEMod::AdminMode getAdminMode() { return s_adminMode; }
  void setAdminMode(BLEMod::AdminMode mode) { s_adminMode = mode; uint8_t m = (uint8_t)mode; if (g_cAdminMode) g_cAdminMode->setValue(&m, 1); }
  void notify(const char* msg) { if (g_cAdminInfo && msg) { sendSecureNotify(g_cAdminInfo, msg); } }
  void resetSession() { s_txSeq = 0; s_rxSeq = 0; }
}
