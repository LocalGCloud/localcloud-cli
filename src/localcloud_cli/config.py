from __future__ import annotations

import copy
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
ACTIVE_RUNTIME_SCHEMA_VERSION = 3
ACTIVE_RUNTIME_FILE = "active-runtime.json"
LEGACY_LOCK_PATTERN = re.compile(r"^[0-9a-f]{64}\.lock$")
LEGACY_HOST_FILES = ("state.db", "daemon.sock", "daemon.pid", "daemon.lock", "daemon.log")
DATA_VOLUME_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,254}$")
DOCKER_NAME_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$")
PROJECT_ID_PATTERN = re.compile(r"^[a-z][a-z0-9-]{4,28}[a-z0-9]$")
USER_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._+@-]{0,126}$")
ENVIRONMENT_KEY_PATTERN = re.compile(r"^LOCALCLOUD_[A-Z0-9_]+$")
CONFIG_FIELDS = {
    "version",
    "context",
    "host",
    "server",
    "services",
    "infrastructure",
}
CONTEXT_FIELDS = {"project", "user"}
HOST_CONFIG_FIELDS = {
    "data_volume",
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
SERVICES_FIELDS = {"enabled", "catalog"}
FLAT_FIELD_REPLACEMENTS = {
    "project": "context.project",
    "user": "context.user",
    "services": "services.enabled",
    **{field: f"host.{field}" for field in HOST_CONFIG_FIELDS},
}
RESERVED_HOST_ENVIRONMENT = {
    "LOCALCLOUD_CONFIG",
    "LOCALCLOUD_PROJECT",
    "LOCALCLOUD_DATA_DIR",
    "LOCALCLOUD_SERVICES",
}
LEGACY_CONFIG_FIELDS = {"instance", "volume_name"}
SKIP_CONFIG_VALIDATION_ENV = "LOCALCLOUD_SKIP_CONFIG_VALIDATION"
AMBIGUOUS_PLAIN_SCALARS = {
    "yes",
    "no",
    "on",
    "off",
    "true",
    "false",
    "null",
    "~",
}
EXACT_SPECIAL_SCALARS = {"true", "false", "null", "~"}
_ACTIVE_RUNTIME_UNSET = object()



class _StrictLoader(yaml.SafeLoader):
    pass


_StrictLoader.yaml_implicit_resolvers = copy.deepcopy(
    yaml.SafeLoader.yaml_implicit_resolvers
)
for _first_character, _resolvers in list(
    _StrictLoader.yaml_implicit_resolvers.items()
):
    _StrictLoader.yaml_implicit_resolvers[_first_character] = [
        resolver
        for resolver in _resolvers
        if resolver[0]
        not in {"tag:yaml.org,2002:bool", "tag:yaml.org,2002:null"}
    ]
_StrictLoader.add_implicit_resolver(
    "tag:yaml.org,2002:bool",
    re.compile(r"^(?:true|false)$"),
    list("tf"),
)
_StrictLoader.add_implicit_resolver(
    "tag:yaml.org,2002:null",
    re.compile(r"^(?:null|~)$"),
    ["n", "~"],
)


def _validate_yaml_node(
    node: yaml.Node,
    path: Path,
    active: set[int] | None = None,
    validated: set[int] | None = None,
) -> None:
    active = set() if active is None else active
    validated = set() if validated is None else validated
    identity = id(node)
    if identity in active:
        _invalid_config(
            "Recursive YAML aliases are not supported",
            config=str(path),
        )
    if identity in validated:
        return

    active.add(identity)
    try:
        if isinstance(node, yaml.MappingNode):
            seen: set[str] = set()
            for key_node, value_node in node.value:
                if (
                    isinstance(key_node, yaml.ScalarNode)
                    and (
                        key_node.value == "<<"
                        or key_node.tag == "tag:yaml.org,2002:merge"
                    )
                ):
                    _invalid_config(
                        "YAML merge keys (<<) are not supported",
                        config=str(path),
                    )
                if (
                    not isinstance(key_node, yaml.ScalarNode)
                    or key_node.tag != "tag:yaml.org,2002:str"
                ):
                    _invalid_config(
                        "Configuration field names must be strings",
                        config=str(path),
                    )
                if key_node.value in seen:
                    _invalid_config(
                        "Duplicate configuration field",
                        config=str(path),
                        field=key_node.value,
                    )
                seen.add(key_node.value)
                _validate_yaml_node(key_node, path, active, validated)
                _validate_yaml_node(value_node, path, active, validated)
        elif isinstance(node, yaml.SequenceNode):
            for value_node in node.value:
                _validate_yaml_node(value_node, path, active, validated)
        elif isinstance(node, yaml.ScalarNode) and node.style is None:
            lowered = node.value.lower()
            if (
                lowered in AMBIGUOUS_PLAIN_SCALARS
                and node.value not in EXACT_SPECIAL_SCALARS
            ):
                _invalid_config(
                    "Ambiguous YAML scalar must be quoted when used as a string",
                    config=str(path),
                    value=node.value,
                )
    finally:
        active.remove(identity)
    validated.add(identity)

@dataclass(frozen=True)
class ActiveRuntime:
    schema_version: int
    data_volume: str
    image: str
    container_id: str
    container_name: str | None = None
    network_name: str | None = None


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
    """Return host-owned Docker settings that define runtime identity."""
    return {
        "data_volume": config.data_volume,
        "config_path": str(config.config_path)
        if config.config_path is not None
        else None,
        "data": config.data,
        "image": config.image,
        "memory": config.memory,
        "docker_socket": config.docker_socket,
        "transparent_network": config.transparent_network,
        "services": list(config.services) if config.services is not None else None,
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


def load_active_runtime(
    paths: HostPaths,
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


def save_active_runtime(paths: HostPaths, runtime: ActiveRuntime) -> None:
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


def _env_flag(name: str) -> bool:
    return os.environ.get(name, "").strip().lower() in {"1", "true", "yes", "on"}


def _enforce_or_record(
    skip_validation: bool,
    diagnostics: list[dict[str, Any]],
    violated: bool,
    message: str,
    **details: object,
) -> None:
    """Raise unless skip_validation is set, in which case record and continue.

    Only ever used for the CLI's closed-set beliefs about the shared
    document's shape (known fields, supported version) — never for values the
    CLI itself consumes directly to drive Docker, since those are not a
    CLI/LocalCloud drift risk and getting them wrong breaks the CLI, not just
    Java-owned semantics.
    """
    if not violated:
        return
    error = HostError("invalid_config", message, details)
    if not skip_validation:
        raise error
    diagnostics.append(
        {
            **error.to_dict(),
            "code": "config_validation_skipped",
            "bypassed_code": error.code,
        }
    )


def _reject_or_record(
    skip_validation: bool,
    diagnostics: list[dict[str, Any]],
    reject: Any,
    raw: dict[object, object],
) -> None:
    try:
        reject(raw)
    except HostError as error:
        if not skip_validation:
            raise
        diagnostics.append(
            {
                **error.to_dict(),
                "code": "config_validation_skipped",
                "bypassed_code": error.code,
            }
        )


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
    tls: bool | None = None,
    memory: str | None = None,
    image: str | None = None,
    services: list[str] | str | None = None,
    paths: HostPaths | None = None,
    active_runtime: ActiveRuntime | None | object = _ACTIVE_RUNTIME_UNSET,
    active_diagnostics: tuple[dict[str, Any], ...] = (),
    skip_validation: bool = False,
) -> LocalCloudConfig:
    source_directory = _source_directory(directory)
    explicit_path = Path(explicit) if explicit is not None else None
    config_path = _select_config_path(source_directory, explicit_path, remembered)
    raw = _read_config(config_path)
    effective_skip_validation = skip_validation or _env_flag(
        SKIP_CONFIG_VALIDATION_ENV
    )
    diagnostics = list(active_diagnostics)
    _validate_config_document(
        raw, skip_validation=effective_skip_validation, diagnostics=diagnostics
    )

    context = raw.get("context") or {}
    host = raw.get("host") or {}
    services_section = raw.get("services") or {}

    def host_value(field: str, default: object) -> object:
        value = host.get(field)
        return default if value is None else value

    host_paths = paths if paths is not None else HostPaths.from_environment()
    if active_runtime is _ACTIVE_RUNTIME_UNSET:
        configured_volume = host.get("data_volume")
        requested_runtime_volume = (
            data_volume
            if data_volume is not None
            else configured_volume
            if isinstance(configured_volume, str)
            else None
        )
        active = load_active_runtime(
            host_paths,
            diagnostics,
            data_volume=requested_runtime_volume,
        )
    else:
        active = active_runtime
        if active is not None and not isinstance(active, ActiveRuntime):
            raise TypeError("active_runtime must be ActiveRuntime or None")

    configured_volume = host.get("data_volume")
    if data_volume is not None:
        selected_data_volume = validate_data_volume(data_volume)
    elif configured_volume is not None:
        selected_data_volume = validate_data_volume(configured_volume)
    elif active is not None:
        selected_data_volume = active.data_volume
    else:
        selected_data_volume = DEFAULT_DATA_VOLUME

    selected_project = validate_project(
        project
        if project is not None
        else context.get("project", DEFAULT_PROJECT)
    )
    configured_user = context.get("user")
    selected_user = validate_user(
        user
        if user is not None
        else configured_user
        if configured_user is not None
        else DEFAULT_USER
    )
    selected_services = (
        _services(services)
        if services is not None
        else _services(services_section["enabled"])
        if "enabled" in services_section
        else None
    )
    seed_path, seed_yaml = _seed(
        host_value("seed", "auto"), config_path, source_directory
    )
    data = host_value("data", "persistent")
    if data not in {"persistent", "ephemeral"}:
        _invalid_config(
            "host.data must be 'persistent' or 'ephemeral'", value=data
        )

    if image is not None:
        image = _non_blank_string("host.image", image)
    else:
        configured_image = host.get("image")
        if configured_image is not None:
            image = _non_blank_string("host.image", configured_image)
        else:
            image = os.environ.get("LOCALCLOUD_IMAGE")
            if (
                image is None
                and active is not None
                and active.data_volume == selected_data_volume
            ):
                image = active.image
            image = _non_blank_string("host.image", image or DEFAULT_IMAGE)

    memory = _non_blank_string(
        "host.memory",
        memory if memory is not None else host_value("memory", DEFAULT_MEMORY),
    )
    docker_socket = _boolean(
        "host.docker_socket", host_value("docker_socket", False)
    )
    transparent_network = _boolean(
        "host.transparent_network",
        host_value("transparent_network", False),
    )
    environment = _environment(host_value("environment", {}))
    if tls is not None:
        environment["LOCALCLOUD_TLS_ENABLED"] = "true" if tls else "false"
    else:
        environment.setdefault("LOCALCLOUD_TLS_ENABLED", "true")

    defaults = default_resource_names(selected_data_volume)
    active_for_volume = (
        active
        if active is not None and active.data_volume == selected_data_volume
        else None
    )
    configured_container = host.get("container_name")
    default_container = (
        active_for_volume.container_name
        if active_for_volume and active_for_volume.container_name
        else defaults["container"]
    )
    selected_container = _docker_name(
        "host.container_name",
        container_name
        if container_name is not None
        else configured_container
        if configured_container is not None
        else default_container,
    )
    configured_network = host.get("network_name")
    default_network = (
        active_for_volume.network_name
        if active_for_volume and active_for_volume.network_name
        else defaults["network"]
    )
    selected_network = _docker_name(
        "host.network_name",
        network_name
        if network_name is not None
        else configured_network
        if configured_network is not None
        else default_network,
    )

    return LocalCloudConfig(
        data_volume=selected_data_volume,
        config_path=config_path,
        project=selected_project,
        user=selected_user,
        services=selected_services,
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


def _validate_config_document(
    raw: dict[object, object],
    *,
    skip_validation: bool,
    diagnostics: list[dict[str, Any]],
) -> None:
    non_string_keys = sorted(str(key) for key in raw if not isinstance(key, str))
    if non_string_keys:
        _invalid_config(
            "Configuration field names must be strings", fields=non_string_keys
        )
    _reject_or_record(skip_validation, diagnostics, _reject_legacy_config, raw)
    _reject_or_record(skip_validation, diagnostics, _reject_flat_config, raw)
    unknown = sorted(set(raw) - CONFIG_FIELDS)
    _enforce_or_record(
        skip_validation,
        diagnostics,
        bool(unknown),
        "Unknown configuration fields",
        fields=unknown,
    )

    _enforce_or_record(
        skip_validation,
        diagnostics,
        "version" in raw and (type(raw["version"]) is not int or raw["version"] != 1),
        "version must be the supported integer value 1",
        value=raw.get("version"),
    )

    context = raw.get("context")
    if "context" in raw:
        if not isinstance(context, dict):
            _invalid_config("context must be an object", value=context)
        else:
            unknown_context = sorted(set(context) - CONTEXT_FIELDS)
            _enforce_or_record(
                skip_validation,
                diagnostics,
                bool(unknown_context),
                "Unknown context fields",
                fields=unknown_context,
            )
            if "project" in context and context["project"] is None:
                _invalid_config("context.project cannot be null")

    host = raw.get("host")
    if host is not None:
        if not isinstance(host, dict):
            _invalid_config("host must be an object or null", value=host)
        else:
            unknown_host = sorted(set(host) - HOST_CONFIG_FIELDS)
            _enforce_or_record(
                skip_validation,
                diagnostics,
                bool(unknown_host),
                "Unknown host fields",
                fields=unknown_host,
            )

    server = raw.get("server")
    if "server" in raw and not isinstance(server, dict):
        _invalid_config("server must be an object", value=server)

    services = raw.get("services")
    if "services" in raw:
        if not isinstance(services, dict):
            _invalid_config("services must be an object", value=services)
        else:
            unknown_services = sorted(set(services) - SERVICES_FIELDS)
            _enforce_or_record(
                skip_validation,
                diagnostics,
                bool(unknown_services),
                "Unknown services fields",
                fields=unknown_services,
            )
            if "enabled" in services and services["enabled"] is None:
                _invalid_config("services.enabled cannot be null")
            if "catalog" in services and not isinstance(
                services["catalog"], dict
            ):
                _invalid_config(
                    "services.catalog must be an object",
                    value=services["catalog"],
                )

    infrastructure = raw.get("infrastructure")
    if "infrastructure" in raw and not isinstance(infrastructure, dict):
        _invalid_config(
            "infrastructure must be an object", value=infrastructure
        )


def _reject_flat_config(raw: dict[object, object]) -> None:
    removed = sorted(
        field
        for field in FLAT_FIELD_REPLACEMENTS
        if field in raw
        and not (field == "services" and isinstance(raw[field], dict))
    )
    if not removed:
        return
    details: dict[str, object] = {
        "fields": removed,
        "replacement": {
            field: FLAT_FIELD_REPLACEMENTS[field] for field in removed
        },
    }
    if "seed" in removed and raw.get("seed") is None:
        details["seed_null_migration"] = {
            "from": "seed: null",
            "replacement": "host.seed: disabled",
        }
    raise HostError(
        "removed_flat_config",
        "Configuration uses the removed flat LocalCloud schema",
        details,
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
            "replacement": {"host.data_volume": replacement},
            "recovery": "Replace the listed fields with host.data_volume",
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

    configured = os.environ.get("LOCALCLOUD_CONFIG")
    if configured is not None and configured.strip():
        return _required_config_path(Path(configured), directory)

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
        text = path.read_text(encoding="utf-8")
        node = yaml.compose(text, Loader=_StrictLoader)
        if node is None:
            return {}
        _validate_yaml_node(node, path)
        parsed = yaml.load(text, Loader=_StrictLoader)
    except HostError:
        raise
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
    if isinstance(value, str) and value.strip() == "default":
        return None
    if not isinstance(value, list) or not value:
        _invalid_config(
            "services.enabled must be 'default' or a non-empty list",
            value=value,
        )
    normalized: list[str] = []
    seen: set[str] = set()
    for item in value:
        if not isinstance(item, str) or not item.strip():
            _invalid_config(
                "services.enabled IDs must be non-empty strings", value=item
            )
        service = item.strip().lower()
        if service not in seen:
            normalized.append(service)
            seen.add(service)
    return tuple(normalized)


def _seed(
    value: object, config_path: Path | None, directory: Path
) -> tuple[Path | None, str | None]:
    if not isinstance(value, str) or not value.strip():
        _invalid_config(
            "host.seed must be 'auto', 'disabled', or a file path", value=value
        )

    selected = value.strip()
    if selected == "disabled":
        return None, None
    base = config_path.parent if config_path is not None else directory
    if selected == "auto":
        path = base / "seed.yaml"
        if not path.exists():
            return None, None
    else:
        path = Path(selected).expanduser()
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
        if key in RESERVED_HOST_ENVIRONMENT:
            _invalid_config(
                f"host.environment cannot set controller-owned {key}",
                key=key,
            )
        if isinstance(item, (dict, list)):
            _invalid_config("environment values must be scalars", key=key)
        if item is None:
            continue
        if isinstance(item, bool):
            normalized[key] = str(item).lower()
        else:
            normalized[key] = str(item)
    return normalized


def _invalid_config(message: str, **details: object) -> None:
    raise HostError("invalid_config", message, details)
