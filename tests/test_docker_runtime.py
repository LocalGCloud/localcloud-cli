from __future__ import annotations

from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest

import localcloud_cli.docker_runtime as runtime_module
from localcloud_cli.config import DEFAULT_PROJECT, load_config
from localcloud_cli.docker_runtime import (
    CONFIG_HASH_LABEL,
    CONFIG_LABEL,
    CONFIG_PATH_LABEL,
    DATA_LABEL,
    INSTANCE_LABEL,
    MANAGED_LABEL,
    NETWORK_NAME_LABEL,
    RESOURCE_ROLE_LABEL,
    SERVICES_LABEL,
    VOLUME_NAME_LABEL,
    DockerRuntime,
    resource_names,
)
from localcloud_cli.errors import HostError


class NotFound(Exception):
    status_code = 404


class Resource:
    def __init__(
        self,
        name: str,
        labels: dict[str, str] | None = None,
        *,
        state: str = "running",
        ports: dict[str, Any] | None = None,
    ):
        self.name = name
        self.id = f"id-{name}"
        self.labels = dict(labels or {})
        self.status = state
        self.attrs = {
            "Labels": self.labels,
            "State": {"Status": state},
            "NetworkSettings": {"Ports": ports or {}},
        }
        self.removed: list[dict[str, Any]] = []
        self.started = 0
        self.stopped = 0

    def reload(self) -> None:
        return None

    def start(self) -> None:
        self.started += 1
        self.status = "running"
        self.attrs["State"]["Status"] = "running"

    def stop(self, timeout: int) -> None:
        assert timeout == 20
        self.stopped += 1
        self.status = "exited"
        self.attrs["State"]["Status"] = "exited"

    def remove(self, **kwargs: Any) -> None:
        self.removed.append(kwargs)

    def logs(self, **_kwargs: Any) -> bytes:
        return b"container log\n"


class Collection:
    def __init__(self):
        self.values: dict[str, Resource] = {}
        self.created: list[tuple[str, dict[str, Any]]] = []

    def get(self, name: str) -> Resource:
        if name not in self.values:
            raise NotFound(name)
        return self.values[name]

    def create(self, name: str | None = None, **kwargs: Any) -> Resource:
        selected = name or kwargs.pop("name")
        resource = Resource(selected, kwargs.get("labels"))
        self.values[selected] = resource
        self.created.append((selected, kwargs))
        return resource

    def list(self, **_kwargs: Any) -> list[Resource]:
        return list(self.values.values())


class ContainerCollection(Collection):
    def __init__(self):
        super().__init__()
        self.run_calls: list[dict[str, Any]] = []

    def run(self, image: str, **kwargs: Any) -> Resource:
        self.run_calls.append({"image": image, **kwargs})
        mapped: dict[str, list[dict[str, str]]] = {}
        for port, (_host, host_port) in kwargs["ports"].items():
            canonical = int(port.split("/", 1)[0])
            mapped[port] = [{"HostPort": str(host_port or canonical + 20000)}]
        resource = Resource(kwargs["name"], kwargs["labels"], ports=mapped)
        self.values[resource.name] = resource
        return resource


class Images:
    def __init__(self, exposed: dict[str, Any]):
        self.exposed = exposed

    def get(self, _name: str) -> Any:
        return SimpleNamespace(attrs={"Config": {"ExposedPorts": self.exposed}})

    def pull(self, name: str) -> Any:
        return self.get(name)


class Client:
    def __init__(self, exposed: dict[str, Any] | None = None):
        self.containers = ContainerCollection()
        self.networks = Collection()
        self.volumes = Collection()
        self.images = Images(exposed or {"24080/tcp": {}, "24081/tcp": {}, "24093/udp": {}})

    def version(self) -> dict[str, str]:
        return {"Version": "test"}


@pytest.fixture
def ready_runtime(monkeypatch: pytest.MonkeyPatch) -> tuple[DockerRuntime, Client]:
    client = Client()
    runtime = DockerRuntime(client=client)
    monkeypatch.setattr(runtime_module, "_port_is_free", lambda *_args: True)
    monkeypatch.setattr(
        DockerRuntime,
        "wait_ready",
        staticmethod(lambda _url, timeout=120.0, container=None: {"ready": True}),
    )
    return runtime, client


def test_resource_names_are_fixed_or_named() -> None:
    assert resource_names("default") == {
        "container": "localcloud",
        "network": "localcloud",
        "volume": "localcloud-data",
    }
    assert resource_names("team-a") == {
        "container": "localcloud-team-a",
        "network": "localcloud-team-a",
        "volume": "localcloud-data-team-a",
    }


def test_create_labels_roles_and_uses_fixed_bootstrap_project(
    tmp_path: Path, ready_runtime: tuple[DockerRuntime, Client]
) -> None:
    runtime, client = ready_runtime
    config = load_config(
        directory=tmp_path,
        project="other-project-1",
        user="alice",
    )

    record = runtime.create(config)
    run = client.containers.run_calls[0]
    labels = run["labels"]
    environment = run["environment"]

    assert record["name"] == "localcloud"
    assert labels[MANAGED_LABEL] == "true"
    assert labels[INSTANCE_LABEL] == "default"
    assert labels[RESOURCE_ROLE_LABEL] == "container"
    assert labels[CONFIG_HASH_LABEL] == config.config_hash
    assert labels[CONFIG_PATH_LABEL] == "<defaults>"
    assert labels[NETWORK_NAME_LABEL] == "localcloud"
    assert labels[VOLUME_NAME_LABEL] == "localcloud-data"
    assert labels[SERVICES_LABEL] == "<default>"
    assert labels[DATA_LABEL] == "persistent"
    assert environment["LOCALCLOUD_PROJECT"] == DEFAULT_PROJECT
    assert environment["LOCALCLOUD_INSTANCE"] == "default"
    assert "LOCALCLOUD_USER" not in environment
    assert "LOCALCLOUD_" + "WORK" + "SPACE" not in environment
    assert "LOCALCLOUD_CONTROLLER_ID" not in environment
    assert client.networks.values["localcloud"].labels[RESOURCE_ROLE_LABEL] == "network"
    assert client.volumes.values["localcloud-data"].labels[RESOURCE_ROLE_LABEL] == "volume"


def test_named_and_custom_resources_are_created_and_discovered_by_labels(
    tmp_path: Path, ready_runtime: tuple[DockerRuntime, Client]
) -> None:
    runtime, _client = ready_runtime
    config = load_config(
        directory=tmp_path,
        instance="team-a",
        container_name="custom-container",
        network_name="custom-network",
        volume_name="custom-volume",
    )

    runtime.create(config)
    inspected = runtime.inspect("team-a")

    assert inspected is not None
    assert inspected["name"] == "custom-container"
    assert inspected["network_name"] == "custom-network"
    assert inspected["volume_name"] == "custom-volume"


def test_unmanaged_deterministic_collision_fails_closed(ready_runtime: tuple[DockerRuntime, Client]) -> None:
    runtime, client = ready_runtime
    client.containers.values["localcloud"] = Resource("localcloud")

    with pytest.raises(HostError) as caught:
        runtime.inspect("default")

    assert caught.value.code == "ownership_mismatch"


def test_custom_name_collision_fails_closed(
    tmp_path: Path, ready_runtime: tuple[DockerRuntime, Client]
) -> None:
    runtime, client = ready_runtime
    client.networks.values["custom-network"] = Resource("custom-network")
    config = load_config(directory=tmp_path, network_name="custom-network")

    with pytest.raises(HostError) as caught:
        runtime.create(config)

    assert caught.value.code == "ownership_mismatch"
    assert client.containers.run_calls == []


def test_multiple_parent_containers_are_ambiguous(ready_runtime: tuple[DockerRuntime, Client]) -> None:
    runtime, client = ready_runtime
    base = {
        MANAGED_LABEL: "true",
        INSTANCE_LABEL: "default",
        RESOURCE_ROLE_LABEL: "container",
    }
    client.containers.values["first"] = Resource("first", base)
    client.containers.values["second"] = Resource("second", base)

    with pytest.raises(HostError) as caught:
        runtime.inspect("default")

    assert caught.value.code == "ambiguous_instance"
    assert caught.value.details["names"] == ["first", "second"]


def test_embedded_child_does_not_ambiguous_parent_and_is_removed_even_with_old_hash(
    tmp_path: Path, ready_runtime: tuple[DockerRuntime, Client]
) -> None:
    runtime, client = ready_runtime
    config = load_config(directory=tmp_path)
    environment = runtime.create(config)
    child = Resource(
        "dataproc-child",
        {
            MANAGED_LABEL: "true",
            INSTANCE_LABEL: "default",
            CONFIG_HASH_LABEL: "old-configuration-hash",
            "localcloud.managed": "true",
            "localcloud.service": "dataproc",
        },
    )
    client.containers.values[child.name] = child

    assert runtime.inspect("default") is not None
    runtime.remove(environment, remove_volume=False)

    assert child.removed == [{"force": True, "v": True}]
    assert client.volumes.values["localcloud-data"].removed == []
def test_child_with_incomplete_ownership_blocks_parent_cleanup(
    tmp_path: Path, ready_runtime: tuple[DockerRuntime, Client]
) -> None:
    runtime, client = ready_runtime
    environment = runtime.create(load_config(directory=tmp_path))
    parent = client.containers.values[environment["name"]]
    child = Resource(
        "untrusted-child",
        {
            INSTANCE_LABEL: "default",
            "localcloud.managed": "true",
        },
    )
    client.containers.values[child.name] = child

    with pytest.raises(HostError) as caught:
        runtime.remove(environment)

    assert caught.value.code == "cleanup_failed"
    assert parent.removed == []
    assert child.removed == []




def test_associated_resource_name_drift_fails_closed(
    tmp_path: Path, ready_runtime: tuple[DockerRuntime, Client]
) -> None:
    runtime, client = ready_runtime
    environment = runtime.create(load_config(directory=tmp_path))
    container = client.containers.values[environment["name"]]
    container.labels[NETWORK_NAME_LABEL] = "different-network"

    with pytest.raises(HostError) as caught:
        runtime.inspect("default")

    assert caught.value.code in {"resource_missing", "ownership_mismatch"}


def test_role_mismatch_is_not_adopted(
    tmp_path: Path, ready_runtime: tuple[DockerRuntime, Client]
) -> None:
    runtime, client = ready_runtime
    config = load_config(directory=tmp_path)
    client.volumes.values["localcloud-data"] = Resource(
        "localcloud-data",
        {
            MANAGED_LABEL: "true",
            INSTANCE_LABEL: "default",
            RESOURCE_ROLE_LABEL: "network",
        },
    )

    with pytest.raises(HostError) as caught:
        runtime.create(config)

    assert caught.value.code == "ownership_mismatch"


def test_config_metadata_contains_no_request_context(
    tmp_path: Path, ready_runtime: tuple[DockerRuntime, Client]
) -> None:
    runtime, client = ready_runtime
    config = load_config(directory=tmp_path, project="other-project-1", user="alice")
    runtime.create(config)

    snapshot = __import__("json").loads(client.containers.run_calls[0]["labels"][CONFIG_LABEL])
    assert "project" not in snapshot
    assert "user" not in snapshot
    assert "seed" not in snapshot


def test_wait_ready_fails_immediately_when_container_exits(monkeypatch: pytest.MonkeyPatch) -> None:
    container = Resource("localcloud", state="exited")
    monkeypatch.setattr(runtime_module.time, "monotonic", lambda: 0.0)

    with pytest.raises(HostError) as caught:
        DockerRuntime.wait_ready("http://127.0.0.1:24080", timeout=1.0, container=container)

    assert caught.value.code == "container_start_failed"
    assert caught.value.details["state"] == "exited"


@pytest.mark.parametrize(
    "url",
    [
        "http://example.com:24080",
        "file:///tmp/socket",
        "http://user:pass@127.0.0.1:24080",
        "http://127.0.0.1:24080?next=example.com",
    ],
)
def test_wait_ready_rejects_nonlocal_or_unsafe_urls(url: str) -> None:
    with pytest.raises(HostError) as caught:
        DockerRuntime.wait_ready(url, timeout=0)
    assert caught.value.code in {"invalid_endpoint", "nonlocal_endpoint"}


def test_doctor_reports_only_legacy_path_derived_resources(
    ready_runtime: tuple[DockerRuntime, Client]
) -> None:
    runtime, client = ready_runtime
    old_label = "com.localcloud." + "work" + "space-key"
    client.containers.values["old"] = Resource("old", {old_label: "abc"})
    client.containers.values["current"] = Resource(
        "current",
        {
            MANAGED_LABEL: "true",
            INSTANCE_LABEL: "default",
            RESOURCE_ROLE_LABEL: "container",
        },
    )

    result = runtime.doctor()
    assert result["legacy_resources"] == [{"kind": "container", "name": "old"}]
    assert "not migrated or removed" in result["warning"]
