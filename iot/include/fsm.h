#pragma once
#include "fsm_states.h"

namespace FSM {

// Initialize FSM and all subsystems
void begin();

// Main FSM loop - call this in loop()
void tick();

// Trigger an event externally (from BLE/NFC callbacks)
void triggerEvent(Event event);

// Get current state
State getCurrentState();

// Get detailed state info
StateInfo getStateInfo();

// Force state transition (for testing)
bool forceState(State newState, bool force = false);

// Reset FSM to IDLE
void reset(bool clearSession = false);

// Check if in specific state groups
bool isProvisioning();
bool isAuthenticating();
bool isUnlocking();
bool isInErrorState();

// Get time spent in current state (ms)
uint32_t getTimeInCurrentState();

// Enable/disable debug logging
void setDebugLogging(bool enable);

// Print FSM status to Serial
void printStatus();

} // namespace FSM

