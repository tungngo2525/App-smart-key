#!/usr/bin/env python3
"""Fix nfc_session.cpp - Replace waitForRemoval block with UID tracking"""

import re

file_path = r"c:\Users\tungn\Desktop\smartkey\iot\src\nfc_session.cpp"

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Find and replace the waitForRemoval block
# Pattern: from the closing brace of LED blink to the end of waitForRemoval loop
pattern = r'''(\}\s+)
#if DEBUG_LOGS\s+
\s+Serial\.println\("⏳ Waiting for card removal.*?\);
#endif\s+
.*?
\s+delay\(3000\);\s+
\}\s+
\}\s+
\}\s+
\}'''

replacement = r'''\1
            // Record this auth to block same card for cooldown period
            memcpy(lastAuthUID, currentUID, currentUIDLen);
            lastAuthUIDLen = currentUIDLen;
            lastAuthCompletedMs = millis();
            
#if DEBUG_LOGS
            Serial.printf("✅ Auth complete! Card blocked for %ums\\n\\n", AUTH_COOLDOWN_MS);
#endif
        }'''

# Try simple line-based approach instead
lines = content.splitlines(keepends=True)

# Find line with "Waiting for card removal"
start_idx = None
for i, line in enumerate(lines):
    if "Waiting for card removal" in line:
        start_idx = i - 2  # Go back to include #if DEBUG_LOGS
        break

if start_idx:
    # Find end of block (line with "Clean up PN532 state")
    end_idx = None
    for i in range(start_idx, len(lines)):
        if "// Clean up PN532 state for next detection cycle" in lines[i]:
            end_idx = i
            break
    
    if end_idx:
        # Replace the block
        new_block = [
            "            \n",
            "            // Record this auth to block same card for cooldown period\n",
            "            memcpy(lastAuthUID, currentUID, currentUIDLen);\n",
            "            lastAuthUIDLen = currentUIDLen;\n",
            "            lastAuthCompletedMs = millis();\n",
            "            \n",
            "#if DEBUG_LOGS\n",
            '            Serial.printf("✅ Auth complete! Card blocked for %ums\\n\\n", AUTH_COOLDOWN_MS);\n',
            "#endif\n",
            "        }\n",
            "\n",
        ]
        
        new_lines = lines[:start_idx] + new_block + lines[end_idx:]
        
        with open(file_path, 'w', encoding='utf-8', newline='\n') as f:
            f.writelines(new_lines)
        
        print(f"✅ Fixed! Replaced lines {start_idx+1} to {end_idx}")
        print(f"   Removed {end_idx - start_idx} lines")
        print(f"   Added {len(new_block)} lines")
    else:
        print("❌ Could not find end marker")
else:
    print("❌ Could not find start marker")
