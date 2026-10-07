from __future__ import annotations

import hashlib
import ipaddress
import json
import os
import re
import shutil
import socket
import shlex
import stat
import time
import warnings
from dataclasses import dataclass, field, replace
from datetime import datetime
from types import MappingProxyType
from typing import Any, Mapping
from urllib.parse import urlparse

import httpx

from .config import LocalCloudConfig, runtime_settings, validate_data_volume
from .constants import DEFAULTS_CONFIG_LABEL, DEFAULT_DATA_VOLUME, DEFAULT_TLS_PORT
from .errors import HostError
from .java_client import get_shared_http_client

_ORIGINAL_HTTPX_GET = httpx.get


def _http_get(url: str, **kwargs: Any) -> httpx.Response:
    if httpx.get is not _ORIGINAL_HTTPX_GET:
        return httpx.get(url, **kwargs)
    return get_shared_http_client().get(url, **kwargs)

MANAGED_LABEL = "com.localcloud.managed"
INSTANCE_LABEL = "com.localcloud.instance"  # Legacy child cleanup only.
RESOURCE_ROLE_LABEL = "com.localcloud.resource-role"
CONFIG_HASH_LABEL = "com.localcloud.config-hash"
CONFIG_PATH_LABEL = "com.localcloud.config-path"
CONFIG_LABEL = "com.localcloud.config"
NETWORK_NAME_LABEL = "com.localcloud.network-name"
VOLUME_NAME_LABEL = "com.localcloud.volume-name"
SERVICES_LABEL = "com.localcloud.services"
DATA_LABEL = "com.localcloud.data"
RUNTIME_OWNERSHIP_LABEL = "com.localcloud.runtime-ownership"
RUNTIME_OWNERSHIP_CAPABILITY = "data-volume-v1"
CONFIG_SCHEMA_LABEL = "com.localcloud.config-schema"
CONFIG_SCHEMA_CAPABILITY = "1"
DATA_MOUNT_DESTINATION = "/var/lib/localcloud"
LEGACY_SEED_MOUNT_DESTINATION = "/etc/localcloud/cli-seed.yaml"
CONFIG_MOUNT_DESTINATION = "/etc/localcloud/localcloud.yaml"
GATEWAY_PORT = "5380"
_BASE_TCP_PORTS = tuple(range(5380, 5406))
_DEDICATED_TLS_PORTS = (5392, 5393, 5394)
_DNS_PORT = 5410
_TRANSPARENT_HOST_PORTS = frozenset({53, 80, 443})
_IMAGE_PORT_CAPABILITIES = frozenset(
    {
        f"{port}/tcp"
        for port in (*_BASE_TCP_PORTS, DEFAULT_TLS_PORT, *_DEDICATED_TLS_PORTS)
    }
    | {f"{_DNS_PORT}/udp"}
)
_DEFAULT_READINESS_TIMEOUT = 120.0
# Docker sends SIGKILL after this. LocalCloud's shutdown stops its Dataproc and
# MySQL companions and then Postgres; 20s was not always enough (exit 137).
STOP_TIMEOUT_SECONDS = 30
_DOCKER_SOCKET_PATH = "/var/run/docker.sock"
_FALLBACK_TCP_PORT_RANGES = (
    range(5508, 5540),
    range(5821, 5841),
    range(5322, 5343),
)
_CHILD_MANAGED_LABEL = "localcloud.managed"
_CHILD_SERVICE_LABEL = "com.localcloud.service"
# Host relay sessions (`lc env --identity`): one relay container per data volume, project and
# account, started from the running LocalCloud image. Its child ownership labels let `lc stop`
# and `lc restart` remove it with the server's own children.
IDENTITY_SESSION_LABEL = "com.localcloud.identity-session"
IDENTITY_PROJECT_LABEL = "com.localcloud.identity-project"
IDENTITY_ACCOUNT_LABEL = "com.localcloud.identity-account"
IDENTITY_RELAY_BINARY = "/opt/localcloud/bin/localcloud-relay"
IDENTITY_RELAY_METADATA_PORT = 8081
IDENTITY_RELAY_CAPABILITY_ENV = "LOCALCLOUD_RELAY_CAPABILITY"
_IDENTITY_RELAY_MEMORY = "32m"
# The Cloud SQL MySQL companion keeps its data on the volume, and the server
# recreates it on demand, so it is the only child a replacement removes.
_MYSQL_COMPANION_SERVICE = "cloudsql-mysql"
# The server's default port for that companion (MySqlServerManager); the CLI
# passes LOCALCLOUD_MYSQL_PORT only when the planned port differs.
_MYSQL_DEFAULT_PORT = 5406
_LOG_READ_LIMIT = 2000
_LOG_TIMESTAMP = re.compile(
    r"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})"
)
_LEGACY_LABELS = {
    "com.localcloud." + "work" + "space",
    "com.localcloud." + "work" + "space-key",
    "com.localcloud.controller",
    "com.localcloud.project",
}
_MANAGEMENT_LABELS = {
    MANAGED_LABEL,
    INSTANCE_LABEL,
    RESOURCE_ROLE_LABEL,
    CONFIG_HASH_LABEL,
    CONFIG_PATH_LABEL,
    CONFIG_LABEL,
    NETWORK_NAME_LABEL,
    VOLUME_NAME_LABEL,
    DATA_LABEL,
}
_CONTAINER_METADATA_LABELS = (
    CONFIG_HASH_LABEL,
    CONFIG_PATH_LABEL,
    CONFIG_LABEL,
    NETWORK_NAME_LABEL,
    VOLUME_NAME_LABEL,
    DATA_LABEL,
)


@dataclass(frozen=True)
class RuntimeRecord:
    data_volume: str
    origin: str | None
    ownership: dict[str, str]
    name: str
    container_id: str | None
    state: str
    health: str | None
    url: str | None
    connect_url: str | None
    endpoint_map: dict[str, int]
    network_name: str | None
    mount: dict[str, Any]
    configured_image: str
    actual_image: str | None
    image_id: str | None
    configured_image_id: str | None = None
    config_hash: str | None = None
    config_path: str | None = None
    runtime_settings: dict[str, Any] | None = None
    services: str = ""
    data: str = ""
    labels: dict[str, str] = field(default_factory=dict)
    drift: dict[str, dict[str, Any]] = field(default_factory=dict)
    legacy_seed_mount: bool = False
    volume_created: bool = False
    network_created: bool = False
    image_status: str = ""
    published_ports: PublishedPorts = field(default_factory=dict)
    # Host port of the Cloud SQL MySQL companion this runtime publishes.
    mysql_port: int | None = None


PortBinding = tuple[str, int | None]
PortMapping = tuple[str, int, int, str]
RequestedPorts = dict[str, tuple[PortBinding, ...]]
PublishedPorts = dict[str, tuple[PortBinding, ...]]


@dataclass(frozen=True)
class DockerRunPlan:
    image: str
    name: str
    network_name: str
    mem_limit: str | None
    volumes: Mapping[str, Mapping[str, str]]
    ports: Mapping[str, tuple[PortBinding, ...]]
    environment: Mapping[str, str]
    labels: Mapping[str, str]
    # (canonical, host) port of the Cloud SQL MySQL companion. The server
    # publishes it, so it is planned here but is not a `docker run` binding.
    mysql_ports: tuple[int, int] | None = None

    def __post_init__(self) -> None:
        object.__setattr__(
            self,
            "volumes",
            MappingProxyType(
                {
                    source: MappingProxyType(dict(mount))
                    for source, mount in self.volumes.items()
                }
            ),
        )
        object.__setattr__(
            self,
            "ports",
            MappingProxyType(
                {
                    port: _normalize_requested_bindings(bindings)
                    for port, bindings in self.ports.items()
                }
            ),
        )
        object.__setattr__(
            self,
            "environment",
            MappingProxyType(dict(self.environment)),
        )
        object.__setattr__(
            self,
            "labels",
            MappingProxyType(dict(self.labels)),
        )

    def command(self, *, include_management_metadata: bool = False) -> str:
        return _format_docker_run(
            self.image,
            self.name,
            self.network_name,
            self.mem_limit,
            self.volumes,
            self.ports,
            self.environment,
            self.labels if include_management_metadata else None,
        )

    def run_kwargs(self) -> dict[str, Any]:
        return {
            "detach": True,
            "name": self.name,
            "labels": dict(self.labels),
            "environment": dict(self.environment),
            "mem_limit": self.mem_limit,
            "network": self.network_name,
            "ports": {
                port: bindings[0] if len(bindings) == 1 else list(bindings)
                for port, bindings in self.ports.items()
            },
            "volumes": {
                source: dict(mount) for source, mount in self.volumes.items()
            },
        }

    def alternative_port_mappings(
        self,
    ) -> tuple[PortMapping, ...]:
        """Return explicit noncanonical TCP host bindings for confirmation."""
        primary_mappings: list[PortMapping] = []
        for container_spec, bindings in sorted(
            self.ports.items(), key=lambda item: _port_spec_sort_key(item[0])
        ):
            port_text, _, protocol_text = container_spec.partition("/")
            protocol = protocol_text or "tcp"
            if protocol != "tcp":
                continue
            try:
                container_port = int(port_text)
            except ValueError:
                continue
            for host_ip, host_port in bindings:
                if host_port is not None and host_port not in _TRANSPARENT_HOST_PORTS:
                    primary_mappings.append(
                        (host_ip, host_port, container_port, protocol)
                    )
                    break
        if self.mysql_ports is not None:
            canonical, host_port = self.mysql_ports
            host_ip = primary_mappings[0][0] if primary_mappings else ""
            primary_mappings.append((host_ip, host_port, canonical, "tcp"))
        if all(
            host_port == container_port
            for _host_ip, host_port, container_port, _protocol in primary_mappings
        ):
            return ()
        return tuple(primary_mappings)


class LogCursor:
    """Reads a container's log incrementally, on the Docker daemon's clock.

    Each read resumes at the newest log timestamp already returned, so a burst
    between two polls is never cut short by a tail limit, and host/daemon clock
    drift cannot skip lines. Docker's `since` is a float, so reads overlap by
    a millisecond and the lines in that overlap are de-duplicated.
    """

    _OVERLAP_NS = 1_000_000

    def __init__(
        self,
        since_ns: int | None = None,
        *,
        first_tail: int | None = None,
    ) -> None:
        self._since_ns = since_ns
        self._first_tail = first_tail
        self._overlap: set[str] = set()

    @classmethod
    def from_start(cls, container: Any) -> LogCursor:
        """Every line of the container's current run (not earlier runs)."""
        return cls(_started_at_ns(container))

    def read(self, container: Any) -> str:
        limit = self._first_tail or _LOG_READ_LIMIT
        self._first_tail = None
        kwargs: dict[str, Any] = {"timestamps": True, "tail": limit}
        if self._since_ns is not None:
            kwargs["since"] = self._since_ns / 1_000_000_000
        output = container.logs(**kwargs)
        text = (
            output.decode("utf-8", errors="replace")
            if isinstance(output, bytes)
            else str(output)
        )
        fresh = [
            line for line in text.splitlines() if line and line not in self._overlap
        ]
        stamps = [
            stamp
            for stamp in map(_timestamp_ns, fresh)
            if stamp is not None
        ]
        if stamps:
            cutoff = max(stamps) - self._OVERLAP_NS
            self._overlap = {
                line
                for line in (*self._overlap, *fresh)
                if (_timestamp_ns(line) or 0) >= cutoff
            }
            self._since_ns = cutoff
        return "".join(f"{line}\n" for line in fresh)


class DockerRuntime:
    def __init__(self, client: Any | None = None):
        self._resolved_image_details: dict[str, dict[str, Any]] = {}
        # One log cursor per container, shared by the readiness wait and the
        # log tail that follows it, so neither repeats nor skips lines.
        self._log_cursors: dict[str, LogCursor] = {}
        if client is not None:
            self.client = client
            return
        try:
            import docker

            try:
                self.client = docker.from_env(use_context=True)
            except TypeError:
                self.client = docker.from_env()
        except Exception as error:
            raise HostError(
                "docker_unavailable",
                "Docker is unavailable; start Docker Desktop, Colima, or the selected Docker context",
                {"cause": str(error), "docker_host": os.environ.get("DOCKER_HOST")},
            ) from error

    def resolve(
        self,
        config: LocalCloudConfig,
        preferred_container_id: str | None = None,
        *,
        require: bool = False,
    ) -> RuntimeRecord | None:
        self._resolved_image_details.clear()
        data_volume = validate_data_volume(config.data_volume)
        volume = self._get_optional(self.client.volumes, data_volume, "volume")
        containers = self._list_containers(data_volume)
        users: list[tuple[Any, list[dict[str, Any]]]] = []
        for container in containers:
            try:
                container.reload()
            except Exception as error:
                raise HostError(
                    "docker_inspect_failed",
                    "Could not inspect a container while resolving the selected data volume",
                    {
                        "data_volume": data_volume,
                        "container_id": _resource_identity(container),
                        "cause": str(error),
                    },
                ) from error
            matching_mounts = [
                mount
                for mount in _container_mounts(container)
                if _mount_volume_name(mount) == data_volume
            ]
            if matching_mounts:
                users.append((container, matching_mounts))

        if len(users) > 1:
            raise HostError(
                "data_volume_collision",
                "Multiple containers use the selected LocalCloud data volume",
                {
                    "data_volume": data_volume,
                    "containers": sorted(
                        (
                            {
                                "id": _resource_identity(container),
                                "name": _resource_name(container),
                                "state": _container_state(container),
                            }
                            for container, _mounts in users
                        ),
                        key=lambda item: (item["name"] or "", item["id"]),
                    ),
                },
            )

        if not users:
            if require:
                raise HostError(
                    "runtime_not_found",
                    "No LocalCloud container uses the selected data volume",
                    {
                        "data_volume": data_volume,
                        "volume_exists": volume is not None,
                    },
                )
            return None

        container, mounts = users[0]
        if volume is None:
            raise HostError(
                "data_volume_missing",
                "A container references a LocalCloud data volume that Docker cannot inspect",
                {
                    "data_volume": data_volume,
                    "container_id": _resource_identity(container),
                },
            )
        mount = self._validated_data_mount(data_volume, container, mounts)
        labels = _resource_labels(container, reload=False)
        container_ownership = self._classify_resource(
            container,
            "container",
            data_volume,
            resource_labels=labels,
        )
        image = self._compatible_image(
            config,
            container,
            allow_reconfiguration=container_ownership == "managed",
        )
        metadata = self._container_metadata(
            container, labels, data_volume, container_ownership
        )
        network_name = self._container_network(config, container, metadata)
        network = (
            self._get_optional(self.client.networks, network_name, "network")
            if network_name
            else None
        )
        network_ownership = "attached"
        if network is not None:
            network_ownership = self._classify_resource(network, "network", data_volume)
        elif container_ownership == "managed":
            network_ownership = "missing"
        volume_ownership = self._classify_resource(volume, "volume", data_volume, allow_legacy_volume=True)
        ownership = {
            "container": container_ownership,
            "network": network_ownership,
            "data_volume": volume_ownership,
        }
        origin = (
            "managed"
            if (
                container_ownership == "managed"
                and volume_ownership == "managed"
                and network_ownership in {"managed", "missing"}
            )
            else "attached"
        )
        published_ports = _published_ports(container)
        endpoint_map = _endpoint_map(published_ports)
        gateway = endpoint_map.get(GATEWAY_PORT)
        runtime_config = metadata.get("runtime_config")
        tls_enabled = config.tls_enabled
        tls_port = config.tls_port
        if isinstance(runtime_config, Mapping):
            if isinstance(runtime_config.get("tls_enabled"), bool):
                tls_enabled = runtime_config["tls_enabled"]
            if isinstance(runtime_config.get("tls_port"), int):
                tls_port = runtime_config["tls_port"]
        container_environment = _container_environment_values(container)
        raw_tls_enabled = container_environment.get("LOCALCLOUD_TLS_ENABLED", "").lower()
        if raw_tls_enabled in {"true", "false"}:
            tls_enabled = raw_tls_enabled == "true"
        raw_tls_port = container_environment.get("LOCALCLOUD_TLS_PORT", "")
        if raw_tls_port.isdigit() and 1 <= int(raw_tls_port) <= 65535:
            tls_port = int(raw_tls_port)
        # Without LOCALCLOUD_MYSQL_PORT the server publishes its default port;
        # 0 lets Docker pick one.
        raw_mysql_port = container_environment.get("LOCALCLOUD_MYSQL_PORT", "")
        mysql_port = (
            int(raw_mysql_port)
            if raw_mysql_port.isdigit() and 1 <= int(raw_mysql_port) <= 65535
            else None
            if raw_mysql_port or config.mysql_port is None
            else _MYSQL_DEFAULT_PORT
        )
        tls_gateway = endpoint_map.get(str(tls_port))
        state = _container_state(container)
        health = _container_health(container)
        drift = (
            _runtime_drift(runtime_config, config)
            if container_ownership == "managed"
            else _attached_drift(config, container, network_name, metadata)
        )
        return RuntimeRecord(
            data_volume=data_volume,
            origin=origin,
            ownership=ownership,
            name=_resource_name(container),
            container_id=_resource_identity(container),
            state=state,
            health=health,
            url=f"http://127.0.0.1:{gateway}" if gateway else None,
            connect_url=(
                f"https://127.0.0.1:{tls_gateway}"
                if tls_enabled and tls_gateway
                else f"http://127.0.0.1:{gateway}"
                if gateway
                else None
            ),
            endpoint_map=endpoint_map,
            network_name=network_name,
            mount=mount,
            configured_image=config.image,
            configured_image_id=image.get("configured_id"),
            actual_image=image["declared"],
            image_id=image["container_id"],
            config_hash=metadata.get("config_hash"),
            config_path=metadata.get("config_path"),
            runtime_settings=runtime_config,
            data=metadata["data"],
            labels=labels,
            drift=drift,
            published_ports=published_ports,
            legacy_seed_mount=any(
                item.get("Destination") == LEGACY_SEED_MOUNT_DESTINATION
                for item in _container_mounts(container)
            ),
            mysql_port=mysql_port,
        )

    def preflight_create(
        self,
        config: LocalCloudConfig,
        replacing: RuntimeRecord | None = None,
        *,
        pull: bool | None = None,
        observer: Any | None = None,
        local_only: bool = False,
    ) -> tuple[Any, bool]:
        if local_only and pull is True:
            raise HostError(
                "dry_run_pull_conflict",
                "Read-only image planning cannot pull images",
                {"image": config.image},
            )
        effective_pull = False if local_only else (True if pull is None else pull)
        socket_usable = False
        try:
            socket_usable = _docker_socket_is_usable(self.client)
        except TypeError:
            socket_usable = _docker_socket_is_usable()
        if config.docker_socket and not socket_usable:
            raise HostError(
                "docker_socket_unavailable",
                f"Docker socket {_DOCKER_SOCKET_PATH} is not available",
                {
                    "path": _DOCKER_SOCKET_PATH,
                    "docker_access": config.docker_socket_mode,
                },
            )
        image, was_pulled = self._image_for_create(
            config.image,
            pull=effective_pull,
            observer=observer,
            local_only=local_only,
        )
        self._require_runtime_ownership_capability(config, image)
        self._validate_image_port_metadata(config, image, observer)
        allowed_ports = self._allowed_host_ports(config, replacing)
        self._port_bindings(config, allowed_ports=allowed_ports)
        if (
            config.mysql_port is not None
            and _mysql_port_pinned(config)
            and (config.mysql_port, "tcp") not in allowed_ports
            and not _port_is_free(
                config.mysql_port,
                socket.SOCK_STREAM,
                "127.0.0.1" if config.local_only else "",
            )
        ):
            _emit_warning(
                observer,
                f"Host port {config.mysql_port}/tcp, set by LOCALCLOUD_MYSQL_PORT, is "
                "in use; Cloud SQL MySQL cannot start until it is free",
            )
        replacing_id = replacing.container_id if replacing is not None else ""
        name_collision = self._get_optional(
            self.client.containers, config.container_name, "container"
        )
        if (
            name_collision is not None
            and _resource_identity(name_collision) != replacing_id
        ):
            raise HostError(
                "resource_name_in_use",
                "Configured container name is already in use",
                {
                    "data_volume": config.data_volume,
                    "container": config.container_name,
                    "container_id": _resource_identity(name_collision),
                },
            )

        volume = self._get_optional(
            self.client.volumes, config.data_volume, "volume"
        )
        if volume is not None:
            self._classify_resource(
                volume,
                "volume",
                config.data_volume,
                allow_legacy_volume=True,
            )
        network = self._get_optional(
            self.client.networks, config.network_name, "network"
        )
        if network is not None:
            ownership = self._classify_resource(
                network, "network", config.data_volume
            )
            if ownership != "managed":
                raise HostError(
                    "resource_name_in_use",
                    "Configured network name is already used by an attached resource",
                    {"network": config.network_name},
                )
        return image, was_pulled

    def plan_run(
        self,
        config: LocalCloudConfig,
        image: Any,
        *,
        replacing: RuntimeRecord | None = None,
    ) -> DockerRunPlan:
        volumes: dict[str, dict[str, str]] = {
            config.data_volume: {
                "bind": DATA_MOUNT_DESTINATION,
                "mode": "rw",
            }
        }
        if config.docker_socket:
            volumes["/var/run/docker.sock"] = {
                "bind": "/var/run/docker.sock",
                "mode": "rw",
            }
        if config.config_path is not None:
            volumes[str(config.config_path)] = {
                "bind": CONFIG_MOUNT_DESTINATION,
                "mode": "ro",
            }
        labels = {
            **_config_labels(config),
            **_base_labels(config.data_volume, "container"),
        }
        ports, mysql_host_port = self._port_plan(
            config,
            allowed_ports=self._allowed_host_ports(config, replacing),
        )
        return DockerRunPlan(
            image=config.image,
            name=config.container_name,
            network_name=config.network_name,
            mem_limit=config.memory,
            volumes=volumes,
            ports=ports,
            environment=_container_environment(
                config, config.network_name, mysql_port=mysql_host_port
            ),
            labels=labels,
            mysql_ports=(
                (config.mysql_port, mysql_host_port)
                if config.mysql_port is not None and mysql_host_port is not None
                else None
            ),
        )

    def _allowed_host_ports(
        self,
        config: LocalCloudConfig,
        replacing: RuntimeRecord | None,
    ) -> set[tuple[int, str]]:
        """Host ports this runtime already holds: its container's and children's."""
        allowed = _published_host_ports(replacing)
        try:
            children = self.client.containers.list(
                all=True,
                filters={
                    "label": [
                        f"{_CHILD_MANAGED_LABEL}=true",
                        f"{VOLUME_NAME_LABEL}={config.data_volume}",
                    ]
                },
            )
        except Exception:
            return allowed
        for child in children:
            for port_spec, bindings in _published_ports(child).items():
                _port, _, protocol = port_spec.partition("/")
                allowed.update(
                    (host_port, protocol or "tcp")
                    for _host_ip, host_port in bindings
                    if host_port is not None
                )
        return allowed

    def has_canonical_ports(
        self,
        config: LocalCloudConfig,
        runtime: RuntimeRecord,
    ) -> bool:
        if config.port_range is not None:
            return _within_configured_range(config, runtime)
        expected = _canonical_port_bindings(config)
        if runtime.published_ports.keys() != expected.keys():
            return False
        if config.mysql_port is not None and runtime.mysql_port != config.mysql_port:
            return False
        return all(
            {
                (
                    ""
                    if not config.local_only and host_ip in {"0.0.0.0", "::"}
                    else host_ip,
                    host_port,
                )
                for host_ip, host_port in runtime.published_ports[port]
            } == set(bindings)
            for port, bindings in expected.items()
        )

    def inspect_run_plan(
        self,
        config: LocalCloudConfig,
        runtime: RuntimeRecord,
    ) -> DockerRunPlan:
        container, current = self._mutation_target(config, runtime)
        attrs = getattr(container, "attrs", {})
        container_config = attrs.get("Config", {})
        host_config = attrs.get("HostConfig", {})
        container_config = (
            container_config if isinstance(container_config, Mapping) else {}
        )
        host_config = host_config if isinstance(host_config, Mapping) else {}

        volumes: dict[str, dict[str, str]] = {}
        for mount in attrs.get("Mounts", ()):
            if not isinstance(mount, Mapping):
                continue
            source = (
                mount.get("Name")
                if mount.get("Type") == "volume"
                else mount.get("Source")
            ) or mount.get("Source")
            destination = mount.get("Destination")
            if not source or not destination:
                continue
            mode = mount.get("Mode") or (
                "rw" if mount.get("RW", True) else "ro"
            )
            volumes[str(source)] = {
                "bind": str(destination),
                "mode": str(mode),
            }

        ports: RequestedPorts = {}
        for container_port, bindings in current.published_ports.items():
            if bindings:
                ports[container_port] = bindings

        network_name = (
            current.network_name
            or str(host_config.get("NetworkMode") or config.network_name)
        )
        environment = _container_environment(
            config, network_name, mysql_port=current.mysql_port
        )

        raw_labels = container_config.get("Labels")
        labels = (
            {str(key): str(value) for key, value in raw_labels.items()}
            if isinstance(raw_labels, Mapping)
            else dict(current.labels)
        )
        mem_limit = _format_memory_limit(host_config.get("Memory"))
        return DockerRunPlan(
            image=str(
                container_config.get("Image")
                or current.actual_image
                or config.image
            ),
            name=current.name or config.container_name,
            network_name=network_name,
            mem_limit=mem_limit,
            volumes=volumes,
            ports=ports,
            environment=environment,
            labels=labels,
        )

    def preview_create_commands(
        self,
        config: LocalCloudConfig,
        run_plan: DockerRunPlan,
        *,
        volume_exists: bool | None = None,
        network_exists: bool | None = None,
    ) -> tuple[str, ...]:
        commands: list[str] = []
        volume = self._get_optional(
            self.client.volumes, config.data_volume, "volume"
        )
        effective_volume_exists = (
            volume is not None if volume_exists is None else volume_exists
        )
        if not effective_volume_exists:
            commands.append(
                _format_resource_create(
                    "volume",
                    config.data_volume,
                    _base_labels(config.data_volume, "volume"),
                )
            )

        network = self._get_optional(
            self.client.networks, config.network_name, "network"
        )
        effective_network_exists = (
            network is not None if network_exists is None else network_exists
        )
        if not effective_network_exists:
            commands.append(
                _format_resource_create(
                    "network",
                    config.network_name,
                    _network_labels(config),
                )
            )
        commands.append(run_plan.command())
        return tuple(commands)

    def preview_remove_commands(
        self,
        config: LocalCloudConfig,
        runtime: RuntimeRecord,
        *,
        remove_volume: bool,
        remove_network: bool = True,
    ) -> tuple[str, ...]:
        container, current = self._mutation_target(config, runtime)
        if current.ownership["container"] != "managed":
            raise HostError(
                "ownership_forbidden",
                "Attached LocalCloud containers cannot be removed or replaced",
                {
                    "data_volume": config.data_volume,
                    "container_id": current.container_id,
                    "ownership": current.ownership,
                },
            )
        failures: list[dict[str, Any]] = []
        children = self._remove_children(
            config.data_volume,
            container,
            current,
            failures,
            dry_run=True,
            services=_replaced_child_services(
                current, remove_volume=remove_volume, remove_network=remove_network
            ),
        )
        if failures:
            raise HostError(
                "cleanup_failed",
                "Managed child-container cleanup could not be planned",
                {"data_volume": config.data_volume, "failures": failures},
            )
        commands = (
            list(
                self.preview_stop_commands(
                    _resource_name(container) or str(current.container_id)
                )
            )
            if current.state == "running"
            else []
        )
        commands.extend(
            shlex.join(
                [
                    "docker",
                    "rm",
                    "-f",
                    "-v",
                    _resource_name(child) or _resource_identity(child),
                ]
            )
            for child in children
        )
        commands.append(
            shlex.join(
                [
                    "docker",
                    "rm",
                    "-f",
                    "-v",
                    _resource_name(container) or current.container_id,
                ]
            )
        )
        if (
            remove_network
            and current.ownership["network"] == "managed"
            and current.network_name is not None
        ):
            commands.append(
                shlex.join(["docker", "network", "rm", current.network_name])
            )
        if remove_volume and current.ownership["data_volume"] == "managed":
            commands.append(
                shlex.join(["docker", "volume", "rm", "-f", config.data_volume])
            )
        return tuple(commands)

    @staticmethod
    def preview_start_commands(target: str) -> tuple[str, ...]:
        return (shlex.join(["docker", "start", target]),)

    @staticmethod
    def preview_restart_commands(
        target: str, timeout: int = STOP_TIMEOUT_SECONDS
    ) -> tuple[str, ...]:
        return (shlex.join(["docker", "restart", "-t", str(timeout), target]),)

    @staticmethod
    def preview_stop_commands(
        target: str, timeout: int = STOP_TIMEOUT_SECONDS
    ) -> tuple[str, ...]:
        return (shlex.join(["docker", "stop", "-t", str(timeout), target]),)

    def create(

        self,
        config: LocalCloudConfig,
        *,
        pull: bool = True,
        readiness_deadline: float | None = None,
        observer: Any | None = None,
        prepared_image: tuple[Any, bool] | None = None,
        run_plan: DockerRunPlan | None = None,
    ) -> RuntimeRecord:
        current = self.resolve(config)
        if current is not None:
            raise HostError(
                "runtime_exists",
                "A LocalCloud container already uses the selected data volume",
                {
                    "data_volume": config.data_volume,
                    "container_id": current.container_id,
                },
            )
        image, was_pulled = (
            prepared_image
            if prepared_image is not None
            else self.preflight_create(config, pull=pull, observer=observer)
        )
        deadline = _resolve_readiness_deadline(readiness_deadline)
        if was_pulled and observer is not None and hasattr(observer, "starting"):
            observer.starting(config)

        run_plan = run_plan or self.plan_run(config, image)
        container_labels = dict(run_plan.labels)
        network_labels = _network_labels(config)
        volume_labels = _base_labels(config.data_volume, "volume")
        self._require_runtime_ownership_capability(config, image)

        container = network = volume = None
        network_created = volume_created = False
        try:
            self._require_name_available(
                self.client.containers, config.container_name, "container"
            )
            volume, volume_created = self._volume_for_create(config, volume_labels)
            network, network_created = self._network_for_create(
                config, network_labels
            )
            if observer is not None and hasattr(observer, "debug"):
                observer.debug(
                    f"Executing Docker SDK containers.run for {run_plan.name!r}"
                )
            container = self.client.containers.run(
                run_plan.image,
                **run_plan.run_kwargs(),
            )
            container.reload()
            container_id = _resource_identity(container)
            record = self.resolve(
                config,
                preferred_container_id=container_id,
                require=True,
            )
            if record is None:  # pragma: no cover - require=True is exhaustive.
                raise AssertionError("created runtime was not resolved")
            record = self._require_container_identity(
                config,
                container_id,
                record,
            )
            self._require_gateway(record)
            self.wait_ready(
                record.url,
                deadline=deadline,
                container=container,
                observer=observer,
                cursor=self._track_logs(container, from_start=True),
            )
            ready = self.resolve(
                config,
                preferred_container_id=container_id,
                require=True,
            )
            assert ready is not None
            ready = self._require_container_identity(
                config,
                container_id,
                ready,
            )
            ready = replace(
                ready,
                volume_created=volume_created,
                network_created=network_created,
                image_status=(
                    "pulled from registry"
                    if was_pulled
                    else "available locally"
                ),
            )
            return ready
        except Exception as error:
            failures = self._rollback_create(
                container,
                network if network_created else None,
                volume if volume_created else None,
                container_labels,
                network_labels,
                volume_labels,
            )
            if isinstance(error, HostError):
                if failures:
                    error.details["rollback_failures"] = failures
                raise
            if _is_port_conflict(error):
                # `_port_bindings` only checks port availability at preflight
                # time; another process can still claim the port before
                # `containers.run()` actually binds it. Surface that race as
                # a specific, actionable error instead of the generic
                # environment_create_failed catch-all.
                raise HostError(
                    "port_no_longer_available",
                    "A required host port became unavailable between "
                    "preflight checks and container start; retry the command",
                    {
                        "data_volume": config.data_volume,
                        "cause": str(error),
                        "image": config.image,
                        "rollback_failures": failures,
                    },
                ) from error
            raise HostError(
                "environment_create_failed",
                "Managed LocalCloud runtime could not be created",
                {
                    "data_volume": config.data_volume,
                    "cause": str(error),
                    "image": config.image,
                    "rollback_failures": failures,
                },
            ) from error

    def start(
        self,
        config: LocalCloudConfig,
        runtime: RuntimeRecord,
        *,
        readiness_deadline: float | None = None,
        observer: Any | None = None,
    ) -> RuntimeRecord:
        deadline = _resolve_readiness_deadline(readiness_deadline)
        container, current = self._mutation_target(config, runtime)
        started = current.state != "running"
        if started:
            if observer is not None and hasattr(observer, "debug"):
                target_name = _resource_name(container) or current.container_id
                observer.debug(
                    "Executing: " + shlex.join(["docker", "start", target_name])
                )
            try:
                container.start()
            except Exception as error:
                raise HostError(
                    "container_start_failed",
                    "LocalCloud runtime container could not be started",
                    {
                        "data_volume": config.data_volume,
                        "container_id": current.container_id,
                        "cause": str(error),
                        "logs": _container_logs(container),
                    },
                ) from error
        updated = self.resolve(
            config,
            preferred_container_id=current.container_id,
            require=True,
        )
        assert updated is not None
        updated = self._require_container_identity(
            config,
            current.container_id,
            updated,
        )
        self._require_gateway(updated)
        self.wait_ready(
            updated.url,
            deadline=deadline,
            container=container,
            observer=observer,
            cursor=self._track_logs(container, from_start=started),
        )
        ready = self.resolve(
            config,
            preferred_container_id=current.container_id,
            require=True,
        )
        assert ready is not None
        ready = replace(
            self._require_container_identity(
                config,
                current.container_id,
                ready,
            ),
            image_status="available locally",
        )
        return ready

    def restart(
        self,
        config: LocalCloudConfig,
        runtime: RuntimeRecord,
        *,
        readiness_deadline: float | None = None,
        observer: Any | None = None,
    ) -> RuntimeRecord:
        deadline = _resolve_readiness_deadline(readiness_deadline)
        container, current = self._mutation_target(config, runtime)
        if observer is not None and hasattr(observer, "debug"):
            target_name = _resource_name(container) or current.container_id
            observer.debug(
                "Executing: "
                + shlex.join(
                    ["docker", "restart", "-t", str(STOP_TIMEOUT_SECONDS), target_name]
                )
            )
        try:
            container.restart(timeout=STOP_TIMEOUT_SECONDS)
        except Exception as error:
            raise HostError(
                "container_restart_failed",
                "LocalCloud runtime container could not be restarted",
                {
                    "data_volume": config.data_volume,
                    "container_id": current.container_id,
                    "cause": str(error),
                    "logs": _container_logs(container),
                },
            ) from error
        updated = self.resolve(
            config,
            preferred_container_id=current.container_id,
            require=True,
        )
        assert updated is not None
        updated = self._require_container_identity(
            config,
            current.container_id,
            updated,
        )
        self._require_gateway(updated)
        self.wait_ready(
            updated.url,
            deadline=deadline,
            container=container,
            observer=observer,
            cursor=self._track_logs(container, from_start=True),
        )
        ready = self.resolve(
            config,
            preferred_container_id=current.container_id,
            require=True,
        )
        assert ready is not None
        ready = replace(
            self._require_container_identity(
                config,
                current.container_id,
                ready,
            ),
            image_status="available locally",
        )
        return ready

    def stop(
        self,
        config: LocalCloudConfig,
        runtime: RuntimeRecord,
        *,
        observer: Any | None = None,
    ) -> RuntimeRecord:
        container, current = self._mutation_target(config, runtime)
        self._stop_container(config, container, current, observer)
        if current.ownership.get("container") == "managed":
            # Keep children: the server stops them on shutdown and resumes
            # them on the next start (Dataproc clusters, the MySQL companion).
            failures: list[dict[str, Any]] = []
            self._stop_children(config.data_volume, container, current, failures)
            if failures:
                raise HostError(
                    "cleanup_failed",
                    "Managed child containers could not be stopped",
                    {"data_volume": config.data_volume, "failures": failures},
                )
        updated = self.resolve(
            config,
            preferred_container_id=current.container_id,
            require=True,
        )
        assert updated is not None
        return updated

    def _stop_container(
        self,
        config: LocalCloudConfig,
        container: Any,
        current: RuntimeRecord,
        observer: Any | None,
    ) -> None:
        """Let LocalCloud shut down (companions, then Postgres) before Docker kills it."""
        if current.state != "running":
            return
        if observer is not None and hasattr(observer, "debug"):
            target_name = _resource_name(container) or current.container_id
            observer.debug(
                "Executing: "
                + shlex.join(
                    ["docker", "stop", "-t", str(STOP_TIMEOUT_SECONDS), target_name]
                )
            )
        try:
            container.stop(timeout=STOP_TIMEOUT_SECONDS)
        except Exception as error:
            raise HostError(
                "container_stop_failed",
                "LocalCloud runtime container could not be stopped",
                {
                    "data_volume": config.data_volume,
                    "container_id": current.container_id,
                    "cause": str(error),
                },
            ) from error

    def remove(
        self,
        config: LocalCloudConfig,
        runtime: RuntimeRecord,
        *,
        remove_volume: bool = True,
        remove_network: bool = True,
        observer: Any | None = None,
    ) -> None:
        container, current = self._mutation_target(config, runtime)
        if current.ownership["container"] != "managed":
            raise HostError(
                "ownership_forbidden",
                "Attached LocalCloud containers cannot be removed or replaced",
                {
                    "data_volume": config.data_volume,
                    "container_id": current.container_id,
                    "ownership": current.ownership,
                },
            )
        if observer is not None and hasattr(observer, "debug"):
            observer.debug(
                f"Removing managed container {current.name!r}; "
                f"remove_volume={remove_volume}"
            )
        # `docker rm -f` would SIGKILL a running LocalCloud, Postgres included.
        self._stop_container(config, container, current, observer)

        failures: list[dict[str, Any]] = []
        self._remove_children(
            config.data_volume,
            container,
            current,
            failures,
            services=_replaced_child_services(
                current, remove_volume=remove_volume, remove_network=remove_network
            ),
        )
        if failures:
            raise HostError(
                "cleanup_failed",
                "Managed child-container cleanup was incomplete",
                {"data_volume": config.data_volume, "failures": failures},
            )
        _remove_verified(
            container,
            "container",
            _record_container_labels(current),
            failures,
            force=True,
            v=True,
        )
        if (
            remove_network
            and current.ownership["network"] == "managed"
            and current.network_name is not None
        ):
            network = self._get_optional(
                self.client.networks, current.network_name, "network"
            )
            self._teardown_network_if_owned(
                config, network, failures, ownership="managed"
            )
        if remove_volume and current.ownership["data_volume"] == "managed":
            volume = self._get_optional(
                self.client.volumes, config.data_volume, "volume"
            )
            self._teardown_volume_if_owned(
                config, volume, failures, ownership="managed"
            )
        if failures:
            raise HostError(
                "cleanup_failed",
                "Managed LocalCloud runtime cleanup was incomplete",
                {"data_volume": config.data_volume, "failures": failures},
            )

    def _teardown_network_if_owned(
        self,
        config: LocalCloudConfig,
        network: Any | None,
        failures: list[dict[str, Any]],
        *,
        ownership: str | None = None,
    ) -> None:
        """Remove `network` if it is (or, when `ownership` is omitted, turns
        out to be) managed by this LocalCloud instance. Called by `remove()`,
        which already knows the ownership from a resolved RuntimeRecord."""
        if network is None:
            return
        resolved_ownership = (
            ownership
            if ownership is not None
            else self._classify_resource(network, "network", config.data_volume)
        )
        if resolved_ownership != "managed":
            return
        try:
            if hasattr(network, "reload"):
                network.reload()
            attached_containers = (
                getattr(network, "attrs", {}).get("Containers")
                or getattr(network, "containers", [])
            )
            if attached_containers:
                other_containers = [
                    cid
                    for cid in (
                        attached_containers.keys()
                        if isinstance(attached_containers, dict)
                        else [getattr(c, "id", None) for c in attached_containers]
                    )
                    if cid and cid != getattr(config, "container_name", None)
                ]
                if other_containers:
                    return
        except Exception:
            pass
        _remove_verified(
            network,
            "network",
            _removal_base_labels(network, "network", config.data_volume),
            failures,
        )

    def _teardown_volume_if_owned(
        self,
        config: LocalCloudConfig,
        volume: Any | None,
        failures: list[dict[str, Any]],
        *,
        ownership: str | None = None,
    ) -> None:
        """Remove `volume` if it is (or, when `ownership` is omitted, turns
        out to be) managed by this LocalCloud instance. Called by `remove()`
        for a managed data volume when `remove_volume` is set."""
        if volume is None:
            return
        resolved_ownership = (
            ownership
            if ownership is not None
            else self._classify_resource(
                volume, "volume", config.data_volume, allow_legacy_volume=True
            )
        )
        if resolved_ownership != "managed":
            return
        _remove_verified(
            volume,
            "volume",
            _removal_base_labels(volume, "volume", config.data_volume),
            failures,
            force=True,
        )

    def recreation_ownership(
        self, config: LocalCloudConfig
    ) -> dict[str, str]:
        current = self.resolve(config)
        if current is not None:
            return dict(current.ownership)
        name_collision = self._get_optional(
            self.client.containers, config.container_name, "container"
        )
        if name_collision is not None:
            raise HostError(
                "resource_name_in_use",
                "Configured container name is already in use",
                {
                    "data_volume": config.data_volume,
                    "container": config.container_name,
                    "container_id": _resource_identity(name_collision),
                },
            )
        network = self._get_optional(
            self.client.networks, config.network_name, "network"
        )
        network_ownership = "managed"
        if network is not None:
            network_ownership = self._classify_resource(network, "network", config.data_volume)
        volume = self._get_optional(
            self.client.volumes, config.data_volume, "volume"
        )
        volume_ownership = "managed"
        if volume is not None:
            volume_ownership = self._classify_resource(volume,
            "volume",
            config.data_volume,
            allow_legacy_volume=True,)
        return {
            "container": "managed",
            "network": network_ownership,
            "data_volume": volume_ownership,
        }


    def logs(
        self,
        config: LocalCloudConfig,
        runtime: RuntimeRecord,
        tail: int = 200,
    ) -> str:
        if tail < 0:
            raise HostError("invalid_tail", "Log tail must be zero or greater")
        container = self._log_container(config, runtime)
        try:
            output = container.logs(tail=tail, timestamps=True)
        except Exception as error:
            raise _logs_failed(config, runtime, error) from error
        return (
            output.decode("utf-8", errors="replace")
            if isinstance(output, bytes)
            else str(output)
        )

    def follow_logs(self, config: LocalCloudConfig, runtime: RuntimeRecord) -> str:
        """Log lines the runtime wrote since the previous read, without gaps."""
        container = self._log_container(config, runtime)
        cursor = self._log_cursors.get(str(runtime.container_id))
        if cursor is None:
            cursor = self._track_logs(container, from_start=False)
        try:
            return cursor.read(container)
        except Exception as error:
            raise _logs_failed(config, runtime, error) from error

    def mysql_companion_host_ip(self, config: LocalCloudConfig) -> str | None:
        """Address the runtime's Cloud SQL MySQL companion publishes on, if any."""
        try:
            companions = self.client.containers.list(
                all=True,
                filters={
                    "label": [
                        f"{VOLUME_NAME_LABEL}={config.data_volume}",
                        f"{_CHILD_SERVICE_LABEL}={_MYSQL_COMPANION_SERVICE}",
                    ]
                },
            )
        except Exception:
            return None
        for companion in companions:
            for bindings in _published_ports(companion).values():
                for host_ip, _host_port in bindings:
                    return host_ip
        return None

    def _track_logs(self, container: Any, *, from_start: bool) -> LogCursor:
        """Follow a container's log, from its current run or from its last lines."""
        if from_start:
            try:
                container.reload()
            except Exception:
                pass
            cursor = LogCursor.from_start(container)
        else:
            cursor = LogCursor(first_tail=12)
        self._log_cursors[_resource_identity(container)] = cursor
        return cursor

    def _log_container(self, config: LocalCloudConfig, runtime: RuntimeRecord) -> Any:
        container_id = str(runtime.container_id or "")
        if not container_id:
            raise HostError(
                "container_missing",
                "Runtime record has no immutable container ID",
                {"data_volume": config.data_volume},
            )
        try:
            return self.client.containers.get(container_id)
        except Exception as error:
            raise HostError(
                "container_missing",
                "Selected LocalCloud container no longer exists or cannot be inspected",
                {
                    "data_volume": config.data_volume,
                    "container_id": container_id,
                    "cause": str(error),
                },
            ) from error

    def is_ready(
        self,
        runtime: RuntimeRecord,
        *,
        timeout: float = 3.0,
    ) -> bool:
        if runtime.state != "running" or not runtime.url:
            return False
        try:
            response = _http_get(f"{runtime.url}/health", timeout=timeout)
            if response.status_code != 200:
                return False
            payload = response.json()
            return payload.get("status") in {"healthy", "ok", "ready"}
        except Exception:
            return False

    def effective_services(self, runtime: RuntimeRecord) -> tuple[str, ...] | None:
        """Return the enabled service IDs LocalCloud reports, or None if unavailable.

        Queried live from the running server rather than predicted from the
        public YAML, so results reflect catalog/tier/availability resolution
        the CLI does not own.
        """
        if runtime.state != "running" or not runtime.url:
            return None
        try:
            response = _http_get(f"{runtime.url}/services", timeout=3.0)
            if response.status_code != 200:
                return None
            payload = response.json()
        except Exception:
            return None
        services = payload.get("services") if isinstance(payload, dict) else None
        if not isinstance(services, list):
            return None
        return tuple(
            sorted(
                str(entry["id"])
                for entry in services
                if isinstance(entry, dict) and entry.get("enabled") and entry.get("id")
            )
        )

    def doctor(self) -> dict[str, Any]:
        legacy: list[dict[str, str]] = []
        invalid_ownership: list[dict[str, Any]] = []
        volume_users: dict[str, list[dict[str, str]]] = {}
        for kind, collection in (
            ("container", self.client.containers),
            ("network", self.client.networks),
            ("volume", self.client.volumes),
        ):
            try:
                resources = (
                    collection.list(all=True)
                    if kind == "container"
                    else collection.list()
                )
            except Exception as error:
                raise HostError(
                    "docker_inspect_failed",
                    f"Could not inspect {kind} resources",
                    {"resource": kind, "cause": str(error)},
                ) from error
            for resource in resources:
                labels = _resource_labels(resource)
                if any(label in labels for label in _LEGACY_LABELS):
                    legacy.append(
                        {"kind": kind, "name": _resource_name(resource) or "unknown"}
                    )
                if kind == "container":
                    for mount in _container_mounts(resource):
                        name = _mount_volume_name(mount)
                        if name:
                            volume_users.setdefault(name, []).append(
                                {
                                    "id": _resource_identity(resource),
                                    "name": _resource_name(resource) or "unknown",
                                }
                            )
                if labels.get(_CHILD_MANAGED_LABEL) == "true":
                    continue
                claimed_role = labels.get(RESOURCE_ROLE_LABEL)
                if MANAGED_LABEL in labels or claimed_role:
                    role = claimed_role or kind
                    data_volume = labels.get(VOLUME_NAME_LABEL)
                    if kind == "volume" and not data_volume:
                        data_volume = _resource_name(resource)
                    try:
                        self._classify_resource(
                            resource,
                            role,
                            data_volume or "invalid/data-volume",
                            allow_legacy_volume=kind == "volume",
                            resource_labels=labels,
                        )
                    except HostError as error:
                        invalid_ownership.append(
                            {
                                "kind": kind,
                                "name": _resource_name(resource),
                                "error": error.to_dict(),
                            }
                        )
                    except Exception as error:
                        invalid_ownership.append(
                            {
                                "kind": kind,
                                "name": _resource_name(resource),
                                "error": {
                                    "code": "resource_classification_failed",
                                    "message": str(error),
                                },
                            }
                        )
        collisions = [
            {"data_volume": volume, "containers": users}
            for volume, users in sorted(volume_users.items())
            if len(users) > 1
        ]
        try:
            version = self.client.version()
        except Exception:
            version = {}
        docker_command_path = shutil.which("docker")
        result: dict[str, Any] = {
            "status": "ok",
            "docker": version.get("Version")
            or version.get("version")
            or "available",
            "docker_command_path": docker_command_path,
            "docker_path": docker_command_path,
            "docker_command": docker_command_path,
            "legacy_resources": legacy,
            "volume_collisions": collisions,
            "invalid_ownership": invalid_ownership,
        }
        warnings: list[str] = []
        if legacy:
            warnings.append(
                "Legacy path-derived Docker resources are not migrated or removed automatically; clean them up manually after confirming they are unused."
            )
        if collisions:
            warnings.append(
                "Multiple containers share one or more named volumes; LocalCloud runtime selection will fail for those volumes."
            )
        if invalid_ownership:
            warnings.append(
                "Malformed LocalCloud ownership metadata was found; affected resources will not be mutated."
            )
        if warnings:
            result["warning"] = " ".join(warnings)
        return result

    def port_diagnostics(
        self,
        *,
        tls_enabled: bool = False,
        tls_port: int | None = None,
        local_only: bool = True,
        mysql_port: int | None = None,
        config: LocalCloudConfig | None = None,
    ) -> dict[str, Any]:
        """Check port availability for LocalCloud and report diagnostics.

        With `config`, its settings replace the keyword values, a configured
        port range is checked instead of the canonical ports, and the fallback
        block is sized for the ports its enabled services use.
        """
        if config is not None:
            tls_enabled = config.tls_enabled
            tls_port = config.tls_port
            local_only = config.local_only
            mysql_port = config.mysql_port
            if config.port_range is not None:
                return self._range_diagnostics(config)
        ports = list(_BASE_TCP_PORTS)
        if tls_enabled:
            effective_tls_port = tls_port if tls_port is not None else DEFAULT_TLS_PORT
            ports.extend((effective_tls_port, *_DEDICATED_TLS_PORTS))
        if mysql_port is not None:
            ports.append(mysql_port)
        canonical_ports = tuple(sorted(set(ports)))
        host_ip = "127.0.0.1" if local_only else ""

        # Find running LocalCloud containers and their port bindings.
        lc_port_owners: dict[int, str] = {}
        try:
            for container in self.client.containers.list(
                all=False,
                filters={"label": MANAGED_LABEL},
            ):
                labels = _resource_labels(container)
                if MANAGED_LABEL not in labels:
                    continue

                name = _resource_name(container) or "unknown"
                for port_spec, bindings in (
                    _published_ports(container).items()
                ):
                    port_text, _, _proto = port_spec.partition("/")
                    try:
                        int(port_text)
                    except ValueError:
                        continue
                    for _hip, hp in bindings:
                        if hp is not None:
                            lc_port_owners[hp] = name
        except Exception:
            pass

        occupied: list[dict[str, Any]] = []
        localcloud_ports: list[dict[str, Any]] = []
        for port in canonical_ports:
            if port in lc_port_owners:
                localcloud_ports.append(
                    {"port": port, "container": lc_port_owners[port]}
                )
            elif not _port_is_free(port, socket.SOCK_STREAM, host_ip):
                occupied.append({"port": port, "status": "in_use"})

        all_canonical_available = not occupied
        has_lc_container = bool(localcloud_ports)

        alternative_range: list[int] | None = None
        if occupied and not has_lc_container:
            try:
                alt = _available_tcp_port_block(
                    _fallback_block_size(config)
                    if config is not None
                    else len(canonical_ports),
                    set(),
                    host_ip,
                )
                alternative_range = list(alt)
            except HostError:
                alternative_range = None

        if has_lc_container:
            status = "in_use_by_localcloud"
        elif all_canonical_available:
            status = "available"
        elif alternative_range is not None:
            status = "conflict_with_alternative"
        else:
            status = "conflict"

        return {
            "canonical_ports": list(canonical_ports),
            "all_canonical_available": all_canonical_available,
            "occupied_ports": occupied,
            "localcloud_ports": localcloud_ports,
            "alternative_range": alternative_range,
            "status": status,
        }

    def _range_diagnostics(self, config: LocalCloudConfig) -> dict[str, Any]:
        """The block a runtime would take from its configured port range."""
        assert config.port_range is not None
        start, end = config.port_range
        host_ip = "127.0.0.1" if config.local_only else ""
        try:
            block = list(
                _available_tcp_port_block(
                    _fallback_block_size(config),
                    set(),
                    host_ip,
                    _configured_port_ranges(config),
                )
            )
        except HostError:
            block = []
        return {
            "canonical_ports": block or list(range(start, end + 1)),
            "configured_range": [start, end],
            "all_canonical_available": bool(block),
            "occupied_ports": [],
            "localcloud_ports": [],
            "alternative_range": None,
            "status": "available" if block else "conflict",
        }

    def cleanup_resources(
        self, invalid: list[dict[str, Any]]
    ) -> dict[str, Any]:
        removed: list[dict[str, str]] = []
        failures: list[dict[str, Any]] = []
        for entry in invalid:
            kind = entry["kind"]
            name = entry.get("name")
            if not name:
                continue
            if kind == "container":
                collection = self.client.containers
            elif kind == "network":
                collection = self.client.networks
            elif kind == "volume":
                collection = self.client.volumes
            else:
                continue
            resource = self._get_optional(collection, name, kind)
            if resource is None:
                continue
            try:
                if kind == "container":
                    resource.remove(force=True, v=True)
                elif kind == "volume":
                    resource.remove(force=True)
                else:
                    resource.remove()
            except Exception as error:  # noqa: BLE001
                failures.append(
                    {"kind": kind, "name": name, "cause": str(error)}
                )
                continue
            removed.append({"kind": kind, "name": name})
        return {"removed": removed, "failures": failures}

    def image_status(self, image_name: str) -> str:
        if image_name in self._resolved_image_details:
            return "available locally"
        try:
            self.client.images.get(image_name)
            return "available locally"
        except Exception:
            return "not available locally"

    @staticmethod
    def _short_id_from_raw(raw_id: Any) -> str:
        if not raw_id:
            return "unknown"
        return str(raw_id).removeprefix("sha256:")[:12]

    @staticmethod
    def _normalize_sha(sha: str | None, raw_id: Any) -> str:
        if not sha:
            sha = str(raw_id) if raw_id else "unknown"
        if sha != "unknown" and not sha.startswith("sha256:"):
            sha = f"sha256:{sha}"
        return sha

    @staticmethod
    def _image_details_result(
        location: str, short_id: str, sha: str
    ) -> dict[str, Any]:
        if location == "Local":
            formatted = f"({location}: ID: {short_id} , {sha})"
        elif sha == "unknown":
            formatted = "(not available locally)"
        else:
            formatted = f"(not available locally · registry digest: {sha})"
        return {
            "location": location,
            "image_id": short_id,
            "sha": sha,
            "formatted": formatted,
        }

    def _image_details_from_image(self, image: Any) -> dict[str, Any]:
        attrs = getattr(image, "attrs", None)
        raw_id = getattr(image, "id", None) or (
            attrs.get("Id") if isinstance(attrs, dict) else None
        )
        short_id = self._short_id_from_raw(raw_id)
        sha = None
        if isinstance(attrs, dict):
            repo_digests = attrs.get("RepoDigests") or []
            if isinstance(repo_digests, list):
                for rd in repo_digests:
                    if "@" in str(rd):
                        sha = str(rd).split("@", 1)[1]
                        break
            if not sha:
                desc_digest = attrs.get("Descriptor", {}).get("digest")
                if desc_digest:
                    sha = str(desc_digest)
        sha = self._normalize_sha(sha, raw_id)
        return self._image_details_result("Local", short_id, sha)

    def image_details(self, image_name: str) -> dict[str, Any]:
        if image_name in self._resolved_image_details:
            return dict(self._resolved_image_details[image_name])
        try:
            image = self.client.images.get(image_name)
            details = self._image_details_from_image(image)
            self._resolved_image_details[image_name] = details
            return dict(details)
        except Exception:
            try:
                reg_data = self.client.images.get_registry_data(image_name)
                attrs = getattr(reg_data, "attrs", None)
                raw_id = getattr(reg_data, "id", None) or (
                    attrs.get("Descriptor", {}).get("digest")
                    if isinstance(attrs, dict)
                    else None
                )
                reg_sha = (
                    attrs.get("Descriptor", {}).get("digest")
                    if isinstance(attrs, dict)
                    else None
                )
                reg_sha = self._normalize_sha(reg_sha, raw_id)
                return self._image_details_result(
                    "Remote", "not available locally", reg_sha
                )
            except Exception:
                return self._image_details_result(
                    "Remote", "not available locally", "unknown"
                )

    @staticmethod
    def wait_ready(
        url: str,
        *,
        deadline: float,
        container: Any | None = None,
        observer: Any | None = None,
        cursor: LogCursor | None = None,
    ) -> dict[str, Any]:
        normalized = _validate_base_url(url)
        timeout = max(0.0, deadline - time.monotonic())
        last_error = "not attempted"
        if observer is not None and hasattr(observer, "debug"):
            observer.debug(
                f"Waiting for runtime readiness at {normalized}/health "
                f"(timeout {timeout:.1f}s)"
            )

        last_log_emit = 0.0
        _LOG_EMIT_INTERVAL = 2.0
        log_cursor = cursor or LogCursor(first_tail=12)

        def _emit_logs(*, force: bool = False) -> None:
            nonlocal last_log_emit
            now = time.monotonic()
            if not force and (now - last_log_emit) < _LOG_EMIT_INTERVAL:
                return
            last_log_emit = now
            if (
                container is not None
                and observer is not None
                and hasattr(observer, "runtime_logs")
            ):
                try:
                    logs = log_cursor.read(container)
                    if logs:
                        observer.runtime_logs(logs)
                except Exception:
                    pass

        _emit_logs(force=True)

        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            if container is not None:
                try:
                    container.reload()
                    state = _container_state(container)
                except Exception as error:
                    state = "unknown"
                    last_error = f"container inspection failed: {error}"
                if state in {"dead", "exited", "removing"}:
                    raise HostError(
                        "container_start_failed",
                        "LocalCloud runtime container exited before becoming healthy",
                        {
                            "container": _resource_name(container),
                            "state": state,
                            "logs": _container_logs(container),
                        },
                    )
            _emit_logs()
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            try:
                response = _http_get(
                    f"{normalized}/health",
                    timeout=min(3.0, remaining),
                )
                if response.status_code == 200:
                    payload = response.json()
                    if payload.get("status") in {"healthy", "ok", "ready"}:
                        _emit_logs(force=True)
                        if observer is not None and hasattr(observer, "debug"):
                            observer.debug(
                                f"Runtime readiness succeeded at {normalized}/health"
                            )
                        return payload
                    last_error = f"health returned {payload}"
                else:
                    last_error = f"HTTP {response.status_code}"
            except Exception as error:
                last_error = str(error)
            _emit_logs()
            remaining = deadline - time.monotonic()
            if remaining > 0:
                time.sleep(min(1.0, remaining))
        raise HostError(
            "health_timeout",
            "LocalCloud did not become healthy",
            {
                "url": normalized,
                "timeout_seconds": timeout,
                "last_error": last_error,
                "logs": _container_logs(container) if container is not None else "",
            },
        )

    def _list_containers(self, data_volume: str) -> list[Any]:
        try:
            return list(
                self.client.containers.list(
                    all=True,
                    filters={"volume": data_volume},
                    sparse=True,
                )
            )
        except Exception as error:
            raise HostError(
                "docker_inspect_failed",
                "Could not list Docker containers for the selected data volume",
                {"resource": "container", "cause": str(error)},
            ) from error

    @staticmethod
    def _validated_data_mount(
        data_volume: str,
        container: Any,
        mounts: list[dict[str, Any]],
    ) -> dict[str, Any]:
        if len(mounts) != 1:
            raise HostError(
                "invalid_data_volume_mount",
                "The selected data volume must be mounted exactly once",
                {
                    "data_volume": data_volume,
                    "container_id": _resource_identity(container),
                    "mounts": mounts,
                },
            )
        mount = mounts[0]
        destination = str(mount.get("Destination") or mount.get("Target") or "")
        read_write = mount.get("RW")
        if read_write is None:
            read_write = "ro" not in str(mount.get("Mode") or "").split(",")
        if (
            str(mount.get("Type") or "volume") != "volume"
            or destination != DATA_MOUNT_DESTINATION
            or read_write is not True
        ):
            raise HostError(
                "invalid_data_volume_mount",
                "The selected data volume must be mounted read-write at /var/lib/localcloud",
                {
                    "data_volume": data_volume,
                    "container_id": _resource_identity(container),
                    "mount": mount,
                },
            )
        return {
            "type": "volume",
            "source": data_volume,
            "destination": destination,
            "mode": "rw",
            "read_write": True,
        }

    def _compatible_image(
        self,
        config: LocalCloudConfig,
        container: Any,
        *,
        allow_reconfiguration: bool = False,
    ) -> dict[str, Any]:
        attrs = getattr(container, "attrs", {})
        declared = str(attrs.get("Config", {}).get("Image") or "").strip()
        container_id = str(attrs.get("Image") or "").strip()
        configured_id = None
        try:
            image_obj = self.client.images.get(config.image)
            configured_id = _image_id(image_obj)
            self._resolved_image_details[config.image] = self._image_details_from_image(image_obj)
        except Exception as error:
            if not _is_not_found(error):
                raise HostError(
                    "docker_inspect_failed",
                    "Could not inspect the configured LocalCloud image",
                    {"image": config.image, "cause": str(error)},
                ) from error
        reference_match = (
            bool(declared)
            and _normalize_image_reference(declared)
            == _normalize_image_reference(config.image)
        )
        id_match = bool(
            container_id and configured_id and container_id == configured_id
        )
        if not allow_reconfiguration and not reference_match and not id_match:
            raise HostError(
                "incompatible_data_volume_user",
                "A container using the selected data volume has an incompatible image",
                {
                    "data_volume": config.data_volume,
                    "container_id": _resource_identity(container),
                    "configured_image": config.image,
                    "actual_image": declared or None,
                    "actual_image_id": container_id or None,
                    "configured_image_id": configured_id,
                },
            )
        return {
            "declared": declared or None,
            "container_id": container_id or None,
            "configured_id": configured_id,
        }

    def _classify_resource(
        self,
        resource: Any,
        role: str,
        data_volume: str,
        *,
        allow_legacy_volume: bool = False,
        resource_labels: dict[str, str] | None = None,
    ) -> str:
        labels = (
            resource_labels
            if resource_labels is not None
            else _resource_labels(resource)
        )
        claimed = {key: labels[key] for key in _MANAGEMENT_LABELS if key in labels}
        if not claimed:
            return "attached"
        if labels.get(MANAGED_LABEL) != "true":
            raise _ownership_error(resource, role, data_volume, labels)
        if labels.get(RESOURCE_ROLE_LABEL) != role:
            raise _ownership_error(resource, role, data_volume, labels)
        actual_volume = labels.get(VOLUME_NAME_LABEL)
        legacy_volume = bool(
            allow_legacy_volume
            and role == "volume"
            and actual_volume is None
            and labels.get(INSTANCE_LABEL)
            and _resource_name(resource) == data_volume
        )
        if role in {"volume", "container"}:
            if not legacy_volume and actual_volume != data_volume:
                raise _ownership_error(resource, role, data_volume, labels)
        if role == "container":
            missing = [label for label in _CONTAINER_METADATA_LABELS if label not in labels]
            if missing:
                raise HostError(
                    "ownership_mismatch",
                    "Managed container ownership metadata is incomplete",
                    {
                        "data_volume": data_volume,
                        "container_id": _resource_identity(resource),
                        "missing_labels": missing,
                    },
                )
        return "managed"

    @staticmethod
    def _container_metadata(
        container: Any,
        labels: dict[str, str],
        data_volume: str,
        ownership: str,
    ) -> dict[str, Any]:
        environment = _container_environment_values(container)
        if ownership == "attached":
            services = labels.get(SERVICES_LABEL)
            if services is None:
                services = environment.get("LOCALCLOUD_SERVICES", "<default>")
            return {
                "config_hash": None,
                "config_path": None,
                "runtime_config": None,
                "services": services or "<default>",
                "data": labels.get(DATA_LABEL, "persistent"),
            }
        try:
            runtime_config = json.loads(labels[CONFIG_LABEL])
        except (TypeError, json.JSONDecodeError) as error:
            raise HostError(
                "ownership_mismatch",
                "Managed container has invalid runtime configuration metadata",
                {
                    "data_volume": data_volume,
                    "container_id": _resource_identity(container),
                },
            ) from error
        if not isinstance(runtime_config, dict):
            raise HostError(
                "ownership_mismatch",
                "Managed container runtime configuration metadata must be an object",
                {
                    "data_volume": data_volume,
                    "container_id": _resource_identity(container),
                },
            )
        config_path = labels[CONFIG_PATH_LABEL]
        return {
            "config_hash": labels[CONFIG_HASH_LABEL],
            "config_path": config_path,
            "runtime_config": runtime_config,
            "services": "<default>"
            if config_path == DEFAULTS_CONFIG_LABEL
            else "<config>",
            "data": labels[DATA_LABEL],
        }

    @staticmethod
    def _container_network(
        config: LocalCloudConfig,
        container: Any,
        metadata: dict[str, Any],
    ) -> str | None:
        networks = sorted(
            str(name)
            for name in (
                getattr(container, "attrs", {})
                .get("NetworkSettings", {})
                .get("Networks", {})
            )
        )
        runtime_config = metadata.get("runtime_config")
        if runtime_config is not None:
            network_name = str(runtime_config.get("network_name") or "")
            if not network_name:
                network_name = str(
                    getattr(container, "labels", {}).get(NETWORK_NAME_LABEL) or ""
                )
            if networks and network_name not in networks:
                raise HostError(
                    "ownership_mismatch",
                    "Managed container is not attached to its recorded network",
                    {
                        "data_volume": config.data_volume,
                        "network": network_name,
                        "actual_networks": networks,
                    },
                )
            return network_name or None
        if config.network_name in networks:
            return config.network_name
        non_default = [name for name in networks if name not in {"bridge", "host", "none"}]
        return non_default[0] if non_default else (networks[0] if networks else None)

    def _is_newer_image_available(
        self,
        image_name: str,
        local_image: Any,
        *,
        observer: Any | None = None,
    ) -> bool:
        if "@sha256:" in image_name:
            return False

        attrs = getattr(local_image, "attrs", None)
        if not isinstance(attrs, dict):
            attrs = {}

        local_digests: set[str] = set()
        repo_digests = attrs.get("RepoDigests") or []
        if isinstance(repo_digests, list):
            for rd in repo_digests:
                if "@" in str(rd):
                    local_digests.add(str(rd).split("@", 1)[1].strip())
        raw_id = getattr(local_image, "id", None) or attrs.get("Id")
        if raw_id:
            raw_id_str = str(raw_id).strip()
            local_digests.add(raw_id_str)
            local_digests.add(raw_id_str.removeprefix("sha256:").strip())
        desc_digest = attrs.get("Descriptor", {}).get("digest")
        if desc_digest:
            desc_digest_str = str(desc_digest).strip()
            local_digests.add(desc_digest_str)
            local_digests.add(desc_digest_str.removeprefix("sha256:").strip())

        get_reg_data = getattr(self.client.images, "get_registry_data", None)
        if not callable(get_reg_data):
            return False

        try:
            reg_data = get_reg_data(image_name)
        except Exception as error:
            if observer is not None and hasattr(observer, "debug"):
                observer.debug(
                    f"Could not check registry for updates to {image_name!r}: {error}"
                )
            return False

        reg_attrs = getattr(reg_data, "attrs", None)
        if not isinstance(reg_attrs, dict):
            reg_attrs = {}

        reg_digest = (
            reg_attrs.get("Descriptor", {}).get("digest")
            or getattr(reg_data, "id", None)
        )
        if not reg_digest:
            return False

        reg_digest_str = str(reg_digest).strip()
        reg_digest_clean = reg_digest_str.removeprefix("sha256:").strip()
        reg_digest_full = f"sha256:{reg_digest_clean}"

        if (
            reg_digest_clean in local_digests
            or reg_digest_full in local_digests
            or reg_digest_str in local_digests
        ):
            return False

        return True

    def _image_for_create(
        self,
        image_name: str,
        *,
        pull: bool = True,
        observer: Any | None = None,
        local_only: bool = False,
    ) -> tuple[Any, bool]:
        if local_only and pull:
            raise HostError(
                "dry_run_pull_conflict",
                "Read-only image planning cannot pull images",
                {"image": image_name},
            )
        try:
            local_image = self.client.images.get(image_name)
        except Exception as first_error:
            if not _is_not_found(first_error):
                # Anything other than "image doesn't exist locally" (auth
                # failure, daemon error, ...) isn't fixed by pulling and
                # would just trigger a confusing, unrequested network pull.
                raise HostError(
                    "invalid_image",
                    "Selected LocalCloud image could not be inspected",
                    {
                        "image": image_name,
                        "cause": str(first_error),
                    },
                ) from first_error
            if local_only:
                raise HostError(
                    "dry_run_image_unavailable",
                    "Dry-run requires the configured image to exist locally",
                    {
                        "image": image_name,
                        "cause": str(first_error),
                        "suggestion": "Pull the image separately, then retry without --pull",
                    },
                ) from first_error
            try:
                return self._pull_image(image_name, observer=observer), True
            except Exception as error:
                raise HostError(
                    "invalid_image",
                    "Selected LocalCloud image could not be pulled",
                    {
                        "image": image_name,
                        "cause": str(error),
                        "local_cause": str(first_error),
                    },
                ) from error

        if pull and self._is_newer_image_available(
            image_name, local_image, observer=observer
        ):
            try:
                return self._pull_image(image_name, observer=observer), True
            except Exception as error:
                raise HostError(
                    "invalid_image",
                    "Selected LocalCloud image could not be pulled",
                    {
                        "image": image_name,
                        "cause": str(error),
                    },
                ) from error

        return local_image, False

    def _pull_image(self, image_name: str, *, observer: Any | None = None) -> Any:
        def emit(
            status: str,
            *,
            layer: str | None = None,
            current: int | None = None,
            total: int | None = None,
        ) -> None:
            if observer is not None and hasattr(observer, "image_pull"):
                observer.image_pull(
                    image_name,
                    status=status,
                    layer=layer,
                    current=current,
                    total=total,
                )

        emit("Contacting registry")
        api = getattr(self.client, "api", None)
        pull_stream = getattr(api, "pull", None)
        progress_enabled = observer is not None and hasattr(observer, "image_pull")
        if not progress_enabled or not callable(pull_stream):
            image = self.client.images.pull(image_name)
            self._resolved_image_details.clear()
            emit("Pull complete")
            return image

        seen_statuses: set[tuple[str, str]] = set()
        progress_buckets: dict[tuple[str, str], int] = {}
        events = pull_stream(image_name, stream=True, decode=True)
        for event in events:
            if not isinstance(event, Mapping):
                continue
            error_detail = event.get("errorDetail")
            error_message = event.get("error")
            if isinstance(error_detail, Mapping):
                error_message = error_detail.get("message") or error_message
            if error_message:
                raise RuntimeError(str(error_message))

            status = str(event.get("status") or "Fetching")
            layer_value = event.get("id")
            layer = str(layer_value) if layer_value else None
            detail = event.get("progressDetail")
            current = total = None
            if isinstance(detail, Mapping):
                raw_current = detail.get("current")
                raw_total = detail.get("total")
                if isinstance(raw_current, (int, float)):
                    current = max(0, int(raw_current))
                if isinstance(raw_total, (int, float)) and raw_total > 0:
                    total = int(raw_total)

            key = (layer or "", status)
            if current is not None and total:
                bucket = min(20, int(current / total * 20))
                if progress_buckets.get(key) == bucket and current < total:
                    continue
                progress_buckets[key] = bucket
            elif key in seen_statuses:
                continue
            seen_statuses.add(key)
            emit(
                status,
                layer=layer,
                current=current,
                total=total,
            )

        image = self.client.images.get(image_name)
        self._resolved_image_details.clear()
        emit("Pull complete")
        return image

    @staticmethod
    def _require_runtime_ownership_capability(
        config: LocalCloudConfig, image: Any
    ) -> None:
        labels = _image_labels(image)
        actual_ownership = labels.get(RUNTIME_OWNERSHIP_LABEL)
        actual_schema = labels.get(CONFIG_SCHEMA_LABEL)
        missing: dict[str, dict[str, str | None]] = {}
        if actual_ownership != RUNTIME_OWNERSHIP_CAPABILITY:
            missing[RUNTIME_OWNERSHIP_LABEL] = {
                "expected": RUNTIME_OWNERSHIP_CAPABILITY,
                "actual": actual_ownership,
            }
        if (
            config.config_path is not None
            and actual_schema != CONFIG_SCHEMA_CAPABILITY
        ):
            missing[CONFIG_SCHEMA_LABEL] = {
                "expected": CONFIG_SCHEMA_CAPABILITY,
                "actual": actual_schema,
            }
        if missing:
            raise HostError(
                "managed_image_capability_missing",
                "The configured image does not support this LocalCloud CLI",
                {"image": config.image, "capabilities": missing},
            )

    @staticmethod
    def _validate_image_port_metadata(
        config: LocalCloudConfig,
        image: Any,
        observer: Any | None,
    ) -> None:
        exposed = _image_exposed_ports(image)
        if f"{GATEWAY_PORT}/tcp" not in exposed:
            raise HostError(
                "invalid_image",
                f"Selected LocalCloud image does not expose {GATEWAY_PORT}/tcp",
                {"image": config.image, "exposed_ports": sorted(exposed)},
            )
        missing = sorted(_IMAGE_PORT_CAPABILITIES - exposed)
        unexpected = sorted(exposed - _IMAGE_PORT_CAPABILITIES)
        if missing:
            raise HostError(
                "invalid_image",
                "Selected LocalCloud image is missing required port capabilities",
                {
                    "image": config.image,
                    "missing": missing,
                    "expected": sorted(_IMAGE_PORT_CAPABILITIES),
                    "actual": sorted(exposed),
                },
            )
        if not missing and not unexpected:
            return
        details = {
            "image": config.image,
            "missing": missing,
            "unexpected": unexpected,
            "expected": sorted(_IMAGE_PORT_CAPABILITIES),
            "actual": sorted(exposed),
        }
        message = (
            "Docker image EXPOSE metadata differs from the canonical LocalCloud "
            f"capability set (missing={missing}, unexpected={unexpected})"
        )
        if config.strict_port_validation:
            raise HostError("image_port_metadata_mismatch", message, details)
        _emit_warning(observer, message)

    def _port_bindings(
        self,
        config: LocalCloudConfig,
        *,
        allowed_ports: set[tuple[int, str]] | None = None,
    ) -> RequestedPorts:
        return self._port_plan(config, allowed_ports=allowed_ports)[0]

    def _port_plan(
        self,
        config: LocalCloudConfig,
        *,
        allowed_ports: set[tuple[int, str]] | None = None,
    ) -> tuple[RequestedPorts, int | None]:
        """Runtime-container bindings and the MySQL companion's host port.

        The canonical layout publishes every canonical port. When any of them
        is taken, or `port_range` is set, the runtime publishes only the ports
        its enabled services use, as one block from the fallback ranges or the
        configured range. The MySQL companion's port belongs to the same set
        and takes the block's last port, unless LOCALCLOUD_MYSQL_PORT pins it.
        """
        movable_mysql = None if _mysql_port_pinned(config) else config.mysql_port

        def with_mysql(ports: tuple[int, ...]) -> tuple[int, ...]:
            return (*ports, movable_mysql) if movable_mysql is not None else ports

        host_ip = "127.0.0.1" if config.local_only else ""
        allowed = allowed_ports or set()
        published = _ordinary_tcp_ports(config)
        host_ports: tuple[int, ...] | None = None
        if config.port_range is None and all(
            _port_is_free(port, socket.SOCK_STREAM, host_ip) or (port, "tcp") in allowed
            for port in with_mysql(published)
        ):
            host_ports = with_mysql(published)
        if host_ports is None:
            published = _fallback_tcp_ports(config)
            host_ports = _available_tcp_port_block(
                len(with_mysql(published)),
                allowed,
                host_ip,
                _configured_port_ranges(config),
            )
        mysql_host_port = (
            host_ports[len(published)]
            if movable_mysql is not None
            else config.mysql_port
        )
        bindings: RequestedPorts = {
            f"{container_port}/tcp": ((host_ip, host_port),)
            for container_port, host_port in zip(published, host_ports)
        }
        if not config.transparent_network:
            return bindings, mysql_host_port
        if not config.tls_enabled:
            raise HostError(
                "invalid_config",
                "Transparent networking requires TLS to be enabled",
                {"field": "host.transparent_network"},
            )
        for host_port, container_port, protocol in (
            (53, _DNS_PORT, "udp"),
            (80, int(GATEWAY_PORT), "tcp"),
            (443, config.tls_port, "tcp"),
        ):
            kind = socket.SOCK_DGRAM if protocol == "udp" else socket.SOCK_STREAM
            if (
                not _port_is_free(host_port, kind, host_ip)
                and (host_port, protocol) not in allowed
            ):
                raise HostError(
                    "transparent_port_unavailable",
                    "Transparent networking requires free host ports 53, 80, and 443",
                    {"port": host_port, "protocol": protocol},
                )
            key = f"{container_port}/{protocol}"
            bindings[key] = (*bindings.get(key, ()), (host_ip, host_port))
        return bindings, mysql_host_port

    def _volume_for_create(
        self, config: LocalCloudConfig, labels: dict[str, str]
    ) -> tuple[Any, bool]:
        existing = self._get_optional(
            self.client.volumes, config.data_volume, "volume"
        )
        if existing is not None:
            self._classify_resource(
                existing,
                "volume",
                config.data_volume,
                allow_legacy_volume=True,
            )
            return existing, False
        return (
            self.client.volumes.create(name=config.data_volume, labels=labels),
            True,
        )

    def _network_for_create(
        self, config: LocalCloudConfig, labels: dict[str, str]
    ) -> tuple[Any, bool]:
        existing = self._get_optional(
            self.client.networks, config.network_name, "network"
        )
        if existing is not None:
            ownership = self._classify_resource(existing, "network", config.data_volume)
            if ownership != "managed":
                raise HostError(
                    "resource_name_in_use",
                    "Configured network name is already used by an attached resource",
                    {"network": config.network_name},
                )
            return existing, False
        return (
            self.client.networks.create(
                config.network_name,
                driver="bridge",
                labels=labels,
                check_duplicate=True,
            ),
            True,
        )

    @staticmethod
    def _require_container_identity(
        config: LocalCloudConfig,
        expected_container_id: str | None,
        current: RuntimeRecord,
    ) -> RuntimeRecord:
        if current.container_id != expected_container_id:
            raise HostError(
                "container_changed",
                "The selected data volume now resolves to a different container",
                {
                    "data_volume": config.data_volume,
                    "expected_container_id": expected_container_id,
                    "actual_container_id": current.container_id,
                },
            )
        return current


    def _mutation_target(
        self, config: LocalCloudConfig, runtime: RuntimeRecord
    ) -> tuple[Any, RuntimeRecord]:
        container_id = str(runtime.container_id or "")
        if not container_id:
            raise HostError(
                "container_missing",
                "Runtime record has no immutable container ID",
                {"data_volume": config.data_volume},
            )
        try:
            container = self.client.containers.get(container_id)
        except Exception as error:
            raise HostError(
                "container_missing",
                "Selected LocalCloud container no longer exists or cannot be inspected",
                {
                    "data_volume": config.data_volume,
                    "container_id": container_id,
                    "cause": str(error),
                },
            ) from error
        current = self.resolve(
            config, preferred_container_id=container_id, require=True
        )
        assert current is not None
        if current.container_id != container_id:
            raise HostError(
                "container_changed",
                "The selected data volume now resolves to a different container",
                {
                    "data_volume": config.data_volume,
                    "expected_container_id": container_id,
                    "actual_container_id": current.container_id,
                },
            )
        return container, current

    @staticmethod
    def _require_gateway(runtime: RuntimeRecord) -> None:
        if not runtime.url:
            raise HostError(
                "gateway_not_published",
                f"LocalCloud runtime does not publish {GATEWAY_PORT}/tcp",
                {
                    "data_volume": runtime.data_volume,
                    "container_id": runtime.container_id,
                    "endpoint_map": runtime.endpoint_map,
                },
            )

    def _remove_children(
        self,
        data_volume: str,
        parent: Any | None,
        runtime: RuntimeRecord | None,
        failures: list[dict[str, Any]],
        *,
        dry_run: bool = False,
        services: frozenset[str] | None = None,
    ) -> list[Any]:
        owned = self._owned_children(
            data_volume, parent, runtime, failures, services=services
        )
        if dry_run:
            return [child for child, _expected in owned]
        for child, expected in owned:
            _remove_verified(
                child,
                "child_container",
                expected,
                failures,
                force=True,
                v=True,
            )
        return [child for child, _expected in owned]

    def _stop_children(
        self,
        data_volume: str,
        parent: Any | None,
        runtime: RuntimeRecord | None,
        failures: list[dict[str, Any]],
    ) -> None:
        """Stop children the server left running, e.g. after a forced stop."""
        for child, _expected in self._owned_children(
            data_volume, parent, runtime, failures
        ):
            if _container_state(child) != "running":
                continue
            try:
                child.stop(timeout=STOP_TIMEOUT_SECONDS)
            except Exception as error:
                failures.append(
                    {
                        "resource": "child_container",
                        "identity": _resource_identity(child),
                        "cause": str(error),
                    }
                )

    def _owned_children(
        self,
        data_volume: str,
        parent: Any | None,
        runtime: RuntimeRecord | None,
        failures: list[dict[str, Any]],
        *,
        services: frozenset[str] | None = None,
    ) -> list[tuple[Any, dict[str, str]]]:
        """Children whose ownership labels match; `services` narrows the set."""
        try:
            containers = self.client.containers.list(
                all=True,
                filters={"label": f"{_CHILD_MANAGED_LABEL}=true"},
            )
        except Exception as error:
            failures.append(
                {
                    "resource": "child_containers",
                    "identity": data_volume,
                    "cause": str(error),
                }
            )
            return []
        parent_id = _resource_identity(parent) if parent is not None else None
        legacy_instance = None
        legacy_hash = None
        if runtime is not None and runtime.ownership["container"] == "managed":
            labels = runtime.labels
            legacy_instance = labels.get(INSTANCE_LABEL)
            legacy_hash = labels.get(CONFIG_HASH_LABEL)
        owned: list[tuple[Any, dict[str, str]]] = []
        for child in containers:
            if parent_id == _resource_identity(child):
                continue
            labels = _resource_labels(child)
            if services is not None and labels.get(_CHILD_SERVICE_LABEL) not in services:
                continue
            new_claim = (
                labels.get(VOLUME_NAME_LABEL) == data_volume
                and labels.get(_CHILD_MANAGED_LABEL) == "true"
            )
            legacy_claim = bool(
                legacy_instance
                and legacy_hash
                and labels.get(INSTANCE_LABEL) == legacy_instance
                and labels.get(CONFIG_HASH_LABEL) == legacy_hash
                and labels.get(_CHILD_MANAGED_LABEL) == "true"
            )
            if not new_claim and not legacy_claim:
                continue
            expected = {
                MANAGED_LABEL: "true",
                _CHILD_MANAGED_LABEL: "true",
                CONFIG_HASH_LABEL: legacy_hash
                if legacy_claim
                else labels.get(CONFIG_HASH_LABEL, ""),
            }
            if new_claim:
                expected[VOLUME_NAME_LABEL] = data_volume
            else:
                expected[INSTANCE_LABEL] = str(legacy_instance)
            mismatches = _label_mismatches(labels, expected)
            if not expected[CONFIG_HASH_LABEL]:
                mismatches[CONFIG_HASH_LABEL] = {
                    "expected": "<non-empty>",
                    "actual": labels.get(CONFIG_HASH_LABEL),
                }
            if mismatches:
                failures.append(
                    {
                        "resource": "child_container",
                        "identity": _resource_identity(child),
                        "cause": f"ownership label mismatch: {mismatches}",
                    }
                )
            else:
                owned.append((child, expected))
        if failures:
            return []
        return owned

    @staticmethod
    def _get_optional(collection: Any, name: str, kind: str) -> Any | None:
        try:
            return collection.get(name)
        except Exception as error:
            if _is_not_found(error):
                return None
            raise HostError(
                "docker_inspect_failed",
                f"Could not inspect named {kind}",
                {"resource": kind, "name": name, "cause": str(error)},
            ) from error

    def _require_name_available(
        self, collection: Any, name: str, kind: str
    ) -> None:
        existing = self._get_optional(collection, name, kind)
        if existing is not None:
            raise HostError(
                "resource_name_in_use",
                f"Configured {kind} name is already in use",
                {"resource": kind, "name": name},
            )

    @staticmethod
    def _resolved_ports(container: Any) -> dict[str, int]:
        return _endpoint_map(_published_ports(container))

    @staticmethod
    def _rollback_create(
        container: Any,
        network: Any,
        volume: Any,
        container_labels: dict[str, str],
        network_labels: dict[str, str],
        volume_labels: dict[str, str],
    ) -> list[dict[str, Any]]:
        failures: list[dict[str, Any]] = []
        for kind, resource, labels, kwargs in (
            ("container", container, container_labels, {"force": True, "v": True}),
            ("network", network, network_labels, {}),
            ("volume", volume, volume_labels, {"force": True}),
        ):
            if resource is not None:
                _remove_verified(resource, kind, labels, failures, **kwargs)
        return failures

    # --- Identity session relays (`lc env --identity`) -------------------------------------------

    def identity_relays(
        self,
        data_volume: str,
        *,
        project: str | None = None,
        account_key: str | None = None,
    ) -> list[Any]:
        """Identity relay containers of this data volume, optionally one project or account."""
        try:
            containers = self.client.containers.list(
                all=True,
                filters={
                    "label": [
                        IDENTITY_SESSION_LABEL,
                        f"{VOLUME_NAME_LABEL}={data_volume}",
                    ]
                },
            )
        except Exception as error:
            raise HostError(
                "docker_inspect_failed",
                "Could not list LocalCloud identity relays",
                {"data_volume": data_volume, "cause": str(error)},
            ) from error
        selected = []
        for container in containers:
            labels = _resource_labels(container)
            if labels.get(VOLUME_NAME_LABEL) != data_volume:
                continue
            if project is not None and labels.get(IDENTITY_PROJECT_LABEL) != project:
                continue
            if account_key is not None and labels.get(IDENTITY_ACCOUNT_LABEL) != account_key:
                continue
            selected.append(container)
        return selected

    def start_identity_relay(
        self,
        runtime: RuntimeRecord,
        *,
        session_id: str,
        capability: str,
        project: str,
        account_key: str,
        relay_profile: Mapping[str, Any] | None = None,
        preferred_host_port: int | None = None,
    ) -> dict[str, Any]:
        """Start a session's host relay from the runtime's own image.

        The relay joins the runtime's network and reaches the gateway by the runtime's container
        name; its metadata listener is published on 127.0.0.1 only. The capability is passed in
        the relay-only environment variable and nowhere else.
        """
        if not runtime.network_name or not runtime.name:
            raise HostError(
                "identity_relay_unavailable",
                "The LocalCloud runtime has no network to attach an identity relay to",
                {"data_volume": runtime.data_volume},
            )
        profile = dict(relay_profile or {})
        command = profile.get("command")
        if not (
            isinstance(command, list)
            and command
            and all(isinstance(part, str) and part for part in command)
        ):
            command = [IDENTITY_RELAY_BINARY, "workload"]
        port_value = profile.get("metadataPort")
        metadata_port = (
            port_value
            if isinstance(port_value, int) and 0 < port_value < 65536
            else IDENTITY_RELAY_METADATA_PORT
        )
        capability_env = profile.get("capabilityEnv")
        if not isinstance(capability_env, str) or not capability_env:
            capability_env = IDENTITY_RELAY_CAPABILITY_ENV
        environment = {
            "LOCALCLOUD_RELAY_METADATA_ADDR": f"0.0.0.0:{metadata_port}",
        }
        served = profile.get("environment")
        if isinstance(served, Mapping) and isinstance(
            served.get("LOCALCLOUD_RELAY_METADATA_ADDR"), str
        ):
            environment["LOCALCLOUD_RELAY_METADATA_ADDR"] = served[
                "LOCALCLOUD_RELAY_METADATA_ADDR"
            ]
        # The gateway as seen from the runtime network; the relay never uses the host's ports.
        environment["LOCALCLOUD_RELAY_GATEWAY"] = f"http://{runtime.name}:{GATEWAY_PORT}"
        environment[capability_env] = capability
        image = runtime.image_id or runtime.actual_image or runtime.configured_image
        name = identity_relay_name(runtime.data_volume, project, account_key)
        labels = {
            MANAGED_LABEL: "true",
            _CHILD_MANAGED_LABEL: "true",
            VOLUME_NAME_LABEL: runtime.data_volume,
            CONFIG_HASH_LABEL: runtime.config_hash
            or runtime.labels.get(CONFIG_HASH_LABEL)
            or "identity-session",
            "localcloud.service": "identity-session",
            "localcloud.relay": "true",
            "localcloud.relay.binding": session_id,
            IDENTITY_SESSION_LABEL: session_id,
            IDENTITY_PROJECT_LABEL: project,
            IDENTITY_ACCOUNT_LABEL: account_key,
        }
        attempts = [preferred_host_port, None] if preferred_host_port else [None]
        last_error: Exception | None = None
        for host_port in attempts:
            self._remove_named_identity_relay(name)
            try:
                container = self.client.containers.run(
                    image,
                    command=list(command[1:]),
                    entrypoint=[command[0]],
                    name=name,
                    detach=True,
                    labels=labels,
                    environment=environment,
                    network=runtime.network_name,
                    ports={
                        f"{metadata_port}/tcp": ("127.0.0.1", host_port),
                    },
                    mem_limit=_IDENTITY_RELAY_MEMORY,
                    cap_drop=["ALL"],
                    security_opt=["no-new-privileges"],
                    read_only=True,
                    restart_policy={"Name": "unless-stopped"},
                )
            except Exception as error:
                last_error = error
                self._remove_named_identity_relay(name)
                if host_port is not None and _is_port_conflict(error):
                    continue
                break
            published = self._identity_relay_port(container, metadata_port)
            if published is None:
                self._remove_named_identity_relay(name)
                raise HostError(
                    "identity_relay_failed",
                    "The identity relay started without a published metadata port",
                    {"container": name},
                )
            return {
                "container": name,
                "container_id": getattr(container, "id", None),
                "host_port": published,
                "image": image,
                "network": runtime.network_name,
            }
        raise HostError(
            "identity_relay_failed",
            "Could not start the LocalCloud identity relay",
            {"container": name, "image": image, "cause": str(last_error)},
        ) from last_error

    def identity_relay_state(self, container_name: str) -> tuple[str, str]:
        """The relay's Docker state and its recent log lines (which never hold the capability)."""
        container = self._get_optional(self.client.containers, container_name, "container")
        if container is None:
            return "removed", ""
        try:
            container.reload()
        except Exception:
            pass
        return _container_state(container), _container_logs(container, tail=20)

    def identity_relay_host_port(self, container: Any) -> int | None:
        return self._identity_relay_port(container, IDENTITY_RELAY_METADATA_PORT)

    def remove_identity_relay(self, container: Any) -> None:
        """Remove one relay after checking it is an identity relay of a LocalCloud volume."""
        labels = _resource_labels(container)
        if (
            labels.get(MANAGED_LABEL) != "true"
            or labels.get(_CHILD_MANAGED_LABEL) != "true"
            or not labels.get(IDENTITY_SESSION_LABEL)
            or not labels.get(VOLUME_NAME_LABEL)
        ):
            raise HostError(
                "ownership_mismatch",
                "Refusing to remove a container that is not a LocalCloud identity relay",
                {"container": _resource_identity(container), "labels": labels},
            )
        try:
            container.remove(force=True, v=True)
        except Exception as error:
            if not _is_not_found(error):
                raise HostError(
                    "identity_relay_remove_failed",
                    "Could not remove the LocalCloud identity relay",
                    {"container": _resource_identity(container), "cause": str(error)},
                ) from error

    def _remove_named_identity_relay(self, name: str) -> None:
        existing = self._get_optional(self.client.containers, name, "container")
        if existing is not None:
            self.remove_identity_relay(existing)

    @staticmethod
    def _identity_relay_port(container: Any, metadata_port: int) -> int | None:
        reload_container = getattr(container, "reload", None)
        if callable(reload_container):
            try:
                reload_container()
            except Exception:
                pass
        for host_ip, host_port in _published_ports(container).get(
            f"{metadata_port}/tcp", ()
        ):
            if host_port is not None:
                return host_port
        return None


def identity_relay_name(data_volume: str, project: str, account_key: str) -> str:
    """Stable container name of one data volume, project and account's identity relay."""
    digest = hashlib.sha256(
        f"{data_volume}\0{project}\0{account_key}".encode("utf-8")
    ).hexdigest()
    return f"lc-identity-{digest[:12]}"


def _published_ports(container: Any) -> PublishedPorts:
    attrs = getattr(container, "attrs", {})
    network_settings = attrs.get("NetworkSettings", {})
    host_config = attrs.get("HostConfig", {})
    live = network_settings.get("Ports", {})
    configured = host_config.get("PortBindings", {})
    live = live if isinstance(live, Mapping) else {}
    configured = configured if isinstance(configured, Mapping) else {}
    published: PublishedPorts = {}
    for container_port in sorted(set(live) | set(configured)):
        raw_bindings = live.get(container_port) or configured.get(container_port)
        if isinstance(raw_bindings, Mapping):
            raw_bindings = [raw_bindings]
        if not isinstance(raw_bindings, (tuple, list)):
            continue
        bindings: list[tuple[str, int | None]] = []
        for raw in raw_bindings:
            if not isinstance(raw, Mapping):
                continue
            host_ip = str(raw.get("HostIp") or "0.0.0.0")
            host_port_value = raw.get("HostPort")
            try:
                host_port = (
                    int(host_port_value)
                    if host_port_value not in (None, "")
                    else None
                )
            except (TypeError, ValueError):
                continue
            bindings.append((host_ip, host_port))
        if bindings:
            published[str(container_port)] = tuple(bindings)
    return published


def _endpoint_map(
    published_ports: Mapping[str, tuple[PortBinding, ...]]
) -> dict[str, int]:
    selected: dict[str, tuple[int, int, int]] = {}
    for container_port, bindings in sorted(published_ports.items()):
        port, _, protocol = container_port.partition("/")
        try:
            numeric_port = int(port)
        except ValueError:
            continue
        protocol_priority = 0 if (protocol or "tcp") == "tcp" else 1
        candidates = [
            (
                0
                if host_port == numeric_port
                else 2
                if host_port in _TRANSPARENT_HOST_PORTS
                else 3
                if host_port is None
                else 1,
                host_port if host_port is not None else numeric_port,
            )
            for _host_ip, host_port in bindings
        ]
        if not candidates:
            continue
        binding_priority, host_port = min(candidates)
        candidate = (protocol_priority, binding_priority, host_port)
        previous = selected.get(port)
        if previous is None or candidate < previous:
            selected[port] = candidate
    return {port: value[2] for port, value in selected.items()}


def _format_resource_create(
    kind: str,
    name: str,
    labels: Mapping[str, str],
) -> str:
    args = ["docker", kind, "create"]
    if kind == "network":
        args.extend(["--driver", "bridge"])
    for key, value in sorted(labels.items()):
        args.extend(["--label", f"{key}={value}"])
    args.append(name)
    return shlex.join(args)


def _published_host_ports(
    runtime: RuntimeRecord | None,
) -> set[tuple[int, str]]:
    if runtime is None:
        return set()
    allowed: set[tuple[int, str]] = set()
    for container_port, bindings in runtime.published_ports.items():
        _port, _, protocol = container_port.partition("/")
        for _host_ip, host_port in bindings:
            if host_port is not None:
                allowed.add((host_port, protocol or "tcp"))
    if not allowed:
        allowed.update((host_port, "tcp") for host_port in runtime.endpoint_map.values())
    return allowed






def _base_labels(data_volume: str, role: str) -> dict[str, str]:
    return {
        MANAGED_LABEL: "true",
        RESOURCE_ROLE_LABEL: role,
        VOLUME_NAME_LABEL: validate_data_volume(data_volume),
    }


def _removal_base_labels(
    resource: Any, role: str, data_volume: str
) -> dict[str, str]:
    labels = _resource_labels(resource)
    if (
        role == "volume"
        and VOLUME_NAME_LABEL not in labels
        and labels.get(INSTANCE_LABEL)
    ):
        return {
            MANAGED_LABEL: "true",
            RESOURCE_ROLE_LABEL: "volume",
            INSTANCE_LABEL: labels[INSTANCE_LABEL],
        }
    if role == "network":
        return {
            MANAGED_LABEL: "true",
            RESOURCE_ROLE_LABEL: "network",
        }
    return _base_labels(data_volume, role)


def _config_labels(config: LocalCloudConfig) -> dict[str, str]:
    return {
        CONFIG_HASH_LABEL: config.config_hash,
        CONFIG_PATH_LABEL: str(config.config_path)
        if config.config_path is not None
        else DEFAULTS_CONFIG_LABEL,
        CONFIG_LABEL: json.dumps(
            runtime_settings(config),
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
        ),
        NETWORK_NAME_LABEL: config.network_name,
        VOLUME_NAME_LABEL: config.data_volume,
        DATA_LABEL: config.data,
    }


def _network_labels(config: LocalCloudConfig) -> dict[str, str]:
    """Ownership labels for the managed network.

    Deliberately excludes the container-level config/config-hash labels:
    `docker network create` never varies with unrelated config fields
    (image, memory, environment, ...), so tagging the network with the
    whole-config hash would make any unrelated config change look like a
    network change and force a needless rm+recreate.
    """
    return {
        **_base_labels(config.data_volume, "network"),
        NETWORK_NAME_LABEL: config.network_name,
    }


def _record_container_labels(runtime: RuntimeRecord) -> dict[str, str]:
    labels = runtime.labels
    expected = _base_labels(runtime.data_volume, "container")
    for label in _CONTAINER_METADATA_LABELS:
        if label not in labels:
            raise HostError(
                "ownership_mismatch",
                "Container record is missing required ownership metadata",
                {"data_volume": runtime.data_volume, "label": label},
            )
        expected[label] = labels[label]
    return expected


def _container_environment(
    config: LocalCloudConfig,
    network_name: str,
    *,
    mysql_port: int | None = None,
) -> dict[str, str]:
    environment = dict(config.environment)
    environment.pop("LOCALCLOUD_CONFIG", None)
    if config.services is not None:
        environment["LOCALCLOUD_SERVICES"] = ",".join(config.services)
    else:
        environment.pop("LOCALCLOUD_SERVICES", None)
    if config.data_volume != DEFAULT_DATA_VOLUME or network_name != "localcloud":
        environment["LOCALCLOUD_RUNTIME_NETWORK"] = network_name
        environment["LOCALCLOUD_DATA_VOLUME"] = config.data_volume
    environment["LOCALCLOUD_DOCKER_ACCESS"] = config.docker_socket_mode
    environment.pop("LOCALCLOUD_INSTANCE", None)
    if config.mysql_port is not None:
        # One companion per data volume. Older images name every runtime's
        # companion `localcloud-mysql`, letting one server replace another's.
        if config.data_volume != DEFAULT_DATA_VOLUME:
            environment.setdefault(
                "LOCALCLOUD_MYSQL_CONTAINER_NAME", _mysql_companion_name(config)
            )
        if mysql_port is not None and mysql_port != _MYSQL_DEFAULT_PORT:
            environment.setdefault("LOCALCLOUD_MYSQL_PORT", str(mysql_port))
    return environment


def _mysql_port_pinned(config: LocalCloudConfig) -> bool:
    return "LOCALCLOUD_MYSQL_PORT" in config.environment


def _mysql_companion_name(config: LocalCloudConfig) -> str:
    """The server's own per-volume name (MySqlServerManager.containerName)."""
    configured = config.environment.get("LOCALCLOUD_MYSQL_CONTAINER_NAME", "").strip()
    if configured:
        return configured
    return "localcloud-mysql-" + re.sub(r"[^a-zA-Z0-9_.-]", "-", config.data_volume)

def _format_memory_limit(value: Any) -> str | None:
    if not isinstance(value, (int, float)) or value <= 0:
        return None
    size = int(value)
    for suffix, unit in (
        ("g", 1024**3),
        ("m", 1024**2),
        ("k", 1024),
    ):
        if size % unit == 0:
            return f"{size // unit}{suffix}"
    return str(size)



def _normalize_requested_bindings(value: Any) -> tuple[PortBinding, ...]:
    if (
        isinstance(value, (tuple, list))
        and len(value) == 2
        and isinstance(value[0], str)
    ):
        values = (value,)
    elif isinstance(value, (tuple, list)):
        values = value
    else:
        raise TypeError("Docker port bindings must be a binding or binding sequence")
    normalized: list[PortBinding] = []
    for binding in values:
        if not isinstance(binding, (tuple, list)) or len(binding) != 2:
            raise TypeError("Docker port bindings must contain (host, port) pairs")
        host_ip, host_port = binding
        if not isinstance(host_ip, str) or (
            host_port is not None and not isinstance(host_port, int)
        ):
            raise TypeError("Docker port bindings must contain string hosts and integer ports")
        normalized.append((host_ip, host_port))
    if not normalized:
        raise TypeError("Docker port bindings cannot be empty")
    return tuple(normalized)


def _emit_warning(observer: Any | None, message: str) -> None:
    if observer is not None and hasattr(observer, "warning"):
        observer.warning(message)
        return
    warnings.warn(message, RuntimeWarning, stacklevel=2)


def _format_port_args(ports: Mapping[str, Any]) -> list[str]:
    """Render `-p` flags, collapsing contiguous 1:1 port runs into Docker's
    `host_start-host_end:container_start-container_end` range syntax so the
    debug-printed command stays short. Purely cosmetic: the real container is
    still created via one port-binding entry per port through the Docker SDK.
    """
    parsed: list[tuple[int, str, str, int | None]] = []
    literal: list[str] = []
    for container_port, raw_bindings in ports.items():
        try:
            requested = _normalize_requested_bindings(raw_bindings)
        except TypeError:
            literal.append(str(raw_bindings))
            continue
        port_text, _, proto = container_port.partition("/")
        try:
            port_num = int(port_text)
        except ValueError:
            for host_ip, host_port in requested:
                host_prefix = (
                    f"[{host_ip}]:" if ":" in host_ip else f"{host_ip}:" if host_ip else ""
                )
                spec = (
                    f"{host_prefix}{host_port}:{container_port}"
                    if host_port is not None
                    else f"{host_prefix}:{container_port}"
                )
                literal.append(spec)
            continue
        for host_ip, host_port in requested:
            parsed.append((port_num, proto or "tcp", str(host_ip), host_port))
    parsed.sort(
        key=lambda item: (
            item[1],
            item[2],
            item[3] is None,
            item[3] - item[0] if item[3] is not None else 0,
            item[0],
        )
    )

    args: list[str] = []

    def flush(run: list[tuple[int, str, str, int | None]]) -> None:
        if not run:
            return
        _, proto, host_ip, _ = run[0]
        host_prefix = (
            f"[{host_ip}]:" if ":" in host_ip else f"{host_ip}:" if host_ip else ""
        )
        if len(run) == 1:
            port_num, _, _, host_port = run[0]
            spec = (
                f"{host_prefix}{host_port}:{port_num}"
                if host_port is not None
                else f"{host_prefix}:{port_num}"
            )
        else:
            start_port, start_host = run[0][0], run[0][3]
            end_port, end_host = run[-1][0], run[-1][3]
            spec = f"{host_prefix}{start_host}-{end_host}:{start_port}-{end_port}"
        args.extend(["-p", f"{spec}/{proto}"])

    run: list[tuple[int, str, str, int | None]] = []
    for item in parsed:
        port_num, proto, host_ip, host_port = item
        previous = run[-1] if run else None
        contiguous = (
            previous is not None
            and previous[1] == proto
            and previous[2] == host_ip
            and previous[3] is not None
            and host_port is not None
            and port_num == previous[0] + 1
            and host_port == previous[3] + 1
        )
        if contiguous:
            run.append(item)
        else:
            flush(run)
            run = [item]
    flush(run)
    for spec in literal:
        args.extend(["-p", spec])
    return args


def _format_docker_run(
    image: str,
    name: str,
    network_name: str | None,
    mem_limit: str | None,
    volumes: Mapping[str, Any] | None,
    ports: Mapping[str, Any] | None,
    environment: Mapping[str, str] | None,
    labels: Mapping[str, str] | None,
) -> str:
    args = ["docker", "run", "-d", "--name", name]
    if network_name:
        args.extend(["--network", network_name])
    if mem_limit:
        args.extend(["-m", str(mem_limit)])
    if volumes:
        for host_source, mount in sorted(volumes.items()):
            bind = mount.get("bind", "") if isinstance(mount, Mapping) else str(mount)
            mode = mount.get("mode", "rw") if isinstance(mount, Mapping) else "rw"
            spec = f"{host_source}:{bind}"
            if mode and mode != "rw":
                spec = f"{spec}:{mode}"
            args.extend(["-v", spec])
    if ports:
        args.extend(_format_port_args(ports))
    if environment:
        for k, v in sorted(environment.items()):
            args.extend(["-e", f"{k}={v}"])
    if labels:
        for k, v in sorted(labels.items()):
            args.extend(["-l", f"{k}={v}"])
    args.append(image)
    return shlex.join(args)




def _runtime_drift(
    actual: dict[str, Any] | None, config: LocalCloudConfig
) -> dict[str, dict[str, Any]]:
    configured = runtime_settings(config)
    if not isinstance(actual, dict):
        return {"configuration": {"actual": None, "configured": configured}}
    return {
        key: {"actual": actual.get(key), "configured": configured.get(key)}
        for key in sorted(set(actual) | set(configured))
        if actual.get(key) != configured.get(key)
    }


def _attached_drift(
    config: LocalCloudConfig,
    container: Any,
    network_name: str | None,
    metadata: dict[str, Any],
) -> dict[str, dict[str, Any]]:
    actual_environment = _container_environment_values(container)
    mounts = _container_mounts(container)
    actual_socket = any(
        str(mount.get("Destination") or mount.get("Target") or "")
        == "/var/run/docker.sock"
        for mount in mounts
    )
    actual_services = metadata["services"]
    configured_services = (
        "<default>" if config.services is None else ",".join(config.services)
    )
    candidates: dict[str, tuple[Any, Any]] = {
        "container_name": (_resource_name(container), config.container_name),
        "network_name": (network_name, config.network_name),
        "services": (actual_services, configured_services),
        "docker_socket": (actual_socket, config.docker_socket),
        "transparent_network": (
            actual_environment.get("LOCALCLOUD_ENABLE_LOCAL_PROXY", "false")
            == "true",
            config.transparent_network,
        ),
    }
    for key, value in config.environment.items():
        candidates[f"environment.{key}"] = (actual_environment.get(key), value)
    return {
        key: {"actual": actual, "configured": configured}
        for key, (actual, configured) in sorted(candidates.items())
        if actual != configured
    }


def _resource_labels(resource: Any, *, reload: bool = False) -> dict[str, str]:
    reload_resource = getattr(resource, "reload", None)
    if reload and callable(reload_resource):
        try:
            reload_resource()
        except Exception:
            pass
    labels = getattr(resource, "labels", None)
    if labels is None:
        attrs = getattr(resource, "attrs", None)
        labels = attrs.get("Labels") if isinstance(attrs, dict) else None
    return dict(labels or {})


def _label_mismatches(
    actual: dict[str, str], expected: dict[str, str]
) -> dict[str, dict[str, str | None]]:
    return {
        label: {"expected": value, "actual": actual.get(label)}
        for label, value in expected.items()
        if actual.get(label) != value
    }


def _remove_verified(
    resource: Any,
    kind: str,
    expected: dict[str, str],
    failures: list[dict[str, Any]],
    **kwargs: Any,
) -> None:
    identity = _resource_identity(resource)
    try:
        mismatches = _label_mismatches(_resource_labels(resource), expected)
        if mismatches:
            raise HostError(
                "ownership_mismatch",
                f"Refusing to remove a mismatched {kind}",
                {"resource": kind, "identity": identity, "label_mismatches": mismatches},
            )
        resource.remove(**kwargs)
    except Exception as error:
        failures.append({"resource": kind, "identity": identity, "cause": str(error)})


def _ownership_error(
    resource: Any,
    role: str,
    data_volume: str,
    labels: dict[str, str],
) -> HostError:
    return HostError(
        "ownership_mismatch",
        "LocalCloud ownership metadata is partial or contradictory",
        {
            "resource": role,
            "name": _resource_name(resource),
            "data_volume": data_volume,
            "labels": labels,
        },
    )


def _resource_name(resource: Any) -> str | None:
    if resource is None:
        return None
    value = getattr(resource, "name", None) or getattr(resource, "id", None)
    return str(value) if value else None


def _resource_identity(resource: Any) -> str:
    return str(getattr(resource, "id", None) or getattr(resource, "name", "unknown"))


def _container_state(container: Any) -> str:
    state = getattr(container, "attrs", {}).get("State", {})
    return str(state.get("Status") or getattr(container, "status", "unknown"))


def _container_health(container: Any) -> str | None:
    health = getattr(container, "attrs", {}).get("State", {}).get("Health") or {}
    value = health.get("Status")
    return str(value) if value else None


def _container_mounts(container: Any) -> list[dict[str, Any]]:
    mounts = getattr(container, "attrs", {}).get("Mounts") or []
    return [dict(mount) for mount in mounts if isinstance(mount, dict)]


def _mount_volume_name(mount: dict[str, Any]) -> str | None:
    if str(mount.get("Type") or "") != "volume":
        return None
    value = mount.get("Name")
    return str(value) if value else None


def _container_environment_values(container: Any) -> dict[str, str]:
    values = getattr(container, "attrs", {}).get("Config", {}).get("Env") or []
    environment: dict[str, str] = {}
    for value in values:
        if isinstance(value, str) and "=" in value:
            key, item = value.split("=", 1)
            environment[key] = item
    return environment


def _timestamp_ns(value: str) -> int | None:
    """Docker RFC 3339 timestamp (nanosecond precision) as epoch nanoseconds."""
    match = _LOG_TIMESTAMP.match(value)
    if match is None:
        return None
    base, fraction, zone = match.groups()
    try:
        moment = datetime.fromisoformat(base + ("+00:00" if zone == "Z" else zone))
    except ValueError:
        return None
    seconds = int(moment.timestamp())
    if seconds <= 0:
        return None
    return seconds * 1_000_000_000 + int((fraction or "").ljust(9, "0"))


def _started_at_ns(container: Any) -> int | None:
    state = getattr(container, "attrs", {}).get("State") or {}
    started = state.get("StartedAt") if isinstance(state, Mapping) else None
    return _timestamp_ns(started) if isinstance(started, str) else None


def _replaced_child_services(
    runtime: RuntimeRecord, *, remove_volume: bool, remove_network: bool
) -> frozenset[str] | None:
    """Which children removing this runtime's container also removes.

    All of them (None) when their volume or network goes too. Otherwise only
    the MySQL companion, whose data stays on the volume and whose name and port
    must follow the replacement; Dataproc clusters keep running state that the
    server resumes from the same containers.
    """
    if (
        remove_volume and runtime.ownership["data_volume"] == "managed"
    ) or (
        remove_network
        and runtime.ownership["network"] == "managed"
        and runtime.network_name is not None
    ):
        return None
    return frozenset({_MYSQL_COMPANION_SERVICE})


def _logs_failed(
    config: LocalCloudConfig, runtime: RuntimeRecord, error: Exception
) -> HostError:
    return HostError(
        "logs_failed",
        "Could not read LocalCloud runtime logs",
        {
            "data_volume": config.data_volume,
            "container_id": str(runtime.container_id or ""),
            "cause": str(error),
        },
    )


def _container_logs(container: Any, *, tail: int = 200) -> str:
    if container is None:
        return ""
    try:
        output = container.logs(tail=tail, timestamps=True)
        return (
            output.decode("utf-8", errors="replace")
            if isinstance(output, bytes)
            else str(output)
        )
    except Exception as error:
        return f"<logs unavailable: {error}>"

def _image_id(image: Any) -> str | None:
    value = getattr(image, "id", None) or getattr(image, "attrs", {}).get("Id")
    return str(value) if value else None


def _image_labels(image: Any) -> dict[str, str]:
    labels = getattr(image, "attrs", {}).get("Config", {}).get("Labels") or {}
    return dict(labels) if isinstance(labels, dict) else {}


def _image_exposed_ports(image: Any) -> set[str]:
    exposed = getattr(image, "attrs", {}).get("Config", {}).get("ExposedPorts") or {}
    if not isinstance(exposed, dict):
        raise HostError(
            "invalid_image",
            "Selected LocalCloud image has malformed exposed-port metadata",
        )
    return set(exposed)


def _normalize_image_reference(value: str) -> str:
    reference = value.strip()
    if reference.startswith("sha256:"):
        return reference.lower()
    if "@" in reference:
        repository, digest = reference.split("@", 1)
        suffix = f"@{digest.lower()}"
    else:
        slash = reference.rfind("/")
        colon = reference.rfind(":")
        if colon > slash:
            repository, tag = reference[:colon], reference[colon + 1 :]
        else:
            repository, tag = reference, "latest"
        suffix = f":{tag}"
    parts = repository.split("/")
    first = parts[0].lower()
    if len(parts) == 1:
        repository = f"docker.io/library/{repository}"
    elif "." not in first and ":" not in first and first != "localhost":
        repository = f"docker.io/{repository}"
    elif first == "index.docker.io":
        repository = f"docker.io/{'/'.join(parts[1:])}"
    return f"{repository.lower()}{suffix}"


def _resolve_readiness_deadline(deadline: float | None) -> float:
    if deadline is not None:
        return deadline
    return time.monotonic() + _DEFAULT_READINESS_TIMEOUT


def _ordinary_tcp_ports(config: LocalCloudConfig) -> tuple[int, ...]:
    ports = list(_BASE_TCP_PORTS)
    if config.tls_enabled:
        ports.extend((config.tls_port, *_DEDICATED_TLS_PORTS))
    return tuple(sorted(set(ports)))


def _fallback_tcp_ports(config: LocalCloudConfig) -> tuple[int, ...]:
    """The canonical ports the enabled services use; the rest are unused or reserved."""
    if not config.service_ports:
        return _ordinary_tcp_ports(config)
    used = {int(GATEWAY_PORT), *config.service_ports}
    if config.tls_enabled:
        used.add(config.tls_port)
    return tuple(port for port in _ordinary_tcp_ports(config) if port in used)


def _fallback_block_size(config: LocalCloudConfig) -> int:
    movable_mysql = config.mysql_port is not None and not _mysql_port_pinned(config)
    return len(_fallback_tcp_ports(config)) + int(movable_mysql)


def _within_configured_range(config: LocalCloudConfig, runtime: RuntimeRecord) -> bool:
    """Whether a runtime already publishes its service ports inside port_range."""
    assert config.port_range is not None
    start, end = config.port_range
    expected = {f"{port}/tcp" for port in _fallback_tcp_ports(config)}
    if config.transparent_network:
        expected.add(f"{_DNS_PORT}/udp")
    if set(runtime.published_ports) != expected:
        return False
    host_ports = [
        host_port
        for port_spec, bindings in runtime.published_ports.items()
        if port_spec.endswith("/tcp")
        for _host_ip, host_port in bindings
        if host_port is not None and host_port not in _TRANSPARENT_HOST_PORTS
    ]
    if not host_ports or not all(start <= port <= end for port in host_ports):
        return False
    if config.mysql_port is None or _mysql_port_pinned(config):
        return True
    return runtime.mysql_port is not None and start <= runtime.mysql_port <= end


def _configured_port_ranges(config: LocalCloudConfig) -> tuple[range, ...]:
    if config.port_range is None:
        return _FALLBACK_TCP_PORT_RANGES
    start, end = config.port_range
    return (range(start, end + 1),)


def _canonical_port_bindings(config: LocalCloudConfig) -> PublishedPorts:
    host_ip = "127.0.0.1" if config.local_only else ""
    bindings: PublishedPorts = {
        f"{port}/tcp": ((host_ip, port),)
        for port in _ordinary_tcp_ports(config)
    }
    if not config.transparent_network:
        return bindings
    gateway_key = f"{GATEWAY_PORT}/tcp"
    tls_key = f"{config.tls_port}/tcp"
    bindings[gateway_key] = (*bindings[gateway_key], (host_ip, 80))
    bindings[tls_key] = (*bindings[tls_key], (host_ip, 443))
    bindings[f"{_DNS_PORT}/udp"] = ((host_ip, 53),)
    return bindings


def _context_docker_socket_path() -> str | None:
    current_context = os.environ.get("DOCKER_CONTEXT")
    docker_config = os.path.expanduser("~/.docker/config.json")
    if not current_context and os.path.exists(docker_config):
        try:
            with open(docker_config, "r", encoding="utf-8") as file:
                current_context = json.load(file).get("currentContext")
        except Exception:
            current_context = None

    if not current_context or current_context == "default":
        return None

    contexts_dir = os.path.expanduser("~/.docker/contexts/meta")
    if not os.path.isdir(contexts_dir):
        return None

    for entry in os.scandir(contexts_dir):
        if not entry.is_dir():
            continue
        meta_path = os.path.join(entry.path, "meta.json")
        if not os.path.exists(meta_path):
            continue
        try:
            with open(meta_path, "r", encoding="utf-8") as file:
                meta = json.load(file)
                if meta.get("Name") == current_context:
                    endpoint = (
                        meta.get("Endpoints", {})
                        .get("docker", {})
                        .get("Host", "")
                    )
                    if endpoint.startswith("unix://"):
                        return endpoint.removeprefix("unix://")
        except Exception:
            continue
    return None


def _docker_socket_is_usable(client: Any | None = None) -> bool:
    try:
        if stat.S_ISSOCK(os.stat(_DOCKER_SOCKET_PATH).st_mode):
            return True
    except OSError:
        pass

    if client is not None:
        adapter = getattr(getattr(client, "api", None), "_custom_adapter", None)
        socket_path = getattr(adapter, "socket_path", None)
        if socket_path:
            try:
                if stat.S_ISSOCK(os.stat(socket_path).st_mode):
                    return True
            except OSError:
                pass

    docker_host = os.environ.get("DOCKER_HOST", "").strip()
    if docker_host.startswith("unix://"):
        host_socket = docker_host.removeprefix("unix://")
        try:
            if stat.S_ISSOCK(os.stat(host_socket).st_mode):
                return True
        except OSError:
            pass

    context_socket = _context_docker_socket_path()
    if context_socket:
        try:
            if stat.S_ISSOCK(os.stat(context_socket).st_mode):
                return True
        except OSError:
            pass

    home = os.path.expanduser("~")
    alternate_paths = [
        os.path.join(home, ".colima", "default", "docker.sock"),
        os.path.join(home, ".orbstack", "run", "docker.sock"),
        os.path.join(home, ".docker", "run", "docker.sock"),
        os.path.join(home, ".lima", "default", "docker.sock"),
    ]
    xdg_runtime = os.environ.get("XDG_RUNTIME_DIR")
    if xdg_runtime:
        alternate_paths.append(os.path.join(xdg_runtime, "docker.sock"))

    for path in alternate_paths:
        try:
            if stat.S_ISSOCK(os.stat(path).st_mode):
                return True
        except OSError:
            continue

    return False


def _available_tcp_port_block(
    count: int,
    allowed_ports: set[tuple[int, str]],
    host_ip: str,
    ranges: tuple[range, ...] = _FALLBACK_TCP_PORT_RANGES,
) -> tuple[int, ...]:
    for allowed_range in ranges:
        last_start = allowed_range.stop - count
        for start in range(allowed_range.start, last_start + 1):
            candidates = tuple(range(start, start + count))
            if all(
                (port, "tcp") in allowed_ports
                or _port_is_free(port, socket.SOCK_STREAM, host_ip)
                for port in candidates
            ):
                return candidates
    searched = ", ".join(
        f"{allowed_range.start}-{allowed_range.stop - 1}" for allowed_range in ranges
    )
    raise HostError(
        "alternative_ports_unavailable",
        "No complete alternative LocalCloud host-port set is available: "
        f"{count} free ports needed in {searched}",
        {
            "ranges": [
                [allowed_range.start, allowed_range.stop - 1]
                for allowed_range in ranges
            ],
            "required_ports": count,
        },
    )


def _port_spec_sort_key(spec: str) -> tuple[int, str]:
    port_text, _, protocol = spec.partition("/")
    try:
        port = int(port_text)
    except ValueError:
        port = 65536
    return port, protocol or "tcp"


def _port_is_free(
    port: int, kind: int = socket.SOCK_STREAM, host_ip: str = "127.0.0.1"
) -> bool:
    with socket.socket(socket.AF_INET, kind) as probe:
        try:
            probe.bind((host_ip or "0.0.0.0", port))
            return True
        except OSError:
            return False


def _validate_base_url(url: str) -> str:
    if not isinstance(url, str):
        raise HostError("invalid_endpoint", "LocalCloud URL must be a string")
    candidate = url.strip()
    try:
        parsed = urlparse(candidate)
        port = parsed.port
    except (TypeError, ValueError) as error:
        raise HostError(
            "invalid_endpoint", "LocalCloud URL is invalid", {"url": url}
        ) from error
    if (
        parsed.scheme not in {"http", "https"}
        or not parsed.netloc
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or any(character.isspace() for character in candidate)
    ):
        raise HostError(
            "invalid_endpoint",
            "LocalCloud URL must be a local HTTP endpoint without credentials, query, or fragment",
            {"url": url},
        )
    if port is not None and not 1 <= port <= 65535:
        raise HostError("invalid_endpoint", "LocalCloud URL has an invalid port")
    if not _is_loopback_host(parsed.hostname):
        raise HostError(
            "nonlocal_endpoint",
            "LocalCloud URL must be loopback",
            {"url": url},
        )
    return candidate.rstrip("/")


def _is_loopback_host(host: str) -> bool:
    if host.lower() == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def _is_not_found(error: Exception) -> bool:
    status_code = getattr(error, "status_code", None)
    if status_code is None:
        status_code = getattr(getattr(error, "response", None), "status_code", None)
    return status_code == 404 or error.__class__.__name__ in {
        "NotFound",
        "ImageNotFound",
    }


def _is_port_conflict(error: Exception) -> bool:
    message = str(error).lower()
    return any(
        phrase in message
        for phrase in (
            "port is already allocated",
            "address already in use",
            "bind for",
        )
    )
