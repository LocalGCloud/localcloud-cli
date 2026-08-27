from __future__ import annotations

import ipaddress
import json
import os
import socket
import shlex
import time
from dataclasses import dataclass, field, replace
from typing import Any, Mapping
from urllib.parse import urlparse

import httpx

from .config import (
    DEFAULTS_CONFIG_LABEL,
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
CONFIG_SCHEMA_LABEL = "com.localcloud.config-schema"
CONFIG_SCHEMA_CAPABILITY = "1"
DATA_MOUNT_DESTINATION = "/var/lib/localcloud"
CONFIG_MOUNT_DESTINATION = "/etc/localcloud/localcloud.yaml"
GATEWAY_PORT = "24080"
TLS_GATEWAY_PORT = "24443"
_DEFAULT_READINESS_TIMEOUT = 120.0
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
    volume_created: bool = False
    network_created: bool = False
    image_status: str = ""


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
        tls_gateway = endpoint_map.get(TLS_GATEWAY_PORT)
        tls_enabled = (
            _container_environment_values(container)
            .get("LOCALCLOUD_TLS_ENABLED", "")
            .strip()
            .lower()
            == "true"
        )
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
        )

    def preflight_create(
        self,
        config: LocalCloudConfig,
        replacing: RuntimeRecord | None = None,
        *,
        pull: bool = False,
        observer: Any | None = None,
    ) -> tuple[Any, bool]:
        image, was_pulled = self._image_for_create(
            config.image, pull=pull, observer=observer
        )
        self._require_runtime_ownership_capability(config, image)
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
        return image, was_pulled

    def create(
        self,
        config: LocalCloudConfig,
        *,
        pull: bool = False,
        readiness_deadline: float | None = None,
        observer: Any | None = None,
        prepared_image: tuple[Any, bool] | None = None,
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
        self._require_runtime_ownership_capability(config, image)
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
            if config.config_path is not None:
                volumes[str(config.config_path)] = {
                    "bind": CONFIG_MOUNT_DESTINATION,
                    "mode": "ro",
                }
            if observer is not None and hasattr(observer, "debug"):
                observer.debug(
                    _format_docker_run(
                        config.image,
                        config.container_name,
                        network.name if network else None,
                        config.memory,
                        volumes,
                        ports,
                        _container_environment(config, network.name if network else config.network_name),
                        container_labels,
                    )
                )
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
            return replace(
                ready,
                volume_created=volume_created,
                network_created=network_created,
                image_status="pulled from registry" if was_pulled else "available locally",
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
        if observer is not None and hasattr(observer, "debug"):
            observer.debug(_format_effective_run_command(config, current))
        if current.state != "running":
            if observer is not None and hasattr(observer, "debug"):
                target_name = _resource_name(container) or current.container_id
                observer.debug(f"docker start {target_name}")
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
        )
        ready = self.resolve(
            config,
            preferred_container_id=current.container_id,
            require=True,
        )
        assert ready is not None
        return replace(
            self._require_container_identity(
                config,
                current.container_id,
                ready,
            ),
            image_status="available locally",
        )

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
            observer.debug(_format_effective_run_command(config, current))
            target_name = _resource_name(container) or current.container_id
            observer.debug(f"docker restart -t 20 {target_name}")
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
        )
        ready = self.resolve(
            config,
            preferred_container_id=current.container_id,
            require=True,
        )
        assert ready is not None
        return replace(
            self._require_container_identity(
                config,
                current.container_id,
                ready,
            ),
            image_status="available locally",
        )

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
        out to be) managed by this LocalCloud instance. Shared by `remove()`
        (which already knows the ownership from a resolved RuntimeRecord) and
        `purge()`'s orphan-cleanup path (which has to classify on the fly)."""
        if network is None:
            return
        resolved_ownership = (
            ownership
            if ownership is not None
            else self._classify_resource(network, "network", config.data_volume)
        )
        if resolved_ownership != "managed":
            return
        _remove_verified(
            network,
            "network",
            _base_labels(config.data_volume, "network"),
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
        out to be) managed by this LocalCloud instance. See
        `_teardown_network_if_owned` for why this is shared."""
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
        self._teardown_network_if_owned(config, network, failures)
        volume = self._get_optional(
            self.client.volumes, config.data_volume, "volume"
        )
        self._teardown_volume_if_owned(config, volume, failures)
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
        *,
        since: float | None = None,
    ) -> str:
        if tail < 0:
            raise HostError("invalid_tail", "Log tail must be zero or greater")
        container, current = self._mutation_target(config, runtime)
        try:
            kwargs: dict[str, Any] = {"tail": tail, "timestamps": True}
            if since is not None:
                kwargs["since"] = since
            output = container.logs(**kwargs)
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

    def effective_services(self, runtime: RuntimeRecord) -> tuple[str, ...] | None:
        """Return the enabled service IDs LocalCloud reports, or None if unavailable.

        Queried live from the running server rather than predicted from the
        public YAML, so results reflect catalog/tier/availability resolution
        the CLI does not own.
        """
        if runtime.state != "running" or not runtime.url:
            return None
        try:
            response = httpx.get(f"{runtime.url}/services", timeout=3.0)
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

    def image_details(self, image_name: str) -> dict[str, Any]:
        try:
            image = self.client.images.get(image_name)
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
    ) -> dict[str, Any]:
        normalized = _validate_base_url(url)
        timeout = max(0.0, deadline - time.monotonic())
        last_error = "not attempted"

        def _emit_logs() -> None:
            if container is not None and observer is not None and hasattr(observer, "runtime_logs"):
                try:
                    logs = _container_logs(container, tail=12)
                    if logs and not logs.startswith("<logs unavailable"):
                        observer.runtime_logs(logs)
                except Exception:
                    pass

        _emit_logs()
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
                response = httpx.get(
                    f"{normalized}/health",
                    timeout=min(3.0, remaining),
                )
                if response.status_code == 200:
                    payload = response.json()
                    if payload.get("status") in {"healthy", "ok", "ready"}:
                        _emit_logs()
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

    def _image_for_create(
        self,
        image_name: str,
        *,
        pull: bool = False,
        observer: Any | None = None,
    ) -> tuple[Any, bool]:
        if pull:
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
        try:
            return self.client.images.get(image_name), False
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
    if config.config_path is not None:
        environment["LOCALCLOUD_CONFIG"] = CONFIG_MOUNT_DESTINATION
    else:
        environment.pop("LOCALCLOUD_CONFIG", None)
    if config.services is not None:
        environment["LOCALCLOUD_SERVICES"] = ",".join(config.services)
    else:
        environment.pop("LOCALCLOUD_SERVICES", None)
    environment.pop("LOCALCLOUD_INSTANCE", None)
    return environment


def _format_port_args(ports: Mapping[str, Any]) -> list[str]:
    """Render `-p` flags, collapsing contiguous 1:1 port runs into Docker's
    `host_start-host_end:container_start-container_end` range syntax so the
    debug-printed command stays short. Purely cosmetic: the real container is
    still created via one port-binding entry per port through the Docker SDK.
    """
    parsed: list[tuple[int, str, str, int | None]] = []
    literal: list[str] = []
    for container_port, binding in ports.items():
        if not isinstance(binding, (tuple, list)):
            literal.append(str(binding))
            continue
        host_ip, host_port = binding[0], binding[1]
        port_text, _, proto = container_port.partition("/")
        try:
            port_num = int(port_text)
        except ValueError:
            spec = (
                f"{host_ip}:{host_port}:{container_port}"
                if host_port is not None
                else f"{host_ip}::{container_port}"
            )
            literal.append(spec)
            continue
        parsed.append((port_num, proto or "tcp", str(host_ip), host_port))
    parsed.sort(key=lambda item: (item[1], item[2], item[0]))

    args: list[str] = []

    def flush(run: list[tuple[int, str, str, int | None]]) -> None:
        if not run:
            return
        _, proto, host_ip, _ = run[0]
        if len(run) == 1:
            port_num, _, _, host_port = run[0]
            spec = (
                f"{host_ip}:{host_port}:{port_num}"
                if host_port is not None
                else f"{host_ip}::{port_num}"
            )
        else:
            start_port, start_host = run[0][0], run[0][3]
            end_port, end_host = run[-1][0], run[-1][3]
            spec = f"{host_ip}:{start_host}-{end_host}:{start_port}-{end_port}"
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
            bind = mount.get("bind", "") if isinstance(mount, dict) else str(mount)
            mode = mount.get("mode", "rw") if isinstance(mount, dict) else "rw"
            args.extend(["-v", f"{host_source}:{bind}:{mode}"])
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


def _format_effective_run_command(
    config: LocalCloudConfig, current: "RuntimeRecord"
) -> str:
    """Render the docker-run equivalent of the container's current
    configuration for --debug, even when no `docker run`/`create` is actually
    happening (a plain start/restart of an already-conforming container).
    Ports come from the live, already-resolved endpoint map so this needs no
    extra Docker API calls; everything else is derived the same way `create`
    derives it, from `config`.
    """
    volumes: dict[str, dict[str, str]] = {
        config.data_volume: {"bind": DATA_MOUNT_DESTINATION, "mode": "rw"},
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
    ports = {
        f"{container_port}/tcp": ("127.0.0.1", host_port)
        for container_port, host_port in current.endpoint_map.items()
    }
    network_name = current.network_name or config.network_name
    container_labels = {
        **_config_labels(config),
        **_base_labels(config.data_volume, "container"),
    }
    return _format_docker_run(
        config.image,
        config.container_name,
        network_name,
        config.memory,
        volumes,
        ports,
        _container_environment(config, network_name),
        container_labels,
    )


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


def _port_is_free(port: int, kind: int = socket.SOCK_STREAM) -> bool:
    with socket.socket(socket.AF_INET, kind) as probe:
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
