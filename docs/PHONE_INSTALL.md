# Android Phone Installation (Mini 4 Pro + RC-N2)

Install and operate the **`:app-phone`** MQTT control bridge on an **Android phone** connected via USB to a **DJI RC-N2** (or RC-N1) with a **DJI Mini 4 Pro**.

> **MSDK note:** Mini 4 Pro is **not** listed on MSDK **5.9.0**; this project uses **MSDK 5.14.0** (see [DJI Mobile SDK release notes](https://developer.dji.com/doc/mobile-sdk-tutorial/en/)) which documents **DJI Mini 4 Pro + DJI RC N2** and continues to support **Mavic 3 Enterprise** on RC Pro.

---

## 1. Register a separate DJI App Key (phone package)

1. Open the [DJI Developer Portal](https://developer.dji.com/user/apps/#all) and create a **new Android Mobile SDK V5 app**.
2. Set **Package Name** to exactly:
   ```
   com.dji.dronecontrol.phone
   ```
3. Copy the generated **App Key** (24-character string).

The RC Pro app uses a **different** package (`com.dji.dronecontrol.rcpro`) and key (`DJI_APP_KEY`). Do not reuse the same portal registration for both APKs.

---

## 2. Configure `DJI_APP_KEY_PHONE` locally

In the repository root, create or edit `local.properties`:

```properties
# RC Pro / Mavic 3E (optional if you only build phone)
DJI_APP_KEY=your_rcpro_app_key_here

# Phone / Mini 4 Pro + RC-N2 (required for :app-phone)
DJI_APP_KEY_PHONE=your_phone_app_key_here

# Agora live view (App ID only at build time — never commit App Certificate)
AGORA_APP_ID=your_agora_app_id_here

# Publisher token endpoint (dashboard / MQTT can omit token when these are set)
STREAM_TOKEN_URL=https://tronico.kujuhk.com/live/token
STREAM_TOKEN_SECRET=your_bearer_secret_here
PHOTO_UPLOAD_URL=https://tronico.kujuhk.com/live/photo
```

The dashboard **Stream** card needs only a **channel** when `STREAM_TOKEN_SECRET` is configured; the app fetches the publisher token from `STREAM_TOKEN_URL`. Photo delivery (`camera.photo` with `deliver: both` by default) uploads JPEGs to `PHOTO_UPLOAD_URL` with the same bearer secret. **`camera.snapshot`** (Controls → **Snapshot (no SD)**) captures one frame from the live FPV feed on the phone—no microSD required—and uses the same upload URL and secret. Full-resolution stills and onboard video still require a working aircraft SD card for `camera.photo` / `camera.record_*`. Override URLs and secret in **MQTT settings** (secret is masked and stored encrypted). A manual pasted token override remains available in the UI (memory-only).

### MQTT mTLS (optional)

In the app **MQTT** settings you can enable **mTLS**, import a **PKCS#12** client certificate (and password), and optionally a custom **CA PEM**. Material is stored in app-private encrypted storage. Username/password MQTT auth still works when mTLS is off.

Fallback order for the phone key: `local.properties` → Gradle property `-PDJI_APP_KEY_PHONE=...` → environment variable `DJI_APP_KEY_PHONE`.

Never commit real keys or `local.properties` (already in `.gitignore`).

---

## 3. Build the phone debug APK

```bash
./gradlew test :app-phone:assembleDebug verifyDjiSdkInApks
```

After upgrading from v1.0.6, **uninstall the old APK** (or `adb install -r`) and prefer a clean build if Gradle cached a broken variant: `./gradlew clean :app-phone:assembleDebug`.

Output APK:

```
app-phone/build/outputs/apk/debug/app-phone-debug.apk
```

- **applicationId:** `com.dji.dronecontrol.phone`
- **Default version:** `v1.0.11 (12)` (shown in the app top bar)

---

## 4. Install via ADB

Enable **Developer options** and **USB debugging** on the phone, connect to your computer, then:

```bash
adb devices
adb install -r app-phone/build/outputs/apk/debug/app-phone-debug.apk
```

Grant **Location**, **Nearby devices / Bluetooth**, and **Storage** prompts when asked (required by MSDK V5).

---

## 5. Connect phone to RC-N2

1. Power on **Mini 4 Pro** and **RC-N2**; link the controller to the aircraft with DJI Fly if needed.
2. Connect the phone to the **RC-N2 USB-C port** with a data-capable USB cable.
3. When Android shows **USB accessory** / “Allow DJI…” prompts, tap **Allow** / **OK**.
4. The app may auto-launch via `UsbAttachActivity` (`USB_ACCESSORY_ATTACHED`). You can also open **DJI Drone MQTT (Phone)** from the launcher.

The manifest declares `android.hardware.usb.accessory` and `res/xml/accessory_filter.xml` for DJI logiclink accessories (RC-N1/N2 pattern).

---

## 6. First-run checks

1. **Internet (Wi‑Fi or mobile data)** on first launch so MSDK can register `DJI_APP_KEY_PHONE` with DJI license servers.
2. On the **Dashboard** tab, confirm:
   - **App Build:** `v1.0.11 (12)`
   - **App Key card:** masked key or ⚠️ warning if unset
   - **SDK Registered:** `YES`
   - **Hardware Link:** `CONNECTED` after USB + aircraft link
   - **Model:** `Mini 4 Pro` (or product string from SDK)
3. **Settings** tab: set MQTT broker host/port and `aircraft_id` (default `mini4pro-01`), then **Connect MQTT**.
4. Smoke-test (open area, VLOS, pilot on sticks):
   - `mqtt.ping`
   - `flight.hover`
   - **Rotate CW / CCW** (20°/s, 1000 ms)
   - **Emergency Stop** cancels virtual stick streams

Physical stick input on the RC-N2 **always overrides** MQTT virtual stick commands.

---

## 7. Camera / photo defaults (Mini 4 Pro)

For `camera.photo`, Mini 4 Pro supports **12MP** and **48MP** JPEG (and DNG where enabled)—handled in `:dji-adapter` via `CameraKey.KeyPhotoSize`. See [MINI4PRO_TRANSFER.md](MINI4PRO_TRANSFER.md).

---

## 8. Troubleshooting

| Symptom | Check |
|--------|--------|
| SDK registration failed | `DJI_APP_KEY_PHONE` set; package `com.dji.dronecontrol.phone` matches portal; phone has internet on first run |
| No aircraft connection | USB data cable; RC-N2 powered; accessory permission; try replugging USB |
| App does not auto-open on USB | Launch manually; verify `accessory_filter.xml` and RC firmware |
| MQTT commands ignored | Broker connected; `aircraft_id` in JSON matches Settings |
