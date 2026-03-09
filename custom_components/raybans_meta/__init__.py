"""Ray-Ban Meta Glasses — Home Assistant integration."""
from __future__ import annotations

import logging

from homeassistant.components.webhook import (
    async_register as webhook_register,
    async_unregister as webhook_unregister,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import Event, HomeAssistant, callback
from homeassistant.helpers.network import get_url

from .const import CONF_DEVICE_ID, DOMAIN, EVENT_ASK, EVENT_SENSOR, PLATFORMS

_LOGGER = logging.getLogger(__name__)


def _webhook_id(device_id: str) -> str:
    return f"raybans_ask_{device_id}"


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Set up Ray-Ban Meta from a config entry."""
    hass.data.setdefault(DOMAIN, {})
    hass.data[DOMAIN][entry.entry_id] = {"config": entry.data, "entities": {}}

    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)

    device_id = entry.data[CONF_DEVICE_ID]

    # --- sensor state push via HA event bus ---
    @callback
    def _handle_sensor_event(event: Event) -> None:
        if event.data.get("device_id") != device_id:
            return
        entities = hass.data[DOMAIN][entry.entry_id].get("entities", {})
        sensor_type = event.data.get("type")
        value = event.data.get("value")
        if entity := entities.get(sensor_type):
            entity.handle_update(value)
        else:
            _LOGGER.debug("raybans_meta_sensor: unknown type=%s", sensor_type)

    unsub_sensor = hass.bus.async_listen(EVENT_SENSOR, _handle_sensor_event)
    hass.data[DOMAIN][entry.entry_id]["unsub_sensor"] = unsub_sensor

    # --- Meta AI webhook → raybans_meta_ask event ---
    # POST https://<ha-url>/api/webhook/raybans_ask_<device_id>
    # Body: {"text": "turn on jeff's office light"}
    async def _handle_ask_webhook(hass: HomeAssistant, webhook_id: str, request):  # noqa: ARG001
        try:
            data = await request.json()
        except Exception:
            data = {}
        text = data.get("text", "").strip()
        if text:
            _LOGGER.debug("Ask webhook: text=%s", text)
            hass.bus.async_fire(EVENT_ASK, {"device_id": device_id, "text": text})

    webhook_id = _webhook_id(device_id)
    webhook_register(
        hass,
        DOMAIN,
        f"RayBan Ask ({device_id})",
        webhook_id,
        _handle_ask_webhook,
        allowed_methods=["POST"],
    )

    try:
        ha_url = get_url(hass, prefer_external=True)
        _LOGGER.info(
            "Ray-Ban Ask webhook: POST %s/api/webhook/%s  body: {\"text\": \"...\"}",
            ha_url,
            webhook_id,
        )
    except Exception:
        _LOGGER.info("Ray-Ban Ask webhook registered as: raybans_ask_%s", device_id)

    hass.data[DOMAIN][entry.entry_id]["webhook_id"] = webhook_id

    _LOGGER.debug("Ray-Ban Meta set up for device_id=%s", device_id)
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Unload a config entry."""
    entry_data = hass.data[DOMAIN].get(entry.entry_id, {})
    if unsub := entry_data.get("unsub_sensor"):
        unsub()
    if wid := entry_data.get("webhook_id"):
        webhook_unregister(hass, wid)

    unload_ok = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    if unload_ok:
        hass.data[DOMAIN].pop(entry.entry_id, None)
    return unload_ok
