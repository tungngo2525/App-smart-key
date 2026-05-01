#include <Arduino.h>
#include "fsm.h"
#include "fsm_integration.h"
#include "nfc_session.h"
#include "cloud_sync.h"
#include "ble/ble.h"
#include "uwb_ranging.h"

void handleSerialCommands();

void setup() {
  Serial.begin(115200);
  delay(2000);
  while (!Serial) delay(10);
  
  Serial.println("\n========================================");
  Serial.println("   Smart Car Access System v2.0 FSM");
  Serial.println("========================================\n");
  
  Serial.println("[MAIN] Initializing LED on GPIO 48...");
  pinMode(48, OUTPUT);
  digitalWrite(48, LOW); // Start with LED off (locked state)

  Serial.println("[MAIN] Initializing NFC (PN532 I2C SDA=11 SCL=12)...");
  NfcSession::begin(11, 12);
  
  Serial.println("[MAIN] Initializing FSM...");
  FSM::begin();

  Serial.println("[MAIN] Initializing cloud sync...");
  CloudSync::begin();

  Serial.println("[MAIN] Initializing BLE...");
  BLEMod::begin();

  Serial.println("[MAIN] Initializing UWB (DW1000)...");
  UWBRanging::begin();
  
  Serial.println("[MAIN] System ready! Press 'h' for help\n");
}

void loop() {
  FSM::tick();
  NfcSession::tick();
  CloudSync::tick();
  UWBRanging::tick();
  handleSerialCommands();
}

// Handle serial commands to control FSM
void handleSerialCommands() {
  if (!Serial.available()) return;
  
  char cmd = Serial.read();
  
  switch (cmd) {
    case 's':
      FSMIntegration::SerialCmd::printFSMStatus();
      break;
    case 'p':
      FSMIntegration::SerialCmd::startProvisioning();
      break;
    case 'f':
      FSMIntegration::SerialCmd::toggleForceProvision();
      break;
    case 'F':
      FSMIntegration::SerialCmd::armOneShotForce();
      break;
    case 'r':
      FSMIntegration::SerialCmd::clearKeys();
      break;
    case 'C':
      FSMIntegration::SerialCmd::clearAll();
      break;
    case 'w':
      CloudSync::printStatus();
      break;
    case 'W':
      Serial.println("[MAIN] Forcing cloud sync now...");
      CloudSync::requestSyncNow();
      break;
    case 'h':
      Serial.println("\n=== Commands ===");
      Serial.println("s - FSM status");
      Serial.println("p - Start provisioning");
      Serial.println("f - Toggle force provision");
      Serial.println("F - Arm one-shot force");
      Serial.println("r - Clear keys");
      Serial.println("C - Clear all");
      Serial.println("w - Cloud sync status");
      Serial.println("W - Force cloud sync");
      Serial.println("u - Test unlock");
      Serial.println("h - Help\n");
      break;
    case 'u':
      Serial.println("[MAIN] Simulating unlock...");
      FSM::triggerEvent(FSM::UNLOCK_REQUESTED);
      break;
    default:
      if (cmd >= 32 && cmd <= 126) {
        Serial.printf("[MAIN] Unknown: '%c'. Press 'h' for help.\n", cmd);
      }
      break;
  }
}
