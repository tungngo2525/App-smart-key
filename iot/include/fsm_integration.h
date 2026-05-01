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

/**
 * UWB namespace — CCC Phase C ranging events
 * Gọi từ uwb_ranging.cpp khi khoảng cách vượt ngưỡng
 */
namespace UWB {
  /** Key fob đã vào vùng gần (≤ UNLOCK_THRESHOLD) liên tiếp đủ CONFIRM_COUNT lần */
  void onInRange();
  /** Key fob ra xa (> LOCK_THRESHOLD) hoặc mất tín hiệu */
  void onOutOfRange();
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
