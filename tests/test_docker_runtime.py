from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

import localcloud_cli.docker_runtime as runtime_module
from localcloud_cli.config import DEFAULT_IMAGE, DEFAULT_PROJECT, HostPaths, load_config
from localcloud_cli.docker_runtime import (
    CONFIG_HASH_LABEL,
    CONFIG_LABEL,
    CONFIG_PATH_LABEL,
    DATA_LABEL,
    DATA_MOUNT_DESTINATION,
    INSTANCE_LABEL,
    MANAGED_LABEL,
    NETWORK_NAME_LABEL,
    RESOURCE_ROLE_LABEL,
    RUNTIME_OWNERSHIP_CAPABILITY,
    RUNTIME_OWNERSHIP_LABEL,
    SERVICES_LABEL,
    VOLUME_NAME_LABEL,
    DockerRuntime,
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
        resource_id: str | None = None,
        state: str = "running",
        image: str | None = None,
        image_id: str | None = None,
        mounts: list[dict[str, Any]] | None = None,
        ports: dict[str, Any] | None = None,
        networks: list[str] | None = None,
        environment: dict[str, str] | None = None,
    ):
        self.name = name
        self.id = resource_id or f"id-{name}"
        self.labels = dict(labels or {})
        self.status = state
        self.collection: Collection | None = None
        self.attrs = {
            "Labels": self.labels,
            "State": {"Status": state, "Health": {"Status": "healthy"}},
            "Config": {
                "Image": image,
                "Env": [f"{key}={value}" for key, value in (environment or {}).items()],
            },
            "Image": image_id,
            "Mounts": list(mounts or []),
            "NetworkSettings": {
                "Ports": ports or {},
                "Networks": {name: {} for name in (networks or [])},
            },
        }
        self.removed: list[dict[str, Any]] = []
        self.started = 0
        self.stopped = 0
        self.restarted = 0
        self.reload_calls = 0
        self.reload_error: Exception | None = None

    def reload(self) -> None:
        self.reload_calls += 1
        if self.reload_error is not None:
            raise self.reload_error

    def start(self) -> None:
        self.started += 1
        self.status = "running"
        self.attrs["State"]["Status"] = "running"

    def stop(self, timeout: int) -> None:
        assert timeout == 20
        self.stopped += 1
        self.status = "exited"
        self.attrs["State"]["Status"] = "exited"

    def restart(self, timeout: int) -> None:
        assert timeout == 20
        self.restarted += 1
        self.status = "running"
        self.attrs["State"]["Status"] = "running"

    def remove(self, **kwargs: Any) -> None:
        self.removed.append(kwargs)
        if self.collection is not None:
            self.collection.values.pop(self.name, None)

    def logs(self, **_kwargs: Any) -> bytes:
        return b"container log\n"


class Collection:
    def __init__(self):
        self.values: dict[str, Resource] = {}
        self.created: list[tuple[str, dict[str, Any]]] = []
        self.list_calls: list[dict[str, Any]] = []

    def add(self, resource: Resource) -> Resource:
        resource.collection = self
        self.values[resource.name] = resource
        return resource

    def get(self, identity: str) -> Resource:
        if identity in self.values:
            return self.values[identity]
        for resource in self.values.values():
            if resource.id == identity:
                return resource
        raise NotFound(identity)

    def create(self, name: str | None = None, **kwargs: Any) -> Resource:
        selected = name or kwargs.pop("name")
        resource = self.add(Resource(selected, kwargs.get("labels")))
        self.created.append((selected, kwargs))
        return resource

    def list(self, **kwargs: Any) -> list[Resource]:
        self.list_calls.append(kwargs)
        filters = kwargs.get("filters") or {}
        volume = filters.get("volume")
        if volume is None:
            return list(self.values.values())
        selected = {str(item) for item in volume} if isinstance(volume, list) else {str(volume)}
        return [
            resource
            for resource in self.values.values()
            if any(
                str(mount.get("Name") or "") in selected
                for mount in resource.attrs.get("Mounts", [])
            )
        ]


class Image:
    def __init__(
        self,
        image_id: str,
        *,
        qualified: bool = True,
        exposed: dict[str, Any] | None = None,
    ):
        self.id = image_id
        labels = (
            {RUNTIME_OWNERSHIP_LABEL: RUNTIME_OWNERSHIP_CAPABILITY}
            if qualified
            else {}
        )
        self.attrs = {
            "Id": image_id,
            "Config": {
                "Labels": labels,
                "ExposedPorts": exposed
                or {"24080/tcp": {}, "24081/tcp": {}, "24093/udp": {}},
            },
        }


class Images:
    def __init__(self):
        self.by_name: dict[str, Image] = {}
        self.pulls: list[str] = []

    def add(self, name: str, image: Image) -> Image:
        self.by_name[name] = image
        self.by_name[image.id] = image
        return image

    def get(self, name: str) -> Image:
        if name not in self.by_name:
            raise NotFound(name)
        return self.by_name[name]

    def pull(self, name: str) -> Image:
        self.pulls.append(name)
        return self.get(name)


class ContainerCollection(Collection):
    def __init__(self, client: Client):
        super().__init__()
        self.client = client
        self.run_calls: list[dict[str, Any]] = []

    def run(self, image: str, **kwargs: Any) -> Resource:
        self.run_calls.append({"image": image, **kwargs})
        mapped: dict[str, list[dict[str, str]]] = {}
        for port, (_host, host_port) in kwargs["ports"].items():
            canonical = int(port.split("/", 1)[0])
            mapped[port] = [{"HostPort": str(host_port or canonical + 20000)}]
        mounts: list[dict[str, Any]] = []
        for source, mount in kwargs["volumes"].items():
            is_bind = str(source).startswith("/")
            mounts.append(
                {
                    "Type": "bind" if is_bind else "volume",
                    "Name": None if is_bind else source,
                    "Source": source,
                    "Destination": mount["bind"],
                    "Mode": mount["mode"],
                    "RW": mount["mode"] != "ro",
                }
            )
        image_object = self.client.images.get(image)
        resource = Resource(
            kwargs["name"],
            kwargs["labels"],
            image=image,
            image_id=image_object.id,
            mounts=mounts,
            ports=mapped,
            networks=[kwargs["network"]],
            environment=kwargs["environment"],
        )
        return self.add(resource)


class Client:
    def __init__(self):
        self.networks = Collection()
        self.volumes = Collection()
        self.images = Images()
        self.images.add(DEFAULT_IMAGE, Image("sha256:qualified"))
        self.containers = ContainerCollection(self)

    def version(self) -> dict[str, str]:
        return {"Version": "test"}


def _paths(tmp_path: Path) -> HostPaths:
    home = tmp_path / "home"
    return HostPaths(home, home / "locks")


def _config(tmp_path: Path, **kwargs: Any):
    return load_config(directory=tmp_path, paths=_paths(tmp_path), **kwargs)


def _write_config(tmp_path: Path, source: str):
    (tmp_path / "localcloud.yaml").write_text(source, encoding="utf-8")
    return _config(tmp_path)


def _volume_mount(
    name: str,
    *,
    destination: str = DATA_MOUNT_DESTINATION,
    read_write: bool = True,
) -> dict[str, Any]:
    return {
        "Type": "volume",
        "Name": name,
        "Source": f"/var/lib/docker/volumes/{name}/_data",
        "Destination": destination,
        "Mode": "rw" if read_write else "ro",
        "RW": read_write,
    }


def _ports(gateway: int = 49080) -> dict[str, list[dict[str, str]]]:
    return {"24080/tcp": [{"HostIp": "127.0.0.1", "HostPort": str(gateway)}]}


def _add_external(
    client: Client,
    *,
    name: str = "external-localcloud",
    data_volume: str = "localcloud-data",
    state: str = "running",
    image: str = DEFAULT_IMAGE,
    image_id: str = "sha256:qualified",
    labels: dict[str, str] | None = None,
    mount: dict[str, Any] | None = None,
    ports: dict[str, Any] | None = None,
    network: str = "external-network",
) -> Resource:
    if data_volume not in client.volumes.values:
        client.volumes.add(Resource(data_volume))
    if network not in client.networks.values:
        client.networks.add(Resource(network))
    return client.containers.add(
        Resource(
            name,
            labels,
            state=state,
            image=image,
            image_id=image_id,
            mounts=[mount or _volume_mount(data_volume)],
            ports=_ports() if ports is None else ports,
            networks=[network],
            environment={"LOCALCLOUD_DATA_DIR": DATA_MOUNT_DESTINATION},
        )
    )


@pytest.fixture
def ready_runtime(monkeypatch: pytest.MonkeyPatch) -> tuple[DockerRuntime, Client]:
    client = Client()
    runtime = DockerRuntime(client=client)
    monkeypatch.setattr(runtime_module, "_port_is_free", lambda *_args: True)
    monkeypatch.setattr(
        DockerRuntime,
        "wait_ready",
        staticmethod(lambda _url, timeout=120.0, container=None: {"status": "healthy"}),
    )
    return runtime, client


def test_managed_create_uses_data_volume_ownership_and_fixed_bootstrap_project(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path, project="other-project-1", user="alice")

    record = runtime.create(config)
    run = client.containers.run_calls[0]
    labels = run["labels"]
    environment = run["environment"]

    assert record.origin == "managed"
    assert record.ownership == {
        "container": "managed",
        "network": "managed",
        "data_volume": "managed",
    }
    assert labels[MANAGED_LABEL] == "true"
    assert labels[RESOURCE_ROLE_LABEL] == "container"
    assert labels[VOLUME_NAME_LABEL] == "localcloud-data"
    assert INSTANCE_LABEL not in labels
    assert labels[CONFIG_HASH_LABEL] == config.config_hash
    assert labels[CONFIG_PATH_LABEL] == "<defaults>"
    assert labels[NETWORK_NAME_LABEL] == "localcloud"
    assert labels[SERVICES_LABEL] == "<default>"
    assert labels[DATA_LABEL] == "persistent"
    assert environment["LOCALCLOUD_PROJECT"] == DEFAULT_PROJECT
    assert environment["LOCALCLOUD_DATA_VOLUME"] == "localcloud-data"
    assert "LOCALCLOUD_INSTANCE" not in environment
    assert "LOCALCLOUD_USER" not in environment
    assert client.networks.values["localcloud"].labels[VOLUME_NAME_LABEL] == (
        "localcloud-data"
    )
    assert client.volumes.values["localcloud-data"].labels[VOLUME_NAME_LABEL] == (
        "localcloud-data"
    )


def test_managed_create_rejects_image_without_runtime_ownership_capability(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    legacy = client.images.add(
        "registry.example/localcloud:legacy",
        Image("sha256:legacy", qualified=False),
    )
    assert legacy.attrs["Config"]["Labels"] == {}
    config = _write_config(tmp_path, "image: registry.example/localcloud:legacy\n")

    with pytest.raises(HostError) as caught:
        runtime.create(config)

    assert caught.value.code == "managed_image_capability_missing"
    assert client.containers.run_calls == []
    assert client.networks.created == []
    assert client.volumes.created == []


def test_manual_container_is_adopted_by_exact_mount_and_id(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    external = _add_external(client)
    labels_before = dict(external.labels)

    record = runtime.resolve(_config(tmp_path), preferred_container_id="stale-id")

    assert record is not None
    assert record.data_volume == "localcloud-data"
    assert record.origin == "attached"
    assert record.ownership == {
        "container": "attached",
        "network": "attached",
        "data_volume": "attached",
    }
    assert record.container_id == external.id
    assert record.mount == {
        "type": "volume",
        "source": "localcloud-data",
        "destination": DATA_MOUNT_DESTINATION,
        "mode": "rw",
        "read_write": True,
    }
    assert external.labels == labels_before


def test_resolve_filters_by_volume_and_inspects_only_candidates(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    selected = _add_external(client)
    unrelated = client.containers.add(
        Resource(
            "unrelated",
            image=DEFAULT_IMAGE,
            image_id="sha256:qualified",
            mounts=[_volume_mount("other-data")],
        )
    )
    unrelated.reload_error = RuntimeError("must not be inspected")

    record = runtime.resolve(_config(tmp_path))

    assert record is not None
    assert record.container_id == selected.id
    assert selected.reload_calls == 1
    assert unrelated.reload_calls == 0
    assert client.containers.list_calls[-1] == {
        "all": True,
        "filters": {"volume": "localcloud-data"},
        "sparse": True,
    }


def test_external_stopped_container_start_stop_restart_preserve_every_resource(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    external = _add_external(client, state="exited")
    network = client.networks.values["external-network"]
    volume = client.volumes.values["localcloud-data"]
    config = _config(tmp_path)
    record = runtime.resolve(config)
    assert record is not None

    started = runtime.start(config, record)
    stopped = runtime.stop(config, started)
    restarted = runtime.restart(config, stopped)

    assert started.container_id == external.id
    assert stopped.container_id == external.id
    assert stopped.state == "exited"
    assert restarted.container_id == external.id
    assert external.started == 1
    assert external.stopped == 1
    assert external.restarted == 1
    assert client.containers.run_calls == []
    assert external.removed == []
    assert network.removed == []
    assert volume.removed == []
    assert external.labels == {}


@pytest.mark.parametrize(
    "mount",
    [
        _volume_mount("localcloud-data", destination="/wrong/path"),
        _volume_mount("localcloud-data", read_write=False),
    ],
    ids=["wrong-destination", "read-only"],
)
def test_wrong_data_mount_fails_before_attachment(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
    mount: dict[str, Any],
) -> None:
    runtime, client = ready_runtime
    _add_external(client, mount=mount)

    with pytest.raises(HostError) as caught:
        runtime.resolve(_config(tmp_path))

    assert caught.value.code == "invalid_data_volume_mount"


def test_every_second_volume_user_causes_deterministic_collision(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    first = _add_external(client, name="first", state="running")
    second = _add_external(client, name="second", state="exited")

    with pytest.raises(HostError) as caught:
        runtime.resolve(_config(tmp_path), preferred_container_id=first.id)

    assert caught.value.code == "data_volume_collision"
    assert [item["name"] for item in caught.value.details["containers"]] == [
        "first",
        "second",
    ]


def test_incompatible_container_using_volume_blocks_creation(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    client.images.add("busybox:latest", Image("sha256:busybox", qualified=False))
    _add_external(
        client,
        image="busybox:latest",
        image_id="sha256:busybox",
    )

    with pytest.raises(HostError) as caught:
        runtime.resolve(_config(tmp_path))

    assert caught.value.code == "incompatible_data_volume_user"
    assert client.containers.run_calls == []


def test_equivalent_reference_or_immutable_image_id_is_compatible(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    external = _add_external(
        client,
        image="docker.io/jaysen2apache/localcloud:latest",
    )
    resolved = runtime.resolve(_config(tmp_path))
    assert resolved is not None
    assert resolved.container_id == external.id

    client.images.add(
        "mirror.example/localcloud:pinned",
        client.images.get(DEFAULT_IMAGE),
    )
    config = _write_config(tmp_path, "image: mirror.example/localcloud:pinned\n")
    record = runtime.resolve(config)
    assert record is not None
    assert record.container_id == external.id


def test_mutable_matching_reference_allows_older_container_image_id(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    client.images.add("sha256:older", Image("sha256:older"))
    external = _add_external(client, image_id="sha256:older")

    record = runtime.resolve(_config(tmp_path))

    assert record is not None
    assert record.container_id == external.id
    assert record.image_id == "sha256:older"


def test_managed_runtime_remains_resolvable_for_image_reconfiguration(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    original = _config(tmp_path)
    created = runtime.create(original)
    replacement_image = "registry.example/localcloud:replacement"
    client.images.add(replacement_image, Image("sha256:replacement"))
    replacement = _write_config(tmp_path, f"image: {replacement_image}\n")

    resolved = runtime.resolve(replacement)

    assert resolved is not None
    assert resolved.container_id == created.container_id
    assert resolved.ownership["container"] == "managed"
    assert resolved.actual_image == DEFAULT_IMAGE
    assert resolved.configured_image == replacement_image


@pytest.mark.parametrize(
    "labels",
    [
        {MANAGED_LABEL: "true"},
        {
            MANAGED_LABEL: "true",
            RESOURCE_ROLE_LABEL: "container",
            VOLUME_NAME_LABEL: "another-volume",
        },
        {VOLUME_NAME_LABEL: "localcloud-data"},
    ],
    ids=["missing-role", "wrong-volume", "partial-claim"],
)
def test_partial_or_contradictory_container_ownership_fails_closed(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
    labels: dict[str, str],
) -> None:
    runtime, client = ready_runtime
    _add_external(client, labels=labels)

    with pytest.raises(HostError) as caught:
        runtime.resolve(_config(tmp_path))

    assert caught.value.code == "ownership_mismatch"


def test_existing_unlabeled_volume_stays_attached_to_new_managed_container(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    external_volume = client.volumes.add(
        Resource("localcloud-data", {"third.party.owner": "developer"})
    )
    config = _config(tmp_path)

    record = runtime.create(config)

    assert record.origin == "attached"
    assert record.ownership == {
        "container": "managed",
        "network": "managed",
        "data_volume": "attached",
    }
    assert record.volume_created is False
    assert external_volume.labels == {"third.party.owner": "developer"}

    runtime.remove(config, record, remove_volume=True)

    assert external_volume.removed == []
    assert client.containers.values == {}
    assert client.networks.values == {}


def test_attached_container_removal_is_forbidden_without_any_mutation(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    external = _add_external(client)
    volume = client.volumes.values["localcloud-data"]
    network = client.networks.values["external-network"]
    config = _config(tmp_path)
    record = runtime.resolve(config)
    assert record is not None

    with pytest.raises(HostError) as caught:
        runtime.remove(config, record)

    assert caught.value.code == "ownership_forbidden"
    assert external.removed == []
    assert volume.removed == []
    assert network.removed == []


def test_legacy_managed_parent_is_adopted_by_volume_not_instance(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path)
    created = runtime.create(config)
    parent = client.containers.values[created.name]
    parent.labels[INSTANCE_LABEL] = "obsolete-instance"
    network = client.networks.values[created.network_name]
    network.labels[INSTANCE_LABEL] = "obsolete-instance"
    volume = client.volumes.values[config.data_volume]
    volume.labels.pop(VOLUME_NAME_LABEL)
    volume.labels[INSTANCE_LABEL] = "obsolete-instance"

    adopted = runtime.resolve(config)

    assert adopted is not None
    assert adopted.origin == "managed"
    assert adopted.data_volume == config.data_volume

    runtime.remove(config, adopted, remove_volume=True)
    assert volume.removed == [{"force": True}]


def test_new_and_legacy_managed_children_are_cleaned_by_validated_ownership(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path)
    created = runtime.create(config)
    parent = client.containers.values[created.name]
    parent.labels[INSTANCE_LABEL] = "legacy-parent"
    new_child = client.containers.add(
        Resource(
            "new-child",
            {
                MANAGED_LABEL: "true",
                VOLUME_NAME_LABEL: config.data_volume,
                CONFIG_HASH_LABEL: config.config_hash,
                "localcloud.managed": "true",
            },
        )
    )
    legacy_child = client.containers.add(
        Resource(
            "legacy-child",
            {
                MANAGED_LABEL: "true",
                INSTANCE_LABEL: "legacy-parent",
                CONFIG_HASH_LABEL: config.config_hash,
                "localcloud.managed": "true",
            },
        )
    )
    record = runtime.resolve(config)
    assert record is not None

    runtime.remove(config, record, remove_volume=False)

    assert new_child.removed == [{"force": True, "v": True}]
    assert legacy_child.removed == [{"force": True, "v": True}]
    assert client.volumes.values[config.data_volume].removed == []


def test_incomplete_new_child_ownership_blocks_parent_cleanup(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path)
    created = runtime.create(config)
    parent = client.containers.values[created.name]
    child = client.containers.add(
        Resource(
            "untrusted-child",
            {
                VOLUME_NAME_LABEL: config.data_volume,
                "localcloud.managed": "true",
            },
        )
    )

    with pytest.raises(HostError) as caught:
        runtime.remove(config, created)

    assert caught.value.code == "cleanup_failed"
    assert parent.removed == []
    assert child.removed == []


def test_incomplete_orphan_child_ownership_blocks_purge(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path)
    created = runtime.create(config)
    parent = client.containers.values.pop(created.name)
    network = client.networks.values[created.network_name]
    volume = client.volumes.values[config.data_volume]
    child = client.containers.add(
        Resource(
            "untrusted-orphan-child",
            {
                VOLUME_NAME_LABEL: config.data_volume,
                "localcloud.managed": "true",
            },
        )
    )

    with pytest.raises(HostError) as caught:
        runtime.purge(config)

    assert caught.value.code == "cleanup_failed"
    assert parent.removed == []
    assert child.removed == []
    assert network.removed == []
    assert volume.removed == []


def test_legacy_compatible_image_can_attach_and_restart_but_not_create(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    legacy_name = "registry.example/localcloud:legacy"
    client.images.add(legacy_name, Image("sha256:legacy", qualified=False))
    config = _write_config(tmp_path, f"image: {legacy_name}\n")
    external = _add_external(
        client,
        state="exited",
        image=legacy_name,
        image_id="sha256:legacy",
    )

    record = runtime.resolve(config)
    assert record is not None
    restarted = runtime.restart(config, record)
    assert restarted.container_id == external.id
    assert external.restarted == 1

    client.containers.values.clear()
    client.volumes.values.clear()
    client.networks.values.clear()
    with pytest.raises(HostError) as caught:
        runtime.create(config)
    assert caught.value.code == "managed_image_capability_missing"


def test_failed_create_rolls_back_only_resources_created_by_the_operation(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runtime, client = ready_runtime
    attached_volume = client.volumes.add(Resource("localcloud-data"))
    monkeypatch.setattr(
        DockerRuntime,
        "wait_ready",
        staticmethod(
            lambda *_args, **_kwargs: (_ for _ in ()).throw(
                HostError("health_timeout", "not healthy")
            )
        ),
    )

    with pytest.raises(HostError) as caught:
        runtime.create(_config(tmp_path))

    assert caught.value.code == "health_timeout"
    assert attached_volume.removed == []
    assert attached_volume.labels == {}
    assert client.containers.values == {}
    assert client.networks.values == {}


def test_runtime_configuration_metadata_contains_no_request_context(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path, project="other-project-1", user="alice")
    runtime.create(config)

    snapshot = json.loads(client.containers.run_calls[0]["labels"][CONFIG_LABEL])
    assert snapshot["data_volume"] == "localcloud-data"
    assert "instance" not in snapshot
    assert "volume_name" not in snapshot
    assert "project" not in snapshot
    assert "user" not in snapshot
    assert "seed" not in snapshot


def test_wait_ready_uses_health_and_fails_immediately_when_container_exits(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    container = Resource("localcloud", state="exited")
    monkeypatch.setattr(runtime_module.time, "monotonic", lambda: 0.0)

    with pytest.raises(HostError) as caught:
        DockerRuntime.wait_ready(
            "http://127.0.0.1:24080", timeout=1.0, container=container
        )

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


def test_doctor_reports_collisions_invalid_ownership_and_legacy_resources(
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    old_label = "com.localcloud." + "work" + "space-key"
    client.containers.add(Resource("old", {old_label: "abc"}))
    _add_external(client, name="first", data_volume="shared-data")
    _add_external(client, name="second", data_volume="shared-data")
    client.networks.add(
        Resource("broken-network", {MANAGED_LABEL: "true"})
    )

    result = runtime.doctor()

    assert result["legacy_resources"] == [{"kind": "container", "name": "old"}]
    assert result["volume_collisions"][0]["data_volume"] == "shared-data"
    assert result["invalid_ownership"][0]["name"] == "broken-network"
    assert "will not be mutated" in result["warning"]
