from __future__ import annotations

from contextlib import asynccontextmanager
import io
from typing import Any

import anyio
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


class _Buffer(io.StringIO):
    def __init__(self, *, interactive: bool):
        super().__init__()
        self.interactive = interactive

    def isatty(self) -> bool:
        return self.interactive


class _EmptyStream:
    async def __aenter__(self) -> "_EmptyStream":
        return self

    async def __aexit__(self, *_args: Any) -> None:
        return None

    def __aiter__(self) -> "_EmptyStream":
        return self

    async def __anext__(self) -> Any:
        raise StopAsyncIteration


class RunningController:
    def __init__(self):
        self.targets: list[LocalCloudConfig] = []
        self.readiness_timeouts: list[float | None] = []

    def target(
        self,
        config: LocalCloudConfig,
        *,
        readiness_timeout: float | None = None,
        on_url_resolved: Any = None,
    ) -> dict[str, Any]:
        self.targets.append(config)
        self.readiness_timeouts.append(readiness_timeout)
        if on_url_resolved is not None:
            on_url_resolved("http://127.0.0.1:49080")
        return {
            "url": "http://127.0.0.1:49080",
            "endpoint_map": {"5365": 49080, "5366": 49081},
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


def test_run_raises_on_first_sigint_and_restores_handler(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    previous_handler = object()
    installed: dict[str, Any] = {}
    calls: list[tuple[int, Any]] = []

    def set_handler(signum: int, handler: Any) -> Any:
        calls.append((signum, handler))
        if len(calls) == 1:
            installed["handler"] = handler
            return previous_handler
        return installed["handler"]

    def run_async(
        _function: Any,
        _config: LocalCloudConfig,
        _connect_timeout: float,
        _on_connecting: Any,
    ) -> None:
        installed["handler"](mcp_module.signal.SIGINT, None)

    monkeypatch.setattr(mcp_module.signal, "signal", set_handler)
    monkeypatch.setattr(anyio, "run", run_async)

    with pytest.raises(KeyboardInterrupt):
        mcp_module.run(CONFIG)

    assert calls[0] == (
        mcp_module.signal.SIGINT,
        mcp_module._raise_keyboard_interrupt,
    )
    assert calls[1] == (mcp_module.signal.SIGINT, previous_handler)


@pytest.mark.parametrize("interactive", [True, False])
def test_stdio_lifecycle_feedback_uses_interactive_stderr_only(
    interactive: bool,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class Adapter:
        mcp_url = "http://127.0.0.1:49080/mcp"

    @asynccontextmanager
    async def fake_stdio_server():
        yield _EmptyStream(), _EmptyStream()

    import mcp.server.stdio as stdio_module

    monkeypatch.setattr(
        mcp_module,
        "McpAdapter",
        lambda _config, **_kwargs: Adapter(),
    )
    monkeypatch.setattr(stdio_module, "stdio_server", fake_stdio_server)
    stderr = _Buffer(interactive=interactive)
    stdout = io.StringIO()
    monkeypatch.setattr(mcp_module.sys, "stderr", stderr)
    monkeypatch.setattr(mcp_module.sys, "stdout", stdout)

    anyio.run(mcp_module._run_sdk, CONFIG)

    expected = (
        "Connected to LocalCloud at http://127.0.0.1:49080/mcp\n"
        "Accepting MCP requests over stdio. Press Ctrl-C to close.\n"
        if interactive
        else ""
    )
    assert stderr.getvalue() == expected
    assert stdout.getvalue() == ""


@pytest.mark.parametrize(
    ("interactive", "expected"),
    [
        (
            True,
            "Connecting to LocalCloud MCP at "
            "http://127.0.0.1:49080/mcp (timeout: 10s)…\n",
        ),
        (False, ""),
    ],
)
def test_run_reports_resolved_endpoint_during_sdk_start(
    interactive: bool,
    expected: str,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    stderr = _Buffer(interactive=interactive)
    stdout = io.StringIO()
    observed_before_url_resolution: list[str] = []

    def run_async(
        _function: Any,
        _config: LocalCloudConfig,
        connect_timeout: float,
        on_connecting: Any,
    ) -> None:
        observed_before_url_resolution.append(stderr.getvalue())
        assert connect_timeout == 10.0
        on_connecting("http://127.0.0.1:49080/mcp")

    monkeypatch.setattr(anyio, "run", run_async)
    monkeypatch.setattr(mcp_module.sys, "stderr", stderr)
    monkeypatch.setattr(mcp_module.sys, "stdout", stdout)

    mcp_module.run(CONFIG)

    assert observed_before_url_resolution == [""]
    assert stderr.getvalue() == expected
    assert stdout.getvalue() == ""


def test_stopped_runtime_uses_exact_recovery_command() -> None:
    class StoppedController:
        def target(
            self,
            _config: LocalCloudConfig,
            **_kwargs: Any,
        ) -> dict[str, Any]:
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
        def target(
            self,
            config: LocalCloudConfig,
            **_kwargs: Any,
        ) -> dict[str, Any]:
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


def test_host_error_details_are_not_forwarded_to_mcp_client() -> None:
    class FailingJava(FakeJava):
        def forward(self, message: dict[str, Any]) -> dict[str, Any] | None:
            raise HostError(
                "java_mcp_unavailable",
                "Java LocalCloud MCP request failed",
                {
                    "url": "http://127.0.0.1:49080/mcp",
                    "cause": "connection reset by internal-host-1.local",
                },
            )

    adapter = McpAdapter(CONFIG, controller=RunningController())
    adapter.java = FailingJava("http://127.0.0.1:49080", PROJECT, USER)

    response = adapter.handle(
        {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {}}
    )

    assert response is not None
    assert response["error"]["data"] == {"code": "java_mcp_unavailable"}
    assert "internal-host-1.local" not in str(response)


def test_malformed_target_raises_clean_host_error_not_key_error() -> None:
    class MalformedController:
        def target(
            self,
            _config: LocalCloudConfig,
            **_kwargs: Any,
        ) -> dict[str, Any]:
            return {"url": "http://127.0.0.1:49080"}  # missing endpoint_map

    with pytest.raises(HostError) as caught:
        McpAdapter(CONFIG, controller=MalformedController())

    assert caught.value.code == "runtime_target_invalid"


@pytest.mark.parametrize(
    ("connect_timeout", "duration"),
    [
        (1.0, "1 second"),
        (2.5, "2.5 seconds"),
    ],
)
def test_connection_timeout_names_endpoint_and_deadline(
    connect_timeout: float,
    duration: str,
) -> None:
    connecting: list[str] = []

    class TimedOutController:
        def target(
            self,
            _config: LocalCloudConfig,
            *,
            readiness_timeout: float,
            on_url_resolved: Any,
        ) -> dict[str, Any]:
            assert readiness_timeout == connect_timeout
            on_url_resolved("http://127.0.0.1:49080")
            raise HostError(
                "runtime_readiness_timeout",
                "not ready",
                {"url": "http://127.0.0.1:49080"},
            )

    with pytest.raises(HostError) as caught:
        McpAdapter(
            CONFIG,
            controller=TimedOutController(),
            connect_timeout=connect_timeout,
            on_connecting=connecting.append,
        )

    assert connecting == ["http://127.0.0.1:49080/mcp"]
    assert caught.value.code == "mcp_connection_timeout"
    assert caught.value.message == (
        "Could not connect to LocalCloud MCP at "
        f"http://127.0.0.1:49080/mcp within {duration}."
    )
    assert caught.value.details["timeout_seconds"] == connect_timeout


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
                            '{"endpoint":"http://127.0.0.1:5366",'
                            '"port":5366,"env_var":"STORAGE_EMULATOR_HOST"}'
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
                            '{"endpoint":"http://localhost:5366",'
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
    assert adapter.mcp_url == "http://127.0.0.1:49080/mcp"

    assert selected == [
        ("http://127.0.0.1:49080", PROJECT, USER)
    ]
    assert controller.targets == [CONFIG]
    assert controller.readiness_timeouts == [10.0]
