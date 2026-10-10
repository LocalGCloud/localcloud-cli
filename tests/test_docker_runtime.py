from __future__ import annotations

import json
import os
import socket
import time
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest

import localcloud_cli.docker_runtime as runtime_module
from localcloud_cli.config import HostPaths, load_config
from localcloud_cli.constants import DEFAULT_IMAGE
from localcloud_cli.docker_runtime import (
    CONFIG_SCHEMA_CAPABILITY,
    CONFIG_SCHEMA_LABEL,
    CONFIG_HASH_LABEL,
    CONFIG_LABEL,
    LEGACY_SEED_MOUNT_DESTINATION,
    CONFIG_MOUNT_DESTINATION,
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
    LogCursor,
)
from localcloud_cli.errors import HostError

_real_docker_socket_is_usable = runtime_module._docker_socket_is_usable


@pytest.fixture(autouse=True)
def available_docker_socket(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        runtime_module,
        "_docker_socket_is_usable",
        lambda: True,
    )


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
        assert timeout == runtime_module.STOP_TIMEOUT_SECONDS
        self.stopped += 1
        self.status = "exited"
        self.attrs["State"]["Status"] = "exited"

    def restart(self, timeout: int) -> None:
        assert timeout == runtime_module.STOP_TIMEOUT_SECONDS
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
        results = list(self.values.values())
        volume = filters.get("volume")
        if volume is not None:
            selected = {str(item) for item in volume} if isinstance(volume, list) else {str(volume)}
            results = [
                resource
                for resource in results
                if any(
                    str(mount.get("Name") or "") in selected
                    for mount in resource.attrs.get("Mounts", [])
                )
            ]
        label_filter = filters.get("label")
        if label_filter is not None:
            labels_to_check = (
                [str(item) for item in label_filter]
                if isinstance(label_filter, list)
                else [str(label_filter)]
            )
            for spec in labels_to_check:
                if "=" in spec:
                    k, v = spec.split("=", 1)
                    results = [r for r in results if r.labels.get(k) == v]
                else:
                    results = [r for r in results if spec in r.labels]
        return results



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
            {
                RUNTIME_OWNERSHIP_LABEL: RUNTIME_OWNERSHIP_CAPABILITY,
                CONFIG_SCHEMA_LABEL: CONFIG_SCHEMA_CAPABILITY,
            }
            if qualified
            else {}
        )
        default_exposed = {
            **{f"{port}/tcp": {} for port in range(5380, 5406)},
            "5410/udp": {},
        }
        self.attrs = {
            "Id": image_id,
            "Config": {
                "Labels": labels,
                "ExposedPorts": default_exposed if exposed is None else exposed,
            },
        }


class RegistryData:
    def __init__(self, digest: str | None = None):
        self.attrs = {
            "Descriptor": {"digest": digest} if digest else {},
        }
        self.id = digest


class Images:
    def __init__(self):
        self.by_name: dict[str, Image] = {}
        self.gets: list[str] = []
        self.pulls: list[str] = []
        self.registry_data: dict[str, Any] = {}

    def add(self, name: str, image: Image) -> Image:
        self.by_name[name] = image
        self.by_name[image.id] = image
        return image

    def get(self, name: str) -> Image:
        self.gets.append(name)
        if name not in self.by_name:
            raise NotFound(name)
        return self.by_name[name]

    def get_registry_data(self, name: str) -> Any:
        if name in self.registry_data:
            return self.registry_data[name]
        raise NotFound(name)

    def pull(self, name: str) -> Image:
        self.pulls.append(name)
        if name not in self.by_name:
            self.add(name, Image(f"sha256:{name}"))
        return self.get(name)


class ContainerCollection(Collection):
    def __init__(self, client: Client):
        super().__init__()
        self.client = client
        self.run_calls: list[dict[str, Any]] = []

    def run(self, image: str, **kwargs: Any) -> Resource:
        self.run_calls.append({"image": image, **kwargs})
        mapped: dict[str, list[dict[str, str]]] = {}
        for port, requested in kwargs["ports"].items():
            bindings = (
                requested
                if isinstance(requested, list)
                else [requested]
            )
            canonical = int(port.split("/", 1)[0])
            mapped[port] = [
                {
                    "HostIp": host,
                    "HostPort": str(host_port or canonical + 20000),
                }
                for host, host_port in bindings
            ]
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
    return {"5380/tcp": [{"HostIp": "127.0.0.1", "HostPort": str(gateway)}]}


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
    environment: dict[str, str] | None = None,
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
            environment={"LOCALCLOUD_DATA_DIR": DATA_MOUNT_DESTINATION, **(environment or {})},
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
        staticmethod(
            lambda _url, *, deadline, container=None, **_kwargs: {"status": "healthy"}
        ),
    )
    return runtime, client


def test_managed_create_uses_only_controller_owned_bootstrap_environment(
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
    assert SERVICES_LABEL not in labels
    assert labels[DATA_LABEL] == "persistent"
    assert environment == {"LOCALCLOUD_DOCKER_ACCESS": "auto"}
    network_labels = client.networks.values["localcloud"].labels
    assert network_labels[MANAGED_LABEL] == "true"
    assert network_labels[RESOURCE_ROLE_LABEL] == "network"
    assert network_labels[VOLUME_NAME_LABEL] == "localcloud-data"
    assert network_labels[NETWORK_NAME_LABEL] == "localcloud"
    # The network's own identity never depends on unrelated config (image,
    # memory, environment, ...), so it isn't tagged with the whole-config
    # hash/JSON blob the container carries - that would make any unrelated
    # config change look like a network change.
    assert CONFIG_HASH_LABEL not in network_labels
    assert CONFIG_LABEL not in network_labels
    assert CONFIG_PATH_LABEL not in network_labels
    assert DATA_LABEL not in network_labels
    assert client.volumes.values["localcloud-data"].labels[VOLUME_NAME_LABEL] == (
        "localcloud-data"
    )


def test_non_default_runtime_passes_only_required_ownership_environment(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path, data_volume="localcloud-data-team")

    runtime.create(config)

    environment = client.containers.run_calls[0]["environment"]
    assert environment == {
        "LOCALCLOUD_DOCKER_ACCESS": "auto",
        "LOCALCLOUD_RUNTIME_NETWORK": "localcloud-team",
        "LOCALCLOUD_DATA_VOLUME": "localcloud-data-team",
        # A per-runtime MySQL companion; the default name is the server's own.
        "LOCALCLOUD_MYSQL_CONTAINER_NAME": "localcloud-mysql-localcloud-data-team",
    }
    assert client.volumes.values["localcloud-data-team"].labels[
        VOLUME_NAME_LABEL
    ] == "localcloud-data-team"


def test_tls_flag_is_rendered_as_required_container_environment(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path, tls=True)
    image = client.images.get(config.image)

    command = runtime.plan_run(config, image).command()

    assert "-e LOCALCLOUD_TLS_ENABLED=true" in command


def test_managed_create_propagates_explicit_services_override(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path, services=["gcs", "pubsub"])

    runtime.create(config)
    environment = client.containers.run_calls[0]["environment"]

    assert environment["LOCALCLOUD_SERVICES"] == "gcs,pubsub"


def test_socket_mount_does_not_enable_embedded_runtime(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _write_config(
        tmp_path,
        "host:\n  docker_socket: auto\nservices:\n  enabled: [dataproc]\n",
    )

    plan = runtime.plan_run(config, client.images.get(config.image))

    assert "/var/run/docker.sock" in plan.volumes
    assert "LOCALCLOUD_RUNTIME_EMBEDDED_DOCKER" not in plan.environment
    assert plan.environment["LOCALCLOUD_DOCKER_ACCESS"] == "auto"


def test_preflight_rejects_missing_docker_socket_before_image_work(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = Client()
    runtime = DockerRuntime(client=client)
    monkeypatch.setattr(
        runtime_module,
        "_docker_socket_is_usable",
        lambda: False,
    )

    with pytest.raises(HostError) as caught:
        runtime.preflight_create(_config(tmp_path, active_runtime=None))

    assert caught.value.code == "docker_socket_unavailable"
    assert caught.value.details == {
        "path": "/var/run/docker.sock",
        "docker_access": "auto",
    }
    assert client.images.gets == []
    assert client.images.pulls == []


def _temp_unix_socket() -> tuple[socket.socket, str]:
    path = f"/tmp/lc_t_{os.getpid()}_{time.time_ns()}.sock"
    server = socket.socket(socket.AF_UNIX)
    server.bind(path)
    return server, path


def test_docker_socket_is_usable_detects_custom_adapter_socket(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    server, sock_path = _temp_unix_socket()
    try:
        class FakeAdapter:
            socket_path = str(sock_path)

        class FakeApi:
            _custom_adapter = FakeAdapter()

        class FakeDockerClient:
            api = FakeApi()

        monkeypatch.setattr(runtime_module, "_DOCKER_SOCKET_PATH", str(tmp_path / "nonexistent.sock"))
        monkeypatch.delenv("DOCKER_HOST", raising=False)
        monkeypatch.delenv("DOCKER_CONTEXT", raising=False)
        monkeypatch.setenv("HOME", str(tmp_path / "empty_home"))

        assert _real_docker_socket_is_usable(FakeDockerClient()) is True
        assert _real_docker_socket_is_usable(None) is False
    finally:
        server.close()
        try:
            os.unlink(sock_path)
        except OSError:
            pass


def test_docker_socket_is_usable_detects_docker_host_socket(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    server, sock_path = _temp_unix_socket()
    try:
        monkeypatch.setattr(runtime_module, "_DOCKER_SOCKET_PATH", str(tmp_path / "nonexistent.sock"))
        monkeypatch.setenv("DOCKER_HOST", f"unix://{sock_path}")
        monkeypatch.setenv("HOME", str(tmp_path / "empty_home"))

        assert _real_docker_socket_is_usable() is True
    finally:
        server.close()
        try:
            os.unlink(sock_path)
        except OSError:
            pass


def test_docker_socket_is_usable_detects_docker_context(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    home = tmp_path / "home"
    docker_dir = home / ".docker"
    contexts_dir = docker_dir / "contexts" / "meta" / "colima_ctx"
    contexts_dir.mkdir(parents=True)
    server, sock_path = _temp_unix_socket()
    try:
        (docker_dir / "config.json").write_text(
            json.dumps({"currentContext": "colima"}), encoding="utf-8"
        )
        (contexts_dir / "meta.json").write_text(
            json.dumps({
                "Name": "colima",
                "Endpoints": {"docker": {"Host": f"unix://{sock_path}"}},
            }),
            encoding="utf-8",
        )
        monkeypatch.setattr(runtime_module, "_DOCKER_SOCKET_PATH", str(tmp_path / "nonexistent.sock"))
        monkeypatch.delenv("DOCKER_HOST", raising=False)
        monkeypatch.setenv("HOME", str(home))

        assert _real_docker_socket_is_usable() is True
    finally:
        server.close()
        try:
            os.unlink(sock_path)
        except OSError:
            pass


def test_docker_socket_is_usable_returns_false_when_no_socket(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(runtime_module, "_DOCKER_SOCKET_PATH", str(tmp_path / "nonexistent.sock"))
    monkeypatch.delenv("DOCKER_HOST", raising=False)
    monkeypatch.delenv("DOCKER_CONTEXT", raising=False)
    monkeypatch.delenv("XDG_RUNTIME_DIR", raising=False)
    monkeypatch.setenv("HOME", str(tmp_path / "empty_home"))

    assert _real_docker_socket_is_usable() is False


def test_preflight_passes_when_alternate_socket_is_usable(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    server, sock_path = _temp_unix_socket()
    try:
        class FakeAdapter:
            socket_path = str(sock_path)

        class FakeApi:
            _custom_adapter = FakeAdapter()

        client = Client()
        client.api = FakeApi()
        runtime = DockerRuntime(client=client)

        monkeypatch.setattr(runtime_module, "_DOCKER_SOCKET_PATH", str(tmp_path / "nonexistent.sock"))
        monkeypatch.delenv("DOCKER_HOST", raising=False)
        monkeypatch.delenv("DOCKER_CONTEXT", raising=False)
        monkeypatch.setenv("HOME", str(tmp_path / "empty_home"))
        monkeypatch.setattr(runtime_module, "_docker_socket_is_usable", _real_docker_socket_is_usable)

        prepared, was_pulled = runtime.preflight_create(
            _config(tmp_path, active_runtime=None), pull=False
        )
        assert prepared is not None
    finally:
        server.close()
        try:
            os.unlink(sock_path)
        except OSError:
            pass




def test_selected_config_is_mounted_read_only_and_forwards_resolved_services(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config_path = tmp_path / "localcloud.yaml"
    config_path.write_text(
        "version: 1\n"
        "context:\n"
        "  project: merged-project\n"
        "server:\n"
        "  postgres:\n"
        "    password: super-secret\n"
        "services:\n"
        "  enabled: [gcs]\n"
        "  catalog:\n"
        "    gcs:\n"
        "      displayName: Local Buckets\n",
        encoding="utf-8",
    )
    config = _config(tmp_path)

    runtime.create(config)

    run = client.containers.run_calls[0]
    mounted = run["volumes"][str(config_path.resolve())]
    assert mounted == {"bind": CONFIG_MOUNT_DESTINATION, "mode": "ro"}
    assert "LOCALCLOUD_CONFIG" not in run["environment"]
    # project/user are request-scoped, not container-scoped, so they are never
    # frozen into the container even though the mounted file carries them.
    assert "LOCALCLOUD_PROJECT" not in run["environment"]
    # services.enabled IS container-scoped (it picks which emulators the
    # process starts), so the resolved list is forwarded like every other
    # host.*-tier setting (memory, image, tls) and participates in the
    # config hash that decides whether a change requires recreation.
    assert run["environment"]["LOCALCLOUD_SERVICES"] == "gcs"
    assert SERVICES_LABEL not in run["labels"]
    assert "super-secret" not in run["labels"][CONFIG_LABEL]
    assert "Local Buckets" not in run["labels"][CONFIG_LABEL]

    original_hash = config.config_hash
    config_path.write_text(
        config_path.read_text(encoding="utf-8").replace(
            "super-secret", "changed-secret"
        ),
        encoding="utf-8",
    )
    assert _config(tmp_path).config_hash == original_hash


def test_seed_files_are_never_mounted(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    seed = tmp_path / "custom-seed.yaml"
    seed.write_text("services: {}\n", encoding="utf-8")
    (tmp_path / "localcloud.yaml").write_text(
        "host:\n  seed: custom-seed.yaml\n",
        encoding="utf-8",
    )
    config = _config(tmp_path)

    runtime.create(config)

    volumes = client.containers.run_calls[0]["volumes"]
    assert str(seed.resolve()) not in volumes
    assert not any(mount["bind"] == LEGACY_SEED_MOUNT_DESTINATION for mount in volumes.values())



def test_discovers_legacy_seed_mount_for_safe_replacement(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path)
    created = runtime.create(config)
    container = client.containers.get(created.container_id)
    container.attrs["Mounts"].append({
        "Type": "bind", "Source": str(tmp_path / "seed.yaml"),
        "Destination": LEGACY_SEED_MOUNT_DESTINATION, "RW": False,
    })
    assert runtime.resolve(config).legacy_seed_mount is True


def test_port_probe_rejects_occupied_tcp_port() -> None:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen()
        port = int(listener.getsockname()[1])

        assert runtime_module._port_is_free(port) is False
        assert runtime_module._port_is_free(port, host_ip="") is False


@pytest.mark.parametrize("local_only", [False, True])
@pytest.mark.parametrize("canonical_free", [False, True])
def test_port_bind_address_reaches_probes_sdk_and_command(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    local_only: bool,
    canonical_free: bool,
) -> None:
    from docker.types import HostConfig

    runtime = DockerRuntime(client=Client())
    config = replace(
        _write_config(tmp_path, "host:\n  transparent_network: true\ntls:\n  enabled: true\n"),
        local_only=local_only,
    )
    host_ip = "127.0.0.1" if local_only else ""
    probes = []

    def port_is_free(port: int, kind: int, address: str) -> bool:
        probes.append((port, kind, address))
        return canonical_free or port != 5380

    monkeypatch.setattr(runtime_module, "_port_is_free", port_is_free)
    plan = runtime.plan_run(config, object())
    host_config = HostConfig(version="1.44", port_bindings=plan.run_kwargs()["ports"])
    gateway_port = 5380 if canonical_free else 5508

    assert host_config["PortBindings"]["5380/tcp"] == [
        {"HostIp": host_ip, "HostPort": str(gateway_port)},
        {"HostIp": host_ip, "HostPort": "80"},
    ]
    assert all(
        binding["HostIp"] == host_ip
        for bindings in host_config["PortBindings"].values()
        for binding in bindings
    )
    assert probes and all(address == host_ip for _, _, address in probes)
    assert (53, socket.SOCK_DGRAM, host_ip) in probes
    prefix = "127.0.0.1:" if local_only else ""
    if canonical_free:
        assert f"-p {prefix}5380-5405:5380-5405/tcp" in plan.command()
    else:
        # Off the canonical ports only the ports enabled services use are published.
        fallback = runtime_module._fallback_tcp_ports(config)
        assert {spec for spec in plan.ports if spec.endswith("/tcp")} == {
            f"{port}/tcp" for port in fallback
        }
        assert f"-p {prefix}5508-5511:5380-5383/tcp" in plan.command()
    assert f"-p {prefix}53:5410/udp" in plan.command()
    assert f"-p {prefix}80:5380/tcp" in plan.command()
    assert f"-p {prefix}443:5381/tcp" in plan.command()
    if canonical_free:
        assert dict(plan.ports) == runtime_module._canonical_port_bindings(config)


@pytest.mark.parametrize("local_only", [False, True])
@pytest.mark.parametrize("addresses", [("",), ("0.0.0.0",), ("0.0.0.0", "::"), ("127.0.0.1",)])
def test_canonical_ports_match_selected_host_address(
    tmp_path: Path, local_only: bool, addresses: tuple[str, ...]
) -> None:
    runtime = DockerRuntime(client=Client())
    config = _config(tmp_path, local_only=local_only)
    published = {
        f"{port}/tcp": tuple((address, port) for address in addresses)
        for port in range(5380, 5406)
    }
    assert runtime.has_canonical_ports(config, SimpleNamespace(published_ports=published, mysql_port=5406)) is (
        (addresses == ("127.0.0.1",)) == local_only
    )


def test_inspected_ipv6_binding_formats_a_copyable_publish_argument() -> None:
    assert runtime_module._format_port_args({"5380/tcp": (("::", 5380),)}) == [
        "-p", "[::]:5380:5380/tcp"
    ]


def test_canonical_and_fallback_port_contract_is_exact() -> None:
    assert runtime_module._BASE_TCP_PORTS == tuple(range(5380, 5406))
    assert runtime_module._DEDICATED_TLS_PORTS == (5392, 5393, 5394)
    assert runtime_module._DNS_PORT == 5410
    assert runtime_module._FALLBACK_TCP_PORT_RANGES == (
        range(5508, 5540),
        range(5821, 5841),
        range(5322, 5343),
    )
    assert "5406/tcp" not in runtime_module._IMAGE_PORT_CAPABILITIES
    assert "5407/tcp" not in runtime_module._IMAGE_PORT_CAPABILITIES


def test_canonical_layout_rejects_extra_child_port_publication(
    tmp_path: Path,
) -> None:
    runtime = DockerRuntime(client=Client())
    config = _config(tmp_path)
    published = runtime_module._canonical_port_bindings(config)

    assert runtime.has_canonical_ports(
        config,
        SimpleNamespace(published_ports=published, mysql_port=5406),
    )

    with_child = {**published, "5406/tcp": (("127.0.0.1", 5406),)}
    assert not runtime.has_canonical_ports(
        config,
        SimpleNamespace(published_ports=with_child, mysql_port=5406),
    )
    # A MySQL companion left on an alternative port is not canonical either.
    assert not runtime.has_canonical_ports(
        config,
        SimpleNamespace(published_ports=published, mysql_port=5534),
    )


def test_canonical_layout_requires_exact_transparent_aliases(tmp_path: Path) -> None:
    runtime = DockerRuntime(client=Client())
    config = _write_config(
        tmp_path,
        "host:\n  transparent_network: true\n"
        "tls:\n  enabled: true\n",
    )
    published = runtime_module._canonical_port_bindings(config)

    assert runtime.has_canonical_ports(
        config,
        SimpleNamespace(published_ports=published, mysql_port=5406),
    )
    reversed_aliases = {**published}
    reversed_aliases["5380/tcp"] = tuple(reversed(published["5380/tcp"]))
    reversed_aliases["5381/tcp"] = tuple(reversed(published["5381/tcp"]))
    assert runtime.has_canonical_ports(
        config,
        SimpleNamespace(published_ports=reversed_aliases, mysql_port=5406),
    )
    missing_dns = {**published}
    missing_dns.pop("5410/udp")
    assert not runtime.has_canonical_ports(
        config,
        SimpleNamespace(published_ports=missing_dns, mysql_port=5406),
    )


def test_occupied_canonical_port_selects_exact_alternative_complete_set(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runtime = DockerRuntime(client=Client())
    config = _config(tmp_path)
    monkeypatch.setattr(
        runtime_module,
        "_port_is_free",
        lambda port, *_args: port != 5380,
    )

    bindings = runtime._port_bindings(config)
    fallback = runtime_module._fallback_tcp_ports(config)

    assert len(fallback) < len(range(5380, 5406))
    assert bindings == {
        f"{container_port}/tcp": (("", 5508 + offset),)
        for offset, container_port in enumerate(fallback)
    }

    plan = runtime.plan_run(config, object())
    assert plan.alternative_port_mappings() == (
        *(
            ("", 5508 + offset, container_port, "tcp")
            for offset, container_port in enumerate(fallback)
        ),
        ("", 5508 + len(fallback), 5406, "tcp"),
    )


def test_alternative_port_selection_skips_incomplete_blocks(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runtime = DockerRuntime(client=Client())
    config = _config(tmp_path)
    occupied = {5380, 5510}
    monkeypatch.setattr(
        runtime_module,
        "_port_is_free",
        lambda port, *_args: port not in occupied,
    )

    bindings = runtime._port_bindings(config)
    fallback = runtime_module._fallback_tcp_ports(config)

    assert bindings["5380/tcp"] == (("", 5511),)
    assert bindings[f"{fallback[-1]}/tcp"] == (("", 5511 + len(fallback) - 1),)
    assert "5405/tcp" not in bindings
    assert "5406/tcp" not in bindings


@pytest.mark.parametrize(
    ("occupied_ranges", "expected_start"),
    [
        ((range(5508, 5540),), 5821),
        ((range(5508, 5540), range(5821, 5841)), 5322),
    ],
)
def test_alternative_port_selection_spills_to_next_ordered_range(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    occupied_ranges: tuple[range, ...],
    expected_start: int,
) -> None:
    runtime = DockerRuntime(client=Client())
    config = _config(tmp_path)
    monkeypatch.setattr(runtime_module, "_BASE_TCP_PORTS", tuple(range(5380, 5391)))
    occupied = {5380}
    for occupied_range in occupied_ranges:
        occupied.update(occupied_range)
    monkeypatch.setattr(
        runtime_module,
        "_port_is_free",
        lambda port, *_args: port not in occupied,
    )

    bindings = runtime._port_bindings(config)
    fallback = runtime_module._fallback_tcp_ports(config)

    assert bindings["5380/tcp"] == (("", expected_start),)
    assert bindings["5390/tcp"] == (("", expected_start + fallback.index(5390)),)
    assert "5406/tcp" not in bindings


def test_alternative_port_selection_never_leaves_allowlisted_ranges(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runtime = DockerRuntime(client=Client())
    config = _config(tmp_path)
    occupied = {5380}
    for allowed_range in runtime_module._FALLBACK_TCP_PORT_RANGES:
        occupied.update(allowed_range)
    monkeypatch.setattr(
        runtime_module,
        "_port_is_free",
        lambda port, *_args: port not in occupied,
    )

    with pytest.raises(HostError) as caught:
        runtime._port_bindings(config)

    assert caught.value.code == "alternative_ports_unavailable"
    assert caught.value.details["ranges"] == [
        [5508, 5539],
        [5821, 5840],
        [5322, 5342],
    ]


def test_tls_alternative_mapping_uses_one_complete_allowlisted_block(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runtime = DockerRuntime(client=Client())
    config = _write_config(tmp_path, "tls:\n  enabled: true\n")
    monkeypatch.setattr(
        runtime_module,
        "_port_is_free",
        lambda port, *_args: port != 5380,
    )

    bindings = runtime._port_bindings(config)
    fallback = runtime_module._fallback_tcp_ports(config)

    assert len(bindings) == len(fallback)
    assert "5381/tcp" in bindings
    assert "5406/tcp" not in bindings
    assert "5407/tcp" not in bindings
    assert bindings["5380/tcp"] == (("", 5508),)
    assert bindings[f"{fallback[-1]}/tcp"] == (("", 5508 + len(fallback) - 1),)


def test_alternative_mapping_displays_every_binding_when_one_is_numerically_canonical(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runtime = DockerRuntime(client=Client())
    config = _write_config(
        tmp_path,
        "tls:\n  enabled: true\n  port: 5521\n",
    )
    monkeypatch.setattr(
        runtime_module,
        "_port_is_free",
        lambda port, *_args: port != 5380,
    )

    plan = runtime.plan_run(config, object())
    mappings = plan.alternative_port_mappings()

    # The used ports (the custom TLS port last) and the MySQL companion's port.
    fallback = runtime_module._fallback_tcp_ports(config)
    assert fallback[-1] == 5521
    assert len(mappings) == len(fallback) + 1
    assert ("", 5521, 5521, "tcp") in mappings


def test_tls_bindings_use_configured_gateway_and_dedicated_ports(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, _client = ready_runtime
    config = _write_config(
        tmp_path,
        "tls:\n  enabled: true\n  port: 25443\n",
    )

    bindings = runtime._port_bindings(config)

    assert "5410/udp" not in bindings
    assert bindings["25443/tcp"] == (("", 25443),)
    for port in (5392, 5393, 5394):
        assert bindings[f"{port}/tcp"] == (("", port),)


def test_transparent_network_adds_aliases_without_replacing_standard_bindings(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _write_config(
        tmp_path,
        "host:\n  transparent_network: true\n"
        "tls:\n  enabled: true\n  port: 25443\n",
    )

    bindings = runtime._port_bindings(config)

    assert bindings["5380/tcp"] == (
        ("", 5380),
        ("", 80),
    )
    assert bindings["25443/tcp"] == (
        ("", 25443),
        ("", 443),
    )
    assert bindings["5410/udp"] == (("", 53),)

    run_ports = runtime.plan_run(
        config,
        client.images.get(config.image),
    ).run_kwargs()["ports"]
    assert run_ports["5380/tcp"] == [
        ("", 5380),
        ("", 80),
    ]


@pytest.mark.parametrize("occupied_port", [53, 80, 443])
def test_transparent_network_rejects_fixed_host_port_collisions(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    occupied_port: int,
) -> None:
    runtime = DockerRuntime(client=Client())
    config = _write_config(
        tmp_path,
        "host:\n  transparent_network: true\n"
        "tls:\n  enabled: true\n",
    )
    monkeypatch.setattr(
        runtime_module,
        "_port_is_free",
        lambda port, *_args: port != occupied_port,
    )

    with pytest.raises(HostError) as caught:
        runtime._port_bindings(config)

    assert caught.value.code == "transparent_port_unavailable"
    assert caught.value.details["port"] == occupied_port


def test_plan_run_reclaims_ports_owned_by_replaced_runtime(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = Client()
    runtime = DockerRuntime(client=client)
    config = _config(tmp_path)
    published = runtime_module._canonical_port_bindings(config)
    replacing = SimpleNamespace(published_ports=published, endpoint_map={})
    client.containers.add(
        Resource(
            "localcloud-mysql",
            {
                MANAGED_LABEL: "true",
                VOLUME_NAME_LABEL: config.data_volume,
                "localcloud.managed": "true",
                "com.localcloud.service": "cloudsql-mysql",
            },
            ports={"3306/tcp": [{"HostIp": "", "HostPort": "5406"}]},
        )
    )
    monkeypatch.setattr(runtime_module, "_port_is_free", lambda *_args: False)

    plan = runtime.plan_run(config, object(), replacing=replacing)

    assert dict(plan.ports) == published
    assert plan.mysql_ports == (5406, 5406)
    assert plan.alternative_port_mappings() == ()

def test_unexpected_image_metadata_warns_by_default(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    image = client.images.get(DEFAULT_IMAGE)
    exposed = dict(image.attrs["Config"]["ExposedPorts"])
    exposed["5999/tcp"] = {}
    client.images.add(DEFAULT_IMAGE, Image(image.id, exposed=exposed))

    class Observer:
        def __init__(self) -> None:
            self.messages: list[str] = []

        def warning(self, message: str) -> None:
            self.messages.append(message)

    observer = Observer()
    runtime.preflight_create(_config(tmp_path), observer=observer)

    assert observer.messages
    assert "5999/tcp" in observer.messages[0]


def test_taken_mysql_port_moves_the_whole_set_to_one_alternative_block(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runtime = DockerRuntime(client=Client())
    config = _config(tmp_path)
    assert config.mysql_port == 5406
    monkeypatch.setattr(
        runtime_module, "_port_is_free", lambda port, *args, **kwargs: port != 5406
    )

    plan = runtime.plan_run(config, object())
    mysql_host_port = 5508 + len(runtime_module._fallback_tcp_ports(config))

    assert plan.ports["5380/tcp"] == (("", 5508),)
    assert plan.mysql_ports == (5406, mysql_host_port)
    assert plan.environment["LOCALCLOUD_MYSQL_PORT"] == str(mysql_host_port)
    assert ("", mysql_host_port, 5406, "tcp") in plan.alternative_port_mappings()


def test_mysql_port_is_not_planned_without_cloud_sql_mysql(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runtime = DockerRuntime(client=Client())
    config = _config(tmp_path, services=["gcs", "pubsub"])
    monkeypatch.setattr(
        runtime_module, "_port_is_free", lambda port, *args, **kwargs: port != 5406
    )

    plan = runtime.plan_run(config, object())

    assert config.mysql_port is None
    assert plan.mysql_ports is None
    assert plan.alternative_port_mappings() == ()
    assert "LOCALCLOUD_MYSQL_PORT" not in plan.environment


def test_pinned_mysql_port_stays_put_when_the_set_moves(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runtime = DockerRuntime(client=Client())
    config = _write_config(
        tmp_path,
        "host:\n  environment:\n    LOCALCLOUD_MYSQL_PORT: '6000'\n",
    )
    monkeypatch.setattr(
        runtime_module, "_port_is_free", lambda port, *args, **kwargs: port not in {5380, 6000}
    )

    class Observer:
        def __init__(self) -> None:
            self.messages: list[str] = []

        def warning(self, message: str) -> None:
            self.messages.append(message)

    observer = Observer()
    runtime.preflight_create(config, observer=observer)
    plan = runtime.plan_run(config, object())

    assert plan.ports["5380/tcp"] == (("", 5508),)
    assert plan.mysql_ports == (6000, 6000)
    assert plan.environment["LOCALCLOUD_MYSQL_PORT"] == "6000"
    assert ("", 6000, 6000, "tcp") in plan.alternative_port_mappings()
    assert any("6000/tcp, set by LOCALCLOUD_MYSQL_PORT" in m for m in observer.messages)


def test_mysql_companion_address_is_found_by_its_labels(tmp_path: Path) -> None:
    client = Client()
    runtime = DockerRuntime(client=client)
    config = _config(tmp_path)
    client.containers.add(
        Resource(
            "localcloud-mysql",
            {
                VOLUME_NAME_LABEL: config.data_volume,
                "com.localcloud.service": "cloudsql-mysql",
            },
            ports={"3306/tcp": [{"HostIp": "127.0.0.1", "HostPort": "5406"}]},
        )
    )

    assert runtime.mysql_companion_host_ip(config) == "127.0.0.1"
    assert runtime.mysql_companion_host_ip(_config(tmp_path, data_volume="other")) is None


def test_off_canonical_runtimes_publish_only_used_ports_so_four_fit(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runtime = DockerRuntime(client=Client())
    config = _config(tmp_path)
    size = runtime_module._fallback_block_size(config)
    # A second off-canonical runtime takes the block after the first one.
    occupied = {5380, *range(5508, 5508 + size)}
    monkeypatch.setattr(
        runtime_module, "_port_is_free", lambda port, *args, **kwargs: port not in occupied
    )

    plan = runtime.plan_run(config, object())

    assert plan.ports["5380/tcp"] == (("", 5508 + size),)
    assert sum(len(r) // size for r in runtime_module._FALLBACK_TCP_PORT_RANGES) >= 4


def test_configured_port_range_is_used_even_when_canonical_ports_are_free(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runtime = DockerRuntime(client=Client())
    config = _config(tmp_path, port_range="6000-6099")
    monkeypatch.setattr(runtime_module, "_port_is_free", lambda *args, **kwargs: True)

    plan = runtime.plan_run(config, object())
    fallback = runtime_module._fallback_tcp_ports(config)

    assert dict(plan.ports) == {
        f"{port}/tcp": (("", 6000 + offset),) for offset, port in enumerate(fallback)
    }
    assert plan.mysql_ports == (5406, 6000 + len(fallback))
    record = SimpleNamespace(published_ports=dict(plan.ports), mysql_port=plan.mysql_ports[1])
    assert runtime.has_canonical_ports(config, record)
    assert not runtime.has_canonical_ports(
        config,
        SimpleNamespace(
            published_ports=runtime_module._canonical_port_bindings(config),
            mysql_port=5406,
        ),
    )


def test_configured_port_range_without_room_fails_with_that_range(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runtime = DockerRuntime(client=Client())
    config = _config(tmp_path, port_range="6000-6004")
    monkeypatch.setattr(runtime_module, "_port_is_free", lambda *args, **kwargs: True)

    with pytest.raises(HostError) as caught:
        runtime.plan_run(config, object())

    assert caught.value.code == "alternative_ports_unavailable"
    assert caught.value.details["ranges"] == [[6000, 6004]]
    assert caught.value.message.endswith("needed in 6000-6004")
    diagnostics = runtime.port_diagnostics(config=config)
    assert diagnostics["status"] == "conflict"
    assert diagnostics["configured_range"] == [6000, 6004]


def test_removing_a_running_runtime_stops_it_gracefully_first(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path)
    created = runtime.create(config)
    parent = client.containers.values[created.name]

    previews = runtime.preview_remove_commands(
        config, created, remove_volume=False, remove_network=False
    )
    runtime.remove(config, created, remove_volume=False, remove_network=False)

    assert previews[0] == f"docker stop -t 30 {created.name}"
    assert parent.stopped == 1
    assert parent.removed == [{"force": True, "v": True}]


def test_port_diagnostics_include_the_mysql_port(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runtime = DockerRuntime(client=Client())
    monkeypatch.setattr(
        runtime_module, "_port_is_free", lambda port, *args, **kwargs: port != 5406
    )

    diagnostics = runtime.port_diagnostics(local_only=False, mysql_port=5406)

    assert diagnostics["canonical_ports"][-1] == 5406
    assert diagnostics["occupied_ports"] == [{"port": 5406, "status": "in_use"}]
    assert diagnostics["alternative_range"] == list(range(5508, 5535))


def test_strict_unexpected_image_metadata_fails_preflight(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    image = client.images.get(DEFAULT_IMAGE)
    exposed = dict(image.attrs["Config"]["ExposedPorts"])
    exposed["5999/tcp"] = {}
    client.images.add(DEFAULT_IMAGE, Image(image.id, exposed=exposed))

    with pytest.raises(HostError) as caught:
        runtime.preflight_create(
            _config(tmp_path, strict_port_validation=True),
        )

    assert caught.value.code == "image_port_metadata_mismatch"


@pytest.mark.parametrize("missing_port", ["5382/tcp", "5410/udp", "5392/tcp"])
def test_missing_required_image_port_capability_always_fails_preflight(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
    missing_port: str,
) -> None:
    runtime, client = ready_runtime
    image = client.images.get(DEFAULT_IMAGE)
    exposed = dict(image.attrs["Config"]["ExposedPorts"])
    exposed.pop(missing_port)
    client.images.add(DEFAULT_IMAGE, Image(image.id, exposed=exposed))

    with pytest.raises(HostError) as caught:
        runtime.preflight_create(_config(tmp_path))

    assert caught.value.code == "invalid_image"
    assert caught.value.details["missing"] == [missing_port]


def test_missing_gateway_image_metadata_always_fails_preflight(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    image = client.images.get(DEFAULT_IMAGE)
    exposed = dict(image.attrs["Config"]["ExposedPorts"])
    exposed.pop("5380/tcp")
    client.images.add(DEFAULT_IMAGE, Image(image.id, exposed=exposed))

    with pytest.raises(HostError) as caught:
        runtime.preflight_create(_config(tmp_path))

    assert caught.value.code == "invalid_image"

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
    config = _write_config(
        tmp_path, "host:\n  image: registry.example/localcloud:legacy\n"
    )

    with pytest.raises(HostError) as caught:
        runtime.create(config)

    assert caught.value.code == "managed_image_capability_missing"
    assert client.containers.run_calls == []
    assert client.networks.created == []
    assert client.volumes.created == []

def test_managed_create_rejects_image_without_config_schema_capability(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    image_name = "registry.example/localcloud:old-schema"
    image = Image("sha256:old-schema")
    image.attrs["Config"]["Labels"].pop(CONFIG_SCHEMA_LABEL)
    client.images.add(image_name, image)
    config = _write_config(
        tmp_path, f"host:\n  image: {image_name}\n"
    )

    with pytest.raises(HostError) as caught:
        runtime.create(config)

    assert caught.value.code == "managed_image_capability_missing"
    assert CONFIG_SCHEMA_LABEL in caught.value.details["capabilities"]
    assert client.containers.run_calls == []


def test_zero_config_create_accepts_runtime_image_without_config_schema_label(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    image = client.images.get(DEFAULT_IMAGE)
    image.attrs["Config"]["Labels"].pop(CONFIG_SCHEMA_LABEL)

    record = runtime.create(_config(tmp_path))

    assert record.state == "running"
    assert client.containers.run_calls


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


def test_resolve_reports_https_connect_url_when_tls_enabled(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    _add_external(
        client,
        ports={
            "5380/tcp": [{"HostIp": "127.0.0.1", "HostPort": "49080"}],
            "5381/tcp": [{"HostIp": "127.0.0.1", "HostPort": "49443"}],
        },
        environment={"LOCALCLOUD_TLS_ENABLED": "true"},
    )

    record = runtime.resolve(_config(tmp_path))

    assert record is not None
    assert record.url == "http://127.0.0.1:49080"
    assert record.connect_url == "https://127.0.0.1:49443"


def test_resolve_connect_url_matches_http_url_when_tls_disabled(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    _add_external(
        client,
        ports={
            "5380/tcp": [{"HostIp": "127.0.0.1", "HostPort": "49080"}],
            "5381/tcp": [{"HostIp": "127.0.0.1", "HostPort": "49443"}],
        },
        environment={"LOCALCLOUD_TLS_ENABLED": "false"},
    )

    record = runtime.resolve(_config(tmp_path))

    assert record is not None
    assert record.url == "http://127.0.0.1:49080"
    assert record.connect_url == "http://127.0.0.1:49080"


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


def test_start_waits_without_restarting_and_returns_refreshed_health(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runtime, client = ready_runtime
    external = _add_external(client)
    external.attrs["State"]["Health"]["Status"] = "starting"
    config = _config(tmp_path)
    record = runtime.resolve(config)
    assert record is not None
    assert record.health == "starting"

    def mark_ready(
        _url: str,
        *,
        deadline: float,
        container: Resource | None = None,
        **_kwargs: Any,
    ) -> dict[str, str]:
        assert 0 < deadline - runtime_module.time.monotonic() <= 60.0
        assert container is external
        external.attrs["State"]["Health"]["Status"] = "healthy"
        return {"status": "healthy"}

    monkeypatch.setattr(
        DockerRuntime,
        "wait_ready",
        staticmethod(mark_ready),
    )

    started = runtime.start(
        config,
        record,
        readiness_deadline=runtime_module.time.monotonic() + 60.0,
    )

    assert started.container_id == external.id
    assert started.health == "healthy"
    assert external.started == 0
    assert external.restarted == 0


def test_start_rejects_container_replacement_during_readiness(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runtime, client = ready_runtime
    selected = _add_external(client)
    config = _config(tmp_path)
    record = runtime.resolve(config)
    assert record is not None

    def replace_selected(
        _url: str,
        *,
        deadline: float,
        container: Resource | None = None,
        **_kwargs: Any,
    ) -> dict[str, str]:
        assert deadline > runtime_module.time.monotonic()
        assert container is selected
        client.containers.values.pop(selected.name)
        _add_external(client, name="replacement-localcloud")
        return {"status": "healthy"}

    monkeypatch.setattr(
        DockerRuntime,
        "wait_ready",
        staticmethod(replace_selected),
    )

    with pytest.raises(HostError) as caught:
        runtime.start(
            config,
            record,
            readiness_deadline=runtime_module.time.monotonic() + 60.0,
        )

    assert caught.value.code == "container_changed"
    assert caught.value.details["expected_container_id"] == selected.id
    assert caught.value.details["actual_container_id"] == (
        "id-replacement-localcloud"
    )
    assert selected.started == 0
    assert selected.restarted == 0


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
        image="docker.io/agentcloud/localcloud:latest",
    )
    resolved = runtime.resolve(_config(tmp_path))
    assert resolved is not None
    assert resolved.container_id == external.id

    client.images.add(
        "mirror.example/localcloud:pinned",
        client.images.get(DEFAULT_IMAGE),
    )
    config = _write_config(
        tmp_path, "host:\n  image: mirror.example/localcloud:pinned\n"
    )
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
    assert client.images.gets == [DEFAULT_IMAGE]


def test_managed_runtime_remains_resolvable_for_image_reconfiguration(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    original = _config(tmp_path)
    created = runtime.create(original)
    replacement_image = "registry.example/localcloud:replacement"
    client.images.add(replacement_image, Image("sha256:replacement"))
    replacement = _write_config(
        tmp_path, f"host:\n  image: {replacement_image}\n"
    )

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
    child_no_hash = client.containers.add(
        Resource(
            "child-no-hash",
            {
                MANAGED_LABEL: "true",
                VOLUME_NAME_LABEL: config.data_volume,
                "localcloud.managed": "true",
            },
        )
    )
    record = runtime.resolve(config)
    assert record is not None

    runtime.remove(config, record, remove_volume=False)

    assert new_child.removed == [{"force": True, "v": True}]
    assert legacy_child.removed == [{"force": True, "v": True}]
    assert child_no_hash.removed == [{"force": True, "v": True}]
    assert client.volumes.values[config.data_volume].removed == []


def test_stop_keeps_managed_children_for_the_server_to_resume(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path)
    created = runtime.create(config)
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
    record = runtime.resolve(config)
    assert record is not None

    runtime.stop(config, record)

    assert new_child.removed == []
    assert new_child.stopped == 1


def test_replacing_a_runtime_removes_only_its_mysql_companion(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path)
    created = runtime.create(config)
    child_labels = {
        MANAGED_LABEL: "true",
        VOLUME_NAME_LABEL: config.data_volume,
        CONFIG_HASH_LABEL: config.config_hash,
        "localcloud.managed": "true",
    }
    cluster = client.containers.add(
        Resource("dataproc-cluster", {**child_labels, "com.localcloud.service": "dataproc"})
    )
    mysql = client.containers.add(
        Resource("mysql", {**child_labels, "com.localcloud.service": "cloudsql-mysql"})
    )

    previews = runtime.preview_remove_commands(
        config, created, remove_volume=False, remove_network=False
    )
    runtime.remove(config, created, remove_volume=False, remove_network=False)

    assert "docker rm -f -v mysql" in previews
    assert not any("dataproc-cluster" in command for command in previews)
    assert mysql.removed == [{"force": True, "v": True}]
    assert cluster.removed == []


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


def test_legacy_compatible_image_can_attach_and_restart_but_not_create(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    legacy_name = "registry.example/localcloud:legacy"
    client.images.add(legacy_name, Image("sha256:legacy", qualified=False))
    config = _write_config(tmp_path, f"host:\n  image: {legacy_name}\n")
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
            "http://127.0.0.1:5380",
            deadline=1.0,
            container=container,
        )

    assert caught.value.code == "container_start_failed"
    assert caught.value.details["state"] == "exited"


def test_wait_ready_streams_logs_to_observer(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    container = Resource("localcloud", state="running")

    class MockObserver:
        def __init__(self) -> None:
            self.emitted: list[str] = []

        def runtime_logs(self, logs: str) -> None:
            self.emitted.append(logs)

    observer = MockObserver()
    class MockResponse:
        status_code = 200
        def json(self) -> dict[str, str]:
            return {"status": "healthy"}

    monkeypatch.setattr(
        runtime_module.httpx,
        "get",
        lambda _url, **_kwargs: MockResponse(),
    )
    result = DockerRuntime.wait_ready(
        "http://127.0.0.1:5380",
        deadline=runtime_module.time.monotonic() + 5.0,
        container=container,
        observer=observer,
    )

    assert result == {"status": "healthy"}
    assert observer.emitted
    assert "container log" in observer.emitted[0]

class _GrowingLog:
    """A container whose log grows between reads and honours `since`/`tail`."""

    def __init__(self, started_at: str = "2026-10-06T04:18:00.000000000Z") -> None:
        self.lines: list[str] = []
        self.calls: list[dict[str, Any]] = []
        self.attrs = {"State": {"StartedAt": started_at}}

    def write(self, *stamped: str) -> None:
        self.lines.extend(stamped)

    def logs(self, **kwargs: Any) -> bytes:
        self.calls.append(kwargs)
        since = kwargs.get("since")
        selected = [
            line
            for line in self.lines
            if since is None
            or runtime_module._timestamp_ns(line) / 1_000_000_000 >= since
        ]
        return "".join(f"{line}\n" for line in selected[-kwargs["tail"]:]).encode()


def _stamp(second: int, nanos: int, text: str) -> str:
    return f"2026-10-06T04:18:{second:02d}.{nanos:09d}Z {text}"


def test_log_cursor_returns_every_line_of_a_burst_exactly_once() -> None:
    container = _GrowingLog()
    cursor = LogCursor.from_start(container)
    container.write(*(_stamp(1, index, f"line {index}") for index in range(300)))

    first = cursor.read(container).splitlines()
    container.write(_stamp(1, 300, "line 300"), _stamp(2, 0, "line 301"))
    second = cursor.read(container).splitlines()

    assert [line.split(" ", 1)[1] for line in first + second] == [
        f"line {index}" for index in range(302)
    ]
    assert cursor.read(container) == ""


def test_log_cursor_skips_earlier_runs_of_a_restarted_container() -> None:
    container = _GrowingLog(started_at="2026-10-06T04:18:05.5Z")
    container.write(_stamp(1, 0, "previous run"), _stamp(6, 0, "this run"))

    assert LogCursor.from_start(container).read(container).splitlines() == [
        _stamp(6, 0, "this run")
    ]


def test_log_cursor_without_a_start_shows_only_recent_lines() -> None:
    container = _GrowingLog()
    container.write(*(_stamp(1, index, f"line {index}") for index in range(50)))

    lines = LogCursor(first_tail=12).read(container).splitlines()

    assert len(lines) == 12
    assert lines[-1].endswith("line 49")


def test_wait_ready_bounds_requests_and_sleep_by_absolute_deadline(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    now = [0.0]
    request_timeouts: list[float] = []
    sleeps: list[float] = []
    monkeypatch.setattr(
        runtime_module.time,
        "monotonic",
        lambda: now[0],
    )

    def fail_after_timeout(
        _url: str,
        *,
        timeout: float,
    ) -> None:
        request_timeouts.append(timeout)
        now[0] += timeout
        raise TimeoutError("health request timed out")

    def sleep(seconds: float) -> None:
        sleeps.append(seconds)
        now[0] += seconds

    monkeypatch.setattr(runtime_module.httpx, "get", fail_after_timeout)
    monkeypatch.setattr(runtime_module.time, "sleep", sleep)

    with pytest.raises(HostError) as caught:
        DockerRuntime.wait_ready(
            "http://127.0.0.1:5380",
            deadline=5.0,
        )

    assert caught.value.code == "health_timeout"
    assert caught.value.details["timeout_seconds"] == 5.0
    assert now[0] == 5.0
    assert request_timeouts == [3.0, 1.0]
    assert sleeps == [1.0]


@pytest.mark.parametrize(
    "url",
    [
        "http://example.com:5380",
        "file:///tmp/socket",
        "http://user:pass@127.0.0.1:5380",
        "http://127.0.0.1:5380?next=example.com",
    ],
)
def test_wait_ready_rejects_nonlocal_or_unsafe_urls(url: str) -> None:
    with pytest.raises(HostError) as caught:
        DockerRuntime.wait_ready(
            url,
            deadline=runtime_module.time.monotonic(),
        )
    assert caught.value.code in {"invalid_endpoint", "nonlocal_endpoint"}


def test_resource_labels_handles_missing_labels_and_reload_failures() -> None:
    class FailingResource:
        def __init__(self):
            self.attrs = {"Labels": None}

        def reload(self):
            raise NotFound("resource deleted out of band")

    resource = FailingResource()
    labels = runtime_module._resource_labels(resource, reload=True)
    assert labels == {}

    class NonDictAttrsResource:
        def __init__(self):
            self.attrs = None

        def reload(self):
            pass

    resource2 = NonDictAttrsResource()
    labels2 = runtime_module._resource_labels(resource2, reload=True)
    assert labels2 == {}


def test_doctor_catches_classify_resource_host_errors(
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime

    class CrashingResource:
        def __init__(self, name: str):
            self.name = name
            self.id = f"id-{name}"
            self.labels = {MANAGED_LABEL: "true"}
            self.status = "running"
            self.attrs = {"Labels": self.labels}

    crashing_volume = CrashingResource("invalid/data-volume-name")
    client.volumes.add(crashing_volume)  # type: ignore[arg-type]

    result = runtime.doctor()

    assert "invalid_ownership" in result
    invalid_entries = [
        item for item in result["invalid_ownership"] if item.get("name") == "invalid/data-volume-name"
    ]
    assert len(invalid_entries) == 1
    assert invalid_entries[0]["kind"] == "volume"


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


def test_cleanup_resources_removes_invalid_ownership(
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    client.networks.add(
        Resource("broken-network", {MANAGED_LABEL: "true"})
    )

    report = runtime.doctor()
    invalid_ownership = report["invalid_ownership"]
    assert invalid_ownership[0]["name"] == "broken-network"

    result = runtime.cleanup_resources(invalid_ownership)

    assert result["removed"] == [{"kind": "network", "name": "broken-network"}]
    assert result["failures"] == []
    assert "broken-network" not in client.networks.values


def test_image_status_reports_local_and_missing(
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    client.images.add("present:latest", Image("sha256:present"))

    assert runtime.image_status("present:latest") == "available locally"
    assert runtime.image_status("absent:latest") == "not available locally"


def test_image_details_reports_local_and_remote(
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    client.images.add("present:latest", Image("sha256:present_qualified_id_1234567890"))

    local_details = runtime.image_details("present:latest")
    assert local_details["location"] == "Local"
    assert local_details["image_id"] == "present_qual"
    assert local_details["sha"] == "sha256:present_qualified_id_1234567890"
    assert local_details["formatted"] == "(Local: ID: present_qual , sha256:present_qualified_id_1234567890)"

    remote_details = runtime.image_details("absent:latest")
    assert remote_details["location"] == "Remote"
    assert remote_details["image_id"] == "not available locally"
    assert remote_details["formatted"] == "(not available locally)"


def test_missing_image_pull_streams_layer_progress_to_observer(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path)
    client.images.by_name.pop(config.image)

    class Api:
        def pull(
            self, image_name: str, *, stream: bool, decode: bool
        ) -> Any:
            assert image_name == config.image
            assert stream is True
            assert decode is True

            def events() -> Any:
                yield {
                    "status": "Downloading",
                    "id": "layer-1234567890",
                    "progressDetail": {"current": 512, "total": 1024},
                }
                yield {
                    "status": "Pull complete",
                    "id": "layer-1234567890",
                    "progressDetail": {},
                }
                client.images.add(config.image, Image("sha256:streamed"))

            return events()

    class Observer:
        def __init__(self) -> None:
            self.updates: list[dict[str, Any]] = []

        def image_pull(self, image: str, **progress: Any) -> None:
            self.updates.append({"image": image, **progress})

    client.api = Api()  # type: ignore[attr-defined]
    observer = Observer()

    image, was_pulled = runtime.preflight_create(config, observer=observer)

    assert was_pulled is True
    assert image.id == "sha256:streamed"
    assert observer.updates[0]["status"] == "Contacting registry"
    assert {
        "image": config.image,
        "status": "Downloading",
        "layer": "layer-1234567890",
        "current": 512,
        "total": 1024,
    } in observer.updates
    assert observer.updates[-1]["status"] == "Pull complete"


def test_streamed_image_pull_error_is_reported_as_invalid_image(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path)
    client.images.by_name.pop(config.image)

    class Api:
        @staticmethod
        def pull(_image_name: str, *, stream: bool, decode: bool) -> Any:
            assert stream is True
            assert decode is True
            return iter(
                [
                    {
                        "error": "pull access denied",
                        "errorDetail": {"message": "registry authentication failed"},
                    }
                ]
            )

    class Observer:
        @staticmethod
        def image_pull(_image: str, **_progress: Any) -> None:
            return None

    client.api = Api()  # type: ignore[attr-defined]

    with pytest.raises(HostError) as caught:
        runtime.preflight_create(config, observer=Observer())

    assert caught.value.code == "invalid_image"
    assert caught.value.message == "Selected LocalCloud image could not be pulled"
    assert caught.value.details["cause"] == "registry authentication failed"
    assert client.images.pulls == []

def test_create_sets_image_status_from_pull(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path)
    record = runtime.create(config)

    assert record.image_status == "available locally"
    assert client.images.pulls == []

    # Now test the pull path by removing the image name.
    client.images.by_name.pop(config.image, None)
    runtime.remove(config, record, remove_volume=True)
    record2 = runtime.create(config)

    assert record2.image_status == "pulled from registry"
    assert client.images.pulls == [config.image]


def test_create_reuses_prepared_image_without_pulling_again(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path)
    client.images.registry_data[config.image] = RegistryData("sha256:newer-digest")
    prepared = runtime.preflight_create(config, pull=True)

    record = runtime.create(config, prepared_image=prepared)

    assert record.image_status == "pulled from registry"
    assert client.images.pulls == [config.image]


def test_create_pulls_when_newer_image_available_on_registry(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path)
    client.images.registry_data[config.image] = RegistryData("sha256:newer-digest")
    record = runtime.create(config, pull=True)

    assert record.image_status == "pulled from registry"
    assert client.images.pulls == [config.image]


def test_create_skips_pull_when_image_is_already_latest(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path)
    local_img = client.images.get(config.image)
    client.images.registry_data[config.image] = RegistryData(local_img.id)
    record = runtime.create(config, pull=True)

    assert record.image_status == "available locally"
    assert client.images.pulls == []


def test_create_with_no_pull_uses_local_image_even_if_newer_remote_exists(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path)
    client.images.registry_data[config.image] = RegistryData("sha256:newer-digest")
    record = runtime.create(config, pull=False)

    assert record.image_status == "available locally"
    assert client.images.pulls == []


def test_is_newer_image_available_logic(
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    local_img = client.images.add("myimage:latest", Image("sha256:local-digest-12345"))

    # When remote matches local id -> False
    client.images.registry_data["myimage:latest"] = RegistryData("sha256:local-digest-12345")
    assert runtime._is_newer_image_available("myimage:latest", local_img) is False

    # When remote differs -> True
    client.images.registry_data["myimage:latest"] = RegistryData("sha256:newer-remote-digest")
    assert runtime._is_newer_image_available("myimage:latest", local_img) is True

    # When image has pinned digest (@sha256:) -> False
    assert runtime._is_newer_image_available("myimage@sha256:pinned", local_img) is False

    # When registry raises error -> False
    def failing_reg_data(_name: str) -> Any:
        raise ConnectionError("offline")

    client.images.get_registry_data = failing_reg_data  # type: ignore[assignment]
    assert runtime._is_newer_image_available("myimage:latest", local_img) is False


def test_preflight_create_pull_failure_raises_host_error(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path)
    client.images.registry_data[config.image] = RegistryData("sha256:newer-digest")

    def failing_pull(_name: str) -> None:
        raise RuntimeError("network down")

    client.images.pull = failing_pull  # type: ignore[assignment]
    with pytest.raises(HostError) as caught:
        runtime.preflight_create(config, pull=True)
    assert caught.value.code == "invalid_image"


def test_preflight_create_non_missing_image_error_does_not_attempt_pull(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path)

    class DaemonError(Exception):
        status_code = 500

    def failing_get(_name: str) -> None:
        raise DaemonError("daemon overloaded")

    client.images.get = failing_get  # type: ignore[assignment]
    with pytest.raises(HostError) as caught:
        runtime.preflight_create(config, pull=False)

    assert caught.value.code == "invalid_image"
    assert client.images.pulls == []


def test_create_reports_port_conflict_when_bind_races_preflight(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path)

    def racing_run(*_args: Any, **_kwargs: Any) -> None:
        raise RuntimeError(
            "driver failed programming external connectivity on endpoint "
            "localcloud: Bind for 0.0.0.0:5380 failed: port is already "
            "allocated"
        )

    client.containers.run = racing_run  # type: ignore[assignment]
    with pytest.raises(HostError) as caught:
        runtime.create(config)

    assert caught.value.code == "port_no_longer_available"


def test_resolve_records_configured_image_id_when_local_image_differs(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path)
    record = runtime.create(config)
    original_image_id = record.image_id
    assert record.configured_image_id == original_image_id

    # Simulate pulling/building a new image with the same tag name locally
    new_image = Image("sha256:newer-image-id")
    client.images.add(config.image, new_image)

    resolved = runtime.resolve(config)
    assert resolved is not None
    assert resolved.image_id == original_image_id
    assert resolved.configured_image_id == "sha256:newer-image-id"
    assert resolved.image_id != resolved.configured_image_id

def test_format_docker_run_produces_valid_command_string() -> None:
    from localcloud_cli.docker_runtime import _format_docker_run

    cmd = _format_docker_run(
        image="agentcloud/localcloud:latest",
        name="localcloud",
        network_name="localcloud-net",
        mem_limit="4g",
        volumes={"localcloud-data": {"bind": "/var/lib/localcloud", "mode": "rw"}},
        ports={
            "5380/tcp": (("127.0.0.1", 5380), ("127.0.0.1", 80)),
            "5382/tcp": ("127.0.0.1", None),
        },
        environment={"LOCALCLOUD_PROJECT": "default"},
        labels={"managed": "true"},
    )
    assert cmd.startswith("docker run -d --name localcloud")
    assert "--network localcloud-net" in cmd
    assert "-m 4g" in cmd
    assert "-v localcloud-data:/var/lib/localcloud" in cmd
    assert "-p 127.0.0.1:5380:5380/tcp" in cmd
    assert "-p 127.0.0.1:80:5380/tcp" in cmd
    assert "-p 127.0.0.1::5382/tcp" in cmd
    assert "-e LOCALCLOUD_PROJECT=default" in cmd
    assert "-l managed=true" in cmd
    assert cmd.endswith("agentcloud/localcloud:latest")


def test_format_docker_run_collapses_contiguous_port_ranges() -> None:
    from localcloud_cli.docker_runtime import _format_docker_run

    ports = {
        f"{port}/tcp": ("127.0.0.1", port) for port in range(5380, 5406)
    }
    ports["5410/tcp"] = ("127.0.0.1", 5410)

    cmd = _format_docker_run(
        image="agentcloud/localcloud:latest",
        name="localcloud",
        network_name="localcloud-net",
        mem_limit="4g",
        volumes=None,
        ports=ports,
        environment=None,
        labels=None,
    )
    assert "-p 127.0.0.1:5380-5405:5380-5405/tcp" in cmd
    assert "-p 127.0.0.1:5410:5410/tcp" in cmd
    assert "5382/tcp" not in cmd
    assert cmd.count("-p ") == 2


def test_docker_runtime_emits_debug_commands_to_observer(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path)

    class DebugObserver:
        def __init__(self) -> None:
            self.debug_messages: list[str] = []
            self.emitted_logs: list[str] = []

        def debug(self, msg: str) -> None:
            self.debug_messages.append(msg)

        def runtime_logs(self, logs: str) -> None:
            self.emitted_logs.append(logs)

    observer = DebugObserver()
    runtime.create(config, observer=observer)
    assert any(
        msg.startswith("Executing Docker SDK containers.run")
        for msg in observer.debug_messages
    )
    assert not any(
        "published ports" in msg.lower()
        for msg in observer.debug_messages
    )
    assert not any(msg.startswith("docker run") for msg in observer.debug_messages)

    observer.debug_messages.clear()
    record = runtime.resolve(config)
    assert record is not None
    container = client.containers.get(record.container_id)
    container.attrs["State"]["Status"] = "exited"
    runtime.start(config, record, observer=observer)
    assert any(
        msg.startswith("Executing: docker start")
        for msg in observer.debug_messages
    )
    assert not any("docker run" in msg for msg in observer.debug_messages)

    observer.debug_messages.clear()
    runtime.restart(config, record, observer=observer)
    assert any(
        msg.startswith("Executing: docker restart -t 30")
        for msg in observer.debug_messages
    )
    assert not any("docker run" in msg for msg in observer.debug_messages)


def test_run_plan_is_shared_by_preview_and_sdk_execution(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path)
    image = client.images.get(config.image)
    plan = runtime.plan_run(config, image)

    runtime.create(
        config,
        prepared_image=(image, False),
        run_plan=plan,
    )

    assert client.containers.run_calls[-1] == {
        "image": plan.image,
        **plan.run_kwargs(),
    }
    assert "-p 5380-5405:5380-5405/tcp" in plan.command()


def test_resolve_falls_back_to_configured_ports_for_stopped_container(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path)
    created = runtime.create(config)
    container = client.containers.get(created.container_id)
    container.attrs["State"]["Status"] = "exited"
    container.attrs["NetworkSettings"]["Ports"] = {}
    container.attrs["HostConfig"] = {
        "PortBindings": {
            "5380/tcp": [
                {"HostIp": "127.0.0.1", "HostPort": "49080"}
            ],
            "5410/udp": [
                {"HostIp": "127.0.0.1", "HostPort": "53"}
            ],
        }
    }

    resolved = runtime.resolve(config)

    assert resolved is not None
    assert resolved.endpoint_map["5380"] == 49080
    assert resolved.published_ports["5410/udp"] == (("127.0.0.1", 53),)


def test_endpoint_map_prefers_tcp_when_protocols_share_container_port(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path)
    created = runtime.create(config)
    container = client.containers.get(created.container_id)
    container.attrs["NetworkSettings"]["Ports"].update(
        {
            "5410/udp": [{"HostIp": "127.0.0.1", "HostPort": "53"}],
            "5410/tcp": [
                {"HostIp": "127.0.0.1", "HostPort": "49093"}
            ],
        }
    )

    resolved = runtime.resolve(config)

    assert resolved is not None
    assert resolved.endpoint_map["5410"] == 49093


def test_endpoint_map_falls_back_to_container_port_when_host_port_is_none() -> None:
    from localcloud_cli.docker_runtime import _endpoint_map

    bindings = {"5380/tcp": (("127.0.0.1", None),)}
    result = _endpoint_map(bindings)
    assert result == {"5380": 5380}


def test_endpoint_map_prefers_standard_binding_over_transparent_alias(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path)
    created = runtime.create(config)
    container = client.containers.get(created.container_id)
    container.attrs["NetworkSettings"]["Ports"]["5380/tcp"] = [
        {"HostIp": "127.0.0.1", "HostPort": "80"},
        {"HostIp": "127.0.0.1", "HostPort": "5380"},
    ]

    resolved = runtime.resolve(config)

    assert resolved is not None
    assert resolved.endpoint_map["5380"] == 5380
    assert resolved.url == "http://127.0.0.1:5380"


def test_local_only_preflight_never_pulls_missing_image(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path, image="missing/localcloud:latest")

    with pytest.raises(HostError) as caught:
        runtime.preflight_create(config, local_only=True)
    assert caught.value.code == "dry_run_image_unavailable"
    assert client.images.pulls == []



def test_create_preview_reuses_existing_matching_target_network(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path, network_name="localcloud-target-network")
    client.networks.add(
        Resource(
            config.network_name,
            {
                MANAGED_LABEL: "true",
                RESOURCE_ROLE_LABEL: "network",
                VOLUME_NAME_LABEL: config.data_volume,
                CONFIG_HASH_LABEL: config.config_hash,
            },
        )
    )
    image, _pulled = runtime.preflight_create(config, local_only=True)
    run_plan = runtime.plan_run(config, image)

    commands = runtime.preview_create_commands(
        config,
        run_plan,
        volume_exists=True,
        network_exists=None,
    )

    assert not any("docker network create" in command for command in commands)
    assert not any("docker network rm" in command for command in commands)


def test_replace_with_remove_network_false_preserves_network_across_config_change(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path)
    created = runtime.create(config)
    original_network = client.networks.values[created.network_name]

    changed = _write_config(tmp_path, "host:\n  memory: 8g\n")
    assert changed.config_hash != config.config_hash

    runtime.remove(config, created, remove_volume=False, remove_network=False)
    recreated = runtime.create(changed)

    # An unrelated config change (memory) must not have torn down and
    # recreated the network: it's the exact same object, never removed.
    assert client.networks.values[recreated.network_name] is original_network
    assert original_network.removed == []
    assert recreated.network_created is False


def test_inspected_run_plan_is_copyable_and_collapses_port_ranges(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path)
    current = runtime.create(config)
    container = client.containers.get(current.container_id)
    container.attrs["HostConfig"] = {
        "Memory": 4 * 1024 * 1024 * 1024,
        "NetworkMode": config.network_name,
    }

    command = runtime.inspect_run_plan(config, current).command()

    assert command.startswith("docker run -d --name localcloud")
    assert "--network localcloud" in command
    assert "-m 4g" in command
    assert "-v localcloud-data:/var/lib/localcloud" in command
    assert "-p 0.0.0.0:5380-5405:5380-5405/tcp" in command
    assert "-e LOCALCLOUD_DOCKER_ACCESS=auto" in command
    assert " -l " not in command
    assert command.endswith(config.image)


def test_resolve_with_missing_managed_network_records_missing_ownership(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path)
    created = runtime.create(config)
    del client.networks.values[created.network_name]

    resolved = runtime.resolve(config)
    assert resolved is not None
    assert resolved.ownership["network"] == "missing"
    assert resolved.ownership["container"] == "managed"
    assert resolved.origin == "managed"


def test_teardown_network_if_owned_preserves_network_when_other_containers_attached(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path)
    created = runtime.create(config)
    network = client.networks.values[created.network_name]
    network.attrs["Containers"] = {
        "other-container-id": {"Name": "companion-app"},
    }

    failures: list[dict[str, Any]] = []
    runtime._teardown_network_if_owned(config, network, failures)
    assert failures == []
    assert network.removed == []


def test_classify_resource_allows_managed_network_with_different_data_volume(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Client],
) -> None:
    runtime, client = ready_runtime
    config = _config(tmp_path)
    created = runtime.create(config)
    network = client.networks.values[created.network_name]

    ownership = runtime._classify_resource(network, "network", "different-data-volume")
    assert ownership == "managed"


def test_unreachable_docker_raises_docker_unavailable_with_a_reason(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    import docker

    import localcloud_cli.host_checks as host_checks

    def refuse(**_kwargs: Any) -> None:
        raise docker.errors.DockerException(
            "Error while fetching server API version: "
            "('Connection aborted.', FileNotFoundError(2, 'No such file or directory'))"
        )

    monkeypatch.setattr(docker, "from_env", refuse)
    monkeypatch.setattr(
        host_checks,
        "default_probe",
        lambda: host_checks.HostProbe(
            environ={"DOCKER_CONTEXT": "colima"},
            home=tmp_path,
            system="Darwin",
            run=lambda _argv, _timeout: None,
            which=lambda name: "/opt/homebrew/bin/colima" if name == "colima" else None,
            applications=(),
        ),
    )

    with pytest.raises(HostError) as caught:
        DockerRuntime()

    error = caught.value
    assert error.code == "docker_unavailable"
    assert error.details["reason"] == "not_running"
    assert "FileNotFoundError" in error.details["cause"]
    assert error.message == (
        "Colima is installed but not running. Start it with 'colima start' or run "
        "'lc doctor --fix'."
    )
    assert [finding["id"] for finding in error.details["setup_findings"]] == [
        "docker_not_running"
    ]
