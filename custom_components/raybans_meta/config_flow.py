"""Config flow for Ray-Ban Meta integration."""
from __future__ import annotations

import re
from typing import Any

import voluptuous as vol

from homeassistant import config_entries
from homeassistant.data_entry_flow import FlowResult

from .const import CONF_DEVICE_ID, CONF_MJPEG_URL, DOMAIN

_DEVICE_ID_RE = re.compile(r"^[A-Za-z0-9_]+$")

STEP_USER_DATA_SCHEMA = vol.Schema(
    {
        vol.Required(CONF_DEVICE_ID): str,
        vol.Optional(CONF_MJPEG_URL, default=""): str,
    }
)


class RayBanMetaConfigFlow(config_entries.ConfigFlow, domain=DOMAIN):
    """Handle a config flow for Ray-Ban Meta."""

    VERSION = 1

    async def async_step_user(
        self, user_input: dict[str, Any] | None = None
    ) -> FlowResult:
        errors: dict[str, str] = {}

        if user_input is not None:
            device_id = user_input[CONF_DEVICE_ID].strip()

            if not _DEVICE_ID_RE.match(device_id):
                errors[CONF_DEVICE_ID] = "invalid_device_id"
            else:
                # Prevent duplicate entries for the same device_id
                await self.async_set_unique_id(device_id)
                self._abort_if_unique_id_configured()

                return self.async_create_entry(
                    title=f"Ray-Ban Meta ({device_id})",
                    data={
                        CONF_DEVICE_ID: device_id,
                        CONF_MJPEG_URL: user_input.get(CONF_MJPEG_URL, "").strip(),
                    },
                )

        return self.async_show_form(
            step_id="user",
            data_schema=STEP_USER_DATA_SCHEMA,
            errors=errors,
        )
