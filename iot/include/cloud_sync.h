#pragma once

namespace CloudSync {
  void begin();
  void tick();
  void requestSyncNow();
  void printStatus();
  bool isConfigured();
}
