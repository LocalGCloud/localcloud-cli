from __future__ import annotations

import threading
import time
from pathlib import Path

import pytest

from localcloud_cli.config import HostPaths
from localcloud_cli.errors import (
    ConfigError,
    DockerError,
    EndpointError,
    HostError,
    OwnershipError,
    ReadinessError,
    StateError,
)
from localcloud_cli.state import (
    ActiveRuntime,
    clear_active_runtime,
    data_volume_lock,
    load_active_runtime,
    save_active_runtime,
    validate_data_volume,
)


def test_typed_errors_inherit_from_host_error() -> None:
    error = DockerError("docker_error", "Docker failed", {"hint": "check daemon"})
    assert isinstance(error, HostError)
    assert error.code == "docker_error"
    assert error.message == "Docker failed"
    assert error.to_dict() == {
        "error": True,
        "code": "docker_error",
        "message": "Docker failed",
        "details": {"hint": "check daemon"},
    }

    for err_cls in (ConfigError, ReadinessError, EndpointError, OwnershipError, StateError):
        instance = err_cls("code", "msg")
        assert isinstance(instance, HostError)
        assert instance.code == "code"


def test_active_runtime_persistence_and_loading(tmp_path: Path) -> None:
    paths = HostPaths(home=tmp_path, locks=tmp_path / "locks")
    assert load_active_runtime(paths) is None

    runtime = ActiveRuntime(
        schema_version=3,
        data_volume="test-vol",
        image="localcloud:latest",
        container_id="cid-123",
        container_name="localcloud-test",
        network_name="localcloud-net",
    )
    save_active_runtime(paths, runtime)

    loaded = load_active_runtime(paths, data_volume="test-vol")
    assert loaded is not None
    assert loaded.data_volume == "test-vol"
    assert loaded.image == "localcloud:latest"
    assert loaded.container_id == "cid-123"
    assert loaded.container_name == "localcloud-test"

    clear_active_runtime(paths)
    assert load_active_runtime(paths) is None


def test_data_volume_lock_concurrent_threads(tmp_path: Path) -> None:
    paths = HostPaths(home=tmp_path, locks=tmp_path / "locks")
    vol_name = "concurrent-vol"
    execution_order: list[str] = []
    concurrency_detected: list[bool] = []
    active_in_critical_section = 0
    lock_monitor = threading.Lock()

    def worker(worker_id: str) -> None:
        nonlocal active_in_critical_section
        with data_volume_lock(paths, vol_name):
            with lock_monitor:
                active_in_critical_section += 1
                if active_in_critical_section > 1:
                    concurrency_detected.append(True)
            time.sleep(0.05)
            execution_order.append(worker_id)
            with lock_monitor:
                active_in_critical_section -= 1

    t1 = threading.Thread(target=worker, args=("w1",))
    t2 = threading.Thread(target=worker, args=("w2",))
    t1.start()
    t2.start()
    t1.join()
    t2.join()

    assert not concurrency_detected
    assert len(execution_order) == 2
