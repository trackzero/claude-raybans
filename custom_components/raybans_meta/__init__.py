"""Ray-Ban Meta Glasses — Home Assistant integration."""
from __future__ import annotations

import logging

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import Event, HomeAssistant, callback

from .const import CONF_DEVICE_ID, DOMAIN, EVENT_SENSOR, PLATFORMS

_LOGGER = logging.getLogger(__name__)


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Set up Ray-Ban Meta from a config entry."""
    hass.data.setdefault(DOMAIN, {})
    # Store a mutable dict; platforms will inject entity references into ["entities"]
    hass.data[DOMAIN][entry.entry_id] = {"config": entry.data, "entities": {}}

    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)

    device_id = entry.data[CONF_DEVICE_ID]

    @callback
    def _handle_sensor_event(event: Event) -> None:
        """Dispatch raybans_meta_sensor events to the matching entity."""
        if event.data.get("device_id") != device_id:
            return
        entities = hass.data[DOMAIN][entry.entry_id].get("entities", {})
        sensor_type = event.data.get("type")
        value = event.data.get("value")
        if entity := entities.get(sensor_type):
            entity.handle_update(value)
        else:
            _LOGGER.debug("raybans_meta_sensor: unknown type=%s", sensor_type)

    unsub = hass.bus.async_listen(EVENT_SENSOR, _handle_sensor_event)
    hass.data[DOMAIN][entry.entry_id]["unsub_sensor"] = unsub

    _LOGGER.debug("Ray-Ban Meta set up for device_id=%s", device_id)
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Unload a config entry."""
    entry_data = hass.data[DOMAIN].get(entry.entry_id, {})
    if unsub := entry_data.get("unsub_sensor"):
        unsub()

    unload_ok = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    if unload_ok:
        hass.data[DOMAIN].pop(entry.entry_id, None)
    return unload_ok
