#pragma once
#include <Arduino.h>
#include <PN532_I2C.h>
#include <PN532.h>

namespace NfcSession {
  void begin(int sdaPin, int sclPin, uint32_t i2cClock = 400000);
  void tick();

  // Admin controls for provisioning behavior
  void setPersistentForce(bool on);
  void armOneShotForce();
  bool getPersistentForce();
  bool isOneShotArmed();

  // CCC shared key API: verifies owner's signature and stores delegated key.
  bool shareKeyCcc(const char* keyIdAscii,
                   const uint8_t* delegatedPub65,
                   const uint8_t* ownerSigDer, size_t ownerSigLen);
}
