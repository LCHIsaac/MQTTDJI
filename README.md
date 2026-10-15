# DJI Drone MQTT Control App

A multi-module Android application implementing an MQTT-based bidirectional control bridge for DJI drones via the official **DJI Mobile SDK V5 (MSDK V5)**.

- **Phase 1 (Primary)**: APK for **DJI RC Pro** controlling a physical **Mavic 3 Enterprise (Mavic 3E)**.
- **Phase 2 (Transferable)**: Phone application for **DJI Mini 4 Pro + RC-N2/N3** using the exact same MQTT protocol, domain ports, and command router.

---

## 1. Project Architecture

This project is built with clean architecture and strict boundary separation:

```
drone-mqtt-control/
  ├── :core                 // Pure Kotlin module: MQTT client, CommandRouter, JSON envelope models, domain ports (NO DJI imports)
  ├── :dji-adapter          // Android library: Implements domain ports using real DJI MSDK V5 KeyManager / LiveStreamManager / VirtualStickManager
  ├── :app-ui               // Shared Jetpack Compose dashboard, Connection checklist, settings, MQTT/app logs, smoke-test flight controls
  ├── :app-persistence      // DataStore + encrypted MQTT password persistence (shared by both apps)
  ├── :app-rcpro            // RC Pro host app (applicationId: com.dji.dronecontrol.rcpro, key: DJI_APP_KEY)
  ├── :app-phone            // Phone host app for Mini 4 Pro + RC-N2 USB (applicationId: com.dji.dronecontrol.phone, key: DJI_APP_KEY_PHONE)
  └── docs/
      ├── MQTT_API.md           // Complete MQTT 1.0 JSON API specifications & schema
      ├── RC_PRO_INSTALL.md     // Sideloading, ADB, permissions & testing on DJI RC Pro
      ├── PHONE_INSTALL.md      // Phone + RC-N2 install, DJI_APP_KEY_PHONE, USB accessory
      └── MINI4PRO_TRANSFER.md  // Architecture transferability & Mini 4 Pro specifics
```

### Module Boundary Guarantees
- `:core` contains **zero DJI SDK imports**. All flight, camera, stream, and telemetry capabilities are exposed as pure Kotlin domain interfaces (`FlightControllerPort`, `CameraPort`, `StreamPort`, `AircraftConnectionPort`).
- `:dji-adapter` implements these domain ports with real DJI Mobile SDK V5 Aircraft pack APIs:
  - **`KeyManager`**: Flight control actions (takeoff, land, RTH, emergency stop), camera modes/recording/shooting, battery and product connection state.
  - **`VirtualStickManager`**: Velocity-based flight movements with timeout safety watchdog and manual stick override detection.
  - **`LiveStreamManager`**: RTMP live video streaming pipeline (`LiveStreamSettings`, `RtmpSettings`).
  - **`SDKManager`**: Lifecycle registration and device initialization.
- Both app flavors (`:app-rcpro` and `:app-phone`) reuse `:core` and `:dji-adapter` cleanly.
- Includes `MockAircraft` and unit tests in `:core` to verify all MQTT commands without drone hardware or SDK keys.

---

## 2. Supported MQTT Commands (Version 1.0)

| Command | Summary | Key Parameters |
|---|---|---|
| `mqtt.ping` | Health check & instantaneous telemetry ping | `{}` |
| `flight.takeoff` | Auto-takeoff to specified target height | `{"target_height_m": 1.5}` |
| `flight.land` | Auto-landing at current position | `{}` |
| `flight.rth` / `flight.go_home` | Return-to-home | `{}` |
| `flight.hover` | Zeroes virtual stick velocity and holds position | `{}` |
| `flight.move` | Virtual Stick advanced velocity control | `{"mode": "velocity", "vx": 1.0, "vy": 0.0, "vz": 0.0, "yaw_rate": 0.0, "duration_ms": 1000}` |
| `flight.rotate` | Pure yaw angular rotation with auto-hover | `{"yaw_rate": 20.0, "duration_ms": 1000}` |
| `flight.emergency_stop` | Immediate virtual stick halt & emergency stop | `{}` |
| `camera.photo` | Shoot still; pull JPEG, optional local save + upload link | `{"size": "12mp", "format": "jpeg", "deliver": "both"}` |
| `camera.snapshot` | One FPV video frame → JPEG on handset (no SD); same upload as photo | `{"deliver": "both", "quality": 85}` |
| `camera.record_start` | Switch to video mode & start recording | `{}` |
| `camera.record_stop` | Stop active video recording | `{}` |
| `stream.start` | Start live video streaming to RTMP server | `{"protocol": "rtmp", "url": "rtmp://...", "quality": "HD"}` |
| `stream.stop` | Stop live video stream | `{}` |
| `config.get` | Query drone connection status & MQTT topics | `{}` |

For full schema details, see [docs/MQTT_API.md](docs/MQTT_API.md).

---

## 3. Safety Notice & Pilot-in-the-Loop Rules

⚠️ **CRITICAL FLIGHT SAFETY PROTOCOL**:
1. **Pilot Authority & Manual Stick Priority**: Moving the physical control sticks on the RC Pro or RC-N2 will **always immediately override** any virtual stick velocity commands issued via MQTT.
2. **Visual Line of Sight (VLOS)**: Real flight operations must always maintain uninterrupted Visual Line of Sight and have a designated pilot in the loop with hands on the controller.
3. **Safety Timeout Watchdog**: Virtual stick velocity commands automatically timeout after the specified `duration_ms` (default 500ms–1000ms), safely resetting velocity to zero and returning the aircraft to a stationary hover in P-GPS mode.
4. **No Heavy Payload Over MQTT**: Photographs and full video streams are never transmitted over MQTT. High-definition live video is ingested via RTMP (`stream.start`); photos and video files remain securely recorded on the aircraft's high-speed internal/SD card storage.

---

## 4. How to Build & Run

### 4.1 Prerequisites
- JDK 17 or JDK 21
- Android SDK (API 24 to 34)

### 4.2 Configuring DJI App Keys

Register **one app per APK** on the [DJI Developer Portal](https://developer.dji.com):

| Module | Package name | `local.properties` key |
|--------|----------------|-------------------------|
| `:app-rcpro` | `com.dji.dronecontrol.rcpro` | `DJI_APP_KEY` |
| `:app-phone` | `com.dji.dronecontrol.phone` | `DJI_APP_KEY_PHONE` |

Example `local.properties` (never commit):

```properties
DJI_APP_KEY=your_rcpro_key_for_mavic3e
DJI_APP_KEY_PHONE=your_phone_key_for_mini4pro
AGORA_APP_ID=your_agora_app_id_here
# Agora publisher tokens are fetched from your viewer site (never put App Certificate in the app)
STREAM_TOKEN_URL=https://tronico.kujuhk.com/live/token
STREAM_TOKEN_SECRET=your_bearer_secret_for_token_endpoint
PHOTO_UPLOAD_URL=https://tronico.kujuhk.com/live/photo
VIDEO_UPLOAD_URL=https://tronico.kujuhk.com/live/video
```

`STREAM_TOKEN_URL`, `STREAM_TOKEN_SECRET`, `PHOTO_UPLOAD_URL`, and `VIDEO_UPLOAD_URL` are optional in `local.properties` (blank URLs use the tronico defaults above). Token/photo upload auth reuses `STREAM_TOKEN_SECRET` as `Authorization: Bearer …` for `POST /live/photo` (raw JPEG, max 16 MiB). URLs and the secret can be overridden in MQTT settings; the secret is stored encrypted. The Agora **App Certificate** must never be embedded in the APK, BuildConfig, or logs.

Each key also supports Gradle property / `-P` / environment variable of the same name.

**MSDK version:** `djiMsdkVersion=5.14.0` in `gradle.properties` (Mini 4 Pro requires newer than 5.9.0; see [docs/MINI4PRO_TRANSFER.md](docs/MINI4PRO_TRANSFER.md)).

### 4.3 Building the Project
```bash
./gradlew test
./gradlew :app-rcpro:assembleDebug
./gradlew :app-phone:assembleDebug
```

| APK | Output path |
|-----|-------------|
| RC Pro | `app-rcpro/build/outputs/apk/debug/app-rcpro-debug.apk` |
| Phone | `app-phone/build/outputs/apk/debug/app-phone-debug.apk` |

---

## 5. Sideloading onto DJI RC Pro

1. Enable Developer Options & USB Debugging on the DJI RC Pro.
2. Connect RC Pro via USB-C to your computer.
3. Install the APK:
   ```bash
   adb install -r app-rcpro/build/outputs/apk/debug/app-rcpro-debug.apk
   ```
4. Turn on Wi-Fi on the RC Pro (internet access required for initial DJI SDK key verification).
5. Turn on the Mavic 3E and ensure it is linked to the RC Pro.
6. Launch **DJI Drone MQTT** and confirm the top badge displays **`Mavic 3E (Ready)`**.
7. Connect to your MQTT broker and begin testing with `mqtt.ping` before issuing flight commands.

See [docs/RC_PRO_INSTALL.md](docs/RC_PRO_INSTALL.md) for RC Pro steps and [docs/PHONE_INSTALL.md](docs/PHONE_INSTALL.md) for Mini 4 Pro + RC-N2 on a phone.
