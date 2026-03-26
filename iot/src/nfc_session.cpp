// NFC session implementation: encapsulates PN532 setup, polling, provisioning APDUs, and admin commands.
// Refactored for readability: clearer function names, smaller helpers, consistent naming and comments.

#include "nfc_session.h"
#include "provisioning_phase.h"
#include <esp_system.h>
#include <esp_log.h>
#include <cstring>
#include <Wire.h>

namespace {
    // PN532 objects and session state
    PN532_I2C* pn532I2c = nullptr;
    PN532* nfc = nullptr;
    bool samConfigured = false;
    bool pn532Ready = false;
    int activeSdaPin = -1;
    int activeSclPin = -1;
    uint32_t activeI2cClock = 100000;
    uint32_t lastReconnectAttemptMs = 0;
    uint32_t reconnectIntervalMs = 1500;
    uint32_t lastBusMissingLogMs = 0;

    bool ensureSAMConfig(uint8_t attempts, uint16_t backoffMs);

    // Provisioning control flags
    bool forceProvisionPersistent = false; // toggled with 'f' - stays until toggled off
    bool forceProvisionOneShot = false;    // armed with 'F' - auto-clears after one success

    // Startup guard to ignore early serial noise
    uint32_t bootMillis = 0;

    // HCE AID used when SELECTing the phone applet
    uint8_t aid[] = {0xF0,0x01,0x02,0x03,0x04,0x05};

    // Small utility: print hex bytes to Serial
    void printHex(const uint8_t* data, size_t len) {
        for (size_t i = 0; i < len; ++i) {
            Serial.printf("%02X", data[i]);
            if (i + 1 < len) Serial.print(' ');
        }
        Serial.println();
    }

    bool i2cDevicePresent(uint8_t address) {
        Wire.beginTransmission(address);
        return Wire.endTransmission() == 0;
    }

    void logI2cQuickScan() {
        Serial.print("[I2C] Scan: ");
        bool foundAny = false;
        for (uint8_t addr = 1; addr < 127; ++addr) {
            Wire.beginTransmission(addr);
            if (Wire.endTransmission() == 0) {
                Serial.printf("0x%02X ", addr);
                foundAny = true;
            }
            delay(1);
        }
        if (!foundAny) {
            Serial.print("<none>");
        }
        Serial.println();
    }

    void initI2cBus(int sda, int scl, uint32_t i2cClock) {
        Wire.end();
        delay(20);
        Wire.begin(sda, scl);
        Wire.setClock(i2cClock);
        Wire.setTimeOut(200); // 200ms per transaction; 1-byte polls avoid long stretches
        delay(80);
    }

    void destroyPn532Objects() {
        if (nfc) {
            delete nfc;
            nfc = nullptr;
        }
        if (pn532I2c) {
            delete pn532I2c;
            pn532I2c = nullptr;
        }
    }

    bool startPn532OnCurrentBus(bool* sawAnyAck = nullptr) {
        const uint32_t clockCandidates[] = {activeI2cClock, 50000, 25000};
        bool ackSeen = false;

        for (size_t i = 0; i < sizeof(clockCandidates) / sizeof(clockCandidates[0]); ++i) {
            uint32_t trialClock = clockCandidates[i];
            initI2cBus(activeSdaPin, activeSclPin, trialClock);
            if (!i2cDevicePresent(0x24)) {
                Serial.printf("[I2C] No ACK on 0x24 at %luHz (SDA=%d SCL=%d)\n", (unsigned long)trialClock, activeSdaPin, activeSclPin);
                continue;
            }
            ackSeen = true;

            destroyPn532Objects();

            pn532I2c = new PN532_I2C(Wire);
            nfc = new PN532(*pn532I2c);
            nfc->begin();
            // Re-apply our pin mapping: PN532_I2C::begin() may have reset Wire to default pins.
            Wire.begin(activeSdaPin, activeSclPin);
            Wire.setClock(trialClock);
            Wire.setTimeOut(1000);
            delay(30);

            uint32_t ver = 0;
            for (uint8_t attempt = 1; attempt <= 3; ++attempt) {
                ver = nfc->getFirmwareVersion();
                if (ver) break;
                delay(80);
            }
            if (!ver) {
                Serial.printf("[I2C] 0x24 ACK but getFirmwareVersion failed at %luHz\n", (unsigned long)trialClock);
                continue;
            }

            Serial.printf("PN532 Firmware %d.%d\n", (int)((ver >> 16) & 0xFF), (int)((ver >> 8) & 0xFF));
            Serial.printf("Configuring SAM at %luHz...", (unsigned long)trialClock);
            samConfigured = ensureSAMConfig(3, 300);
            Serial.println(samConfigured ? "OK" : "SAM CONFIG FAILED (trying anyway)");

            // Even if SAMConfig fails, PN532 boots in normal mode; try polling anyway.
            activeI2cClock = trialClock;
            if (sawAnyAck) *sawAnyAck = ackSeen;
            return true;
        }

        if (sawAnyAck) *sawAnyAck = ackSeen;
        return false;
    }

    // Repeatedly try an INDATAEXCHANGE (APDU) with retry/backoff
    bool apduRetry(const uint8_t* apdu, uint8_t apduLen, uint8_t* resp, uint8_t* respLen,
                                 uint8_t attempts = 3, uint16_t backoffMs = 60) {
        for (uint8_t attempt = 1; attempt <= attempts; ++attempt) {
            uint8_t len = *respLen;
            bool ok = nfc->inDataExchange((uint8_t*)apdu, apduLen, resp, &len);
            if (ok) {
                *respLen = len;
                return true;
            }
            if (attempt < attempts) delay(backoffMs);
        }
        return false;
    }

    // Try to configure the SAM (Secure Access Module) with retries
    bool ensureSAMConfig(uint8_t attempts = 5, uint16_t backoffMs = 150) {
        for (uint8_t i = 1; i <= attempts; ++i) {
            if (nfc->SAMConfig()) return true;
            delay(backoffMs);
        }
        return false;
    }

    // Explicitly release any active target/session on the PN532 to return to idle.
    // This prevents the next poll from requiring a "priming" tap.
    bool releaseTarget() {
        // Some libraries return bool; ignore result if not supported.
        return nfc->inRelease();
    }

    // Wait for a card to appear using adaptive polling. Returns true when stable detected.
    bool waitForCard(uint32_t timeoutMs = 15000, uint8_t stableReads = 2, uint16_t pollMs = 60) {
        // Ensure previous sessions are fully released before starting a new poll loop
        if (samConfigured) {
            releaseTarget();
        }

        const uint32_t start = millis();
        uint8_t stableCount = 0;
        uint32_t lastDot = 0;
        Serial.println("Waiting for card (adaptive poll)...");
        while (true) {
            if (!i2cDevicePresent(0x24)) {
                pn532Ready = false;
                samConfigured = false;
                Serial.println("\n[I2C] PN532 bus lost while polling. Switching to reconnect mode...");
                return false;
            }

            bool present = nfc->inListPassiveTarget();
            if (present) {
                if (++stableCount >= stableReads) {
                    Serial.println("Card detected (stable)");
                    return true;
                }
            } else {
                stableCount = 0;
            }
            uint32_t now = millis();
            if (now - lastDot >= 2000) { Serial.print('.'); lastDot = now; }
            if (timeoutMs && now - start >= timeoutMs) {
                Serial.println("\nTimeout waiting for card");
                return false;
            }
            delay(pollMs);
        }
    }

    // Wait for a card to be removed. Returns true when stable absent detected.
    bool waitForRemoval(uint32_t timeoutMs = 8000, uint8_t stableAbsentReads = 3, uint16_t pollMs = 80) {
        const uint32_t start = millis();
        uint8_t absentCount = 0;
        while (true) {
            bool present = nfc->inListPassiveTarget();
            if (!present) {
                if (++absentCount >= stableAbsentReads) {
                    Serial.println("Card removed (stable)\n");
                    return true;
                }
            } else {
                absentCount = 0;
            }
            if (timeoutMs && millis() - start >= timeoutMs) {
                Serial.println("Removal wait timeout");
                return false;
            }
            delay(pollMs);
        }
    }

    // After a session (successful SELECT and optional provisioning), either wait briefly for removal
    // or continue to the next cycle to keep UX snappy. For failed SELECT, just short backoff.
    void postSessionCooldown(bool hadSelectSuccess) {
        if (hadSelectSuccess) {
            // Wait up to ~1.5s for user to remove phone; if not, continue anyway
            bool removed = waitForRemoval(1500, 2, 80);
            if (!removed) {
                Serial.println("[Session] Proceeding without removal (phone still present)");
            }
            // In any case, release the target to ensure the next poll starts cleanly
            releaseTarget();
        } else {
            // Wrong tag / SELECT failed: small backoff to avoid tight loop
            delay(200);
            // Release just in case there is a half-open session
            releaseTarget();
        }
    }

    // Build and send SELECT AID APDU; returns SW1, SW2, payload length, and optionally copies payload bytes
    bool selectAid(uint8_t& sw1, uint8_t& sw2, int& payloadLen,
                   uint8_t* payloadOut = nullptr, int payloadOutMax = 0) {
        uint8_t sel[16];
        uint8_t idx = 0;
        sel[idx++] = 0x00; sel[idx++] = 0xA4; sel[idx++] = 0x04; sel[idx++] = 0x00;
        sel[idx++] = sizeof(aid);
        for (size_t i = 0; i < sizeof(aid); ++i) sel[idx++] = aid[i];
        sel[idx++] = 0x00; // Le

        uint8_t resp[32];
        uint8_t rlen = sizeof(resp);
        // Phone HCE can take longer than physical cards to wake and route APDUs.
        if (!apduRetry(sel, idx, resp, &rlen, 8, 120)) return false;

        sw1 = (rlen >= 2) ? resp[rlen - 2] : 0x00;
        sw2 = (rlen >= 2) ? resp[rlen - 1] : 0x00;
        payloadLen = (rlen >= 2) ? (rlen - 2) : 0;
        if (payloadOut && payloadLen > 0 && payloadOutMax > 0) {
            int toCopy = (payloadLen < payloadOutMax) ? payloadLen : payloadOutMax;
            memcpy(payloadOut, resp, toCopy);
        }
        return true;
    }

    // Parse the "Base GET_CHALLENGE" response layout:
    // [keyIdLen][keyId..][pubKey65..][certLen(2)][cert..]
    struct BaseInfo {
        String keyId;
        const uint8_t* pubKey65 = nullptr;
        uint16_t certLen = 0;
        const uint8_t* cert = nullptr;
        bool hasPub = false;
    };

    bool parseBaseResponse(const uint8_t* data, int dataLen, BaseInfo& out) {
        if (dataLen < 1 + 65 + 2) return false; // minimal sizes
        int idx = 0;
        uint8_t keyIdLen = data[idx++];
        if (keyIdLen > dataLen - idx) return false;
        String keyId;
        for (uint8_t k = 0; k < keyIdLen; ++k) keyId += (char)data[idx + k];
        idx += keyIdLen;

        if (idx + 65 + 2 > dataLen) return false;
        const uint8_t* pubKey65 = data + idx; idx += 65;
        uint16_t certLen = (uint16_t)(data[idx] << 8 | data[idx + 1]); idx += 2;
        if (idx + certLen > dataLen) return false;
        const uint8_t* cert = data + idx;

        out.keyId = keyId;
        out.pubKey65 = pubKey65;
        out.certLen = certLen;
        out.cert = cert;
        out.hasPub = (pubKey65[0] == 0x04);
        return true;
    }

    // Execute the two-step provisioning exchange when appropriate.
    void performProvisioningIfNeeded(uint8_t sw1, uint8_t sw2) {
        bool alreadyProvisioned = ProvisioningPhase::isProvisioned();
        bool shouldForce = (forceProvisionPersistent || forceProvisionOneShot);
        bool shouldProvision = (shouldForce || !alreadyProvisioned) && (sw1 == 0x90 && sw2 == 0x00);
        if (!shouldProvision) return;

        // ------------------------------
        // Step 1: Base GET_CHALLENGE (Lc=0)
        // ------------------------------
        Serial.println("[PhaseA] Step1: Base GET_CHALLENGE (Lc=0)");
        uint8_t baseApdu[] = {0x00, 0xCA, 0x00, 0x00, 0x00, 0x00};
        uint8_t baseResp[255]; uint8_t baseLen = sizeof(baseResp);

        Serial.print("[PhaseA] OUT Base APDU: "); printHex(baseApdu, sizeof(baseApdu));
        if (!(apduRetry(baseApdu, sizeof(baseApdu), baseResp, &baseLen, 5, 150)
                    && baseLen >= 2 && baseResp[baseLen - 2] == 0x90 && baseResp[baseLen - 1] == 0x00)) {
            Serial.println("[PhaseA] Base GET_CHALLENGE failed");
            return;
        }

        Serial.printf("[PhaseA] IN Base Resp len=%u SW=%02X%02X\n", baseLen, baseResp[baseLen - 2], baseResp[baseLen - 1]);
        int payloadLen = baseLen - 2;
        const uint8_t* payload = baseResp;
        BaseInfo info;
        if (!parseBaseResponse(payload, payloadLen, info)) {
            Serial.println("[PhaseA] Base payload parse error");
            return;
        }

        Serial.printf("[PhaseA] Parsed Base: keyId='%s' pubKeyFirst=%02X certLen=%u\n",
                                    info.keyId.c_str(), info.pubKey65 ? info.pubKey65[0] : 0, info.certLen);
        if (info.hasPub) {
            Serial.print("[PhaseA] pubKey65: "); printHex(info.pubKey65, 65);
        }
        if (info.certLen > 0) {
            Serial.print("[PhaseA] cert (first up to 32 bytes): ");
            size_t show = info.certLen < 32 ? info.certLen : 32;
            printHex(info.cert, show);
        } else {
            Serial.println("[PhaseA] cert: <none>");
        }

        // ------------------------------
        // Step 2: Signature GET_CHALLENGE (Lc=24)
        // ------------------------------
        Serial.println("[PhaseA] Step2: Signature GET_CHALLENGE (Lc=24 challenge)");
        uint8_t vehicleId[8];
        uint8_t nonce[16];
        for (int i = 0; i < 8; ++i) vehicleId[i] = (uint8_t)esp_random();
        for (int i = 0; i < 16; ++i) nonce[i] = (uint8_t)esp_random();

        uint8_t challenge[24];
        memcpy(challenge, vehicleId, 8);
        memcpy(challenge + 8, nonce, 16);

        Serial.print("[PhaseA] vehicleId: "); printHex(vehicleId, 8);
        Serial.print("[PhaseA] nonce: "); printHex(nonce, 16);
        Serial.print("[PhaseA] challenge(24): "); printHex(challenge, 24);

        // Build signature APDU: CLA INS P1 P2 Lc [challenge] Le
        uint8_t sigApdu[5 + sizeof(challenge) + 1];
        uint8_t sIdx = 0;
        sigApdu[sIdx++] = 0x00; sigApdu[sIdx++] = 0xCA; sigApdu[sIdx++] = 0x00; sigApdu[sIdx++] = 0x00;
        sigApdu[sIdx++] = sizeof(challenge);
        memcpy(sigApdu + sIdx, challenge, sizeof(challenge)); sIdx += sizeof(challenge);
        sigApdu[sIdx++] = 0x00; // Le

        Serial.print("[PhaseA] OUT Sig APDU: "); printHex(sigApdu, sIdx);
        uint8_t sigResp[255]; uint8_t sigTotalLen = sizeof(sigResp);
        if (!(apduRetry(sigApdu, sIdx, sigResp, &sigTotalLen, 5, 150)
                    && sigTotalLen >= 2 && sigResp[sigTotalLen - 2] == 0x90 && sigResp[sigTotalLen - 1] == 0x00)) {
            Serial.println("[PhaseA] Signature GET_CHALLENGE failed");
            return;
        }

        Serial.printf("[PhaseA] IN Sig Resp len=%u SW=%02X%02X\n", sigTotalLen, sigResp[sigTotalLen - 2], sigResp[sigTotalLen - 1]);
        int sigPayloadLen = sigTotalLen - 2;
        if (sigPayloadLen < 2) { Serial.println("[PhaseA] Signature payload too short"); return; }

        uint16_t sigLen = (uint16_t)(sigResp[0] << 8 | sigResp[1]);
        if (2 + sigLen > sigPayloadLen) { Serial.println("[PhaseA] sigLen OOB"); return; }
        const uint8_t* sigDer = sigResp + 2;

        Serial.print("[PhaseA] sigDer (first up to 32 bytes): ");
        size_t showSig = sigLen < 32 ? sigLen : 32;
        printHex(sigDer, showSig);

        // Verify signature if we have the public key. Fallback: accept empty signature (sigLen==0)
        bool sigOK = false;
        if (sigLen > 0 && info.hasPub) {
            sigOK = ProvisioningPhase::verifySignatureP256(info.pubKey65, challenge, sizeof(challenge), sigDer, sigLen);
        } else if (sigLen == 0) {
            sigOK = true; // fallback acceptance
        }

        Serial.printf("[PhaseA] sigLen=%u verify=%s\n", sigLen, sigOK ? "OK" : "FAIL");
        if (!sigOK) { Serial.println("[PhaseA] Credentials NOT stored (signature failure)"); return; }

        // Decide whether to store provisioning data
        String existingKeyId;
        bool haveExisting = ProvisioningPhase::getKeyId(existingKeyId);
        bool sameKey = haveExisting && existingKeyId == info.keyId;

        if (!alreadyProvisioned || forceProvisionPersistent || forceProvisionOneShot) {
            // Store keyId according to force flags and whether it differs from stored value
            if (!sameKey) {
                if (forceProvisionPersistent || forceProvisionOneShot) {
                    ProvisioningPhase::storeKeyIdAsciiForce(info.keyId.c_str());
                } else {
                    ProvisioningPhase::storeKeyIdAsciiIfEmpty(info.keyId.c_str());
                }
            } else {
                Serial.println("[PhaseA] Same keyId as stored; skipping keyId re-store");
            }

            if (info.hasPub) ProvisioningPhase::storePhonePubRaw(info.pubKey65);
            if (info.certLen > 0) ProvisioningPhase::storeCertChain(info.cert, info.certLen);
            Serial.println("[PhaseA] Provisioning data persisted (keyId/pub/cert as available)");

            if (forceProvisionOneShot) {
                forceProvisionOneShot = false; // consume the one-shot force
                Serial.println("[PhaseA] One-shot force consumed; returning to normal mode");
            }
        } else {
            Serial.println("[PhaseA] Already provisioned; skipping store (toggle 'f' to force)");
        }
    }

    // Process incoming serial admin commands (debounced and guarded by startup time)
    void handleSerialCommands() {
        static uint32_t lastCmdMs = 0;
        static char lastCmd = 0;

        // Ignore serial input for the first 1500ms after boot to avoid garbage triggering destructive commands
        if (millis() - bootMillis < 1500) {
            while (Serial.available()) Serial.read(); // flush
            return;
        }

        while (Serial.available()) {
            char c = Serial.read();
            if (c < 32 || c > 126) continue; // non-printable guard

            uint32_t now = millis();
            if (c == lastCmd && now - lastCmdMs < 250) continue; // debounce bursts
            lastCmd = c; lastCmdMs = now;

            if (c == 'p') {
                String kid; bool haveKid = ProvisioningPhase::getKeyId(kid);
                uint8_t pub[65]; size_t pubLen = ProvisioningPhase::getPhonePubRaw(pub, sizeof(pub));
                size_t certLen = ProvisioningPhase::getCertChain(nullptr, 0);
                Serial.println("[Admin] Stored provisioning state:");
                Serial.printf("  keyId: %s\n", haveKid ? kid.c_str() : "<none>");
                Serial.printf("  pubKey: %s (len=%u)\n", (pubLen == 65 && pub[0] == 0x04) ? "present" : "<none>", (unsigned)pubLen);
                Serial.printf("  certLen: %u\n", (unsigned)certLen);
            } else if (c == 'f') {
                forceProvisionPersistent = !forceProvisionPersistent;
                Serial.printf("[Admin] Persistent force provisioning: %s\n", forceProvisionPersistent ? "ON" : "OFF");
            } else if (c == 'F') {
                forceProvisionOneShot = true;
                Serial.println("[Admin] One-shot force provisioning ARMED (will auto-clear after success)");
            } else if (c == 'r') {
                ProvisioningPhase::clearProvisionedOnly();
                Serial.println("[Admin] Cleared phone provisioning (kept device keypair)");
            } else if (c == 'C') {
                ProvisioningPhase::clearAll();
                Serial.println("[Admin] CLEARED ALL");
            } else if (c == 'v') {
                bool ok = ProvisioningPhase::validateStoredCertMatchesStoredPub();
                Serial.printf("[Admin] Cert vs pub match: %s\n", ok ? "YES" : "NO/UNAVAILABLE");
            } else if (c == 'x') {
                uint8_t pub[65]; size_t pubLen = ProvisioningPhase::getPhonePubRaw(pub, sizeof(pub));
                if (pubLen == 65) { Serial.print("[Admin] pubKey65: "); printHex(pub, 65); }
                else Serial.println("[Admin] pubKey not stored");
            } else if (c == 'h') {
                Serial.println("Commands: p f F r C v x h");
            }
        }
    }
} // anonymous namespace

namespace NfcSession {
    void begin(int sdaPin, int sclPin, uint32_t i2cClock) {
        activeSdaPin = sdaPin;
        activeSclPin = sclPin;
        activeI2cClock = i2cClock;

        // PN532 I2C 7-bit address used by this library is 0x24.
        initI2cBus(activeSdaPin, activeSclPin, activeI2cClock);
        bool pn532Seen = i2cDevicePresent(0x24);
        if (!pn532Seen) {
            Serial.printf("[I2C] PN532 not found on SDA=%d SCL=%d. Trying swapped pins...\n", activeSdaPin, activeSclPin);
            initI2cBus(sclPin, sdaPin, activeI2cClock);
            if (i2cDevicePresent(0x24)) {
                activeSdaPin = sclPin;
                activeSclPin = sdaPin;
                pn532Seen = true;
                Serial.printf("[I2C] PN532 detected on swapped mapping SDA=%d SCL=%d\n", activeSdaPin, activeSclPin);
            }
        }

        if (!pn532Seen) {
            Serial.printf("[I2C] PN532 still not detected on 0x24 (tried SDA/SCL=%d/%d and %d/%d)\n", sdaPin, sclPin, sclPin, sdaPin);
            logI2cQuickScan();
        }

        Serial.printf("\n=== PN532 HCE READER (I2C SDA=%d SCL=%d %luHz) ===\n", activeSdaPin, activeSclPin, (unsigned long)activeI2cClock);
        bool sawI2cAck = false;
        pn532Ready = startPn532OnCurrentBus(&sawI2cAck);
        if (!pn532Ready) {
            if (sawI2cAck) Serial.println("[NFC] I2C device detected at 0x24 but init failed");
            else Serial.println("[NFC] I2C mode not ready (no ACK on 0x24)");
        }

        ProvisioningPhase::begin();
        esp_log_level_set("Preferences", ESP_LOG_NONE); // suppress noisy NVS NOT_FOUND logs
        // Reduce noisy low-level I2C logs in monitor; high-level reconnect logs remain.
        esp_log_level_set("Wire", ESP_LOG_NONE);
        esp_log_level_set("wire", ESP_LOG_NONE);
        esp_log_level_set("i2c", ESP_LOG_NONE);

        bootMillis = millis(); // mark startup time to guard serial noise

        if (!pn532Ready) {
            Serial.println("PN532 NOT FOUND! Entering reconnect mode...");
        }

        Serial.printf("[PhaseA] Provisioned=%s  Force(persist)=%s  OneShot=%s\n",
                                    ProvisioningPhase::isProvisioned() ? "YES" : "NO",
                                    forceProvisionPersistent ? "YES" : "NO",
                                    forceProvisionOneShot ? "ARMED" : "NO");

        Serial.println("[PhaseA] Serial: p=print, f=toggle persistent force, F=one-shot force, r=reset phone, C=clear ALL, v=validate cert, x=dump pub, h=help");
    }

    void tick() {
        // Process admin serial commands
        handleSerialCommands();

        if (!pn532Ready) {
            uint32_t now = millis();
            if (now - lastReconnectAttemptMs >= reconnectIntervalMs) {
                lastReconnectAttemptMs = now;
                bool sawI2cAck = false;
                pn532Ready = startPn532OnCurrentBus(&sawI2cAck);
                if (pn532Ready) {
                    reconnectIntervalMs = 1500;
                    Serial.println("[NFC] PN532 reconnected successfully over I2C");
                } else {
                    logI2cQuickScan();
                    // Exponential backoff so repeated SAM failures do not flood the monitor.
                    if (reconnectIntervalMs < 10000) reconnectIntervalMs *= 2;
                    if (reconnectIntervalMs > 10000) reconnectIntervalMs = 10000;
                    if (now - lastBusMissingLogMs >= 5000) {
                        lastBusMissingLogMs = now;
                        Serial.printf("[NFC] PN532 not ready on I2C. Next retry in %lu ms\n", (unsigned long)reconnectIntervalMs);
                    }
                }
            }
            return;
        }

        if (!i2cDevicePresent(0x24)) {
            pn532Ready = false;
            samConfigured = false;
            Serial.println("[I2C] Lost ACK from PN532 (0x24). Pausing NFC and retrying...");
            return;
        }

        // Wait for card; if none, try to refresh SAMConfig occasionally
        if (!waitForCard(15000, 2, 60)) {
            if (!samConfigured) {
                Serial.println("Re-attempting SAMConfig...");
                samConfigured = ensureSAMConfig(3, 200);
                if (samConfigured) Serial.println("SAM configured successfully");
            }
            return;
        }

        // SELECT the HCE AID on the phone
        uint8_t sw1 = 0, sw2 = 0;
        int payloadLen = 0;
        uint8_t actionBuf[4] = {0};
        bool selectOk = selectAid(sw1, sw2, payloadLen, actionBuf, sizeof(actionBuf));
        if (!selectOk) {
            Serial.println("SELECT AID failed");
            postSessionCooldown(false);
            return;
        }

        Serial.printf("SELECT SW1SW2=%02X%02X payloadLen=%d  Provisioned=%s  Force(persist)=%s  OneShot=%s\n",
                                    sw1, sw2, payloadLen,
                                    ProvisioningPhase::isProvisioned() ? "YES" : "NO",
                                    forceProvisionPersistent ? "YES" : "NO",
                                    forceProvisionOneShot ? "ARMED" : "NO");

        // Execute lock/unlock action signalled by the phone in the SELECT payload
        if (sw1 == 0x90 && sw2 == 0x00 && payloadLen >= 1) {
            uint8_t action = actionBuf[0];
            if (action == 0x01) {
                digitalWrite(48, LOW);
                Serial.println("[NFC] Action: LOCK -> LED OFF (GPIO 48)");
            } else if (action == 0x02) {
                digitalWrite(48, HIGH);
                Serial.println("[NFC] Action: UNLOCK -> LED ON (GPIO 48)");
            }
        }

        // Possibly perform provisioning sequence after a successful SELECT
        performProvisioningIfNeeded(sw1, sw2);

        // Keep flow smooth: wait briefly for removal only when SELECT succeeded
        postSessionCooldown(sw1 == 0x90 && sw2 == 0x00);
    }

    // ---- Admin control API (exposed via header & used by BLE) ----
    void setPersistentForce(bool on) {
        forceProvisionPersistent = on;
        Serial.printf("[NfcSession] Persistent force set %s\n", on ? "ON" : "OFF");
    }
    void armOneShotForce() {
        forceProvisionOneShot = true;
        Serial.println("[NfcSession] One-shot force ARMED");
    }
    bool getPersistentForce() { return forceProvisionPersistent; }
    bool isOneShotArmed() { return forceProvisionOneShot; }
}
