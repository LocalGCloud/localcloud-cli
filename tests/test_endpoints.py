from __future__ import annotations

import json

import pytest

import localcloud_cli.endpoints as endpoints_module
from localcloud_cli.endpoints import (
    environment_config,
    rewrite_endpoints,
    transform_endpoint_payload,
    validate_local_endpoints,
)
from localcloud_cli.errors import HostError


def test_rewrite_endpoints_is_recursive_and_preserves_unmapped_ports() -> None:
    value = {
        "url": "http://localhost:5380/path",
        "nested": ["127.0.0.1:5382", "127.0.0.1:24099"],
    }

    rewritten = rewrite_endpoints(value, {"5380": 49080, "5382": 49081})

    assert rewritten == {
        "url": "http://127.0.0.1:49080/path",
        "nested": ["127.0.0.1:49081", "127.0.0.1:24099"],
    }


def test_transform_endpoint_payload_rewrites_generated_endpoint_records() -> None:
    value = {
        "content": [
            {
                "type": "text",
                "text": (
                    '{"endpoint":"http://127.0.0.1:5382",'
                    '"port":5382,"env_var":"STORAGE_EMULATOR_HOST"}'
                ),
            }
        ]
    }

    transformed = transform_endpoint_payload(value, {"5382": 49081})

    text = transformed["content"][0]["text"]
    assert "127.0.0.1:49081" in text
    assert '"port":49081' in text


def test_transform_endpoint_payload_rejects_public_google_endpoint() -> None:
    value = {
        "endpoint": "https://storage.googleapis.com",
        "env_var": "STORAGE_EMULATOR_HOST",
    }

    with pytest.raises(HostError) as caught:
        transform_endpoint_payload(value, {"5382": 49081})

    assert caught.value.code == "real_google_endpoint"


def test_validate_local_endpoints_rejects_public_google_and_non_loopback() -> None:
    for value in (
        "https://storage.googleapis.com/bucket",
        {"url": "http://192.0.2.10:8080"},
    ):
        with pytest.raises(HostError):
            validate_local_endpoints(value)


def test_validate_local_endpoints_allows_descriptive_urls() -> None:
    # Descriptive non-endpoint fields should not trigger nonlocal_endpoint
    value = {
        "description": "See docs at https://example.com/guide",
        "url": "http://127.0.0.1:5382",
    }
    validate_local_endpoints(value)



def test_environment_config_uses_running_environment_without_daemon_state(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[str] = []

    class FakeJava:
        def __init__(self, url: str, project: str, user: str):
            assert url == "http://127.0.0.1:49080"
            assert project == "agent-project-1"
            assert user == "integration-agent"

        def environment(self, output_format: str):
            calls.append(output_format)
            return {
                "LOCALCLOUD_PROJECT": "agent-project-1",
                "LOCALCLOUD_USER": "integration-agent",
                "LOCALCLOUD_PRINCIPAL": "integration-agent@localcloud.invalid",
                "STORAGE_EMULATOR_HOST": "http://127.0.0.1:5382",
            }

    monkeypatch.setattr(endpoints_module, "JavaMcpClient", FakeJava)
    environment = {
        "url": "http://127.0.0.1:49080",
        "endpoint_map": {"5382": 49081},
    }

    result = environment_config(
        environment,
        "agent-project-1",
        "integration-agent",
        "json",
    )

    assert result["STORAGE_EMULATOR_HOST"] == "http://127.0.0.1:49081"
    assert result["LOCALCLOUD_USER"] == "integration-agent"
    assert result["LOCALCLOUD_PRINCIPAL"] == "integration-agent@localcloud.invalid"
    assert calls == ["json"]


def test_off_canonical_runtime_rejects_endpoints_on_ports_it_does_not_publish() -> None:
    # A runtime off the canonical ports publishes only its services' ports.
    endpoint_map = {"5380": 5508, "5382": 5509}

    endpoints_module._validate_no_unpublished_canonical_endpoints(
        {"STORAGE_EMULATOR_HOST": "http://127.0.0.1:5509"}, endpoint_map
    )
    with pytest.raises(HostError) as caught:
        endpoints_module._validate_no_unpublished_canonical_endpoints(
            {"FIRESTORE_EMULATOR_HOST": "127.0.0.1:5384"}, endpoint_map
        )
    assert caught.value.details["canonical_port"] == "5384"

    # On the canonical ports nothing is remapped, so nothing is unpublished.
    endpoints_module._validate_no_unpublished_canonical_endpoints(
        {"FIRESTORE_EMULATOR_HOST": "127.0.0.1:5384"}, {"5380": 5380}
    )
