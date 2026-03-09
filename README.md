# Ray-Ban Meta Gen 2 — Home Assistant Integration

An Android bridge app and HA custom integration that connects your Ray-Ban Meta Gen 2 glasses to Home Assistant. Built by Claude, supervised by a human who should probably have read the SDK docs first.

---

## Does it actually work?

Mostly! Here's the honest breakdown:

| Feature | Status | Notes |
|---|---|---|
| **HA Notifications → glasses TTS** | ✅ Works | `notify.raybans` → speech on glasses speakers |
| **"Ask HA" via notification button** | ✅ Works | Tap notification → phone mic → Claude + HA MCP → TTS |
| **"Hey Meta, ask HA to…" via webhook** | ✅ Works | Meta AI custom action → HA webhook → Claude + MCP → TTS |
| **Battery sensor** | ✅ Works | Required fixing an Android 13+ broadcast registration bug that silently dropped all events |
| **Connected sensor** | ✅ Works | Uses Bluetooth ACL events — mwdat's session state is useless here |
| **Worn sensor** | ❌ Unavailable | mwdat v0.4.0 has no worn detection API. The code is there. The SDK is not cooperating. |
| **Camera MJPEG stream** | ❌ Non-functional | `PhotoData` in mwdat v0.4.0 is an opaque interface with no `toByteArray()`. We call `capturePhoto()` and return null. With aplomb. |
| **Glasses mic → HA Assist** | ❌ Not yet | mwdat v0.4.0 exposes no public audio stream. Phone mic is the fallback for Claude queries. |

The short version: notifications work great, Claude voice control works great, sensors mostly work, camera is a stub, and the mwdat SDK is holding everything else hostage until Meta finishes the API.

---

## How the "Ask HA" flow works

```
Option A — Tap "Ask HA" in the persistent notification:
  Pull down notification shade ──tap──► Android SpeechRecognizer (phone mic)
      → transcribed text
      → Claude API (haiku-4-5) with your HA MCP tools
      → Claude calls HassTurnOn / HassLightSet / HassGetState / etc.
      → Claude's response → Android TTS → glasses speakers (Bluetooth A2DP)

Option B — "Hey Meta, ask home assistant to turn on Jeff's office light":
  Ray-Ban glasses ──BT──► Meta AI (cloud)
      → your Meta AI custom action triggers
      → POST https://your-ha.nabu.casa/api/webhook/raybans_ask_<device_id>
      → HA fires raybans_meta_ask event on the event bus
      → Android bridge receives it via the existing WebSocket connection
      → Claude API + HA MCP tools → TTS response on glasses
```

Option B is the glasses-native experience. Option A is for when you're too close to your phone to feel like saying "Hey Meta." Both use Claude with full access to your HA MCP server.

---

## Architecture

```
┌───────────────────────────────────────────────────────────┐
│                       Android Phone                        │
│                                                            │
│  GlassesBridgeService (foreground)                         │
│  ├── MetaGlassesManager     mwdat SDK (connection mgmt)    │◄──BT──► Ray-Ban Meta Gen 2
│  ├── BatteryMonitor         BT ACL + battery broadcasts    │         (mic†, speakers,
│  ├── TtsPlayer              Android TTS → A2DP             │          camera†, battery)
│  ├── PhoneMicCapture        SpeechRecognizer (phone mic)   │
│  ├── CameraStreamServer     NanoHTTPD MJPEG (stub†)        │──LAN──► HA Camera entity
│  ├── HaWebSocketClient      HA WS auth + event subscr.    │◄──WS──► HA WebSocket API
│  ├── HaApiClient            HA event firing (sensor push)  │
│  ├── McpClient              HA MCP HTTP (tools/list+call)  │──────► HA /api/mcp
│  └── ClaudeVoiceAssist      Claude API agentic tool loop   │──────► api.anthropic.com
└───────────────────────────────────────────────────────────┘

† stub: mwdat v0.4.0 doesn't expose these publicly yet
```

**Key design decisions:**
- No MQTT. HA native WebSocket + REST. Works remotely via Nabu Casa.
- Sensors use HA event bus (Android fires `raybans_meta_sensor` → component calls `async_write_ha_state`). Direct `/api/states/` REST pushes were tried first and abandoned when we discovered HA generates entity IDs that don't match human intuition.
- Claude is the voice AI because HA Assist needs the glasses mic stream, which mwdat doesn't provide. Claude also handles ambiguous commands better than "I didn't understand that."
- The connected sensor uses Bluetooth ACL broadcasts, not mwdat session state. mwdat's DeviceSession goes `STOPPED` almost immediately; turns out that's the SDK being dramatic, not the glasses actually disconnecting.

---

## Prerequisites

- **Home Assistant** 2024.x+ with the **MCP Server integration** enabled (`Settings → Devices & Services → Add Integration → MCP Server`)
- **Android phone** Android 10+ (API 29+), paired to glasses via the Meta View app
- **Ray-Ban Meta Gen 2** glasses
- **Meta Wearables Device Access Toolkit (mwdat)** SDK access — [apply at Meta for Developers](https://developers.facebook.com/docs/wearables). Budget a few days for approval.
- **Anthropic API key** — [console.anthropic.com](https://console.anthropic.com)
- **GitHub PAT** with `read:packages` scope (to download mwdat from GitHub Packages)

---

## Setup

### 1. HA Custom Integration

Copy the integration directory into your HA config:

```
<ha-config>/custom_components/raybans_meta/   ← copy this whole folder
```

Restart Home Assistant, then **Settings → Devices & Services → Add Integration → Ray-Ban Meta**.

| Field | Value |
|---|---|
| Device ID | Your label, e.g. `wayfarer`. Appears in entity IDs. |
| MJPEG URL | `http://<phone-LAN-IP>:8080` (optional; camera doesn't work yet anyway) |

Entities created:

| Entity | Status |
|---|---|
| `sensor.ray_ban_meta_{id}_battery` | ✅ Updates on BT battery events |
| `binary_sensor.ray_ban_meta_{id}_connected` | ✅ Tracks BT connection |
| `binary_sensor.ray_ban_meta_{id}_worn` | ❌ Always Unavailable (SDK limitation) |
| `camera.ray_ban_meta_{id}` | ❌ No frames (SDK limitation) |
| `notify.raybans_{id}` | ✅ Works great |

### 2. Long-Lived Access Token

HA **Profile → Security → Long-Lived Access Tokens → Create Token**. This same token authenticates both the WebSocket connection and the MCP server calls.

### 3. Android App

#### Configure mwdat credentials

```bash
cp android-bridge/local.properties.example android-bridge/local.properties
```

Edit `local.properties`:
```properties
sdk.dir=/path/to/Android/Sdk
github.username=YOUR_GITHUB_USERNAME
github.token=YOUR_GITHUB_PAT
```

Also add your mwdat app credentials to `AndroidManifest.xml` (get these from the Meta developer portal after SDK approval):
```xml
<meta-data android:name="com.meta.wearable.mwdat.APPLICATION_ID" android:value="YOUR_APP_ID" />
<meta-data android:name="com.meta.wearable.mwdat.CLIENT_TOKEN" android:value="YOUR_CLIENT_TOKEN" />
```

#### Build and install

```bash
cd android-bridge
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

#### Configure the app

Open **Ray-Ban HA Bridge** and fill in all fields:

| Field | Example |
|---|---|
| HA URL | `https://abc123.ui.nabu.casa` |
| Long-lived access token | The one you just created |
| Device ID | `wayfarer` (matches HA config) |
| MJPEG port | `8080` |
| Anthropic API key | `sk-ant-api03-…` |
| HA MCP URL | `https://abc123.ui.nabu.casa/api/mcp` |

Tap **Connect**. A persistent notification appears. You're live.

### 4. "Hey Meta" → HA (optional but the whole point)

When the integration loads, HA logs the full webhook URL. Find it in **Settings → System → Logs** or look for `raybans_ask_` in the log output. It'll be:

```
POST https://your-ha.nabu.casa/api/webhook/raybans_ask_<device_id>
Body: {"text": "your command here"}
```

Register this as a Meta AI custom action in the [Meta AI developer portal](https://developers.meta.com/). Set the trigger phrase to something like "ask home assistant." After setup:

> "Hey Meta, ask home assistant to turn on Jeff's office light"

Meta AI hits the webhook → HA fires an event → Android bridge picks it up → Claude calls the HA MCP tool → glasses speak the response. It's a lot of hops but it works.

---

## Usage

### Sending notifications

```yaml
service: notify.raybans_wayfarer
data:
  message: "Your laundry is done"
```

Works from automations, scripts, the developer tools — anywhere HA can send a notification.

### Voice control

Pull down the notification shade, tap **Ask HA**, speak your request. Claude has access to your complete HA tool set via MCP and can handle chained commands, queries, and anything else HA exposes.

Or just use "Hey Meta, ask home assistant to [anything]" if you set up the webhook.

---

## Why Things Don't Work

**Glasses microphone** — `getMicAudioStream()` returns `emptyFlow()`. mwdat v0.4.0 has the `StreamSession` object but audio capture is not exposed publicly. The code for HA Assist integration is fully written (`AssistPipelineClient.kt`) and will work the moment the SDK provides audio frames. Until then, phone mic it is.

**Camera stream** — `StreamSession.capturePhoto()` returns `PhotoData`. `PhotoData` is an interface with zero public methods for extracting bytes. The NanoHTTPD server starts on port 8080, the `/stream` and `/snapshot` endpoints exist, and every single request to them returns a 503. This will be fixed when Meta ships a concrete `PhotoData` implementation.

**Worn detection** — Not in the SDK. `MetaGlassesManager` has `onWornStateChanged()` in the listener interface, ready and waiting. mwdat has not called it once. The entity shows Unavailable, which is at least truthful.

**mwdat DeviceSession going STOPPED immediately** — This is the SDK failing to establish its own application-level session, probably due to app registration state. It doesn't affect Bluetooth audio, battery reporting, or anything the user actually cares about. We use Bluetooth ACL broadcasts for connection state and ignore mwdat's opinion on the matter.

---

## Roadmap

- [ ] Camera frames (blocked on `PhotoData.toByteArray()` in a future mwdat release)
- [ ] Glasses mic audio (blocked on public audio stream in mwdat)
- [ ] Physical glasses button as push-to-talk (mwdat gesture API, not yet public)
- [ ] Worn detection via accelerometer heuristic
- [ ] Wake word activation via Porcupine

---

## License

MIT. Use it, break it, fix it, complain about mwdat in the issues.
