#pragma once
#include <Arduino.h>
#include <PN532_I2C.h>
#include <PN532.h>

namespace NfcSession {
  void begin(int sdaPin, int sclPin, uint32_t i2cClock = 100000);
  void tick();

  // Admin controls for provisioning behavior
  void setPersistentForce(bool on);
  void armOneShotForce();
  bool getPersistentForce();
  bool isOneShotArmed();
}
