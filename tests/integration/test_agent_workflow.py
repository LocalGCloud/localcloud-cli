from __future__ import annotations

import json
import time
from pathlib import Path
from typing import Any

import httpx
import pytest

from integration._support import assert_loopback_url, default_runtime_for
from localcloud_cli.cli import main
from localcloud_cli.constants import DEFAULT_DATA_VOLUME, DEFAULT_PROJECT, DEFAULT_USER
from localcloud_cli.docker_runtime import DockerRuntime, RuntimeRecord
from localcloud_cli.errors import HostError
from localcloud_cli.java_client import JavaMcpClient, is_retryable_java_error


pytestmark = pytest.mark.docker

_READINESS_TIMEOUT = 60.0
_READINESS_POLL_INTERVAL = 1.0
_READINESS_REQUEST_TIMEOUT = 10.0
_SEEDED_PROJECT = "local-project"
_SEEDED_GCS_BUCKETS = {"app-assets", "demo-bucket", "user-profiles"}
_SEEDED_BIGQUERY_DATASETS = {"app_analytics", "dataset"}


def _invoke(
    capsys: pytest.CaptureFixture[str],
    *arguments: str,
    verbose: bool = True,
) -> dict[str, Any]:
    argv = [*arguments]
    if verbose:
        argv.append("--verbose")
    code = main(argv)
    captured = capsys.readouterr()
    assert code == 0, captured.err
    return json.loads(captured.out)


def _error_text(error: Exception) -> str:
    if isinstance(error, HostError):
        return json.dumps(error.to_dict(), sort_keys=True)
    return f"{type(error).__name__}: {error}"


def _request_timeout(deadline: float) -> float:
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise TimeoutError("default runtime readiness deadline elapsed")
    return min(_READINESS_REQUEST_TIMEOUT, remaining)


def _read_seeded_data(
    current: RuntimeRecord,
    *,
    deadline: float,
) -> dict[str, Any]:
    gcs = JavaMcpClient(
        current.url,
        _SEEDED_PROJECT,
        DEFAULT_USER,
        timeout=_request_timeout(deadline),
    ).tool(
        "localcloud_browse_resources",
        {"service": "gcs", "project": _SEEDED_PROJECT},
    )
    bigquery = JavaMcpClient(
        current.url,
        _SEEDED_PROJECT,
        DEFAULT_USER,
        timeout=_request_timeout(deadline),
    ).tool(
        "localcloud_browse_resources",
        {"service": "bigquery", "project": _SEEDED_PROJECT},
    )
    buckets = {
        str(bucket.get("name"))
        for bucket in gcs.get("buckets", [])
        if isinstance(bucket, dict)
    }
    datasets = {
        str(dataset.get("datasetReference", {}).get("datasetId"))
        for dataset in bigquery.get("datasets", [])
        if isinstance(dataset, dict)
    }
    missing_buckets = _SEEDED_GCS_BUCKETS - buckets
    missing_datasets = _SEEDED_BIGQUERY_DATASETS - datasets
    if missing_buckets or missing_datasets:
        raise RuntimeError(
            "seeded data is incomplete: "
            f"missing_buckets={sorted(missing_buckets)!r}, "
            f"missing_datasets={sorted(missing_datasets)!r}"
        )

    headers = {
        "X-LocalCloud-Project": _SEEDED_PROJECT,
        "X-LocalCloud-User": DEFAULT_USER,
    }
    gcs_url = (
        f"http://127.0.0.1:{int(current.endpoint_map['5366'])}"
        "/storage/v1/b/demo-bucket"
    )
    bigquery_url = (
        f"http://127.0.0.1:{int(current.endpoint_map['5372'])}"
        f"/bigquery/v2/projects/{_SEEDED_PROJECT}/datasets/app_analytics"
    )
    with httpx.Client(headers=headers) as client:
        gcs_response = client.get(
            gcs_url,
            timeout=_request_timeout(deadline),
        )
        gcs_response.raise_for_status()
        bigquery_response = client.get(
            bigquery_url,
            timeout=_request_timeout(deadline),
        )
        bigquery_response.raise_for_status()
    gcs_payload = gcs_response.json()
    bigquery_payload = bigquery_response.json()
    if gcs_payload.get("name") != "demo-bucket":
        raise RuntimeError("seeded GCS bucket payload is invalid")
    dataset_reference = bigquery_payload.get("datasetReference", {})
    if dataset_reference.get("projectId") != _SEEDED_PROJECT:
        raise RuntimeError("seeded BigQuery dataset project is invalid")
    if dataset_reference.get("datasetId") != "app_analytics":
        raise RuntimeError("seeded BigQuery dataset payload is invalid")
    return {
        "project": _SEEDED_PROJECT,
        "gcs_buckets": sorted(buckets),
        "bigquery_datasets": sorted(datasets),
    }


def _wait_for_default_seeded_runtime(
    current: RuntimeRecord,
) -> dict[str, Any]:
    deadline = time.monotonic() + _READINESS_TIMEOUT
    last_error: Exception | None = None
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            pytest.fail(
                "default_runtime_prerequisite_unavailable: "
                f"runtime health, default project {DEFAULT_PROJECT!r}, or "
                f"built-in seed data for {_SEEDED_PROJECT!r} did not become "
                f"readable within {_READINESS_TIMEOUT:.0f}s; "
                f"last_error={_error_text(last_error) if last_error else 'none'}"
            )
        java = JavaMcpClient(
            current.url,
            DEFAULT_PROJECT,
            DEFAULT_USER,
            timeout=_request_timeout(deadline),
        )
        try:
            health = httpx.get(
                f"{current.url}/health",
                timeout=_request_timeout(deadline),
            )
            health.raise_for_status()
            health_payload = health.json()
            if health_payload.get("status") not in {"healthy", "ok", "ready"}:
                raise RuntimeError(
                    f"default runtime health returned {health_payload!r}"
                )
            if not java.project_exists():
                last_error = RuntimeError(
                    f"default project {DEFAULT_PROJECT!r} is not visible"
                )
            else:
                return _read_seeded_data(current, deadline=deadline)
        except Exception as error:
            if isinstance(error, HostError) and not is_retryable_java_error(error):
                pytest.fail(
                    "default_runtime_prerequisite_unavailable: "
                    f"{_error_text(error)}"
                )
            last_error = error
        remaining = deadline - time.monotonic()
        if remaining > 0:
            time.sleep(min(_READINESS_POLL_INTERVAL, remaining))


def _resource_identity(resource: Any) -> str:
    return str(
        getattr(resource, "id", None)
        or getattr(resource, "name", None)
        or ""
    )


def _runtime_identity_snapshot(
    runtime: DockerRuntime,
    current: RuntimeRecord,
) -> dict[str, Any]:
    container = runtime.client.containers.get(current.container_id)
    container.reload()
    volume = runtime.client.volumes.get(current.data_volume)
    volume.reload()
    network = (
        runtime.client.networks.get(current.network_name)
        if current.network_name
        else None
    )
    if network is not None:
        network.reload()
    container_state = container.attrs.get("State", {})
    return {
        "data_volume": current.data_volume,
        "container_id": _resource_identity(container),
        "container_name": current.name,
        "container_state": current.state,
        "container_created_at": container.attrs.get("Created"),
        "container_started_at": container_state.get("StartedAt"),
        "container_finished_at": container_state.get("FinishedAt"),
        "container_restart_count": container.attrs.get("RestartCount"),
        "container_labels": dict(getattr(container, "labels", {}) or {}),
        "network_name": current.network_name,
        "network_id": _resource_identity(network) if network is not None else None,
        "network_labels": (
            dict(getattr(network, "labels", {}) or {})
            if network is not None
            else None
        ),
        "volume_id": _resource_identity(volume),
        "volume_labels": dict(getattr(volume, "labels", {}) or {}),
        "mount": dict(current.mount),
        "image_id": current.image_id,
        "actual_image": current.actual_image,
        "endpoint_map": dict(current.endpoint_map),
        "ownership": dict(current.ownership),
    }


def _guard_against_runtime_mutation(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def forbidden(operation: str):
        def fail(*_args: Any, **_kwargs: Any) -> None:
            pytest.fail(f"read-only integration path invoked {operation}")

        return fail

    for method in ("create", "start", "restart", "stop", "remove"):
        monkeypatch.setattr(
            DockerRuntime,
            method,
            forbidden(f"DockerRuntime.{method}"),
        )
    for method in ("create_project", "seed_project", "reset_project"):
        monkeypatch.setattr(
            JavaMcpClient,
            method,
            forbidden(f"JavaMcpClient.{method}"),
        )


def test_default_start_attaches_without_runtime_or_data_mutation(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    controller, runtime, config, current = default_runtime_for(tmp_path)
    assert config.data_volume == DEFAULT_DATA_VOLUME
    _wait_for_default_seeded_runtime(current)
    before = _runtime_identity_snapshot(runtime, current)
    _guard_against_runtime_mutation(monkeypatch)
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("LOCALCLOUD_HOME", str(controller.paths.home))

    started = _invoke(capsys, "start")
    status = _invoke(capsys, "status")
    environment = _invoke(capsys, "env", "--format", "json", verbose=False)

    resolved = runtime.resolve(
        config,
        preferred_container_id=current.container_id,
        require=True,
    )
    assert resolved is not None
    after = _runtime_identity_snapshot(runtime, resolved)

    assert started["status"] == "already_running"
    assert started["data_volume"] == DEFAULT_DATA_VOLUME
    assert started["container"]["id"] == current.container_id
    assert_loopback_url(started["container"]["url"])
    assert started["mcp"]["args"] == [
        "mcp",
        "--data-volume",
        DEFAULT_DATA_VOLUME,
        "--project-id",
        DEFAULT_PROJECT,
        "--user",
        DEFAULT_USER,
    ]
    assert started["mcp"]["headers"] == {
        "X-LocalCloud-Project": DEFAULT_PROJECT,
        "X-LocalCloud-User": DEFAULT_USER,
    }
    assert status["status"] == "running"
    assert status["container"]["id"] == current.container_id
    assert environment["GOOGLE_CLOUD_PROJECT"] == DEFAULT_PROJECT
    assert before == after


def test_default_seeded_runtime_passes_read_only_operational_checks(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _controller, runtime, config, current = default_runtime_for(tmp_path)
    assert config.data_volume == DEFAULT_DATA_VOLUME
    before = _runtime_identity_snapshot(runtime, current)
    _guard_against_runtime_mutation(monkeypatch)

    verification = _wait_for_default_seeded_runtime(current)
    java = JavaMcpClient(
        current.url,
        DEFAULT_PROJECT,
        DEFAULT_USER,
        timeout=_READINESS_REQUEST_TIMEOUT,
    )
    projects = java.list_projects()
    services = java.tool("localcloud_list_services")

    resolved = runtime.resolve(
        config,
        preferred_container_id=current.container_id,
        require=True,
    )
    assert resolved is not None
    after = _runtime_identity_snapshot(runtime, resolved)

    assert any(project.get("project_id") == DEFAULT_PROJECT for project in projects)
    assert services
    assert verification["project"] == _SEEDED_PROJECT
    assert _SEEDED_GCS_BUCKETS <= set(verification["gcs_buckets"])
    assert _SEEDED_BIGQUERY_DATASETS <= set(
        verification["bigquery_datasets"]
    )
    assert before == after
