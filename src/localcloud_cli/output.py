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
_GRADIENT_STOPS = (
    (66, 133, 244),
    (234, 67, 53),
    (251, 188, 4),
    (52, 168, 83),
    (66, 133, 244),
)
_PHASES = (0.0, 0.25, 0.5, 0.75)
_ANSI_256_RAMP = (33, 196, 226, 34, 33)
_ANSI_16_RAMP = (94, 91, 93, 92, 94)
_TRUECOLOR_SEMANTICS = {
    "processing": (34, 211, 238),
    "success": (52, 211, 153),
    "error": (248, 113, 113),
    "warning": (251, 191, 36),
    "label": (96, 165, 250),
    "url": (34, 211, 238),
    "muted": (156, 163, 175),
    "primary": (243, 244, 246),
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
    FieldSpec("data_volume", "Data volume"),
    FieldSpec("origin", "Origin"),
    FieldSpec("project", "Project"),
    FieldSpec("user", "User"),
    FieldSpec("container.state", "State", "status"),
    FieldSpec("container.url", "URL", "url"),
    FieldSpec("services", "Services"),
    FieldSpec("data", "Data"),
    FieldSpec("changed_fields", "Changed", "warning"),
    FieldSpec("reset_scope", "Reset scope", "warning"),
)
_DOCTOR_FIELDS = (
    FieldSpec("status", "Status", "status"),
    FieldSpec("docker", "Docker"),
    FieldSpec("cli_version", "CLI version", "muted"),
    FieldSpec("default_image", "Image", "muted"),
    FieldSpec("legacy_resources", "Legacy resources", "warning"),
    FieldSpec("legacy_host_state", "Legacy host state", "warning"),
    FieldSpec("legacy_locks", "Legacy locks", "warning"),
    FieldSpec("warning", "Warning", "warning"),
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
    FieldSpec("container.configured_image", "Configured image", "muted"),
    FieldSpec("container.actual_image", "Actual image", "muted"),
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
    "reset": _COMMON_FIELDS,
    "stop": tuple(field for field in _COMMON_FIELDS if field.path not in {"project", "user", "changed_fields", "reset_scope"}),
    "status": tuple(field for field in _COMMON_FIELDS if field.path not in {"project", "user", "changed_fields", "reset_scope"}),
    "doctor": _DOCTOR_FIELDS,
    "console": _CONSOLE_FIELDS,
}
ALLOWED_FIELDS: Mapping[str, tuple[FieldSpec, ...]] = {
    command: (
        defaults
        if command == "doctor"
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
    fields = list(DEFAULT_FIELDS.get(command, ()))
    present = {field.path for field in fields}
    fields.extend(allowed[path] for path in requested if path not in present)
    resolved: list[tuple[FieldSpec, Any]] = []
    for field in fields:
        value = _resolve_path(payload, field.path)
        if value is _MISSING or value is None or value == [] or value == {}:
            continue
        resolved.append((field, value))
    if not resolved:
        return ""
    label_width = max(len(field.label) for field, _ in resolved)
    lines: list[str] = []
    for field, value in resolved:
        label = style_text(field.label.ljust(label_width), "label", color, bold=True)
        value_text = _format_value(value)
        role = _value_role(field, value)
        lines.append(f"{label}  {style_text(value_text, role, color)}")
    return "\n".join(lines)


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


def _value_role(field: FieldSpec, value: Any) -> str:
    if field.style != "status":
        return field.style
    normalized = str(value).lower()
    if normalized in {"ok", "started", "restarted", "reset", "opened", "running", "ready", "already_running"}:
        return "success"
    if normalized in {"unhealthy", "failed", "error"}:
        return "error"
    if normalized in {"stopped", "not_running", "not_created", "reconfigured"}:
        return "warning"
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
    rows = len(_CLOUD)
    cols = max(len(line) for line in _CLOUD)
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
            base = (x + rows - 1 - y) / max(1, cols + rows - 1)
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
    scaled = (position % 1.0) * (len(_GRADIENT_STOPS) - 1)
    index = min(len(_GRADIENT_STOPS) - 2, int(scaled))
    fraction = scaled - index
    left = _GRADIENT_STOPS[index]
    right = _GRADIENT_STOPS[index + 1]
    return tuple(round(a + (b - a) * fraction) for a, b in zip(left, right))


def _gradient_escape(position: float, rgb: tuple[int, int, int], color: ColorMode) -> str:
    if color is ColorMode.TRUECOLOR:
        return f"\x1b[38;2;{rgb[0]};{rgb[1]};{rgb[2]}m"
    ramp = _ANSI_256_RAMP if color is ColorMode.ANSI256 else _ANSI_16_RAMP
    index = min(len(ramp) - 1, round((position % 1.0) * (len(ramp) - 1)))
    return f"\x1b[38;5;{ramp[index]}m" if color is ColorMode.ANSI256 else f"\x1b[{ramp[index]}m"


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
        plain = "╰" + "─" * (box_width - 2) + "╯"
    else:
        plain = "╰" + "─" * split + "┴" + "─" * (box_width - split - 3) + "╯"
    return style_text(plain, "muted", color)


def _context_rows(context: PanelContext) -> list[tuple[str, str, str]]:
    services = context.services if isinstance(context.services, str) else ", ".join(context.services)
    config = Path(context.config).name if context.config else "defaults"
    return [
        ("Data volume", context.data_volume, "primary"),
        ("Project", context.project, "primary"),
        ("User", context.user, "primary"),
        ("Services", services or "default", "primary"),
        ("Data", context.data, "primary"),
        ("Config", config, "muted"),
    ]


def _styled_context(label: str, value: str, width: int, color: ColorMode, role: str) -> str:
    label_width = 12
    plain_value = truncate_visible(value, max(1, width - label_width))
    plain = label.ljust(label_width) + plain_value
    styled = style_text(label.ljust(label_width), "label", color, bold=True) + style_text(plain_value, role, color)
    return styled + " " * max(0, width - visible_width(plain))


def _wide_panel(context: PanelContext, box_width: int, phase: float, progress: float, color: ColorMode) -> list[str]:
    inside = box_width - 3
    left_width = max(24, min(30, inside // 3))
    right_width = inside - left_width
    cloud = render_cloud(phase=phase, progress=progress, color=color)
    left = ["", *cloud, ""]
    right = [_styled_context(label, value, right_width, color, role) for label, value, role in _context_rows(context)]
    lines = [_border_title(box_width, color)]
    border = style_text("│", "muted", color)
    for index in range(max(len(left), len(right))):
        left_value = left[index] if index < len(left) else ""
        left_pad = max(0, left_width - visible_width(left_value))
        left_cell = " " * (left_pad // 2) + left_value + " " * (left_pad - left_pad // 2)
        right_cell = right[index] if index < len(right) else " " * right_width
        lines.append(f"{border}{left_cell}{border}{right_cell}{border}")
    lines.append(_border_bottom(box_width, color, left_width))
    return lines


def _stacked_panel(context: PanelContext, box_width: int, phase: float, progress: float, color: ColorMode) -> list[str]:
    inside = box_width - 2
    border = style_text("│", "muted", color)
    lines = [_border_title(box_width, color)]
    for line in render_cloud(phase=phase, progress=progress, color=color):
        pad = inside - visible_width(line)
        lines.append(f"{border}{' ' * (pad // 2)}{line}{' ' * (pad - pad // 2)}{border}")
    lines.append(f"{border}{' ' * inside}{border}")
    for label, value, role in _context_rows(context):
        lines.append(f"{border}{_styled_context(label, value, inside, color, role)}{border}")
    lines.append(_border_bottom(box_width, color))
    return lines


def _compact_panel(context: PanelContext, box_width: int, phase: float, progress: float, color: ColorMode) -> list[str]:
    inside = box_width - 2
    border = style_text("│", "muted", color)
    lines = [_border_title(box_width, color)]
    for line in render_cloud(phase=phase, progress=progress, color=color):
        pad = inside - visible_width(line)
        lines.append(f"{border}{' ' * (pad // 2)}{line}{' ' * (pad - pad // 2)}{border}")
    for label, value, role in _context_rows(context)[:2]:
        lines.append(f"{border}{_styled_context(label, value, inside, color, role)}{border}")
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
    data_volume = style_text(data_volume, "primary", color)
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
                self._panel_settled = False
                self._phase = self.phase_selector(_PHASES)
        if not self.capabilities.cursor:
            self._write_plain("Processing", message)

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
                elapsed = self._elapsed()
                progress = min(1.0, elapsed / self.intro_seconds) if self.intro_seconds > 0 else 1.0
                if self.capabilities.color is ColorMode.NONE:
                    progress = 1.0
                lines = self._frame(
                    progress,
                    elapsed,
                    include_panel=not self._panel_settled,
                )
                self._draw_frame(lines)
                if self._panel is not None and progress >= 1.0:
                    self._panel_settled = True
                    self._rows = 1
            self._stop.wait(interval)

    def _frame(
        self,
        progress: float,
        elapsed: float,
        *,
        include_panel: bool = True,
    ) -> list[str]:
        lines: list[str] = []
        if include_panel and self._panel is not None:
            lines.extend(render_panel(self._panel, self.width, phase=self._phase, progress=progress, color=self.capabilities.color))
        spinner = _SPINNERS[int(elapsed * 10) % len(_SPINNERS)]
        processing = style_text("Processing", "processing", self.capabilities.color, bold=True)
        spinner = style_text(spinner, "processing", self.capabilities.color)
        elapsed_text = style_text(f"{elapsed:.1f}s", "muted", self.capabilities.color)
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
        styled = style_text(status.ljust(10), role, self.capabilities.color, bold=True)
        elapsed_text = style_text(f"{elapsed:.1f}s", "muted", self.capabilities.color)
        overhead = visible_width(styled) + visible_width(elapsed_text) + 2
        available = max(1, self.width - overhead)
        return f"{styled}{truncate_visible(message, available)}  {elapsed_text}"

    def _write_plain(self, status: str, message: str, *, role: str | None = None) -> None:
        selected_role = role or ("processing" if status == "Processing" else "primary")
        label = style_text(status.ljust(10), selected_role, self.capabilities.color, bold=True)
        self.stream.write(f"{label}{message}\n")
        self.stream.flush()
