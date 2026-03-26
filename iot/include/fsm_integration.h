#pragma once
#include "fsm.h"

// Helper functions to trigger FSM events from NFC/BLE callbacks
namespace FSMIntegration {

namespace NFC {
  void onCardDetected();
  void onCardRemoved();
  void onSelectAidSuccess();
  void onSelectAidFailed();
  void onKeysExchanged();
  void onKeysInvalid();
  void onCredentialsStored();
}

namespace BLE {
  void onClientConnected();
  void onClientDisconnected();
  void onClientHelloReceived();
  void onServerHelloSent();
  void onClientConfirmReceived();
  void onAuthVerified();
  void onAuthFailed();
  void onUnlockRequested();
  void onAdminCommand();
}

namespace SerialCmd {
  void startProvisioning();
  void toggleForceProvision();
  void armOneShotForce();
  void clearKeys();
  void clearAll();
  void printDiagnostics();
  void printFSMStatus();
}

} // namespace FSMIntegration
