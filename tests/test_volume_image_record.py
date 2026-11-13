"""The image each data volume last became ready on, and the start failures it explains.

A LocalCloud image refuses data that a newer image wrote, and its container then
exits while starting. The CLI names the image the volume last ran instead of
guessing, and changes nothing."""

from __future__ import annotations

from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest

import localcloud_cli.mcp_stdio as mcp_module
from localcloud_cli.errors import HostError
from localcloud_cli.mcp_stdio import error_diagnostics
from localcloud_cli.state import (
    forget_volume_image,
    load_volume_image,
    record_volume_image,
)

from test_controller import _config, _controller, _record, fake_java  # noqa: F401 - fixture
from test_start_diagnostics import _start_failure



def test_volume_image_record_round_trips_beside_the_active_runtime_state(
    tmp_path: Path,
) -> None:
    paths = SimpleNamespace(home=tmp_path / "home", locks=tmp_path / "home" / "locks")
    paths.active_runtime = paths.home / "active-runtime.json"
    assert load_volume_image(paths, "team-data") is None

    record_volume_image(
        paths,
        "team-data",
        image="agentcloud/localcloud:lite",
        image_id="sha256:new",
        version="0.1.17",
    )
    record_volume_image(paths, "other", image="custom:dev", image_id="sha256:dev")

    assert load_volume_image(paths, "team-data") == {
        "image": "agentcloud/localcloud:lite",
        "image_id": "sha256:new",
        "version": "0.1.17",
    }
    assert not paths.active_runtime.exists()
    forget_volume_image(paths, "team-data")
    assert load_volume_image(paths, "team-data") is None
    assert load_volume_image(paths, "other") == {"image": "custom:dev", "image_id": "sha256:dev"}

    (paths.home / "volume-images.json").write_text("{not json", encoding="utf-8")
    assert load_volume_image(paths, "other") is None


def _controller_with_failed_start(tmp_path: Path, **config_kwargs: Any) -> tuple[Any, Any, Any]:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths, **config_kwargs)
    runtime.record = _record(config, state="exited", image_id="sha256:" + "b" * 64)
    return controller, runtime, config


def test_a_start_that_exits_on_another_image_names_the_image_the_data_last_ran(
    tmp_path: Path,
) -> None:
    controller, runtime, config = _controller_with_failed_start(tmp_path)
    # A first, successful start records the image the volume became ready on.
    runtime.record = replace(runtime.record, state="running", image_id="sha256:" + "d" * 64,
                                    labels={"org.opencontainers.image.version": "0.1.18"})
    controller.start(config)
    assert load_volume_image(controller.paths, config.data_volume) == {
        "image": config.image,
        "image_id": "sha256:" + "d" * 64,
        "version": "0.1.18",
    }

    # The same reference now names an older local image, which exits.
    runtime.record = replace(runtime.record, state="exited", image_id="sha256:" + "b" * 64)
    failure = _start_failure()
    failure.details["diagnostics"]["image"] = config.image
    runtime.wait_error = failure

    with pytest.raises(HostError) as caught:
        controller.start(config)

    error = caught.value
    assert error.code == "container_start_failed"
    assert error.details["data_last_ready_on"]["image_id"] == "sha256:" + "d" * 64
    assert error.details["steps"] == ["lc restart --pull"]
    assert "last became ready on another image" in error.message
    assert "dddddddddddd, version 0.1.18" in error.message
    # Nothing was replaced or removed.
    assert runtime.creates == 0 and runtime.removes == []

    adapter_text = mcp_module._recovery(error, auto_start=True)
    assert "`lc restart --pull`" in adapter_text
    diagnostics = error_diagnostics(error)
    assert diagnostics is not None
    assert diagnostics["data_last_ready_on"] == {
        "image": config.image,
        "image_id": "sha256:" + "d" * 64,
        "version": "0.1.18",
    }
    assert any(
        line.startswith("This data volume last became ready on image")
        for line in mcp_module._diagnostic_lines(diagnostics)
    )


def test_the_step_names_a_different_image_and_a_custom_data_volume(
    tmp_path: Path,
) -> None:
    controller, runtime, config = _controller_with_failed_start(
        tmp_path, data_volume="team-data"
    )
    record_volume_image(
        controller.paths, "team-data", image="custom/localcloud:dev", image_id="sha256:dev"
    )
    runtime.wait_error = _start_failure()

    with pytest.raises(HostError) as caught:
        controller.start(config)

    assert caught.value.details["steps"] == [
        "lc restart --data-volume team-data --image custom/localcloud:dev"
    ]
    assert "`lc restart --data-volume team-data --image custom/localcloud:dev`" in (
        mcp_module._recovery(caught.value, auto_start=True)
    )


@pytest.mark.parametrize("case", ["same_image", "no_record", "restored", "other_code"])
def test_start_failures_are_left_alone_without_another_recorded_image(
    tmp_path: Path, case: str
) -> None:
    controller, runtime, config = _controller_with_failed_start(tmp_path)
    failure = _start_failure()
    if case != "no_record":
        record_volume_image(
            controller.paths,
            config.data_volume,
            image=config.image,
            image_id=(
                failure.details["diagnostics"]["image_id"]
                if case == "same_image"
                else "sha256:other"
            ),
        )
    if case == "restored":
        failure.details["previous_runtime_restored"] = True
    if case == "other_code":
        failure = HostError("health_timeout", "not healthy", {"diagnostics": {}})
    message = failure.message
    runtime.wait_error = failure

    with pytest.raises(HostError) as caught:
        controller.start(config)

    assert "data_last_ready_on" not in caught.value.details
    assert "steps" not in caught.value.details
    assert caught.value.message == message
