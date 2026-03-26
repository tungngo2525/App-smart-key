# 🚗 PHASE A — Provisioning Overview

### 🧩 Goal

Phase A (Provisioning) is the **secure enrollment step** — it’s what happens when you first pair a *new phone* with your *vehicle ECU (ESP32 + PN532)*.

After this phase:

* The ESP32 “knows” and stores the phone’s **public key**, **key ID**, and optionally **certificate chain**.
* The phone becomes a recognized, authorized key.
* Later phases (Phase B “Unlock” and Phase C “UWB proximity”) depend on this trust relationship.

---

# ⚙️ High-Level Flow

| Step | Device        | Action                                              | Purpose                         |
| ---- | ------------- | --------------------------------------------------- | ------------------------------- |
| 1️⃣  | ESP32 + PN532 | Generate / load its own ECC keypair                 | Device identity                 |
| 2️⃣  | ESP32         | Wait for admin to request provisioning              | Start provisioning mode         |
| 3️⃣  | PN532         | Go into NFC target polling                          | Wait for phone tap              |
| 4️⃣  | Phone (HCE)   | Respond to SELECT AID APDU                          | Identify correct app            |
| 5️⃣  | ESP32         | Send `GET_CHALLENGE` APDU with `{vehicleId, nonce}` | Ask phone to prove its identity |
| 6️⃣  | Phone         | Sign that nonce with its private key                | Prove it owns the key           |
| 7️⃣  | Phone         | Send back `{keyId, pubKey, cert}`                   | Give ECU its credentials        |
| 8️⃣  | ESP32         | Validate and save those credentials in NVS          | Remember authorized phone       |

---

# 🧠 Step-by-Step Deep Dive

### 🟢 1. ESP32 loads or creates its own ECC keypair

```cpp
ensureKeypair()
```

* Checks if an ECC key already exists in NVS (Preferences).
* If not, it creates one using mbedTLS + `SECP256R1`.
* Saves it back to NVS in PEM format.

💡 *This keypair identifies the car — you can think of it as the car’s own “certificate”.*

---

### 🟢 2. Admin triggers provisioning

```cpp
Provisioning::runNfcProvisioning();
```

* Puts PN532 into *provision mode* → polling suspended to avoid noise.
* Uses a special AID:

  ```cpp
  static const uint8_t HCE_AID[] = {0xF0, 0x01, 0x02, 0x03, 0x04, 0x05};
  ```
* That AID must match your Android HCE app.

Console shows:

```
[Prov] Admin requested provisioning. Bring phone close...
```

---

### 🟢 3. Phone tap detected (ISO14443-A)

PN532 detects the NFC field:

```
[Prov][NFC] Waiting for phone tap...
```

→ When the phone is in HCE mode, Android behaves as a **virtual card** using ISO-DEP.

---

### 🟢 4. SELECT AID APDU

ESP32 sends this APDU:

```
00 A4 04 00 06 F00102030405 00
```

Phone receives it, Android finds a matching HCE service registered for `F00102030405`, wakes up the app, and calls:

```java
processCommandApdu()
```

If your app replies with `90 00`, the ESP32 knows it’s the right phone:

```
[Prov][NFC] <<< Response ok=true
```

---

### 🟢 5. GET_CHALLENGE APDU

ESP32 builds another command:

```cpp
00 CA 00 00 [Lc] [vehicleId || nonce]
```

* `vehicleId` = first 8 bytes of SHA-256 fingerprint of the car’s public key.
* `nonce` = 16 random bytes from DRBG.

Purpose → ask the phone to sign this random challenge.

---

### 🟢 6. Phone signs the challenge

In your Android app:

1. Gets the APDU.
2. Extracts `vehicleId` + `nonce`.
3. Retrieves its **private key** from Android Keystore.
4. Signs `nonce || vehicleId`.
5. Builds a response:

   ```
   [keyIdLen][keyId][pubKey(65)][certLen(2)][cert]90 00
   ```

---

### 🟢 7. ESP32 verifies and stores credentials

ESP32 parses the response:

```cpp
pd.keyId
pd.pubKey65
pd.cert
```

Then:

* Checks that `pubKey65[0] == 0x04` → uncompressed EC key.
* Saves them to NVS:

  * `prov/phone_pub_raw`
  * `prov/key_id`
  * `prov/cert_chain`

Example serial output:

```
[Prov] Stored phone public key (raw 65 bytes) in NVS.
[Prov] Stored phone keyId in NVS.
[Prov] Provisioned keyId='user-phone-1'
```

---

### 🟢 8. Provisioning Success

Once saved, provisioning exits:

```
[Prov] Provisioning success. Polling resumed.
```

Now the ECU recognizes this phone as an authorized key.

---

# 🛡️  Error Handling

Phase A has detailed error management:

| Stage            | Possible Error                      | System Reaction              |
| ---------------- | ----------------------------------- | ---------------------------- |
| Keypair missing  | `[Prov] Failed to init ECC keypair` | Retry or reset NVS           |
| No NFC or phone  | `"no phone"`                        | Wait and re-poll             |
| SELECT AID fails | `"SELECT AID failed"`               | Ignore tag, continue polling |
| Wrong key format | `"Invalid phone public key format"` | Reset for retry              |
| Duplicate keyId  | `"Duplicate keyId detected"`        | Abort provisioning           |
| Commit failed    | `"Commit to flash failed"`          | Rollback and retry           |

---

# 🧩 Internal Storage Map (ESP32 NVS)

| NVS Key              | Description                      |
| -------------------- | -------------------------------- |
| `prov/ec_priv`       | ECC private key (PEM)            |
| `prov/cert`          | Device certificate (optional)    |
| `prov/phone_pub_raw` | 65-byte phone public key         |
| `prov/key_id`        | Phone’s identifier               |
| `prov/tags`          | Authorized physical tag list     |
| `prov/cert_chain`    | Optional phone certificate chain |

---

## 📶 Using the BLE Admin Service (to drive provisioning)

When BLE is enabled (the firmware calls `BLEMod::begin()` in `setup()`), the ECU exposes an Admin GATT service that lets you query status and control provisioning behavior without serial.

### Service and characteristics

- Service (Admin): `9a9b9c9d-0000-4000-8000-9a9b9c9d0000`
  - Admin Mode (RW, 1 byte): `9a9b9c9d-0001-4000-8000-9a9b9c9d0001`
  - Admin Command (W): `9a9b9c9d-0002-4000-8000-9a9b9c9d0002`
  - Admin Info (R/Notify): `9a9b9c9d-0003-4000-8000-9a9b9c9d0003`
  - Phone Key Upload (W): `9a9b9c9d-0004-4000-8000-9a9b9c9d0004` (disabled in Phase A – use NFC provisioning)

Enable notifications on Admin Info so you can see responses to your commands.

### Commands (write to Admin Command)

The characteristic accepts either a single raw byte or an ASCII hex string (e.g., `"01"`, `"0x01"`, `"33"`). The recognized commands are:

- 0x01 — Clear phone provisioning only (keeps device keypair).
  - Response: `CLEARED_PROVISIONED`
- 0x02 — Clear everything including device keypair.
  - Response: `CLEARED_ALL`
- 0x10 — Legacy tag listing.
  - Response: `NOT_SUPPORTED`
- 0x20 — Request NFC provisioning (informational; NFC loop is always active).
  - Response: `NFC_TAP_TO_PROVISION`
- 0x30 — Set persistent force provisioning ON (future taps overwrite stored phone data).
  - Response: `FORCE_PERSIST_ON`
- 0x31 — Set persistent force provisioning OFF.
  - Response: `FORCE_PERSIST_OFF`
- 0x32 — Arm one‑shot force (only next successful provisioning overwrites, then auto-clears).
  - Response: `FORCE_ONESHOT_ARMED`
- 0x33 — Status query.
  - Response: `STATUS P=YES/NO PF=ON/OFF OS=ARMED/NO`
- 0x34 — Validate that stored cert matches stored pub key (if available).
  - Response: `CERT_MATCH` or `CERT_MISMATCH`
- 0x35 — Check if phone public key is present.
  - Response: `PUB_PRESENT` or `PUB_NONE`
- 0x36 — Get keyId summary (truncated if long).
  - Response: `KEYID:<value>` or `KEYID_NONE`

Any other values will respond with `UNSUPPORTED`.

### Typical flows

1) Check state before provisioning

- Write `"33"` (or raw 0x33) to Admin Command.
- Read/observe Admin Info notification (e.g., `STATUS P=NO PF=OFF OS=NO`).

2) Ask app/user to tap phone for provisioning

- Write `"20"` to Admin Command.
- App shows prompt; user taps phone at reader. The NFC Phase A flow handles the rest.

3) Force re‑provisioning for a different phone

- Write `"32"` to arm one‑shot force (ideal for a single overwrite), or `"30"` to enable persistent force.
- Tap the new phone; provisioning data will be overwritten if the signature verification succeeds.
- Query `"33"` again to confirm flags (one‑shot clears automatically after success).

### Troubleshooting tips

- If you don’t see Admin Info notifications, ensure notifications are enabled in your BLE client.
- The device advertises as `ESP-Smart-Car-ECU` and exposes both Admin and Auth/Echo services.
- Serial prints include lines like `"[BLE-Admin] Cmd write: ..."` for command debugging.
