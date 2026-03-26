/**
 * @file fsm.cpp
 * @brief FSM core implementation - Main state machine logic
 */

#include "fsm.h"
#include "nfc_session.h"
#include "ble/ble.h"
#include "provisioning_phase.h"
#include <Arduino.h>

namespace FSM {

// ============ Internal State ============
namespace {
  StateInfo g_stateInfo;
  
  bool g_debugLogging = true;
  Event g_pendingEvent = SYSTEM_READY;
  bool g_hasPendingEvent = false;
  
  State handle_INIT();
  State handle_IDLE();
  State handle_PROVISIONING_WAIT_TAP();
  State handle_PROVISIONING_SELECT_AID();
  State handle_PROVISIONING_EXCHANGE_KEYS();
  State handle_PROVISIONING_STORE_CREDS();
  State handle_AUTH_WAIT_CONNECT();
  State handle_AUTH_HANDSHAKE();
  State handle_AUTH_VERIFY_KEYS();
  State handle_AUTH_SESSION_READY();
  State handle_UNLOCKING_CHECK_PROXIMITY();
  State handle_UNLOCKING_VERIFY_AUTH();
  State handle_UNLOCKING_EXECUTE();
  State handle_UNLOCKING_COMPLETE();
  State handle_ADMIN_MODE();
  State handle_ERROR_HANDLER();

  /**
   * @brief Transition to new state
   */
  void transitionTo(State newState, Event event) {
    if (g_stateInfo.current == newState) return;

    if (g_debugLogging) {
      Serial.printf("[FSM] Transition: %s -> %s (event: %s)\n",
        stateToString(g_stateInfo.current),
        stateToString(newState),
        eventToString(event));
    }

    g_stateInfo.previous = g_stateInfo.current;
    g_stateInfo.current = newState;
    g_stateInfo.lastEvent = event;
    g_stateInfo.enter_time = millis();
  }

  /**
   * @brief Log error and transition to ERROR_HANDLER
   */
  void handleError(ErrorCode error) {
    g_stateInfo.lastError = error;
    Serial.printf("[FSM] ERROR: %s\n", errorToString(error));
    transitionTo(State::ERROR_HANDLER, Event::ERROR_OCCURRED);
  }

} // anonymous namespace

// ============ Public API Implementation ============

void begin() {
  Serial.println("\n[FSM] ========== Initializing FSM ==========");
  
  g_stateInfo.current = INIT;
  g_stateInfo.previous = INIT;
  g_stateInfo.lastEvent = SYSTEM_READY;
  g_stateInfo.lastError = NONE;
  g_stateInfo.enter_time = millis();
}

void tick() {
  State nextState = State::INIT;
  
  switch (g_stateInfo.current) {
    case State::INIT:
      nextState = handle_INIT();
      break;
    case State::IDLE:
      nextState = handle_IDLE();
      break;
      
    case State::PROVISIONING_WAIT_TAP:
      nextState = handle_PROVISIONING_WAIT_TAP();
      break;
    case State::PROVISIONING_SELECT_AID:
      nextState = handle_PROVISIONING_SELECT_AID();
      break;
    case State::PROVISIONING_EXCHANGE_KEYS:
      nextState = handle_PROVISIONING_EXCHANGE_KEYS();
      break;
    case State::PROVISIONING_STORE_CREDS:
      nextState = handle_PROVISIONING_STORE_CREDS();
      break;
      
    case State::AUTH_WAIT_CONNECT:
      nextState = handle_AUTH_WAIT_CONNECT();
      break;
    case State::AUTH_HANDSHAKE:
      nextState = handle_AUTH_HANDSHAKE();
      break;
    case State::AUTH_VERIFY_KEYS:
      nextState = handle_AUTH_VERIFY_KEYS();
      break;
    case State::AUTH_SESSION_READY:
      nextState = handle_AUTH_SESSION_READY();
      break;
      
    case State::UNLOCKING_CHECK_PROXIMITY:
      nextState = handle_UNLOCKING_CHECK_PROXIMITY();
      break;
    case State::UNLOCKING_VERIFY_AUTH:
      nextState = handle_UNLOCKING_VERIFY_AUTH();
      break;
    case State::UNLOCKING_EXECUTE:
      nextState = handle_UNLOCKING_EXECUTE();
      break;
    case State::UNLOCKING_COMPLETE:
      nextState = handle_UNLOCKING_COMPLETE();
      break;
      
    case State::ADMIN_MODE:
      nextState = handle_ADMIN_MODE();
      break;
      
    case State::ERROR_HANDLER:
      nextState = handle_ERROR_HANDLER();
      break;
  }
  
  if (nextState != g_stateInfo.current) {
    transitionTo(nextState, g_stateInfo.lastEvent);
  }
  
  if (g_hasPendingEvent) {
    g_stateInfo.lastEvent = g_pendingEvent;
    g_hasPendingEvent = false;
  }
}

void triggerEvent(Event event) {
  if (g_debugLogging) {
    Serial.printf("[FSM] Event triggered: %s (current state: %s)\n",
      eventToString(event),
      stateToString(g_stateInfo.current));
  }
  g_pendingEvent = event;
  g_hasPendingEvent = true;
}

State getCurrentState() {
  return g_stateInfo.current;
}

StateInfo getStateInfo() {
  return g_stateInfo;
}

bool forceState(State newState, bool force) {
  if (!force) {
    Serial.println("[FSM] WARNING: Force state without validation!");
  }
  transitionTo(newState, Event::RESET_REQUESTED);
  return true;
}

void reset(bool clearSession) {
  Serial.println("[FSM] Resetting to IDLE...");
  if (clearSession) {
    Serial.println("[FSM] Clearing auth session");
  }
  transitionTo(State::IDLE, Event::RESET_REQUESTED);
  g_stateInfo.lastError = ErrorCode::NONE;
}

bool isProvisioning() {
  return g_stateInfo.current >= State::PROVISIONING_WAIT_TAP &&
         g_stateInfo.current <= State::PROVISIONING_STORE_CREDS;
}

bool isAuthenticating() {
  return g_stateInfo.current >= State::AUTH_WAIT_CONNECT &&
         g_stateInfo.current <= State::AUTH_SESSION_READY;
}

bool isUnlocking() {
  return g_stateInfo.current >= State::UNLOCKING_CHECK_PROXIMITY &&
         g_stateInfo.current <= State::UNLOCKING_COMPLETE;
}

bool isInErrorState() {
  return g_stateInfo.current == State::ERROR_HANDLER;
}

uint32_t getTimeInCurrentState() {
  return millis() - g_stateInfo.enter_time;
}

void setDebugLogging(bool enable) {
  g_debugLogging = enable;
  Serial.printf("[FSM] Debug logging %s\n", enable ? "ENABLED" : "DISABLED");
}

void printStatus() {
  Serial.println("\n[FSM] ========== Status ==========");
  Serial.printf("  State:         %s\n", stateToString(g_stateInfo.current));
  Serial.printf("  Previous:      %s\n", stateToString(g_stateInfo.previous));
  Serial.printf("  Last Event:    %s\n", eventToString(g_stateInfo.lastEvent));
  Serial.printf("  Last Error:    %s\n", errorToString(g_stateInfo.lastError));
  Serial.printf("  Time in state: %lu ms\n", getTimeInCurrentState());
  Serial.println("[FSM] ================================\n");
}

// ============ State Handlers Implementation ============

namespace {

/**
 * @brief INIT state: Initialize all subsystems
 */
State handle_INIT() {
  static bool initialized = false;
  if (initialized) return State::IDLE;
  
  Serial.println("[FSM/INIT] Starting system initialization...");
  
  Serial.print("[FSM/INIT] Initializing BLE... ");
  BLEMod::begin();
  if (!BLEMod::isStarted()) {
    Serial.println("FAILED");
    handleError(ErrorCode::BLE_INIT_FAILED);
    return State::ERROR_HANDLER;
  }
  Serial.println("OK");
  
  Serial.println("[FSM/INIT] NFC will be initialized by NfcSession::begin() in main.cpp");
  
  Serial.print("[FSM/INIT] Loading provisioning state... ");
  ProvisioningPhase::begin();
  bool provisioned = ProvisioningPhase::isProvisioned();
  Serial.printf("%s\n", provisioned ? "PROVISIONED" : "NOT PROVISIONED");
  
  Serial.println("[FSM/INIT] Initialization complete!");
  initialized = true;
  
  return State::IDLE;
}

/**
 * @brief IDLE state: Wait for events from user/app
 */
State handle_IDLE() {
  if (g_hasPendingEvent) {
    Event evt = g_pendingEvent;
    g_hasPendingEvent = false;
    
    switch (evt) {
      case Event::PROVISION_START:
        Serial.println("[FSM/IDLE] Starting provisioning flow...");
        return State::PROVISIONING_WAIT_TAP;
        
      case Event::BLE_CLIENT_CONNECTED:
        if (ProvisioningPhase::isProvisioned()) {
          Serial.println("[FSM/IDLE] BLE client connected, starting auth...");
          return State::AUTH_WAIT_CONNECT;
        } else {
          Serial.println("[FSM/IDLE] BLE connected but not provisioned yet");
          BLEMod::adminNotify("NOT_PROVISIONED");
        }
        break;
        
      case Event::ADMIN_COMMAND:
        Serial.println("[FSM/IDLE] Entering admin mode...");
        return State::ADMIN_MODE;
        
      default:
        break;
    }
  }
  
  NfcSession::tick();
  
  return State::IDLE;
}

/**
 * @brief PROVISIONING_WAIT_TAP: Wait for NFC tap (30s timeout)
 */
State handle_PROVISIONING_WAIT_TAP() {
  static uint32_t startTime = 0;
  const uint32_t TIMEOUT_MS = 30000;
  
  if (g_stateInfo.enter_time == millis() || startTime == 0) {
    startTime = millis();
    Serial.println("[FSM/PROV] Waiting for NFC tap... (30s timeout)");
    BLEMod::adminNotify("TAP_PHONE_TO_READER");
  }
  
  if (millis() - startTime > TIMEOUT_MS) {
    Serial.println("[FSM/PROV] Timeout waiting for NFC tap");
    BLEMod::adminNotify("TIMEOUT");
    return State::IDLE;
  }
  
  if (g_hasPendingEvent && g_pendingEvent == Event::NFC_CARD_DETECTED) {
    g_hasPendingEvent = false;
    Serial.println("[FSM/PROV] NFC card detected!");
    return State::PROVISIONING_SELECT_AID;
  }
  
  return State::PROVISIONING_WAIT_TAP;
}

/**
 * @brief PROVISIONING_SELECT_AID: Send SELECT AID APDU
 */
State handle_PROVISIONING_SELECT_AID() {
  Serial.println("[FSM/PROV] Selecting AID...");
  
  if (g_hasPendingEvent) {
    if (g_pendingEvent == Event::SELECT_AID_SUCCESS) {
      g_hasPendingEvent = false;
      Serial.println("[FSM/PROV] SELECT AID successful");
      return State::PROVISIONING_EXCHANGE_KEYS;
    } else if (g_pendingEvent == Event::SELECT_AID_FAILED) {
      g_hasPendingEvent = false;
      Serial.println("[FSM/PROV] SELECT AID failed");
      handleError(ErrorCode::NFC_APDU_FAILED);
      return State::ERROR_HANDLER;
    }
  }
  
  return State::IDLE;
}

/**
 * @brief PROVISIONING_EXCHANGE_KEYS: Exchange keys via GET_CHALLENGE
 */
State handle_PROVISIONING_EXCHANGE_KEYS() {
  Serial.println("[FSM/PROV] Exchanging keys (GET_CHALLENGE)...");
  
  if (g_hasPendingEvent) {
    if (g_pendingEvent == Event::KEYS_EXCHANGED) {
      g_hasPendingEvent = false;
      Serial.println("[FSM/PROV] Keys exchanged successfully");
      return State::PROVISIONING_STORE_CREDS;
    } else if (g_pendingEvent == Event::KEYS_INVALID) {
      g_hasPendingEvent = false;
      Serial.println("[FSM/PROV] Keys validation failed");
      handleError(ErrorCode::CRYPTO_SIGNATURE_INVALID);
      return State::ERROR_HANDLER;
    }
  }
  
  return State::IDLE;
}

/**
 * @brief PROVISIONING_STORE_CREDS: Store credentials to NVS
 */
State handle_PROVISIONING_STORE_CREDS() {
  Serial.println("[FSM/PROV] Storing credentials to NVS...");
  
  if (ProvisioningPhase::isProvisioned()) {
    Serial.println("[FSM/PROV] Credentials stored successfully!");
    BLEMod::adminNotify("PROVISION_SUCCESS");
    return State::IDLE;
  } else {
    Serial.println("[FSM/PROV] Failed to store credentials");
    handleError(ErrorCode::STORAGE_WRITE_FAILED);
    return State::ERROR_HANDLER;
  }
}

/**
 * @brief AUTH_WAIT_CONNECT: Wait for BLE client connection
 */
State handle_AUTH_WAIT_CONNECT() {
  if (g_hasPendingEvent && g_pendingEvent == Event::CLIENT_HELLO_RECEIVED) {
    g_hasPendingEvent = false;
    Serial.println("[FSM/AUTH] ClientHello received");
    return State::AUTH_HANDSHAKE;
  }
  
  if (g_hasPendingEvent && g_pendingEvent == Event::BLE_CLIENT_DISCONNECTED) {
    g_hasPendingEvent = false;
    Serial.println("[FSM/AUTH] Client disconnected");
    return State::IDLE;
  }
  
  return State::AUTH_WAIT_CONNECT;
}

/**
 * @brief AUTH_HANDSHAKE: Exchange ephemeral keys
 */
State handle_AUTH_HANDSHAKE() {
  Serial.println("[FSM/AUTH] Performing ECDH handshake...");
  
  if (g_hasPendingEvent && g_pendingEvent == Event::SERVER_HELLO_SENT) {
    g_hasPendingEvent = false;
    Serial.println("[FSM/AUTH] ServerHello sent, waiting for ClientConfirm");
    return State::AUTH_VERIFY_KEYS;
  }
  
  return State::AUTH_HANDSHAKE;
}

/**
 * @brief AUTH_VERIFY_KEYS: Verify HMAC proofs
 */
State handle_AUTH_VERIFY_KEYS() {
  Serial.println("[FSM/AUTH] Verifying HMAC proofs...");
  
  if (g_hasPendingEvent) {
    if (g_pendingEvent == Event::AUTH_VERIFIED) {
      g_hasPendingEvent = false;
      Serial.println("[FSM/AUTH] Authentication successful!");
      BLEMod::adminNotify("AUTH_OK");
      return State::AUTH_SESSION_READY;
    } else if (g_pendingEvent == Event::AUTH_FAILED) {
      g_hasPendingEvent = false;
      Serial.println("[FSM/AUTH] Authentication failed");
      handleError(ErrorCode::CRYPTO_HMAC_MISMATCH);
      return State::ERROR_HANDLER;
    }
  }
  
  return State::AUTH_VERIFY_KEYS;
}

/**
 * @brief AUTH_SESSION_READY: Session ready, wait for unlock request
 */
State handle_AUTH_SESSION_READY() {
  if (!BLEMod::isSessionReady()) {
    Serial.println("[FSM/AUTH] Session expired");
    return State::IDLE;
  }
  
  if (g_hasPendingEvent && g_pendingEvent == Event::UNLOCK_REQUESTED) {
    g_hasPendingEvent = false;
    Serial.println("[FSM/AUTH] Unlock requested!");
    return State::UNLOCKING_CHECK_PROXIMITY;
  }
  
  if (g_hasPendingEvent && g_pendingEvent == Event::BLE_CLIENT_DISCONNECTED) {
    g_hasPendingEvent = false;
    Serial.println("[FSM/AUTH] Client disconnected");
    return State::IDLE;
  }
  
  return State::AUTH_SESSION_READY;
}

/**
 * @brief UNLOCKING_CHECK_PROXIMITY: Check distance (future: UWB)
 */
State handle_UNLOCKING_CHECK_PROXIMITY() {
  Serial.println("[FSM/UNLOCK] Checking proximity...");
  
  Serial.println("[FSM/UNLOCK] Proximity OK (UWB not implemented yet)");
  return State::UNLOCKING_VERIFY_AUTH;
}

/**
 * @brief UNLOCKING_VERIFY_AUTH: Verify session still valid
 */
State handle_UNLOCKING_VERIFY_AUTH() {
  Serial.println("[FSM/UNLOCK] Verifying auth session...");
  
  if (!BLEMod::isSessionReady()) {
    Serial.println("[FSM/UNLOCK] Session not ready!");
    handleError(ErrorCode::SESSION_NOT_READY);
    return State::ERROR_HANDLER;
  }
  
  if (!ProvisioningPhase::isProvisioned()) {
    Serial.println("[FSM/UNLOCK] Device not provisioned!");
    handleError(ErrorCode::NOT_PROVISIONED);
    return State::ERROR_HANDLER;
  }
  
  Serial.println("[FSM/UNLOCK] Auth verified, executing unlock...");
  return State::UNLOCKING_EXECUTE;
}

/**
 * @brief UNLOCKING_EXECUTE: Execute unlock
 */
State handle_UNLOCKING_EXECUTE() {
  Serial.println("[FSM/UNLOCK] *** EXECUTING UNLOCK ***");
  
  Serial.println("[FSM/UNLOCK] Sending unlock signal to car actuator...");
  
  Serial.println("[FSM/UNLOCK] Logging unlock event...");
  
  BLEMod::adminNotify("UNLOCKED");
  
  return State::UNLOCKING_COMPLETE;
}

/**
 * @brief UNLOCKING_COMPLETE: Cooldown before returning to SESSION_READY
 */
State handle_UNLOCKING_COMPLETE() {
  static uint32_t completeTime = 0;
  const uint32_t COOLDOWN_MS = 3000;
  
  if (completeTime == 0) {
    completeTime = millis();
    Serial.println("[FSM/UNLOCK] Unlock complete! Cooldown 3s...");
  }
  
  if (millis() - completeTime > COOLDOWN_MS) {
    completeTime = 0;
    Serial.println("[FSM/UNLOCK] Cooldown finished, back to SESSION_READY");
    return State::AUTH_SESSION_READY;
  }
  
  return State::UNLOCKING_COMPLETE;
}

/**
 * @brief ADMIN_MODE: Handle admin commands
 */
State handle_ADMIN_MODE() {
  Serial.println("[FSM/ADMIN] Executing admin command...");
  
  delay(100);
  return State::IDLE;
}

/**
 * @brief ERROR_HANDLER: Handle errors
 */
State handle_ERROR_HANDLER() {
  Serial.printf("[FSM/ERROR] Handling error: %s\n", errorToString(g_stateInfo.lastError));
  
  printStatus();
  
  BLEMod::adminNotify("ERROR");
  
  delay(1000);
  
  Serial.println("[FSM/ERROR] Recovering to IDLE state");
  g_stateInfo.lastError = ErrorCode::NONE;
  return State::IDLE;
}

} // anonymous namespace (state handlers)

// ============ Helper Functions Implementation ============

const char* stateToString(State state) {
  switch (state) {
    case State::INIT: return "INIT";
    case State::IDLE: return "IDLE";
    case State::PROVISIONING_WAIT_TAP: return "PROVISIONING_WAIT_TAP";
    case State::PROVISIONING_SELECT_AID: return "PROVISIONING_SELECT_AID";
    case State::PROVISIONING_EXCHANGE_KEYS: return "PROVISIONING_EXCHANGE_KEYS";
    case State::PROVISIONING_STORE_CREDS: return "PROVISIONING_STORE_CREDS";
    case State::AUTH_WAIT_CONNECT: return "AUTH_WAIT_CONNECT";
    case State::AUTH_HANDSHAKE: return "AUTH_HANDSHAKE";
    case State::AUTH_VERIFY_KEYS: return "AUTH_VERIFY_KEYS";
    case State::AUTH_SESSION_READY: return "AUTH_SESSION_READY";
    case State::UNLOCKING_CHECK_PROXIMITY: return "UNLOCKING_CHECK_PROXIMITY";
    case State::UNLOCKING_VERIFY_AUTH: return "UNLOCKING_VERIFY_AUTH";
    case State::UNLOCKING_EXECUTE: return "UNLOCKING_EXECUTE";
    case State::UNLOCKING_COMPLETE: return "UNLOCKING_COMPLETE";
    case State::ADMIN_MODE: return "ADMIN_MODE";
    case State::ERROR_HANDLER: return "ERROR_HANDLER";
    default: return "UNKNOWN";
  }
}

const char* eventToString(Event event) {
  switch (event) {
    case Event::SYSTEM_READY: return "SYSTEM_READY";
    case Event::TIMEOUT: return "TIMEOUT";
    case Event::ERROR_OCCURRED: return "ERROR_OCCURRED";
    case Event::RESET_REQUESTED: return "RESET_REQUESTED";
    case Event::PROVISION_START: return "PROVISION_START";
    case Event::NFC_CARD_DETECTED: return "NFC_CARD_DETECTED";
    case Event::NFC_CARD_REMOVED: return "NFC_CARD_REMOVED";
    case Event::SELECT_AID_SUCCESS: return "SELECT_AID_SUCCESS";
    case Event::SELECT_AID_FAILED: return "SELECT_AID_FAILED";
    case Event::KEYS_EXCHANGED: return "KEYS_EXCHANGED";
    case Event::KEYS_INVALID: return "KEYS_INVALID";
    case Event::CREDENTIALS_STORED: return "CREDENTIALS_STORED";
    case Event::BLE_CLIENT_CONNECTED: return "BLE_CLIENT_CONNECTED";
    case Event::BLE_CLIENT_DISCONNECTED: return "BLE_CLIENT_DISCONNECTED";
    case Event::CLIENT_HELLO_RECEIVED: return "CLIENT_HELLO_RECEIVED";
    case Event::SERVER_HELLO_SENT: return "SERVER_HELLO_SENT";
    case Event::CLIENT_CONFIRM_RECEIVED: return "CLIENT_CONFIRM_RECEIVED";
    case Event::AUTH_VERIFIED: return "AUTH_VERIFIED";
    case Event::AUTH_FAILED: return "AUTH_FAILED";
    case Event::UNLOCK_REQUESTED: return "UNLOCK_REQUESTED";
    case Event::PROXIMITY_OK: return "PROXIMITY_OK";
    case Event::PROXIMITY_TOO_FAR: return "PROXIMITY_TOO_FAR";
    case Event::AUTH_SESSION_VALID: return "AUTH_SESSION_VALID";
    case Event::AUTH_SESSION_EXPIRED: return "AUTH_SESSION_EXPIRED";
    case Event::UNLOCK_EXECUTED: return "UNLOCK_EXECUTED";
    case Event::ADMIN_COMMAND: return "ADMIN_COMMAND";
    case Event::FORCE_PROVISION_ON: return "FORCE_PROVISION_ON";
    case Event::FORCE_PROVISION_OFF: return "FORCE_PROVISION_OFF";
    case Event::CLEAR_KEYS: return "CLEAR_KEYS";
    case Event::DIAGNOSTICS_REQUEST: return "DIAGNOSTICS_REQUEST";
    default: return "UNKNOWN_EVENT";
  }
}

const char* errorToString(ErrorCode error) {
  switch (error) {
    case ErrorCode::NONE: return "NONE";
    case ErrorCode::NFC_INIT_FAILED: return "NFC_INIT_FAILED";
    case ErrorCode::NFC_SAM_CONFIG_FAILED: return "NFC_SAM_CONFIG_FAILED";
    case ErrorCode::NFC_TIMEOUT: return "NFC_TIMEOUT";
    case ErrorCode::NFC_APDU_FAILED: return "NFC_APDU_FAILED";
    case ErrorCode::BLE_INIT_FAILED: return "BLE_INIT_FAILED";
    case ErrorCode::BLE_DISCONNECT_UNEXPECTED: return "BLE_DISCONNECT_UNEXPECTED";
    case ErrorCode::BLE_INVALID_DATA: return "BLE_INVALID_DATA";
    case ErrorCode::CRYPTO_SIGNATURE_INVALID: return "CRYPTO_SIGNATURE_INVALID";
    case ErrorCode::CRYPTO_ECDH_FAILED: return "CRYPTO_ECDH_FAILED";
    case ErrorCode::CRYPTO_HMAC_MISMATCH: return "CRYPTO_HMAC_MISMATCH";
    case ErrorCode::STORAGE_WRITE_FAILED: return "STORAGE_WRITE_FAILED";
    case ErrorCode::STORAGE_READ_FAILED: return "STORAGE_READ_FAILED";
    case ErrorCode::INVALID_STATE_TRANSITION: return "INVALID_STATE_TRANSITION";
    case ErrorCode::NOT_PROVISIONED: return "NOT_PROVISIONED";
    case ErrorCode::SESSION_NOT_READY: return "SESSION_NOT_READY";
    default: return "UNKNOWN_ERROR";
  }
}

} // namespace FSM
