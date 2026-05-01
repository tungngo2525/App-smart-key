// NFC session implementation: encapsulates PN532 setup, polling, provisioning APDUs, and admin commands.
// Refactored for readability: clearer function names, smaller helpers, consistent naming and comments.
// ⚡ OPTIMIZED: Debug logging control, RAM-cached pubKey, instant detection, static crypto context

#include "nfc_session.h"
#include "provisioning_phase.h"
#include <esp_system.h>
#include <esp_log.h>
#include <cstring>
#include <Wire.h>

// 🚀 PERFORMANCE: Control debug logging (set to 0 to save ~100-200ms per transaction)
#define DEBUG_LOGS 1
#define DEBUG_HEX 0  // Hex dumps are expensive - only enable when needed

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
    uint32_t lastI2cPresenceCheckMs = 0;

    // 🔥 CACHE: Cached pubKey in RAM to avoid NVS read (~50-100ms) on every transaction
    uint8_t cachedPubKey[65] = {0};
    bool pubKeyCached = false;

    // 🔐 CCC EDGE-BASED DETECTION: 1 tap = 1 action (proper state machine)
    bool cardSessionActive = false;
    uint32_t sessionStartMs = 0;
    uint32_t lastSessionStatusLogMs = 0;
    const uint32_t SESSION_WINDOW_MS = 1200;       // preferred remove-and-retap window
    const uint32_t SESSION_FORCE_REARM_MS = 2600;  // fallback in case PN532/HCE keeps reporting the tag as present

    // 🎯 ACTION CHANGE DETECTION: Prevent duplicate execution on repeated same action
    uint8_t lastExecutedAction = 0xFF;  // 0xFF = no action executed yet
    bool expectedLedState = LOW;        // LOW = locked, HIGH = unlocked

    // Remember the last successful identity so repeated taps can try the likely key first.
    bool preferDelegatedFirst = false;
    char preferredDelegatedKeyId[65] = {0};

    // 💡 Optional LED pulse state (must always restore the steady lock/unlock state)
    bool ledBlinkActive = false;
    uint8_t ledBlinkCount = 0;          // Number of toggles done (2 blinks = 4 toggles)
    uint32_t ledLastToggle = 0;         // Timestamp of last toggle
    const uint32_t LED_BLINK_INTERVAL = 80;  // ms between toggles
    const uint8_t LED_BLINK_TOTAL = 4;       // Total toggles (2 full blinks)

    bool ensureSAMConfig(uint8_t attempts, uint16_t backoffMs);

    // Provisioning control flags
    bool forceProvisionPersistent = false; // toggled with 'f' - stays until toggled off
    bool forceProvisionOneShot = false;    // armed with 'F' - auto-clears after one success
    bool shareKeySyncOneShot = false;      // armed with 's' - tries one share-key fetch in next session

    // Startup guard to ignore early serial noise
    uint32_t bootMillis = 0;

    // CCC Digital Key R3 AID: D2760000850101
    // RID D276000085 (Bundesdruckerei / CCC-registered) + PIX 0101 (Digital Key service)
    uint8_t aid[] = {0xD2,0x76,0x00,0x00,0x85,0x01,0x01};

    // Small utility: print hex bytes to Serial (controlled by DEBUG_HEX)
    void printHex(const uint8_t* data, size_t len) {
#if DEBUG_HEX
        for (size_t i = 0; i < len; ++i) {
            Serial.printf("%02X", data[i]);
            if (i + 1 < len) Serial.print(' ');
        }
        Serial.println();
#else
        (void)data; (void)len; // Suppress unused warnings
#endif
    }

    bool i2cDevicePresent(uint8_t address) {
        Wire.beginTransmission(address);
        return Wire.endTransmission() == 0;
    }

    bool i2cDevicePresentStable(uint8_t address, uint8_t attempts = 3, uint16_t retryDelayMs = 2) {
        for (uint8_t i = 0; i < attempts; ++i) {
            if (i2cDevicePresent(address)) return true;
            if (i + 1 < attempts) delay(retryDelayMs);
        }
        return false;
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
        Wire.setTimeOut(80); // keep individual I2C ops short to avoid visible polling stalls
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
            if (!i2cDevicePresentStable(0x24, 2, 1)) {
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
            Wire.setTimeOut(80);
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
            Serial.println(samConfigured ? "OK" : "SAM CONFIG FAILED (will retry)");

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

    // CCC R3 §4.2: Anti-collision check — verify exactly 1 ISO14443A card in RF field.
    // Releases current target, lists with MaxTg=2, returns true only when NbTg==1.
    // The card remains selected after a successful check (inListedTag is set).
    bool checkAntiCollision() {
        nfc->inRelease(0);
        uint8_t count = nfc->inListPassiveTargetCount(2);
        if (count != 1) {
            Serial.printf("[CCC] Anti-collision: %u card(s) in field — rejected\n", count);
            nfc->inRelease(0);
            return false;
        }
        return true;  // exactly 1 card; still selected
    }

    // Wait for a card to appear using continuous non-blocking polling (no timeout).
    // 🚀 OPTIMIZED: Instant detection for <100ms detect→SELECT (CCC fast-path requirement)
    // Returns true when card detected. Minimal debounce for rapid response.
    bool waitForCard(uint32_t timeoutMs = 0, uint8_t stableReads = 1, uint16_t pollMs = 2) {
        uint32_t lastDot = 0;
        uint32_t lastI2cCheckMs = 0;
#if DEBUG_LOGS
        Serial.println("⚡ Polling (instant detect)...");
#endif
        while (true) {
            uint32_t now = millis();
            // Check I2C health less frequently to reduce overhead
            if (now - lastI2cCheckMs >= 1000) {
                lastI2cCheckMs = now;
                if (!i2cDevicePresentStable(0x24, 1, 0)) {
                    pn532Ready = false;
                    samConfigured = false;
#if DEBUG_LOGS
                    Serial.println("\n[I2C] PN532 lost. Reconnecting...");
#endif
                    return false;
                }
            }

            // ⚡ INSTANT: Detect immediately on first positive read (no stable count)
            bool present = nfc->inListPassiveTarget();
            if (present) {
#if DEBUG_LOGS
                Serial.printf("\u26a1 Card! [%lums]\n", millis());
#endif
                return true;
            }

#if DEBUG_LOGS
            if (now - lastDot >= 1000) { Serial.print('.'); lastDot = now; }
#endif
            delay(pollMs);  // Reduced to 2ms for faster response
        }
    }

    // Wait for a card to be removed. Returns true when stable absent detected.
    // 🔧 OPTIMIZED: Fast removal detection (~300-500ms) for production UX
    // 🚨 SIMPLIFIED: Avoid active polling that can hang - use passive check only
    bool waitForRemoval(uint32_t timeoutMs = 8000, uint8_t stableAbsentReads = 2, uint16_t pollMs = 50) {
        const uint32_t start = millis();
        uint8_t absentCount = 0;
        
        while (true) {
            // 🚀 PASSIVE CHECK: Only use inListPassiveTarget (no active poll that can hang)
            // This checks if the previously selected target is still present
            bool present = nfc->inListPassiveTarget();
            
            if (!present) {
                if (++absentCount >= stableAbsentReads) {
#if DEBUG_LOGS
                    Serial.printf("✅ Card removed in %lums\n\n", millis() - start);
#endif
                    return true;
                }
            } else {
                absentCount = 0;
            }
            
            if (timeoutMs && millis() - start >= timeoutMs) {
#if DEBUG_LOGS
                if (timeoutMs >= 1000) Serial.println("⏱️  Timeout: card still present\n");
#endif
                return false;  // Timeout - card still present
            }
            delay(pollMs);
        }
    }

    // After a session, force PN532 back to idle for next detection.
    // 🔥 CRITICAL: Aggressive RF reset to prevent Transport fail on consecutive auths
    void postSessionCooldown(bool hadSelectSuccess) {
        nfc->inRelease(0); // release all targets - tell PN532 to drop connection
        delay(50);
        
        // 🚀 AGGRESSIVE RESET: Power down RF field to ensure clean state
        nfc->powerDownMode();  // Turn off RF field completely
        delay(100);            // Let field stabilize
        
        // Reconfigure SAM - retry if failed
        if (!nfc->SAMConfig()) {
            delay(50);
            nfc->SAMConfig();  // Retry once
        }
        
        delay(50);             // Additional settle time
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
        // Do one APDU attempt per call; higher-level loop handles fast retries.
        if (!apduRetry(sel, idx, resp, &rlen, 1, 0)) return false;

        sw1 = (rlen >= 2) ? resp[rlen - 2] : 0x00;
        sw2 = (rlen >= 2) ? resp[rlen - 1] : 0x00;
        payloadLen = (rlen >= 2) ? (rlen - 2) : 0;
        if (payloadOut && payloadLen > 0 && payloadOutMax > 0) {
            int toCopy = (payloadLen < payloadOutMax) ? payloadLen : payloadOutMax;
            memcpy(payloadOut, resp, toCopy);
        }
        return true;
    }

    // ── CCC R3 Phase 2: INTERNAL_AUTHENTICATE ──────────────────────────────
    // Sends 00 88 00 00 18 [vehicleId(8)][nonce(16)] 00
    // Response: [pubKey65(65)][sigLen(2)][sigDER(N)][SW(2)]
    // Proves phone identity via ECDSA-P256 over the challenge.
    // On success: stores pubKey+keyId in NVS, copies pubKey into pubKey65Out.
    bool performInternalAuth(uint8_t* vehicleId8, uint8_t* nonce16, uint8_t* pubKey65Out) {
        uint8_t challenge[24];
        memcpy(challenge, vehicleId8, 8);
        memcpy(challenge + 8, nonce16, 16);

        uint8_t apdu[5 + 24 + 1];
        uint8_t idx = 0;
        apdu[idx++] = 0x00; apdu[idx++] = 0x88; // CLA INS=INTERNAL_AUTHENTICATE
        apdu[idx++] = 0x00; apdu[idx++] = 0x00;
        apdu[idx++] = 24;
        memcpy(apdu + idx, challenge, 24); idx += 24;
        apdu[idx++] = 0x00;
        Serial.print("[CCC] OUT INTERNAL_AUTHENTICATE: "); printHex(apdu, idx);

        for (int attempt = 1; attempt <= 3; ++attempt) {
            if (attempt > 1) { Serial.printf("[CCC] Auth retry %d/3\n", attempt); delay(50); }
            uint8_t resp[255]; uint8_t respLen = sizeof(resp);
            if (!apduRetry(apdu, idx, resp, &respLen, 3, 80)) {
                Serial.println("[CCC] INTERNAL_AUTHENTICATE transport fail"); continue;
            }
            if (respLen < 2) { Serial.println("[CCC] Auth response empty"); continue; }
            if (resp[respLen-2] != 0x90 || resp[respLen-1] != 0x00) {
                Serial.printf("[CCC] INTERNAL_AUTHENTICATE SW=%02X%02X\n", resp[respLen-2], resp[respLen-1]);
                return false; // explicit rejection, don't retry
            }
            // Response: [pubKey65(65)][sigLen(2)][sigDER(N)][SW(2)]
            int payloadLen = respLen - 2;
            if (payloadLen < 65 + 2) {
                Serial.printf("[CCC] Auth response too short: %d bytes - retrying\n", payloadLen); continue;
            }
            const uint8_t* pub = resp;
            if (pub[0] != 0x04) {
                Serial.printf("[CCC] Auth bad pubKey prefix 0x%02X - retrying\n", pub[0]); continue;
            }
            uint16_t sigLen = (uint16_t)((resp[65] << 8) | resp[66]);
            if (65 + 2 + (int)sigLen > payloadLen) {
                Serial.printf("[CCC] Auth sigLen OOB (sigLen=%u payloadLen=%d) - retrying\n", sigLen, payloadLen); continue;
            }
            const uint8_t* sigDer = resp + 67;
            Serial.printf("[CCC] Auth resp: pubKeyPrefix=%02X sigLen=%u\n", pub[0], sigLen);

            bool ok = (sigLen > 0) &&
                      ProvisioningPhase::verifySignatureP256(pub, challenge, 24, sigDer, sigLen);
            Serial.printf("[CCC] Identity verify=%s\n", ok ? "OK" : "FAIL");
            if (!ok) return false;

            ProvisioningPhase::storePhonePubRaw(pub);
            char vidHex[17];
            for (int i = 0; i < 8; ++i) sprintf(vidHex + i * 2, "%02X", vehicleId8[i]);
            vidHex[16] = '\0';
            ProvisioningPhase::storeKeyIdAsciiForce(vidHex);
            memcpy(pubKey65Out, pub, 65);
            Serial.println("[CCC] Identity verified; pubKey and keyId stored to NVS");
            return true;
        }
        return false;
    }

    // ── Fast Single-Transaction INTERNAL_AUTHENTICATE ──────────────────────
    // CCC Fast Transaction Protocol (single APDU, single verify)
    // Response: [action(1)][sigLen(2)][sig(N)][SW(2)]
    // Signature covers: nonce(16) + vehicleId(8) + action(1) = 25 bytes
    // → Verify once, execute immediately! ⚡
    bool performFastAuth(uint8_t* vehicleId8, uint8_t* nonce16, uint8_t* pubKey65, uint8_t* actionOut) {
        uint8_t challenge[24];
        memcpy(challenge, vehicleId8, 8);
        memcpy(challenge + 8, nonce16, 16);

        uint8_t apdu[5 + 24 + 1];
        uint8_t idx = 0;
        apdu[idx++] = 0x00; apdu[idx++] = 0x88;
        apdu[idx++] = 0x00; apdu[idx++] = 0x00;
        apdu[idx++] = 24;
        memcpy(apdu + idx, challenge, 24); idx += 24;
        apdu[idx++] = 0x00;
#if DEBUG_LOGS
        Serial.print("[CCC] OUT INTERNAL_AUTHENTICATE: ");
#endif
        printHex(apdu, idx);

        for (int attempt = 1; attempt <= 3; ++attempt) {
#if DEBUG_LOGS
            if (attempt > 1) { 
                Serial.printf("[CCC] Retry %d/3\n", attempt); 
                delay(80);  // 🔥 Increased: 80ms RF stabilization between retries
            }
#else
            if (attempt > 1) delay(80);  // RF stabilization even without debug
#endif
            uint8_t resp[255]; uint8_t respLen = sizeof(resp);
            if (!apduRetry(apdu, idx, resp, &respLen, 2, 60)) {
#if DEBUG_LOGS
                Serial.println("[CCC] Transport fail");
#endif
                continue;
            }
            
            // 🔥 VALIDATE: Check minimum response length FIRST
            if (respLen < 2) {
#if DEBUG_LOGS
                Serial.printf("[CCC] Response too short: %d bytes\n", respLen);
#endif
                continue;
            }
            
            if (resp[respLen-2] != 0x90 || resp[respLen-1] != 0x00) {
#if DEBUG_LOGS
                Serial.printf("[CCC] SW=%02X%02X\n", resp[respLen-2], resp[respLen-1]);
#endif
                return false;  // SW error is fatal, don't retry
            }

            int payloadLen = respLen - 2;
            // 🔥 VALIDATE: Minimum payload for [action(1)][sigLen(2)][sig(>=60)]
            if (payloadLen < 1 + 2 + 60) {
#if DEBUG_LOGS
                Serial.printf("[CCC] Payload truncated: %d bytes (need >=63)\n", payloadLen);
#endif
                continue;  // Frame corruption - retry
            }

            // 🔥 VALIDATE: Check action byte FIRST (before parsing signature)
            uint8_t action = resp[0];
            if (action != 0x01 && action != 0x02) {
#if DEBUG_LOGS
                Serial.printf("[CCC] Invalid action=0x%02X (frame corrupt)\n", action);
#endif
                continue;  // Corrupted frame - retry immediately
            }
            
            uint16_t sigLen = (uint16_t)((resp[1] << 8) | resp[2]);
            // 🔥 VALIDATE: ECDSA-P256 DER signature is 70-72 bytes (rarely 68-73)
            if (sigLen < 60 || sigLen > 80) {
#if DEBUG_LOGS
                Serial.printf("[CCC] Invalid sigLen=%u (expect 60-80)\n", sigLen);
#endif
                continue;  // Corrupted length - retry
            }
            
            if (1 + 2 + (int)sigLen > payloadLen) {
#if DEBUG_LOGS
                Serial.printf("[CCC] SigLen OOB: %u > %d\n", sigLen, payloadLen - 3);
#endif
                continue;  // Truncated signature - retry
            }
            const uint8_t* sigDer = resp + 3;

#if DEBUG_LOGS
            Serial.printf("[CCC] ⚡ Fast: action=0x%02X sigLen=%u\n", action, sigLen);
#endif

            // 🔥 VERIFY ONCE: nonce(16) + vehicleId(8) + action(1) = 25 bytes
            uint8_t signedData[25];
            memcpy(signedData, nonce16, 16);
            memcpy(signedData + 16, vehicleId8, 8);
            signedData[24] = action;

            bool ok = (sigLen > 0) &&
                      ProvisioningPhase::verifySignatureP256(pubKey65, signedData, 25, sigDer, sigLen);
#if DEBUG_LOGS
            Serial.printf("[CCC] Verify=%s [%lums]\n", ok ? "OK" : "FAIL", millis());
#endif
            if (!ok) return false;

            *actionOut = action;
#if DEBUG_LOGS
            Serial.println("[CCC] ⚡ Authorized!");
#endif
            return true;
        }
        return false;
    }

    // ── CCC R3 Phase 3: GET_ACTION ───────────────────────────────────────────
    // Sends 00 CA 02 00 00
    // Response: [action(1)][sigLen(2)][actionSigDER(N)][SW(2)]
    // Action sig covers nonce(16)+action(1) — binds action to this session.
    uint8_t performGetAction(uint8_t* pubKey65, uint8_t* nonce16) {
        uint8_t apdu[] = {0x00, 0xCA, 0x02, 0x00, 0x00};
        Serial.print("[CCC] OUT GET_ACTION: "); printHex(apdu, sizeof(apdu));

        uint8_t resp[255]; uint8_t respLen = sizeof(resp);
        // Increased retry attempts from 3 to 5 and backoff from 80ms to 120ms
        if (!apduRetry(apdu, sizeof(apdu), resp, &respLen, 5, 120)) {
            Serial.println("[CCC] GET_ACTION transport fail (keep phone on reader longer!)"); 
            return 0x00;
        }
        if (respLen < 2) return 0x00;
        if (resp[respLen-2] != 0x90 || resp[respLen-1] != 0x00) {
            Serial.printf("[CCC] GET_ACTION rejected SW=%02X%02X\n", resp[respLen-2], resp[respLen-1]);
            if (resp[respLen-2] == 0x69 && resp[respLen-1] == 0x85)
                Serial.println("[CCC] Hint: no pending action (queue lock/unlock in app first)");
            return 0x00;
        }
        // Response: [action(1)][sigLen(2)][actionSigDER(N)][SW(2)]
        int payloadLen = respLen - 2;
        if (payloadLen < 1 + 2) {
            Serial.printf("[CCC] GET_ACTION response too short: %d bytes\n", payloadLen); return 0x00;
        }
        uint8_t action = resp[0];
        if (action != 0x01 && action != 0x02) {
            Serial.printf("[CCC] Invalid action=0x%02X\n", action); return 0x00;
        }
        uint16_t sigLen = (uint16_t)((resp[1] << 8) | resp[2]);
        if (1 + 2 + (int)sigLen > payloadLen) {
            Serial.printf("[CCC] Action sigLen OOB (sigLen=%u payloadLen=%d)\n", sigLen, payloadLen); return 0x00;
        }
        const uint8_t* sigDer = resp + 3;
        Serial.printf("[CCC] Action resp: action=0x%02X sigLen=%u\n", action, sigLen);

        // Verify: signed = nonce(16) + action(1)
        uint8_t signedData[17];
        memcpy(signedData, nonce16, 16);
        signedData[16] = action;
        bool ok = (sigLen > 0) &&
                  ProvisioningPhase::verifySignatureP256(pubKey65, signedData, 17, sigDer, sigLen);
        Serial.printf("[CCC] Action verify=%s\n", ok ? "OK" : "FAIL");
        if (!ok) return 0x00;

        Serial.println("[CCC] Action authorized");
        return action;
    }

    // ── CCC ShareKey: GET_DATA P1=0x03 ─────────────────────────────────────
    // Sends 00 CA 03 00 00
    // Response: [keyIdLen(1)][keyIdAscii(N)][delegatedPub65(65)][ownerSigLen(2)][ownerSigDer(M)][SW(2)]
    // The owner signature is verified on ECU with provisioned owner pubkey before persisting delegated key.
    bool performGetShareKey(const uint8_t* vehicleId8, const uint8_t* nonce16) {
        uint8_t apdu[] = {0x00, 0xCA, 0x03, 0x00, 0x00};
        Serial.print("[CCC] OUT GET_SHARE_KEY: "); printHex(apdu, sizeof(apdu));

        uint8_t resp[255]; uint8_t respLen = sizeof(resp);
        if (!apduRetry(apdu, sizeof(apdu), resp, &respLen, 2, 50)) {
            Serial.println("[CCC] GET_SHARE_KEY transport fail");
            return false;
        }
        if (respLen < 2) return false;

        uint8_t sw1 = resp[respLen - 2];
        uint8_t sw2 = resp[respLen - 1];
        if (!(sw1 == 0x90 && sw2 == 0x00)) {
            Serial.printf("[CCC] GET_SHARE_KEY SW=%02X%02X\n", sw1, sw2);
            return false;
        }

        int payloadLen = respLen - 2;
        if (payloadLen < 1 + 65 + 2) {
            Serial.printf("[CCC] GET_SHARE_KEY payload too short: %d\n", payloadLen);
            return false;
        }

        const uint8_t keyIdLen = resp[0];
        if (keyIdLen == 0 || keyIdLen > 64) {
            Serial.printf("[CCC] GET_SHARE_KEY invalid keyIdLen=%u\n", keyIdLen);
            return false;
        }

        int idx = 1;
        if (idx + keyIdLen + 65 + 2 > payloadLen) {
            Serial.println("[CCC] GET_SHARE_KEY malformed payload");
            return false;
        }

        char keyIdAscii[65];
        memcpy(keyIdAscii, resp + idx, keyIdLen);
        keyIdAscii[keyIdLen] = '\0';
        idx += keyIdLen;

        const uint8_t* delegatedPub65 = resp + idx;
        idx += 65;

        const uint16_t ownerSigLen = (uint16_t)((resp[idx] << 8) | resp[idx + 1]);
        idx += 2;
        if (idx + ownerSigLen > payloadLen || ownerSigLen == 0) {
            Serial.printf("[CCC] GET_SHARE_KEY invalid ownerSigLen=%u\n", ownerSigLen);
            return false;
        }
        const uint8_t* ownerSigDer = resp + idx;

        uint8_t sessionCtx[24];
        memcpy(sessionCtx, vehicleId8, 8);
        memcpy(sessionCtx + 8, nonce16, 16);

        // 🔥 PRODUCTION: Default to no expiry (0) and full permissions
        const bool ok = ProvisioningPhase::storeSharedKeyCcc(
            keyIdAscii, delegatedPub65, ownerSigDer, ownerSigLen, sessionCtx, sizeof(sessionCtx),
            0,  // validUntil: 0 = no expiry
            ProvisioningPhase::PERM_FULL);  // Full access by default
        Serial.printf("[CCC] ShareKey store=%s keyId=%s\n", ok ? "OK" : "FAIL", keyIdAscii);
        return ok;
    }

    // ── Provisioning: First-time INTERNAL_AUTHENTICATE ────────────────────
    // Only used once to store pubKey, then fast flow takes over
    // Response: [pubKey65][sigLen][sig][SW]
    bool performProvisionAuth(uint8_t* vehicleId8, uint8_t* nonce16, uint8_t* pubKey65Out) {
        uint8_t challenge[24];
        memcpy(challenge, vehicleId8, 8);
        memcpy(challenge + 8, nonce16, 16);

        uint8_t apdu[5 + 24 + 1];
        uint8_t idx = 0;
        apdu[idx++] = 0x00; apdu[idx++] = 0x88;
        apdu[idx++] = 0x00; apdu[idx++] = 0x00;
        apdu[idx++] = 24;
        memcpy(apdu + idx, challenge, 24); idx += 24;
        apdu[idx++] = 0x00;
        Serial.println("[CCC] Provisioning: requesting pubKey...");

        uint8_t resp[255]; uint8_t respLen = sizeof(resp);
        if (!apduRetry(apdu, idx, resp, &respLen, 3, 80)) {
            Serial.println("[CCC] Provision transport fail"); 
            return false;
        }
        if (respLen < 2 || resp[respLen-2] != 0x90 || resp[respLen-1] != 0x00) {
            Serial.println("[CCC] Provision failed");
            return false;
        }

        int payloadLen = respLen - 2;
        
        // Try to parse as legacy format with pubKey: [pubKey65][sigLen][sig]
        if (payloadLen >= 65 + 2) {
            const uint8_t* pub = resp;
            if (pub[0] == 0x04) {
                // Has pubKey!
                memcpy(pubKey65Out, pub, 65);
                ProvisioningPhase::storePhonePubRaw(pub);
                char vidHex[17];
                for (int i = 0; i < 8; ++i) sprintf(vidHex + i * 2, "%02X", vehicleId8[i]);
                vidHex[16] = '\0';
                ProvisioningPhase::storeKeyIdAsciiForce(vidHex);
                Serial.println("[CCC] ✅ Provisioned! PubKey stored. Next taps will be fast.");
                return true;
            }
        }
        
        Serial.println("[CCC] ❌ No pubKey in response - phone needs update for provisioning");
        return false;
    }

    // 🔥 OPTIMIZED: Load pubKey from RAM cache (first time from NVS, then cached)
    void loadPubKeyIfNeeded() {
        if (pubKeyCached && cachedPubKey[0] == 0x04) return;  // Already cached
        
        size_t pubLen = ProvisioningPhase::getPhonePubRaw(cachedPubKey, sizeof(cachedPubKey));
        if (pubLen == 65 && cachedPubKey[0] == 0x04) {
            pubKeyCached = true;
#if DEBUG_LOGS
            Serial.println("[CCC] 🔥 PubKey cached in RAM");
#endif
        } else {
            pubKeyCached = false;
            memset(cachedPubKey, 0, sizeof(cachedPubKey));
        }
    }

    // ── CCC Fast Transaction orchestrator ──────────────────────────────────
    // Single APDU, single verify, instant execute! ⚡
    // 🔥 PRODUCTION: Now supports delegated keys with expiry + permissions
    uint8_t performCccFlow(uint8_t sw1, uint8_t sw2) {
        if (!(sw1 == 0x90 && sw2 == 0x00)) return 0x00;

        uint8_t vehicleId[8];
        uint8_t mac[6];
        esp_read_mac(mac, ESP_MAC_WIFI_STA);
        memcpy(vehicleId, mac, 6);
        vehicleId[6] = 0xCC;
        vehicleId[7] = 0x01;
#if DEBUG_LOGS
        Serial.print("[CCC] vehicleId: ");
        // Always print important session data (even if DEBUG_HEX=0)
        for (int i = 0; i < 8; ++i) Serial.printf("%02X", vehicleId[i]);
        Serial.println();
#endif

        uint8_t nonce[16];
        for (int i = 0; i < 16; ++i) nonce[i] = (uint8_t)esp_random();
#if DEBUG_LOGS
        Serial.print("[CCC] nonce: ");
        for (int i = 0; i < 16; ++i) Serial.printf("%02X", nonce[i]);
        Serial.println();
#endif

        // 🔥 FAST: Try RAM cache first (avoid NVS read ~50-100ms)
        loadPubKeyIfNeeded();
        
        if (!pubKeyCached) {
            // First time - run provisioning to get pubKey
#if DEBUG_LOGS
            Serial.println("[CCC] 🔧 First tap - provisioning...");
#endif
            uint8_t pubKey65[65];
            if (!performProvisionAuth(vehicleId, nonce, pubKey65)) {
#if DEBUG_LOGS
                Serial.println("[CCC] ❌ Provisioning failed");
#endif
                return 0x00;
            }
            // Cache the newly provisioned key
            memcpy(cachedPubKey, pubKey65, 65);
            pubKeyCached = true;
            // Provisioned! But don't execute action this time (just setup)
#if DEBUG_LOGS
            Serial.println("[CCC] ℹ️  Tap again to unlock");
#endif
            return 0x00;
        }

        // ⚡ FAST PATH: Authenticate and get action
        uint8_t action = 0x00;
        uint8_t permissions = ProvisioningPhase::PERM_FULL;  // Default: owner has full access
        char keyIdUsed[65] = "owner";
        bool usedDelegated = false;

        auto tryDelegatedKeys = [&](bool preferredFirst) -> bool {
            int cleaned = ProvisioningPhase::cleanupExpiredKeys();
            if (cleaned > 0) {
                Serial.printf("[CCC] 🗑️ Cleaned %d expired keys\n", cleaned);
            }

            ProvisioningPhase::DelegatedKey keys[5];
            int keyCount = ProvisioningPhase::getAllDelegatedKeys(keys, 5);

            for (int pass = 0; pass < (preferredFirst ? 2 : 1); ++pass) {
                for (int i = 0; i < keyCount; ++i) {
                    if (!keys[i].active) continue;

                    const bool isPreferred =
                        preferredDelegatedKeyId[0] != '\0' &&
                        strcmp(keys[i].keyId, preferredDelegatedKeyId) == 0;

                    if (preferredFirst) {
                        if (pass == 0 && !isPreferred) continue;
                        if (pass == 1 && isPreferred) continue;
                    }

                    if (performFastAuth(vehicleId, nonce, keys[i].pubKey, &action)) {
                        uint64_t now = millis() / 1000;  // Simple timestamp
                        if (keys[i].validUntil > 0 && now > keys[i].validUntil) {
#if DEBUG_LOGS
                            Serial.printf("[CCC] ⏰ Key expired: %s\n", keys[i].keyId);
#endif
                            continue;
                        }

                        permissions = keys[i].permissions;
                        strncpy(keyIdUsed, keys[i].keyId, sizeof(keyIdUsed) - 1);
                        keyIdUsed[sizeof(keyIdUsed) - 1] = '\0';
                        usedDelegated = true;
                        preferDelegatedFirst = true;
                        strncpy(preferredDelegatedKeyId, keys[i].keyId, sizeof(preferredDelegatedKeyId) - 1);
                        preferredDelegatedKeyId[sizeof(preferredDelegatedKeyId) - 1] = '\0';
#if DEBUG_LOGS
                        Serial.printf("[CCC] ✅ Authenticated as DELEGATED: %s perm=0x%02X\n",
                                      keyIdUsed, permissions);
#endif
                        return true;
                    }
                }
            }
            return false;
        };

        bool found = false;

        // If the previous successful tap was delegated, try that key path first.
        if (preferDelegatedFirst && preferredDelegatedKeyId[0] != '\0') {
#if DEBUG_LOGS
            Serial.printf("[CCC] 🔁 Trying delegated key first: %s\n", preferredDelegatedKeyId);
#endif
            found = tryDelegatedKeys(true);
        }

        // Owner tap path.
        if (!found && performFastAuth(vehicleId, nonce, cachedPubKey, &action)) {
#if DEBUG_LOGS
            Serial.println("[CCC] ✅ Authenticated as OWNER (full access)");
#endif
            permissions = ProvisioningPhase::PERM_FULL;
            preferDelegatedFirst = false;
            preferredDelegatedKeyId[0] = '\0';
            found = true;
        }

        // Fallback: try all delegated keys.
        if (!found) {
#if DEBUG_LOGS
            Serial.println("[CCC] 🔍 Owner key failed, checking delegated keys...");
#endif
            found = tryDelegatedKeys(false);
            if (!found) {
#if DEBUG_LOGS
                Serial.println("[CCC] ❌ Auth failed (no valid keys)");
#endif
                return 0x00;
            }
        }

        // 🔥 PRODUCTION: Check permission for requested action
        if (action == 0x01 && !(permissions & ProvisioningPhase::PERM_LOCK)) {
#if DEBUG_LOGS
            Serial.printf("[CCC] ❌ Permission denied: %s cannot LOCK\n", keyIdUsed);
#endif
            return 0x00;
        }
        if (action == 0x02 && !(permissions & ProvisioningPhase::PERM_UNLOCK)) {
#if DEBUG_LOGS
            Serial.printf("[CCC] ❌ Permission denied: %s cannot UNLOCK\n", keyIdUsed);
#endif
            return 0x00;
        }

#if DEBUG_LOGS
        if (usedDelegated) {
            Serial.printf("[CCC] ✅ Action authorized for %s: 0x%02X\n", keyIdUsed, action);
        }
#endif

        // Success! Return authorized action
        return action;
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
                Serial.printf("  pubKey cached: %s\n", pubKeyCached ? "YES" : "NO");
                Serial.printf("  certLen: %u\n", (unsigned)certLen);
            } else if (c == 'f') {
                forceProvisionPersistent = !forceProvisionPersistent;
                Serial.printf("[Admin] Persistent force provisioning: %s\n", forceProvisionPersistent ? "ON" : "OFF");
            } else if (c == 'F') {
                forceProvisionOneShot = true;
                Serial.println("[Admin] One-shot force provisioning ARMED (will auto-clear after success)");
            } else if (c == 'r') {
                ProvisioningPhase::clearProvisionedOnly();
                pubKeyCached = false;  // 🔥 Clear cache when clearing provisioning
                memset(cachedPubKey, 0, sizeof(cachedPubKey));
                Serial.println("[Admin] Cleared phone provisioning (kept device keypair)");
            } else if (c == 'C') {
                ProvisioningPhase::clearAll();
                pubKeyCached = false;  // 🔥 Clear cache when clearing all
                memset(cachedPubKey, 0, sizeof(cachedPubKey));
                Serial.println("[Admin] CLEARED ALL");
            } else if (c == 'v') {
                bool ok = ProvisioningPhase::validateStoredCertMatchesStoredPub();
                Serial.printf("[Admin] Cert vs pub match: %s\n", ok ? "YES" : "NO/UNAVAILABLE");
            } else if (c == 'x') {
                uint8_t pub[65]; size_t pubLen = ProvisioningPhase::getPhonePubRaw(pub, sizeof(pub));
                if (pubLen == 65) { Serial.print("[Admin] pubKey65: "); printHex(pub, 65); }
                else Serial.println("[Admin] pubKey not stored");
            } else if (c == 'y') {
                String sk; bool have = ProvisioningPhase::getSharedKeyId(sk);
                if (!have) Serial.println("[Admin] Shared key: <none>");
                else Serial.printf("[Admin] Shared keyId: %s\n", sk.c_str());
            } else if (c == 'Y') {
                ProvisioningPhase::clearSharedKey();
                Serial.println("[Admin] Shared key cleared");
            } else if (c == 's') {
                shareKeySyncOneShot = true;
                Serial.println("[Admin] Share-key sync ARMED (one-shot on next NFC session)");
            } else if (c == 'h') {
                Serial.println("Commands: p f F r C v x y Y s h");
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
        bool pn532Seen = i2cDevicePresentStable(0x24, 3, 3);
        if (!pn532Seen) {
            Serial.printf("[I2C] PN532 not found on SDA=%d SCL=%d. Trying swapped pins...\n", activeSdaPin, activeSclPin);
            initI2cBus(sclPin, sdaPin, activeI2cClock);
            if (i2cDevicePresentStable(0x24, 3, 3)) {
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

    // 💡 Update async LED blink (non-blocking)
    void updateLedBlink() {
        if (!ledBlinkActive) return;
        
        uint32_t now = millis();
        if (now - ledLastToggle >= LED_BLINK_INTERVAL) {
            digitalWrite(48, !digitalRead(48));  // Toggle LED
            ledLastToggle = now;
            ledBlinkCount++;
            
            if (ledBlinkCount >= LED_BLINK_TOTAL) {
                // Blink complete - always restore the steady lock/unlock indicator state
                ledBlinkActive = false;
                ledBlinkCount = 0;
                digitalWrite(48, expectedLedState ? HIGH : LOW);
            }
        }
    }

    void tick() {
        // 💡 Update LED blink state (non-blocking)
        updateLedBlink();
        
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

        if (!i2cDevicePresentStable(0x24, 1, 0)) {  // Relax: require only 1 ACK, no retry delay
            pn532Ready = false;
            samConfigured = false;
            Serial.println("[I2C] I2C brief glitch; queuing reconnect...");
            return;
        }

        // 🔐 HARD LATCH: once a tap starts, require phone removal before arming the next tap.
        // This prevents repeated SELECT/auth attempts while the phone is still resting on the reader.
        if (cardSessionActive) {
            if (waitForRemoval(180, 2, 35)) {
                cardSessionActive = false;
                lastSessionStatusLogMs = 0;
#if DEBUG_LOGS
                Serial.println("✅ Phone removed - ready for next tap");
#endif
            } else {
                uint32_t elapsed = millis() - sessionStartMs;
                if (elapsed >= SESSION_FORCE_REARM_MS) {
                    // Fallback: some phones/PN532 states keep reporting "present" after success.
                    // Force the link back to idle so the next physical tap can start a fresh session.
                    releaseTarget();
                    delay(20);
                    cardSessionActive = false;
                    lastSessionStatusLogMs = 0;
#if DEBUG_LOGS
                    Serial.println("♻️  Forced NFC re-arm after hold timeout - re-tap phone");
#endif
                } else {
#if DEBUG_LOGS
                    if (millis() - lastSessionStatusLogMs >= 300) {
                        lastSessionStatusLogMs = millis();
                        if (elapsed < SESSION_WINDOW_MS) {
                            Serial.printf("🚧 Session active (%lums remaining) - remove phone\n",
                                         SESSION_WINDOW_MS - elapsed);
                        } else {
                            Serial.println("⌛ Waiting for phone removal before next tap");
                        }
                    }
#endif
                }
                return;
            }
        }

        // Wait for a fresh card/tap; continuous polling without long timeout.
        if (!waitForCard(0, 1, 2)) {  // 🚀 Use 2ms poll for instant response
            // I2C bus lost, recovery handled in waitForCard.
#if DEBUG_LOGS
            Serial.println("[DEBUG] waitForCard returned false - I2C issue?");
#endif
            return;
        }

        // Latch this physical tap immediately so failures also require a clean remove/re-tap.
        cardSessionActive = true;
        sessionStartMs = millis();
        lastSessionStatusLogMs = 0;

        // CCC R3 §4.2: Reject if >1 card in RF field (relay attack / multiple credentials)
        if (!checkAntiCollision()) {
            cardSessionActive = false;
            return;
        }

#if DEBUG_LOGS
        Serial.printf("[DEBUG] Card detected at %lums\n", millis());
#endif

        // 🔥 CRITICAL: Samsung Android 13 HCE needs a bit more warmup before SELECT/AUTH.
        // Favor stability over raw speed here to reduce intermittent routing races.
        delay(180);
#if DEBUG_LOGS
        Serial.printf("⏱️  HCE warmup done [%lums]\n", millis());
#endif

        // 🚀 OPTIMIZED: Do NOT poll card during SELECT - it confuses Android HCE routing!
        // Just attempt SELECT directly after warmup.
        uint8_t sw1 = 0, sw2 = 0;
        int payloadLen = 0;
        uint8_t actionBuf[4] = {0};
        bool selectOk = false;

        // 🔥 RETRY: Try SELECT up to 3 times with RF stabilization delay
        // Common failures: anti-collision, RF field instability, HCE routing race
        for (int attempt = 1; attempt <= 3 && !selectOk; ++attempt) {
            if (attempt > 1) {
#if DEBUG_LOGS
                Serial.printf("[CCC] SELECT retry %d/3\n", attempt);
#endif
                delay(70);  // Slightly longer RF stabilization between SELECT retries
            }
            
            if (selectAid(sw1, sw2, payloadLen, actionBuf, sizeof(actionBuf))) {
                if (sw1 == 0x90 && sw2 == 0x00) {
                    selectOk = true;
                    break;
                }
            }
        }

        if (!selectOk) {
            Serial.println("SELECT AID failed");
            postSessionCooldown(false);
            return;
        }

#if DEBUG_LOGS
        Serial.printf("SELECT SW1SW2=%02X%02X [%lums] payloadLen=%d  Provisioned=%s  Force(persist)=%s  OneShot=%s\n",
                                    sw1, sw2, millis(), payloadLen,
                                    ProvisioningPhase::isProvisioned() ? "YES" : "NO",
                                    forceProvisionPersistent ? "YES" : "NO",
                                    forceProvisionOneShot ? "ARMED" : "NO");
#else
        Serial.printf("SELECT SW1SW2=%02X%02X payloadLen=%d  Provisioned=%s  Force(persist)=%s  OneShot=%s\n",
                                    sw1, sw2, payloadLen,
                                    ProvisioningPhase::isProvisioned() ? "YES" : "NO",
                                    forceProvisionPersistent ? "YES" : "NO",
                                    forceProvisionOneShot ? "ARMED" : "NO");
#endif

        // 🔥 CRITICAL: HCE stabilization delay after SELECT.
        // A slightly longer settle time is more reliable on repeated Samsung taps.
        delay(90);

        // CCC R3 two-phase flow: Phase 2 identity + Phase 3 action authorization
        uint8_t action = performCccFlow(sw1, sw2);
        
        // 🎯 ACTION CHANGE DETECTION: Only execute if action is different from last time
        // This prevents LED blinking when tapping multiple times with same action
        bool actionChanged = (action != 0x00 && action != lastExecutedAction);
        
        if (actionChanged) {
            // Action changed - update the steady lock/unlock indicator
            if (action == 0x01) {
                expectedLedState = LOW;
                digitalWrite(48, LOW);
                Serial.println("[CCC] Verified action: LOCK -> LED OFF (GPIO 48)");
            } else if (action == 0x02) {
                expectedLedState = HIGH;
                digitalWrite(48, HIGH);
                Serial.println("[CCC] Verified action: UNLOCK -> LED ON (GPIO 48)");
            }
            lastExecutedAction = action;

            // Do not blink the same state LED here; blinking can briefly show the wrong state.
            ledBlinkActive = false;
            ledBlinkCount = 0;
        } else if (action != 0x00) {
            // Same action - keep the physical LED synchronized with the last verified state
            int actualLedState = digitalRead(48);
            int desiredLedState = expectedLedState ? HIGH : LOW;
            if (actualLedState != desiredLedState) {
                digitalWrite(48, desiredLedState);
                Serial.printf("[CCC] 🔄 Restored LED to %s for repeated action 0x%02X\n",
                              expectedLedState ? "ON" : "OFF", action);
            }
#if DEBUG_LOGS
            Serial.printf("[CCC] ⏭️  Same action (0x%02X) - LED unchanged\n", action);
#endif
        } else {
            Serial.println("[CCC] No verified action executed");
        }

        // 🔐 CCC SPEC COMPLIANCE: keep the tap latched until the phone is removed.
        // The latch is armed at first detection so even failed/partial exchanges do not thrash the RF link.
#if DEBUG_LOGS
        if (action != 0x00) {
            Serial.printf("✅ Auth complete! Remove phone to re-arm (%ums window)\n\n", SESSION_WINDOW_MS);
        } else {
            Serial.println("ℹ️  Attempt ended without action - remove/re-tap to retry\n");
        }
#endif

        // Clean up PN532 state for next detection cycle
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

    bool shareKeyCcc(const char* keyIdAscii,
                     const uint8_t* delegatedPub65,
                     const uint8_t* ownerSigDer, size_t ownerSigLen) {
        // 🔥 PRODUCTION: Note - sessionCtx nullptr means no session binding (less secure)
        // For production, this wrapper should be used with caution
        return ProvisioningPhase::storeSharedKeyCcc(
            keyIdAscii, delegatedPub65, ownerSigDer, ownerSigLen, 
            nullptr, 0,  // No session context (backward compat)
            0,  // No expiry
            ProvisioningPhase::PERM_FULL);  // Full access
    }
}
