from __future__ import annotations

import copy
import fcntl
import hashlib
import json
import os
import re
import subprocess
import tempfile
import threading
import unicodedata
from contextlib import contextmanager
from dataclasses import dataclass, field
from functools import lru_cache
from importlib import resources
from pathlib import Path
from typing import Any, Iterator, Literal, cast

import yaml

from .constants import (
    DEFAULT_CONFIG_NAME,
    DEFAULTS_CONFIG_LABEL,
    DEFAULT_DATA_VOLUME,
    DEFAULT_IMAGE,
    DEFAULT_MEMORY,
    DEFAULT_PROJECT,
    DEFAULT_TLS_PORT,
    DEFAULT_USER,
)

from .errors import ConfigError, HostError
from .state import (
    ACTIVE_RUNTIME_FILE,
    ACTIVE_RUNTIME_SCHEMA_VERSION,
    ActiveRuntime,
    DOCKER_NAME_PATTERN,
    clear_active_runtime,
    data_volume_lock,
    default_resource_names,
    load_active_runtime,
    save_active_runtime,
    validate_data_volume,
)

LEGACY_LOCK_PATTERN = re.compile(r"^[0-9a-f]{64}\.lock$")
LEGACY_HOST_FILES = ("state.db", "daemon.sock", "daemon.pid", "daemon.lock", "daemon.log")
PROJECT_ID_PATTERN = re.compile(r"^[a-z][a-z0-9-]{4,28}[a-z0-9]$")
USER_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._+@-]{0,126}$")
ENVIRONMENT_KEY_PATTERN = re.compile(r"^LOCALCLOUD_[A-Z0-9_]+$")
CONFIG_FIELDS = {
    "version",
    "context",
    "host",
    "tls",
    "mcp",
    "updater",
    "server",
    "services",
    "infrastructure",
    "observability",
}
CONTEXT_FIELDS = {"project", "user"}
TLS_FIELDS = {"enabled", "port", "certificate", "private_key"}
MCP_FIELDS = {"write", "destructive", "allow_remote"}
HOST_CONFIG_FIELDS = {
    "data_volume",
    "backup_dir",
    "seed",  # Retired CLI option; accepted but ignored for existing configs.
    "data",
    "image",
    "memory",
    "docker_socket",
    "transparent_network",
    "environment",
    "container_name",
    "network_name",
    "port_range",
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
    "LOCALCLOUD_DOCKER_ACCESS",
    "LOCALCLOUD_PROJECT",
    "LOCALCLOUD_DATA_DIR",
    "LOCALCLOUD_SERVICES",
    "LOCALCLOUD_RUNTIME_NETWORK",
    "LOCALCLOUD_DATA_VOLUME",
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
DOCKER_ACCESS_ENV = "LOCALCLOUD_DOCKER_ACCESS"
DOCKER_DEPENDENCY = "docker"
DockerAccessMode = Literal["auto", "true", "false"]
ProjectSource = Literal["flag", "config", "git", "default"]



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
class LocalCloudConfig:
    data_volume: str
    config_path: Path | None
    config_hash: str = field(init=False)
    project: str
    user: str
    services: tuple[str, ...] | None
    data: str
    image: str
    memory: str
    docker_socket: bool
    transparent_network: bool
    environment: dict[str, str]
    container_name: str
    network_name: str
    diagnostics: tuple[dict[str, Any], ...]
    docker_socket_mode: DockerAccessMode = "auto"
    effective_services: tuple[str, ...] = ()
    tls_enabled: bool = False
    tls_port: int = DEFAULT_TLS_PORT
    strict_port_validation: bool = False
    local_only: bool = False
    # Host port the Cloud SQL MySQL companion publishes, or None when it cannot
    # start. Derived from services, so it is not part of the runtime identity.
    mysql_port: int | None = None
    # Container ports the enabled services listen on (from the catalog). A
    # runtime off the canonical ports publishes only these.
    service_ports: tuple[int, ...] = ()
    # host.port_range / --port-range: host ports to use instead of the
    # canonical and built-in fallback ports.
    port_range: tuple[int, int] | None = None
    # Where `project` came from: "flag" (--project-id), "config" (context.project
    # of this directory's or an explicit config), "git" (the repository's main
    # checkout name), or "default" (built in, or a shared config's
    # context.project). Not part of the runtime identity.
    project_source: ProjectSource = "default"

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
    settings = {
        "config_path": (
            str(config.config_path) if config.config_path is not None else None
        ),
        "data_volume": config.data_volume,
        "data": config.data,
        "image": config.image,
        "memory": config.memory,
        "docker_socket_mode": config.docker_socket_mode,
        "docker_socket": config.docker_socket,
        "transparent_network": config.transparent_network,
        "local_only": config.local_only,
        "services": list(config.services) if config.services is not None else None,
        "effective_services": list(config.effective_services),
        "environment": dict(config.environment),
        "container_name": config.container_name,
        "network_name": config.network_name,
    }
    if config.tls_enabled:
        settings["tls_enabled"] = True
        settings["tls_port"] = config.tls_port
    if config.port_range is not None:
        settings["port_range"] = "{}-{}".format(*config.port_range)
    return settings


def resolve_docker_socket(
    mode: DockerAccessMode,
    services: tuple[str, ...] | None,
    catalog: dict[str, dict[str, object]],
) -> bool:
    """Resolve whether the launcher must mount the host Docker socket."""
    if mode == "true":
        return True
    if mode == "false":
        return False
    enabled = (
        set(services)
        if services is not None
        else {
            service_id
            for service_id, definition in catalog.items()
            if definition.get("defaultEnabled") is True
        }
    )
    return any(
        service_id in enabled
        and DOCKER_DEPENDENCY
        in definition.get("runtimeDependencies", [])
        for service_id, definition in catalog.items()
    )


@lru_cache(maxsize=1)
def _packaged_defaults() -> dict[str, object]:
    json_resource = resources.files("localcloud_cli").joinpath(
        "defaults/localcloud.v1.json"
    )
    try:
        defaults = json.loads(json_resource.read_text(encoding="utf-8"))
    except Exception:
        defaults_resource = resources.files("localcloud_cli").joinpath(
            "defaults/localcloud.v1.yaml"
        )
        defaults = yaml.safe_load(defaults_resource.read_text(encoding="utf-8"))
    if not isinstance(defaults, dict):
        raise RuntimeError("Packaged LocalCloud defaults are invalid")
    return defaults


@lru_cache(maxsize=1)
def _packaged_service_catalog() -> dict[str, dict[str, object]]:
    defaults = _packaged_defaults()
    services = defaults.get("services")
    catalog = services.get("catalog") if isinstance(services, dict) else None
    if not isinstance(catalog, dict) or any(
        not isinstance(service_id, str) or not isinstance(definition, dict)
        for service_id, definition in catalog.items()
    ):
        raise RuntimeError("Packaged LocalCloud service catalog is invalid")
    return catalog


def _packaged_docker_access_default() -> object:
    defaults = _packaged_defaults()
    host = defaults.get("host")
    if not isinstance(host, dict) or "docker_socket" not in host:
        raise RuntimeError(
            "Packaged LocalCloud defaults do not define host.docker_socket"
        )
    return host["docker_socket"]


def _effective_service_catalog(
    services_section: dict[object, object],
) -> dict[str, dict[str, object]]:
    packaged_catalog = _packaged_service_catalog()
    overrides = services_section.get("catalog") or {}
    if not isinstance(overrides, dict):
        _invalid_config("services.catalog must be an object", value=overrides)
    if not overrides:
        return {key: dict(val) for key, val in packaged_catalog.items()}
    catalog = copy.deepcopy(packaged_catalog)
    for service_id, override in overrides.items():
        if not isinstance(service_id, str):
            _invalid_config(
                "services.catalog field names must be strings",
                value=service_id,
            )
        if override is None:
            catalog.pop(service_id, None)
            continue
        if not isinstance(override, dict):
            _invalid_config(
                "services.catalog entries must be objects",
                field=f"services.catalog.{service_id}",
                value=override,
            )
        if "runtimeDependencies" in override:
            canonical_dependencies = packaged_catalog.get(service_id, {}).get(
                "runtimeDependencies"
            )
            if override["runtimeDependencies"] != canonical_dependencies:
                _invalid_config(
                    "services.catalog runtimeDependencies is immutable",
                    field=(
                        f"services.catalog.{service_id}.runtimeDependencies"
                    ),
                )
        definition = catalog.setdefault(service_id, {})
        _overlay_mapping(definition, override)
    return catalog


def _service_ports(
    catalog: dict[str, dict[str, object]],
    services: tuple[str, ...],
) -> tuple[int, ...]:
    """Every port the enabled services' catalog entries name.

    Walks each entry generically (no catalog field names): integers under a
    port-named key (`port`, `...Port`, `..._port`) or inside a `...Ports` map.
    """
    ports: set[int] = set()

    def collect(value: object, key: str, in_port_map: bool) -> None:
        if isinstance(value, dict):
            for child_key, child in value.items():
                collect(child, str(child_key), key.endswith("Ports"))
        elif (
            isinstance(value, int)
            and not isinstance(value, bool)
            and (in_port_map or key == "port" or key.endswith(("Port", "_port")))
        ):
            ports.add(value)

    for service_id in services:
        collect(catalog.get(service_id) or {}, service_id, False)
    return tuple(sorted(ports))


def _port_range(name: str, value: object) -> tuple[int, int]:
    text = str(value).strip() if isinstance(value, (str, int)) else ""
    start_text, separator, end_text = text.partition("-")
    if not (
        separator
        and start_text.strip().isdigit()
        and end_text.strip().isdigit()
        and 1024 <= int(start_text) <= int(end_text) <= 65535
    ):
        _invalid_config(
            f"{name} must be START-END with 1024 <= START <= END <= 65535",
            field=name,
            value=value,
        )
    return int(start_text), int(end_text)


def _mysql_port(
    catalog: dict[str, dict[str, object]],
    services: tuple[str, ...],
    environment: dict[str, str],
) -> int | None:
    """Host port of the Cloud SQL MySQL companion, or None when it never starts.

    The server publishes this port from its own companion container, outside
    the runtime container's port block, so the CLI checks and plans it here."""
    if "cloudsql" not in services:
        return None
    definition = catalog.get("cloudsql") or {}
    settings = definition.get("config")
    if isinstance(settings, dict):
        enabled = settings.get("mysql_enabled", True)
        if enabled is False or str(enabled).strip().lower() == "false":
            return None
    if "LOCALCLOUD_MYSQL_PORT" in environment:
        value = environment["LOCALCLOUD_MYSQL_PORT"].strip()
        # 0 lets Docker pick a free port, so there is nothing to reserve.
        if value == "0":
            return None
        return _port("host.environment.LOCALCLOUD_MYSQL_PORT", value)
    ports = definition.get("additionalPorts")
    value = ports.get("mysql") if isinstance(ports, dict) else None
    if value is None:
        return None
    return _port("services.catalog.cloudsql.additionalPorts.mysql", value)


def _overlay_mapping(
    base: dict[str, object],
    overlay: dict[object, object],
) -> None:
    for key, value in overlay.items():
        if not isinstance(key, str):
            _invalid_config("Configuration field names must be strings", value=key)
        if value is None:
            base.pop(key, None)
        elif isinstance(value, dict) and isinstance(base.get(key), dict):
            nested = base[key]
            assert isinstance(nested, dict)
            _overlay_mapping(nested, value)
        else:
            base[key] = copy.deepcopy(value)


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
    strict_port_validation: bool = False,
    local_only: bool = False,
    port_range: str | None = None,
    project_from_git: bool = False,
) -> LocalCloudConfig:
    """`project_from_git` lets the git repository containing `directory` name
    the project when neither --project-id nor this directory's config does
    (used by `lc mcp`, so each repository's agents get their own project)."""
    source_directory = _source_directory(directory)
    host_paths = paths if paths is not None else HostPaths.from_environment()
    explicit_path = Path(explicit) if explicit is not None else None
    config_path, config_source = _select_config_path(
        source_directory,
        explicit_path,
        remembered,
        host_paths.home / DEFAULT_CONFIG_NAME,
    )
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

    selected_project, project_source = _select_project(
        project,
        context.get("project"),
        config_source,
        source_directory,
        from_git=project_from_git,
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
    effective_catalog = _effective_service_catalog(services_section)
    effective_services = (
        selected_services
        if selected_services is not None
        else tuple(
            service_id
            for service_id, definition in effective_catalog.items()
            if definition.get("defaultEnabled") is True
        )
    )
    data = host_value("data", "persistent")
    if not isinstance(data, str) or data not in {"persistent", "ephemeral"}:
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
    docker_access_override = os.environ.get(DOCKER_ACCESS_ENV)
    docker_socket_mode = _docker_access_mode(
        DOCKER_ACCESS_ENV
        if docker_access_override is not None
        else "host.docker_socket",
        docker_access_override
        if docker_access_override is not None
        else host_value("docker_socket", _packaged_docker_access_default()),
    )
    docker_socket = resolve_docker_socket(
        docker_socket_mode,
        selected_services,
        effective_catalog,
    )
    transparent_network = _boolean(
        "host.transparent_network",
        host_value("transparent_network", False),
    )
    environment = _environment(host_value("environment", {}))
    if (
        "LOCALCLOUD_RUNTIME_EMBEDDED_DOCKER" in environment
        and _environment_boolean(
            "host.environment.LOCALCLOUD_RUNTIME_EMBEDDED_DOCKER",
            environment["LOCALCLOUD_RUNTIME_EMBEDDED_DOCKER"],
        )
        and not docker_socket
    ):
        _invalid_config(
            "LOCALCLOUD_RUNTIME_EMBEDDED_DOCKER=true requires Docker access",
            field="host.environment.LOCALCLOUD_RUNTIME_EMBEDDED_DOCKER",
            docker_access=docker_socket_mode,
        )
    if tls is not None:
        environment["LOCALCLOUD_TLS_ENABLED"] = "true" if tls else "false"
    mysql_port = _mysql_port(effective_catalog, effective_services, environment)
    service_ports = _service_ports(effective_catalog, effective_services)
    selected_port_range = (
        _port_range("--port-range", port_range)
        if port_range is not None
        else _port_range("host.port_range", host["port_range"])
        if host.get("port_range") is not None
        else None
    )

    tls_section = raw.get("tls") or {}
    if not isinstance(tls_section, dict):
        _invalid_config("tls must be an object", value=tls_section)
    tls_enabled = _boolean("tls.enabled", tls_section.get("enabled", False))
    tls_port = _port("tls.port", tls_section.get("port", DEFAULT_TLS_PORT))
    if "LOCALCLOUD_TLS_ENABLED" in environment:
        tls_enabled = _environment_boolean(
            "host.environment.LOCALCLOUD_TLS_ENABLED",
            environment["LOCALCLOUD_TLS_ENABLED"],
        )
    if "LOCALCLOUD_TLS_PORT" in environment:
        tls_port = _port(
            "host.environment.LOCALCLOUD_TLS_PORT",
            environment["LOCALCLOUD_TLS_PORT"],
        )
    if transparent_network and not tls_enabled:
        _invalid_config(
            "host.transparent_network requires TLS to be enabled",
            field="host.transparent_network",
        )
    reserved_tls_ports = {5380, *range(5382, 5407), *range(5410, 5415), 5443}
    if transparent_network:
        reserved_tls_ports.update({53, 80, 443})
    if tls_enabled and tls_port in reserved_tls_ports:
        _invalid_config(
            "tls.port conflicts with another published LocalCloud listener",
            field="tls.port",
            port=tls_port,
        )

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
        project_source=project_source,
        user=selected_user,
        services=selected_services,
        data=str(data),
        image=image,
        memory=memory,
        docker_socket_mode=docker_socket_mode,
        docker_socket=docker_socket,
        transparent_network=transparent_network,
        environment=environment,
        tls_enabled=tls_enabled,
        tls_port=tls_port,
        strict_port_validation=strict_port_validation,
        local_only=local_only,
        container_name=selected_container,
        network_name=selected_network,
        diagnostics=tuple(diagnostics),
        effective_services=effective_services,
        mysql_port=mysql_port,
        service_ports=service_ports,
        port_range=selected_port_range,
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
            if "docker_socket" in host and host["docker_socket"] is None:
                _invalid_config("host.docker_socket cannot be null")

    for section_name, value, allowed_fields in (
        ("tls", raw.get("tls"), TLS_FIELDS),
        ("mcp", raw.get("mcp"), MCP_FIELDS),
    ):
        if section_name not in raw:
            continue
        if not isinstance(value, dict):
            _invalid_config(f"{section_name} must be an object", value=value)
            continue
        unknown_fields = sorted(set(value) - allowed_fields)
        _enforce_or_record(
            skip_validation,
            diagnostics,
            bool(unknown_fields),
            f"Unknown {section_name} fields",
            fields=unknown_fields,
        )
        for boolean_field in (
            {"enabled"} if section_name == "tls" else MCP_FIELDS
        ):
            if boolean_field in value and type(value[boolean_field]) is not bool:
                _invalid_config(
                    f"{section_name}.{boolean_field} must be boolean",
                    value=value[boolean_field],
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
            "replacement": "server.auto_seed: false",
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
    directory: Path,
    explicit: Path | None,
    remembered: str | None,
    home_config: Path,
) -> tuple[Path | None, str | None]:
    """The config file to use and where it was found: "explicit", "environment",
    "local", "remembered", or "home" (None when there is no file)."""
    if explicit is not None:
        return _required_config_path(explicit, directory), "explicit"

    configured = os.environ.get("LOCALCLOUD_CONFIG")
    if configured is not None and configured.strip():
        return _required_config_path(Path(configured), directory), "environment"

    local_config = directory / DEFAULT_CONFIG_NAME
    if local_config.exists():
        if not local_config.is_file():
            _invalid_config(
                f"Configuration path is not a file: {local_config}",
                config=str(local_config),
            )
        return local_config.resolve(), "local"

    if remembered and remembered != DEFAULTS_CONFIG_LABEL:
        return _required_config_path(Path(remembered), directory), "remembered"

    if home_config.exists():
        if not home_config.is_file():
            _invalid_config(
                f"Configuration path is not a file: {home_config}",
                config=str(home_config),
            )
        return home_config.resolve(), "home"
    return None, None


# Config files chosen for this directory or this command. A remembered or home
# config is shared across repositories, so its context.project does not override
# the repository's own project.
_PROJECT_PINNING_CONFIG_SOURCES = frozenset({"explicit", "environment", "local"})


def _select_project(
    flag: str | None,
    configured: object | None,
    config_source: str | None,
    directory: Path,
    *,
    from_git: bool,
) -> tuple[str, ProjectSource]:
    """--project-id, then context.project from this directory's (or an explicit)
    config, then (with `from_git`) the git repository's name, then a shared
    config's context.project, then the built-in default. The last two are both
    "default": neither belongs to the directory."""
    if flag is not None:
        return validate_project(flag), "flag"
    if configured is not None and config_source in _PROJECT_PINNING_CONFIG_SOURCES:
        return validate_project(configured), "config"
    git_project = detect_git_project(directory) if from_git else None
    if git_project is not None:
        return git_project, "git"
    if configured is not None:
        return validate_project(configured), "default"
    return DEFAULT_PROJECT, "default"


def workspace_project(directory: Path) -> tuple[str, ProjectSource] | None:
    """The project a command run in `directory` would select on its own: the
    context.project of its localcloud.yaml, else its git repository's name.
    None when neither applies or `directory` cannot be read."""
    try:
        source_directory = _source_directory(directory)
        local_config = source_directory / DEFAULT_CONFIG_NAME
        if local_config.is_file():
            context = _read_config(local_config).get("context")
            if isinstance(context, dict) and context.get("project") is not None:
                return validate_project(context["project"]), "config"
    except HostError:
        pass
    git_project = detect_git_project(directory)
    return (git_project, "git") if git_project is not None else None


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


def slugify_project_name(name: str) -> str | None:
    """Convert an arbitrary repository or directory name into a valid GCP project ID."""
    if not name:
        return None
    # Normalize unicode to ASCII equivalents (e.g. Ünïcödé -> Unicode)
    normalized = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode("ascii")
    lowered = normalized.strip().lower()
    cleaned = re.sub(r"[^a-z0-9]+", "-", lowered).strip("-")
    if not cleaned:
        return None
    if not cleaned[0].isalpha():
        cleaned = f"proj-{cleaned}"
    cleaned = cleaned[:30].rstrip("-")
    if len(cleaned) < 6:
        if not cleaned.startswith("proj-"):
            cleaned = f"proj-{cleaned}"
        while len(cleaned) < 6:
            cleaned = f"{cleaned}-dev"
        cleaned = cleaned[:30].rstrip("-")
    if PROJECT_ID_PATTERN.fullmatch(cleaned):
        return cleaned
    return None


def detect_git_project(directory: Path | None) -> str | None:
    """The project ID for the git repository containing `directory`: its main
    checkout's directory name, so every worktree of a repository shares one
    project. None outside a repository, and for a repository rooted at the home
    directory (a dotfiles checkout is not a project)."""
    if directory is None:
        return None
    try:
        checkout = _git_main_checkout(str(Path(directory).resolve()))
    except OSError:
        return None
    if checkout is None or checkout == Path.home().resolve():
        return None
    return slugify_project_name(checkout.name)


@lru_cache(maxsize=32)
def _git_main_checkout(directory: str) -> Path | None:
    try:
        result = subprocess.run(
            ["git", "rev-parse", "--git-common-dir", "--show-toplevel"],
            cwd=directory,
            capture_output=True,
            text=True,
            timeout=3.0,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return _git_main_checkout_from_files(Path(directory))
    lines = result.stdout.splitlines()
    if result.returncode != 0 or not lines:
        return None
    common_dir = Path(lines[0])
    if not common_dir.is_absolute():
        common_dir = Path(directory) / common_dir
    common_dir = common_dir.resolve()
    if common_dir.name == ".git":
        return common_dir.parent
    # A submodule (.git/modules/<name>) or bare layout: use the checkout itself.
    return Path(lines[1]).resolve() if len(lines) > 1 else None


def _git_main_checkout_from_files(directory: Path) -> Path | None:
    """The same answer without a git executable: the nearest `.git`, following a
    worktree's `gitdir:` pointer back to the main repository."""
    for candidate in (directory, *directory.parents):
        marker = candidate / ".git"
        if marker.is_dir():
            return candidate
        if marker.is_file():
            try:
                content = marker.read_text(encoding="utf-8").strip()
            except OSError:
                return candidate
            if content.startswith("gitdir:"):
                gitdir = Path(content[len("gitdir:") :].strip())
                if not gitdir.is_absolute():
                    gitdir = (candidate / gitdir).resolve()
                for parent in gitdir.parents:
                    if parent.name == ".git":
                        return parent.parent
            return candidate
    return None


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



def _boolean(name: str, value: object) -> bool:
    if not isinstance(value, bool):
        _invalid_config(f"{name} must be a boolean", value=value)
    return value


def _docker_access_mode(name: str, value: object) -> DockerAccessMode:
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, str):
        if value in {"auto", "true", "false"}:
            return cast(DockerAccessMode, value)
    _invalid_config(
        f"{name} must be 'auto', 'true', or 'false'",
        field=name,
        value=value,
    )


def _environment_boolean(name: str, value: str) -> bool:
    normalized = value.strip().lower()
    if normalized not in {"true", "false"}:
        _invalid_config(f"{name} must be 'true' or 'false'", value=value)
    return normalized == "true"


def _port(name: str, value: object) -> int:
    if isinstance(value, bool):
        _invalid_config(f"{name} must be an integer in 1..65535", value=value)
    if isinstance(value, int):
        port = value
    elif isinstance(value, str) and value.strip().isdigit():
        port = int(value.strip())
    else:
        _invalid_config(f"{name} must be an integer in 1..65535", value=value)
    if not 1 <= port <= 65535:
        _invalid_config(f"{name} must be an integer in 1..65535", value=value)
    return port


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
    raise ConfigError("invalid_config", message, details)
