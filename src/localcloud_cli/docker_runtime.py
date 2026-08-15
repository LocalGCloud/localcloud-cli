from __future__ import annotations

import ipaddress
import json
import os
import socket
import time
from dataclasses import dataclass, replace
from typing import Any
from urllib.parse import urlparse

import httpx

from .config import (
    DEFAULTS_CONFIG_LABEL,
    DEFAULT_PROJECT,
    LocalCloudConfig,
    runtime_settings,
    validate_data_volume,
)
from .errors import HostError

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
DATA_MOUNT_DESTINATION = "/var/lib/localcloud"
GATEWAY_PORT = "24080"
_CHILD_MANAGED_LABEL = "localcloud.managed"
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
    SERVICES_LABEL,
    DATA_LABEL,
}
_CONTAINER_METADATA_LABELS = (
    CONFIG_HASH_LABEL,
    CONFIG_PATH_LABEL,
    CONFIG_LABEL,
    NETWORK_NAME_LABEL,
    VOLUME_NAME_LABEL,
    SERVICES_LABEL,
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
    endpoint_map: dict[str, int]
    network_name: str | None
    mount: dict[str, Any]
    configured_image: str
    actual_image: str | None
    image_id: str | None
    config_hash: str | None
    config_path: str | None
    runtime_settings: dict[str, Any] | None
    services: str
    data: str
    labels: dict[str, str]
    drift: dict[str, dict[str, Any]]
    volume_created: bool = False
    network_created: bool = False


class DockerRuntime:
    def __init__(self, client: Any | None = None):
        if client is not None:
            self.client = client
            return
        try:
            import docker

            try:
                self.client = docker.from_env(use_context=True)
            except TypeError:
                self.client = docker.from_env()
            self.client.ping()
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
        if container_ownership == "managed" and network is None:
            raise HostError(
                "resource_missing",
                "Managed LocalCloud network could not be found",
                {
                    "data_volume": data_volume,
                    "network": network_name,
                    "container_id": _resource_identity(container),
                },
            )
        network_ownership = "attached"
        if network is not None:
            network_ownership = self._classify_resource(network, "network", data_volume)
        volume_ownership = self._classify_resource(volume, "volume", data_volume, allow_legacy_volume=True)
        ownership = {
            "container": container_ownership,
            "network": network_ownership,
            "data_volume": volume_ownership,
        }
        origin = (
            "managed"
            if all(value == "managed" for value in ownership.values())
            else "attached"
        )
        endpoint_map = self._resolved_ports(container)
        gateway = endpoint_map.get(GATEWAY_PORT)
        state = _container_state(container)
        health = _container_health(container)
        runtime_config = metadata.get("runtime_config")
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
            endpoint_map=endpoint_map,
            network_name=network_name,
            mount=mount,
            configured_image=config.image,
            actual_image=image["declared"],
            image_id=image["container_id"],
            config_hash=metadata.get("config_hash"),
            config_path=metadata.get("config_path"),
            runtime_settings=runtime_config,
            services=metadata["services"],
            data=metadata["data"],
            labels=labels,
            drift=drift,
        )

    def preflight_create(
        self,
        config: LocalCloudConfig,
        replacing: RuntimeRecord | None = None,
    ) -> None:
        image = self._image_for_create(config.image)
        self._require_runtime_ownership_capability(config.image, image)
        allowed_transparent_ports: set[tuple[int, str]] = set()
        if replacing is not None:
            endpoint_map = replacing.endpoint_map
            for host_port, container_port, protocol in (
                (53, 24093, "udp"),
                (80, 24095, "tcp"),
                (443, 24094, "tcp"),
            ):
                if endpoint_map.get(str(container_port)) == host_port:
                    allowed_transparent_ports.add((host_port, protocol))
        self._port_bindings(
            config,
            image,
            allowed_transparent_ports=allowed_transparent_ports,
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
            ownership = self._classify_resource(network, "network", config.data_volume)
            if ownership != "managed":
                raise HostError(
                    "resource_name_in_use",
                    "Configured network name is already used by an attached resource",
                    {"network": config.network_name},
                )


    def create(self, config: LocalCloudConfig) -> RuntimeRecord:
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
        self.preflight_create(config)


        metadata = _config_labels(config)
        container_labels = {
            **metadata,
            **_base_labels(config.data_volume, "container"),
        }
        network_labels = {
            **metadata,
            **_base_labels(config.data_volume, "network"),
        }
        volume_labels = _base_labels(config.data_volume, "volume")
        image = self._image_for_create(config.image)
        self._require_runtime_ownership_capability(config.image, image)
        ports = self._port_bindings(config, image)

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
            container = self.client.containers.run(
                config.image,
                detach=True,
                name=config.container_name,
                labels=container_labels,
                environment=_container_environment(config, network.name),
                mem_limit=config.memory,
                network=network.name,
                ports=ports,
                volumes=volumes,
            )
            container.reload()
            record = self.resolve(
                config,
                preferred_container_id=_resource_identity(container),
                require=True,
            )
            if record is None:  # pragma: no cover - require=True is exhaustive.
                raise AssertionError("created runtime was not resolved")
            self._require_gateway(record)
            self.wait_ready(record.url, container=container)
            return replace(
                record,
                volume_created=volume_created,
                network_created=network_created,
            )
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
        self, config: LocalCloudConfig, runtime: RuntimeRecord
    ) -> RuntimeRecord:
        container, current = self._mutation_target(config, runtime)
        if current.state != "running":
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
        self._require_gateway(updated)
        self.wait_ready(updated.url, container=container)
        return updated

    def restart(
        self, config: LocalCloudConfig, runtime: RuntimeRecord
    ) -> RuntimeRecord:
        container, current = self._mutation_target(config, runtime)
        try:
            container.restart(timeout=20)
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
        self._require_gateway(updated)
        self.wait_ready(updated.url, container=container)
        return updated

    def stop(
        self, config: LocalCloudConfig, runtime: RuntimeRecord
    ) -> RuntimeRecord:
        container, current = self._mutation_target(config, runtime)
        if current.state == "running":
            try:
                container.stop(timeout=20)
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
        updated = self.resolve(
            config,
            preferred_container_id=current.container_id,
            require=True,
        )
        assert updated is not None
        return updated

    def remove(
        self,
        config: LocalCloudConfig,
        runtime: RuntimeRecord,
        *,
        remove_volume: bool = True,
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

        failures: list[dict[str, Any]] = []
        self._remove_children(config.data_volume, container, current, failures)
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
            current.ownership["network"] == "managed"
            and current.network_name is not None
        ):
            network = self._get_optional(
                self.client.networks, current.network_name, "network"
            )
            if network is not None:
                _remove_verified(
                    network,
                    "network",
                    _base_labels(config.data_volume, "network"),
                    failures,
                )
        if remove_volume and current.ownership["data_volume"] == "managed":
            volume = self._get_optional(
                self.client.volumes, config.data_volume, "volume"
            )
            if volume is not None:
                _remove_verified(
                    volume,
                    "volume",
                    _removal_base_labels(
                        volume, "volume", config.data_volume
                    ),
                    failures,
                    force=True,
                )
        if failures:
            raise HostError(
                "cleanup_failed",
                "Managed LocalCloud runtime cleanup was incomplete",
                {"data_volume": config.data_volume, "failures": failures},
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


    def purge(self, config: LocalCloudConfig) -> None:
        current = self.resolve(config)
        if current is not None:
            self.remove(config, current, remove_volume=True)
            return
        failures: list[dict[str, Any]] = []
        self._remove_children(config.data_volume, None, None, failures)
        if failures:
            raise HostError(
                "cleanup_failed",
                "Managed child-container cleanup was incomplete",
                {"data_volume": config.data_volume, "failures": failures},
            )
        network = self._get_optional(
            self.client.networks, config.network_name, "network"
        )
        if network is not None:
            ownership = self._classify_resource(network, "network", config.data_volume)
            if ownership == "managed":
                _remove_verified(
                    network,
                    "network",
                    _base_labels(config.data_volume, "network"),
                    failures,
                )
        volume = self._get_optional(
            self.client.volumes, config.data_volume, "volume"
        )
        if volume is not None:
            ownership = self._classify_resource(volume,
            "volume",
            config.data_volume,
            allow_legacy_volume=True,)
            if ownership == "managed":
                _remove_verified(
                    volume,
                    "volume",
                    _removal_base_labels(
                        volume, "volume", config.data_volume
                    ),
                    failures,
                    force=True,
                )
        if failures:
            raise HostError(
                "cleanup_failed",
                "Managed LocalCloud runtime cleanup was incomplete",
                {"data_volume": config.data_volume, "failures": failures},
            )

    def logs(
        self,
        config: LocalCloudConfig,
        runtime: RuntimeRecord,
        tail: int = 200,
    ) -> str:
        if tail < 0:
            raise HostError("invalid_tail", "Log tail must be zero or greater")
        container, current = self._mutation_target(config, runtime)
        try:
            output = container.logs(tail=tail, timestamps=True)
        except Exception as error:
            raise HostError(
                "logs_failed",
                "Could not read LocalCloud runtime logs",
                {
                    "data_volume": config.data_volume,
                    "container_id": current.container_id,
                    "cause": str(error),
                },
            ) from error
        return (
            output.decode("utf-8", errors="replace")
            if isinstance(output, bytes)
            else str(output)
        )

    def is_ready(self, runtime: RuntimeRecord) -> bool:
        if runtime.state != "running" or not runtime.url:
            return False
        try:
            response = httpx.get(f"{runtime.url}/health", timeout=3.0)
            if response.status_code != 200:
                return False
            payload = response.json()
            return payload.get("status") in {"healthy", "ok", "ready"}
        except Exception:
            return False

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
                        )
                    except HostError as error:
                        invalid_ownership.append(
                            {
                                "kind": kind,
                                "name": _resource_name(resource),
                                "error": error.to_dict(),
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
        result: dict[str, Any] = {
            "status": "ok",
            "docker": version.get("Version")
            or version.get("version")
            or "available",
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

    @staticmethod
    def wait_ready(
        url: str,
        timeout: float = 120.0,
        container: Any | None = None,
    ) -> dict[str, Any]:
        normalized = _validate_base_url(url)
        deadline = time.monotonic() + timeout
        last_error = "not attempted"
        while time.monotonic() < deadline:
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
            try:
                response = httpx.get(f"{normalized}/health", timeout=3.0)
                if response.status_code == 200:
                    payload = response.json()
                    if payload.get("status") in {"healthy", "ok", "ready"}:
                        return payload
                    last_error = f"health returned {payload}"
                else:
                    last_error = f"HTTP {response.status_code}"
            except Exception as error:
                last_error = str(error)
            time.sleep(1.0)
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
        configured_image = None
        configured_id = None
        labels: dict[str, str] = {}
        try:
            configured_image = self.client.images.get(config.image)
        except Exception as error:
            if not _is_not_found(error):
                raise HostError(
                    "docker_inspect_failed",
                    "Could not inspect the configured LocalCloud image",
                    {"image": config.image, "cause": str(error)},
                ) from error
        if configured_image is not None:
            configured_id = _image_id(configured_image)
        image_object = configured_image
        if container_id and configured_id != container_id:
            try:
                image_object = self.client.images.get(container_id)
            except Exception as error:
                if not _is_not_found(error):
                    raise HostError(
                        "docker_inspect_failed",
                        "Could not inspect the running LocalCloud image",
                        {"image_id": container_id, "cause": str(error)},
                    ) from error
                image_object = None
        if image_object is not None:
            labels = _image_labels(image_object)
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
            "labels": labels,
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
        if role == "network" and not labels.get(CONFIG_HASH_LABEL):
            raise HostError(
                "ownership_mismatch",
                "Managed network ownership metadata is incomplete",
                {
                    "data_volume": data_volume,
                    "network": _resource_name(resource),
                    "missing_labels": [CONFIG_HASH_LABEL],
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
        return {
            "config_hash": labels[CONFIG_HASH_LABEL],
            "config_path": labels[CONFIG_PATH_LABEL],
            "runtime_config": runtime_config,
            "services": labels[SERVICES_LABEL],
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

    def _image_for_create(self, image_name: str) -> Any:
        try:
            return self.client.images.get(image_name)
        except Exception as first_error:
            try:
                return self.client.images.pull(image_name)
            except Exception as error:
                raise HostError(
                    "invalid_image",
                    "Selected LocalCloud image could not be inspected",
                    {
                        "image": image_name,
                        "cause": str(error),
                        "local_cause": str(first_error),
                    },
                ) from error

    @staticmethod
    def _require_runtime_ownership_capability(image_name: str, image: Any) -> None:
        actual = _image_labels(image).get(RUNTIME_OWNERSHIP_LABEL)
        if actual != RUNTIME_OWNERSHIP_CAPABILITY:
            raise HostError(
                "managed_image_capability_missing",
                "Managed runtime creation requires a data-volume ownership capable LocalCloud image",
                {
                    "image": image_name,
                    "required_label": {
                        RUNTIME_OWNERSHIP_LABEL: RUNTIME_OWNERSHIP_CAPABILITY
                    },
                    "actual": actual,
                },
            )

    def _port_bindings(
        self,
        config: LocalCloudConfig,
        image: Any,
        *,
        allowed_transparent_ports: set[tuple[int, str]] | None = None,
    ) -> dict[str, tuple[str, int | None]]:
        exposed = _image_exposed_ports(image)
        tcp_ports = sorted(
            int(value.split("/", 1)[0])
            for value in exposed
            if value.endswith("/tcp")
        )
        if int(GATEWAY_PORT) not in tcp_ports:
            raise HostError(
                "invalid_image",
                "Selected LocalCloud image does not expose 24080/tcp",
                {"image": config.image, "exposed_ports": sorted(exposed)},
            )
        canonical_free = all(_port_is_free(port) for port in tcp_ports)
        bindings: dict[str, tuple[str, int | None]] = {
            f"{port}/tcp": ("127.0.0.1", port if canonical_free else None)
            for port in tcp_ports
        }
        if config.transparent_network:
            for host_port, container_port, protocol in (
                (53, 24093, "udp"),
                (80, 24095, "tcp"),
                (443, 24094, "tcp"),
            ):
                kind = socket.SOCK_DGRAM if protocol == "udp" else socket.SOCK_STREAM
                if (
                    not _port_is_free(host_port, kind)
                    and (host_port, protocol)
                    not in (allowed_transparent_ports or set())
                ):
                    raise HostError(
                        "transparent_port_unavailable",
                        "Transparent networking requires free host ports 53, 80, and 443",
                        {"port": host_port, "protocol": protocol},
                    )
                bindings[f"{container_port}/{protocol}"] = (
                    "127.0.0.1",
                    host_port,
                )
        return bindings

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
            if _resource_labels(existing).get(CONFIG_HASH_LABEL) == config.config_hash:
                return existing, False
            failures: list[dict[str, Any]] = []
            _remove_verified(
                existing,
                "network",
                _base_labels(config.data_volume, "network"),
                failures,
            )
            if failures:
                raise HostError(
                    "cleanup_failed",
                    "Managed network replacement failed",
                    {"data_volume": config.data_volume, "failures": failures},
                )
        return (
            self.client.networks.create(
                config.network_name,
                driver="bridge",
                labels=labels,
                check_duplicate=True,
            ),
            True,
        )

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
            container.reload()
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
                "LocalCloud runtime does not publish 24080/tcp",
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
    ) -> None:
        try:
            containers = self.client.containers.list(all=True)
        except Exception as error:
            failures.append(
                {
                    "resource": "child_containers",
                    "identity": data_volume,
                    "cause": str(error),
                }
            )
            return
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
            return
        for child, expected in owned:
            _remove_verified(
                child,
                "child_container",
                expected,
                failures,
                force=True,
                v=True,
            )

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
        resolved: dict[str, int] = {}
        for container_port, bindings in (
            getattr(container, "attrs", {})
            .get("NetworkSettings", {})
            .get("Ports", {})
            .items()
        ):
            if bindings:
                resolved[container_port.split("/", 1)[0]] = int(
                    bindings[0]["HostPort"]
                )
        return resolved

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
        SERVICES_LABEL: ",".join(config.services)
        if config.services is not None
        else "<default>",
        DATA_LABEL: config.data,
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
    config: LocalCloudConfig, network_name: str
) -> dict[str, str]:
    environment = dict(config.environment)
    environment["LOCALCLOUD_PROJECT"] = DEFAULT_PROJECT
    if config.services is None:
        environment.pop("LOCALCLOUD_SERVICES", None)
    else:
        environment["LOCALCLOUD_SERVICES"] = ",".join(config.services)
    environment.update(
        {
            "LOCALCLOUD_DATA_DIR": DATA_MOUNT_DESTINATION,
            "LOCALCLOUD_SEED_FILE": "/__localcloud_controller_seed_disabled__.yaml",
            "LOCALCLOUD_MCP_WRITE": "true",
            "LOCALCLOUD_MCP_DESTRUCTIVE": "true",
            "LOCALCLOUD_RUNTIME_NETWORK": network_name,
            "LOCALCLOUD_RUNTIME_EMBEDDED_DOCKER": "true"
            if config.docker_socket
            else "false",
            "LOCALCLOUD_DATA_VOLUME": config.data_volume,
            "LOCALCLOUD_CONFIG_HASH": config.config_hash,
            "LOCALCLOUD_ENABLE_LOCAL_PROXY": "true"
            if config.transparent_network
            else "false",
            "LOCALCLOUD_MCP_ALLOW_REMOTE": "true",
        }
    )
    environment.pop("LOCALCLOUD_INSTANCE", None)
    return environment


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


def _resource_labels(resource: Any, *, reload: bool = True) -> dict[str, str]:
    reload_resource = getattr(resource, "reload", None)
    if reload and callable(reload_resource):
        reload_resource()
    labels = getattr(resource, "labels", None)
    if labels is None:
        labels = getattr(resource, "attrs", {}).get("Labels")
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


def _container_logs(container: Any) -> str:
    if container is None:
        return ""
    try:
        output = container.logs(tail=200, timestamps=True)
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


def _port_is_free(port: int, kind: int = socket.SOCK_STREAM) -> bool:
    with socket.socket(socket.AF_INET, kind) as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            probe.bind(("127.0.0.1", port))
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
