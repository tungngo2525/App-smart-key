#pragma once

#include <NimBLEDevice.h>

// Forward declaration for RNG context
struct mbedtls_ctr_drbg_context;

namespace BLEAuth {
  // Register the AUTH service and start the crypto worker.
  // Uses the provided DRBG for key generation and nonces.
  void registerService(NimBLEServer* server, mbedtls_ctr_drbg_context* drbg);

  // Session status and key accessors
  bool isSessionReady();
  const uint8_t* sessionEncKey();
  size_t sessionEncKeyLen();

  // Reset the current session state (keys, buffers) on disconnect.
  void resetSession();
}
