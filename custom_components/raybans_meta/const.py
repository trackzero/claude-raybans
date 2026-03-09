"""Constants for the Ray-Ban Meta integration."""

DOMAIN = "raybans_meta"

# Config entry keys
CONF_DEVICE_ID = "device_id"
CONF_MJPEG_URL = "mjpeg_url"

# HA event fired by notify platform; Android WS client listens for this
EVENT_NOTIFY = "raybans_meta_notify"

# HA event fired by Android bridge to push sensor state updates
EVENT_SENSOR = "raybans_meta_sensor"

# HA event fired by the webhook when Meta AI calls the ask endpoint
EVENT_ASK = "raybans_meta_ask"

# Platforms
PLATFORMS = ["sensor", "binary_sensor", "camera", "notify"]

# Entity unique ID suffixes
SUFFIX_BATTERY = "battery"
SUFFIX_WORN = "worn"
SUFFIX_CONNECTED = "connected"
SUFFIX_CAMERA = "camera"

# State push: Android POSTs to /api/states/<entity_id>
# Entity IDs follow pattern: domain.raybans_{suffix}_{device_id}
def entity_id_battery(device_id: str) -> str:
    return f"sensor.raybans_{SUFFIX_BATTERY}_{device_id}"

def entity_id_worn(device_id: str) -> str:
    return f"binary_sensor.raybans_{SUFFIX_WORN}_{device_id}"

def entity_id_connected(device_id: str) -> str:
    return f"binary_sensor.raybans_{SUFFIX_CONNECTED}_{device_id}"
