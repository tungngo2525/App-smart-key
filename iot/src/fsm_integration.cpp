#include "fsm_integration.h"
#include "fsm.h"
#include <Arduino.h>

namespace FSMIntegration {

namespace NFC {
  void onCardDetected() {
    Serial.println("[FSM-INT/NFC] Card detected");
    FSM::triggerEvent(FSM::NFC_CARD_DETECTED);
  }
  
  void onCardRemoved() {
    Serial.println("[FSM-INT/NFC] Card removed");
    FSM::triggerEvent(FSM::NFC_CARD_REMOVED);
  }
  
  void onSelectAidSuccess() {
    Serial.println("[FSM-INT/NFC] SELECT AID success");
    FSM::triggerEvent(FSM::SELECT_AID_SUCCESS);
  }
  
  void onSelectAidFailed() {
    Serial.println("[FSM-INT/NFC] SELECT AID failed");
    FSM::triggerEvent(FSM::SELECT_AID_FAILED);
  }
  
  void onKeysExchanged() {
    Serial.println("[FSM-INT/NFC] Keys exchanged");
    FSM::triggerEvent(FSM::KEYS_EXCHANGED);
  }
  
  void onKeysInvalid() {
    Serial.println("[FSM-INT/NFC] Keys invalid");
    FSM::triggerEvent(FSM::KEYS_INVALID);
  }
  
  void onCredentialsStored() {
    Serial.println("[FSM-INT/NFC] Credentials stored");
    FSM::triggerEvent(FSM::CREDENTIALS_STORED);
  }
}

namespace BLE {
  void onClientConnected() {
    Serial.println("[FSM-INT/BLE] Client connected");
    FSM::triggerEvent(FSM::BLE_CLIENT_CONNECTED);
  }
  
  void onClientDisconnected() {
    Serial.println("[FSM-INT/BLE] Client disconnected");
    FSM::triggerEvent(FSM::BLE_CLIENT_DISCONNECTED);
  }
  
  void onClientHelloReceived() {
    Serial.println("[FSM-INT/BLE] ClientHello received");
    FSM::triggerEvent(FSM::CLIENT_HELLO_RECEIVED);
  }
  
  void onServerHelloSent() {
    Serial.println("[FSM-INT/BLE] ServerHello sent");
    FSM::triggerEvent(FSM::SERVER_HELLO_SENT);
  }
  
  void onClientConfirmReceived() {
    Serial.println("[FSM-INT/BLE] ClientConfirm received");
    FSM::triggerEvent(FSM::CLIENT_CONFIRM_RECEIVED);
  }
  
  void onAuthVerified() {
    Serial.println("[FSM-INT/BLE] Auth verified!");
    FSM::triggerEvent(FSM::AUTH_VERIFIED);
  }
  
  void onAuthFailed() {
    Serial.println("[FSM-INT/BLE] Auth failed!");
    FSM::triggerEvent(FSM::AUTH_FAILED);
  }
  
  void onUnlockRequested() {
    Serial.println("[FSM-INT/BLE] Unlock requested!");
    FSM::triggerEvent(FSM::UNLOCK_REQUESTED);
  }
  
  void onAdminCommand() {
    Serial.println("[FSM-INT/BLE] Admin command");
    FSM::triggerEvent(FSM::ADMIN_COMMAND);
  }
}

namespace SerialCmd {
  void startProvisioning() {
    Serial.println("[FSM-INT/Serial] Starting provisioning...");
    FSM::triggerEvent(FSM::PROVISION_START);
  }
  
  void toggleForceProvision() {
    Serial.println("[FSM-INT/Serial] Toggle force provision");
    FSM::triggerEvent(FSM::FORCE_PROVISION_ON);
  }
  
  void armOneShotForce() {
    Serial.println("[FSM-INT/Serial] Arm one-shot force");
    FSM::triggerEvent(FSM::PROVISION_START);
  }
  
  void clearKeys() {
    Serial.println("[FSM-INT/Serial] Clearing keys...");
    FSM::triggerEvent(FSM::CLEAR_KEYS);
  }
  
  void clearAll() {
    Serial.println("[FSM-INT/Serial] Clearing all...");
    FSM::triggerEvent(FSM::CLEAR_KEYS);
  }
  
  void printDiagnostics() {
    Serial.println("[FSM-INT/Serial] Diagnostics request");
    FSM::triggerEvent(FSM::DIAGNOSTICS_REQUEST);
  }
  
  void printFSMStatus() {
    Serial.println("[FSM-INT/Serial] FSM status:");
    FSM::printStatus();
  }
}

} // namespace FSMIntegration
