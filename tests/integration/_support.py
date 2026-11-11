from __future__ import annotations

import io
import os
import tarfile
import uuid
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

import pytest

from localcloud_cli.config import HostPaths, LocalCloudConfig, load_config
from localcloud_cli.constants import DEFAULT_IMAGE
from localcloud_cli.controller import Controller
from localcloud_cli.docker_runtime import (
    VOLUME_NAME_LABEL,
    DockerRuntime,
    RuntimeRecord,
)

# Host ports for throwaway runtimes, away from the canonical 5380-5406 and the
# fallback ranges, so a LocalCloud the developer runs is left alone.
THROWAWAY_PORT_RANGE = "5960-5999"


@contextmanager
def throwaway_runtime(
    tmp_path: Path, yaml: str, *, images: tuple[str, ...]
) -> Iterator[tuple[Controller, DockerRuntime, Callable[..., LocalCloudConfig], HostPaths]]:
    """A controller on its own LOCALCLOUD home, data volume and host ports.

    Yields a function that loads the config for that volume. Every container,
    network and volume made for it is removed afterwards. `images` must be
    local: nothing is pulled."""
    try:
        runtime = DockerRuntime()
        runtime.client.ping()
        for image in images:
            runtime.client.images.get(image)
    except Exception as error:
        pytest.fail(f"docker_prerequisite_unavailable: {error}")
    paths = HostPaths(tmp_path / "home", tmp_path / "home" / "locks")
    volume = f"lc-test-{uuid.uuid4().hex[:8]}"
    (tmp_path / "localcloud.yaml").write_text(yaml, encoding="utf-8")

    def config(**kwargs: Any) -> LocalCloudConfig:
        return load_config(
            directory=tmp_path,
            paths=paths,
            data_volume=volume,
            port_range=THROWAWAY_PORT_RANGE,
            **kwargs,
        )

    try:
        yield Controller(runtime=runtime, paths=paths), runtime, config, paths
    finally:
        client = runtime.client
        selector = {"label": f"{VOLUME_NAME_LABEL}={volume}"}
        for container in client.containers.list(all=True, filters=selector):
            container.remove(force=True, v=True)
        for network in client.networks.list(filters=selector):
            network.remove()
        for item in client.volumes.list(filters={"name": volume}):
            if item.name == volume:
                item.remove(force=True)


def controller_for(
    tmp_path: Path,
) -> tuple[Controller, DockerRuntime, str]:
    image = os.environ.get("LOCALCLOUD_IMAGE", DEFAULT_IMAGE)
    try:
        runtime = DockerRuntime()
        runtime.client.ping()
        runtime.client.images.get(image)
    except Exception as error:
        pytest.fail(f"docker_prerequisite_unavailable: {error}")
    paths = HostPaths(
        tmp_path / "localcloud-home",
        tmp_path / "localcloud-home" / "locks",
    )
    return Controller(runtime=runtime, paths=paths), runtime, image


def write_config(
    directory: Path,
    image: str,
    *,
    services: list[str],
    project: str = "local-gcp-project",
    user: str = "local-developer",
    data_volume: str = "localcloud-data",
    data: str = "persistent",
    docker_socket: bool = False,
    seed: str | None = "seed.yaml",
    name: str = "localcloud.yaml",
    mcp_write: bool = False,
    mcp_destructive: bool = False,
) -> LocalCloudConfig:
    seed_value = "disabled" if seed is None else seed
    path = directory / name
    path.write_text(
        "\n".join(
            [
                "version: 1",
                "context:",
                f"  project: {project}",
                f"  user: {user}",
                "host:",
                f"  data_volume: {data_volume}",
                f"  seed: {seed_value}",
                f"  data: {data}",
                f"  image: {image}",
                "  memory: 4g",
                f"  docker_socket: {str(docker_socket).lower()}",
                "  transparent_network: false",
                f"  port_range: {THROWAWAY_PORT_RANGE}",
                "  environment: {}",
                "mcp:",
                f"  write: {str(mcp_write).lower()}",
                f"  destructive: {str(mcp_destructive).lower()}",
                "services:",
                "  enabled:",
                *[f"    - {service}" for service in services],
                "",
            ]
        ),
        encoding="utf-8",
    )
    return load_config(
        explicit=path,
        directory=directory,
        data_volume=data_volume,
        project=project,
        user=user,
    )


def parent_container(
    runtime: DockerRuntime, config: LocalCloudConfig
):
    current = runtime.resolve(config, require=True)
    assert current is not None
    return runtime.client.containers.get(current.container_id)


def dataproc_container(runtime: DockerRuntime, config: LocalCloudConfig):
    containers = runtime.client.containers.list(
        all=True,
        filters={
            "label": [
                f"{VOLUME_NAME_LABEL}={config.data_volume}",
                "localcloud.service=dataproc",
            ]
        },
    )
    if len(containers) != 1:
        pytest.fail(
            "full_olap_prerequisite_dataproc_runtime: "
            f"expected one running Dataproc child, found {len(containers)}"
        )
    container = containers[0]
    container.reload()
    if container.status != "running":
        pytest.fail(
            "full_olap_prerequisite_dataproc_runtime: "
            f"child status is {container.status}"
        )
    return container


def put_bytes(container, target: str, content: bytes, mode: int = 0o644) -> None:
    path = Path(target)
    archive = io.BytesIO()
    with tarfile.open(fileobj=archive, mode="w") as bundle:
        info = tarfile.TarInfo(path.name)
        info.size = len(content)
        info.mode = mode
        bundle.addfile(info, io.BytesIO(content))
    assert container.put_archive(str(path.parent), archive.getvalue())


def get_bytes(container, target: str) -> bytes:
    chunks, _ = container.get_archive(target)
    archive = io.BytesIO(b"".join(chunks))
    with tarfile.open(fileobj=archive, mode="r") as bundle:
        member = next(item for item in bundle.getmembers() if item.isfile())
        stream = bundle.extractfile(member)
        assert stream is not None
        return stream.read()


def assert_loopback_url(value: str) -> None:
    host = urlparse(value).hostname
    assert host in {"127.0.0.1", "localhost", "::1"}, value


def exec_checked(container, command: list[str]) -> str:
    result = container.exec_run(command)
    output = result.output.decode("utf-8", errors="replace")
    assert result.exit_code == 0, output
    return output
