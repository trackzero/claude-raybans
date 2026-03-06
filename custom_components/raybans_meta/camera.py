"""Camera platform for Ray-Ban Meta — MJPEG stream from Android bridge."""
from __future__ import annotations

import asyncio
import logging

import aiohttp

from homeassistant.components.camera import Camera, CameraEntityFeature
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.entity import DeviceInfo
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import CONF_DEVICE_ID, CONF_MJPEG_URL, DOMAIN, SUFFIX_CAMERA

_LOGGER = logging.getLogger(__name__)

# Timeout for fetching a single JPEG snapshot from the Android server
_SNAPSHOT_TIMEOUT = aiohttp.ClientTimeout(total=5)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    device_id = entry.data[CONF_DEVICE_ID]
    mjpeg_url = entry.data.get(CONF_MJPEG_URL, "")
    if mjpeg_url:
        async_add_entities([RayBanCamera(hass, device_id, entry.entry_id, mjpeg_url)])


class RayBanCamera(Camera):
    """Camera entity that proxies the MJPEG stream from the Android bridge app.

    The Android app runs a NanoHTTPD server that exposes:
      GET /stream   → multipart/x-mixed-replace MJPEG stream
      GET /snapshot → single JPEG frame

    async_camera_image() grabs a snapshot frame; the frontend uses the
    stream URL directly for live MJPEG playback via stream_source().
    """

    _attr_has_entity_name = True
    _attr_name = "Camera"
    _attr_icon = "mdi:camera"
    _attr_supported_features = CameraEntityFeature(0)

    def __init__(
        self,
        hass: HomeAssistant,
        device_id: str,
        entry_id: str,
        mjpeg_url: str,
    ) -> None:
        super().__init__()
        self._hass = hass
        self._device_id = device_id
        self._mjpeg_url = mjpeg_url.rstrip("/")
        self._attr_unique_id = f"{DOMAIN}_{device_id}_{SUFFIX_CAMERA}"
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, device_id)},
            name=f"Ray-Ban Meta ({device_id})",
            manufacturer="Meta",
            model="Ray-Ban Meta Gen 2",
        )

    async def async_camera_image(
        self, width: int | None = None, height: int | None = None
    ) -> bytes | None:
        """Fetch a single JPEG frame from the Android snapshot endpoint."""
        snapshot_url = f"{self._mjpeg_url}/snapshot"
        session = async_get_clientsession(self._hass)
        try:
            async with session.get(snapshot_url, timeout=_SNAPSHOT_TIMEOUT) as resp:
                if resp.status == 200:
                    return await resp.read()
                _LOGGER.warning(
                    "Snapshot request to %s returned HTTP %s", snapshot_url, resp.status
                )
        except asyncio.TimeoutError:
            _LOGGER.debug("Snapshot request to %s timed out", snapshot_url)
        except aiohttp.ClientError as exc:
            _LOGGER.debug("Snapshot request error: %s", exc)
        return None

    async def stream_source(self) -> str | None:
        """Return the MJPEG stream URL for frontend playback."""
        return f"{self._mjpeg_url}/stream"
