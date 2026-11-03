"""Say where each enabled service's state comes from.

The runtime reports, per service, whether it is enabled and which layer decided
that. The layers, lowest to highest: the image default, services.enabled in the
config file, a choice saved on the data volume (made in the Console or through
the API), a LOCALCLOUD_ENABLE_<ID> variable, and LOCALCLOUD_SERVICES, which the
CLI sets from --services. The command line therefore wins over a saved choice,
and a saved choice wins over the config file."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

# Highest rank first: the order the rows are printed in.
_RANK = ("profile-env", "flag-env", "persisted", "yaml", "default")


def _label(source: str | None, data_volume: str, config_path: str | None) -> str:
    if source == "profile-env":
        return "--services"
    if source == "flag-env":
        return "LOCALCLOUD_ENABLE_* variable"
    if source == "persisted":
        return f"saved setting (data volume {data_volume})"
    if source == "yaml":
        name = Path(config_path).name if config_path else "config file"
        return f"services.enabled ({name})"
    if source == "default":
        return "image default"
    return source or "unknown"


def describe_service_sources(
    states: Sequence[Mapping[str, Any]] | None,
    *,
    data_volume: str,
    config_path: str | None,
) -> dict[str, str]:
    """Rows for the enabled services' sources and for saved settings that a
    launch-time choice overrides. Empty when the runtime did not say."""
    if not states:
        return {}
    by_source: dict[str, list[str]] = {}
    for state in states:
        if state.get("enabled") and state.get("source"):
            by_source.setdefault(str(state["source"]), []).append(str(state["id"]))
    rows: dict[str, str] = {}
    ordered = [name for name in _RANK if name in by_source] + sorted(
        name for name in by_source if name not in _RANK
    )
    if ordered:
        rows["service_sources"] = "; ".join(
            f"{_label(name, data_volume, config_path)}: "
            + ", ".join(sorted(by_source[name]))
            for name in ordered
        )
    overridden: dict[str, list[str]] = {}
    for state in states:
        if "overridden_saved_enabled" in state:
            word = "enabled" if state["overridden_saved_enabled"] else "disabled"
            overridden.setdefault(
                _label(state.get("source"), data_volume, config_path), []
            ).append(f"{state['id']} ({word})")
    if overridden:
        rows["saved_settings"] = "; ".join(
            "not applied, " + ", ".join(sorted(items)) + f" overridden by {selector}"
            for selector, items in overridden.items()
        ) + "; a start without that choice restores them"
    return rows


def unpublished_services(
    states: Sequence[Mapping[str, Any]] | None,
    planned: Sequence[str],
    *,
    data_volume: str,
    config_path: str | None,
) -> str | None:
    """A warning for services that are enabled although the runtime was created
    to publish only the ports of the `planned` ones, or None."""
    if not states:
        return None
    extra = sorted(
        (state for state in states if state.get("enabled") and state["id"] not in planned),
        key=lambda state: state["id"],
    )
    if not extra:
        return None
    detail = ", ".join(
        f"{state['id']} ({_label(state.get('source'), data_volume, config_path)})"
        for state in extra
    )
    return (
        f"Enabled but not published by this runtime: {detail}. The runtime publishes "
        f"only the ports of the services it was started with ({', '.join(planned)}); "
        "choose the set with --services, or disable the extras in the Console."
    )
