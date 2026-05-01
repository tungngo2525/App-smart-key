#pragma once

/**
 * UWB Ranging Module — CCC Digital Key R3 Phase C (DW1000 variant)
 *
 * Chuẩn CCC R3 dùng IEEE 802.15.4z (FiRa) với DW3000/SR150.
 * DW1000 implement IEEE 802.15.4a — cùng nguyên lý DS-TWR, độ chính xác ~±10cm.
 *
 * Kiến trúc:
 *   ECU (Yolo Uno S3 + DW1000)  ← SPI → responder
 *   Key fob (ESP32 + DW1000)    ← wireless → initiator
 *
 * Wiring DW1000 → ESP32-S3 (Yolo Uno):
 *   VCC  → 3.3V
 *   GND  → GND
 *   SCK  → GPIO 12
 *   MOSI → GPIO 11
 *   MISO → GPIO 13
 *   CS   → GPIO 10
 *   IRQ  → GPIO  9
 *   RST  → GPIO  8
 *
 * Flow (CCC-like):
 *   1. BLE Phase B xong (SessionState::READY) → BLEMod notify → UWBRanging::enable()
 *   2. Khi BLE disconnect                     → UWBRanging::disable()
 *   3. Key fob gửi Poll frame
 *   4. ECU trả Response + Final
 *   5. ECU tính khoảng cách DS-TWR
 *   6. Nếu distance < UNLOCK_THRESHOLD_M → FSMIntegration::UWB::onInRange()
 *   7. Nếu distance > LOCK_THRESHOLD_M   → FSMIntegration::UWB::onOutOfRange()
 *   8. Khoảng cách broadcast qua BLE AdminInfo: "UWB:1.23m"
 */

namespace UWBRanging {

    // --- Configuration ---
    static constexpr float UNLOCK_THRESHOLD_M = 1.5f;  // unlock khi < 1.5m
    static constexpr float LOCK_THRESHOLD_M   = 2.5f;  // lock khi > 2.5m (hysteresis)
    static constexpr int   CONFIRM_COUNT       = 3;     // số readings liên tiếp xác nhận

    // Pins (thay đổi nếu cần theo board của bạn)
    static constexpr uint8_t PIN_CS  = 10;
    static constexpr uint8_t PIN_IRQ =  9;
    static constexpr uint8_t PIN_RST =  8;

    /** Khởi tạo DW1000 hardware và cấu hình responder */
    void begin();

    /** Gọi từ loop() — xử lý ranging state machine */
    void tick();

    /** Bật ranging (gọi sau BLE auth READY) */
    void enable();

    /** Tắt ranging (gọi khi BLE disconnect) */
    void disable();

    /** Khoảng cách đo được lần cuối (meters). -1 nếu chưa có reading. */
    float getLastDistance();

    /** true nếu DW1000 init thành công */
    bool isHardwareOK();

    /** true nếu ranging đang active */
    bool isEnabled();

} // namespace UWBRanging
