# DJI RC Pro Installation & Operation Guide

This guide details how to build, install, configure, verify, and operate the **drone-mqtt-control** application on a **DJI RC Pro** connected to a physical **DJI Mavic 3 Enterprise (Mavic 3E)**.

---

## 1. Hardware & OS Compatibility

### Android Compatibility & minSdk
- **DJI RC Pro** runs an embedded customized build of **Android 10 (API Level 29)**.
- **DJI Smart Controller Enterprise** runs Android 7.1.2 (API 25) / Android 9.
- **`minSdk`**: Set to **`24`** (Android 7.0 Nougat) to ensure complete binary compatibility with DJI RC Pro (API 29), RC Plus (API 29), and older enterprise remotes, while supporting Jetpack Compose and coroutines.
- **`targetSdk`**: Set to **`34`** (Android 14) for modern Android toolchain compliance.
- **Supported Aircraft**: DJI Mavic 3 Enterprise Series (Mavic 3E / Mavic 3T / Mavic 3M), Matrice 30 / 30T, Matrice 300 / 350 RTK (via MSDK V5 Aircraft).

---

## 2. Generating a DJI Developer App Key

The DJI Mobile SDK V5 requires an App Key bound to the exact application package name.

1. Navigate to the [DJI Developer Portal](https://developer.dji.com/user/apps/#all) and log into your DJI account.
2. Click **Create App**.
3. Select the following settings:
   - **App Type**: `Mobile SDK`
   - **Platform**: `Android`
   - **App Name**: `DJI Drone MQTT Control` (or your preferred name)
   - **Package Name**: **`com.dji.dronecontrol.rcpro`** *(Must match `applicationId` in `app-rcpro/build.gradle.kts` exactly!)*
   - **Category**: `Enterprise / Other`
4. Click **Generate** and copy the generated **App Key** (a 24-character alphanumeric string).

> **Note on Debug Variant Package Name**: The Gradle build does NOT append `.debug` to the `applicationId` (i.e., `applicationIdSuffix` is disabled). This ensures the debug APK uses `com.dji.dronecontrol.rcpro` directly, avoiding the need to register duplicate keys on the DJI portal.

---

## 3. Local Configuration & Building

### Step 3.1: Configure DJI App Key
In the root directory of this repository, open or create `local.properties` and add:
```properties
DJI_APP_KEY=your_dji_app_key_here
AGORA_APP_ID=your_agora_app_id_here
STREAM_TOKEN_URL=https://tronico.kujuhk.com/live/token
STREAM_TOKEN_SECRET=your_bearer_secret_here
```
*(Keys also support Gradle property `-P...` and environment variables of the same name.)*

`STREAM_TOKEN_URL` / `STREAM_TOKEN_SECRET` let the app fetch Agora publisher tokens from your viewer site (see `docs/MQTT_API.md`). Never put the Agora App Certificate in the app or `local.properties`.

The build script automatically reads `local.properties` first, then Gradle properties, then environment variables.

> **Security Note**: Never commit `local.properties` or real App Keys to version control. `local.properties` is already included in `.gitignore`.

### Step 3.2: Build the Debug APK
```bash
# Verify unit tests
./gradlew :core:test

# Build the RC Pro APK
./gradlew :app-rcpro:assembleDebug
```
The output APK is generated at:
```
app-rcpro/build/outputs/apk/debug/app-rcpro-debug.apk
```

---

## 4. Preparing DJI RC Pro for Sideloading

1. Power on the **DJI RC Pro**.
2. Swipe down from the top edge of the screen twice to reveal the Quick Settings panel, then tap the **Settings (gear icon)**.
3. Scroll down and navigate to **System** → **About device**.
4. Locate the **Build number** entry and tap it rapidly **7 times** until you see the toast message: *"You are now a developer!"*.
5. Go back to **System** → **Developer options**.
6. Toggle **USB debugging** to **ON**.
7. Connect the DJI RC Pro to your computer using a high-quality USB-C data cable (plugged into the bottom USB-C port of the RC Pro).
8. On the RC Pro screen prompt, check **"Always allow from this computer"** and tap **Allow**.

---

## 5. Installing the APK via ADB

Verify the RC Pro is recognized:
```bash
adb devices
# Output:
# List of devices attached
# 1581F4HKB21XXXXXXXX    device
```

Install the APK:
```bash
adb install -r app-rcpro/build/outputs/apk/debug/app-rcpro-debug.apk
```

If you receive a prompt on the RC Pro screen asking to grant permissions (Location, Storage, Network), tap **Allow**.

---

## 6. Network Configuration & Initial SDK Registration

1. **Internet Access for First Launch**:
   - Connect the RC Pro to a Wi-Fi network with internet access.
   - The DJI SDK requires an active internet connection on the first app launch to authenticate the `DJI_APP_KEY` against DJI's license servers (`INITIALIZE_COMPLETE` event). Once registered, the license is cached on the device.
2. **Local Broker Network Connectivity**:
   - Connect the RC Pro to the same Wi-Fi network or mobile hotspot as your MQTT broker (e.g., Mosquitto) and RTMP ingest server (e.g., MediaMTX / SRS).
   - Ensure TCP ports `1883` (MQTT) and `1935` (RTMP) are reachable.

---

## 7. Verifying Aircraft Hardware Connection & Telemetry

1. Power on your **DJI Mavic 3E** aircraft.
2. Wait for the Mavic 3E to establish its O3 Enterprise radio link with the RC Pro (the status LEDs on the controller and aircraft turn solid green).
3. Open the **DJI Drone MQTT** app from the RC Pro launcher.
4. Verify the **DJI App Key**:
   - The **Aircraft Telemetry** card (and **Settings** tab) displays the currently configured DJI App Key.
   - It is masked by default (`ABCD••••••••WXYZ`). Tap the key or the eye icon to reveal the full key in plain text.
   - If `DJI_APP_KEY` was not configured or is set to placeholder, a prominent warning (`⚠️ App Key not set`) is displayed.
5. Check the **Top App Bar** badges on the screen:
   - **SDK Reg Status**: `SDK Reg OK` (Green) when App Key was registered with DJI servers; `SDK Reg Failed` (Red) or `SDK Reg Pending` (Orange) if registration failed or Wi-Fi is offline.
   - **Aircraft Status**:
     - `Drone Offline` (Red): Aircraft is not powered on or unlinked.
     - `Mavic 3E (Telemetry Sync...)` (Orange): Physical link is established, waiting for first battery / GPS telemetry update.
     - `Mavic 3E (Ready)` (Green): Aircraft is connected AND live battery / GPS telemetry has synced.
   - **MQTT Status**: `MQTT Online` (Green) when broker is connected.
6. Check the **Aircraft Telemetry** card on the dashboard:
   - **App Build**: `v1.0.9 (10)`
   - **SDK Registered**: `YES`
   - **Hardware Link**: `CONNECTED`
   - **Telemetry Sync**: `LIVE READY`
   - **Model**: `Mavic 3E`
   - **Battery**: Real-time percentage (e.g. `92%`)
   - **Flight Mode**: Current mode reported by KeyManager (e.g., `STANDBY`, `MANUAL`, `P-GPS`)
   - **Satellites**: Number of GPS satellites locked (e.g., `18 Sats`)
   - **In Air**: `NO (Grounded)`

---

## 8. App Execution Logs vs MQTT Traffic Logs

The application provides two dedicated logging consoles in the left navigation rail:

1. **`App Logs` Tab (Execution Logs)**:
   - Captures low-level Android app and DJI Mobile SDK V5 execution logs in an in-memory ring buffer (up to 1000 lines).
   - Tracks: SDK init & registration results (with error codes), product connection/disconnection events, KeyManager telemetry listener & polling updates, VirtualStick enable & velocity timeout watchdogs, camera mode switching, and RTMP streaming events.
   - Includes timestamp, log level badge (`[I]`, `[W]`, `[E]`), component tag (`[DjiConn]`, `[DjiFlightAdapter]`, `[Router]`, `[MQTT]`), level filtering buttons (`ALL`, `INFO`, `WARN`, `ERROR`), search filter query, and a clear button.
2. **`MQTT Log` Tab (Message Console)**:
   - Captures incoming JSON command envelopes, outgoing execution acks, and periodic status telemetry payloads broadcasted over MQTT.

---

## 8. Troubleshooting: Zeroed / Empty Telemetry or Registration Errors

If the app shows `Drone Offline`, `Telemetry Sync...`, or telemetry fields (battery, satellites, lat/lon) are zeroed:

### Issue 1: "SDK Reg Failed" or App Key Registration Error
- **Symptom**: Top badge shows `SDK Reg Failed` and Console shows `DJI SDK Registration FAILED: ...`.
- **Causes**:
  1. **Package Name Mismatch**: The package name registered in the DJI Developer Portal MUST match `com.dji.dronecontrol.rcpro` exactly (case-sensitive, no `.debug` suffix).
  2. **Placeholder Key Used**: If `local.properties` contains `YOUR_DJI_APP_KEY_HERE`, replace it with your real 24-character App Key from developer.dji.com.
  3. **No Internet on First Launch**: The RC Pro must be connected to Wi-Fi with internet access during the first launch to validate the App Key with DJI license servers. Once registered, the license is cached offline.

### Issue 2: Hardware Connected but Battery/GPS shows 0 or Telemetry Syncing
- **Symptom**: Aircraft is linked (green RC LED) but app stays on `Telemetry Sync...` with `battery_pct: 0` or `satellites: 0`.
- **Causes & Solution**:
  1. The app now registers both active `KeyManager.listen` listeners and periodic `KeyManager.getValue` polling with dual `ComponentIndexType` support (handling single-battery Mavic 3E and dual-battery Matrice series).
  2. If the drone was booted after the app launched, tap the **Console** tab to verify that `Product connection state changed: connected=true` fired. The app will immediately trigger `fetchTelemetryOnce()`.
  3. Ensure the RC Pro is switched to **Normal (N)** or **Position (P)** flight mode on the physical mode switch.

---

## 9. Pilot-in-the-Loop Pre-Flight Safety Protocol

⚠️ **MANDATORY SAFETY CHECKLIST BEFORE ISSUING MQTT FLIGHT COMMANDS**:

| Checkpoint | Requirement |
|---|---|
| **Airspace & Environment** | Open outdoor area away from obstacles, people, and overhead wires. |
| **GPS Lock** | Ensure at least **10 satellites** are acquired and Home Point is recorded. |
| **Pilot Stance** | The Remote Pilot in Command (RPIC) must physically hold the RC Pro with thumbs on the control sticks at all times. |
| **Physical Stick Override** | Moving either physical control stick on the RC Pro **instantly revokes Virtual Stick control** and returns full manual control to the pilot. |
| **Visual Line of Sight (VLOS)** | The aircraft must remain within direct visual line of sight at all times. |
| **Auto-Hover Watchdog** | The bridge enforces a 500ms–1000ms velocity timeout; if the upstream MQTT command stream drops, the drone automatically stops and hovers in place. |

---

## 10. MQTT Smoke Testing (Step-by-Step)

### Step 10.1: Connect to MQTT Broker
In the **MQTT** tab of the app:
- Set **Host**: Broker IP (e.g., `192.168.1.50`)
- Set **Port**: `1883`
- Set **Aircraft ID**: `m3e-01`
- Tap **Connect MQTT Broker**. Verify the badge shows **`MQTT Online`**.

### Step 10.2: Smoke Test 1 - Ping & Telemetry
Publish a ping command:
```bash
mosquitto_pub -h 192.168.1.50 -t "dji/cmd/m3e-01" -m '{
  "schema_version": "1.0",
  "msg_id": "test-ping-01",
  "ts": "2026-09-29T10:00:00Z",
  "aircraft_id": "m3e-01",
  "cmd": "mqtt.ping",
  "params": {}
}'
```
Subscribe to responses:
```bash
mosquitto_sub -h 192.168.1.50 -t "dji/#" -v
```
Expected output on `dji/ack/m3e-01`:
```json
{"msg_id":"test-ping-01","aircraft_id":"m3e-01","cmd":"mqtt.ping","ok":true,"code":0,"msg":"Success","ts":"2026-09-29T10:00:00Z"}
```
And on `dji/status/m3e-01`:
```json
{"schema_version":"1.0","msg_id":"...","aircraft_id":"m3e-01","type":"telemetry.ping","data":{"connected":true,"battery_percent":92,"flight_mode":"STANDBY","is_flying":false,"altitude_m":0.0,"satellites":18,"latitude":...,"longitude":...}}
```

### Step 10.3: Smoke Test 2 - Camera Photo & Video
```bash
# Shoot a still photo
mosquitto_pub -h 192.168.1.50 -t "dji/cmd/m3e-01" -m '{
  "schema_version": "1.0",
  "msg_id": "photo-01",
  "ts": "2026-09-29T10:01:00Z",
  "aircraft_id": "m3e-01",
  "cmd": "camera.photo",
  "params": { "size": "48mp", "format": "jpeg" }
}'

# Start video recording
mosquitto_pub -h 192.168.1.50 -t "dji/cmd/m3e-01" -m '{
  "schema_version": "1.0",
  "msg_id": "rec-01",
  "ts": "2026-09-29T10:02:00Z",
  "aircraft_id": "m3e-01",
  "cmd": "camera.record_start",
  "params": {}
}'
```

### Step 10.4: Smoke Test 3 - Takeoff (After Safety Verification)
Ensure propellers are attached, clear radius of 5 meters around aircraft:
```bash
mosquitto_pub -h 192.168.1.50 -t "dji/cmd/m3e-01" -m '{
  "schema_version": "1.0",
  "msg_id": "takeoff-01",
  "ts": "2026-09-29T10:03:00Z",
  "aircraft_id": "m3e-01",
  "cmd": "flight.takeoff",
  "params": { "target_height_m": 1.5 }
}'
```
The Mavic 3E will start motors, ascend to 1.5m, and enter stationary hover.

### Step 10.5: Smoke Test 4 - Virtual Stick Micro-Move
```bash
# Move forward at 0.5 m/s for 1000 ms (1 second)
mosquitto_pub -h 192.168.1.50 -t "dji/cmd/m3e-01" -m '{
  "schema_version": "1.0",
  "msg_id": "move-01",
  "ts": "2026-09-29T10:04:00Z",
  "aircraft_id": "m3e-01",
  "cmd": "flight.move",
  "params": {
    "mode": "velocity",
    "vx": 0.5,
    "vy": 0.0,
    "vz": 0.0,
    "yaw_rate": 0.0,
    "duration_ms": 1000
  }
}'
```

### Step 10.6: Smoke Test 5 - Landing
```bash
mosquitto_pub -h 192.168.1.50 -t "dji/cmd/m3e-01" -m '{
  "schema_version": "1.0",
  "msg_id": "land-01",
  "ts": "2026-09-29T10:05:00Z",
  "aircraft_id": "m3e-01",
  "cmd": "flight.land",
  "params": {}
}'
```
The aircraft will auto-land at its current position and stop motors upon touchdown.
