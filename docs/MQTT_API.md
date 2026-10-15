# MQTT API Reference (Version 1.0)

This document defines the MQTT API contract between the remote IoT gateway / ground station backend and the DJI Drone Android control app (`:app-rcpro` and `:app-phone`).

---

## 1. Topic Structure

Topic paths are fully configurable in the application settings. By default, they follow:

| Direction | Default Topic Pattern | Description |
|---|---|---|
| **Gateway → App** | `dji/{aircraft_id}/cmd` | Commands sent to the drone |
| **App → Gateway** | `dji/{aircraft_id}/status` | Periodic telemetry and asynchronous status events |
| **App → Gateway** | `dji/{aircraft_id}/ack` | Immediate acknowledgment responses for received commands |

*(Note: If the gateway listens only to a single status topic, acks are also mirrored to `dji/{aircraft_id}/status`)*

---

## 2. Command Message Envelope (Gateway → Drone)

All commands must be formatted as JSON with `schema_version: "1.0"`:

```json
{
  "schema_version": "1.0",
  "msg_id": "c1f73b64-1598-4c3e-8bc7-5c4a78129034",
  "ts": "2026-09-24T12:00:00Z",
  "aircraft_id": "m3e-01",
  "cmd": "flight.takeoff",
  "params": {
    "target_height_m": 2.5
  }
}
```

### Fields

| Field | Type | Required | Description |
|---|---|---|---|
| `schema_version` | String | Yes | Must be `"1.0"` |
| `msg_id` | String | Yes | Unique UUID for correlation with acks |
| `ts` | String | Yes | ISO-8601 timestamp in UTC |
| `aircraft_id` | String | Yes | Target drone identifier (e.g. `m3e-01`, `mini4pro-01`) |
| `cmd` | String | Yes | Command identifier string |
| `params` | Object | No | Command-specific parameter payload |

---

## 3. Supported Commands & Parameter Schemas

### 3.1 `mqtt.ping`
Health check and connectivity ping.
- **Params**: `{}`
- **Behavior**: App immediately returns an ack and triggers an instantaneous `aircraft.status` update.

---

### 3.2 `flight.takeoff`
Initiates auto-takeoff.
- **Params**:
  - `target_height_m` *(optional, number)*: Target hover altitude in meters (default: 1.5m).
```json
{
  "cmd": "flight.takeoff",
  "params": {
    "target_height_m": 2.0
  }
}
```

---

### 3.3 `flight.land`
Initiates automated landing at the current position.
- **Params**: `{}`
```json
{
  "cmd": "flight.land",
  "params": {}
}
```

---

### 3.4 `flight.rth` / `flight.go_home`
Initiates Return-to-Home (RTH) procedure using the configured home point.
- **Params**: `{}`
```json
{
  "cmd": "flight.rth",
  "params": {}
}
```

---

### 3.5 `flight.hover`
Brakes virtual stick velocity commands and commands the aircraft to hold position in P-GPS mode.
- **Params**: `{}`
```json
{
  "cmd": "flight.hover",
  "params": {}
}
```

---

### 3.6 `flight.move`
Commands the aircraft using Virtual Stick mode (Velocity or Stick-normalized).
- **Params (Velocity mode)**:
  - `mode`: `"velocity"`
  - `vx`: Horizontal velocity along X-axis / forward-back in m/s (+ forward, - backward)
  - `vy`: Horizontal velocity along Y-axis / right-left in m/s (+ right, - left)
  - `vz`: Vertical velocity along Z-axis in m/s (+ up, - down)
  - `yaw_rate`: Angular yaw speed in deg/s (+ clockwise, - counter-clockwise)
  - `duration_ms` *(optional, integer)*: Duration window in milliseconds (default: 500ms, min: 50ms, max: 10,000ms safety cap).
- **Execution & Watchdog Protocol**:
  - The bridge continuously streams Virtual Stick velocity frames at 20 Hz (every 50 ms) to the DJI Flight Controller for the entire duration window.
  - Upon duration completion, the bridge automatically transmits zero-velocity frames to transition the aircraft into stationary hover (`P_GPS`).
  - Consecutive `flight.move` commands atomically preempt any active move stream without stutter.
  - `flight.hover`, `flight.emergency_stop`, or physical RC stick override immediately cancels the stream and halts motion.
```json
{
  "cmd": "flight.move",
  "params": {
    "mode": "velocity",
    "vx": 2.0,
    "vy": 0.0,
    "vz": 0.5,
    "yaw_rate": 10.0,
    "duration_ms": 1000
  }
}
```
- **Params (Stick normalized mode)**:
  - `mode`: `"stick"`
  - `pitch`: -1.0 to 1.0
  - `roll`: -1.0 to 1.0
  - `yaw`: -1.0 to 1.0
  - `throttle`: -1.0 to 1.0

---

### 3.7 `flight.rotate`
Commands a pure yaw rotation using Virtual Stick velocity control while maintaining zero linear velocity (\(v_x = 0, v_y = 0, v_z = 0\)).
- **Params**:
  - `yaw_rate`: Angular yaw speed in deg/s (+ clockwise / right, - counter-clockwise / left).
  - `duration_ms` *(optional, integer)*: Duration window in milliseconds (default: 500ms, min: 50ms, max: 10,000ms safety cap).
- **Execution & Watchdog Protocol**:
  - The bridge continuously streams Virtual Stick velocity frames at 20 Hz (every 50 ms) to the DJI Flight Controller with `yaw_rate` active and linear velocities held at 0.
  - Upon duration completion, the bridge automatically transmits zero-velocity frames to transition the aircraft into stationary hover (`P_GPS`).
  - Consecutive `flight.rotate` or `flight.move` commands atomically preempt any active stream without stutter.
  - `flight.hover`, `flight.emergency_stop`, or physical RC stick override immediately cancels the stream and halts rotation.
```json
{
  "cmd": "flight.rotate",
  "params": {
    "yaw_rate": 20.0,
    "duration_ms": 1000
  }
}
```

---

### 3.8 `gimbal.rotate`
Timed gimbal speed pulse (pitch and/or yaw rate), then stops rotation at end of `duration_ms`.
- **Params**:
  - `pitch_rate_dps` *(optional)*: Pitch speed in deg/s (+ up, − down).
  - `yaw_rate_dps` *(optional)*: Yaw speed in deg/s (only when aircraft gimbal supports yaw adjust).
  - `duration_ms` *(optional, default 500, max 10_000)*: Pulse length in milliseconds.
- At least one rate must be non-zero. `flight.emergency_stop` and `gimbal.reset` cancel an active rotate pulse.
```json
{
  "cmd": "gimbal.rotate",
  "params": {
    "pitch_rate_dps": -10.0,
    "yaw_rate_dps": 0.0,
    "duration_ms": 500
  }
}
```

### 3.9 `gimbal.set_angle`
Absolute gimbal angles (SDK `KeyRotateByAngle`). Unsupported axes are ignored or rejected with `error_code` `GIMBAL_YAW_UNSUPPORTED` / `GIMBAL_ROLL_UNSUPPORTED` / `GIMBAL_ANGLE_UNSUPPORTED` — the bridge does not invent motion.
- **Params** (at least one required):
  - `pitch_deg`, `yaw_deg`, `roll_deg` *(optional doubles)*
- Pitch is clamped to aircraft limits; roll is not supported on the primary consumer gimbal. On Mini 4 / similar consumer gimbals, `yaw_deg: 0` and `roll_deg: 0` are treated as **ignored** (pitch-only); non-zero yaw requires yaw-adjust support.
```json
{
  "cmd": "gimbal.set_angle",
  "params": {
    "pitch_deg": -45.0,
    "yaw_deg": 0.0,
    "roll_deg": 0.0
  }
}
```

### 3.10 `gimbal.reset`
Recenters the gimbal (SDK `KeyGimbalReset` / `RECENTER`).
```json
{
  "cmd": "gimbal.reset",
  "params": {}
}
```

---

### 3.11 `flight.emergency_stop`
Immediately stops Virtual Stick commands, cuts velocity to zero, and triggers emergency braking/landing policy.
- **Params**: `{}`
```json
{
  "cmd": "flight.emergency_stop",
  "params": {}
}
```

---

### 3.12 `camera.photo`
Switches camera mode to `PHOTO_NORMAL` and shoots a still photo. After a successful shoot, the app may pull the JPEG from the aircraft (MSDK media manager), save a copy on the handset, and upload to the viewer site. **No image bytes are sent over MQTT.**

- **Params**:
  - `size` *(optional)*: `"12mp"` | `"48mp"` | `"default"` — delivery pipeline defaults to **12MP JPEG** when `size` is `"default"` or omitted.
  - `format` *(optional)*: `"jpeg"` | `"dng"` | `"raw_jpeg"`
  - `deliver` *(optional)*: `"link"` | `"local"` | `"both"` — default **`both`** (upload link + local file).
- **Ack**: `ok: true` immediately when the shoot is accepted. A follow-up ack with `result` may arrive when pull/upload finishes (success or upload error).
- **Upload** (when `deliver` includes link): `POST {PHOTO_UPLOAD_URL}` (default `https://tronico.kujuhk.com/live/photo`), raw JPEG body, `Authorization: Bearer {STREAM_TOKEN_SECRET}` (same secret as stream token; never echoed on MQTT). Optional headers: `X-Aircraft-Id`, `X-Msg-Id`, `X-File-Index`. Configure via `PHOTO_UPLOAD_URL` in `local.properties` or MQTT settings (`photo_upload_url`).
```json
{
  "cmd": "camera.photo",
  "params": {
    "size": "12mp",
    "format": "jpeg",
    "deliver": "both"
  }
}
```

**Photo delivery error codes** (`camera.photo_done` / follow-up ack `error_code`): `MEDIA_PULL_FAILED`, `UPLOAD_FAILED`, `UPLOAD_UNAUTHORIZED`, `UPLOAD_TOO_LARGE`, `UPLOAD_NOT_CONFIGURED`, `PHOTO_TIMEOUT`.

---

### 3.13 `camera.snapshot`
Grabs **one frame from the live MSDK camera video feed on the handset** (no aircraft SD / media pull), encodes JPEG locally, then delivers like photo upload (`deliver` default **`both`**). Requires an active **aircraft→phone video link** (DJI FPV decode); the app binds a small off-screen decode surface and listens for frames — **Agora/RTMP `stream.start` is not required**. Resolution is preview/stream quality, not full still resolution.

- **Params**:
  - `deliver` *(optional)*: `"link"` | `"local"` | `"both"` — default **`both`**
  - `quality` *(optional)*: JPEG quality `1`–`100` (default `85`)
- **Ack**: `ok: true` when a frame is captured; follow-up ack with `result` when local save / upload finishes (same shape as photo, plus `source: "video_frame"`, `width`, `height`).
- **Upload**: same as `camera.photo` — `POST {PHOTO_UPLOAD_URL}`, `Authorization: Bearer {STREAM_TOKEN_SECRET}`.

```json
{
  "cmd": "camera.snapshot",
  "params": {
    "deliver": "both",
    "quality": 85
  }
}
```

**Snapshot error codes** (`camera.snapshot_done` / ack `error_code`): `NO_VIDEO_FRAME`, `SNAPSHOT_TIMEOUT`, `UPLOAD_FAILED`, `UPLOAD_UNAUTHORIZED`, `UPLOAD_TOO_LARGE`, `UPLOAD_NOT_CONFIGURED`, plus local-save failures reported as `MEDIA_PULL_FAILED`.

---

### 3.14 `camera.record_start`
Switches camera mode to `VIDEO_NORMAL` and starts video recording on the onboard SD card.
- **Params**: `{}`
```json
{
  "cmd": "camera.record_start",
  "params": {}
}
```

---

### 3.15 `camera.record_stop`
Stops current video recording. On success, the app may pull the newest MP4/MOV from the aircraft, save locally, and upload to the viewer site (same pattern as photo delivery).

- **Params**:
  - `deliver` *(optional)*: `"link"` | `"local"` | `"both"` — default **`both`**
- **Ack**: `ok: true` when stop is accepted; follow-up ack / `camera.record_status` when pull+upload finish.
- **Upload**: `POST {VIDEO_UPLOAD_URL}` (default `https://tronico.kujuhk.com/live/video`), raw `video/mp4`, `Authorization: Bearer {STREAM_TOKEN_SECRET}`, max **512 MiB**. Optional headers: `X-Aircraft-Id`, `X-Msg-Id`, `X-File-Index`. Configure via `VIDEO_UPLOAD_URL` in `local.properties` or MQTT settings (`video_upload_url`).

```json
{
  "cmd": "camera.record_stop",
  "params": { "deliver": "both" }
}
```

---

### 3.16 `stream.start`
Starts live video streaming. Supports **RTMP** (unchanged) and **Agora** via DJI MSDK V5 `LiveStreamManager`.

#### RTMP
- **Params**:
  - `protocol`: `"rtmp"`
  - `url`: Ingest endpoint URL (e.g. `rtmp://video.example.com/live/drone01`)
  - `quality` *(optional)*: `"SD"` | `"HD"` | `"FULL_HD"` | `"AUTO"`

#### Agora
- **Params**:
  - `protocol`: `"agora"`
  - `channel`: Agora channel name (required)
  - `token`: Agora **publisher** token *(optional when the app is configured with a stream token endpoint — see `STREAM_TOKEN_URL` / `STREAM_TOKEN_SECRET` in `local.properties` or MQTT settings)*. When omitted, the app `POST`s to the token endpoint and uses the returned publisher token. When present (gateway path), the app uses it as today. Never echoed in logs, acks, or status.
  - `uid` *(optional, default `1001`)*: Publisher UID (used for token fetch when `token` is omitted)
  - `app_id` *(optional)*: Overrides build-time `AGORA_APP_ID` from `local.properties`
  - `quality` *(optional)*: stream quality enum as above
- A new `stream.start` while live **stops the previous session** first.
- **Ack `ok: true`** only after the SDK reports the stream is **live** (up to ~15s); includes:
```json
{
  "type": "ack",
  "msg_id": "...",
  "ok": true,
  "result": {
    "channel": "my-channel",
    "viewer_url": "https://tronico.kujuhk.com/live?channel=my-channel",
    "started_at": "2026-10-08T12:00:00Z"
  }
}
```
- **`viewer_url`** is the **bare** page link (no token). Your gateway appends the audience token as `#token=<audience token>`.
- Default viewer base URL: `https://tronico.kujuhk.com/live` (editable in app MQTT settings, persisted).
- **Security**: The app redacts `"token"` in all mirrored MQTT console / diagnostics text as `"***"`. Tokens must never appear in logcat, acks, or `stream.status`.

**Error codes** (`ok: false`, `error_code` field): `INVALID_PARAMS`, `AGORA_TOKEN_EXPIRED`, `NO_UPLINK`, `CAMERA_NOT_READY`, `STREAM_TIMEOUT`, `STREAM_ERROR`, `TOKEN_ENDPOINT_NOT_CONFIGURED`, `TOKEN_ENDPOINT_UNAUTHORIZED`, `TOKEN_ENDPOINT_FAILED`.

**Token fetch (app → viewer site, when `token` is omitted):**
- `POST {STREAM_TOKEN_URL}` (default `https://tronico.kujuhk.com/live/token`)
- Header: `Authorization: Bearer {STREAM_TOKEN_SECRET}`
- Body: `{"channel":"<ch>","role":"publisher","uid":1001}`
- `200`: `{"token","channel","uid","expires_at"}` — response `channel` must match the request

```json
{
  "cmd": "stream.start",
  "params": {
    "protocol": "agora",
    "channel": "mini4pro-live-01",
    "uid": 1001,
    "quality": "HD"
  }
}
```

```json
{
  "cmd": "stream.start",
  "params": {
    "protocol": "agora",
    "channel": "mini4pro-live-01",
    "token": "<publisher-token>",
    "uid": 1001,
    "quality": "HD"
  }
}
```

```json
{
  "cmd": "stream.start",
  "params": {
    "protocol": "rtmp",
    "url": "rtmp://192.168.1.100:1935/live/m3e-01",
    "quality": "HD"
  }
}
```

---

### 3.16 `stream.stop`
Stops the active live stream (RTMP or Agora).
- **Params**: `{}`
```json
{
  "cmd": "stream.stop",
  "params": {}
}
```

---

### 3.17 `config.get`
Queries current drone application configuration, connection states, and active topics.
- **Params**: `{}`
```json
{
  "cmd": "config.get",
  "params": {}
}
```

---

## 4. Status & Response Envelopes (Drone → Gateway)

### 4.1 Acknowledgment (`type: "ack"`)
Published immediately upon processing any command:
```json
{
  "type": "ack",
  "msg_id": "c1f73b64-1598-4c3e-8bc7-5c4a78129034",
  "ok": true,
  "error": null
}
```
If an error occurs:
```json
{
  "type": "ack",
  "msg_id": "c1f73b64-1598-4c3e-8bc7-5c4a78129034",
  "ok": false,
  "error": "Aircraft disconnected"
}
```

---

### 4.2 Periodic Telemetry (`type: "aircraft.status"`)
Published periodically (every 1000ms):
```json
{
  "type": "aircraft.status",
  "connected": true,
  "product": "Mavic 3E",
  "flight_mode": "P_GPS",
  "battery_pct": 82,
  "gps": {
    "latitude": 22.3193,
    "longitude": 114.1694,
    "altitude_m": 25.4,
    "satellites_count": 19
  },
  "in_air": true,
  "motors_on": true,
  "registered": true,
  "telemetry_ready": true,
  "error_state": null
}
```

---

### 4.3 Photo Completion (`type: "camera.photo_done"`)
Published when media pull / local save / upload complete (after shoot ack).
```json
{
  "type": "camera.photo_done",
  "ok": true,
  "file_index": 104,
  "download_url": "https://tronico.kujuhk.com/live/photo/abc123",
  "local_path": "/storage/emulated/0/Android/data/.../files/photos/DJI_0104.jpg",
  "content_type": "image/jpeg",
  "bytes": 2345678,
  "expires_at": "2026-10-10T12:00:00Z",
  "error_code": null,
  "error": null
}
```
On upload failure with successful local save (`deliver: "both"`), `ok` is `false`, `local_path` is still set, and `error_code` describes the upload failure.

---

### 4.3.1 Snapshot completion (`type: "camera.snapshot_done"`)
Published after frame capture and optional local save / upload (see §3.13).
```json
{
  "type": "camera.snapshot_done",
  "ok": true,
  "download_url": "https://tronico.kujuhk.com/live/photo/abc123",
  "local_path": "/storage/emulated/0/Android/data/.../files/photos/snapshot_1696800000000.jpg",
  "content_type": "image/jpeg",
  "bytes": 245678,
  "expires_at": "2026-10-10T12:00:00Z",
  "source": "video_frame",
  "width": 1280,
  "height": 720,
  "error_code": null,
  "error": null
}
```

---

### 4.4 Camera Record Status (`type: "camera.record_status"`)
While recording:
```json
{
  "type": "camera.record_status",
  "recording": true,
  "duration_seconds": 45,
  "error": null
}
```

After `camera.record_stop` with delivery, when pull/upload complete:
```json
{
  "type": "camera.record_status",
  "recording": false,
  "ok": true,
  "file_index": 210,
  "download_url": "https://tronico.kujuhk.com/live/video/abc123",
  "local_path": "/storage/emulated/0/Android/data/.../files/videos/DJI_0210.mp4",
  "content_type": "video/mp4",
  "bytes": 12345678,
  "expires_at": "2026-10-10T12:00:00Z",
  "error_code": null,
  "error": null
}
```

---

### 4.5 Live Stream Status (`type: "stream.status"`)
Published on state changes: `starting`, `live`, `stopped`, `error` (includes `error_code` when applicable). Never includes the Agora token.

```json
{
  "type": "stream.status",
  "state": "live",
  "streaming": true,
  "protocol": "agora",
  "channel": "mini4pro-live-01",
  "viewer_url": "https://tronico.kujuhk.com/live?channel=mini4pro-live-01",
  "started_at": "2026-10-08T12:00:00Z",
  "error": null,
  "error_code": null
}
```

RTMP example:
```json
{
  "type": "stream.status",
  "state": "live",
  "streaming": true,
  "protocol": "rtmp",
  "url": "rtmp://192.168.1.100:1935/live/m3e-01",
  "quality": "HD"
}
```

---

### 4.6 Configuration Response (`type: "config.response"`)
```json
{
  "type": "config.response",
  "schema_version": "1.0",
  "aircraft_id": "m3e-01",
  "mqtt_connected": true,
  "aircraft_connected": true,
  "product_type": "Mavic 3E",
  "topics": {
    "cmd": "dji/m3e-01/cmd",
    "status": "dji/m3e-01/status",
    "ack": "dji/m3e-01/ack"
  }
}
```
