#include "cloud_sync.h"
#include "provisioning_phase.h"

#include <Arduino.h>
#include <ArduinoJson.h>
#include <HTTPClient.h>
#include <Preferences.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <esp_system.h>

#ifndef SMARTKEY_WIFI_SSID
#define SMARTKEY_WIFI_SSID ""
#endif

#ifndef SMARTKEY_WIFI_PASSWORD
#define SMARTKEY_WIFI_PASSWORD ""
#endif

#ifndef SMARTKEY_SYNC_BASE_URL
#define SMARTKEY_SYNC_BASE_URL ""
#endif

#ifndef SMARTKEY_DEVICE_ID
#define SMARTKEY_DEVICE_ID ""
#endif

#ifndef SMARTKEY_SYNC_TOKEN
#define SMARTKEY_SYNC_TOKEN ""
#endif

#ifndef SMARTKEY_ISSUER_PUBKEY_HEX
#define SMARTKEY_ISSUER_PUBKEY_HEX "04DEF6B928FE1F5D217DECAE8D3181ACBBC4D5526F98FD7078E02AF11B4B7E7C61BF41BCA332DB023C9F1413E9A2D6BC6889068063D91AC6D644AA60E36CE5F0E0"
#endif

namespace {
Preferences syncPrefs;

String g_baseUrl;
String g_deviceId;
String g_syncToken;
String g_ecuVehicleId;
uint32_t g_lastSyncVersion = 0;
uint32_t g_lastWifiAttemptMs = 0;
uint32_t g_lastSyncAttemptMs = 0;
uint32_t g_lastStatusLogMs = 0;
bool g_configured = false;
bool g_loggedDisabled = false;

const uint32_t WIFI_RETRY_MS = 15000;
const uint32_t SYNC_INTERVAL_MS = 60000;
const char* kSyncNs = "cloudsync";
const char* kSyncVersionKey = "last_ver";

String bytesToHex(const uint8_t* bytes, size_t len) {
  String out;
  out.reserve(len * 2);
  for (size_t i = 0; i < len; ++i) {
    char buf[3] = {0};
    snprintf(buf, sizeof(buf), "%02X", bytes[i]);
    out += buf;
  }
  return out;
}

String getDevicePublicKeyHex() {
  uint8_t pub65[65] = {0};
  if (!ProvisioningPhase::getDevicePubRaw(pub65, sizeof(pub65))) {
    return String();
  }
  return bytesToHex(pub65, sizeof(pub65));
}

String computeEcuVehicleId() {
  uint8_t mac[6] = {0};
  esp_read_mac(mac, ESP_MAC_WIFI_STA);
  char buf[17];
  snprintf(buf, sizeof(buf), "%02X%02X%02X%02X%02X%02XCC01",
           mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
  return String(buf);
}

bool hexToBytes(const String& hex, uint8_t* out, size_t maxLen, size_t* outLen = nullptr) {
  if (!out) return false;
  String normalized = hex;
  normalized.trim();
  normalized.toUpperCase();
  if ((normalized.length() % 2) != 0) {
    return false;
  }

  const size_t byteCount = normalized.length() / 2;
  if (byteCount == 0 || byteCount > maxLen) {
    return false;
  }

  auto nibble = [](char c) -> int {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'A' && c <= 'F') return 10 + (c - 'A');
    return -1;
  };

  for (size_t i = 0; i < byteCount; ++i) {
    const int n1 = nibble(normalized[i * 2]);
    const int n2 = nibble(normalized[i * 2 + 1]);
    if (n1 < 0 || n2 < 0) return false;
    out[i] = static_cast<uint8_t>((n1 << 4) | n2);
  }

  if (outLen) *outLen = byteCount;
  return true;
}

bool hexToPubKey65(const String& hex, uint8_t* out65) {
  size_t got = 0;
  return hexToBytes(hex, out65, 65, &got) && got == 65 && out65[0] == 0x04;
}

String buildCredentialSigningPayload(const char* credentialId,
                                     const char* subjectUid,
                                     const char* subjectDeviceId,
                                     const char* publicKeyHex,
                                     uint8_t permissionMask,
                                     uint64_t expiresAtMs) {
  String normalizedPub = String(publicKeyHex ? publicKeyHex : "");
  normalizedPub.trim();
  normalizedPub.toUpperCase();

  char permBuf[8] = {0};
  snprintf(permBuf, sizeof(permBuf), "%u", static_cast<unsigned>(permissionMask));

  char expBuf[32] = {0};
  snprintf(expBuf, sizeof(expBuf), "%llu", static_cast<unsigned long long>(expiresAtMs));

  String payload;
  payload.reserve(256 + normalizedPub.length());
  payload += "CCC-CLOUD-CRED-V1\n";
  payload += String(credentialId ? credentialId : "");
  payload += '\n';
  payload += String(subjectUid ? subjectUid : "");
  payload += '\n';
  payload += String(subjectDeviceId ? subjectDeviceId : "");
  payload += '\n';
  payload += normalizedPub;
  payload += '\n';
  payload += permBuf;
  payload += '\n';
  payload += expBuf;
  return payload;
}

bool verifyCredentialPackage(JsonObjectConst item, uint8_t permissionMask) {
  const char* credentialId = item["credentialId"] | "";
  const char* subjectUid = item["subjectUid"] | "";
  const char* subjectDeviceId = item["subjectDeviceId"] | "";
  const char* publicKeyHex = item["publicKeyHex"] | "";
  const char* signatureHex = item["credentialSignatureDerHex"] | "";
  const char* issuerId = item["issuerId"] | "unknown-issuer";
  const uint64_t expiresAtMs = item["expiresAt"] | 0ULL;

  if (!signatureHex[0]) {
    Serial.printf("[CloudSync] Reject credential %s: missing issuer signature\n", credentialId);
    return false;
  }

  uint8_t issuerPub65[65] = {0};
  if (!hexToPubKey65(String(SMARTKEY_ISSUER_PUBKEY_HEX), issuerPub65)) {
    Serial.println("[CloudSync] Reject credential: invalid issuer public key config");
    return false;
  }

  uint8_t sigDer[96] = {0};
  size_t sigLen = 0;
  if (!hexToBytes(String(signatureHex), sigDer, sizeof(sigDer), &sigLen) || sigLen == 0) {
    Serial.printf("[CloudSync] Reject credential %s: bad signature encoding\n", credentialId);
    return false;
  }

  const String payload = buildCredentialSigningPayload(
      credentialId,
      subjectUid,
      subjectDeviceId,
      publicKeyHex,
      permissionMask,
      expiresAtMs);

  const bool ok = ProvisioningPhase::verifySignatureP256(
      issuerPub65,
      reinterpret_cast<const uint8_t*>(payload.c_str()),
      payload.length(),
      sigDer,
      sigLen);

  if (!ok) {
    Serial.printf("[CloudSync] Reject credential %s: issuer verification failed (%s)\n",
                  credentialId,
                  issuerId);
  }
  return ok;
}

void normalizeBaseUrl() {
  while (g_baseUrl.endsWith("/")) {
    g_baseUrl.remove(g_baseUrl.length() - 1);
  }
}

bool ensureWiFiConnected() {
  if (!g_configured) {
    if (!g_loggedDisabled) {
      Serial.println("[CloudSync] Disabled: set WiFi + sync build flags in platformio.ini to enable vehicle sync");
      g_loggedDisabled = true;
    }
    return false;
  }

  if (WiFi.status() == WL_CONNECTED) {
    return true;
  }

  const uint32_t now = millis();
  if (now - g_lastWifiAttemptMs < WIFI_RETRY_MS) {
    return false;
  }
  g_lastWifiAttemptMs = now;

  Serial.printf("[CloudSync] Connecting to WiFi SSID=%s ...\n", SMARTKEY_WIFI_SSID);
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.begin(SMARTKEY_WIFI_SSID, SMARTKEY_WIFI_PASSWORD);
  return false;
}

bool sendAck(uint32_t version) {
  if (!g_configured || WiFi.status() != WL_CONNECTED) return false;

  WiFiClientSecure client;
  client.setInsecure();
  HTTPClient http;

  String url = g_baseUrl + "/ackVehicleSync?ecuVehicleId=" + g_ecuVehicleId +
               "&deviceId=" + g_deviceId +
               "&ackVersion=" + String(version);

  if (!http.begin(client, url)) {
    Serial.println("[CloudSync] Failed to begin ack request");
    return false;
  }
  http.addHeader("x-smartkey-device-id", g_deviceId);
  http.addHeader("x-smartkey-sync-token", g_syncToken);
  const String devicePublicKeyHex = getDevicePublicKeyHex();
  if (devicePublicKeyHex.length() == 130) {
    http.addHeader("x-smartkey-device-pub", devicePublicKeyHex);
  }

  const int code = http.GET();
  const String response = http.getString();
  http.end();

  if (code < 200 || code >= 300) {
    Serial.printf("[CloudSync] Ack failed HTTP %d: %s\n", code, response.c_str());
    return false;
  }

  syncPrefs.putULong(kSyncVersionKey, version);
  g_lastSyncVersion = version;
  return true;
}

size_t applyCredentialArray(JsonArrayConst array) {
  size_t applied = 0;
  for (JsonObjectConst item : array) {
    const char* credentialId = item["credentialId"] | "";
    const char* publicKeyHex = item["publicKeyHex"] | "";
    const char* status = item["status"] | "active";
    const uint64_t expiresAtMs = item["expiresAt"] | 0ULL;
    const uint8_t permissionMask = static_cast<uint8_t>(item["permissionMask"] | ProvisioningPhase::PERM_FULL);

    if (!credentialId[0]) continue;

    if (strcmp(status, "active") != 0) {
      ProvisioningPhase::clearDelegatedKey(credentialId);
      continue;
    }

    if (!verifyCredentialPackage(item, permissionMask)) {
      continue;
    }

    uint8_t pub65[65] = {0};
    if (!hexToPubKey65(String(publicKeyHex), pub65)) {
      Serial.printf("[CloudSync] Skip credential %s: invalid pubKeyHex\n", credentialId);
      continue;
    }

    const uint64_t validUntilSec = expiresAtMs > 0 ? (expiresAtMs / 1000ULL) : 0ULL;
    if (ProvisioningPhase::upsertDelegatedKeyFromCloud(credentialId, pub65, validUntilSec, permissionMask)) {
      applied++;
    }
  }
  return applied;
}

size_t applyRevocationArray(JsonArrayConst array) {
  size_t revoked = 0;
  for (JsonObjectConst item : array) {
    const char* credentialId = item["credentialId"] | "";
    if (!credentialId[0]) continue;
    if (ProvisioningPhase::clearDelegatedKey(credentialId)) {
      revoked++;
    }
  }
  return revoked;
}

bool performSyncNow() {
  if (!ensureWiFiConnected() || WiFi.status() != WL_CONNECTED) {
    return false;
  }

  WiFiClientSecure client;
  client.setInsecure();
  HTTPClient http;

  String url = g_baseUrl + "/getVehicleSyncDelta?ecuVehicleId=" + g_ecuVehicleId +
               "&deviceId=" + g_deviceId +
               "&sinceVersion=" + String(g_lastSyncVersion);

  if (!http.begin(client, url)) {
    Serial.println("[CloudSync] Failed to begin sync request");
    return false;
  }
  http.addHeader("x-smartkey-device-id", g_deviceId);
  http.addHeader("x-smartkey-sync-token", g_syncToken);
  const String devicePublicKeyHex = getDevicePublicKeyHex();
  if (devicePublicKeyHex.length() == 130) {
    http.addHeader("x-smartkey-device-pub", devicePublicKeyHex);
  }

  const int code = http.GET();
  const String payload = http.getString();
  http.end();

  if (code < 200 || code >= 300) {
    Serial.printf("[CloudSync] Sync HTTP %d: %s\n", code, payload.c_str());
    return false;
  }

  DynamicJsonDocument doc(16384);
  DeserializationError error = deserializeJson(doc, payload);
  if (error) {
    Serial.printf("[CloudSync] JSON parse failed: %s\n", error.c_str());
    return false;
  }

  if (!(doc["ok"] | false)) {
    Serial.println("[CloudSync] Sync response returned ok=false");
    return false;
  }

  const String mode = String(doc["mode"] | "delta");
  size_t applied = 0;
  size_t revoked = 0;

  if (mode == "full") {
    ProvisioningPhase::clearAllDelegatedKeys();
    applied = applyCredentialArray(doc["activeCredentials"].as<JsonArrayConst>());
  } else {
    applied = applyCredentialArray(doc["upserts"].as<JsonArrayConst>());
    revoked = applyRevocationArray(doc["revocations"].as<JsonArrayConst>());
  }

  const uint32_t latestVersion = doc["latestVersion"] | g_lastSyncVersion;
  if (latestVersion > g_lastSyncVersion) {
    sendAck(latestVersion);
  }

  Serial.printf("[CloudSync] Sync %s complete: +%u / -%u, version=%lu\n",
                mode.c_str(),
                static_cast<unsigned>(applied),
                static_cast<unsigned>(revoked),
                static_cast<unsigned long>(g_lastSyncVersion));
  return true;
}
}  // namespace

namespace CloudSync {
void begin() {
  g_baseUrl = String(SMARTKEY_SYNC_BASE_URL);
  g_deviceId = String(SMARTKEY_DEVICE_ID);
  g_syncToken = String(SMARTKEY_SYNC_TOKEN);
  g_ecuVehicleId = computeEcuVehicleId();
  normalizeBaseUrl();

  syncPrefs.begin(kSyncNs, false);
  g_lastSyncVersion = syncPrefs.getULong(kSyncVersionKey, 0);

  g_configured = g_baseUrl.length() > 0 && g_deviceId.length() > 0 && g_syncToken.length() > 0 && String(SMARTKEY_WIFI_SSID).length() > 0;

  if (g_configured) {
    Serial.printf("[CloudSync] Enabled: ecuVehicleId=%s deviceId=%s lastSyncVersion=%lu\n",
                  g_ecuVehicleId.c_str(),
                  g_deviceId.c_str(),
                  static_cast<unsigned long>(g_lastSyncVersion));
    Serial.println("[CloudSync] Auth token configured for backend sync");
  } else {
    Serial.printf("[CloudSync] Not configured (ecuVehicleId=%s). Vehicle sync will stay offline.\n",
                  g_ecuVehicleId.c_str());
  }
}

void tick() {
  if (!g_configured) return;

  ensureWiFiConnected();

  const uint32_t now = millis();
  if (WiFi.status() == WL_CONNECTED && now - g_lastStatusLogMs > 30000) {
    g_lastStatusLogMs = now;
    Serial.printf("[CloudSync] WiFi OK, IP=%s, syncedVersion=%lu\n",
                  WiFi.localIP().toString().c_str(),
                  static_cast<unsigned long>(g_lastSyncVersion));
  }

  if (WiFi.status() != WL_CONNECTED) return;
  if (now - g_lastSyncAttemptMs < SYNC_INTERVAL_MS) return;

  g_lastSyncAttemptMs = now;
  performSyncNow();
}

void requestSyncNow() {
  g_lastSyncAttemptMs = 0;
}

void printStatus() {
  Serial.printf("[CloudSync] configured=%s wifi=%s ecuVehicleId=%s deviceId=%s version=%lu\n",
                g_configured ? "YES" : "NO",
                WiFi.status() == WL_CONNECTED ? "CONNECTED" : "DISCONNECTED",
                g_ecuVehicleId.c_str(),
                g_deviceId.c_str(),
                static_cast<unsigned long>(g_lastSyncVersion));
}

bool isConfigured() {
  return g_configured;
}
}  // namespace CloudSync
