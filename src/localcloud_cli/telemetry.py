"""Anonymous, best-effort PostHog telemetry for LocalCloud CLI runtime commands.

The CLI reports to the same PostHog project as the LocalCloud server and honors
the same ``LOCALCLOUD_TELEMETRY=false`` opt-out (plus ``DO_NOT_TRACK``). Two
event kinds exist:

* ``cli_startup_error`` - a failed start/restart/reset, or error lines in the
  startup log, reduced to scrubbed one-line signatures (never raw logs).
* ``cli_heartbeat`` - at most hourly: CLI version, platform, configured
  services, and command counts since the previous heartbeat. Runtime usage is
  already reported hourly by the server's own heartbeat.

The project is shared with the website, the server, and the Console (whose
summaries the server forwards). Every CLI event is identified three ways: a
``cli_`` event-name prefix, ``source: "cli"`` with ``$lib: "localcloud-cli"``,
and an ``lcc_`` distinct ID (the server uses ``lc_``). Properties that mean the
same thing as a server property reuse its name and type, such as
``services_enabled`` and ``error_message``.

Delivery never changes a command's outcome: only start/restart/reset/stop send,
as one batch request bounded by a short deadline, and at most ``MAX_PENDING``
undelivered events are retried on a later run. Other runtime commands only
count themselves, so quick commands such as ``env`` never wait on the network.
"""

from __future__ import annotations

import json
import math
import os
import re
import sys
import time
from collections.abc import Callable, Mapping
from datetime import UTC, datetime
from pathlib import Path
from typing import TYPE_CHECKING, Any

from . import __release_commit__, __version__
from .constants import DEFAULT_DATA_VOLUME, DEFAULT_IMAGE, DEFAULT_PROJECT, DEFAULT_USER
from .errors import HostError
from .output import strip_ansi

if TYPE_CHECKING:
    from .config import LocalCloudConfig


# The public, write-only key shared with the server image (docker-entrypoint.sh),
# which also forwards the Console's summaries, and the website.
DEFAULT_API_KEY = "phc_o9nQDAQjEgsPcamE8pCnhv7ekA8CmA2VQXechLju9LA9"
DEFAULT_URL = "https://us.i.posthog.com/i/v0/e/"
STATE_FILE = "telemetry.json"
MAX_PENDING = 5
HEARTBEAT_SECONDS = 3600.0
RETRY_SECONDS = 300.0
SEND_DEADLINE_SECONDS = 3.0
# Tells CLI events apart from server, Console, and website events in the shared project.
SOURCE = "cli"
LIBRARY = "localcloud-cli"
STARTUP_COMMANDS = frozenset({"start", "restart", "reset"})
SEND_COMMANDS = STARTUP_COMMANDS | {"stop"}

# Waiting on the user, conflicting flags, or nothing to act on: not runtime health.
_IGNORED_ERRORS = frozenset(
    {
        "dry_run_image_unavailable",
        "dry_run_pull_conflict",
        "host_lock_failed",
        "manual_volume_removal_required",
        "port_mapping_confirmation_required",
        "port_mapping_declined",
        "runtime_not_found",
    }
)
_MAX_LOG_LINES = 10
_MAX_CANDIDATES = 100
_MAX_TEXT = 240
_MAX_SCRUB_INPUT = 2000
_CLOCK_SKEW = 5.0
# Docker log timestamps come from the daemon (often a VM) clock. Larger apparent
# offsets mean the container logged nothing new, not that the clocks disagree.
_MAX_DRIFT = 300.0
_FALSE = {"0", "false", "no", "off"}
_TRUE = {"1", "true", "yes", "on"}
_ID = re.compile(r"lcc_[0-9a-f]{16}")
_TIMESTAMP = re.compile(r"^(\d{4}-\d{2}-\d{2}T[0-9:.]+(?:Z|[+-]\d{2}:\d{2}))\s+")
_ELAPSED = re.compile(r"\s+in \d+(?:\.\d+)?s$")
_ERROR_LINE = re.compile(
    r"\[FAILED\]|\bERROR\b|\bFATAL\b|\bstate=failed\b|\b\w+(?:Exception|Error):"
)
_SECRET_NAME = (
    r"[\w.-]{0,40}(?:token|secret(?!manager)|passw(?:or)?d|pwd|api[_-]?key"
    r"|access[_-]?key|private[_-]?key|authorization|credentials?)[\w.-]{0,40}"
)
_SECRET_SCRUBBERS = (
    (re.compile(r"://[^/\s:@]+:[^/\s@]+@"), "://<credentials>@"),
    (re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+"), "<email>"),
    (
        re.compile(
            rf"(?i)\b({_SECRET_NAME}[\"']?\s*[=:]\s*)"
            r"(?:(?:bearer|basic|token)\s+)?(?:\"[^\"]*\"|'[^']*'|[^\s\"',;&)}\]]+)"
        ),
        r"\1<redacted>",
    ),
    (re.compile(r"(?i)\b(bearer)\s+[\w.~+/=-]{8,}"), r"\1 <redacted>"),
)
_GENERIC_SCRUBBERS = (
    (re.compile(r"(?:/Users|/home)/[^/\s]+|/root\b"), "~"),
    (re.compile(r"[A-Za-z]:\\Users\\[^\\\s]+"), "~"),
    (re.compile(r"[A-Za-z0-9_+=-]{32,}"), "<redacted>"),
)


def enabled(environment: Mapping[str, str] | None = None) -> bool:
    """Opt-out wins: host env, ``DO_NOT_TRACK``, or ``host.environment`` config."""
    if os.environ.get("DO_NOT_TRACK", "").strip().lower() in _TRUE:
        return False
    for source in (os.environ, environment or {}):
        if str(source.get("LOCALCLOUD_TELEMETRY", "")).strip().lower() in _FALSE:
            return False
    return True


class Telemetry:
    """Collects one CLI invocation's signals and flushes them when it ends."""

    def __init__(
        self,
        command: str,
        *,
        state_path: Path | None = None,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self.command = command
        self._state_path = state_path
        self._clock = clock
        self._started = clock()
        self._config: LocalCloudConfig | None = None
        self._fallback = False
        # Error line -> newest daemon timestamp seen for it (inf when unstamped).
        self._candidates: dict[str, float] = {}
        # Lower bound of (daemon clock - host clock) from the newest log line.
        self._offset: float | None = None
        self._names: tuple[re.Pattern[str] | None, dict[str, str]] | None = None
        self._id = ""

    @property
    def configured(self) -> bool:
        return self._config is not None

    def configure(self, config: LocalCloudConfig, *, fallback: bool = False) -> None:
        """``fallback`` marks a config resolved from files alone, without Docker."""
        self._config = config
        self._fallback = fallback
        self._names = None

    def recorded_config(self) -> str | None:
        """Config path of the last run that resolved its config through Docker."""
        try:
            return _load_state(self._path(), self._clock())["config_path"]
        except Exception:
            return None

    def observe_logs(self, logs: str) -> None:
        if self.command not in STARTUP_COMMANDS or not logs:
            return
        observed = self._clock()
        newest, lines = _scan_logs(logs)
        if newest is not None:
            # Docker stamps a line no later than the daemon's "now", so this bounds
            # how far the daemon clock runs ahead of (or behind) the host clock.
            offset = newest - observed
            self._offset = offset if self._offset is None else max(self._offset, offset)
        for stamp, line in lines:
            self._candidates[line] = max(stamp, self._candidates.get(line, stamp))
        while len(self._candidates) > _MAX_CANDIDATES:
            del self._candidates[min(self._candidates, key=self._candidates.__getitem__)]

    def finish(
        self,
        *,
        error: BaseException | None = None,
        result: Any = None,
        interrupted: bool = False,
    ) -> None:
        try:
            self._finish(error, result, interrupted)
        except (Exception, KeyboardInterrupt):
            # Telemetry must never change a command's output or exit status, even
            # when the user interrupts a slow send.
            pass

    def _finish(self, error: BaseException | None, result: Any, interrupted: bool) -> None:
        # No resolved config means its opt-out could not be read; stay silent.
        config = self._config
        if config is None:
            return
        path = self._path()
        if not enabled():
            path.unlink(missing_ok=True)
            return
        now = self._clock()
        state = _load_state(path, now)
        disabled = state.pop("disabled")
        if not enabled(config.environment):
            # Keep only a marker: runs that cannot reach Docker cannot read a
            # remembered config, so they rely on it to honor this opt-out.
            if not disabled:
                _save_state(path, {"disabled": True})
            return
        if self._fallback and disabled:
            return
        if not self._fallback:
            state["config_path"] = str(config.config_path) if config.config_path else None

        self._id = state["id"]
        commands = state["commands"]
        commands[self.command] = commands.get(self.command, 0) + 1
        startup_error = self._startup_error(error, result, interrupted)
        if startup_error is not None:
            state["startup_errors"] += 1
            state["pending"].append(startup_error)
        sends = self.command in SEND_COMMANDS
        if sends and now - state["last_heartbeat"] >= HEARTBEAT_SECONDS:
            state["pending"].append(self._heartbeat(state))
            state["commands"] = {}
            state["startup_errors"] = 0
            state["last_heartbeat"] = now
        state["pending"] = state["pending"][-MAX_PENDING:]

        # A new error is worth one attempt now; otherwise back off after failures
        # so an offline machine does not pay the send deadline on every command.
        attempt = bool(
            sends
            and state["pending"]
            and (startup_error is not None or now >= state["retry_after"])
        )
        if attempt:
            # Record the attempt before sending. An unwritable state file would
            # otherwise mean a new ID and a send on every run, and an interrupted
            # send keeps its events for the next attempt.
            state["retry_after"] = now + RETRY_SECONDS
        if not _save_state(path, state) or not attempt:
            return
        if _deliver(state["pending"], config.environment):
            state["pending"] = []
            state["retry_after"] = 0.0
            _save_state(path, state)

    def _path(self) -> Path:
        if self._state_path is not None:
            return self._state_path
        from .config import HostPaths

        return HostPaths.from_environment().home / STATE_FILE

    def _startup_error(
        self, error: BaseException | None, result: Any, interrupted: bool
    ) -> dict[str, Any] | None:
        if self.command not in STARTUP_COMMANDS:
            return None
        if isinstance(error, HostError):
            if error.code in _IGNORED_ERRORS:
                return None
            self.observe_logs(str(error.details.get("logs") or ""))
        elif isinstance(result, dict):
            self.observe_logs(str(result.get("logs") or ""))
        since = self._since()
        log_errors = [
            line for line, stamp in self._candidates.items() if stamp >= since
        ][:_MAX_LOG_LINES]
        if error is None and not log_errors:
            return None

        properties: dict[str, Any] = {
            "command": self.command,
            "outcome": "failed"
            if error is not None
            else "interrupted"
            if interrupted
            else "started_with_errors",
            "log_errors": [self._scrub(line) for line in log_errors],
        }
        if isinstance(error, HostError):
            properties["error_code"] = error.code
            properties["error_message"] = self._scrub(error.message)
            details = error.details
            for key in ("phase", "state", "timeout_seconds"):
                if isinstance(details.get(key), (str, int, float)):
                    properties[key] = details[key]
            cause = details.get("cause") or details.get("last_error")
            if isinstance(cause, dict):
                cause = cause.get("code") or cause.get("message")
            if cause:
                properties["cause"] = self._scrub(str(cause))
        elif error is not None:
            properties["error_code"] = "unexpected_error"
            properties["error_type"] = type(error).__name__
            properties["error_message"] = self._scrub(str(error))
        return self._event("cli_startup_error", {**properties, **self._config_facts()})

    def _since(self) -> float:
        """Host start time on the daemon's clock, minus a little slack.

        Restarted containers keep earlier runs' logs, which this excludes. An
        apparent offset beyond any plausible drift means the container logged
        nothing new, so the host clock is trusted instead.
        """
        offset = self._offset
        if offset is None or offset < -_MAX_DRIFT:
            offset = 0.0
        return self._started + offset - _CLOCK_SKEW

    def _heartbeat(self, state: dict[str, Any]) -> dict[str, Any]:
        return self._event(
            "cli_heartbeat",
            {
                "commands": dict(state["commands"]),
                "startup_errors": state["startup_errors"],
                **self._config_facts(),
            },
        )

    def _config_facts(self) -> dict[str, Any]:
        config = self._config
        assert config is not None
        repository, tag = _image_parts(config.image)
        return {
            # Same names and types as the server's heartbeat properties.
            "services_enabled": list(config.effective_services),
            "services_enabled_count": len(config.effective_services),
            "memory": config.memory,
            "tls_enabled": config.tls_enabled,
            "docker_socket_mode": config.docker_socket_mode,
            # Custom registries can identify an organization; report only their use.
            "image_tag": tag if repository == _image_parts(DEFAULT_IMAGE)[0] else "custom",
        }

    def _event(self, name: str, properties: dict[str, Any]) -> dict[str, Any]:
        import platform
        import uuid

        return {
            "event": name,
            "distinct_id": self._id,
            "properties": {
                **properties,
                "distinct_id": self._id,
                "$process_person_profile": False,
                "source": SOURCE,
                "$lib": LIBRARY,
                "$lib_version": __version__,
                "version": __version__,
                "commit_id": __release_commit__ or "unknown",
                "os_name": platform.system().lower(),
                "os_arch": platform.machine().lower(),
                "python_version": platform.python_version(),
                "standalone_binary": bool(getattr(sys, "frozen", False)),
                "ci": bool(os.environ.get("CI")),
            },
            "timestamp": datetime.fromtimestamp(self._clock(), UTC).isoformat(),
            "uuid": str(uuid.uuid4()),
        }

    def _scrub(self, value: str) -> str:
        value = value[:_MAX_SCRUB_INPUT]
        for pattern, replacement in _SECRET_SCRUBBERS:
            value = pattern.sub(replacement, value)
        if self._names is None:
            self._names = _literal_pattern(self._private_names())
        pattern, names = self._names
        if pattern is not None:
            value = pattern.sub(lambda match: names[match.group(0)], value)
        for pattern, replacement in _GENERIC_SCRUBBERS:
            value = pattern.sub(replacement, value)
        return value[:_MAX_TEXT]

    def _private_names(self) -> dict[str, str]:
        """This machine's names and paths that can identify a person or organization."""
        from urllib.parse import urlsplit

        from .config import HostPaths, default_resource_names

        config = self._config
        assert config is not None
        defaults = default_resource_names(config.data_volume)
        candidates: list[tuple[object, object, str]] = [
            (config.project, DEFAULT_PROJECT, "<project>"),
            (config.user, DEFAULT_USER, "<user>"),
            (config.data_volume, DEFAULT_DATA_VOLUME, "<data-volume>"),
            (config.container_name, defaults["container"], "<container>"),
            (config.network_name, defaults["network"], "<network>"),
            (config.config_path, None, "<config>"),
            (HostPaths.from_environment().home, None, "<localcloud-home>"),
            (Path.home(), None, "~"),
        ]
        try:
            candidates.append((Path.cwd(), None, "<cwd>"))
        except OSError:
            pass
        repository = _image_parts(config.image)[0]
        if repository != _image_parts(DEFAULT_IMAGE)[0]:
            candidates.append((repository, None, "<image>"))
            registry = repository.split("/", 1)[0]
            if "/" in repository and ("." in registry or ":" in registry):
                candidates.append((registry, None, "<registry>"))
        docker_host = urlsplit(os.environ.get("DOCKER_HOST", "")).hostname
        if docker_host not in {None, "localhost", "127.0.0.1", "::1"}:
            candidates.append((docker_host, None, "<docker-host>"))

        names: dict[str, str] = {}
        for value, default, placeholder in candidates:
            text = str(value) if value else ""
            if len(text) >= 3 and text != str(default) and text != "/":
                names.setdefault(text, placeholder)
        return names


def _literal_pattern(
    names: dict[str, str],
) -> tuple[re.Pattern[str] | None, dict[str, str]]:
    if not names:
        return None, names
    # Longest first, in one pass, so a placeholder is never replaced again.
    ordered = sorted(names, key=len, reverse=True)
    return re.compile("|".join(re.escape(name) for name in ordered)), names


def _image_parts(image: str) -> tuple[str, str]:
    name, _, digest = image.partition("@")
    repository, separator, tag = name.rpartition(":")
    if not separator or "/" in tag:
        repository, tag = name, ""
    return repository, tag or ("digest" if digest else "latest")


def _scan_logs(logs: str) -> tuple[float | None, list[tuple[float, str]]]:
    """Newest daemon timestamp, and each error line with its timestamp."""
    newest: float | None = None
    lines: list[tuple[float, str]] = []
    for raw in logs.splitlines():
        line = strip_ansi(raw).strip()
        stamp = math.inf
        match = _TIMESTAMP.match(line)
        if match is not None:
            try:
                stamp = datetime.fromisoformat(match.group(1)).timestamp()
            except ValueError:
                pass
            else:
                newest = stamp if newest is None else max(newest, stamp)
            line = line[match.end():]
        if line.startswith("at ") or not _ERROR_LINE.search(line):
            continue
        lines.append((stamp, _ELAPSED.sub("", line)))
    return newest, lines


def _load_state(path: Path, now: float) -> dict[str, Any]:
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        raw = {}
    if not isinstance(raw, dict):
        raw = {}

    def count(value: Any) -> int:
        valid = isinstance(value, int) and not isinstance(value, bool)
        return value if valid and 0 < value < 2**31 else 0

    def moment(key: str, latest: float) -> float:
        value = raw.get(key)
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            return 0.0
        # A clock that once ran ahead must not suppress heartbeats or retries.
        return float(value) if math.isfinite(value) and 0 <= value <= latest else 0.0

    identifier = raw.get("id")
    commands = raw.get("commands")
    pending = raw.get("pending")
    config_path = raw.get("config_path")
    return {
        "id": identifier
        if isinstance(identifier, str) and _ID.fullmatch(identifier)
        else _new_id(),
        "disabled": raw.get("disabled") is True,
        "config_path": config_path if isinstance(config_path, str) and config_path else None,
        "last_heartbeat": moment("last_heartbeat", now),
        "retry_after": moment("retry_after", now + RETRY_SECONDS),
        "startup_errors": count(raw.get("startup_errors")),
        "commands": {
            str(key): count(value) for key, value in commands.items() if count(value)
        }
        if isinstance(commands, dict)
        else {},
        "pending": [event for event in pending if isinstance(event, dict)][-MAX_PENDING:]
        if isinstance(pending, list)
        else [],
    }


def _new_id() -> str:
    import secrets

    return f"lcc_{secrets.token_hex(8)}"


def _save_state(path: Path, state: dict[str, Any]) -> bool:
    import tempfile

    try:
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        descriptor, temporary = tempfile.mkstemp(
            dir=path.parent, prefix=f".{path.name}.", suffix=".tmp"
        )
    except OSError:
        return False
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as state_file:
            os.chmod(temporary, 0o600)
            json.dump(state, state_file, separators=(",", ":"))
        # Concurrent CLI runs may overwrite each other's counts; that loss is acceptable.
        os.replace(temporary, path)
        return True
    except OSError:
        return False
    finally:
        Path(temporary).unlink(missing_ok=True)


def _batch_url(url: str) -> str:
    """PostHog's batch endpoint under the same prefix as a capture URL."""
    from urllib.parse import urlsplit, urlunsplit

    parts = urlsplit(url)
    path = parts.path.rstrip("/")
    for suffix in ("/i/v0/e", "/e", "/capture", "/batch"):
        if path.endswith(suffix):
            path = path[: -len(suffix)]
            break
    return urlunsplit((parts.scheme, parts.netloc, f"{path}/batch/", "", ""))


def _deliver(events: list[dict[str, Any]], environment: Mapping[str, str]) -> bool:
    """Send one batch within the deadline; True means the events can be dropped."""
    import threading

    import httpx

    def setting(name: str) -> str:
        return os.environ.get(name, "").strip() or str(environment.get(name, "")).strip()

    api_key = setting("LOCALCLOUD_EVENT_API_KEY") or DEFAULT_API_KEY
    url = _batch_url(setting("LOCALCLOUD_POSTHOG_URL") or DEFAULT_URL)
    outcome: dict[str, int] = {}

    def send() -> None:
        try:
            response = httpx.post(
                url,
                json={"api_key": api_key, "batch": events},
                timeout=SEND_DEADLINE_SECONDS,
            )
            outcome["status"] = response.status_code
        except Exception:
            pass

    # httpx timeouts do not bound DNS resolution; the join deadline does.
    worker = threading.Thread(target=send, name="localcloud-telemetry", daemon=True)
    worker.start()
    worker.join(SEND_DEADLINE_SECONDS)
    status = outcome.get("status")
    if status is None:
        return False
    # A rejected payload will never succeed; drop it instead of retrying forever.
    return 200 <= status < 300 or (400 <= status < 500 and status not in {408, 429})
