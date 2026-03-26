#pragma once

namespace BLEMod {
  // Initialize BLE (GATT server, advertising)
  void begin();

  // Status helpers
  bool isStarted();
  const char* deviceName();

  // Admin service: device modes for NFC admin operations
  enum AdminMode : uint8_t { ADMIN_NORMAL = 0, ADMIN_ENROLL = 1, ADMIN_REMOVE = 2 };
  AdminMode getAdminMode();
  void setAdminMode(AdminMode mode);

  // Update AdminInfo characteristic and notify connected central (short ASCII status)
  void adminNotify(const char* msg);

  // Phase B: session status helper
  bool isSessionReady();
}
