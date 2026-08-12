from __future__ import annotations

import json

import pytest

import localcloud_cli.endpoints as endpoints_module
from localcloud_cli.endpoints import (
    environment_config,
    rewrite_endpoints,
    validate_local_endpoints,
)
from localcloud_cli.errors import HostError


def test_rewrite_endpoints_is_recursive_and_preserves_unmapped_ports() -> None:
    value = {
        "url": "http://localhost:24080/path",
        "nested": ["127.0.0.1:24081", "127.0.0.1:24099"],
    }

    rewritten = rewrite_endpoints(value, {"24080": 49080, "24081": 49081})

    assert rewritten == {
        "url": "http://127.0.0.1:49080/path",
        "nested": ["127.0.0.1:49081", "127.0.0.1:24099"],
    }


def test_validate_local_endpoints_rejects_public_google_and_non_loopback() -> None:
    for value in (
        "https://storage.googleapis.com/bucket",
        {"url": "http://192.0.2.10:8080"},
    ):
        with pytest.raises(HostError):
            validate_local_endpoints(value)


def test_environment_config_uses_running_environment_without_daemon_state(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[tuple[str, dict[str, object]]] = []

    class FakeJava:
        def __init__(self, url: str, project: str, user: str):
            assert url == "http://127.0.0.1:49080"
            assert project == "agent-project-1"
            assert user == "integration-agent"

        def tool(self, name: str, arguments: dict[str, object]):
            calls.append((name, arguments))
            return json.dumps(
                {
                    "LOCALCLOUD_PROJECT": "agent-project-1",
                    "LOCALCLOUD_USER": "integration-agent",
                    "LOCALCLOUD_PRINCIPAL": "integration-agent@localcloud.invalid",
                    "STORAGE_EMULATOR_HOST": "http://127.0.0.1:24081",
                }
            )

    monkeypatch.setattr(endpoints_module, "JavaMcpClient", FakeJava)
    environment = {
        "url": "http://127.0.0.1:49080",
        "endpoint_map": {"24081": 49081},
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
    assert calls == [
        (
            "localcloud_get_env",
            {"format": "json", "project": "agent-project-1"},
        )
    ]
