from __future__ import annotations

import fcntl
import hashlib
import json
import os
import re
import threading
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator

import yaml

from . import __version__

from .errors import HostError

DEFAULT_CONFIG_NAME = "localcloud.yaml"
DEFAULT_IMAGE = f"jaysen2apache/localcloud:{__version__}"
DEFAULT_MEMORY = "4g"
DEFAULTS_CONFIG_LABEL = "<defaults>"
DEFAULT_INSTANCE = "default"
DEFAULT_PROJECT = "local-gcp-project"
DEFAULT_USER = "local-developer"
INSTANCE_NAME_PATTERN = re.compile(r"^[a-z0-9][a-z0-9._-]{0,62}$")
DOCKER_NAME_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$")
PROJECT_ID_PATTERN = re.compile(r"^[a-z][a-z0-9-]{4,28}[a-z0-9]$")
USER_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._+@-]{0,126}$")
ENVIRONMENT_KEY_PATTERN = re.compile(r"^LOCALCLOUD_[A-Z0-9_]+$")
CONFIG_FIELDS = {
    "instance",
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
    "volume_name",
}


@dataclass(frozen=True)
class LocalCloudConfig:
    instance: str
    config_path: Path | None
    config_hash: str
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
    volume_name: str


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


_LOCKS_GUARD = threading.Lock()
_PROCESS_LOCKS: dict[tuple[str, str], threading.Lock] = {}


@contextmanager
def instance_lock(paths: HostPaths, instance: str) -> Iterator[None]:
    instance = validate_instance(instance)
    lock_name = (
        "localcloud.lock"
        if instance == DEFAULT_INSTANCE
        else f"localcloud-{instance}.lock"
    )
    lock_key = (str(paths.home), instance)
    with _LOCKS_GUARD:
        process_lock = _PROCESS_LOCKS.setdefault(lock_key, threading.Lock())

    with process_lock:
        paths.locks.mkdir(mode=0o700, parents=True, exist_ok=True)
        os.chmod(paths.home, 0o700)
        os.chmod(paths.locks, 0o700)
        lock_path = paths.locks / lock_name
        with lock_path.open("a+b") as lock_file:
            os.chmod(lock_path, 0o600)
            fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
            try:
                yield
            finally:
                fcntl.flock(lock_file.fileno(), fcntl.LOCK_UN)


def validate_instance(value: object | None) -> str:
    if value is None:
        return DEFAULT_INSTANCE
    if not isinstance(value, str) or not INSTANCE_NAME_PATTERN.fullmatch(value.strip()):
        raise HostError(
            "invalid_instance",
            "Instance names must match [a-z0-9][a-z0-9._-]{0,62}",
            {"instance": value},
        )
    return value.strip()


def default_resource_names(instance: str) -> dict[str, str]:
    instance = validate_instance(instance)
    if instance == DEFAULT_INSTANCE:
        return {
            "container": "localcloud",
            "network": "localcloud",
            "volume": "localcloud-data",
        }
    return {
        "container": f"localcloud-{instance}",
        "network": f"localcloud-{instance}",
        "volume": f"localcloud-data-{instance}",
    }


def load_config(
    explicit: str | Path | None = None,
    remembered: str | None = None,
    *,
    directory: str | Path | None = None,
    instance: str | None = None,
    project: str | None = None,
    user: str | None = None,
    container_name: str | None = None,
    network_name: str | None = None,
    volume_name: str | None = None,
) -> LocalCloudConfig:
    source_directory = _source_directory(directory)
    explicit_path = Path(explicit) if explicit is not None else None
    config_path = _select_config_path(source_directory, explicit_path, remembered)
    raw = _read_config(config_path)

    non_string_keys = sorted(str(key) for key in raw if not isinstance(key, str))
    if non_string_keys:
        _invalid_config("Configuration field names must be strings", fields=non_string_keys)
    unknown = sorted(set(raw) - CONFIG_FIELDS)
    if unknown:
        _invalid_config("Unknown configuration fields", fields=unknown)

    selected_instance = validate_instance(instance if instance is not None else raw.get("instance"))
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

    image = raw.get("image")
    if image is None:
        image = os.environ.get("LOCALCLOUD_IMAGE") or DEFAULT_IMAGE
    image = _non_blank_string("image", image)
    memory = _non_blank_string("memory", raw.get("memory", DEFAULT_MEMORY))
    docker_socket = _boolean("docker_socket", raw.get("docker_socket", False))
    transparent_network = _boolean(
        "transparent_network", raw.get("transparent_network", False)
    )
    environment = _environment(raw.get("environment", {}))

    defaults = default_resource_names(selected_instance)
    selected_container = _docker_name(
        "container_name",
        container_name if container_name is not None else raw.get("container_name", defaults["container"]),
    )
    selected_network = _docker_name(
        "network_name",
        network_name if network_name is not None else raw.get("network_name", defaults["network"]),
    )
    selected_volume = _docker_name(
        "volume_name",
        volume_name if volume_name is not None else raw.get("volume_name", defaults["volume"]),
    )

    # Request context and seed contents do not define Docker infrastructure.
    instance_settings = {
        "instance": selected_instance,
        "services": list(services) if services is not None else None,
        "data": data,
        "image": image,
        "memory": memory,
        "docker_socket": docker_socket,
        "transparent_network": transparent_network,
        "environment": environment,
        "container_name": selected_container,
        "network_name": selected_network,
        "volume_name": selected_volume,
    }
    config_hash = hashlib.sha256(
        json.dumps(
            instance_settings,
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
        ).encode("utf-8")
    ).hexdigest()

    return LocalCloudConfig(
        instance=selected_instance,
        config_path=config_path,
        config_hash=config_hash,
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
        volume_name=selected_volume,
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
