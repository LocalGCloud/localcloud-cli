"""Runtime state tracking, persistence, and host locking for LocalCloud."""

from __future__ import annotations

import fcntl
import hashlib
import json
import os
import re
import tempfile
import threading
from contextlib import contextmanager
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .constants import DEFAULT_DATA_VOLUME
from .errors import HostError

ACTIVE_RUNTIME_FILE = "active-runtime.json"
ACTIVE_RUNTIME_SCHEMA_VERSION = 3
DATA_VOLUME_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,254}$")
DOCKER_NAME_PATTERN = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$")


def validate_data_volume(value: object | None) -> str:
    if value is None:
        return DEFAULT_DATA_VOLUME
    if not isinstance(value, str) or not DATA_VOLUME_PATTERN.fullmatch(value.strip()):
        raise HostError(
            "invalid_data_volume",
            "Data volume names must match [A-Za-z0-9][A-Za-z0-9_.-]{0,254}",
            {"data_volume": value},
        )
    return value.strip()


def _hashed_resource_name(data_volume: str) -> str:
    digest = hashlib.sha256(data_volume.encode("utf-8")).hexdigest()[:12]
    return f"localcloud-volume-{digest}"


def default_resource_names(data_volume: str) -> dict[str, str]:
    selected = validate_data_volume(data_volume)
    if selected == DEFAULT_DATA_VOLUME:
        base = "localcloud"
    elif selected.startswith(f"{DEFAULT_DATA_VOLUME}-"):
        candidate = f"localcloud-{selected.removeprefix(f'{DEFAULT_DATA_VOLUME}-')}"
        base = (
            candidate
            if DOCKER_NAME_PATTERN.fullmatch(candidate)
            else _hashed_resource_name(selected)
        )
    else:
        base = _hashed_resource_name(selected)
    return {"container": base, "network": base}


def _non_blank_string(name: str, value: object) -> str:
    if not isinstance(value, str) or not value.strip():
        raise HostError(
            "invalid_active_runtime",
            f"Active runtime {name} must be a non-empty string",
            {"field": name, "value": value},
        )
    return value.strip()


def _docker_name(name: str, value: object) -> str:
    selected = _non_blank_string(name, value)
    if not DOCKER_NAME_PATTERN.fullmatch(selected):
        raise HostError(
            "invalid_active_runtime",
            f"Active runtime {name} must match Docker resource naming conventions",
            {"field": name, "value": value},
        )
    return selected


@dataclass(frozen=True)
class ActiveRuntime:
    schema_version: int
    data_volume: str
    image: str
    container_id: str
    container_name: str | None = None
    network_name: str | None = None


_LOCKS_GUARD = threading.Lock()
_PROCESS_LOCKS: dict[tuple[str, str], threading.Lock] = {}


@contextmanager
def _file_lock(paths: Any, key: str, lock_name: str) -> Iterator[None]:
    lock_key = (str(paths.home), key)
    with _LOCKS_GUARD:
        process_lock = _PROCESS_LOCKS.setdefault(lock_key, threading.Lock())

    with process_lock:
        try:
            paths.locks.mkdir(mode=0o700, parents=True, exist_ok=True)
            os.chmod(paths.home, 0o700)
            os.chmod(paths.locks, 0o700)
            lock_path = paths.locks / lock_name
            lock_file = lock_path.open("a+b")
            os.chmod(lock_path, 0o600)
        except OSError as error:
            raise HostError(
                "host_lock_failed",
                f"Could not prepare LocalCloud lock state under {paths.home}",
                {"path": str(paths.home), "cause": str(error)},
            ) from error
        try:
            try:
                fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
            except OSError as error:
                raise HostError(
                    "host_lock_failed",
                    f"Could not acquire LocalCloud host lock: {lock_path}",
                    {"path": str(lock_path), "cause": str(error)},
                ) from error
            try:
                yield
            finally:
                fcntl.flock(lock_file.fileno(), fcntl.LOCK_UN)
        finally:
            lock_file.close()


@contextmanager
def data_volume_lock(paths: Any, data_volume: str) -> Iterator[None]:
    selected = validate_data_volume(data_volume)
    digest = hashlib.sha256(selected.encode("utf-8")).hexdigest()
    with _file_lock(paths, f"data-volume:{selected}", f"runtime-{digest}.lock"):
        yield


@contextmanager
def _active_runtime_lock(paths: Any) -> Iterator[None]:
    with _file_lock(paths, "active-runtime", "active-runtime.lock"):
        yield


def _decode_runtime_entry(raw: dict[str, Any]) -> ActiveRuntime:
    expected = {
        "data_volume",
        "image",
        "container_id",
        "container_name",
        "network_name",
    }
    if set(raw) != expected:
        raise ValueError(
            f"runtime fields must be exactly {', '.join(sorted(expected))}"
        )
    return ActiveRuntime(
        schema_version=ACTIVE_RUNTIME_SCHEMA_VERSION,
        data_volume=validate_data_volume(raw["data_volume"]),
        image=_non_blank_string("image", raw["image"]),
        container_id=_non_blank_string("container_id", raw["container_id"]),
        container_name=_docker_name(
            "container_name", raw["container_name"]
        ),
        network_name=_docker_name("network_name", raw["network_name"]),
    )


def _decode_active_state(
    raw: Any,
) -> tuple[dict[str, ActiveRuntime], str]:
    if not isinstance(raw, dict):
        raise ValueError("state must be a JSON object")
    schema_version = raw.get("schema_version")
    if schema_version in {1, 2}:
        expected = {
            "schema_version",
            "data_volume",
            "image",
            "container_id",
        }
        if schema_version == 2:
            expected.update({"container_name", "network_name"})
        if set(raw) != expected:
            raise ValueError(
                f"state fields must be exactly {', '.join(sorted(expected))}"
            )
        volume = validate_data_volume(raw["data_volume"])
        names = default_resource_names(volume)
        runtime = ActiveRuntime(
            schema_version=ACTIVE_RUNTIME_SCHEMA_VERSION,
            data_volume=volume,
            image=_non_blank_string("image", raw["image"]),
            container_id=_non_blank_string(
                "container_id", raw["container_id"]
            ),
            container_name=_docker_name(
                "container_name",
                raw.get("container_name") or names["container"],
            ),
            network_name=_docker_name(
                "network_name",
                raw.get("network_name") or names["network"],
            ),
        )
        return {volume: runtime}, volume
    if schema_version != ACTIVE_RUNTIME_SCHEMA_VERSION:
        raise ValueError(f"unsupported schema version {schema_version!r}")
    if set(raw) != {"schema_version", "last_active", "runtimes"}:
        raise ValueError(
            "state fields must be exactly last_active, runtimes, schema_version"
        )
    runtimes_raw = raw["runtimes"]
    if not isinstance(runtimes_raw, dict) or not runtimes_raw:
        raise ValueError("runtimes must be a non-empty object")
    runtimes: dict[str, ActiveRuntime] = {}
    for volume, value in runtimes_raw.items():
        validated_volume = validate_data_volume(volume)
        if not isinstance(value, dict):
            raise ValueError(f"runtime {validated_volume} must be an object")
        runtime = _decode_runtime_entry(value)
        if runtime.data_volume != validated_volume:
            raise ValueError(
                f"runtime key does not match data_volume: {validated_volume}"
            )
        runtimes[validated_volume] = runtime
    last_active = validate_data_volume(raw["last_active"])
    if last_active not in runtimes:
        raise ValueError("last_active must identify a persisted runtime")
    return runtimes, last_active


def _record_active_diagnostic(
    diagnostics: list[dict[str, Any]] | None,
    error: HostError,
) -> None:
    if diagnostics is not None:
        diagnostics.append(error.to_dict())


def load_active_runtime(
    paths: Any,
    diagnostics: list[dict[str, Any]] | None = None,
    *,
    data_volume: str | None = None,
) -> ActiveRuntime | None:
    try:
        encoded = paths.active_runtime.read_text(encoding="utf-8")
    except FileNotFoundError:
        return None
    except (OSError, UnicodeError) as exc:
        _record_active_diagnostic(
            diagnostics,
            HostError(
                "active_runtime_unreadable",
                f"Unable to read active runtime state: {paths.active_runtime}",
                {"path": str(paths.active_runtime), "reason": str(exc)},
            ),
        )
        return None
    try:
        runtimes, last_active = _decode_active_state(json.loads(encoded))
        selected = (
            validate_data_volume(data_volume)
            if data_volume is not None
            else last_active
        )
        return runtimes.get(selected)
    except (HostError, TypeError, ValueError, json.JSONDecodeError) as exc:
        _record_active_diagnostic(
            diagnostics,
            HostError(
                "invalid_active_runtime",
                f"Active runtime state is invalid: {paths.active_runtime}",
                {"path": str(paths.active_runtime), "reason": str(exc)},
            ),
        )
        return None


def save_active_runtime(paths: Any, runtime: ActiveRuntime) -> None:
    if runtime.schema_version != ACTIVE_RUNTIME_SCHEMA_VERSION:
        raise HostError(
            "invalid_active_runtime",
            f"Unsupported active runtime schema: {runtime.schema_version}",
            {"schema_version": runtime.schema_version},
        )
    volume = validate_data_volume(runtime.data_volume)
    names = default_resource_names(volume)
    selected = ActiveRuntime(
        schema_version=ACTIVE_RUNTIME_SCHEMA_VERSION,
        data_volume=volume,
        image=_non_blank_string("image", runtime.image),
        container_id=_non_blank_string(
            "container_id", runtime.container_id
        ),
        container_name=_docker_name(
            "container_name", runtime.container_name or names["container"]
        ),
        network_name=_docker_name(
            "network_name", runtime.network_name or names["network"]
        ),
    )
    with _active_runtime_lock(paths):
        runtimes: dict[str, ActiveRuntime] = {}
        try:
            existing = json.loads(
                paths.active_runtime.read_text(encoding="utf-8")
            )
            runtimes, _last_active = _decode_active_state(existing)
        except FileNotFoundError:
            pass
        except (OSError, UnicodeError) as error:
            raise HostError(
                "active_runtime_write_failed",
                f"Could not read active runtime state: {paths.active_runtime}",
                {"path": str(paths.active_runtime), "cause": str(error)},
            ) from error
        except (json.JSONDecodeError, ValueError) as error:
            raise HostError(
                "invalid_active_runtime",
                f"Existing active runtime state is invalid: {paths.active_runtime}",
                {"path": str(paths.active_runtime), "cause": str(error)},
            ) from error
        runtimes[volume] = selected
        payload = {
            "schema_version": ACTIVE_RUNTIME_SCHEMA_VERSION,
            "last_active": volume,
            "runtimes": {
                key: {
                    "data_volume": value.data_volume,
                    "image": value.image,
                    "container_id": value.container_id,
                    "container_name": value.container_name,
                    "network_name": value.network_name,
                }
                for key, value in sorted(runtimes.items())
            },
        }
        _write_active_state_file(paths, payload)


def _write_active_state_file(paths: Any, payload: dict[str, Any]) -> None:
    try:
        paths.home.mkdir(mode=0o700, parents=True, exist_ok=True)
        descriptor, temporary_name = tempfile.mkstemp(
            dir=paths.home,
            prefix=f".{ACTIVE_RUNTIME_FILE}.",
            suffix=".tmp",
        )
    except OSError as error:
        raise HostError(
            "active_runtime_write_failed",
            f"Could not prepare LocalCloud active runtime state under {paths.home}",
            {"path": str(paths.home), "cause": str(error)},
        ) from error
    temporary = Path(temporary_name)
    try:
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8") as state_file:
                os.chmod(temporary, 0o600)
                json.dump(
                    payload,
                    state_file,
                    sort_keys=True,
                    separators=(",", ":"),
                )
                state_file.write("\n")
                state_file.flush()
                os.fsync(state_file.fileno())
            os.replace(temporary, paths.active_runtime)
            directory_fd = os.open(paths.home, os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        except OSError as error:
            raise HostError(
                "active_runtime_write_failed",
                f"Could not persist LocalCloud active runtime state: {paths.active_runtime}",
                {"path": str(paths.active_runtime), "cause": str(error)},
            ) from error
    finally:
        temporary.unlink(missing_ok=True)


def clear_active_runtime(paths: Any, data_volume: str | None = None) -> None:
    with _active_runtime_lock(paths):
        if data_volume is None:
            try:
                paths.active_runtime.unlink(missing_ok=True)
            except OSError as error:
                raise HostError(
                    "active_runtime_write_failed",
                    f"Could not clear LocalCloud active runtime state: {paths.active_runtime}",
                    {"path": str(paths.active_runtime), "cause": str(error)},
                ) from error
            return

        try:
            encoded = paths.active_runtime.read_text(encoding="utf-8")
            runtimes, last_active = _decode_active_state(json.loads(encoded))
        except FileNotFoundError:
            return
        except (HostError, OSError, UnicodeError, ValueError, json.JSONDecodeError):
            try:
                paths.active_runtime.unlink(missing_ok=True)
            except OSError as error:
                raise HostError(
                    "active_runtime_write_failed",
                    f"Could not clear LocalCloud active runtime state: {paths.active_runtime}",
                    {"path": str(paths.active_runtime), "cause": str(error)},
                ) from error
            return

        selected_volume = validate_data_volume(data_volume)
        if selected_volume not in runtimes:
            return

        del runtimes[selected_volume]
        if not runtimes:
            try:
                paths.active_runtime.unlink(missing_ok=True)
            except OSError as error:
                raise HostError(
                    "active_runtime_write_failed",
                    f"Could not clear LocalCloud active runtime state: {paths.active_runtime}",
                    {"path": str(paths.active_runtime), "cause": str(error)},
                ) from error
            return

        new_last_active = (
            next(iter(runtimes)) if last_active == selected_volume else last_active
        )
        payload = {
            "schema_version": ACTIVE_RUNTIME_SCHEMA_VERSION,
            "last_active": new_last_active,
            "runtimes": {
                key: {
                    "data_volume": value.data_volume,
                    "image": value.image,
                    "container_id": value.container_id,
                    "container_name": value.container_name,
                    "network_name": value.network_name,
                }
                for key, value in sorted(runtimes.items())
            },
        }
        _write_active_state_file(paths, payload)
