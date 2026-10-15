# Phase 2 Transfer Guide: Phone App + Mini 4 Pro (RC-N2/N3)

This guide explains how the unified architecture created in Phase 1 (`:core` and `:dji-adapter`) seamlessly transfers to **Phase 2**, running on an **Android Phone connected via USB to a DJI RC-N2 / RC-N3 controller with a DJI Mini 4 Pro**.

**Status:** `:app-phone` is a full installable app (not a stub). It shares `:core`, `:dji-adapter`, and `:app-ui` with `:app-rcpro`. Phone-specific pieces live under `app-phone/` (USB accessory entry, `DJI_APP_KEY_PHONE`, default `mini4pro-01` aircraft id). See [PHONE_INSTALL.md](PHONE_INSTALL.md).

### MSDK version & Mini 4 Pro support

| Item | Value |
|------|--------|
| **MSDK used in this repo** | **5.14.0** (`djiMsdkVersion` in `gradle.properties`) |
| **Mini 4 Pro on 5.9.0?** | **No** — DJI’s 5.9.0 release matrix does not list Mini 4 Pro; support was added in later 5.x releases. |
| **DJI documentation** | [Mobile SDK release notes](https://developer.dji.com/doc/mobile-sdk-tutorial/en/) list **DJI Mini 4 Pro + DJI RC N2** (and RC N3 in newer entries). |
| **RC Pro / Mavic 3E** | Still supported on 5.14.0 per the same release notes. |

---

## 1. Architecture Transferability Matrix

| Component | Phase 1 (Mavic 3E + RC Pro) | Phase 2 (Mini 4 Pro + Phone + RC-N2) | What Changes? |
|---|---|---|---|
| **MQTT API Envelope & Topics** | `dji/{aircraft_id}/cmd`, `status`, `ack` | `dji/{aircraft_id}/cmd`, `status`, `ack` | **Unchanged (100% shared)** |
| **Command Schemas & Parsing** | `CommandRouter`, `CommandEnvelope` in `:core` | `CommandRouter`, `CommandEnvelope` in `:core` | **Unchanged (100% shared)** |
| **Domain Ports** | `FlightControllerPort`, `CameraPort`, `StreamPort` | `FlightControllerPort`, `CameraPort`, `StreamPort` | **Unchanged (100% shared)** |
| **DJI KeyManager & VS Logic** | `:dji-adapter` | `:dji-adapter` | **Unchanged (100% shared)** |
| **Compose UI** | `:app-ui` (`MainScreen`, smoke controls) | `:app-ui` (same MQTT/flight/rotate buttons) | Host badge + default aircraft id only |
| **DJI App Key** | `DJI_APP_KEY` → `com.dji.dronecontrol.rcpro` | `DJI_APP_KEY_PHONE` → `com.dji.dronecontrol.phone` | Separate portal registrations |
| **App Execution Host** | Built-in Android inside DJI RC Pro | User's Android phone | Runs as phone app |
| **Controller Communication** | Internal Android system bus | Android USB Accessory mode (`AOA`) to RC-N2 | USB Accessory lifecycle in Activity |
| **Hardware Controller** | DJI RC Pro (with screen) | DJI RC-N2 / RC-N3 (screenless, phone holder) | Phone is plugged via USB cable |

---

## 2. Why the Gateway & IoT Backends Remain 100% Unchanged

Because all MQTT serialization, topic management, schemas, and router dispatches live inside `:core`, the IoT gateway / cloud orchestrator does not need to know whether the drone on the other end is a Mavic 3E or a Mini 4 Pro:
1. Target `aircraft_id` is simply set to `mini4pro-01` (or whatever ID is registered).
2. All commands (`flight.takeoff`, `flight.land`, `flight.move`, `flight.rotate`, `camera.photo`, `stream.start`, etc.) are identical.
3. Telemetry envelopes and status responses follow the exact same JSON schema version 1.0.

---

## 3. Mini 4 Pro & Phone Hardware Specifics

When transferring to the physical phone app in Phase 2, take note of the following hardware and SDK nuances:

### 3.1 USB Accessory Lifecycle (`android.hardware.usb.accessory`)
Unlike the RC Pro (which has built-in Android running directly on the controller hardware), a phone communicates with the RC-N2/RC-N3 over the Android Open Accessory (AOA) protocol.
- `UsbAttachActivity` handles `USB_ACCESSORY_ATTACHED` with `res/xml/accessory_filter.xml` (DJI logiclink accessories), then forwards to `MainActivity` (MSDK V5 sample pattern).
- `MainActivity` also logs `USB_ACCESSORY_ATTACHED` on `onNewIntent` when the cable is reconnected while the app is running.
- When the phone is plugged into the RC-N2 USB-C port, Android may auto-launch the app; you can always open it from the launcher.

### 3.2 DJI Controller Differences: RC-N2 vs RC 2
- **Supported for Phone App**: **DJI RC-N2** and **RC-N3** (these have no screen and pass USB telemetry through to the phone).
- **Not Supported for External Phone App**: **DJI RC 2** (the DJI RC 2 has a built-in screen and locked Android firmware without public MSDK sideloading; users must use the RC-N2/N3 with their phone or the RC Pro for enterprise craft).

### 3.3 Camera Resolution & Media Management
- **KeyPhotoSize**: Mini 4 Pro supports 12MP and 48MP photo resolutions, fully matching the `CameraKey.KeyPhotoSize` handling in `:dji-adapter`.
- **Media Manager**: When pulling photos off the SD card, use `ALL` media filter to retrieve both 12MP/48MP JPEGs and DNG raw files.

### 3.4 Waypoint Missions (Future WPML / KMZ Expansion)
- When expanding `:core` to support `mission.upload_and_start` in Phase 2:
  - Mini 4 Pro MSDK V5 `KeyStartMission` requires mission name string without `.kmz` file extension suffix.
  - The domain ports are designed so adding mission upload is purely additive to `FlightControllerPort`.
