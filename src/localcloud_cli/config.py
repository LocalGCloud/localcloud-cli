from __future__ import annotations

import fcntl
import hashlib
import json
import os
import re
import threading
import tempfile
from contextlib import contextmanager
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterator

import yaml

from .errors import HostError

DEFAULT_CONFIG_NAME = "localcloud.yaml"
DEFAULT_IMAGE = "jaysen2apache/localcloud:latest"
DEFAULT_MEMORY = "4g"
DEFAULTS_CONFIG_LABEL = "<defaults>"
DEFAULT_DATA_VOLUME = "localcloud-data"
DEFAULT_PROJECT = "local-gcp-project"
DEFAULT_USER = "local-developer"
ACTIVE_RUNTIME_SCHEMA_VERSION = 1
ACTIVE_RUNTIME_FILE = "active-runtime.json"
LEGACY_LOCK_PATTERN = re.compile(r"^[0-9a-f]{64}\.lock$")
LEGACY_HOST_FILES = ("state.db", "daemon.sock", "daemon.pid", "daemon.lock", "daemon.log")
DATA_VOLUME_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,254}$")
DOCKER_NAME_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$")
PROJECT_ID_PATTERN = re.compile(r"^[a-z][a-z0-9-]{4,28}[a-z0-9]$")
USER_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._+@-]{0,126}$")
ENVIRONMENT_KEY_PATTERN = re.compile(r"^LOCALCLOUD_[A-Z0-9_]+$")
CONFIG_FIELDS = {
    "data_volume",
    "project",
    "user",
    "services",
    "seed",
    "data",
    "image",
    "memory",
    "docker_socket",
    "transparent_network",
    "environment",
    "container_name",
    "network_name",
}
LEGACY_CONFIG_FIELDS = {"instance", "volume_name"}
_ACTIVE_RUNTIME_UNSET = object()


@dataclass(frozen=True)
class ActiveRuntime:
    schema_version: int
    data_volume: str
    image: str
    container_id: str


@dataclass(frozen=True)
class LocalCloudConfig:
    data_volume: str
    config_path: Path | None
    config_hash: str = field(init=False)
    project: str
    user: str
    services: tuple[str, ...] | None
    seed_path: Path | None
    seed_yaml: str | None
    data: str
    image: str
    memory: str
    docker_socket: bool
    transparent_network: bool
    environment: dict[str, str]
    container_name: str
    network_name: str
    diagnostics: tuple[dict[str, Any], ...]

    def __post_init__(self) -> None:
        encoded = json.dumps(
            runtime_settings(self),
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
        ).encode("utf-8")
        object.__setattr__(self, "config_hash", hashlib.sha256(encoded).hexdigest())


def runtime_settings(config: LocalCloudConfig) -> dict[str, Any]:
    """Return the Docker-runtime settings that define configuration identity."""
    return {
        "data_volume": config.data_volume,
        "services": list(config.services) if config.services is not None else None,
        "data": config.data,
        "image": config.image,
        "memory": config.memory,
        "docker_socket": config.docker_socket,
        "transparent_network": config.transparent_network,
        "environment": dict(config.environment),
        "container_name": config.container_name,
        "network_name": config.network_name,
    }


@dataclass(frozen=True)
class HostPaths:
    home: Path
    locks: Path

    @classmethod
    def from_environment(cls) -> "HostPaths":
        configured = os.environ.get("LOCALCLOUD_HOME")
        home = (
            Path(configured).expanduser()
            if configured
            else Path.home() / ".local" / "share" / "localcloud"
        ).resolve()
        return cls(home=home, locks=home / "locks")

    @property
    def active_runtime(self) -> Path:
        return self.home / ACTIVE_RUNTIME_FILE


_LOCKS_GUARD = threading.Lock()
_PROCESS_LOCKS: dict[tuple[str, str], threading.Lock] = {}


@contextmanager
def _file_lock(paths: HostPaths, key: str, lock_name: str) -> Iterator[None]:
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
def data_volume_lock(paths: HostPaths, data_volume: str) -> Iterator[None]:
    selected = validate_data_volume(data_volume)
    digest = hashlib.sha256(selected.encode("utf-8")).hexdigest()
    with _file_lock(paths, f"data-volume:{selected}", f"runtime-{digest}.lock"):
        yield


@contextmanager
def _active_runtime_lock(paths: HostPaths) -> Iterator[None]:
    with _file_lock(paths, "active-runtime", "active-runtime.lock"):
        yield


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


def _hashed_resource_name(data_volume: str) -> str:
    digest = hashlib.sha256(data_volume.encode("utf-8")).hexdigest()[:12]
    return f"localcloud-volume-{digest}"


def load_active_runtime(
    paths: HostPaths,
    diagnostics: list[dict[str, Any]] | None = None,
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
        raw = json.loads(encoded)
        if not isinstance(raw, dict):
            raise ValueError("state must be a JSON object")
        expected = {"schema_version", "data_volume", "image", "container_id"}
        if set(raw) != expected:
            raise ValueError(
                f"state fields must be exactly {', '.join(sorted(expected))}"
            )
        if raw["schema_version"] != ACTIVE_RUNTIME_SCHEMA_VERSION:
            raise ValueError(
                f"unsupported schema version {raw['schema_version']!r}"
            )
        return ActiveRuntime(
            schema_version=ACTIVE_RUNTIME_SCHEMA_VERSION,
            data_volume=validate_data_volume(raw["data_volume"]),
            image=_non_blank_string("image", raw["image"]),
            container_id=_non_blank_string("container_id", raw["container_id"]),
        )
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


def save_active_runtime(paths: HostPaths, runtime: ActiveRuntime) -> None:
    if runtime.schema_version != ACTIVE_RUNTIME_SCHEMA_VERSION:
        raise HostError(
            "invalid_active_runtime",
            f"Unsupported active runtime schema: {runtime.schema_version}",
            {"schema_version": runtime.schema_version},
        )
    payload = {
        "schema_version": ACTIVE_RUNTIME_SCHEMA_VERSION,
        "data_volume": validate_data_volume(runtime.data_volume),
        "image": _non_blank_string("image", runtime.image),
        "container_id": _non_blank_string("container_id", runtime.container_id),
    }
    with _active_runtime_lock(paths):
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
                    json.dump(payload, state_file, sort_keys=True, separators=(",", ":"))
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


def clear_active_runtime(paths: HostPaths) -> None:
    with _active_runtime_lock(paths):
        try:
            paths.active_runtime.unlink(missing_ok=True)
        except OSError as error:
            raise HostError(
                "active_runtime_write_failed",
                f"Could not clear LocalCloud active runtime state: {paths.active_runtime}",
                {"path": str(paths.active_runtime), "cause": str(error)},
            ) from error


def clear_legacy_host_state(paths: HostPaths) -> dict[str, list[str]]:
    removed_files: list[str] = []
    for name in LEGACY_HOST_FILES:
        try:
            (paths.home / name).unlink()
            removed_files.append(name)
        except (FileNotFoundError, OSError):
            continue
    removed_locks: list[str] = []
    if paths.locks.is_dir():
        for entry in paths.locks.iterdir():
            if not entry.is_file() or not LEGACY_LOCK_PATTERN.fullmatch(entry.name):
                continue
            try:
                entry.unlink()
                removed_locks.append(entry.name)
            except (FileNotFoundError, OSError):
                continue
    return {"files": removed_files, "locks": removed_locks}


def _record_active_diagnostic(
    diagnostics: list[dict[str, Any]] | None, error: HostError
) -> None:
    if diagnostics is not None:
        diagnostics.append(error.to_dict())


def load_config(
    explicit: str | Path | None = None,
    remembered: str | None = None,
    *,
    directory: str | Path | None = None,
    data_volume: str | None = None,
    project: str | None = None,
    user: str | None = None,
    container_name: str | None = None,
    network_name: str | None = None,
    paths: HostPaths | None = None,
    active_runtime: ActiveRuntime | None | object = _ACTIVE_RUNTIME_UNSET,
    active_diagnostics: tuple[dict[str, Any], ...] = (),
) -> LocalCloudConfig:
    source_directory = _source_directory(directory)
    explicit_path = Path(explicit) if explicit is not None else None
    config_path = _select_config_path(source_directory, explicit_path, remembered)
    raw = _read_config(config_path)

    non_string_keys = sorted(str(key) for key in raw if not isinstance(key, str))
    if non_string_keys:
        _invalid_config("Configuration field names must be strings", fields=non_string_keys)
    _reject_legacy_config(raw)
    unknown = sorted(set(raw) - CONFIG_FIELDS)
    if unknown:
        _invalid_config("Unknown configuration fields", fields=unknown)

    diagnostics = list(active_diagnostics)
    host_paths = paths if paths is not None else HostPaths.from_environment()
    if active_runtime is _ACTIVE_RUNTIME_UNSET:
        active = load_active_runtime(host_paths, diagnostics)
    else:
        active = active_runtime
        if active is not None and not isinstance(active, ActiveRuntime):
            raise TypeError("active_runtime must be ActiveRuntime or None")
    if data_volume is not None:
        selected_data_volume = validate_data_volume(data_volume)
    elif "data_volume" in raw:
        if raw["data_volume"] is None:
            raise HostError(
                "invalid_data_volume",
                "data_volume must be a valid named Docker volume",
                {"data_volume": None},
            )
        selected_data_volume = validate_data_volume(raw["data_volume"])
    elif active is not None:
        selected_data_volume = active.data_volume
    else:
        selected_data_volume = DEFAULT_DATA_VOLUME

    selected_project = validate_project(
        project if project is not None else raw.get("project", DEFAULT_PROJECT)
    )
    selected_user = validate_user(
        user if user is not None else raw.get("user", DEFAULT_USER)
    )
    services = _services(raw.get("services"))
    seed_path, seed_yaml = _seed(raw.get("seed", "auto"), config_path, source_directory)
    data = raw.get("data", "persistent")
    if data not in {"persistent", "ephemeral"}:
        _invalid_config("data must be 'persistent' or 'ephemeral'", value=data)

    if "image" in raw:
        image = _non_blank_string("image", raw["image"])
    else:
        image = os.environ.get("LOCALCLOUD_IMAGE")
        if (
            image is None
            and active is not None
            and active.data_volume == selected_data_volume
        ):
            image = active.image
        image = _non_blank_string("image", image or DEFAULT_IMAGE)
    memory = _non_blank_string("memory", raw.get("memory", DEFAULT_MEMORY))
    docker_socket = _boolean("docker_socket", raw.get("docker_socket", False))
    transparent_network = _boolean(
        "transparent_network", raw.get("transparent_network", False)
    )
    environment = _environment(raw.get("environment", {}))

    defaults = default_resource_names(selected_data_volume)
    selected_container = _docker_name(
        "container_name",
        container_name
        if container_name is not None
        else raw.get("container_name", defaults["container"]),
    )
    selected_network = _docker_name(
        "network_name",
        network_name
        if network_name is not None
        else raw.get("network_name", defaults["network"]),
    )

    return LocalCloudConfig(
        data_volume=selected_data_volume,
        config_path=config_path,
        project=selected_project,
        user=selected_user,
        services=services,
        seed_path=seed_path,
        seed_yaml=seed_yaml,
        data=str(data),
        image=image,
        memory=memory,
        docker_socket=docker_socket,
        transparent_network=transparent_network,
        environment=environment,
        container_name=selected_container,
        network_name=selected_network,
        diagnostics=tuple(diagnostics),
    )


def _reject_legacy_config(raw: dict[object, object]) -> None:
    legacy = sorted(str(field) for field in LEGACY_CONFIG_FIELDS if field in raw)
    if not legacy:
        return
    if "volume_name" in raw:
        replacement = raw["volume_name"]
    else:
        instance = raw.get("instance")
        replacement = (
            f"{DEFAULT_DATA_VOLUME}-{instance}"
            if isinstance(instance, str) and instance.strip()
            else DEFAULT_DATA_VOLUME
        )
    raise HostError(
        "legacy_runtime_selector",
        "Configuration uses removed LocalCloud runtime selectors",
        {
            "fields": legacy,
            "replacement": {"data_volume": replacement},
            "recovery": "Replace the listed fields with data_volume",
        },
    )


def _source_directory(value: str | Path | None) -> Path:
    path = Path.cwd() if value is None else Path(value).expanduser()
    if not path.is_absolute():
        path = Path.cwd() / path
    try:
        resolved = path.resolve()
    except OSError as exc:
        raise HostError(
            "invalid_config_directory",
            f"Unable to resolve configuration directory: {path}",
            {"directory": str(path), "reason": str(exc)},
        ) from exc
    if not resolved.is_dir():
        raise HostError(
            "invalid_config_directory",
            f"Configuration directory is not an existing directory: {resolved}",
            {"directory": str(resolved)},
        )
    return resolved


def _select_config_path(
    directory: Path, explicit: Path | None, remembered: str | None
) -> Path | None:
    if explicit is not None:
        return _required_config_path(explicit, directory)

    local_config = directory / DEFAULT_CONFIG_NAME
    if local_config.exists():
        if not local_config.is_file():
            _invalid_config(
                f"Configuration path is not a file: {local_config}",
                config=str(local_config),
            )
        return local_config.resolve()

    if remembered and remembered != DEFAULTS_CONFIG_LABEL:
        return _required_config_path(Path(remembered), directory)
    return None


def _required_config_path(path: Path, directory: Path) -> Path:
    path = path.expanduser()
    if not path.is_absolute():
        path = directory / path
    resolved = path.resolve()
    if not resolved.exists():
        raise HostError(
            "config_missing",
            f"Configuration file does not exist: {resolved}",
            {"config": str(resolved)},
        )
    if not resolved.is_file():
        _invalid_config(
            f"Configuration path is not a file: {resolved}", config=str(resolved)
        )
    return resolved


def _read_config(path: Path | None) -> dict[object, object]:
    if path is None:
        return {}
    try:
        parsed = yaml.safe_load(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, yaml.YAMLError) as exc:
        raise HostError(
            "invalid_config",
            f"Unable to read configuration: {path}",
            {"config": str(path), "reason": str(exc)},
        ) from exc
    if parsed is None:
        return {}
    if not isinstance(parsed, dict):
        _invalid_config("Configuration must be a YAML object", config=str(path))
    return parsed


def validate_project(value: object | None) -> str:
    if value is None:
        return DEFAULT_PROJECT
    if not isinstance(value, str) or not PROJECT_ID_PATTERN.fullmatch(value.strip()):
        _invalid_config("project must be a valid GCP project ID", value=value)
    return value.strip()


def validate_user(value: object | None) -> str:
    if value is None:
        return DEFAULT_USER
    if not isinstance(value, str) or not USER_PATTERN.fullmatch(value.strip()):
        _invalid_config("user must be a non-empty local username or email", value=value)
    return value.strip()


def _docker_name(field: str, value: object) -> str:
    if not isinstance(value, str) or not DOCKER_NAME_PATTERN.fullmatch(value.strip()):
        _invalid_config(
            f"{field} must be a valid Docker resource name",
            field=field,
            value=value,
        )
    return value.strip()


def _services(value: object) -> tuple[str, ...] | None:
    if value is None or (
        isinstance(value, str) and value.strip().lower() == "default"
    ):
        return None
    if not isinstance(value, list) or not value:
        _invalid_config("services must be 'default' or a non-empty list", value=value)
    normalized: list[str] = []
    seen: set[str] = set()
    for item in value:
        if not isinstance(item, str) or not item.strip():
            _invalid_config("service IDs must be non-empty strings", value=item)
        service = item.strip().lower()
        if service not in seen:
            normalized.append(service)
            seen.add(service)
    return tuple(normalized)


def _seed(
    value: object, config_path: Path | None, directory: Path
) -> tuple[Path | None, str | None]:
    if value is None:
        return None, None
    if not isinstance(value, str) or not value.strip():
        _invalid_config("seed must be 'auto', null, or a file path", value=value)

    base = config_path.parent if config_path is not None else directory
    if value.strip() == "auto":
        path = base / "seed.yaml"
        if not path.exists():
            return None, None
    else:
        path = Path(value.strip()).expanduser()
        if not path.is_absolute():
            path = base / path

    resolved = path.resolve()
    if not resolved.is_file():
        _invalid_config(f"Seed file does not exist: {resolved}", seed=str(resolved))
    try:
        return resolved, resolved.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        raise HostError(
            "invalid_config",
            f"Unable to read seed file: {resolved}",
            {"seed": str(resolved), "reason": str(exc)},
        ) from exc


def _boolean(name: str, value: object) -> bool:
    if not isinstance(value, bool):
        _invalid_config(f"{name} must be a boolean", value=value)
    return value


def _non_blank_string(name: str, value: object) -> str:
    if not isinstance(value, str) or not value.strip():
        _invalid_config(f"{name} must be a non-empty string", value=value)
    return value.strip()


def _environment(value: object) -> dict[str, str]:
    if not isinstance(value, dict):
        _invalid_config("environment must be an object", value=value)
    normalized: dict[str, str] = {}
    for key, item in value.items():
        if not isinstance(key, str) or not ENVIRONMENT_KEY_PATTERN.fullmatch(key):
            _invalid_config(
                "environment keys must match LOCALCLOUD_[A-Z0-9_]+", value=key
            )
        if isinstance(item, (dict, list)):
            _invalid_config("environment values must be scalars", key=key)
        if isinstance(item, bool):
            normalized[key] = str(item).lower()
        elif item is None:
            normalized[key] = ""
        else:
            normalized[key] = str(item)
    return normalized


def _invalid_config(message: str, **details: object) -> None:
    raise HostError("invalid_config", message, details)
