"""Sensor platform for Ray-Ban Meta — battery level."""
from __future__ import annotations

from homeassistant.components.sensor import (
    SensorDeviceClass,
    SensorEntity,
    SensorStateClass,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import PERCENTAGE
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity import DeviceInfo
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.restore_state import RestoreEntity

from .const import CONF_DEVICE_ID, DOMAIN, SUFFIX_BATTERY


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    device_id = entry.data[CONF_DEVICE_ID]
    entity = RayBanBatterySensor(device_id, entry.entry_id)
    async_add_entities([entity])
    hass.data[DOMAIN][entry.entry_id]["entities"]["battery"] = entity


class RayBanBatterySensor(RestoreEntity, SensorEntity):
    """Battery level sensor for Ray-Ban Meta glasses.

    State is pushed by the Android bridge app via the HA REST API:
      POST /api/states/sensor.raybans_battery_{device_id}
      Body: {"state": 85, "attributes": {"unit_of_measurement": "%", "device_class": "battery"}}

    RestoreEntity ensures the last known state survives HA restarts.
    """

    _attr_device_class = SensorDeviceClass.BATTERY
    _attr_state_class = SensorStateClass.MEASUREMENT
    _attr_native_unit_of_measurement = PERCENTAGE
    _attr_has_entity_name = True
    _attr_name = "Battery"

    def __init__(self, device_id: str, entry_id: str) -> None:
        self._device_id = device_id
        self._attr_unique_id = f"{DOMAIN}_{device_id}_{SUFFIX_BATTERY}"
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, device_id)},
            name=f"Ray-Ban Meta ({device_id})",
            manufacturer="Meta",
            model="Ray-Ban Meta Gen 2",
        )

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        # Restore last known state across restarts
        if (last_state := await self.async_get_last_state()) is not None:
            try:
                self._attr_native_value = int(last_state.state)
            except (ValueError, TypeError):
                self._attr_native_value = None

    def handle_update(self, value) -> None:
        """Called by __init__.py when a raybans_meta_sensor event arrives."""
        try:
            self._attr_native_value = int(value)
        except (ValueError, TypeError):
            return
        self.async_write_ha_state()

    @property
    def should_poll(self) -> bool:
        return False
