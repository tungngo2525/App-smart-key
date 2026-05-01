/**
 * UWB Key Fob Firmware — CCC R3-like DS-TWR Initiator
 *
 * Flash lên ESP32 mini + DW1000 — thiết bị người dùng mang theo.
 * Tự động bắt đầu ranging với ECU anchor (address 0x0001).
 *
 * Wiring DW1000 → ESP32:
 *   VCC  → 3.3V    GND → GND
 *   SCK  → GPIO 18  MOSI → GPIO 23
 *   MISO → GPIO 19  CS   → GPIO 4
 *   IRQ  → GPIO 2   RST  → GPIO 5
 *
 * LED status:
 *   GPIO 2  (built-in): nhấp nháy khi ranging active
 */

#include <Arduino.h>
#include <SPI.h>
#include <DW1000.h>
#include <DW1000Ranging.h>

// ---- Pins ----
#define PIN_SCK  18
#define PIN_MOSI 23
#define PIN_MISO 19
#define PIN_CS    4
#define PIN_IRQ   2
#define PIN_RST   5
#define PIN_LED   2   // built-in LED (shared với IRQ trên nhiều board ESP32)

// ---- Địa chỉ key fob: 0x0002, PAN: 0xDECA ----
#define TAG_ADDRESS "00:02"

void onNewRange() {
    DW1000Device* device = DW1000Ranging.getDistantDevice();
    if (!device) return;
    Serial.printf("[TAG] ECU distance: %.2f m | Signal: %f dBm\n",
                  device->getRange(), device->getRXPower());
}

void onNewDevice(DW1000Device* device) {
    Serial.printf("[TAG] New anchor: %s\n", device->getShortAddress());
}

void onInactiveDevice(DW1000Device* device) {
    Serial.printf("[TAG] Anchor lost: %s\n", device->getShortAddress());
}

void setup() {
    Serial.begin(115200);
    delay(1000);
    Serial.println("[KEYFOB] UWB Tag starting...");

    pinMode(PIN_LED, OUTPUT);
    digitalWrite(PIN_LED, LOW);

    SPI.begin(PIN_SCK, PIN_MISO, PIN_MOSI);

    DW1000Ranging.initCommunication(PIN_RST, PIN_CS, PIN_IRQ);
    DW1000Ranging.attachNewRange(onNewRange);
    DW1000Ranging.attachNewDevice(onNewDevice);
    DW1000Ranging.attachInactiveDevice(onInactiveDevice);

    // Tag mode (initiator gửi Poll)
    DW1000Ranging.startAsTag(TAG_ADDRESS, DW1000.MODE_LONGDATA_FAST_ACCURACY, false);

    Serial.println("[KEYFOB] Ready — ranging started.");
}

void loop() {
    DW1000Ranging.loop();

    // LED nhấp nháy 500ms để biết đang hoạt động
    static unsigned long lastBlink = 0;
    static bool ledState = false;
    if (millis() - lastBlink > 500) {
        ledState = !ledState;
        digitalWrite(PIN_LED, ledState ? HIGH : LOW);
        lastBlink = millis();
    }
}
