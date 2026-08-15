from __future__ import annotations

from typing import Any

import pytest

import localcloud_cli.mcp_stdio as mcp_module
from localcloud_cli.config import LocalCloudConfig
from localcloud_cli.errors import HostError
from localcloud_cli.mcp_stdio import McpAdapter

DATA_VOLUME = "team-data"
PROJECT = "agent-project-1"
USER = "integration-agent"
CONFIG = LocalCloudConfig(
    data_volume=DATA_VOLUME,
    config_path=None,
    project=PROJECT,
    user=USER,
    services=None,
    seed_path=None,
    seed_yaml=None,
    data="persistent",
    image="jaysen2apache/localcloud:latest",
    memory="4g",
    docker_socket=False,
    transparent_network=False,
    environment={},
    container_name="localcloud",
    network_name="localcloud",
    diagnostics=(),
)


class RunningController:
    def __init__(self):
        self.targets: list[LocalCloudConfig] = []

    def target(self, config: LocalCloudConfig) -> dict[str, Any]:
        self.targets.append(config)
        return {
            "url": "http://127.0.0.1:49080",
            "endpoint_map": {"24080": 49080, "24081": 49081},
        }


class FakeJava:
    calls: list[dict[str, Any]] = []
    responses: dict[str, dict[str, Any] | None] = {}

    def __init__(self, url: str, project: str, user: str):
        assert url == "http://127.0.0.1:49080"
        assert project == PROJECT
        assert user == USER

    def forward(self, message: dict[str, Any]) -> dict[str, Any] | None:
        self.calls.append(message)
        response = self.responses.get(message["method"])
        if response is None:
            return None
        return {**response, "id": message.get("id")}


@pytest.fixture(autouse=True)
def _patch_java(monkeypatch: pytest.MonkeyPatch) -> None:
    FakeJava.calls = []
    FakeJava.responses = {}
    monkeypatch.setattr(mcp_module, "JavaMcpClient", FakeJava)


def test_stopped_runtime_uses_exact_recovery_command() -> None:
    class StoppedController:
        def target(self, _config: LocalCloudConfig) -> dict[str, Any]:
            raise HostError("runtime_not_running", "not running")

    with pytest.raises(HostError) as caught:
        McpAdapter(CONFIG, controller=StoppedController())

    assert caught.value.code == "runtime_not_running"
    assert caught.value.message == (
        "Run localcloud start --data-volume team-data "
        "--project-id agent-project-1 --user integration-agent "
        "before connecting MCP."
    )


def test_unknown_project_error_is_not_rewritten_as_runtime_failure() -> None:
    class UnknownProjectController:
        def target(self, config: LocalCloudConfig) -> dict[str, Any]:
            raise HostError(
                "unknown_project",
                f"Project {config.project!r} does not exist in {config.data_volume!r}",
            )

    with pytest.raises(HostError) as caught:
        McpAdapter(CONFIG, controller=UnknownProjectController())

    assert caught.value.code == "unknown_project"
    assert caught.value.message == (
        "Project 'agent-project-1' does not exist in 'team-data'"
    )


def test_running_bridge_lists_only_java_tools_and_preserves_initialize() -> None:
    FakeJava.responses = {
        "initialize": {
            "jsonrpc": "2.0",
            "result": {
                "protocolVersion": "2025-11-25",
                "serverInfo": {"name": "localcloud", "version": "test"},
                "capabilities": {"tools": {}, "tasks": {"list": {}}},
            },
        },
        "tools/list": {
            "jsonrpc": "2.0",
            "result": {
                "tools": [
                    {"name": "localcloud_list_services"},
                    {"name": "localcloud_apply_scenario"},
                ]
            },
        },
    }
    controller = RunningController()
    adapter = McpAdapter(CONFIG, controller=controller)

    initialized = adapter.handle(
        {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {"protocolVersion": "2025-11-25"},
        }
    )
    tools = adapter.handle(
        {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}}
    )

    assert initialized is not None
    assert tools is not None
    assert initialized["result"]["serverInfo"]["name"] == "localcloud"
    assert [tool["name"] for tool in tools["result"]["tools"]] == [
        "localcloud_list_services",
        "localcloud_apply_scenario",
    ]
    assert controller.targets == [CONFIG]


def test_task_completion_logging_and_notifications_are_forwarded_opaquely() -> None:
    methods = (
        "tasks/list",
        "tasks/get",
        "tasks/result",
        "tasks/cancel",
        "completion/complete",
        "logging/setLevel",
    )
    FakeJava.responses = {
        method: {"jsonrpc": "2.0", "result": {"method": method}}
        for method in methods
    }
    adapter = McpAdapter(CONFIG, controller=RunningController())

    for index, method in enumerate(methods, start=1):
        response = adapter.handle(
            {
                "jsonrpc": "2.0",
                "id": index,
                "method": method,
                "params": {"taskId": "task-1"},
            }
        )
        assert response is not None
        assert response["result"]["method"] == method

    assert (
        adapter.handle(
            {
                "jsonrpc": "2.0",
                "method": "notifications/initialized",
                "params": {},
            }
        )
        is None
    )
    assert FakeJava.calls[-1]["method"] == "notifications/initialized"


def test_tool_and_resource_content_rewrites_dynamic_endpoints() -> None:
    FakeJava.responses = {
        "tools/call": {
            "jsonrpc": "2.0",
            "result": {
                "content": [
                    {
                        "type": "text",
                        "text": (
                            '{"endpoint":"http://127.0.0.1:24081",'
                            '"port":24081,"env_var":"STORAGE_EMULATOR_HOST"}'
                        ),
                    }
                ]
            },
        },
        "resources/read": {
            "jsonrpc": "2.0",
            "result": {
                "contents": [
                    {
                        "uri": "localcloud://services/gcs",
                        "mimeType": "application/json",
                        "text": (
                            '{"endpoint":"http://localhost:24081",'
                            '"env_var":"STORAGE_EMULATOR_HOST"}'
                        ),
                    }
                ]
            },
        },
    }
    adapter = McpAdapter(CONFIG, controller=RunningController())

    tool = adapter.handle(
        {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {}}
    )
    resource = adapter.handle(
        {"jsonrpc": "2.0", "id": 2, "method": "resources/read", "params": {}}
    )

    assert tool is not None
    assert resource is not None
    assert "127.0.0.1:49081" in tool["result"]["content"][0]["text"]
    assert '"port":49081' in tool["result"]["content"][0]["text"]
    assert "127.0.0.1:49081" in resource["result"]["contents"][0]["text"]


def test_public_google_endpoint_in_generated_tool_content_is_rejected() -> None:
    FakeJava.responses = {
        "tools/call": {
            "jsonrpc": "2.0",
            "result": {
                "content": [
                    {
                        "type": "text",
                        "text": (
                            '{"endpoint":"https://storage.googleapis.com",'
                            '"env_var":"STORAGE_EMULATOR_HOST"}'
                        ),
                    }
                ]
            },
        }
    }
    adapter = McpAdapter(CONFIG, controller=RunningController())

    response = adapter.handle(
        {"jsonrpc": "2.0", "id": 8, "method": "tools/call", "params": {}}
    )

    assert response is not None
    assert response["error"]["data"]["code"] == "real_google_endpoint"


def test_config_context_is_forwarded_to_java(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    selected: list[tuple[str, str, str]] = []

    class SelectedJava:
        def __init__(self, url: str, project: str, user: str):
            selected.append((url, project, user))

    monkeypatch.setattr(mcp_module, "JavaMcpClient", SelectedJava)
    controller = RunningController()

    adapter = McpAdapter(CONFIG, controller=controller)

    assert selected == [
        ("http://127.0.0.1:49080", PROJECT, USER)
    ]
    assert controller.targets == [CONFIG]
