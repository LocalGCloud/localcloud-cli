from __future__ import annotations

import ipaddress
import json
import os
import socket
import time
from typing import Any
from urllib.parse import urlparse

import httpx

from .config import DEFAULTS_CONFIG_LABEL, DEFAULT_INSTANCE, DEFAULT_PROJECT, LocalCloudConfig, default_resource_names, validate_instance
from .errors import HostError

MANAGED_LABEL = "com.localcloud.managed"
INSTANCE_LABEL = "com.localcloud.instance"
RESOURCE_ROLE_LABEL = "com.localcloud.resource-role"
CONFIG_HASH_LABEL = "com.localcloud.config-hash"
CONFIG_PATH_LABEL = "com.localcloud.config-path"
CONFIG_LABEL = "com.localcloud.config"
NETWORK_NAME_LABEL = "com.localcloud.network-name"
VOLUME_NAME_LABEL = "com.localcloud.volume-name"
SERVICES_LABEL = "com.localcloud.services"
DATA_LABEL = "com.localcloud.data"
GATEWAY_PORT = "24080"
_CHILD_MANAGED_LABEL = "localcloud.managed"
_LEGACY_LABELS = {
    "com.localcloud." + "work" + "space",
    "com.localcloud." + "work" + "space-key",
    "com.localcloud.controller",
    "com.localcloud.project",
}


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

    def inspect(self, instance: str) -> dict[str, Any] | None:
        instance = validate_instance(instance)
        container = self._discover_one(self.client.containers, instance, "container")
        if container is None:
            name = resource_names(instance)["container"]
            collision = self._get_optional(self.client.containers, name, "container")
            if collision is not None:
                _verify_labels(collision, _base_labels(instance, "container"), "container")
                raise HostError(
                    "ambiguous_instance",
                    "A container has the deterministic name but was not discoverable by its labels",
                    {"instance": instance, "container": name},
                )
            return None
        record = self._container_record(container, instance, _resource_labels(container))
        self._verify_associated(record)
        return record

    def create(self, config: LocalCloudConfig) -> dict[str, Any]:
        names = {"container": config.container_name, "network": config.network_name, "volume": config.volume_name}
        metadata = _config_labels(config)
        container_labels = {**metadata, **_base_labels(config.instance, "container")}
        container = network = volume = None
        network_created = volume_created = False
        try:
            existing = self._discover_one(self.client.containers, config.instance, "container")
            if existing is not None:
                raise HostError(
                    "ambiguous_instance",
                    "A managed container already claims this LocalCloud instance",
                    {"instance": config.instance, "container": _resource_name(existing)},
                )
            self._require_name_available(self.client.containers, names["container"], _base_labels(config.instance, "container"), "container")
            self._preflight_name(
                self.client.networks,
                config.instance,
                "network",
                names["network"],
            )
            self._preflight_name(
                self.client.volumes,
                config.instance,
                "volume",
                names["volume"],
                allow_legacy_default_volume=config.data == "persistent",
            )
            ports = self._port_bindings(config)
            network, network_created = self._network_for_create(config, names, metadata)
            volume, volume_created = self._volume_for_create(config, names)
            volumes: dict[str, dict[str, str]] = {volume.name: {"bind": "/var/lib/localcloud", "mode": "rw"}}
            if config.docker_socket:
                volumes["/var/run/docker.sock"] = {"bind": "/var/run/docker.sock", "mode": "rw"}
            container = self.client.containers.run(
                config.image,
                detach=True,
                name=names["container"],
                labels=container_labels,
                environment=_container_environment(config, network.name),
                mem_limit=config.memory,
                network=network.name,
                ports=ports,
                volumes=volumes,
            )
            container.reload()
            gateway = self._resolved_ports(container).get(GATEWAY_PORT)
            if gateway is None:
                raise HostError("container_start_failed", "LocalCloud did not publish its gateway port", {"container": names["container"]})
            self.wait_ready(f"http://127.0.0.1:{gateway}", container=container)
            record = self._container_record(container, config.instance, _resource_labels(container))
            record["volume_created"] = volume_created
            return record
        except Exception as error:
            failures = self._rollback_create(
                container,
                network if network_created else None,
                volume if volume_created else None,
                container_labels,
                _base_labels(config.instance, "network"),
                _base_labels(config.instance, "volume"),
            )
            if isinstance(error, HostError):
                if failures:
                    error.details["rollback_failures"] = failures
                raise
            raise HostError(
                "environment_create_failed",
                "Managed LocalCloud instance could not be created",
                {"instance": config.instance, "cause": str(error), "image": config.image, "rollback_failures": failures},
            ) from error

    def start(self, environment: dict[str, Any]) -> dict[str, Any]:
        container = self._owned_container(environment)
        try:
            container.start()
            container.reload()
            gateway = self._resolved_ports(container).get(GATEWAY_PORT)
            if gateway is None:
                raise HostError("container_start_failed", "LocalCloud did not publish its gateway port", {"container": environment["name"]})
            self.wait_ready(f"http://127.0.0.1:{gateway}", container=container)
            record = self._container_record(container, environment["instance"], _resource_labels(container))
            self._verify_associated(record)
            return record
        except HostError:
            raise
        except Exception as error:
            raise HostError(
                "container_start_failed",
                "LocalCloud instance container could not be started",
                {"container": environment["name"], "cause": str(error), "logs": _container_logs(container)},
            ) from error

    def stop(self, environment: dict[str, Any]) -> None:
        container = self._owned_container(environment)
        try:
            container.stop(timeout=20)
        except Exception as error:
            raise HostError("container_stop_failed", "LocalCloud instance container could not be stopped", {"container": environment["name"], "cause": str(error)}) from error

    def remove(self, environment: dict[str, Any], *, remove_volume: bool = True) -> None:
        failures: list[dict[str, Any]] = []
        expected = _record_container_labels(environment)
        container = self._optional_named(self.client.containers, environment["name"], expected, "container")
        discovered = self._discover_one(
            self.client.containers, environment["instance"], "container"
        )
        if (
            discovered is not None
            and (
                container is None
                or _resource_identity(discovered) != _resource_identity(container)
            )
        ):
            raise HostError(
                "ownership_mismatch",
                "The discovered instance container does not match the removal target",
                {
                    "instance": environment["instance"],
                    "expected": environment["name"],
                    "actual": _resource_name(discovered),
                },
            )
        self._remove_children(environment["instance"], container, failures)
        if failures:
            raise HostError(
                "cleanup_failed",
                "Managed child-container cleanup was incomplete",
                {"instance": environment["instance"], "failures": failures},
            )
        if container is not None:
            _remove_verified(container, "container", expected, failures, force=True, v=True)
        network = self._associated(self.client.networks, environment["instance"], "network", environment["network_name"], required=False)
        if network is not None:
            _remove_verified(network, "network", _base_labels(environment["instance"], "network"), failures)
        if remove_volume:
            volume = self._associated(
                self.client.volumes,
                environment["instance"],
                "volume",
                environment["volume_name"],
                required=False,
                allow_legacy_default_volume=environment["data"] == "persistent",
            )
            if volume is not None:
                _remove_verified(
                    volume,
                    "volume",
                    _base_labels(environment["instance"], "volume"),
                    failures,
                    legacy_default_volume=(
                        (environment["instance"], environment["volume_name"])
                        if environment["data"] == "persistent"
                        else None
                    ),
                    force=True,
                )
        if failures:
            raise HostError("cleanup_failed", "Managed instance cleanup was incomplete", {"instance": environment["instance"], "failures": failures})

    def purge(self, instance: str) -> None:
        instance = validate_instance(instance)
        failures: list[dict[str, Any]] = []
        container = self._discover_one(self.client.containers, instance, "container")
        self._remove_children(instance, container, failures)
        if failures:
            raise HostError(
                "cleanup_failed",
                "Managed child-container cleanup was incomplete",
                {"instance": instance, "failures": failures},
            )
        if container is not None:
            _remove_verified(container, "container", _base_labels(instance, "container"), failures, force=True, v=True)
        network = self._discover_one(self.client.networks, instance, "network")
        if network is not None:
            _remove_verified(network, "network", _base_labels(instance, "network"), failures)
        volume = self._discover_one(self.client.volumes, instance, "volume")
        volume_name = resource_names(instance)["volume"]
        legacy_default_volume = None
        if volume is None and instance == DEFAULT_INSTANCE:
            named = self._get_optional(self.client.volumes, volume_name, "volume")
            if named is not None and _is_legacy_default_volume(named, instance, volume_name):
                volume = named
                legacy_default_volume = (instance, volume_name)
        if volume is not None:
            _remove_verified(
                volume,
                "volume",
                _base_labels(instance, "volume"),
                failures,
                legacy_default_volume=legacy_default_volume,
                force=True,
            )
        if failures:
            raise HostError("cleanup_failed", "Managed instance cleanup was incomplete", {"instance": instance, "failures": failures})

    def logs(self, environment: dict[str, Any], tail: int = 200) -> str:
        if tail < 0:
            raise HostError("invalid_tail", "Log tail must be zero or greater")
        container = self._owned_container(environment)
        try:
            output = container.logs(tail=tail, timestamps=True)
        except Exception as error:
            raise HostError("logs_failed", "Could not read LocalCloud instance logs", {"container": environment["name"], "cause": str(error)}) from error
        return output.decode("utf-8", errors="replace") if isinstance(output, bytes) else str(output)

    def is_ready(self, environment: dict[str, Any]) -> bool:
        if environment.get("state") != "running" or not environment.get("url"):
            return False
        try:
            response = httpx.get(f"{environment['url']}/readiness", timeout=3.0)
            if response.status_code != 200:
                return False
            payload = response.json()
            return bool(payload.get("ready", payload.get("status") in {"ready", "ok"}))
        except Exception:
            return False

    def doctor(self) -> dict[str, Any]:
        legacy: list[dict[str, str]] = []
        for kind, collection in (("container", self.client.containers), ("network", self.client.networks), ("volume", self.client.volumes)):
            try:
                resources = collection.list(all=True) if kind == "container" else collection.list()
            except Exception as error:
                raise HostError("docker_inspect_failed", f"Could not inspect {kind} resources", {"resource": kind, "cause": str(error)}) from error
            for resource in resources:
                if any(label in _resource_labels(resource) for label in _LEGACY_LABELS):
                    legacy.append({"kind": kind, "name": _resource_name(resource) or "unknown"})
        try:
            version = self.client.version()
        except Exception:
            version = {}
        result: dict[str, Any] = {"status": "ok", "docker": version.get("Version") or version.get("version") or "available", "legacy_resources": legacy}
        if legacy:
            result["warning"] = "Legacy path-derived Docker resources are not migrated or removed automatically; clean them up manually after confirming they are unused."
        return result

    @staticmethod
    def wait_ready(url: str, timeout: float = 120.0, container: Any | None = None) -> dict[str, Any]:
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
                    raise HostError("container_start_failed", "LocalCloud instance container exited before becoming ready", {"container": _resource_name(container), "state": state, "logs": _container_logs(container)})
            try:
                response = httpx.get(f"{normalized}/readiness", timeout=3.0)
                if response.status_code == 200:
                    payload = response.json()
                    if payload.get("ready", payload.get("status") in {"ready", "ok"}):
                        return payload
                    last_error = f"readiness returned {payload}"
                else:
                    last_error = f"HTTP {response.status_code}"
            except Exception as error:
                last_error = str(error)
            time.sleep(1.0)
        raise HostError("readiness_timeout", "LocalCloud did not become ready", {"url": normalized, "timeout_seconds": timeout, "last_error": last_error, "logs": _container_logs(container) if container is not None else ""})

    def _port_bindings(self, config: LocalCloudConfig) -> dict[str, tuple[str, int | None]]:
        exposed = self._image_exposed_ports(config.image)
        tcp_ports = sorted(int(value.split("/", 1)[0]) for value in exposed if value.endswith("/tcp"))
        if int(GATEWAY_PORT) not in tcp_ports:
            raise HostError("invalid_image", "Selected LocalCloud image does not expose 24080/tcp", {"image": config.image, "exposed_ports": sorted(exposed)})
        canonical_free = all(_port_is_free(port) for port in tcp_ports)
        bindings: dict[str, tuple[str, int | None]] = {f"{port}/tcp": ("127.0.0.1", port if canonical_free else None) for port in tcp_ports}
        if config.transparent_network:
            for host_port, container_port, protocol in ((53, 24093, "udp"), (80, 24095, "tcp"), (443, 24094, "tcp")):
                kind = socket.SOCK_DGRAM if protocol == "udp" else socket.SOCK_STREAM
                if not _port_is_free(host_port, kind):
                    raise HostError("transparent_port_unavailable", "Transparent networking requires free host ports 53, 80, and 443", {"port": host_port, "protocol": protocol})
                bindings[f"{container_port}/{protocol}"] = ("127.0.0.1", host_port)
        return bindings

    def _image_exposed_ports(self, image_name: str) -> set[str]:
        try:
            image = self.client.images.get(image_name)
        except Exception as first_error:
            try:
                image = self.client.images.pull(image_name)
            except Exception as error:
                raise HostError("invalid_image", "Selected LocalCloud image could not be inspected", {"image": image_name, "cause": str(error), "local_cause": str(first_error)}) from error
        exposed = image.attrs.get("Config", {}).get("ExposedPorts") or {}
        if not isinstance(exposed, dict):
            raise HostError("invalid_image", "Selected LocalCloud image has malformed exposed-port metadata", {"image": image_name})
        return set(exposed)

    def _network_for_create(self, config: LocalCloudConfig, names: dict[str, str], metadata: dict[str, str]) -> tuple[Any, bool]:
        expected = _base_labels(config.instance, "network")
        existing = self._discover_one(self.client.networks, config.instance, "network")
        if existing is not None:
            if _resource_name(existing) != names["network"]:
                raise HostError("resource_name_mismatch", "The configured network name does not match the managed instance network", {"instance": config.instance, "configured": names["network"], "actual": _resource_name(existing)})
            desired = {**metadata, **expected}
            if not _label_mismatches(_resource_labels(existing), desired):
                return existing, False
            existing.remove()
        else:
            self._require_name_available(self.client.networks, names["network"], expected, "network")
        return self.client.networks.create(names["network"], driver="bridge", labels={**metadata, **expected}, check_duplicate=True), True

    def _volume_for_create(self, config: LocalCloudConfig, names: dict[str, str]) -> tuple[Any, bool]:
        expected = _base_labels(config.instance, "volume")
        existing = self._discover_one(self.client.volumes, config.instance, "volume")
        if existing is not None:
            if _resource_name(existing) != names["volume"]:
                raise HostError("resource_name_mismatch", "The configured volume name does not match the managed instance volume", {"instance": config.instance, "configured": names["volume"], "actual": _resource_name(existing)})
            if config.data == "persistent":
                return existing, False
            existing.remove(force=True)
        else:
            named = self._get_optional(self.client.volumes, names["volume"], "volume")
            if (
                named is not None
                and config.data == "persistent"
                and _is_legacy_default_volume(named, config.instance, names["volume"])
            ):
                return named, False
            self._require_name_available(self.client.volumes, names["volume"], expected, "volume")
        return self.client.volumes.create(name=names["volume"], labels=expected), True

    def _verify_associated(self, environment: dict[str, Any]) -> None:
        self._associated(self.client.networks, environment["instance"], "network", environment["network_name"], required=True)
        self._associated(
            self.client.volumes,
            environment["instance"],
            "volume",
            environment["volume_name"],
            required=True,
            allow_legacy_default_volume=environment["data"] == "persistent",
        )

    def _associated(
        self,
        collection: Any,
        instance: str,
        role: str,
        expected_name: str,
        *,
        required: bool,
        allow_legacy_default_volume: bool = False,
    ) -> Any | None:
        resource = self._discover_one(collection, instance, role)
        if resource is None:
            resource = self._get_optional(collection, expected_name, role)
        if resource is None:
            if required:
                raise HostError("resource_missing", f"Managed instance {role} could not be found", {"instance": instance, "resource": role, "name": expected_name})
            return None
        if _resource_name(resource) != expected_name:
            raise HostError("ownership_mismatch", f"Managed instance {role} name does not match its container record", {"instance": instance, "resource": role, "expected_name": expected_name, "actual_name": _resource_name(resource)})
        if not (
            role == "volume"
            and allow_legacy_default_volume
            and _is_legacy_default_volume(resource, instance, expected_name)
        ):
            _verify_labels(resource, _base_labels(instance, role), role)
        return resource

    def _discover_one(self, collection: Any, instance: str, role: str) -> Any | None:
        expected = _base_labels(instance, role)
        filters = {"label": [f"{key}={value}" for key, value in expected.items()]}
        is_container = role == "container"
        try:
            resources = collection.list(all=True, filters=filters) if is_container else collection.list(filters=filters)
        except Exception as error:
            raise HostError("docker_inspect_failed", f"Could not discover managed {role}", {"instance": instance, "resource": role, "cause": str(error)}) from error
        matches = [resource for resource in resources if not _label_mismatches(_resource_labels(resource), expected)]
        if len(matches) > 1:
            raise HostError("ambiguous_instance", f"Multiple managed {role} resources claim the same instance and role", {"instance": instance, "resource": role, "names": sorted(_resource_name(resource) or "unknown" for resource in matches)})
        return matches[0] if matches else None

    def _remove_children(self, instance: str, parent: Any | None, failures: list[dict[str, Any]]) -> None:
        try:
            children = self.client.containers.list(all=True, filters={"label": [f"{INSTANCE_LABEL}={instance}", f"{_CHILD_MANAGED_LABEL}=true"]})
        except Exception as error:
            failures.append({"resource": "child_containers", "identity": instance, "cause": str(error)})
            return
        parent_id = _resource_identity(parent) if parent is not None else None
        owned: list[tuple[Any, dict[str, str]]] = []
        for child in children:
            if parent_id == _resource_identity(child):
                continue
            labels = _resource_labels(child)
            if (
                labels.get(INSTANCE_LABEL) != instance
                or labels.get(_CHILD_MANAGED_LABEL) != "true"
            ):
                continue
            child_hash = labels.get(CONFIG_HASH_LABEL)
            expected = {
                MANAGED_LABEL: "true",
                INSTANCE_LABEL: instance,
                _CHILD_MANAGED_LABEL: "true",
            }
            if child_hash:
                expected[CONFIG_HASH_LABEL] = child_hash
            mismatches = _label_mismatches(labels, expected)
            if not child_hash:
                mismatches[CONFIG_HASH_LABEL] = {
                    "expected": "<non-empty>",
                    "actual": None,
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
            raise HostError("docker_inspect_failed", f"Could not inspect named {kind}", {"resource": kind, "name": name, "cause": str(error)}) from error

    def _require_name_available(self, collection: Any, name: str, expected: dict[str, str], kind: str) -> None:
        existing = self._get_optional(collection, name, kind)
        if existing is None:
            return
        _verify_labels(existing, expected, kind)
        raise HostError("ownership_mismatch", f"Configured {kind} name is already in use", {"resource": kind, "name": name})
    def _preflight_name(
        self,
        collection: Any,
        instance: str,
        role: str,
        configured_name: str,
        *,
        allow_legacy_default_volume: bool = False,
    ) -> None:
        expected = _base_labels(instance, role)
        discovered = self._discover_one(collection, instance, role)
        if discovered is not None and _resource_name(discovered) != configured_name:
            raise HostError(
                "resource_name_mismatch",
                f"The configured {role} name does not match the managed instance resource",
                {
                    "instance": instance,
                    "configured": configured_name,
                    "actual": _resource_name(discovered),
                },
            )
        named = self._get_optional(collection, configured_name, role)
        if named is not None and not (
            allow_legacy_default_volume
            and role == "volume"
            and _is_legacy_default_volume(named, instance, configured_name)
        ):
            _verify_labels(named, expected, role)


    def _optional_named(self, collection: Any, name: str, expected: dict[str, str], kind: str) -> Any | None:
        resource = self._get_optional(collection, name, kind)
        if resource is not None:
            _verify_labels(resource, expected, kind)
        return resource

    def _owned_container(self, environment: dict[str, Any]) -> Any:
        try:
            container = self.client.containers.get(environment["name"])
        except Exception as error:
            raise HostError("container_missing", "Managed instance container no longer exists or cannot be inspected", {"container": environment["name"], "cause": str(error)}) from error
        _verify_labels(container, _record_container_labels(environment), "container")
        return container

    def _container_record(self, container: Any, instance: str, labels: dict[str, str]) -> dict[str, Any]:
        required = (CONFIG_HASH_LABEL, CONFIG_PATH_LABEL, CONFIG_LABEL, NETWORK_NAME_LABEL, VOLUME_NAME_LABEL, SERVICES_LABEL, DATA_LABEL)
        missing = [label for label in required if label not in labels]
        if missing:
            raise HostError("ownership_mismatch", "Managed container is missing required instance metadata", {"instance": instance, "container": _resource_name(container), "missing_labels": missing})
        try:
            instance_config = json.loads(labels[CONFIG_LABEL])
        except (TypeError, json.JSONDecodeError) as error:
            raise HostError("ownership_mismatch", "Managed container has invalid instance configuration metadata", {"instance": instance, "container": _resource_name(container)}) from error
        if not isinstance(instance_config, dict):
            raise HostError("ownership_mismatch", "Managed container instance configuration metadata must be an object", {"instance": instance, "container": _resource_name(container)})
        _verify_labels(container, _base_labels(instance, "container"), "container")
        container.reload()
        endpoint_map = self._resolved_ports(container)
        gateway = endpoint_map.get(GATEWAY_PORT)
        return {
            "instance": instance,
            "name": _resource_name(container),
            "container_id": getattr(container, "id", None),
            "network_name": labels[NETWORK_NAME_LABEL],
            "volume_name": labels[VOLUME_NAME_LABEL],
            "state": _container_state(container),
            "url": f"http://127.0.0.1:{gateway}" if gateway else None,
            "endpoint_map": endpoint_map,
            "config_hash": labels[CONFIG_HASH_LABEL],
            "config_path": labels[CONFIG_PATH_LABEL],
            "instance_config": instance_config,
            "services": labels[SERVICES_LABEL],
            "data": labels[DATA_LABEL],
            "labels": labels,
        }

    @staticmethod
    def _resolved_ports(container: Any) -> dict[str, int]:
        resolved: dict[str, int] = {}
        for container_port, bindings in container.attrs.get("NetworkSettings", {}).get("Ports", {}).items():
            if bindings:
                resolved[container_port.split("/", 1)[0]] = int(bindings[0]["HostPort"])
        return resolved

    @staticmethod
    def _rollback_create(container: Any, network: Any, volume: Any, container_labels: dict[str, str], network_labels: dict[str, str], volume_labels: dict[str, str]) -> list[dict[str, Any]]:
        failures: list[dict[str, Any]] = []
        for kind, resource, labels, kwargs in (
            ("container", container, container_labels, {"force": True, "v": True}),
            ("network", network, network_labels, {}),
            ("volume", volume, volume_labels, {"force": True}),
        ):
            if resource is not None:
                _remove_verified(resource, kind, labels, failures, **kwargs)
        return failures


def resource_names(instance: str) -> dict[str, str]:
    return default_resource_names(instance)


def _base_labels(instance: str, role: str) -> dict[str, str]:
    return {MANAGED_LABEL: "true", INSTANCE_LABEL: validate_instance(instance), RESOURCE_ROLE_LABEL: role}


def _instance_config(config: LocalCloudConfig) -> dict[str, Any]:
    return {
        "instance": config.instance,
        "services": list(config.services) if config.services is not None else None,
        "data": config.data,
        "image": config.image,
        "memory": config.memory,
        "docker_socket": config.docker_socket,
        "transparent_network": config.transparent_network,
        "environment": dict(config.environment),
        "container_name": config.container_name,
        "network_name": config.network_name,
        "volume_name": config.volume_name,
    }


def _config_labels(config: LocalCloudConfig) -> dict[str, str]:
    return {
        CONFIG_HASH_LABEL: config.config_hash,
        CONFIG_PATH_LABEL: str(config.config_path) if config.config_path is not None else DEFAULTS_CONFIG_LABEL,
        CONFIG_LABEL: json.dumps(_instance_config(config), sort_keys=True, separators=(",", ":"), ensure_ascii=False),
        NETWORK_NAME_LABEL: config.network_name,
        VOLUME_NAME_LABEL: config.volume_name,
        SERVICES_LABEL: ",".join(config.services) if config.services is not None else "<default>",
        DATA_LABEL: config.data,
    }


def _record_container_labels(environment: dict[str, Any]) -> dict[str, str]:
    labels = environment.get("labels") or {}
    expected = _base_labels(environment["instance"], "container")
    for label in (CONFIG_HASH_LABEL, CONFIG_PATH_LABEL, CONFIG_LABEL, NETWORK_NAME_LABEL, VOLUME_NAME_LABEL, SERVICES_LABEL, DATA_LABEL):
        if label not in labels:
            raise HostError("ownership_mismatch", "Container record is missing required ownership metadata", {"instance": environment["instance"], "label": label})
        expected[label] = labels[label]
    return expected


def _container_environment(config: LocalCloudConfig, network_name: str) -> dict[str, str]:
    environment = dict(config.environment)
    environment["LOCALCLOUD_PROJECT"] = DEFAULT_PROJECT
    if config.services is None:
        environment.pop("LOCALCLOUD_SERVICES", None)
    else:
        environment["LOCALCLOUD_SERVICES"] = ",".join(config.services)
    environment.update({
        "LOCALCLOUD_DATA_DIR": "/var/lib/localcloud",
        "LOCALCLOUD_SEED_FILE": "/__localcloud_controller_seed_disabled__.yaml",
        "LOCALCLOUD_MCP_WRITE": "true",
        "LOCALCLOUD_MCP_DESTRUCTIVE": "true",
        "LOCALCLOUD_RUNTIME_NETWORK": network_name,
        "LOCALCLOUD_RUNTIME_EMBEDDED_DOCKER": "true" if config.docker_socket else "false",
        "LOCALCLOUD_INSTANCE": config.instance,
        "LOCALCLOUD_CONFIG_HASH": config.config_hash,
        "LOCALCLOUD_ENABLE_LOCAL_PROXY": "true" if config.transparent_network else "false",
        "LOCALCLOUD_MCP_ALLOW_REMOTE": "true",
    })
    return environment


def _verify_labels(resource: Any, expected: dict[str, str], kind: str) -> None:
    mismatches = _label_mismatches(_resource_labels(resource), expected)
    if mismatches:
        raise HostError("ownership_mismatch", f"Refusing to operate on an unlabeled, legacy, drifted, or mismatched {kind}", {"resource": kind, "name": _resource_name(resource), "label_mismatches": mismatches})


def _remove_verified(
    resource: Any,
    kind: str,
    expected: dict[str, str],
    failures: list[dict[str, Any]],
    *,
    legacy_default_volume: tuple[str, str] | None = None,
    **kwargs: Any,
) -> None:
    identity = _resource_identity(resource)
    try:
        if not (
            kind == "volume"
            and legacy_default_volume is not None
            and _is_legacy_default_volume(resource, *legacy_default_volume)
        ):
            _verify_labels(resource, expected, kind)
        resource.remove(**kwargs)
    except Exception as error:
        failures.append({"resource": kind, "identity": identity, "cause": str(error)})


def _resource_labels(resource: Any) -> dict[str, str]:
    reload_resource = getattr(resource, "reload", None)
    if callable(reload_resource):
        reload_resource()
    labels = getattr(resource, "labels", None)
    if labels is None:
        labels = getattr(resource, "attrs", {}).get("Labels")
    return dict(labels or {})


def _label_mismatches(actual: dict[str, str], expected: dict[str, str]) -> dict[str, dict[str, str | None]]:
    return {label: {"expected": value, "actual": actual.get(label)} for label, value in expected.items() if actual.get(label) != value}


def _resource_name(resource: Any) -> str | None:
    if resource is None:
        return None
    value = getattr(resource, "name", None) or getattr(resource, "id", None)
    return str(value) if value else None


def _is_legacy_default_volume(resource: Any, instance: str, configured_name: str) -> bool:
    return (
        instance == DEFAULT_INSTANCE
        and configured_name == "localcloud-data"
        and _resource_name(resource) == "localcloud-data"
        and not _resource_labels(resource)
    )


def _resource_identity(resource: Any) -> str:
    return str(getattr(resource, "id", None) or getattr(resource, "name", "unknown"))


def _container_state(container: Any) -> str:
    state = container.attrs.get("State", {}) if hasattr(container, "attrs") else {}
    return str(state.get("Status") or getattr(container, "status", "unknown"))


def _container_logs(container: Any) -> str:
    if container is None:
        return ""
    try:
        output = container.logs(tail=200, timestamps=True)
        return output.decode("utf-8", errors="replace") if isinstance(output, bytes) else str(output)
    except Exception as error:
        return f"<logs unavailable: {error}>"


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
        raise HostError("invalid_endpoint", "LocalCloud URL is invalid", {"url": url}) from error
    if parsed.scheme not in {"http", "https"} or not parsed.netloc or not parsed.hostname or parsed.username is not None or parsed.password is not None or parsed.query or parsed.fragment or any(character.isspace() for character in candidate):
        raise HostError("invalid_endpoint", "LocalCloud URL must be a local HTTP endpoint without credentials, query, or fragment", {"url": url})
    if port is not None and not 1 <= port <= 65535:
        raise HostError("invalid_endpoint", "LocalCloud URL has an invalid port")
    if not _is_loopback_host(parsed.hostname):
        raise HostError("nonlocal_endpoint", "LocalCloud URL must be loopback", {"url": url})
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
    return status_code == 404 or error.__class__.__name__ in {"NotFound", "ImageNotFound"}
