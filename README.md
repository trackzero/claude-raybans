# Ray-Ban Meta Gen 2 — Home Assistant Integration

Integrates Ray-Ban Meta Gen 2 smart glasses with [Home Assistant](https://www.home-assistant.io/) via an Android bridge app. No cloud dependency beyond your existing HA setup — works remotely via Nabu Casa or any external HA URL.

---

## Features

| Feature | How it works |
|---|---|
| **Notifications** | Call `notify.raybans` in HA → TTS plays through glasses speakers |
| **Voice / Assist** | Glasses mic streams to HA Assist pipeline → STT → intent → TTS response on glasses |
| **Battery sensor** | Glasses battery % appears as a HA sensor entity |
| **Worn sensor** | Binary sensor tracks whether glasses are on your face |
| **BT connected sensor** | Binary sensor tracks Bluetooth connection to phone |
| **Camera stream** | 720p MJPEG stream from glasses camera in a HA dashboard card |

---

## Architecture

```
┌─────────────────────────────────────┐
│           Android Phone             │
│                                     │
│  GlassesBridgeService (foreground)  │
│  ├── MetaGlassesManager  (mwdat)    │◄──── Bluetooth ────► Ray-Ban Meta Gen 2
│  ├── BatteryMonitor      (BT API)   │                      (mic, speakers,
│  ├── TtsPlayer           (A2DP)     │                       camera, battery)
│  ├── VoiceCapture        (VAD)      │
│  ├── CameraStreamServer  (HTTP)     │──── MJPEG (LAN) ───► HA Camera entity
│  ├── HaWebSocketClient   (OkHttp)   │◄─── WS events ─────► HA WebSocket API
│  ├── HaApiClient         (REST)     │──── state push ────► /api/states/...
│  └── AssistPipelineClient           │◄──► assist_pipeline/run
└─────────────────────────────────────┘
```

**Key design decisions:**
- No MQTT broker required — all HA communication uses HA's native WebSocket + REST APIs
- Works remotely via Nabu Casa / any external HA URL with a long-lived access token
- Camera stream is LAN-only (Bluetooth bandwidth cap); all other features work remotely
- State is pushed from Android to HA (not polled), so entities update in real time

---

## Repository Structure

```
claude-raybans/
├── custom_components/raybans_meta/   # HA custom integration
│   ├── manifest.json
│   ├── const.py
│   ├── config_flow.py
│   ├── __init__.py
│   ├── sensor.py                     # Battery %
│   ├── binary_sensor.py              # Worn, Connected
│   ├── camera.py                     # MJPEG proxy
│   ├── notify.py                     # Fires HA event → Android TTS
│   └── strings.json
└── android-bridge/                   # Android foreground service app
    ├── settings.gradle.kts
    ├── build.gradle.kts
    ├── gradle/libs.versions.toml
    ├── local.properties.example
    └── app/src/main/
        ├── AndroidManifest.xml
        └── kotlin/com/raybans/ha/
            ├── MainActivity.kt
            ├── prefs/AppPreferences.kt
            ├── service/GlassesBridgeService.kt
            ├── glasses/
            │   ├── MetaGlassesManager.kt    # mwdat SDK wrapper
            │   ├── BatteryMonitor.kt        # BT broadcast receiver
            │   ├── TtsPlayer.kt             # Android TTS + AudioTrack
            │   ├── VoiceCapture.kt          # Energy VAD
            │   └── CameraStreamServer.kt    # NanoHTTPD MJPEG server
            └── ha/
                ├── HaWebSocketClient.kt     # Auth, event subscription, reconnect
                ├── HaApiClient.kt           # REST state push
                └── AssistPipelineClient.kt  # assist_pipeline/run audio streaming
```

---

## Prerequisites

- **Home Assistant** 2024.x or later (Core, OS, or Container)
- **Android phone** running Android 10+ (API 29+), paired to the glasses via the Meta app
- **Ray-Ban Meta Gen 2** glasses paired via Bluetooth to the Android phone
- **Meta Wearables Device Access Toolkit (mwdat)** SDK access — request access at [Meta for Developers](https://developers.facebook.com/docs/wearables)
- **GitHub Personal Access Token** with `read:packages` scope (for mwdat SDK download)

---

## Setup

### 1. HA Custom Integration

Copy the `custom_components/raybans_meta/` directory into your HA config folder:

```
<ha-config>/
└── custom_components/
    └── raybans_meta/   ← copy here
```

Restart Home Assistant, then:

1. Go to **Settings → Devices & Services → Add Integration**
2. Search for **Ray-Ban Meta**
3. Enter your **Device ID** (e.g. `raybans` — used in entity IDs)
4. Enter the **MJPEG URL** (optional; e.g. `http://192.168.1.x:8080` — your phone's LAN IP)

Entities created:

| Entity | ID pattern |
|---|---|
| Battery | `sensor.raybans_battery_{device_id}` |
| Worn | `binary_sensor.raybans_worn_{device_id}` |
| Connected | `binary_sensor.raybans_connected_{device_id}` |
| Camera | `camera.raybans_camera_{device_id}` |
| Notify | `notify.raybans_{device_id}` |

### 2. Long-Lived Access Token

In HA: **Profile → Security → Long-Lived Access Tokens → Create Token**

Copy it — you'll need it in the Android app.

### 3. Android Bridge App

#### Configure mwdat credentials

```bash
cp android-bridge/local.properties.example android-bridge/local.properties
```

Edit `local.properties`:
```properties
sdk.dir=/path/to/Android/Sdk
github.username=YOUR_GITHUB_USERNAME
github.token=YOUR_GITHUB_PAT_WITH_READ_PACKAGES
```

#### Build and install

```bash
cd android-bridge
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or open `android-bridge/` in Android Studio and run directly.

#### Configure the app

1. Open **Ray-Ban HA Bridge** on your phone
2. Enter your **HA URL** (e.g. `https://example.ui.nabu.casa` or your local URL)
3. Paste your **long-lived access token**
4. Enter the same **Device ID** you used in the HA config flow
5. Set the **MJPEG port** (default `8080`) — your phone's LAN IP + this port = the MJPEG URL
6. Tap **Connect**

---

## Usage

### Send a notification to the glasses

```yaml
service: notify.raybans_raybans
data:
  message: "Dinner is ready"
```

Or via Developer Tools → Services.

### Voice / Assist

Configure an [Assist pipeline](https://www.home-assistant.io/docs/assist/pipelines/) in HA with your preferred STT and TTS providers. The Android app streams mic audio to the pipeline on speech detection and plays the TTS response through the glasses speakers.

### Camera stream

Add a **Picture Glance** or **Camera** card to your dashboard pointing to `camera.raybans_camera_raybans`. The stream only works on your local LAN.

---

## How State Push Works

The Android bridge app pushes sensor states directly to HA's REST API — no webhook or polling required:

```
POST /api/states/sensor.raybans_battery_raybans
Authorization: Bearer <token>
{"state": 85, "attributes": {"unit_of_measurement": "%", "device_class": "battery"}}
```

The HA integration entities use `RestoreEntity` so the last known state survives HA restarts.

---

## Limitations

| Limitation | Notes |
|---|---|
| Camera stream is LAN-only | Bluetooth max ~2–4 Mbps; insufficient for remote MJPEG |
| Worn detection | mwdat v0.4.0 may not expose this directly; accelerometer heuristic planned |
| Wake word | Phase 2 — Porcupine or openWakeWord integration |
| Battery API | Falls back to `BATTERY_LEVEL_CHANGED` BT broadcast if mwdat doesn't expose it |

---

## Roadmap

- [ ] Wake word activation ("Hey Home Assistant") via Porcupine / openWakeWord
- [ ] Worn detection via accelerometer heuristic
- [ ] Optional MQTT transport (for offline-first / multi-device)
- [ ] HACS integration listing

---

## License

MIT
