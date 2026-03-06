"""Notify platform for Ray-Ban Meta — fires HA event consumed by Android bridge."""
from __future__ import annotations

import logging
from typing import Any

from homeassistant.components.notify import BaseNotificationService, NotifyEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity import DeviceInfo
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import CONF_DEVICE_ID, DOMAIN, EVENT_NOTIFY

_LOGGER = logging.getLogger(__name__)


# --- Legacy notify platform (notify.raybans_<device_id>) ---

async def async_get_service(
    hass: HomeAssistant,
    config: dict[str, Any],
    discovery_info: dict[str, Any] | None = None,
) -> RayBanLegacyNotify | None:
    """Return the legacy notification service (registered as notify.<domain>)."""
    if discovery_info is None:
        return None
    return RayBanLegacyNotify(hass, discovery_info[CONF_DEVICE_ID])


class RayBanLegacyNotify(BaseNotificationService):
    """Notify service that fires a HA event.

    Android bridge subscribes to the ``raybans_meta_notify`` event type
    on the HA WebSocket. When the event fires, Android reads the ``text``
    payload and plays it as TTS through the glasses speakers.

    Usage (HA Developer Tools → Services):
      service: notify.raybans_<device_id>
      data:
        message: "Hello from Home Assistant"
    """

    def __init__(self, hass: HomeAssistant, device_id: str) -> None:
        self.hass = hass
        self._device_id = device_id

    def send_message(self, message: str = "", **kwargs: Any) -> None:  # type: ignore[override]
        """Fire the notify event (sync shim — HA calls this on executor thread)."""
        self.hass.bus.fire(
            EVENT_NOTIFY,
            {"text": message, "device_id": self._device_id, **kwargs},
        )
        _LOGGER.debug("Fired %s event: text=%r device_id=%s", EVENT_NOTIFY, message, self._device_id)


# --- Modern entity-based notify (HA 2024.x+) ---

async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    device_id = entry.data[CONF_DEVICE_ID]
    async_add_entities([RayBanNotifyEntity(hass, device_id, entry.entry_id)])


class RayBanNotifyEntity(NotifyEntity):
    """Entity-based notify for Ray-Ban Meta glasses."""

    _attr_has_entity_name = True
    _attr_name = "Notify"
    _attr_icon = "mdi:glasses"

    def __init__(self, hass: HomeAssistant, device_id: str, entry_id: str) -> None:
        self._hass = hass
        self._device_id = device_id
        self._attr_unique_id = f"{DOMAIN}_{device_id}_notify"
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, device_id)},
            name=f"Ray-Ban Meta ({device_id})",
            manufacturer="Meta",
            model="Ray-Ban Meta Gen 2",
        )

    async def async_send_message(self, message: str, title: str | None = None, **kwargs: Any) -> None:
        """Fire the notify event so the Android bridge can play TTS on glasses."""
        self._hass.bus.async_fire(
            EVENT_NOTIFY,
            {"text": message, "title": title, "device_id": self._device_id, **kwargs},
        )
        _LOGGER.debug(
            "Fired %s: text=%r device_id=%s", EVENT_NOTIFY, message, self._device_id
        )
