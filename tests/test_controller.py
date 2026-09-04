from __future__ import annotations

import json
from dataclasses import replace
from pathlib import Path
from typing import Any, cast

import pytest  # pyright: ignore[reportMissingImports]

import localcloud_cli.controller as controller_module
import localcloud_cli.endpoints as endpoints_module
from localcloud_cli.config import (
    ACTIVE_RUNTIME_SCHEMA_VERSION,
    ActiveRuntime,
    HostPaths,
    LocalCloudConfig,
    load_active_runtime,
    load_config,
    runtime_settings,
    save_active_runtime,
)
from localcloud_cli.constants import DEFAULTS_CONFIG_LABEL
from localcloud_cli.controller import Controller
from localcloud_cli.docker_runtime import DockerRunPlan, RuntimeRecord
from localcloud_cli.errors import HostError


class FakeRuntime:
    def __init__(self) -> None:
        self.record: RuntimeRecord | None = None
        self.creates = 0
        self.starts = 0
        self.restarts = 0
        self.stops = 0
        self.removes: list[bool] = []
        self.remove_network_calls: list[bool] = []
        self.ready = True
        self.ready_delay = 0.0
        self.recreation = {
            "container": "managed",
            "network": "managed",
            "data_volume": "managed",
        }
        self.preferred: list[str | None] = []
        self.preflights: list[str | None] = []
        self.preflight_pulls: list[bool] = []
        self.create_pulls: list[bool] = []
        self.preflight_error: HostError | None = None
        self.preflight_delay = 0.0
        self.readiness_deadlines: list[float | None] = []
        self.prepared_images: list[tuple[Any, bool] | None] = []
        self.preview_network_exists: list[bool | None] = []
        self.preview_remove_network: list[bool] = []
        self.wait_error: HostError | None = None
        self.log_calls: list[tuple[LocalCloudConfig, RuntimeRecord, int]] = []
        self.doctor_report: dict[str, Any] = {"status": "ok", "warning": "runtime warning"}
        self.effective: tuple[str, ...] | None = None
        self.image_details_result: dict[str, Any] = {
            "location": "Local",
            "image_id": "qualified",
            "sha": "sha256:qualified",
            "formatted": "(Local: ID: qualified , sha256:qualified)",
        }
        self.image_details_images: list[str] = []
        self.image_status_images: list[str] = []
        self.ready_timeouts: list[float] = []
        self.planned_ports: dict[str, tuple[tuple[str, int], ...]] | None = None




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
        *,
        pull: bool = False,
        observer: Any | None = None,
        local_only: bool = False,
    ) -> tuple[Any, bool]:
        if self.preflight_delay:
            controller_module.time.sleep(self.preflight_delay)
        self.preflights.append(
            None if replacing is None else str(replacing.container_id)
        )
        self.preflight_pulls.append(pull)
        if self.preflight_error is not None:
            raise self.preflight_error
        return None, pull

    def plan_run(
        self,
        config: LocalCloudConfig,
        _image: Any,
        *,
        replacing: RuntimeRecord | None = None,
    ) -> DockerRunPlan:
        _ = replacing
        return DockerRunPlan(
            image=config.image,
            name=config.container_name,
            network_name=config.network_name,
            mem_limit=config.memory,
            volumes={
                config.data_volume: {
                    "bind": "/var/lib/localcloud",
                    "mode": "rw",
                }
            },
            ports=self.planned_ports
            or {
                "5365/tcp": (("127.0.0.1", 5365),),
                "5366/tcp": (("127.0.0.1", 5366),),
            },
            environment=config.environment,
            labels={},
        )

    def has_canonical_ports(
        self,
        config: LocalCloudConfig,
        runtime: RuntimeRecord,
    ) -> bool:
        expected = set(range(5365, 5376))
        if config.tls_enabled:
            expected.update((config.tls_port, 5380, 5381, 5382))
        return all(
            any(
                host_port == port
                for _host_ip, host_port in runtime.published_ports.get(
                    f"{port}/tcp", ()
                )
            )
            for port in expected
        )

    def inspect_run_plan(
        self,
        config: LocalCloudConfig,
        _current: RuntimeRecord,
    ) -> DockerRunPlan:
        return self.plan_run(config, None)


    def preview_create_commands(
        self,
        _config: LocalCloudConfig,
        run_plan: DockerRunPlan,
        *,
        volume_exists: bool | None = None,
        network_exists: bool | None = None,
    ) -> tuple[str, ...]:
        self.preview_network_exists.append(network_exists)
        _ = volume_exists, network_exists
        return (run_plan.command(),)

    def preview_remove_commands(
        self,
        _config: LocalCloudConfig,
        current: RuntimeRecord,
        *,
        remove_volume: bool,
        remove_network: bool = True,
    ) -> tuple[str, ...]:
        self.preview_remove_network.append(remove_network)
        suffix = " --volume" if remove_volume else ""
        return (f"docker rm {current.container_id}{suffix}",)

    def create(
        self,
        config: LocalCloudConfig,
        *,
        pull: bool = False,
        readiness_deadline: float | None = None,
        observer: Any | None = None,
        prepared_image: tuple[Any, bool] | None = None,
        run_plan: DockerRunPlan | None = None,
    ) -> RuntimeRecord:
        self.creates += 1
        self.create_pulls.append(pull)
        self.readiness_deadlines.append(readiness_deadline)
        self.prepared_images.append(prepared_image)
        self.record = replace(
            _record(config, container_id=f"container-{self.creates}"),
            volume_created=self.creates == 1,
        )
        return self.record

    def start(
        self,
        _config: LocalCloudConfig,
        current: RuntimeRecord,
        *,
        readiness_deadline: float | None = None,
        observer: Any | None = None,
    ) -> RuntimeRecord:
        self.readiness_deadlines.append(readiness_deadline)
        if current.state != "running":
            self.starts += 1
        if self.wait_error is not None:
            raise self.wait_error
        self.ready = True
        self.record = replace(current, state="running", health="healthy")
        return self.record

    def restart(
        self,
        _config: LocalCloudConfig,
        current: RuntimeRecord,
        *,
        readiness_deadline: float | None = None,
        observer: Any | None = None,
    ) -> RuntimeRecord:
        self.restarts += 1
        self.readiness_deadlines.append(readiness_deadline)
        self.record = replace(current, state="running", health="healthy")
        return self.record

    def stop(
        self,
        _config: LocalCloudConfig,
        current: RuntimeRecord,
        *,
        observer: Any | None = None,
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
        remove_network: bool = True,
        observer: Any | None = None,
    ) -> None:
        self.removes.append(remove_volume)
        self.remove_network_calls.append(remove_network)
        self.record = None

    def recreation_ownership(
        self, _config: LocalCloudConfig
    ) -> dict[str, str]:
        return dict(self.recreation)

    def logs(
        self,
        config: LocalCloudConfig,
        current: RuntimeRecord,
        *,
        tail: int,
        since: float | None = None,
    ) -> str:
        self.log_calls.append((config, current, tail))
        return f"tail={tail}"

    def is_ready(
        self,
        _current: RuntimeRecord,
        *,
        timeout: float = 3.0,
    ) -> bool:
        self.ready_timeouts.append(timeout)
        if self.ready_delay:
            controller_module.time.sleep(min(self.ready_delay, timeout))
        return self.ready

    def effective_services(self, _current: RuntimeRecord) -> tuple[str, ...] | None:
        return self.effective

    def doctor(self) -> dict[str, Any]:
        return dict(self.doctor_report)

    def image_status(self, image_name: str) -> str:
        self.image_status_images.append(image_name)
        return "available locally"

    def image_details(self, image_name: str) -> dict[str, Any]:
        self.image_details_images.append(image_name)
        return dict(self.image_details_result)

    def cleanup_resources(
        self, invalid: list[dict[str, Any]]
    ) -> dict[str, Any]:
        return {
            "removed": [{"kind": e["kind"], "name": e["name"]} for e in invalid],
            "failures": [],
        }


class FakeJavaClient:
    projects: set[str] = {"local-gcp-project"}
    reset_projects: list[str] = []
    seeds: list[tuple[str, str, bool]] = []
    fail_seed = False
    seed_attempts = 0
    create_calls = 0
    catalog_failures: list[Exception] = []
    catalog_error: Exception | None = None
    timeouts: list[float] = []
    create_error: Exception | None = None
    create_commits_before_error = False

    def __init__(
        self,
        _url: str,
        project: str,
        user: str,
        timeout: float = 60.0,
    ):
        self.project = project
        self.user = user
        type(self).timeouts.append(timeout)

    def list_projects(self) -> list[dict[str, str]]:
        if type(self).catalog_failures:
            raise type(self).catalog_failures.pop(0)
        error = type(self).catalog_error
        if error is not None:
            raise error
        return [
            {"project_id": project}
            for project in sorted(type(self).projects)
        ]

    def project_exists(self) -> bool:
        return any(
            item["project_id"] == self.project
            for item in self.list_projects()
        )

    def create_project(self) -> None:
        type(self).create_calls += 1
        error = type(self).create_error
        if error is not None:
            if type(self).create_commits_before_error:
                type(self).projects.add(self.project)
            raise error
        type(self).projects.add(self.project)

    def reset_project(self) -> None:
        type(self).reset_projects.append(self.project)

    def seed_project(self, yaml: str, *, volatile_only: bool) -> None:
        type(self).seed_attempts += 1
        if type(self).fail_seed:
            raise RuntimeError("seed failed")
        type(self).seeds.append((self.project, yaml, volatile_only))


@pytest.fixture(autouse=True)
def fake_java(monkeypatch: pytest.MonkeyPatch) -> None:
    FakeJavaClient.projects = {"local-gcp-project"}
    FakeJavaClient.reset_projects = []
    FakeJavaClient.seeds = []
    FakeJavaClient.fail_seed = False
    FakeJavaClient.seed_attempts = 0
    FakeJavaClient.create_calls = 0
    FakeJavaClient.catalog_failures = []
    FakeJavaClient.catalog_error = None
    FakeJavaClient.create_error = None
    FakeJavaClient.create_commits_before_error = False
    FakeJavaClient.timeouts = []
    monkeypatch.setattr(controller_module, "JavaMcpClient", FakeJavaClient)
    monkeypatch.setattr(
        endpoints_module,
        "environment_config",
        lambda _environment, project, user, output_format: {
            "GOOGLE_CLOUD_PROJECT": project,
            "LOCALCLOUD_USER": user,
        },
    )


class FakeClock:
    def __init__(self) -> None:
        self.now = 0.0

    def monotonic(self) -> float:
        return self.now

    def sleep(self, seconds: float) -> None:
        self.now += seconds


def _java_transport_error(
    *,
    status_code: int = 503,
    retryable: bool = True,
) -> HostError:
    return HostError(
        "java_mcp_unavailable",
        "Java LocalCloud MCP request failed",
        {
            "url": "http://127.0.0.1:49080/mcp",
            "method": "tools/call",
            "cause": f"HTTP {status_code}",
            "retryable": retryable,
            "status_code": status_code,
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
    image_id: str = "sha256:image",
    configured_image_id: str | None = "sha256:image",
    published_ports: dict[str, tuple[tuple[str, int], ...]] | None = None,
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
        connect_url="http://127.0.0.1:49080",
        endpoint_map={"5365": 49080},
        network_name=config.network_name or "localcloud",
        mount={
            "type": "volume",
            "source": config.data_volume,
            "destination": "/var/lib/localcloud",
            "mode": "rw",
            "read_write": True,
        },
        configured_image=config.image,
        configured_image_id=configured_image_id,
        actual_image=config.image,
        image_id=image_id,
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
        published_ports=published_ports
        or {
            f"{port}/tcp": (("127.0.0.1", port),)
            for port in range(5365, 5376)
        },
    )


def _controller(tmp_path: Path) -> tuple[Controller, FakeRuntime, HostPaths]:
    paths = _paths(tmp_path)
    runtime = FakeRuntime()
    return Controller(runtime=cast(Any, runtime), paths=paths), runtime, paths


class _RuntimeObserver:
    def __init__(self) -> None:
        self.logs: list[str] = []
        self.debug_messages: list[str] = []

    def runtime_logs(self, value: str) -> None:
        self.logs.append(value)

    def debug(self, value: str) -> None:
        self.debug_messages.append(value)


def test_start_uses_selected_project_as_context_without_creating_it(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(
        tmp_path,
        paths=paths,
        project="new-project",
        user="agent@example.test",
    )
    FakeJavaClient.catalog_error = _java_transport_error(
        status_code=403,
        retryable=False,
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
    assert FakeJavaClient.create_calls == 0
    active = load_active_runtime(paths)
    assert active is not None
    assert active.data_volume == config.data_volume
    assert active.image == config.image
    assert active.container_id == "container-1"


def test_start_reports_docker_logs_to_observer(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    _ = paths
    config = _config(tmp_path, paths=paths)
    observer = _RuntimeObserver()

    result = controller.start(config, observer=observer)

    assert result["status"] == "started"
    assert runtime.log_calls
    # _emit_runtime_logs uses tail=12 for the progress excerpt.
    assert any(call[2] == 12 for call in runtime.log_calls)
    assert observer.logs == ["tail=12"]
    # _runtime_logs uses tail=20 for the result dict.
    assert result["logs"] == "tail=20"


def test_start_debug_emits_one_copyable_ranged_run_command(
    tmp_path: Path,
) -> None:
    controller, _runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    observer = _RuntimeObserver()

    controller.start(config, observer=observer)

    commands = [
        message
        for message in observer.debug_messages
        if message.startswith("docker run ")
    ]
    assert len(commands) == 1
    assert "5365-5366:5365-5366/tcp" in commands[0]
    assert not any(
        message.startswith(("Lifecycle action", "Published ports"))
        for message in observer.debug_messages
    )


def test_restart_reports_docker_logs_to_observer(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    _ = paths
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(config, state="exited")
    observer = _RuntimeObserver()

    result = controller.restart(config, observer=observer)

    assert result["status"] == "restarted"
    assert runtime.log_calls
    # _emit_runtime_logs uses tail=12 for the progress excerpt.
    assert any(call[2] == 12 for call in runtime.log_calls)
    assert observer.logs == ["tail=12"]
    # _runtime_logs uses tail=20 for the result dict.
    assert result["logs"] == "tail=20"


def test_restart_replacement_reports_runtime_logs_once(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(config)
    observer = _RuntimeObserver()

    result = controller.restart(config, pull=True, observer=observer)

    assert isinstance(result, dict)
    assert result["status"] == "restarted"
    assert observer.logs == ["tail=12"]


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
    changed = _config(tmp_path, paths=paths, yaml="host:\n  memory: 8g\n")

    result = controller.start(changed)

    assert result["status"] == "already_running"
    assert result["origin"] == "attached"
    assert result["container"]["id"] == "container-existing"
    assert runtime.removes == []
    assert runtime.creates == 0


def test_start_reuses_stopped_same_volume_container(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(config, state="exited")

    result = controller.start(config)

    assert result["status"] == "started"
    assert result["container"]["id"] == "container-existing"
    assert runtime.starts == 1
    assert runtime.creates == 0
    assert runtime.removes == []
    assert runtime.readiness_deadlines
    assert runtime.readiness_deadlines[0] is not None


def test_start_retries_transient_project_catalog_readiness(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(config)
    transient = _java_transport_error()
    FakeJavaClient.catalog_failures = [transient, transient]
    clock = FakeClock()
    monkeypatch.setattr(controller_module.time, "monotonic", clock.monotonic)
    monkeypatch.setattr(controller_module.time, "sleep", clock.sleep)

    result = controller.start(config, ensure_project=True)

    assert result["status"] == "already_running"
    assert result["container"]["id"] == "container-existing"
    assert clock.now == 2.0
    assert runtime.creates == 0
    assert runtime.starts == 0
    assert runtime.restarts == 0
    assert all(0 < timeout <= 5 for timeout in FakeJavaClient.timeouts)


def test_start_readiness_deadline_begins_after_slow_image_pull(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    clock = FakeClock()
    runtime.preflight_delay = 95.0
    monkeypatch.setattr(controller_module.time, "monotonic", clock.monotonic)
    monkeypatch.setattr(controller_module.time, "sleep", clock.sleep)

    result = controller.start(config)

    assert result["status"] == "started"
    assert clock.now == 95.0
    assert runtime.readiness_deadlines == [155.0]
    assert runtime.prepared_images == [(None, False)]


def test_start_replacement_deadline_begins_after_slow_image_pull(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(config)
    clock = FakeClock()
    runtime.preflight_delay = 95.0
    monkeypatch.setattr(controller_module.time, "monotonic", clock.monotonic)
    monkeypatch.setattr(controller_module.time, "sleep", clock.sleep)

    result = controller.start(config, pull=True)

    assert result["status"] == "started"
    assert clock.now == 95.0
    assert runtime.preflight_pulls == [True]
    assert runtime.readiness_deadlines == [155.0]
    assert runtime.prepared_images == [(None, True)]


def test_start_fails_immediately_for_permanent_project_error(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(config)
    FakeJavaClient.catalog_error = _java_transport_error(
        status_code=403,
        retryable=False,
    )
    monkeypatch.setattr(
        controller_module.time,
        "sleep",
        lambda _seconds: pytest.fail("permanent errors must not be retried"),
    )

    with pytest.raises(HostError) as caught:
        controller.start(config, ensure_project=True)

    assert caught.value.code == "project_create_failed"
    cause = caught.value.details["cause"]
    assert cause["details"]["status_code"] == 403
    assert cause["details"]["retryable"] is False
    assert runtime.creates == 0
    assert runtime.starts == 0
    assert runtime.restarts == 0


def test_start_project_readiness_uses_one_shared_deadline(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(config)
    FakeJavaClient.catalog_error = _java_transport_error()
    clock = FakeClock()
    monkeypatch.setattr(controller_module.time, "monotonic", clock.monotonic)
    monkeypatch.setattr(controller_module.time, "sleep", clock.sleep)

    with pytest.raises(HostError) as caught:
        controller.start(config, ensure_project=True)

    assert caught.value.code == "runtime_readiness_timeout"
    assert caught.value.details["phase"] == "project_catalog"
    assert caught.value.details["timeout_seconds"] == 60.0
    assert (
        caught.value.details["last_error"]["details"]["status_code"]
        == 503
    )
    assert clock.now == 60.0


def test_start_creates_project_and_applies_seed_once(
    tmp_path: Path,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    (tmp_path / "seed.yaml").write_text(
        "projects:\n  - projectId: new-project\n",
        encoding="utf-8",
    )
    config = _config(
        tmp_path,
        paths=paths,
        yaml="context:\n  project: new-project\nhost:\n  seed: seed.yaml\n",
    )
    runtime.record = _record(config)

    result = controller.start(config, ensure_project=True)

    assert result["status"] == "already_running"
    assert FakeJavaClient.create_calls == 1
    assert FakeJavaClient.seed_attempts == 1
    assert FakeJavaClient.seeds == [
        ("new-project", config.seed_yaml, False)
    ]
    assert runtime.creates == 0
    assert runtime.starts == 0
    assert runtime.restarts == 0


def test_start_observes_project_after_transient_create_response_failure(
    tmp_path: Path,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    (tmp_path / "seed.yaml").write_text(
        "projects:\n  - projectId: new-project\n",
        encoding="utf-8",
    )
    config = _config(
        tmp_path,
        paths=paths,
        yaml="context:\n  project: new-project\nhost:\n  seed: seed.yaml\n",
    )
    runtime.record = _record(config)
    FakeJavaClient.create_error = _java_transport_error()
    FakeJavaClient.create_commits_before_error = True

    result = controller.start(config, ensure_project=True)

    assert result["status"] == "already_running"
    assert FakeJavaClient.create_calls == 1
    assert FakeJavaClient.seed_attempts == 1
    assert FakeJavaClient.seeds == [
        ("new-project", config.seed_yaml, False)
    ]


def test_start_preserves_transient_create_failure_at_visibility_timeout(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(
        tmp_path,
        paths=paths,
        yaml="context:\n  project: new-project\n",
    )
    runtime.record = _record(config)
    FakeJavaClient.create_error = _java_transport_error()
    clock = FakeClock()
    monkeypatch.setattr(controller_module.time, "monotonic", clock.monotonic)
    monkeypatch.setattr(controller_module.time, "sleep", clock.sleep)

    with pytest.raises(HostError) as caught:
        controller.start(config, ensure_project=True)

    assert caught.value.code == "runtime_readiness_timeout"
    assert caught.value.details["phase"] == "project_visibility"
    last_error = caught.value.details["last_error"]
    assert last_error["details"]["status_code"] == 503
    assert last_error["details"]["retryable"] is True
    assert FakeJavaClient.create_calls == 1
    assert clock.now == 60.0


def test_start_waits_for_running_same_volume_container_without_restart(
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
        published_ports={
            f"{port}/tcp": (("127.0.0.1", 5508 + offset),)
            for offset, port in enumerate(range(5365, 5376))
        },
    )
    runtime.ready = False

    result = controller.start(config)

    assert result["status"] == "already_running"
    assert result["container"]["id"] == "container-existing"
    assert runtime.starts == 0
    assert runtime.restarts == 0
    assert runtime.creates == 0
    assert runtime.readiness_deadlines
    assert runtime.readiness_deadlines[0] is not None


def test_start_does_not_restart_running_attached_container_when_wait_fails(
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
    runtime.wait_error = HostError(
        "health_timeout",
        "LocalCloud did not become healthy",
    )

    with pytest.raises(HostError) as caught:
        controller.start(config)

    assert caught.value is runtime.wait_error
    assert runtime.starts == 0
    assert runtime.restarts == 0
    assert runtime.creates == 0



def test_reconfiguration_preflight_preserves_current_runtime_on_failure(
    tmp_path: Path,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    original = _config(tmp_path, paths=paths)
    runtime.record = _record(original)
    runtime.preflight_error = HostError(
        "managed_image_capability_missing", "replacement is incompatible"
    )
    changed = _config(tmp_path, paths=paths, yaml="host:\n  memory: 8g\n")

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
    changed = _config(tmp_path, paths=paths, yaml="host:\n  memory: 8g\n")

    result = controller.start(changed)

    assert result["status"] == "reconfigured"
    assert result["changed_fields"] == ["config_path", "memory"]
    assert runtime.removes == [False]
    assert runtime.creates == 1


def test_reconfigure_with_unchanged_network_name_never_tears_down_network(
    tmp_path: Path,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    original = _config(tmp_path, paths=paths)
    runtime.record = _record(original)
    changed = _config(tmp_path, paths=paths, yaml="host:\n  memory: 8g\n")

    result = controller.start(changed)

    # A memory change is unrelated to the network - `docker network create`
    # never varies with it, so the still-valid network must be left alone
    # instead of being torn down and recreated.
    assert result["status"] == "reconfigured"
    assert runtime.remove_network_calls == [False]
    assert runtime.preview_network_exists[-1] is True


def test_reconfigure_to_ephemeral_preserves_existing_persistent_volume(
    tmp_path: Path,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    original = _config(tmp_path, paths=paths)
    assert original.data == "persistent"
    runtime.record = _record(original)
    changed = _config(tmp_path, paths=paths, yaml="host:\n  data: ephemeral\n")

    result = controller.start(changed)

    assert result["status"] == "reconfigured"
    # The existing volume already holds persistent data; an implicit
    # reconfigure must never discard it just because the new config no
    # longer requests persistence, since `remove()` runs before `create()`
    # can prove the replacement will actually succeed.
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
    changed = _config(tmp_path, paths=paths, yaml="host:\n  memory: 8g\n")

    result = controller.restart(changed)

    assert result["status"] == "restarted"
    assert result["container"]["id"] == "container-existing"
    assert runtime.restarts == 1
    assert runtime.removes == []


def test_restart_with_pull_replaces_runtime_and_requests_image_pull(
    tmp_path: Path,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(config)

    result = controller.restart(config, pull=True)

    assert result["status"] == "restarted"
    assert runtime.preflight_pulls == [True]
    assert runtime.removes == [False]
    assert runtime.creates == 1
    assert runtime.restarts == 0


def test_restart_without_pull_restarts_existing_container(
    tmp_path: Path,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(config)

    result = controller.restart(config, pull=False)

    assert result["status"] == "restarted"
    assert runtime.preflight_pulls == []
    assert runtime.removes == []
    assert runtime.creates == 0
    assert runtime.restarts == 1


def test_restart_replaces_managed_runtime_with_noncanonical_ports(
    tmp_path: Path,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(
        config,
        published_ports={
            f"{port}/tcp": (("127.0.0.1", 5508 + offset),)
            for offset, port in enumerate(range(5365, 5376))
        },
    )

    result = controller.restart(config)

    assert result["status"] == "reconfigured"
    assert result["changed_fields"] == ["ports"]
    assert runtime.removes == [False]
    assert runtime.creates == 1
    assert runtime.restarts == 0


def test_restart_requires_confirmation_before_alternative_port_replacement(
    tmp_path: Path,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    alternative_ports = {
        f"{port}/tcp": (("127.0.0.1", 5508 + offset),)
        for offset, port in enumerate(range(5365, 5376))
    }
    runtime.record = _record(config, published_ports=alternative_ports)
    runtime.planned_ports = alternative_ports

    with pytest.raises(HostError) as caught:
        controller.restart(config)

    assert caught.value.code == "port_mapping_confirmation_required"
    assert caught.value.details["mappings"][0] == {
        "host_ip": "127.0.0.1",
        "host_port": 5508,
        "container_port": 5365,
        "protocol": "tcp",
    }
    assert runtime.removes == []
    assert runtime.creates == 0


def test_start_requires_confirmation_before_alternative_port_create(
    tmp_path: Path,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.planned_ports = {
        f"{port}/tcp": (("127.0.0.1", 5508 + offset),)
        for offset, port in enumerate(range(5365, 5376))
    }

    with pytest.raises(HostError) as caught:
        controller.start(config)

    assert caught.value.code == "port_mapping_confirmation_required"
    assert runtime.creates == 0


def test_restart_declined_alternative_mapping_does_not_mutate_docker(
    tmp_path: Path,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    alternative_ports = {
        f"{port}/tcp": (("127.0.0.1", 5508 + offset),)
        for offset, port in enumerate(range(5365, 5376))
    }
    runtime.record = _record(config, published_ports=alternative_ports)
    runtime.planned_ports = alternative_ports

    with pytest.raises(HostError) as caught:
        controller.restart(config, confirm_port_mapping=lambda _plan: False)

    assert caught.value.code == "port_mapping_declined"
    assert runtime.removes == []
    assert runtime.creates == 0


def test_restart_dry_run_prints_alternative_mapping_without_confirmation(
    tmp_path: Path,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    alternative_ports = {
        f"{port}/tcp": (("127.0.0.1", 5508 + offset),)
        for offset, port in enumerate(range(5365, 5376))
    }
    runtime.record = _record(config, published_ports=alternative_ports)
    runtime.planned_ports = alternative_ports

    result = controller.restart(
        config,
        dry_run=True,
        confirm_port_mapping=lambda _plan: pytest.fail(
            "dry-run must not request confirmation"
        ),
    )

    assert isinstance(result, str)
    assert "127.0.0.1:5508-5518:5365-5375/tcp" in result
    assert runtime.removes == []
    assert runtime.creates == 0


def test_restart_accepts_confirmed_alternative_mapping(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    alternative_ports = {
        f"{port}/tcp": (("127.0.0.1", 5508 + offset),)
        for offset, port in enumerate(range(5365, 5376))
    }
    runtime.record = _record(config, published_ports=alternative_ports)
    runtime.planned_ports = alternative_ports
    proposed: list[DockerRunPlan] = []

    def confirm(plan: DockerRunPlan) -> bool:
        proposed.append(plan)
        return True

    result = controller.restart(
        config,
        confirm_port_mapping=confirm,
    )

    assert result["status"] == "reconfigured"
    assert proposed[0].ports == alternative_ports
    assert runtime.removes == [False]
    assert runtime.creates == 1


def test_start_with_pull_replaces_running_runtime(
    tmp_path: Path,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(config)

    result = controller.start(config, pull=True)

    assert result["status"] == "started"
    assert runtime.preflight_pulls == [True]
    assert runtime.removes == [False]
    assert runtime.creates == 1

def test_restart_replaces_container_when_local_image_id_is_updated(
    tmp_path: Path,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(
        config,
        image_id="sha256:old-image",
        configured_image_id="sha256:new-image",
    )

    result = controller.restart(config)

    assert result["status"] == "reconfigured"
    assert result["changed_fields"] == ["image"]
    assert runtime.removes == [False]
    assert runtime.creates == 1
    assert runtime.restarts == 0


def test_restart_replaces_container_when_services_override_changes(
    tmp_path: Path,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    original = _config(tmp_path, paths=paths)
    runtime.record = _record(original)
    changed = _config(tmp_path, paths=paths, services=["bigquery"])

    result = controller.restart(changed)

    assert result["status"] == "reconfigured"
    assert result["changed_fields"] == [
        "docker_socket",
        "effective_services",
        "services",
    ]
    assert runtime.removes == [False]
    assert runtime.creates == 1
    assert runtime.restarts == 0


def test_start_attaches_to_running_runtime_without_replacing_when_local_image_differs(
    tmp_path: Path,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(
        config,
        image_id="sha256:old-image",
        configured_image_id="sha256:new-image",
    )

    result = controller.start(config)

    assert result["status"] == "already_running"
    assert runtime.removes == []
    assert runtime.creates == 0

def test_stop_preserves_attached_ephemeral_runtime(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths, yaml="host:\n  data: ephemeral\n")
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
    assert result["container"]["name"] == config.container_name
    assert result["container"]["id"] == "container-existing"
    assert runtime.stops == 1
    assert runtime.removes == []


@pytest.mark.parametrize("data", ["persistent", "ephemeral"])
def test_stop_reports_not_running_without_mutating_stopped_runtime(
    tmp_path: Path, data: str,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = replace(_config(tmp_path, paths=paths), data=data)
    runtime.record = replace(_record(config), state="exited", health=None, url=None)

    result = controller.stop(config)

    assert result["status"] == "not_running"
    assert result["container"]["name"] == config.container_name
    assert result["container"]["id"] == "container-existing"
    assert result["container"]["state"] == "exited"
    assert runtime.stops == 0
    assert runtime.removes == []


def test_stop_removes_fully_managed_ephemeral_runtime(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths, yaml="host:\n  data: ephemeral\n")
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
    assert runtime.removes == []
    assert runtime.creates == 0


def test_reset_all_refuses_and_prints_manual_volume_steps(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(config)

    with pytest.raises(HostError) as caught:
        controller.reset(config, all_projects=True)

    assert caught.value.code == "manual_volume_removal_required"
    steps = caught.value.details["steps"]
    assert any(
        line == f"docker volume rm -f {config.data_volume}" for line in steps
    )
    assert any(line.startswith("localcloud start ") for line in steps)
    assert runtime.removes == []
    assert runtime.creates == 0


def test_reset_all_dry_run_renders_manual_steps_without_mutating(
    tmp_path: Path,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(config)

    plan = controller.reset(config, all_projects=True, dry_run=True)

    assert isinstance(plan, str)
    assert "# action: reset-all" in plan
    assert f"docker volume rm -f {config.data_volume}" in plan
    assert runtime.removes == []
    assert runtime.creates == 0


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
    assert result["container"]["image_details"]["formatted"] == (
        "(Local: ID: qualified , sha256:qualified)"
    )
    assert result["mount"]["source"] == "team-data"


def test_status_looks_up_details_for_the_image_it_renders(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = replace(
        _record(config),
        configured_image="example/custom-localcloud:latest",
    )

    result = controller.status(config)

    assert result["container"]["configured_image"] == (
        "example/custom-localcloud:latest"
    )
    assert runtime.image_details_images == ["example/custom-localcloud:latest"]


def test_status_uses_server_reported_effective_services_when_ready(
    tmp_path: Path,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(config)
    runtime.effective = ("bigtable", "gcs")

    result = controller.status(config)

    assert result["services"] == ["bigtable", "gcs"]


def test_status_reports_services_unavailable_when_server_state_cannot_be_read(
    tmp_path: Path,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(config)
    runtime.effective = None

    result = controller.status(config)

    assert result["services"] == "unavailable"


def test_absent_status_reports_services_unavailable_rather_than_configured_yaml(
    tmp_path: Path,
) -> None:
    controller, _runtime, paths = _controller(
        tmp_path
    )
    config = _config(
        tmp_path, paths=paths, yaml="services:\n  enabled: [gcs]\n"
    )

    result = controller.status(config)

    assert result["status"] == "not_created"
    assert result["services"] == "unavailable"


def test_target_returns_only_connection_context(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(config)
    resolved: list[str] = []

    result = controller.target(config, on_url_resolved=resolved.append)

    assert result == {
        "url": "http://127.0.0.1:49080",
        "connect_url": "http://127.0.0.1:49080",
        "endpoint_map": {"5365": 49080},
    }
    assert resolved == ["http://127.0.0.1:49080"]


def test_target_readiness_timeout_is_bounded_and_reports_resolved_url(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(config)
    runtime.ready = False
    resolved: list[str] = []
    clock = FakeClock()
    monkeypatch.setattr(controller_module.time, "monotonic", clock.monotonic)
    monkeypatch.setattr(controller_module.time, "sleep", clock.sleep)

    with pytest.raises(HostError) as caught:
        controller.target(
            config,
            readiness_timeout=10.0,
            on_url_resolved=resolved.append,
        )

    assert caught.value.code == "runtime_readiness_timeout"
    assert caught.value.details["url"] == "http://127.0.0.1:49080"
    assert caught.value.details["timeout_seconds"] == 10.0
    assert resolved == ["http://127.0.0.1:49080"]
    assert clock.now == 10.0
    assert runtime.ready_timeouts
    assert all(0 < timeout <= 3.0 for timeout in runtime.ready_timeouts)


def test_target_readiness_timeout_bounds_project_catalog_request(
    tmp_path: Path,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(config)

    result = controller.target(config, readiness_timeout=10.0)

    assert result["url"] == "http://127.0.0.1:49080"
    assert FakeJavaClient.timeouts[-1] == 5.0


def test_target_recomputes_project_budget_after_health_request(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(config)
    runtime.ready_delay = 2.5
    clock = FakeClock()
    monkeypatch.setattr(controller_module.time, "monotonic", clock.monotonic)
    monkeypatch.setattr(controller_module.time, "sleep", clock.sleep)

    result = controller.target(config, readiness_timeout=3.0)

    assert result["url"] == "http://127.0.0.1:49080"
    assert clock.now == 2.5
    assert FakeJavaClient.timeouts[-1] == 0.5


def test_target_timeout_does_not_retry_nonretryable_project_error(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(config)
    FakeJavaClient.catalog_error = _java_transport_error(
        status_code=403,
        retryable=False,
    )
    clock = FakeClock()
    monkeypatch.setattr(controller_module.time, "monotonic", clock.monotonic)
    monkeypatch.setattr(controller_module.time, "sleep", clock.sleep)

    with pytest.raises(HostError) as caught:
        controller.target(config, readiness_timeout=10.0)

    assert caught.value.code == "project_lookup_failed"
    assert clock.now == 0.0


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
    config = _config(tmp_path, paths=paths, yaml="host:\n  memory: 6g\n")
    runtime.record = _record(config)

    assert controller.remembered_config(config) == str(
        (tmp_path / "localcloud.yaml").resolve()
    )
    assert controller.logs(config, tail=17)["logs"] == "tail=17"
    assert len(runtime.preferred) == 1


def test_mutating_command_revalidates_remembered_runtime(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(config)

    controller.remembered_config(config)
    controller.stop(config, dry_run=True)

    assert len(runtime.preferred) == 2


def test_remembered_config_marks_resolved_builtin_defaults(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(config)

    assert controller.remembered_config(config) == DEFAULTS_CONFIG_LABEL


def test_failed_seed_does_not_update_active_runtime(tmp_path: Path) -> None:
    controller, _runtime, paths = _controller(tmp_path)
    (tmp_path / "seed.yaml").write_text(
        "projects:\n  - projectId: local-gcp-project\n", encoding="utf-8"
    )
    config = _config(
        tmp_path, paths=paths, yaml="host:\n  seed: seed.yaml\n"
    )
    FakeJavaClient.fail_seed = True

    with pytest.raises(HostError) as caught:
        controller.start(config)

    assert caught.value.code == "seed_failed"
    assert FakeJavaClient.seed_attempts == 1
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


def test_doctor_reports_stale_active_state_warning(tmp_path: Path) -> None:
    controller, _runtime, paths = _controller(tmp_path)
    paths.home.mkdir(parents=True)
    active = ActiveRuntime(
        schema_version=ACTIVE_RUNTIME_SCHEMA_VERSION,
        data_volume="default",
        image="jaysen2apache/localcloud:latest",
        container_id="c" * 12,
        container_name="localcloud-default",
        network_name="localcloud-default",
    )
    save_active_runtime(paths, active)

    result = controller.doctor()

    assert result["status"] == "ok"
    assert result["active_runtime"]["state"] == "stale"
    assert "Last active container info is stale at ~/.localcloud/active.json" in result["warning"]


def _seed_cleanup_state(paths: HostPaths, runtime: FakeRuntime) -> None:
    paths.home.mkdir(parents=True, exist_ok=True)
    paths.locks.mkdir(parents=True, exist_ok=True)
    (paths.home / "active-runtime.json").write_text(
        json.dumps({"schema_version": 999}), encoding="utf-8"
    )
    (paths.home / "state.db").touch()
    (paths.home / "daemon.lock").touch()
    lock_name = "a" * 64 + ".lock"
    (paths.locks / lock_name).touch()
    (paths.locks / "active-runtime.lock").touch()
    runtime.doctor_report = {
        "status": "ok",
        "invalid_ownership": [
            {"kind": "container", "name": "broken", "error": {}}
        ],
    }


def test_cleanup_dry_run_reports_candidates_without_removing(
    tmp_path: Path,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    _seed_cleanup_state(paths, runtime)

    result = controller.cleanup(dry_run=True)

    assert result["dry_run"] is True
    assert result["docker_resources"] == [
        {"kind": "container", "name": "broken", "error": {}}
    ]
    assert result["active_runtime_stale"] is True
    assert "state.db" in result["legacy_host_state"]
    assert "daemon.lock" in result["legacy_host_state"]
    assert any(name.endswith(".lock") and len(name) == 69 for name in result["legacy_locks"])
    # Files preserved in dry-run mode.
    assert (paths.home / "active-runtime.json").exists()
    assert (paths.home / "state.db").exists()
    assert (paths.home / "daemon.lock").exists()
    assert (paths.locks / ("a" * 64 + ".lock")).exists()


def test_cleanup_defaults_to_removing_state(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    _seed_cleanup_state(paths, runtime)

    result = controller.cleanup()

    assert result["dry_run"] is False
    assert result["docker_resources"] == [
        {"kind": "container", "name": "broken"}
    ]
    assert not (paths.home / "active-runtime.json").exists()
    assert not (paths.home / "state.db").exists()
    assert not (paths.home / "daemon.lock").exists()
    assert not (paths.locks / ("a" * 64 + ".lock")).exists()
    # active-runtime.lock is not a hash lock and must survive.
    assert (paths.locks / "active-runtime.lock").exists()


def test_cleanup_with_nothing_to_clean(tmp_path: Path) -> None:
    controller, _runtime, paths = _controller(tmp_path)
    paths.home.mkdir(parents=True)

    result = controller.cleanup()

    assert result["status"] == "ok"
    assert result["docker_resources"] == []
    assert result["active_runtime_stale"] is False
    assert result["legacy_host_state"] == []
    assert result["legacy_locks"] == []
    assert result["failures"] == []


def test_start_includes_image_status_and_logs(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)

    result = controller.start(config)

    assert result["container"]["image_status"] == "available locally"
    assert "logs" in result
    assert isinstance(result["logs"], str)


def test_doctor_includes_image_details(tmp_path: Path) -> None:
    controller, _runtime, paths = _controller(tmp_path)
    paths.home.mkdir(parents=True)

    result = controller.doctor()

    assert "(Local: ID: qualified , sha256:qualified)" in result["default_image"]
    assert result["image_details"]["location"] == "Local"
    assert "image_status" not in result


def test_status_includes_image_status_for_absent_runtime(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)

    result = controller.status(config)

    assert result["status"] == "not_created"
    assert result["container"]["image_status"] == "available locally"
    assert runtime.image_details_images == [config.image]
    assert runtime.image_status_images == []


def test_status_reuses_doctor_details_for_image_not_available_locally(
    tmp_path: Path,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.image_details_result = {
        "location": "Remote",
        "image_id": "not available locally",
        "sha": "unknown",
        "formatted": "(not available locally)",
    }

    result = controller.status(config)

    assert result["container"]["configured_image"] == config.image
    assert result["container"]["image_details"]["formatted"] == (
        "(not available locally)"
    )
    assert result["container"]["image_status"] == "not available locally"
    assert runtime.image_status_images == []


def test_start_tails_runtime_logs_with_specified_duration(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    observer = _RuntimeObserver()

    time_seq = [100.0, 100.2, 100.5, 101.1]
    monkeypatch.setattr(
        controller_module.time,
        "monotonic",
        lambda: time_seq.pop(0) if time_seq else 102.0,
    )
    monkeypatch.setattr(controller_module.time, "sleep", lambda _s: None)

    result = controller.start(config, observer=observer, tail=1.0)
    assert result["status"] == "started"
    assert any(call[2] == 100 for call in runtime.log_calls)


def test_start_with_zero_tail_does_not_tail_after_readiness(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    observer = _RuntimeObserver()

    result = controller.start(config, observer=observer, tail=0.0)
    assert result["status"] == "started"
    # tail=100 is used for post-readiness tailing; tail=0 means no post-readiness calls
    assert not any(call[2] == 100 for call in runtime.log_calls)


def test_tail_runtime_logs_handles_continuous_mode_on_interrupt(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    observer = _RuntimeObserver()

    runtime.record = _record(config)
    calls = [0]

    def interrupting_logs(*args: Any, **kwargs: Any) -> str:
        calls[0] += 1
        if calls[0] > 1:
            raise KeyboardInterrupt()
        return "log line"

    runtime.logs = interrupting_logs  # type: ignore[assignment]
    controller._tail_runtime_logs(observer, config, runtime.record, tail=-1.0)
    assert observer.logs == ["log line"]


def test_start_when_already_running_skips_tailing_and_observer_starting(
    tmp_path: Path,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(config)

    class CustomObserver:
        def __init__(self) -> None:
            self.started_calls = 0
            self.logs: list[str] = []

        def starting(self, _cfg: Any) -> None:
            self.started_calls += 1

        def runtime_logs(self, value: str) -> None:
            self.logs.append(value)

    observer = CustomObserver()
    result = controller.start(config, observer=observer, tail=5.0)
    assert result["status"] == "already_running"
    assert observer.started_calls == 0
    assert observer.logs == []
    assert result.get("logs") is None


def test_start_dry_run_renders_exact_run_without_mutating(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)

    result = controller.start(config, dry_run=True)

    assert isinstance(result, str)
    assert "# action: create" in result
    assert "docker run -d --name localcloud" in result
    assert "-p 127.0.0.1:5365-5366:5365-5366/tcp" in result
    assert runtime.creates == 0
    assert runtime.starts == 0
    assert runtime.removes == []
    assert not paths.locks.exists()
    assert load_active_runtime(paths) is None


def test_dry_run_rejects_pull_without_inspecting_or_mutating(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)

    with pytest.raises(HostError) as caught:
        controller.start(config, pull=True, dry_run=True)

    assert caught.value.code == "dry_run_pull_conflict"
    assert runtime.preflights == []
    assert runtime.creates == 0


def test_restart_without_runtime_initializes_status(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)

    result = controller.restart(config)

    assert isinstance(result, dict)
    assert result["status"] == "restarted"
    assert runtime.creates == 1


def test_reset_and_stop_dry_runs_do_not_mutate(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(config)

    project_plan = controller.reset(config, dry_run=True)
    all_plan = controller.reset(config, all_projects=True, dry_run=True)
    runtime.record = replace(
        runtime.record,
        data="ephemeral",
        origin="managed",
    )
    stop_plan = controller.stop(config, dry_run=True)

    assert isinstance(project_plan, str)
    assert "[LocalCloud API] reset project=" in project_plan
    assert isinstance(all_plan, str)
    assert "# action: reset-all" in all_plan
    assert isinstance(stop_plan, str)
    assert "# action: remove" in stop_plan
    assert runtime.creates == 0
    assert runtime.removes == []
    assert runtime.stops == 0


def test_reused_start_debug_reports_copyable_ranged_run_command(
    tmp_path: Path,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(config)
    observer = _RuntimeObserver()

    result = controller.start(config, observer=observer)

    assert isinstance(result, dict)
    assert result["status"] == "already_running"
    commands = [
        message
        for message in observer.debug_messages
        if message.startswith("docker run ")
    ]
    assert len(commands) == 1
    assert "5365-5366:5365-5366/tcp" in commands[0]


def test_reset_dry_run_plans_managed_unready_restart(tmp_path: Path) -> None:
    controller, runtime, paths = _controller(tmp_path)
    config = _config(tmp_path, paths=paths)
    runtime.record = _record(config)
    runtime.ready = False

    plan = controller.reset(config, dry_run=True)

    assert isinstance(plan, str)
    assert "docker restart -t 20 localcloud" in plan
    assert runtime.restarts == 0


def test_reset_dry_run_rejects_unready_attached_runtime(tmp_path: Path) -> None:
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
        controller.reset(config, dry_run=True)

    assert caught.value.code == "attached_runtime_unhealthy"
    assert runtime.restarts == 0


def test_replacement_preview_inspects_different_target_network(
    tmp_path: Path,
) -> None:
    controller, runtime, paths = _controller(tmp_path)
    original = _config(tmp_path, paths=paths)
    runtime.record = _record(original)
    changed = _config(
        tmp_path,
        paths=paths,
        network_name="localcloud-target-network",
    )

    plan = controller.start(changed, dry_run=True)

    # Renaming the network is the one case where it genuinely needs to be
    # torn down and recreated under the new name.
    assert runtime.preview_remove_network == [True]
    assert isinstance(plan, str)
    assert runtime.preview_network_exists[-1] is None
