from __future__ import annotations

import json
from dataclasses import replace
from pathlib import Path
from typing import Any

import pytest

import localcloud_cli.controller as controller_module
import localcloud_cli.endpoints as endpoints_module
from localcloud_cli.config import (
    HostPaths,
    LocalCloudConfig,
    load_active_runtime,
    load_config,
    runtime_settings,
)
from localcloud_cli.controller import Controller
from localcloud_cli.docker_runtime import RuntimeRecord
from localcloud_cli.errors import HostError


class FakeRuntime:
    def __init__(self) -> None:
        self.record: RuntimeRecord | None = None
        self.creates = 0
        self.starts = 0
        self.restarts = 0
        self.stops = 0
        self.removes: list[bool] = []
        self.purges = 0
        self.ready = True
        self.recreation = {
            "container": "managed",
            "network": "managed",
            "data_volume": "managed",
        }
        self.preferred: list[str | None] = []
        self.preflights: list[str | None] = []
        self.preflight_error: HostError | None = None





    def resolve(
        self,
        _config: LocalCloudConfig,
        *,
        preferred_container_id: str | None = None,
        require: bool = False,
    ) -> RuntimeRecord | None:
        self.preferred.append(preferred_container_id)
        if self.record is None and require:
            raise HostError("runtime_not_found", "not found")
        return self.record

    def preflight_create(
        self,
        _config: LocalCloudConfig,
        replacing: RuntimeRecord | None = None,
    ) -> None:
        self.preflights.append(
            None if replacing is None else str(replacing.container_id)
        )
        if self.preflight_error is not None:
            raise self.preflight_error




    def create(self, config: LocalCloudConfig) -> RuntimeRecord:
        self.creates += 1
        self.record = replace(
            _record(config, container_id=f"container-{self.creates}"),
            volume_created=self.creates == 1,
        )
        return self.record

    def start(
        self, _config: LocalCloudConfig, current: RuntimeRecord
    ) -> RuntimeRecord:
        self.starts += 1
        self.record = replace(current, state="running", health="healthy")
        return self.record

    def restart(
        self, _config: LocalCloudConfig, current: RuntimeRecord
    ) -> RuntimeRecord:
        self.restarts += 1
        self.record = replace(current, state="running", health="healthy")
        return self.record

    def stop(
        self, _config: LocalCloudConfig, current: RuntimeRecord
    ) -> RuntimeRecord:
        self.stops += 1
        self.record = replace(current, state="exited", health=None)
        return self.record

    def remove(
        self,
        _config: LocalCloudConfig,
        _current: RuntimeRecord,
        *,
        remove_volume: bool,
    ) -> None:
        self.removes.append(remove_volume)
        self.record = None

    def purge(self, _config: LocalCloudConfig) -> None:
        self.purges += 1
        self.record = None

    def recreation_ownership(
        self, _config: LocalCloudConfig
    ) -> dict[str, str]:
        return dict(self.recreation)

    def logs(
        self,
        _config: LocalCloudConfig,
        _current: RuntimeRecord,
        *,
        tail: int,
    ) -> str:
        return f"tail={tail}"

    def is_ready(self, _current: RuntimeRecord) -> bool:
        return self.ready

    def doctor(self) -> dict[str, Any]:
        return {"status": "ok", "warning": "runtime warning"}


class FakeJavaClient:
    projects: set[str] = {"local-gcp-project"}
    reset_projects: list[str] = []
    seeds: list[tuple[str, str, bool]] = []
    fail_seed = False

    def __init__(self, _url: str, project: str, user: str):
        self.project = project
        self.user = user

    def project_exists(self) -> bool:
        return self.project in type(self).projects

    def create_project(self) -> None:
        type(self).projects.add(self.project)

    def reset_project(self) -> None:
        type(self).reset_projects.append(self.project)

    def seed_project(self, yaml: str, *, volatile_only: bool) -> None:
        if type(self).fail_seed:
            raise RuntimeError("seed failed")
        type(self).seeds.append((self.project, yaml, volatile_only))


@pytest.fixture(autouse=True)
def fake_java(monkeypatch: pytest.MonkeyPatch) -> None:
    FakeJavaClient.projects = {"local-gcp-project"}
    FakeJavaClient.reset_projects = []
    FakeJavaClient.seeds = []
    FakeJavaClient.fail_seed = False
    monkeypatch.setattr(controller_module, "JavaMcpClient", FakeJavaClient)
    monkeypatch.setattr(
        endpoints_module,
        "environment_config",
        lambda _environment, project, user, output_format: {
            "GOOGLE_CLOUD_PROJECT": project,
            "LOCALCLOUD_USER": user,
        },
    )


def _paths(tmp_path: Path) -> HostPaths:
    return HostPaths(home=tmp_path / "home", locks=tmp_path / "home" / "locks")


def _config(
    tmp_path: Path,
    *,
    paths: HostPaths,
    yaml: str | None = None,
    **kwargs: Any,
) -> LocalCloudConfig:
    if yaml is not None:
        (tmp_path / "localcloud.yaml").write_text(yaml, encoding="utf-8")
    return load_config(directory=tmp_path, paths=paths, **kwargs)


def _record(
    config: LocalCloudConfig,
    *,
    container_id: str = "container-existing",
    origin: str = "managed",
    ownership: dict[str, str] | None = None,
    state: str = "running",
) -> RuntimeRecord:
    return RuntimeRecord(
        data_volume=config.data_volume,
        origin=origin,
        ownership=ownership
        or {
            "container": "managed",
            "network": "managed",
            "data_volume": "managed",
        },
        name=config.container_name or "localcloud",
        container_id=container_id,
        state=state,
        health="healthy" if state == "running" else None,
        url="http://127.0.0.1:49080",
        endpoint_map={"24080": 49080},
        network_name=config.network_name or "localcloud",
        mount={
            "type": "volume",
            "source": config.data_volume,
            "destination": "/var/lib/localcloud",
            "mode": "rw",
            "read_write": True,
        },
        configured_image=config.image,
        actual_image=config.image,
        image_id="sha256:image",
        config_hash=config.config_hash,
        config_path=str(config.config_path) if config.config_path else "<defaults>",
        runtime_settings=runtime_settings(config),
        services=(
            "<default>"
            if config.services is None
            else ",".join(config.services)
        ),
        data=config.data,
        labels={},
        drift={},
    )


def _controller(tmp_path: Path) -> tuple[Controller, FakeRuntime, HostPaths]:
    paths = _paths(tmp_path)
    runtime = FakeRuntime()
    return Controller(runtime=runtime, paths=paths), runtime, paths


def test_start_creates_runtime_project_and_active_record(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(
        tmp_path,
        paths=paths,
        project="new-project",
        user="agent@example.test",
    )

    result = controller.start(config)

    assert result["status"] == "started"
    assert result["data_volume"] == "localcloud-data"
    assert result["project"] == "new-project"
    assert result["origin"] == "managed"
    assert result["ownership"]["data_volume"] == "managed"
    assert result["container"]["id"] == "container-1"
    assert result["mcp"]["args"] == [
        "mcp",
        "--data-volume",
        "localcloud-data",
        "--project-id",
        "new-project",
        "--user",
        "agent@example.test",
    ]
    assert runtime.creates == 1
    active = load_active_runtime(paths)
    assert active is not None
    assert active.data_volume == config.data_volume
    assert active.image == config.image
    assert active.container_id == "container-1"


def test_start_adopts_attached_container_without_reconfiguration(
    tmp_path: Path,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    original = _config(tmp_path, paths=paths)
    runtime.record = _record(
        original,
        origin="attached",
        ownership={
            "container": "attached",
            "network": "attached",
            "data_volume": "attached",
        },
    )
    changed = _config(tmp_path, paths=paths, yaml="memory: 8g\n")

    result = controller.start(changed)

    assert result["status"] == "already_running"
    assert result["origin"] == "attached"
    assert result["container"]["id"] == "container-existing"
    assert runtime.removes == []
    assert runtime.creates == 0


def test_start_does_not_restart_running_unhealthy_attached_container(
    tmp_path: Path,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(
        config,
        origin="attached",
        ownership={
            "container": "attached",
            "network": "attached",
            "data_volume": "attached",
        },
    )
    runtime.ready = False

    with pytest.raises(HostError) as caught:
        controller.start(config)

    assert caught.value.code == "attached_runtime_unhealthy"
    assert runtime.restarts == 0



def test_reconfiguration_preflight_preserves_current_runtime_on_failure(
    tmp_path: Path,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    original = _config(tmp_path, paths=paths)
    runtime.record = _record(original)
    runtime.preflight_error = HostError(
        "managed_image_capability_missing", "replacement is incompatible"
    )
    changed = _config(tmp_path, paths=paths, yaml="memory: 8g\n")

    with pytest.raises(HostError) as caught:
        controller.start(changed)

    assert caught.value.code == "managed_image_capability_missing"
    assert runtime.removes == []
    assert runtime.record is not None



def test_managed_container_on_attached_volume_can_be_reconfigured(
    tmp_path: Path,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    original = _config(tmp_path, paths=paths)
    runtime.record = _record(
        original,
        origin="attached",
        ownership={
            "container": "managed",
            "network": "managed",
            "data_volume": "attached",
        },
    )
    changed = _config(tmp_path, paths=paths, yaml="memory: 8g\n")

    result = controller.start(changed)

    assert result["status"] == "reconfigured"
    assert result["changed_fields"] == ["memory"]
    assert runtime.removes == [False]
    assert runtime.creates == 1


def test_restart_never_replaces_attached_container(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    original = _config(tmp_path, paths=paths)
    runtime.record = _record(
        original,
        origin="attached",
        ownership={
            "container": "attached",
            "network": "attached",
            "data_volume": "attached",
        },
    )
    changed = _config(tmp_path, paths=paths, yaml="memory: 8g\n")

    result = controller.restart(changed)

    assert result["status"] == "restarted"
    assert result["container"]["id"] == "container-existing"
    assert runtime.restarts == 1
    assert runtime.removes == []


def test_stop_preserves_attached_ephemeral_runtime(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths, yaml="data: ephemeral\n")
    runtime.record = _record(
        config,
        origin="attached",
        ownership={
            "container": "attached",
            "network": "attached",
            "data_volume": "attached",
        },
    )

    result = controller.stop(config)

    assert result["status"] == "stopped"
    assert runtime.stops == 1
    assert runtime.removes == []


def test_stop_removes_fully_managed_ephemeral_runtime(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths, yaml="data: ephemeral\n")
    runtime.record = _record(config)

    result = controller.stop(config)

    assert result["status"] == "stopped"
    assert result["container"]["state"] == "removed"
    assert runtime.removes == [True]


def test_reset_all_rejects_attached_resources_before_mutation(
    tmp_path: Path,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.recreation["data_volume"] = "attached"

    with pytest.raises(HostError) as caught:
        controller.reset(config, all_projects=True)

    assert caught.value.code == "ownership_forbidden"
    assert runtime.purges == 0
    assert runtime.creates == 0


def test_reset_all_recreates_fully_managed_runtime(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(config)

    result = controller.reset(config, all_projects=True)

    assert result["status"] == "reset"
    assert result["reset_scope"] == "all_projects"
    assert runtime.purges == 1
    assert runtime.creates == 1


def test_selected_project_reset_uses_attached_runtime(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(
        config,
        origin="attached",
        ownership={
            "container": "attached",
            "network": "attached",
            "data_volume": "attached",
        },
    )

    result = controller.reset(config)

    assert result["reset_scope"] == "project"
    assert FakeJavaClient.reset_projects == [config.project]
    assert runtime.removes == []


def test_status_uses_active_container_as_tie_break_hint(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(config, container_id="preferred-id")
    controller.start(config)

    controller.status(config)

    assert runtime.preferred[-1] == "preferred-id"


def test_status_reports_runtime_identity_and_image(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths, data_volume="team-data")
    runtime.record = _record(config)

    result = controller.status(config)

    assert result["status"] == "running"
    assert result["data_volume"] == "team-data"
    assert result["container"]["configured_image"] == config.image
    assert result["container"]["actual_image"] == config.image
    assert result["mount"]["source"] == "team-data"


def test_target_returns_only_connection_context(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(config)

    result = controller.target(config)

    assert result == {
        "url": "http://127.0.0.1:49080",
        "endpoint_map": {"24080": 49080},
    }


def test_target_requires_existing_project(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths, project="missing")
    runtime.record = _record(config)

    with pytest.raises(HostError) as caught:
        controller.target(config)

    assert caught.value.code == "unknown_project"
    assert "--data-volume localcloud-data" in caught.value.message


def test_logs_and_remembered_config_use_resolved_runtime(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths, yaml="memory: 6g\n")
    runtime.record = _record(config)

    assert controller.remembered_config(config) == str(
        (tmp_path / "localcloud.yaml").resolve()
    )
    assert controller.logs(config, tail=17)["logs"] == "tail=17"


def test_failed_seed_does_not_update_active_runtime(tmp_path: Path) -> None:
    controller, _runtime, paths = _controller(tmp_path)
    (tmp_path / "seed.yaml").write_text(
        "projects:\n  - projectId: local-gcp-project\n", encoding="utf-8"
    )
    config = _config(
        tmp_path, paths=paths, yaml="seed: seed.yaml\n"
    )
    FakeJavaClient.fail_seed = True

    with pytest.raises(HostError) as caught:
        controller.start(config)

    assert caught.value.code == "seed_failed"
    assert load_active_runtime(paths) is None


def test_doctor_reports_malformed_active_state_without_failing(
    tmp_path: Path,
) -> None:
    controller, _runtime, paths = _controller(tmp_path)
    paths.home.mkdir(parents=True)
    (paths.home / "active-runtime.json").write_text(
        json.dumps({"schema_version": 999}), encoding="utf-8"
    )

    result = controller.doctor()

    assert result["status"] == "ok"
    assert result["active_runtime"] is None
    assert result["active_runtime_diagnostics"][0]["code"] == "invalid_active_runtime"
    assert "malformed" in result["warning"]
