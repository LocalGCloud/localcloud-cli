from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest

from localcloud_cli import __version__

from localcloud_cli.config import (
    DEFAULT_IMAGE,
    DEFAULT_PROJECT,
    HostPaths,
    LocalCloudConfig,
    load_config,
)
from localcloud_cli.controller import Controller
from localcloud_cli.errors import HostError


class FakeRuntime:
    def __init__(self) -> None:
        self.record: dict[str, Any] | None = None
        self.creates = 0
        self.starts = 0
        self.stops = 0
        self.removes: list[bool] = []
        self.purges: list[str] = []
        self.ready = True

    def inspect(self, _instance: str) -> dict[str, Any] | None:
        return dict(self.record) if self.record is not None else None

    def create(self, config: LocalCloudConfig) -> dict[str, Any]:
        self.creates += 1
        self.record = _record(config)
        self.record["volume_created"] = not self.removes
        return dict(self.record)

    def start(self, environment: dict[str, Any]) -> dict[str, Any]:
        self.starts += 1
        self.record = dict(environment, state="running", url="http://127.0.0.1:49080")
        return dict(self.record)

    def stop(self, environment: dict[str, Any]) -> None:
        self.stops += 1
        self.record = dict(environment, state="exited", url=None)

    def remove(self, _environment: dict[str, Any], *, remove_volume: bool = True) -> None:
        self.removes.append(remove_volume)
        self.record = None

    def purge(self, instance: str) -> None:
        self.purges.append(instance)
        self.record = None

    def is_ready(self, _environment: dict[str, Any]) -> bool:
        return self.ready

    def logs(self, _environment: dict[str, Any], tail: int = 200) -> str:
        return f"tail={tail}"

    def doctor(self) -> dict[str, Any]:
        return {"status": "ok", "legacy_resources": []}


class FakeJavaMcpClient:
    projects: set[str] = {DEFAULT_PROJECT}
    calls: list[tuple[Any, ...]] = []
    fail_create = False

    def __init__(self, url: str, project: str, user: str):
        self.url = url
        self.project = project
        self.user = user

    def project_exists(self) -> bool:
        type(self).calls.append(("project_exists", self.project, self.user))
        return self.project in type(self).projects

    def create_project(self) -> dict[str, Any]:
        type(self).calls.append(("create_project", self.project, self.user))
        if type(self).fail_create:
            raise RuntimeError("create failed")
        type(self).projects.add(self.project)
        return {"project_id": self.project}

    def reset_project(self) -> dict[str, Any]:
        type(self).calls.append(("reset_project", self.project, self.user))
        return {"project_id": self.project}

    def seed_project(self, yaml: str, *, volatile_only: bool = False) -> dict[str, Any]:
        type(self).calls.append(("seed_project", self.project, self.user, yaml, volatile_only))
        return {"seeded": True}


@pytest.fixture(autouse=True)
def fake_java_and_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    FakeJavaMcpClient.projects = {DEFAULT_PROJECT}
    FakeJavaMcpClient.calls = []
    FakeJavaMcpClient.fail_create = False
    monkeypatch.setattr("localcloud_cli.controller.JavaMcpClient", FakeJavaMcpClient)
    monkeypatch.setattr(
        "localcloud_cli.endpoints.environment_config",
        lambda _environment, project, user, output_format="shell": {
            "LOCALCLOUD_PROJECT": project,
            "LOCALCLOUD_USER": user,
            "format": output_format,
        },
    )


def _controller(tmp_path: Path) -> tuple[Controller, FakeRuntime]:
    runtime = FakeRuntime()
    paths = HostPaths(tmp_path / "home", tmp_path / "home" / "locks")
    return Controller(runtime=runtime, paths=paths), runtime


def _record(config: LocalCloudConfig) -> dict[str, Any]:
    instance_config = {
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
    return {
        "instance": config.instance,
        "name": config.container_name,
        "network_name": config.network_name,
        "volume_name": config.volume_name,
        "state": "running",
        "url": "http://127.0.0.1:49080",
        "endpoint_map": {"24080": 49080, "24081": 49081},
        "config_hash": config.config_hash,
        "config_path": str(config.config_path) if config.config_path else "<defaults>",
        "instance_config": instance_config,
        "services": ",".join(config.services) if config.services is not None else "<default>",
        "data": config.data,
        "labels": {"com.localcloud.instance": config.instance},
    }


def test_projects_share_instance_and_start_creates_only_missing_project(tmp_path: Path) -> None:
    controller, runtime = _controller(tmp_path)
    default = load_config(directory=tmp_path)
    other = load_config(directory=tmp_path, project="other-project-1", user="alice")

    first = controller.start(default)
    second = controller.start(other)

    assert runtime.creates == 1
    assert default.config_hash == other.config_hash
    assert first["container"]["name"] == second["container"]["name"] == "localcloud"
    assert second["project"] == "other-project-1"
    assert ("create_project", "other-project-1", "alice") in FakeJavaMcpClient.calls


def test_payload_carries_direct_and_stdio_context(tmp_path: Path) -> None:
    FakeJavaMcpClient.projects.add("agent-project-1")
    controller, _runtime = _controller(tmp_path)
    result = controller.start(load_config(directory=tmp_path, project="agent-project-1", user="alice"))

    assert result["mcp"]["headers"] == {
        "X-LocalCloud-Project": "agent-project-1",
        "X-LocalCloud-User": "alice",
    }
    assert result["mcp"]["args"] == [
        "mcp", "--instance", "default", "--project-id", "agent-project-1", "--user", "alice"
    ]
    assert result["sdk_env"]["LOCALCLOUD_PROJECT"] == "agent-project-1"
    assert result["sdk_env"]["LOCALCLOUD_USER"] == "alice"


@pytest.mark.parametrize("operation", ["restart", "reset", "target"])
def test_non_start_operations_reject_missing_project_without_creating(
    tmp_path: Path, operation: str
) -> None:
    controller, _runtime = _controller(tmp_path)
    controller.start(load_config(directory=tmp_path))
    FakeJavaMcpClient.calls.clear()
    missing = load_config(directory=tmp_path, project="missing-project-1")

    with pytest.raises(HostError) as caught:
        if operation == "target":
            controller.target(missing.instance, missing.project, missing.user)
        else:
            getattr(controller, operation)(missing)

    assert caught.value.code == "unknown_project"
    assert not any(call[0] == "create_project" for call in FakeJavaMcpClient.calls)


def test_project_creation_failure_leaves_instance_running(tmp_path: Path) -> None:
    controller, runtime = _controller(tmp_path)
    FakeJavaMcpClient.fail_create = True

    with pytest.raises(HostError) as caught:
        controller.start(load_config(directory=tmp_path, project="other-project-1"))

    assert caught.value.code == "project_create_failed"
    assert runtime.record is not None
    assert runtime.removes == []


def test_project_reset_preserves_instance_and_peers(tmp_path: Path) -> None:
    FakeJavaMcpClient.projects.update({"agent-project-1", "peer-project-1"})
    controller, runtime = _controller(tmp_path)
    controller.start(load_config(directory=tmp_path))
    FakeJavaMcpClient.calls.clear()

    result = controller.reset(load_config(directory=tmp_path, project="agent-project-1", user="alice"))

    assert result["reset_scope"] == "project"
    assert runtime.removes == []
    assert ("reset_project", "agent-project-1", "alice") in FakeJavaMcpClient.calls
    assert "peer-project-1" in FakeJavaMcpClient.projects


def test_all_projects_reset_recreates_volume(tmp_path: Path) -> None:
    controller, runtime = _controller(tmp_path)
    config = load_config(directory=tmp_path)
    controller.start(config)

    result = controller.reset(config, all_projects=True)

    assert result["reset_scope"] == "all_projects"
    assert runtime.removes == [True]
    assert runtime.creates == 2
    assert not any(call[0] == "reset_project" for call in FakeJavaMcpClient.calls)


def test_restart_reapplies_only_volatile_seed(tmp_path: Path) -> None:
    (tmp_path / "seed.yaml").write_text("projects: []\n", encoding="utf-8")
    controller, _runtime = _controller(tmp_path)
    config = load_config(directory=tmp_path)
    controller.start(config)
    FakeJavaMcpClient.calls.clear()

    controller.restart(config)

    assert ("seed_project", DEFAULT_PROJECT, "local-developer", "projects: []\n", True) in FakeJavaMcpClient.calls


def test_reconfiguration_preserves_volume_and_reports_fields(tmp_path: Path) -> None:
    controller, runtime = _controller(tmp_path)
    controller.start(load_config(directory=tmp_path))
    (tmp_path / "localcloud.yaml").write_text("memory: 8g\n", encoding="utf-8")

    result = controller.start(load_config(directory=tmp_path))

    assert result["status"] == "reconfigured"
    assert result["changed_fields"] == ["memory"]
    assert runtime.removes == [False]

def test_version_reconfiguration_preserves_persistent_volume(tmp_path: Path) -> None:
    config_path = tmp_path / "localcloud.yaml"
    config_path.write_text(
        "image: jaysen2apache/localcloud:0.0.9\n",
        encoding="utf-8",
    )
    controller, runtime = _controller(tmp_path)
    controller.start(load_config(directory=tmp_path))
    config_path.unlink()

    current = load_config(directory=tmp_path)
    result = controller.start(current)

    assert current.image == DEFAULT_IMAGE
    assert result["status"] == "reconfigured"
    assert result["changed_fields"] == ["image"]
    assert runtime.removes == [False]
    assert runtime.record is not None
    assert runtime.record["instance_config"]["image"] == DEFAULT_IMAGE


def test_doctor_reports_cli_and_default_image_versions(tmp_path: Path) -> None:
    controller, _runtime = _controller(tmp_path)

    result = controller.doctor()

    assert result["cli_version"] == __version__
    assert result["default_image"] == f"jaysen2apache/localcloud:{__version__}"


def test_status_logs_stop_and_target_are_instance_scoped(tmp_path: Path) -> None:
    controller, runtime = _controller(tmp_path)
    controller.start(load_config(directory=tmp_path, instance="team-a"))

    assert controller.status("team-a")["status"] == "running"
    assert "project" not in controller.status("team-a")
    assert controller.logs("team-a", tail=17)["logs"] == "tail=17"
    assert controller.target("team-a")["instance"] == "team-a"
    assert controller.stop("team-a")["status"] == "stopped"
    assert runtime.stops == 1


def test_remembered_config_comes_from_instance_record(tmp_path: Path) -> None:
    config_path = tmp_path / "alternate.yaml"
    config_path.write_text("memory: 6g\n", encoding="utf-8")
    controller, _runtime = _controller(tmp_path)
    assert controller.remembered_config("default") is None

    controller.start(load_config(explicit=config_path, directory=tmp_path))
    assert controller.remembered_config("default") == str(config_path.resolve())
