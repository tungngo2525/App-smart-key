/**
 * uwb_ranging.cpp — CCC R3-like UWB ranging, ECU side (DS-TWR Responder)
 *
 * Giao thức Double-Sided Two-Way Ranging (DS-TWR):
 *
 *   Key fob (Initiator)         ECU (Responder)
 *        |                           |
 *        |------- POLL (msg 1) ----->|  T_sp ghi nhận
 *        |                           |  delay RESP_DELAY
 *        |<------ RESP (msg 2) ------|  T_sp + T_rr
 *        |  T_rp ghi nhận            |
 *        |------- FINAL (msg 3) ---->|  T_sf ghi nhận
 *        |                           |
 *        | ECU tính distance và gửi  |
 *        |<-- REPORT (msg 4, opt) ---|  chứa distance hoặc raw timestamps
 *
 * Công thức DS-TWR:
 *   ToF = (Ra * Rb - Da * Db) / (2 * (Ra + Rb))
 *   với:
 *     Ra = T_rr - T_sp  (Round trip A)
 *     Da = T_sf - T_sp  (turn-around A at initiator)
 *     Rb = T_sf - T_rr  (Round trip B at responder)
 *     Db = T_rp - T_sp  (không dùng trong simplified)
 *
 * Library: thotro/arduino-dw1000
 */

#include <Arduino.h>
#include <SPI.h>
#include <DW1000.h>
#include <DW1000Ranging.h>

#include "uwb_ranging.h"
#include "fsm_integration.h"

// Forward declare BLE notify helper (defined in ble_admin.cpp)
namespace BLEMod {
    void adminNotify(const char* msg);
}

namespace {

// --- State ---
enum class RangingState { IDLE, WAITING_POLL, SENDING_RESP, WAITING_FINAL, DONE };

volatile bool s_hwOK    = false;
volatile bool s_enabled = false;

float s_lastDistance     = -1.0f;
int   s_inRangeCount     = 0;
int   s_outRangeCount    = 0;
bool  s_isUnlocked       = false;

// Ranging activity timestamp (ms)
unsigned long s_lastRangingMs = 0;
static constexpr unsigned long RANGING_TIMEOUT_MS = 5000; // 5s không có ranging → báo out

// ---- DW1000Ranging callbacks ----

void onNewRange() {
    DW1000Device* d = DW1000Ranging.getDistantDevice();
    if (!d) return;

    float dist = d->getRange();
    if (dist <= 0.0f || dist > 100.0f) return;  // loại bỏ outlier

    s_lastDistance   = dist;
    s_lastRangingMs  = millis();

    // Broadcast qua BLE AdminInfo
    char buf[32];
    snprintf(buf, sizeof(buf), "UWB:%.2fm", dist);
    BLEMod::adminNotify(buf);
    Serial.printf("[UWB] Distance: %.2f m\n", dist);

    // --- Hysteresis logic ---
    if (!s_isUnlocked) {
        if (dist < UWBRanging::UNLOCK_THRESHOLD_M) {
            s_inRangeCount++;
            s_outRangeCount = 0;
            if (s_inRangeCount >= UWBRanging::CONFIRM_COUNT) {
                s_isUnlocked    = true;
                s_inRangeCount  = 0;
                Serial.printf("[UWB] UNLOCK triggered (%.2fm < %.2fm)\n",
                              dist, UWBRanging::UNLOCK_THRESHOLD_M);
                FSMIntegration::UWB::onInRange();
            }
        } else {
            if (s_inRangeCount > 0) s_inRangeCount--;
        }
    } else {
        if (dist > UWBRanging::LOCK_THRESHOLD_M) {
            s_outRangeCount++;
            s_inRangeCount = 0;
            if (s_outRangeCount >= UWBRanging::CONFIRM_COUNT) {
                s_isUnlocked    = false;
                s_outRangeCount = 0;
                Serial.printf("[UWB] LOCK triggered (%.2fm > %.2fm)\n",
                              dist, UWBRanging::LOCK_THRESHOLD_M);
                FSMIntegration::UWB::onOutOfRange();
            }
        } else {
            if (s_outRangeCount > 0) s_outRangeCount--;
        }
    }
}

void onNewDevice(DW1000Device* d) {
    Serial.printf("[UWB] New device: %s\n", d->getShortAddress());
    BLEMod::adminNotify("UWB:TAG_FOUND");
}

void onInactiveDevice(DW1000Device* d) {
    Serial.printf("[UWB] Device inactive: %s\n", d->getShortAddress());
    // Key fob ra xa hoặc mất pin — coi như out of range
    s_lastDistance  = -1.0f;
    s_inRangeCount  = 0;
    if (s_isUnlocked) {
        s_isUnlocked    = false;
        s_outRangeCount = 0;
        Serial.println("[UWB] LOCK: tag inactive");
        FSMIntegration::UWB::onOutOfRange();
    }
    BLEMod::adminNotify("UWB:TAG_LOST");
}

} // namespace

// ============================================================
// Public API
// ============================================================

void UWBRanging::begin() {
    Serial.println("[UWB] Initializing DW1000...");

    SPI.begin(12 /*SCK*/, 13 /*MISO*/, 11 /*MOSI*/);

    DW1000Ranging.initCommunication(PIN_RST, PIN_CS, PIN_IRQ);

    // Channel 5, 6.8Mb/s PRF64 — giống CCC R3 preferred config
    DW1000Ranging.attachNewRange(onNewRange);
    DW1000Ranging.attachNewDevice(onNewDevice);
    DW1000Ranging.attachInactiveDevice(onInactiveDevice);

    // Địa chỉ ECU anchor: 0x0001, PAN ID: 0xDECA
    // CCC R3 Phase C: Channel 5 (set by setDefaults), 6.8 Mbps, PRF 64 MHz
    DW1000Ranging.startAsAnchor("00:01", DW1000.MODE_LONGDATA_FAST_ACCURACY, false);

    s_hwOK   = true;
    s_enabled = false;
    Serial.println("[UWB] DW1000 ready as anchor (responder). Ranging disabled until BLE auth.");
}

void UWBRanging::tick() {
    if (!s_hwOK || !s_enabled) return;

    DW1000Ranging.loop();

    // Timeout: nếu có tag nhưng lâu không cập nhật → lock
    if (s_isUnlocked && s_lastDistance >= 0.0f) {
        if (millis() - s_lastRangingMs > RANGING_TIMEOUT_MS) {
            s_isUnlocked    = false;
            s_inRangeCount  = 0;
            s_outRangeCount = 0;
            s_lastDistance  = -1.0f;
            Serial.println("[UWB] LOCK: ranging timeout");
            FSMIntegration::UWB::onOutOfRange();
            BLEMod::adminNotify("UWB:TIMEOUT_LOCK");
        }
    }
}

void UWBRanging::enable() {
    if (!s_hwOK) return;
    s_enabled       = true;
    s_inRangeCount  = 0;
    s_outRangeCount = 0;
    s_isUnlocked    = false;
    s_lastDistance  = -1.0f;
    s_lastRangingMs = millis();
    Serial.println("[UWB] Ranging ENABLED (BLE session ready)");
    BLEMod::adminNotify("UWB:ENABLED");
}

void UWBRanging::disable() {
    if (!s_hwOK) return;
    s_enabled = false;
    if (s_isUnlocked) {
        s_isUnlocked = false;
        FSMIntegration::UWB::onOutOfRange();
    }
    s_lastDistance  = -1.0f;
    s_inRangeCount  = 0;
    s_outRangeCount = 0;
    Serial.println("[UWB] Ranging DISABLED");
    BLEMod::adminNotify("UWB:DISABLED");
}

float UWBRanging::getLastDistance() { return s_lastDistance; }
bool  UWBRanging::isHardwareOK()    { return s_hwOK; }
bool  UWBRanging::isEnabled()       { return s_enabled; }
