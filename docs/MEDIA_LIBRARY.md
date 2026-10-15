# Media library (Ops → Media)

Browse photos and videos on the aircraft SD card via DJI MSDK **MediaDataCenter** / **MediaManager** (not session-only captures).

## UI (v1.0.18+)

- **Ops drawer → Media** (after Controls).
- **Refresh** pulls the file list from the aircraft (newest first).
- Filter chips: **All | Photos | Videos**.
- **Tap** a row: download original (if needed) → photo full-screen preview or in-app **ExoPlayer** video (play/pause/seek).
- **Get link**: upload to tronico (`POST /live/photo` or `/live/video`) and show `download_url` (same bearer secret as stream token; never shown in logs).
- **Select** / long-press: multi-select → **Delete** with confirmation → `deleteMediaFiles` on aircraft.
- Waiting card when not registered/connected → jump to **Connection**.

## MQTT video delivery (after record stop)

`camera.record_stop` accepts optional `deliver`: `link` | `local` | `both` (default **`both`**).

After a successful stop, the app finds the newest video, pulls it, optionally saves under app storage (`videos/`), uploads MP4 to `VIDEO_UPLOAD_URL`, and publishes enriched `camera.record_status` with `download_url` verbatim from the upload 200 response.

Configure in `local.properties`:

```properties
VIDEO_UPLOAD_URL=https://tronico.kujuhk.com/live/video
STREAM_TOKEN_SECRET=your_bearer_secret
```

Override URLs in MQTT settings (`video_upload_url`, `photo_upload_url`).

## Errors

Same family as photo delivery: `MEDIA_PULL_FAILED`, `UPLOAD_FAILED`, `UPLOAD_UNAUTHORIZED`, `UPLOAD_TOO_LARGE`, `UPLOAD_NOT_CONFIGURED`, `PHOTO_TIMEOUT`. Upload may fail until the site endpoint is live; local save still succeeds when pull works (`deliver: both`).
