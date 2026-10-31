"""Lite/full and --no-ui across start and restart, against the published images.

Runs on a throwaway data volume, an isolated LOCALCLOUD home and host ports
5960-5999, so a LocalCloud the developer already runs is left alone. Needs
agentcloud/localcloud:lite and :latest locally; it never pulls."""

from __future__ import annotations

import uuid
from pathlib import Path
from typing import Any

import httpx
import pytest

from localcloud_cli.config import HostPaths, LocalCloudConfig, load_config
from localcloud_cli.controller import Controller
from localcloud_cli.docker_runtime import VOLUME_NAME_LABEL, DockerRuntime

pytestmark = pytest.mark.docker

_LITE = "agentcloud/localcloud:lite"
_FULL = "agentcloud/localcloud:latest"
_PORT_RANGE = "5960-5999"


@pytest.fixture
def lifecycle(tmp_path: Path) -> Any:
    try:
        runtime = DockerRuntime()
        runtime.client.ping()
        for image in (_LITE, _FULL):
            runtime.client.images.get(image)
    except Exception as error:
        pytest.fail(f"docker_prerequisite_unavailable: {error}")
    paths = HostPaths(tmp_path / "home", tmp_path / "home" / "locks")
    volume = f"lc-test-channel-{uuid.uuid4().hex[:8]}"
    # server.console.enabled: true must not undo --no-ui (S1).
    (tmp_path / "localcloud.yaml").write_text(
        "server:\n  console:\n    enabled: true\n"
        "services:\n  enabled:\n    - gcs\n",
        encoding="utf-8",
    )
    controller = Controller(runtime=runtime, paths=paths)

    def config(**kwargs: Any) -> LocalCloudConfig:
        return load_config(
            directory=tmp_path,
            paths=paths,
            data_volume=volume,
            port_range=_PORT_RANGE,
            **kwargs,
        )

    try:
        yield controller, runtime, config, paths
    finally:
        client = runtime.client
        for container in client.containers.list(
            all=True, filters={"label": f"{VOLUME_NAME_LABEL}={volume}"}
        ):
            container.remove(force=True, v=True)
        for network in client.networks.list(
            filters={"label": f"{VOLUME_NAME_LABEL}={volume}"}
        ):
            network.remove()
        for item in client.volumes.list(filters={"name": volume}):
            if item.name == volume:
                item.remove(force=True)


def _running(runtime: DockerRuntime, config: LocalCloudConfig) -> tuple[str, bool]:
    current = runtime.resolve(config, require=True)
    assert current is not None and current.url is not None
    image = runtime.client.containers.get(current.container_id).attrs["Config"]["Image"]
    console = httpx.get(f"{current.url}/health", timeout=5.0).json()["console"]
    return image, console["enabled"]


def test_runtime_keeps_its_build_and_headless_mode_across_restarts(
    lifecycle: Any,
) -> None:
    controller, runtime, config, paths = lifecycle
    start = {"pull": False, "tail": 0, "confirm_port_mapping": lambda _plan: True}

    controller.start(config(lite=False, console=False), **start)
    assert _running(runtime, config()) == (_FULL, False)

    # Without its active-runtime record a plain restart would default to lite (S2).
    paths.active_runtime.unlink()
    plain = config()
    assert plain.image == _LITE
    result = controller.restart(plain, **start)
    assert result["status"] == "restarted", result.get("changed_fields")
    assert _running(runtime, config()) == (_FULL, False)

    # --lite switches the build; the record then keeps it.
    controller.restart(config(lite=True), **start)
    assert _running(runtime, config()) == (_LITE, False)
    assert config().image == _LITE
    assert controller.start(config(), **start)["status"] == "already_running"
