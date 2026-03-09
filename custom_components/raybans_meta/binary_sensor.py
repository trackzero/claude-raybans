"""Binary sensor platform for Ray-Ban Meta — worn state and BT connection."""
from __future__ import annotations

from homeassistant.components.binary_sensor import (
    BinarySensorDeviceClass,
    BinarySensorEntity,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity import DeviceInfo
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.restore_state import RestoreEntity

from .const import CONF_DEVICE_ID, DOMAIN, SUFFIX_CONNECTED, SUFFIX_WORN


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    device_id = entry.data[CONF_DEVICE_ID]
    worn = RayBanWornSensor(device_id, entry.entry_id)
    connected = RayBanConnectedSensor(device_id, entry.entry_id)
    async_add_entities([worn, connected])
    entities = hass.data[DOMAIN][entry.entry_id]["entities"]
    entities["worn"] = worn
    entities["connected"] = connected


class _RayBanBinarySensor(RestoreEntity, BinarySensorEntity):
    """Base class for Ray-Ban binary sensors (state pushed via REST API)."""

    _attr_has_entity_name = True

    def __init__(self, device_id: str, suffix: str) -> None:
        self._device_id = device_id
        self._attr_unique_id = f"{DOMAIN}_{device_id}_{suffix}"
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, device_id)},
            name=f"Ray-Ban Meta ({device_id})",
            manufacturer="Meta",
            model="Ray-Ban Meta Gen 2",
        )

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        if (last_state := await self.async_get_last_state()) is not None:
            self._attr_is_on = last_state.state == "on"

    def handle_update(self, value) -> None:
        """Called by __init__.py when a raybans_meta_sensor event arrives."""
        self._attr_is_on = value == "on"
        self.async_write_ha_state()

    @property
    def should_poll(self) -> bool:
        return False


class RayBanWornSensor(_RayBanBinarySensor):
    """Indicates whether the glasses are currently being worn.

    State pushed by Android bridge:
      POST /api/states/binary_sensor.raybans_worn_{device_id}
      Body: {"state": "on"} or {"state": "off"}
    """

    _attr_device_class = BinarySensorDeviceClass.OCCUPANCY
    _attr_name = "Worn"
    _attr_icon = "mdi:glasses"

    def __init__(self, device_id: str, entry_id: str) -> None:
        super().__init__(device_id, SUFFIX_WORN)


class RayBanConnectedSensor(_RayBanBinarySensor):
    """Indicates whether the glasses are Bluetooth-connected to the phone.

    State pushed by Android bridge:
      POST /api/states/binary_sensor.raybans_connected_{device_id}
      Body: {"state": "on"} or {"state": "off"}
    """

    _attr_device_class = BinarySensorDeviceClass.CONNECTIVITY
    _attr_name = "Connected"
    _attr_icon = "mdi:bluetooth-connect"

    def __init__(self, device_id: str, entry_id: str) -> None:
        super().__init__(device_id, SUFFIX_CONNECTED)
