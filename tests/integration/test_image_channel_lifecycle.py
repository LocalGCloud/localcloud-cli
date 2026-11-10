"""Lite/full and --no-ui across start and restart.

Runs the CLI's default lite build and its full build, as --full names it (`REPO:latest` for
`REPO:lite`, `REPO:TAG` for `REPO:TAG-lite`). LOCALCLOUD_IMAGE names another lite build, such as a
release candidate's; the test then makes it the CLI's default rather than an explicit choice, since
an explicit image would rightly override the runtime's build on restart. Both builds must be local;
it never pulls."""

from __future__ import annotations

from pathlib import Path
from typing import Any

import os

import httpx
import pytest

from integration._support import throwaway_runtime
from localcloud_cli import config as config_module
from localcloud_cli.config import LocalCloudConfig, is_lite_image, lite_image
from localcloud_cli.constants import DEFAULT_IMAGE
from localcloud_cli.docker_runtime import DockerRuntime

pytestmark = pytest.mark.docker

_LITE = os.environ.get("LOCALCLOUD_IMAGE", DEFAULT_IMAGE)
_FULL = lite_image(_LITE, False)


@pytest.fixture
def lifecycle(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Any:
    if not is_lite_image(_LITE):
        pytest.fail(f"image_channel_prerequisite: LOCALCLOUD_IMAGE must name a lite build, not {_LITE}")
    monkeypatch.delenv("LOCALCLOUD_IMAGE", raising=False)
    monkeypatch.setattr(config_module, "DEFAULT_IMAGE", _LITE)
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
