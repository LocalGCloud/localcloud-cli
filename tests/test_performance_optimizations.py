"""Tests verifying performance optimizations and preventing YAML/JSON catalog drift."""

from __future__ import annotations

import json
from importlib.resources import files
from typing import Any
from unittest.mock import MagicMock

import yaml

from localcloud_cli.config import _packaged_defaults
from localcloud_cli.docker_runtime import DockerRuntime, _resource_labels
from localcloud_cli.java_client import get_shared_http_client


def test_catalog_json_matches_yaml() -> None:
    """Ensure precompiled localcloud.v1.json strictly matches localcloud.v1.yaml."""
    defaults_pkg = files("localcloud_cli.defaults")
    yaml_text = defaults_pkg.joinpath("localcloud.v1.yaml").read_text(encoding="utf-8")
    json_text = defaults_pkg.joinpath("localcloud.v1.json").read_text(encoding="utf-8")

    yaml_data = yaml.safe_load(yaml_text)
    json_data = json.loads(json_text)

    assert json_data == yaml_data, (
        "localcloud.v1.json is out of sync with localcloud.v1.yaml! "
        "Update localcloud.v1.json to match localcloud.v1.yaml."
    )


def test_packaged_defaults_loads_from_json() -> None:
    """Verify _packaged_defaults() successfully loads the cached catalog."""
    defaults = _packaged_defaults()
    assert isinstance(defaults, dict)
    assert "services" in defaults
    assert "catalog" in defaults["services"]
    assert "pubsub" in defaults["services"]["catalog"]
    assert "bigquery" in defaults["services"]["catalog"]


def test_resource_labels_reload_false_by_default() -> None:
    """Verify _resource_labels does not call reload() unless reload=True is specified."""
    mock_resource = MagicMock()
    mock_resource.labels = {"com.localcloud.managed": "true"}

    labels = _resource_labels(mock_resource)
    assert labels == {"com.localcloud.managed": "true"}
    mock_resource.reload.assert_not_called()

    _resource_labels(mock_resource, reload=True)
    mock_resource.reload.assert_called_once()


def test_logs_bypasses_redundant_resolve() -> None:
    """Verify logs() fetches the container directly without full resolve() overhead."""
    mock_client = MagicMock()
    mock_container = MagicMock()
    mock_container.logs.return_value = b"sample container logs\n"
    mock_client.containers.get.return_value = mock_container

    runtime = DockerRuntime(mock_client)
    mock_config = MagicMock()
    mock_record = MagicMock(container_id="test-container-id")
    logs_result = runtime.logs(
        mock_config,
        mock_record,
        tail=50,
    )

    assert logs_result == "sample container logs\n"
    mock_client.containers.get.assert_called_once_with("test-container-id")
    # Must NOT perform network or volume lookups for a simple log read
    mock_client.networks.list.assert_not_called()
    mock_client.volumes.list.assert_not_called()


def test_get_shared_http_client_reuses_instance() -> None:
    """Verify HTTP client instance is reused across invocations."""
    client1 = get_shared_http_client()
    client2 = get_shared_http_client()
    assert client1 is client2
    assert not client1.is_closed


def test_status_reuses_resolved_image_details() -> None:
    """Verify image_details() reuses image inspection cached during resolve()."""
    mock_client = MagicMock()
    runtime = DockerRuntime(mock_client)
    cached_info = {
        "id": "sha256:12345",
        "location": "Local",
        "size_bytes": 1000,
        "created": "2026-01-01T00:00:00Z",
    }
    runtime._resolved_image_details["test-image:v1"] = cached_info

    details = runtime.image_details("test-image:v1")
    assert details == cached_info
    mock_client.images.get.assert_not_called()


def test_doctor_does_not_reload_resources() -> None:
    """Verify doctor() inspects resources using existing attributes without reloads."""
    mock_client = MagicMock()
    mock_container = MagicMock()
    mock_container.name = "localcloud-test"
    mock_container.labels = {
        "com.localcloud.managed": "true",
        "com.localcloud.runtime-ownership": "data-volume-v1",
        "com.localcloud.volume-name": "localcloud-data",
        "com.localcloud.resource-role": "container",
    }
    mock_container.attrs = {"Mounts": []}

    mock_client.containers.list.return_value = [mock_container]
    mock_client.networks.list.return_value = []
    mock_client.volumes.list.return_value = []

    runtime = DockerRuntime(mock_client)
    report = runtime.doctor()

    assert isinstance(report, dict)
    mock_container.reload.assert_not_called()
