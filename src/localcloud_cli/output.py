from __future__ import annotations

import json
import os
import random
import re
import shutil
import sys
import threading
import time
import unicodedata
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from typing import Any, Callable, Mapping, Sequence, TextIO

from . import __version__
from .errors import HostError

_ANSI = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
_MISSING = object()
_SPINNERS = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"
_CLOUD = (
    "       ╭────╮       ",
    "   ╭───╯    ╰───╮   ",
    " ╭──╯          ╰──╮ ",
    "╭╯                ╰╮",
    "╰──────────────────╯",
)
_CLOUD_PERIMETER_COORDS = (
    # Top arch (center to right)
    (9, 0), (10, 0), (11, 0), (12, 0),
    # Right curve
    (12, 1), (13, 1), (14, 1), (15, 1), (16, 1),
    (15, 2), (16, 2), (17, 2), (18, 2),
    (18, 3), (19, 3),
    # Bottom right
    (19, 4), (18, 4), (17, 4), (16, 4), (15, 4), (14, 4), (13, 4), (12, 4), (11, 4), (10, 4),
    # Bottom left
    (9, 4), (8, 4), (7, 4), (6, 4), (5, 4), (4, 4), (3, 4), (2, 4), (1, 4), (0, 4),
    # Left curve
    (0, 3), (1, 3),
    (1, 2), (2, 2), (3, 2), (4, 2),
    (3, 1), (4, 1), (5, 1), (6, 1), (7, 1),
    # Top arch (left to center)
    (7, 0), (8, 0),
)
_CLOUD_COORD_INDEX = {coord: idx for idx, coord in enumerate(_CLOUD_PERIMETER_COORDS)}
_GRADIENT_STOPS = (
    (66, 133, 244),
    (234, 67, 53),
    (251, 188, 4),
    (52, 168, 83),
    (66, 133, 244),
)
_PHASES = (0.0, 0.25, 0.5, 0.75)
_ANSI_256_RAMP = (33, 196, 220, 34, 33)
_ANSI_16_RAMP = (94, 91, 93, 92, 94)
_TRUECOLOR_SEMANTICS = {
    "processing": (34, 211, 238),
    "success": (52, 211, 153),
    "error": (248, 113, 113),
    "warning": (251, 191, 36),
    "label": (96, 165, 250),
    "url": (34, 211, 238),
    "muted": (107, 114, 128),
    "primary": (243, 244, 246),
    "g_blue": (66, 133, 244),
    "g_red": (234, 67, 53),
    "g_yellow": (251, 188, 4),
    "g_green": (52, 168, 83),
    "welcome": (255, 255, 255),
    "section_header": (34, 211, 238),
    "cmd_desc": (209, 213, 219),
    "ctx_config": (192, 132, 252),
}
_ANSI256_SEMANTICS = {
    "processing": 45,
    "success": 48,
    "error": 203,
    "warning": 220,
    "label": 75,
    "url": 45,
    "muted": 245,
    "primary": 255,
    "g_blue": 33,
    "g_red": 196,
    "g_yellow": 220,
    "g_green": 34,
    "welcome": 255,
    "section_header": 45,
    "cmd_desc": 250,
    "ctx_config": 141,
}
_ANSI16_SEMANTICS = {
    "processing": 96,
    "success": 92,
    "error": 91,
    "warning": 93,
    "label": 94,
    "url": 96,
    "muted": 90,
    "primary": 97,
    "g_blue": 94,
    "g_red": 91,
    "g_yellow": 93,
    "g_green": 92,
    "welcome": 97,
    "section_header": 96,
    "cmd_desc": 97,
    "ctx_config": 95,
}

class ColorMode(Enum):
    NONE = "none"
    ANSI16 = "ansi16"
    ANSI256 = "ansi256"
    TRUECOLOR = "truecolor"


@dataclass(frozen=True)
class TerminalCapabilities:
    interactive: bool
    color: ColorMode
    cursor: bool


@dataclass(frozen=True)
class FieldSpec:
    path: str
    label: str
    style: str = "primary"


@dataclass(frozen=True)
class PanelContext:
    data_volume: str
    project: str
    user: str
    services: str | Sequence[str]
    data: str
    config: str | None


_COMMON_FIELDS = (
    FieldSpec("status", "Status", "status"),
    FieldSpec("config", "Config", "muted"),
    FieldSpec("data_volume", "Data volume"),
    FieldSpec("origin", "Origin"),
    FieldSpec("project", "Project"),
    FieldSpec("user", "User"),
    FieldSpec("container.configured_image", "Image", "muted"),
    FieldSpec("container.image_status", "Image status", "status"),
    FieldSpec("container.url", "URL", "url"),
    FieldSpec("services", "Services"),
    FieldSpec("changed_fields", "Changed", "warning"),
    FieldSpec("reset_scope", "Reset scope", "warning"),
    FieldSpec("logs", "Logs", "muted"),
)
_DOCTOR_FIELDS = (
    FieldSpec("status", "Status", "status"),
    FieldSpec("docker", "Docker"),
    FieldSpec("cli_version", "CLI version", "muted"),
    FieldSpec("default_image", "Image", "image"),
    FieldSpec("legacy_resources", "Legacy resources", "warning"),
    FieldSpec("legacy_host_state", "Legacy host state", "warning"),
    FieldSpec("legacy_locks", "Legacy locks", "warning"),
    FieldSpec("warning", "Warning", "warning"),
)
_CLEANUP_FIELDS = (
    FieldSpec("status", "Status", "status"),
    FieldSpec("dry_run", "Dry run", "muted"),
    FieldSpec("docker_resources", "Docker resources", "warning"),
    FieldSpec("active_runtime_stale", "Active runtime stale", "warning"),
    FieldSpec("legacy_host_state", "Legacy host state", "warning"),
    FieldSpec("legacy_locks", "Legacy locks", "warning"),
    FieldSpec("failures", "Failures", "error"),
)
_CONSOLE_FIELDS = (
    FieldSpec("status", "Status", "status"),
    FieldSpec("data_volume", "Data volume"),
    FieldSpec("project", "Project"),
    FieldSpec("user", "User"),
    FieldSpec("url", "URL", "url"),
)
_EXTRA_COMMON = (
    FieldSpec("config", "Config", "muted"),
    FieldSpec("container.name", "Container", "muted"),
    FieldSpec("container.id", "Container ID", "muted"),
    FieldSpec("container.state", "State", "status"),
    FieldSpec("container.actual_image", "Actual image", "muted"),
    FieldSpec("data", "Data", "muted"),
    FieldSpec("network.name", "Network", "muted"),
    FieldSpec("mount.source", "Mounted volume", "muted"),
    FieldSpec("ownership", "Ownership", "muted"),
    FieldSpec("drift", "Drift", "warning"),
    FieldSpec("sdk_env", "SDK environment", "muted"),
    FieldSpec("mcp.command", "MCP command", "muted"),
    FieldSpec("mcp.args", "MCP arguments", "muted"),
    FieldSpec("mcp.direct_url", "MCP URL", "url"),
    FieldSpec("mcp.headers", "MCP headers", "muted"),
)

DEFAULT_FIELDS: Mapping[str, tuple[FieldSpec, ...]] = {
    "start": _COMMON_FIELDS,
    "restart": _COMMON_FIELDS,
    "reset": tuple(field for field in _COMMON_FIELDS if field.path != "logs"),
    "stop": (
        *tuple(
            field
            for field in _COMMON_FIELDS
            if field.path
            not in {"project", "user", "changed_fields", "reset_scope", "logs"}
        ),
        FieldSpec("container.name", "Container", "muted"),
        FieldSpec("container.id", "Container ID", "muted"),
    ),
    "status": tuple(field for field in _COMMON_FIELDS if field.path not in {"project", "user", "changed_fields", "reset_scope", "logs"}),
    "doctor": _DOCTOR_FIELDS,
    "cleanup": _CLEANUP_FIELDS,
    "console": _CONSOLE_FIELDS,
}
ALLOWED_FIELDS: Mapping[str, tuple[FieldSpec, ...]] = {
    command: (
        defaults
        if command in ("doctor", "cleanup")
        else tuple(dict.fromkeys((*defaults, *_EXTRA_COMMON)))
    )
    for command, defaults in DEFAULT_FIELDS.items()
}


def strip_ansi(value: str) -> str:
    return _ANSI.sub("", value)


def visible_width(value: str) -> int:
    return sum(_character_width(char) for char in strip_ansi(value))


def _character_width(char: str) -> int:
    if char in {"\n", "\r"} or unicodedata.combining(char):
        return 0
    if unicodedata.category(char) in {"Cf", "Cc"}:
        return 0
    return 2 if unicodedata.east_asian_width(char) in {"F", "W"} else 1


def _centered(text: str, width: int) -> str:
    """Pad `text` with spaces to center it within `width` visible columns.
    ANSI styling on `text` doesn't affect the padding math since
    `visible_width` already strips it."""
    slack = width - visible_width(text)
    pad = slack // 2
    return " " * pad + text + " " * (slack - pad)


def truncate_visible(value: str, width: int) -> str:
    plain = strip_ansi(value)
    if width <= 0:
        return ""
    if visible_width(plain) <= width:
        return plain
    if width == 1:
        return "…"
    budget = width - 1
    used = 0
    result: list[str] = []
    for char in plain:
        char_width = _character_width(char)
        if used + char_width > budget:
            break
        result.append(char)
        used += char_width
    return "".join(result) + "…"


def terminal_capabilities(stream: TextIO, environ: Mapping[str, str] | None = None) -> TerminalCapabilities:
    selected = os.environ if environ is None else environ
    interactive = bool(getattr(stream, "isatty", lambda: False)())
    dumb = selected.get("TERM", "").lower() == "dumb"
    cursor = interactive and not dumb
    if not interactive or dumb or selected.get("NO_COLOR") is not None:
        color = ColorMode.NONE
    elif "truecolor" in selected.get("COLORTERM", "").lower() or "24bit" in selected.get("COLORTERM", "").lower():
        color = ColorMode.TRUECOLOR
    elif "256color" in selected.get("TERM", "").lower():
        color = ColorMode.ANSI256
    else:
        color = ColorMode.ANSI16
    return TerminalCapabilities(interactive=interactive, color=color, cursor=cursor)


def _terminal_width(stream: TextIO) -> int:
    try:
        return os.get_terminal_size(stream.fileno()).columns
    except (AttributeError, OSError, ValueError):
        return shutil.get_terminal_size((80, 24)).columns


def style_text(value: str, role: str, color: ColorMode, *, bold: bool = False) -> str:
    if color is ColorMode.NONE:
        return value
    code = _semantic_code(role, color)
    prefix = "\x1b[1m" if bold else ""
    return f"{prefix}{code}{value}\x1b[0m"


def _semantic_code(role: str, color: ColorMode) -> str:
    role = role.lower()
    if color is ColorMode.TRUECOLOR:
        rgb = _TRUECOLOR_SEMANTICS.get(role, _TRUECOLOR_SEMANTICS["primary"])
        return f"\x1b[38;2;{rgb[0]};{rgb[1]};{rgb[2]}m"
    if color is ColorMode.ANSI256:
        code = _ANSI256_SEMANTICS.get(role, _ANSI256_SEMANTICS["primary"])
        return f"\x1b[38;5;{code}m"
    code = _ANSI16_SEMANTICS.get(role, _ANSI16_SEMANTICS["primary"])
    return f"\x1b[{code}m"


def parse_fields(values: Sequence[str] | None) -> list[str]:
    paths: list[str] = []
    for value in values or ():
        for item in value.split(","):
            path = item.strip()
            if not path:
                raise ValueError("Field paths must not be empty")
            if path not in paths:
                paths.append(path)
    return paths


def valid_field_paths(command: str) -> tuple[str, ...]:
    return tuple(field.path for field in ALLOWED_FIELDS.get(command, ()))


def validate_fields(command: str, requested: Sequence[str]) -> None:
    valid = set(valid_field_paths(command))
    invalid = [path for path in requested if path not in valid]
    if invalid:
        raise HostError(
            "invalid_output_field",
            f"Unsupported summary field for {command}: {', '.join(invalid)}",
            {"fields": invalid, "valid_fields": sorted(valid)},
        )


def render_summary(
    command: str,
    payload: Mapping[str, Any],
    requested: Sequence[str] = (),
    *,
    color: ColorMode = ColorMode.NONE,
) -> str:
    validate_fields(command, requested)
    allowed = {field.path: field for field in ALLOWED_FIELDS.get(command, ())}
    fields = [
        field
        for field in DEFAULT_FIELDS.get(command, ())
        if not (
            command == "stop"
            and payload.get("status") != "stopped"
            and field.path in {"container.name", "container.id"}
        )
    ]
    present = {field.path for field in fields}
    fields.extend(allowed[path] for path in requested if path not in present)
    resolved: list[tuple[FieldSpec, Any]] = []
    for field in fields:
        value = _resolve_path(payload, field.path)
        if value is _MISSING or value is None or value == [] or value == {}:
            continue
        if field.style == "status":
            value = _human_status(value)
        resolved.append((field, value))
    if not resolved:
        return ""
    label_width = max(len(field.label) for field, _ in resolved)
    lines: list[str] = []
    for field, value in resolved:
        label = style_text(field.label.ljust(label_width), "label", color, bold=True)
        value_text = _format_value(value)
        if field.style == "image":
            rendered_value = _render_image_value(value_text, color)
        else:
            role = _value_role(field, value)
            rendered_value = style_text(value_text, role, color)
        lines.append(f"{label}  {rendered_value}")
    return "\n".join(lines)


def _render_image_value(value: str, color: ColorMode) -> str:
    split = value.find(" (")
    if split == -1:
        return style_text(value, "primary", color)
    name, suffix = value[:split], value[split:]
    return style_text(name, "primary", color) + style_text(suffix, "muted", color)


def _resolve_path(payload: Mapping[str, Any], path: str) -> Any:
    current: Any = payload
    for part in path.split("."):
        if not isinstance(current, Mapping) or part not in current:
            return _MISSING
        current = current[part]
    return current


def _format_value(value: Any) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (list, tuple)):
        if all(not isinstance(item, (Mapping, list, tuple)) for item in value):
            return ", ".join(_format_value(item) for item in value)
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    if isinstance(value, Mapping):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    return str(value)


def _human_status(value: Any) -> Any:
    if not isinstance(value, str):
        return value
    normalized = value.strip().lower().replace("_", " ")
    if normalized in {
        "running",
        "ready",
        "started",
        "already running",
        "restarted",
        "reconfigured",
        "opened",
    }:
        return "Running"
    if normalized in {
        "stopped",
        "not running",
        "not created",
        "unhealthy",
        "absent",
        "not_created",
        "not_running",
    }:
        return "Not Running"
    return value


def _value_role(field: FieldSpec, value: Any) -> str:
    if field.style != "status":
        return field.style
    normalized = str(value).strip().lower().replace("_", " ")
    if normalized in {"running", "ready", "started", "already running", "restarted", "reconfigured", "opened", "ok"}:
        return "success"
    if normalized in {
        "stopped",
        "not running",
        "not created",
        "absent",
        "unhealthy",
    }:
        return "warning"
    if normalized in {"error", "failed"}:
        return "error"
    return "primary"


def render_json(value: Any, *, color: ColorMode = ColorMode.NONE) -> str:
    plain = json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False)
    if color is ColorMode.NONE:
        return plain
    token = re.compile(r'("(?:\\.|[^"\\])*")(?=\s*:)|("(?:\\.|[^"\\])*")|\b(true|false)\b|\b(null)\b|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)')

    def replace(match: re.Match[str]) -> str:
        value_text = match.group(0)
        if match.group(1) is not None:
            role = "label"
        elif match.group(2) is not None:
            role = "success"
        elif match.group(3) is not None:
            role = "warning"
        elif match.group(4) is not None:
            role = "muted"
        else:
            role = "processing"
        return style_text(value_text, role, color)

    return token.sub(replace, plain)


_CONCISE_ERROR_FIELDS = {
    "command",
    "config",
    "container",
    "container_id",
    "data",
    "data_volume",
    "docker_host",
    "field",
    "fields",
    "image",
    "network",
    "project",
    "resource",
    "state",
    "timeout_seconds",
    "url",
    "user",
    "value",
}


def render_error(error: HostError, *, color: ColorMode = ColorMode.NONE) -> str:
    heading = style_text(f"Error [{error.code}]", "error", color, bold=True)
    lines = [f"{heading} {error.message}"]
    for key, value in error.details.items():
        if key not in _CONCISE_ERROR_FIELDS or not isinstance(
            value, (str, int, float, bool)
        ):
            continue
        rendered = truncate_visible(_format_value(value).replace("\n", " "), 160)
        label = style_text(key.replace("_", " ").title(), "label", color, bold=True)
        lines.append(f"{label}: {style_text(rendered, 'muted', color)}")
    return "\n".join(lines)


def render_cloud(
    *,
    phase: float = 0.0,
    progress: float = 1.0,
    color: ColorMode = ColorMode.NONE,
) -> tuple[str, ...]:
    if color is ColorMode.NONE:
        return _CLOUD
    total_coords = len(_CLOUD_PERIMETER_COORDS)
    eased = 1 - (1 - min(1.0, max(0.0, progress))) ** 3
    sweep = (1 - eased) * 2.5
    shine_pos = (progress * 2.0) % 1.0
    shine_strength = (1 - eased) ** 1.5
    rendered: list[str] = []
    for y, line in enumerate(_CLOUD):
        pieces: list[str] = []
        for x, char in enumerate(line):
            if char == " ":
                pieces.append(char)
                continue
            idx = _CLOUD_COORD_INDEX.get((x, y), 0)
            base = idx / max(1, total_coords)
            position = (base + phase + sweep) % 1.0
            rgb = _gradient_rgb(position)
            distance = abs(position - shine_pos)
            distance = min(distance, 1 - distance)
            intensity = max(0.0, 1 - distance / 0.16) * shine_strength
            if intensity:
                rgb = tuple(round(channel + (255 - channel) * intensity) for channel in rgb)
            pieces.append(_gradient_escape(position, rgb, color) + char + "\x1b[0m")
        rendered.append("".join(pieces))
    return tuple(rendered)


def _gradient_rgb(position: float) -> tuple[int, int, int]:
    if not _GRADIENT_STOPS:
        return (0, 0, 0)
    last_index = max(0, len(_GRADIENT_STOPS) - 2)
    try:
        scaled = (position % 1.0) * (len(_GRADIENT_STOPS) - 1)
        index = min(last_index, int(scaled))
    except (TypeError, OverflowError, ValueError):
        index = 0
        fraction = 0.0
        left = _GRADIENT_STOPS[index]
        right = _GRADIENT_STOPS[index + 1]
        return (
            round((left[0] + right[0]) / 2),
            round((left[1] + right[1]) / 2),
            round((left[2] + right[2]) / 2),
        )
    fraction = scaled - index
    left = _GRADIENT_STOPS[index]
    right = _GRADIENT_STOPS[index + 1]
    return (
        round(left[0] + (right[0] - left[0]) * fraction),
        round(left[1] + (right[1] - left[1]) * fraction),
        round(left[2] + (right[2] - left[2]) * fraction),
    )


def _gradient_escape(position: float, rgb: tuple[int, int, int], color: ColorMode) -> str:
    if color is ColorMode.TRUECOLOR:
        return f"\x1b[38;2;{rgb[0]};{rgb[1]};{rgb[2]}m"
    ramp = _ANSI_256_RAMP if color is ColorMode.ANSI256 else _ANSI_16_RAMP
    index = min(len(ramp) - 1, round((position % 1.0) * (len(ramp) - 1)))
    return f"\x1b[38;5;{ramp[index]}m" if color is ColorMode.ANSI256 else f"\x1b[{ramp[index]}m"


_ALL_SERVICES_ROW1 = (
    ("storage", "Storage", "g_blue"),
    ("firestore", "Firestore", "g_blue"),
    ("pubsub", "Pub/Sub", "g_red"),
    ("bigquery", "BigQuery", "g_yellow"),
    ("secrets", "Secrets", "g_green"),
)
_ALL_SERVICES_ROW2 = (
    ("spanner", "Spanner", "g_blue"),
    ("cloudsql", "Cloud SQL", "g_blue"),
    ("tasks", "Tasks", "g_red"),
    ("logging", "Logging", "g_green"),
    ("dataproc", "Dataproc", "g_yellow"),
)


def _normalize_service(value: str) -> str:
    return value.strip().lower().replace("-", "").replace("_", "").replace(" ", "")


def _resolve_services_rows(
    services: str | Sequence[str],
) -> tuple[list[tuple[str, str, bool]], list[tuple[str, str, bool]]]:
    enabled: set[str] | None
    if isinstance(services, str):
        if services in {"default", "all", ""}:
            enabled = None
        else:
            enabled = {_normalize_service(s) for s in services.split(",") if s.strip()}
    else:
        service_list = list(services)
        if not service_list or service_list == ["default"]:
            enabled = None
        else:
            enabled = {_normalize_service(s) for s in service_list}
    if enabled is not None and "sql" in enabled:
        enabled = {"cloudsql" if item == "sql" else item for item in enabled}
    if enabled is not None and "secretmanager" in enabled:
        enabled = {"secrets" if item == "secretmanager" else item for item in enabled}

    def build_row(row: Sequence[tuple[str, str, str]]) -> list[tuple[str, str, bool]]:
        return [
            (name, role, enabled is None or key in enabled)
            for key, name, role in row
        ]

    return build_row(_ALL_SERVICES_ROW1), build_row(_ALL_SERVICES_ROW2)


def _format_services_row(services: Sequence[tuple[str, str, bool]], width: int, color: ColorMode) -> str:
    if not services:
        return " " * width
    items_plain = [f"● {name}" for name, _, _ in services]
    items_styled = [
        f"{style_text('●', role if enabled else 'muted', color)} "
        f"{name if enabled else style_text(name, 'muted', color)}"
        for name, role, enabled in services
    ]
    total_plain_len = sum(len(item) for item in items_plain)
    num_items = len(services)
    prefix = "  "
    available = width - len(prefix)
    if available >= total_plain_len + (num_items - 1) * 2:
        gap_size = (available - total_plain_len) // (num_items - 1) if num_items > 1 else 1
        rem = (available - total_plain_len) % (num_items - 1) if num_items > 1 else 0
        row_styled = prefix
        row_plain = prefix
        for i, (p, s) in enumerate(zip(items_plain, items_styled)):
            row_styled += s
            row_plain += p
            if i < num_items - 1:
                cur_gap = " " * (gap_size + (1 if i < rem else 0))
                row_styled += cur_gap
                row_plain += cur_gap
        pad = " " * max(0, width - visible_width(row_plain))
        return row_styled + pad
    elif available >= total_plain_len + (num_items - 1):
        row_styled = prefix
        row_plain = prefix
        for i, (p, s) in enumerate(zip(items_plain, items_styled)):
            row_styled += s
            row_plain += p
            if i < num_items - 1:
                row_styled += " "
                row_plain += " "
        pad = " " * max(0, width - visible_width(row_plain))
        return row_styled + pad
    else:
        fit_items_plain: list[str] = []
        fit_items_styled: list[str] = []
        cur_len = len(prefix)
        for p, s in zip(items_plain, items_styled):
            needed = len(p) + (1 if fit_items_plain else 0)
            if cur_len + needed <= width:
                fit_items_plain.append(p)
                fit_items_styled.append(s)
                cur_len += needed
            else:
                break
        row_styled = prefix + " ".join(fit_items_styled)
        row_plain = prefix + " ".join(fit_items_plain)
        pad = " " * max(0, width - visible_width(row_plain))
        return row_styled + pad


def _format_context_pair(
    label1: str,
    val1: str,
    role1: str,
    label2: str,
    val2: str,
    role2: str,
    width: int,
    color: ColorMode,
) -> str:
    part1_lbl = f"  {label1}: "
    part2_lbl = f" {label2}: "
    full_needed = visible_width(part1_lbl) + visible_width(val1) + visible_width(part2_lbl) + visible_width(val2)
    if width >= full_needed:
        gap = " " * (width - full_needed)
        return (
            style_text(part1_lbl, "muted", color)
            + style_text(val1, role1, color)
            + gap
            + style_text(part2_lbl, "muted", color)
            + style_text(val2, role2, color)
        )
    half1 = (width - 1) // 2
    half2 = width - half1
    v1_budget = max(1, half1 - visible_width(part1_lbl))
    v1_trunc = truncate_visible(val1, v1_budget)
    p1_plain = part1_lbl + v1_trunc
    p1_pad = " " * max(0, half1 - visible_width(p1_plain))
    p1_styled = style_text(part1_lbl, "muted", color) + style_text(v1_trunc, role1, color) + p1_pad
    v2_budget = max(1, half2 - visible_width(part2_lbl))
    v2_trunc = truncate_visible(val2, v2_budget)
    p2_plain = part2_lbl + v2_trunc
    p2_pad = " " * max(0, half2 - visible_width(p2_plain))
    p2_styled = style_text(part2_lbl, "muted", color) + style_text(v2_trunc, role2, color) + p2_pad
    return p1_styled + p2_styled


def _footer_tip(box_width: int, color: ColorMode) -> str:
    full_plain = " Tip: Run localcloud guide for AI agent workflows, or lc for the fast alias."
    if box_width >= visible_width(full_plain):
        pad = " " * (box_width - visible_width(full_plain))
        tip_lbl = style_text("Tip:", "section_header", color, bold=True)
        cmd1 = style_text("localcloud guide", "g_blue", color)
        cmd2 = style_text("lc", "g_yellow", color)
        return f" {tip_lbl} Run {cmd1} for AI agent workflows, or {cmd2} for the fast alias.{pad}"
    short_plain = " Tip: Run localcloud guide or lc"
    if box_width >= visible_width(short_plain):
        pad = " " * (box_width - visible_width(short_plain))
        tip_lbl = style_text("Tip:", "section_header", color, bold=True)
        cmd1 = style_text("localcloud guide", "g_blue", color)
        cmd2 = style_text("lc", "g_yellow", color)
        return f" {tip_lbl} Run {cmd1} or {cmd2}{pad}"
    tip_lbl = style_text("Tip:", "section_header", color, bold=True)
    body = truncate_visible("Run localcloud guide or lc", max(1, box_width - 6))
    pad = " " * max(0, box_width - 6 - visible_width(body))
    return f" {tip_lbl} {body}{pad}"


def render_panel(
    context: PanelContext,
    width: int,
    *,
    phase: float = 0.0,
    progress: float = 1.0,
    color: ColorMode = ColorMode.NONE,
) -> list[str]:
    box_width = min(100, max(4, width - 2))
    if box_width < visible_width(_CLOUD[0]) + 2:
        return _minimal_panel(context, box_width, color)
    if width >= 80:
        return _wide_panel(context, box_width, phase, progress, color)
    if width >= 50:
        return _stacked_panel(context, box_width, phase, progress, color)
    return _compact_panel(context, box_width, phase, progress, color)


def _border_title(box_width: int, color: ColorMode) -> str:
    title = f"── LocalCloud v{__version__} "
    inside = truncate_visible(title, box_width - 2)
    plain = "╭" + inside + "─" * max(0, box_width - 2 - visible_width(inside)) + "╮"
    return style_text(plain, "muted", color)


def _border_bottom(box_width: int, color: ColorMode, split: int | None = None) -> str:
    if split is None:
        plain = "╰" + "─" * max(0, box_width - 2) + "╯"
    else:
        plain = "╰" + "─" * split + "┴" + "─" * max(0, box_width - split - 3) + "╯"
    return style_text(plain, "muted", color)


def _wide_panel(context: PanelContext, box_width: int, phase: float, progress: float, color: ColorMode) -> list[str]:
    split_width = 26
    right_width = box_width - split_width - 3

    left_rows: list[str] = []
    left_rows.append(" " * split_width)
    left_rows.append(
        _centered(style_text("Welcome back!", "welcome", color, bold=True), split_width)
    )
    left_rows.append(" " * split_width)
    cloud = render_cloud(phase=phase, progress=progress, color=color)
    for line in cloud:
        left_rows.append(_centered(line, split_width))
    left_rows.append(" " * split_width)
    left_rows.append(
        _centered(
            style_text(truncate_visible(context.project, split_width - 2), "g_blue", color),
            split_width,
        )
    )
    left_rows.append(
        _centered(
            style_text(truncate_visible(context.data_volume, split_width - 2), "g_yellow", color),
            split_width,
        )
    )
    left_rows.append(
        _centered(
            style_text(truncate_visible(context.user, split_width - 2), "g_green", color),
            split_width,
        )
    )
    left_rows.append(" " * split_width)

    right_rows: list[str] = []
    s1_hdr = " Tips & Commands"
    right_rows.append(style_text(s1_hdr, "section_header", color, bold=True) + " " * max(0, right_width - visible_width(s1_hdr)))
    cmds = (
        ("localcloud start", "g_blue", "Start localcloud container."),
        ("localcloud stop", "g_red", "Stop localcloud container."),
        ("localcloud status", "g_green", "Health & Running status of localcloud"),
        ("eval $(lc env)", "g_yellow", "Export env. vars to redirect cloud service calls to localcloud."),
    )
    for cmd, role, desc in cmds:
        cmd_col_width = 21
        cmd_prefix = f"  {cmd}"
        pad_len = max(1, cmd_col_width - visible_width(cmd_prefix))
        cmd_col_plain = cmd_prefix + " " * pad_len
        desc_budget = max(1, right_width - visible_width(cmd_col_plain))
        desc_trunc = truncate_visible(desc, desc_budget)
        total_plain = cmd_col_plain + desc_trunc
        row_pad = " " * max(0, right_width - visible_width(total_plain))
        cmd_styled = f"  {style_text(cmd, role, color)}" + " " * pad_len
        desc_styled = style_text(desc_trunc, "cmd_desc", color)
        right_rows.append(cmd_styled + desc_styled + row_pad)
    right_rows.append(style_text("─" * right_width, "muted", color))
    s2_hdr = " Google Cloud Services"
    right_rows.append(style_text(s2_hdr, "section_header", color, bold=True) + " " * max(0, right_width - visible_width(s2_hdr)))
    srv_r1, srv_r2 = _resolve_services_rows(context.services)
    right_rows.append(_format_services_row(srv_r1, right_width, color))
    right_rows.append(_format_services_row(srv_r2, right_width, color))
    right_rows.append(style_text("─" * right_width, "muted", color))
    s3_hdr = "Config"
    right_rows.append(style_text(s3_hdr, "section_header", color, bold=True) + " " * max(0, right_width - visible_width(s3_hdr)))
    config_name = Path(context.config).name if context.config else "built-in defaults"
    right_rows.append(_format_context_pair("Data Volume", context.data_volume, "g_yellow", "Project", context.project, "g_blue", right_width, color))
    right_rows.append(_format_context_pair("User", context.user, "g_green", "Config", config_name, "ctx_config", right_width, color))

    border = style_text("│", "muted", color)
    lines = [_border_title(box_width, color)]
    for l, r in zip(left_rows, right_rows):
        lines.append(f"{border}{l}{border}{r}{border}")
    lines.append(_border_bottom(box_width, color, split_width))
    lines.append(_footer_tip(box_width, color))
    return lines


def _stacked_panel(context: PanelContext, box_width: int, phase: float, progress: float, color: ColorMode) -> list[str]:
    inside = box_width - 2
    border = style_text("│", "muted", color)
    lines = [_border_title(box_width, color)]
    lines.append(
        f"{border}{_centered(style_text('Welcome back!', 'welcome', color, bold=True), inside)}{border}"
    )
    for line in render_cloud(phase=phase, progress=progress, color=color):
        lines.append(f"{border}{_centered(line, inside)}{border}")
    lines.append(f"{border}{style_text('─' * inside, 'muted', color)}{border}")
    config_name = Path(context.config).name if context.config else "built-in defaults"
    lines.append(f"{border}{_format_context_pair('Data Volume', context.data_volume, 'g_yellow', 'Project', context.project, 'g_blue', inside, color)}{border}")
    lines.append(f"{border}{_format_context_pair('User', context.user, 'g_green', 'Config', config_name, 'ctx_config', inside, color)}{border}")
    lines.append(_border_bottom(box_width, color))
    lines.append(_footer_tip(box_width, color))
    return lines


def _compact_panel(context: PanelContext, box_width: int, phase: float, progress: float, color: ColorMode) -> list[str]:
    inside = box_width - 2
    border = style_text("│", "muted", color)
    lines = [_border_title(box_width, color)]
    for line in render_cloud(phase=phase, progress=progress, color=color):
        lines.append(f"{border}{_centered(line, inside)}{border}")
    v_trunc = truncate_visible(context.data_volume, max(1, inside - 12))
    v_plain = f"  Volume: {v_trunc}"
    v_pad = " " * max(0, inside - visible_width(v_plain))
    lines.append(f"{border}  {style_text('Volume:', 'muted', color)} {style_text(v_trunc, 'g_yellow', color)}{v_pad}{border}")
    p_trunc = truncate_visible(context.project, max(1, inside - 13))
    p_plain = f"  Project: {p_trunc}"
    p_pad = " " * max(0, inside - visible_width(p_plain))
    lines.append(f"{border}  {style_text('Project:', 'muted', color)} {style_text(p_trunc, 'g_blue', color)}{p_pad}{border}")
    lines.append(_border_bottom(box_width, color))
    return lines


def _minimal_panel(
    context: PanelContext,
    box_width: int,
    color: ColorMode,
) -> list[str]:
    inside = max(1, box_width - 2)
    border = style_text("│", "muted", color)
    title = style_text(
        truncate_visible(f"LocalCloud v{__version__}", inside),
        "label",
        color,
        bold=True,
    )
    title += " " * max(0, inside - visible_width(title))
    data_volume = truncate_visible(context.data_volume, inside)
    data_volume = style_text(data_volume, "g_yellow", color)
    data_volume += " " * max(0, inside - visible_width(data_volume))
    return [
        _border_title(box_width, color),
        f"{border}{title}{border}",
        f"{border}{data_volume}{border}",
        _border_bottom(box_width, color),
    ]

class LifecycleReporter:
    def __init__(
        self,
        *,
        stream: TextIO | None = None,
        verbose: bool = False,
        environ: Mapping[str, str] | None = None,
        clock: Callable[[], float] = time.monotonic,
        phase_selector: Callable[[Sequence[float]], float] | None = None,
        width: int | None = None,
        fps: float = 20.0,
        intro_seconds: float = 1.5,
    ) -> None:
        self.stream = sys.stderr if stream is None else stream
        self.verbose = verbose
        self.environ = os.environ if environ is None else environ
        self.capabilities = terminal_capabilities(self.stream, self.environ)
        self.clock = clock
        self.phase_selector = phase_selector or random.SystemRandom().choice
        self.width = width or _terminal_width(self.stream)
        self.fps = fps
        self.intro_seconds = intro_seconds
        self._message = ""
        self._panel: PanelContext | None = None
        self._phase = 0.0
        self._started: float | None = None
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._lock = threading.Lock()
        self._rows = 0
        self._finished = False
        self._panel_settled = False
        self._cursor_hidden = False

    @property
    def enabled(self) -> bool:
        return not (self.verbose and not self.capabilities.interactive)

    def start(self, message: str) -> None:
        if not self.enabled or self._started is not None:
            return
        self._started = self.clock()
        self._message = message
        if self.capabilities.cursor:
            self._cursor_hidden = True
            self.stream.write("\x1b[?25l")
            self.stream.flush()
            self._thread = threading.Thread(target=self._animate, name="localcloud-output", daemon=True)
            self._thread.start()
        else:
            self._write_plain("Processing", message)

    def update(self, message: str, panel: PanelContext | None = None) -> None:
        if not self.enabled:
            return
        with self._lock:
            self._message = message
            if panel is not None:
                self._panel = panel
                self._phase = self.phase_selector(_PHASES)
                if self.capabilities.cursor and not self._panel_settled:
                    if self._rows:
                        self._clear_frame()
                    for line in render_panel(
                        self._panel,
                        self.width,
                        phase=self._phase,
                        progress=1.0,
                        color=self.capabilities.color,
                    ):
                        self.stream.write(line + "\n")
                    self._panel_settled = True
                    self.stream.flush()
        if not self.capabilities.cursor:
            self._write_plain("Processing", message)

    def write_line(self, line: str) -> None:
        if not self.enabled:
            return
        trimmed = line.rstrip("\r\n")
        with self._lock:
            if self.capabilities.cursor and self._rows:
                self._clear_frame()
            if self.capabilities.cursor and self._panel is not None and not self._panel_settled:
                for p_line in render_panel(
                    self._panel,
                    self.width,
                    phase=self._phase,
                    progress=1.0,
                    color=self.capabilities.color,
                ):
                    self.stream.write(p_line + "\n")
                self._panel_settled = True
            self.stream.write(f"{trimmed}\n")
            self.stream.flush()
    def succeed(self, message: str) -> None:
        self._finish("Done", message, "success")

    def fail(self, message: str) -> None:
        self._finish("Failed", message, "error")

    def close(self) -> None:
        if self._finished:
            return
        self._stop_worker()
        self._restore_cursor()
        self._finished = True

    def _finish(self, status: str, message: str, role: str) -> None:
        if self._finished:
            return
        if not self.enabled:
            self._finished = True
            return
        self._stop_worker()
        elapsed = self._elapsed()
        if self.capabilities.cursor:
            with self._lock:
                self._clear_frame()
                if self._panel is not None and not self._panel_settled:
                    for line in render_panel(
                        self._panel,
                        self.width,
                        phase=self._phase,
                        progress=1.0,
                        color=self.capabilities.color,
                    ):
                        self.stream.write(line + "\n")
                    self._panel_settled = True
                self.stream.write(self._status_line(status, message, role, elapsed) + "\n")
                self.stream.flush()
        else:
            self._write_plain(status, f"{message}  {elapsed:.1f}s", role=role)
        self._restore_cursor()
        self._finished = True

    def _animate(self) -> None:
        interval = 1.0 / self.fps
        while not self._stop.is_set():
            with self._lock:
                lines = self._frame()
                self._draw_frame(lines)
            self._stop.wait(interval)

    def _frame(
        self,
        progress: float = 1.0,
        elapsed: float | None = None,
        *,
        include_panel: bool = False,
    ) -> list[str]:
        effective_elapsed = self._elapsed() if elapsed is None else elapsed
        lines: list[str] = []
        if include_panel and self._panel is not None:
            lines.extend(
                render_panel(
                    self._panel,
                    self.width,
                    phase=self._phase,
                    progress=progress,
                    color=self.capabilities.color,
                )
            )
        spinner = ""
        if _SPINNERS:
            try:
                spinner_index = int(effective_elapsed * 10) % len(_SPINNERS)
            except (TypeError, OverflowError, ValueError, ZeroDivisionError):
                spinner_index = 0
            if spinner_index < len(_SPINNERS):
                spinner = _SPINNERS[spinner_index]
        processing = style_text("Processing", "processing", self.capabilities.color, bold=True)
        spinner = style_text(spinner, "processing", self.capabilities.color)
        elapsed_text = style_text(f"{effective_elapsed:.1f}s", "muted", self.capabilities.color)
        overhead = (
            visible_width(spinner)
            + visible_width(processing)
            + visible_width(elapsed_text)
            + 6
        )
        available = max(1, self.width - overhead)
        message = truncate_visible(self._message, available)
        lines.append(f"{spinner} {processing}  {message}  {elapsed_text}")
        return lines

    def _draw_frame(self, lines: Sequence[str]) -> None:
        if self._rows:
            self.stream.write(f"\x1b[{self._rows}A")
        for line in lines:
            self.stream.write("\r\x1b[2K" + line + "\n")
        for _ in range(max(0, self._rows - len(lines))):
            self.stream.write("\r\x1b[2K\n")
        self._rows = len(lines)
        self.stream.flush()

    def _clear_frame(self) -> None:
        if not self._rows:
            return
        self.stream.write(f"\x1b[{self._rows}A")
        for _ in range(self._rows):
            self.stream.write("\r\x1b[2K")
            self.stream.write("\n")
        self.stream.write(f"\x1b[{self._rows}A")
        self._rows = 0

    def _stop_worker(self) -> None:
        self._stop.set()
        if self._thread is not None and self._thread is not threading.current_thread():
            self._thread.join()
            self._thread = None

    def _restore_cursor(self) -> None:
        if self._cursor_hidden:
            self.stream.write("\x1b[?25h")
            self.stream.flush()
            self._cursor_hidden = False

    def _elapsed(self) -> float:
        return 0.0 if self._started is None else max(0.0, self.clock() - self._started)

    def _status_line(self, status: str, message: str, role: str, elapsed: float) -> str:
        styled = style_text(status.ljust(13), role, self.capabilities.color, bold=True)
        elapsed_text = style_text(f"{elapsed:.1f}s", "muted", self.capabilities.color)
        overhead = visible_width(styled) + visible_width(elapsed_text) + 2
        available = max(0, self.width - overhead)
        if available > 0:
            truncated = truncate_visible(message, available)
            return f"{styled}{truncated}  {elapsed_text}"
        if self.width >= visible_width(styled) + visible_width(elapsed_text):
            gap = " " * (self.width - visible_width(styled) - visible_width(elapsed_text))
            return f"{styled}{gap}{elapsed_text}"
        return truncate_visible(styled, self.width)

    def _write_plain(self, status: str, message: str, *, role: str | None = None) -> None:
        selected_role = role or ("processing" if status == "Processing" else "primary")
        label = style_text(status.ljust(13), selected_role, self.capabilities.color, bold=True)
        self.stream.write(f"{label}{message}\n")
        self.stream.flush()
