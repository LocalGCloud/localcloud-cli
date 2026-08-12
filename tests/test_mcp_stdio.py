from __future__ import annotations

from typing import Any

import pytest

import localcloud_cli.mcp_stdio as mcp_module
from localcloud_cli.errors import HostError
from localcloud_cli.mcp_stdio import McpAdapter

INSTANCE = "default"
PROJECT = "agent-project-1"
USER = "integration-agent"



class RunningController:
    def __init__(self):
        self.targets: list[tuple[str, str, str]] = []
        self.releases: list[tuple[str, str, str]] = []

    def mcp_target(
        self, instance: str, project: str, user: str
    ) -> dict[str, Any]:
        self.targets.append((instance, project, user))
        return {
            "url": "http://127.0.0.1:49080",
            "project": project,
            "user": user,
            "endpoint_map": {"24080": 49080, "24081": 49081},
        }

    def release_mcp_target(
        self, instance: str, project: str, user: str
    ) -> None:
        self.releases.append((instance, project, user))


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


def test_stopped_instance_uses_exact_recovery_command() -> None:
    class StoppedController:
        def mcp_target(
            self, _instance: str, _project: str, _user: str
        ) -> dict[str, Any]:
            raise HostError("instance_not_running", "not running")

    with pytest.raises(HostError) as caught:
        McpAdapter(INSTANCE, PROJECT, USER, controller=StoppedController())

    assert caught.value.code == "instance_not_running"
    assert caught.value.message == (
        "Run localcloud start --instance default --project-id agent-project-1 "
        "--user integration-agent before connecting MCP."
    )


def test_unknown_project_error_is_not_rewritten_as_instance_failure() -> None:
    class UnknownProjectController:
        def mcp_target(
            self, instance: str, project: str, _user: str
        ) -> dict[str, Any]:
            raise HostError(
                "unknown_project",
                f"Project {project!r} does not exist in {instance!r}",
            )

    with pytest.raises(HostError) as caught:
        McpAdapter(
            INSTANCE,
            PROJECT,
            USER,
            controller=UnknownProjectController(),
        )

    assert caught.value.code == "unknown_project"
    assert caught.value.message == (
        "Project 'agent-project-1' does not exist in 'default'"
    )


def test_running_bridge_lists_only_java_tools_and_preserves_initialize(
) -> None:
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
    adapter = McpAdapter(INSTANCE, PROJECT, USER, controller=controller)

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

    assert initialized["result"]["serverInfo"]["name"] == "localcloud"
    assert [tool["name"] for tool in tools["result"]["tools"]] == [
        "localcloud_list_services",
        "localcloud_apply_scenario",
    ]
    assert all("environment" not in tool["name"] for tool in tools["result"]["tools"])
    assert controller.targets == [(INSTANCE, PROJECT, USER)]


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
    adapter = McpAdapter(
        INSTANCE, PROJECT, USER, controller=RunningController()
    )

    for index, method in enumerate(methods, start=1):
        response = adapter.handle(
            {
                "jsonrpc": "2.0",
                "id": index,
                "method": method,
                "params": {"taskId": "task-1"},
            }
        )
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
    adapter = McpAdapter(
        INSTANCE, PROJECT, USER, controller=RunningController()
    )

    tool = adapter.handle(
        {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {}}
    )
    resource = adapter.handle(
        {"jsonrpc": "2.0", "id": 2, "method": "resources/read", "params": {}}
    )

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
    adapter = McpAdapter(
        INSTANCE, PROJECT, USER, controller=RunningController()
    )

    response = adapter.handle(
        {"jsonrpc": "2.0", "id": 8, "method": "tools/call", "params": {}}
    )

    assert response["error"]["data"]["code"] == "real_google_endpoint"


def test_close_releases_only_selected_bridge_context() -> None:
    controller = RunningController()
    adapter = McpAdapter(INSTANCE, PROJECT, USER, controller=controller)

    assert adapter.close() is None
    assert controller.targets == [(INSTANCE, PROJECT, USER)]
    assert controller.releases == [(INSTANCE, PROJECT, USER)]


def test_defaults_select_shared_instance_project_and_caller(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    selected: list[tuple[str, str, str]] = []

    class DefaultJava:
        def __init__(self, url: str, project: str, user: str):
            selected.append((url, project, user))

    monkeypatch.setattr(mcp_module, "JavaMcpClient", DefaultJava)
    controller = RunningController()

    adapter = McpAdapter(controller=controller)

    assert selected == [
        (
            "http://127.0.0.1:49080",
            "local-gcp-project",
            "local-developer",
        )
    ]
    assert controller.targets == [
        ("default", "local-gcp-project", "local-developer")
    ]
    adapter.close()
    assert controller.releases == [
        ("default", "local-gcp-project", "local-developer")
    ]
