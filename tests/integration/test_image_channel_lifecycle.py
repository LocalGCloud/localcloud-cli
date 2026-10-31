"""Lite/full and --no-ui across start and restart, against the published images.

Needs agentcloud/localcloud:lite and :latest locally; it never pulls."""

from __future__ import annotations

from pathlib import Path
from typing import Any

import httpx
import pytest

from integration._support import throwaway_runtime
from localcloud_cli.config import LocalCloudConfig
from localcloud_cli.docker_runtime import DockerRuntime

pytestmark = pytest.mark.docker

_LITE = "agentcloud/localcloud:lite"
_FULL = "agentcloud/localcloud:latest"


@pytest.fixture
def lifecycle(tmp_path: Path) -> Any:
    # server.console.enabled: true must not undo --no-ui (S1).
    with throwaway_runtime(
        tmp_path,
        "server:\n  console:\n    enabled: true\nservices:\n  enabled:\n    - gcs\n",
        images=(_LITE, _FULL),
    ) as resources:
        yield resources


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
