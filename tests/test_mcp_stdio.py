from __future__ import annotations

from contextlib import asynccontextmanager
import copy
from dataclasses import replace
import io
from pathlib import Path
import subprocess
import threading
import time
from typing import Any

import anyio
import pytest

import localcloud_cli.mcp_stdio as mcp_module
from localcloud_cli.config import LocalCloudConfig
from localcloud_cli.errors import HostError
from localcloud_cli.mcp_stdio import STATUS_TOOL, McpAdapter

DATA_VOLUME = "team-data"
PROJECT = "agent-project-1"
USER = "integration-agent"
CONFIG = LocalCloudConfig(
    data_volume=DATA_VOLUME,
    config_path=None,
    project=PROJECT,
    user=USER,
    services=None,
    data="persistent",
    image="agentcloud/localcloud:latest",
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
    def __init__(self, url: str = "http://127.0.0.1:49080"):
        self.url = url
        self.targets: list[LocalCloudConfig] = []
        self.readiness_timeouts: list[float | None] = []
        self.ensured: list[str] = []
        self.starts = 0

    def start(self, config: LocalCloudConfig, **_kwargs: Any) -> dict[str, Any]:
        self.starts += 1
        return {"status": "started"}

    def target(
        self,
        config: LocalCloudConfig,
        *,
        readiness_timeout: float | None = None,
        on_url_resolved: Any = None,
        ensure_project: bool = False,
    ) -> dict[str, Any]:
        self.targets.append(config)
        self.readiness_timeouts.append(readiness_timeout)
        if ensure_project:
            self.ensured.append(config.project)
        if on_url_resolved is not None:
            on_url_resolved(self.url)
        return {"url": self.url, "endpoint_map": {"5380": 49080, "5382": 49081}}


class FailingController:
    """Docker or the runtime is unavailable until `available` is set."""

    def __init__(self, error: HostError):
        self.error = error
        self.available = False
        self.inner = RunningController()

    def start(self, config: LocalCloudConfig, **kwargs: Any) -> dict[str, Any]:
        if not self.available:
            raise self.error
        return self.inner.start(config, **kwargs)

    def target(self, config: LocalCloudConfig, **kwargs: Any) -> dict[str, Any]:
        if not self.available:
            raise self.error
        return self.inner.target(config, **kwargs)


class FakeJava:
    calls: list[dict[str, Any]] = []
    clients: list[tuple[str, str]] = []
    responses: dict[str, dict[str, Any] | None] = {}

    def __init__(self, url: str, project: str, user: str):
        assert user == USER
        self.url = url
        self.project = project
        FakeJava.clients.append((url, project))

    def forward(self, message: dict[str, Any], **_kwargs: Any) -> dict[str, Any] | None:
        self.calls.append(message)
        response = self.responses.get(message["method"])
        if response is None:
            return None
        return {**response, "id": message.get("id")}


@pytest.fixture(autouse=True)
def _patch_java(monkeypatch: pytest.MonkeyPatch) -> None:
    FakeJava.calls = []
    FakeJava.clients = []
    FakeJava.responses = {}
    monkeypatch.setattr(mcp_module, "JavaMcpClient", FakeJava)
    monkeypatch.setattr(mcp_module, "_RETRY_INTERVAL", 0.0)


def _request(request_id: Any, method: str, **params: Any) -> dict[str, Any]:
    return {"jsonrpc": "2.0", "id": request_id, "method": method, "params": params}


def _degraded_adapter(error: HostError, **kwargs: Any) -> tuple[McpAdapter, FailingController]:
    controller = FailingController(error)
    adapter = McpAdapter(CONFIG, controller=controller, connect=False, **kwargs)
    adapter.start_connecting()
    adapter.ensure_connected(5)
    return adapter, controller


# Process lifecycle ---------------------------------------------------------


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

    def run_async(*_args: Any) -> None:
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
    @asynccontextmanager
    async def fake_stdio_server():
        yield _EmptyStream(), _EmptyStream()

    import mcp.server.stdio as stdio_module

    controller = RunningController()
    monkeypatch.setattr(mcp_module, "Controller", lambda: controller)
    monkeypatch.setattr(stdio_module, "stdio_server", fake_stdio_server)
    stderr = _Buffer(interactive=interactive)
    stdout = io.StringIO()
    monkeypatch.setattr(mcp_module.sys, "stderr", stderr)
    monkeypatch.setattr(mcp_module.sys, "stdout", stdout)

    anyio.run(mcp_module._run_sdk, CONFIG)
    deadline = time.monotonic() + 5
    while interactive and "Connected" not in stderr.getvalue() and time.monotonic() < deadline:
        time.sleep(0.01)

    lines = sorted(stderr.getvalue().splitlines())
    expected = (
        [
            "Accepting MCP requests over stdio. Press Ctrl-C to close.",
            "Connected to LocalCloud at http://127.0.0.1:49080/mcp",
        ]
        if interactive
        else []
    )
    assert lines == expected
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
        *_args: Any,
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


# Direct connection errors --------------------------------------------------


def test_stopped_runtime_uses_exact_recovery_command() -> None:
    class StoppedController:
        def target(self, _config: LocalCloudConfig, **_kwargs: Any) -> dict[str, Any]:
            raise HostError("runtime_not_running", "not running")

    with pytest.raises(HostError) as caught:
        McpAdapter(CONFIG, controller=StoppedController(), auto_start=False)

    assert caught.value.code == "runtime_not_running"
    assert caught.value.message == (
        "Run localcloud start --data-volume team-data "
        "--project-id agent-project-1 --user integration-agent "
        "before connecting MCP."
    )


def test_unknown_project_error_is_not_rewritten_as_runtime_failure() -> None:
    class UnknownProjectController:
        def target(self, config: LocalCloudConfig, **_kwargs: Any) -> dict[str, Any]:
            raise HostError(
                "unknown_project",
                f"Project {config.project!r} does not exist in {config.data_volume!r}",
            )

    with pytest.raises(HostError) as caught:
        McpAdapter(CONFIG, controller=UnknownProjectController(), auto_start=False)

    assert caught.value.code == "unknown_project"
    assert caught.value.message == (
        "Project 'agent-project-1' does not exist in 'team-data'"
    )


def test_malformed_target_raises_clean_host_error_not_key_error() -> None:
    class MalformedController(RunningController):
        def target(self, _config: LocalCloudConfig, **_kwargs: Any) -> dict[str, Any]:
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

    class TimedOutController(RunningController):
        def target(
            self,
            _config: LocalCloudConfig,
            *,
            readiness_timeout: float,
            on_url_resolved: Any,
            ensure_project: bool = False,
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


# Forwarding to a ready runtime ----------------------------------------------


def test_host_error_details_are_not_forwarded_to_mcp_client() -> None:
    class FailingJava(FakeJava):
        def forward(self, message: dict[str, Any], **_kwargs: Any) -> dict[str, Any] | None:
            raise HostError(
                "java_mcp_unavailable",
                "Java LocalCloud MCP request failed",
                {
                    "url": "http://127.0.0.1:49080/mcp",
                    "cause": "connection reset by internal-host-1.local",
                    "connect_failed": False,
                },
            )

    adapter = McpAdapter(CONFIG, controller=RunningController())
    adapter.java = FailingJava("http://127.0.0.1:49080", PROJECT, USER)

    response = adapter.handle(_request(1, "tools/call", name="localcloud_list_services"))

    assert response is not None
    assert response["error"]["data"] == {"code": "java_mcp_unavailable"}
    assert "internal-host-1.local" not in str(response)


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

    initialized = adapter.handle(_request(1, "initialize", protocolVersion="2025-11-25"))
    tools = adapter.handle(_request(2, "tools/list"))

    assert initialized is not None
    assert tools is not None
    assert initialized["result"]["serverInfo"] == {"name": "localcloud", "version": "test"}
    assert [tool["name"] for tool in tools["result"]["tools"]] == [
        "localcloud_list_services",
        "localcloud_apply_scenario",
    ]
    assert controller.targets == [CONFIG]


def test_tool_display_titles_preserve_runtime_schemas_and_safety_annotations() -> None:
    upstream = {
        "jsonrpc": "2.0",
        "result": {
            "tools": [
                {
                    "name": "localcloud_get_api_catalog",
                    "inputSchema": {"type": "object", "properties": {}},
                    "annotations": {"readOnlyHint": True, "destructiveHint": False},
                },
                {
                    "name": "localcloud_query_data",
                    "title": "Runtime query",
                    "annotations": {"title": "Query data", "readOnlyHint": False},
                },
            ],
            "nextCursor": "next-page",
        },
    }
    original = copy.deepcopy(upstream)
    FakeJava.responses = {"tools/list": upstream}
    adapter = McpAdapter(CONFIG, controller=RunningController())

    response = adapter.handle({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})

    assert response is not None
    tools = response["result"]["tools"]
    assert tools[0] == {
        **original["result"]["tools"][0],
        "title": "Get API catalog",
        "annotations": {
            **original["result"]["tools"][0]["annotations"],
            "title": "Get API catalog",
        },
    }
    assert tools[1] == original["result"]["tools"][1]
    assert response["result"]["nextCursor"] == "next-page"
    assert response["id"] == 2
    assert upstream == original


@pytest.mark.parametrize(
    "tool", [None, {"name": 123}, {"name": ""}, {"name": "tool", "annotations": []}]
)
def test_tool_titles_do_not_repair_malformed_runtime_records(tool: Any) -> None:
    FakeJava.responses = {"tools/list": {"jsonrpc": "2.0", "result": {"tools": [tool]}}}
    adapter = McpAdapter(CONFIG, controller=RunningController())

    response = adapter.handle({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})

    assert response == {"jsonrpc": "2.0", "id": 2, "result": {"tools": [tool]}}


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
        method: {"jsonrpc": "2.0", "result": {"method": method}} for method in methods
    }
    adapter = McpAdapter(CONFIG, controller=RunningController())

    for index, method in enumerate(methods, start=1):
        response = adapter.handle(_request(index, method, taskId="task-1"))
        assert response is not None
        assert response["result"]["method"] == method

    assert (
        adapter.handle(
            {"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}}
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
                            '{"endpoint":"http://127.0.0.1:5382",'
                            '"port":5382,"env_var":"STORAGE_EMULATOR_HOST"}'
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
                            '{"endpoint":"http://localhost:5382",'
                            '"env_var":"STORAGE_EMULATOR_HOST"}'
                        ),
                    }
                ]
            },
        },
    }
    adapter = McpAdapter(CONFIG, controller=RunningController())

    tool = adapter.handle(_request(1, "tools/call", name="localcloud_get_env"))
    resource = adapter.handle(_request(2, "resources/read", uri="localcloud://services/gcs"))

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

    response = adapter.handle(_request(8, "tools/call", name="localcloud_get_env"))

    assert response is not None
    assert response["error"]["data"]["code"] == "real_google_endpoint"


def test_config_context_is_forwarded_to_java() -> None:
    controller = RunningController()

    adapter = McpAdapter(CONFIG, controller=controller)

    assert adapter.mcp_url == "http://127.0.0.1:49080/mcp"
    assert FakeJava.clients == [("http://127.0.0.1:49080", PROJECT)]
    assert controller.targets == [CONFIG]
    assert controller.readiness_timeouts == [10.0]


def test_ping_is_answered_by_the_bridge() -> None:
    adapter = McpAdapter(CONFIG, controller=RunningController())

    assert adapter.handle(_request(4, "ping")) == {"jsonrpc": "2.0", "id": 4, "result": {}}
    assert FakeJava.calls == []


# Runtime unavailable -------------------------------------------------------


def test_handshake_is_answered_without_a_runtime() -> None:
    adapter, _ = _degraded_adapter(
        HostError("docker_unavailable", "Docker is not reachable")
    )

    for requested, negotiated in (
        ("2025-06-18", "2025-06-18"),
        ("2026-07-28", "2025-11-25"),
    ):
        response = adapter.handle(_request(1, "initialize", protocolVersion=requested))
        assert response is not None
        result = response["result"]
        assert result["protocolVersion"] == negotiated
        assert result["capabilities"]["tools"] == {"listChanged": True}
        assert result["serverInfo"]["name"] == "localcloud"
        assert STATUS_TOOL in result["instructions"]

    discover = adapter.handle(_request(2, "server/discover"))
    assert discover is not None and discover["error"]["code"] == -32601


def test_unavailable_runtime_lists_the_status_tool_and_explains_calls() -> None:
    adapter, _ = _degraded_adapter(
        HostError("docker_unavailable", "Docker is not reachable at unix:///x.sock")
    )

    tools = adapter.handle(_request(1, "tools/list"))
    prompts = adapter.handle(_request(2, "prompts/list"))
    call = adapter.handle(_request(3, "tools/call", name="localcloud_list_services"))
    read = adapter.handle(_request(4, "resources/read", uri="localcloud://services"))
    status = adapter.handle(_request(5, "tools/call", name=STATUS_TOOL))

    assert tools is not None and [t["name"] for t in tools["result"]["tools"]] == [STATUS_TOOL]
    assert prompts is not None and prompts["result"] == {"prompts": []}
    assert call is not None and call["result"]["isError"] is True
    text = call["result"]["content"][0]["text"]
    assert "Docker is not reachable" in text and "start Docker" in text
    assert read is not None and read["error"]["data"] == {"code": "docker_unavailable"}
    assert status is not None
    assert status["result"]["isError"] is True
    structured = status["result"]["structuredContent"]
    assert structured["state"] == "unavailable"
    assert structured["error"]["code"] == "docker_unavailable"
    assert structured["project"] == PROJECT
    assert FakeJava.calls == []


def test_no_start_bridge_tells_the_agent_to_ask_for_lc_start() -> None:
    adapter, _ = _degraded_adapter(
        HostError("runtime_not_running", "not running"), auto_start=False
    )

    status = adapter.handle(_request(1, "tools/call", name=STATUS_TOOL))

    assert status is not None
    assert "--no-start" in status["result"]["structuredContent"]["next_step"]
    assert "lc start" in status["result"]["structuredContent"]["next_step"]


def test_status_tool_retries_and_the_client_is_told_lists_changed() -> None:
    FakeJava.responses = {
        "tools/list": {"jsonrpc": "2.0", "result": {"tools": [{"name": "localcloud_get_env"}]}}
    }
    adapter, controller = _degraded_adapter(HostError("docker_unavailable", "down"))
    adapter.handle(_request(1, "initialize", protocolVersion="2025-06-18"))
    adapter.handle(_request(2, "tools/list"))
    adapter.handle({"jsonrpc": "2.0", "method": "notifications/initialized"})
    assert adapter.drain_outbox() == []

    controller.available = True
    status = adapter.handle(_request(3, "tools/call", name=STATUS_TOOL))
    tools = adapter.handle(_request(4, "tools/list"))

    assert status is not None and status["result"]["isError"] is False
    assert status["result"]["structuredContent"]["state"] == "ready"
    assert [m["method"] for m in adapter.drain_outbox()] == [
        "notifications/tools/list_changed",
        "notifications/resources/list_changed",
        "notifications/prompts/list_changed",
    ]
    assert tools is not None and tools["result"]["tools"] == [
        {"name": "localcloud_get_env", "title": "Get env", "annotations": {"title": "Get env"}}
    ]


def test_a_call_waits_for_a_runtime_that_is_starting(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(mcp_module, "_HANDSHAKE_WAIT", 0.05)
    FakeJava.responses = {"tools/call": {"jsonrpc": "2.0", "result": {"content": []}}}
    release = threading.Event()

    class SlowController(RunningController):
        def start(self, config: LocalCloudConfig, **kwargs: Any) -> dict[str, Any]:
            release.wait(5)
            return super().start(config, **kwargs)

    adapter = McpAdapter(CONFIG, controller=SlowController(), connect=False)
    adapter.start_connecting()

    initialized = adapter.handle(_request(1, "initialize", protocolVersion="2025-06-18"))
    assert initialized is not None
    assert initialized["result"]["serverInfo"]["version"] != "test"
    assert adapter.status()["state"] == "starting"
    threading.Timer(0.1, release.set).start()
    call = adapter.handle(_request(2, "tools/call", name="localcloud_get_env"))

    assert call == {"jsonrpc": "2.0", "id": 2, "result": {"content": []}}


def test_docker_is_resolved_again_after_it_becomes_available() -> None:
    attempts: list[int] = []
    controller = RunningController()

    def prepare() -> tuple[Any, LocalCloudConfig]:
        attempts.append(1)
        if len(attempts) == 1:
            raise HostError("docker_unavailable", "Docker is not reachable")
        return controller, CONFIG

    adapter = McpAdapter(None, prepare=prepare, connect=False)
    adapter.start_connecting()
    adapter._attempt_done.wait(5)
    assert adapter.status()["error"]["code"] == "docker_unavailable"

    status = adapter.handle(_request(1, "tools/call", name=STATUS_TOOL))

    assert status is not None and status["result"]["structuredContent"]["state"] == "ready"
    assert len(attempts) == 2
    assert controller.starts == 1


# Reconnection --------------------------------------------------------------


def _refused() -> HostError:
    return HostError(
        "java_mcp_unavailable",
        "Java LocalCloud MCP request failed",
        {"connect_failed": True, "retryable": True},
    )


def test_a_refused_connection_reconnects_and_resends_once(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    controller = RunningController()
    adapter = McpAdapter(CONFIG, controller=controller)
    sent: list[str] = []

    class RestartedJava(FakeJava):
        def forward(self, message: dict[str, Any], **_kwargs: Any) -> dict[str, Any] | None:
            sent.append(self.url)
            if len(sent) == 1:
                raise _refused()
            return {"jsonrpc": "2.0", "id": message["id"], "result": {"content": []}}

    adapter.java = RestartedJava("http://127.0.0.1:49080", PROJECT, USER)
    controller.url = "http://127.0.0.1:5508"
    monkeypatch.setattr(mcp_module, "JavaMcpClient", RestartedJava)

    response = adapter.handle(_request(1, "tools/call", name="localcloud_get_env"))

    assert response == {"jsonrpc": "2.0", "id": 1, "result": {"content": []}}
    assert sent == ["http://127.0.0.1:49080", "http://127.0.0.1:5508"]
    assert adapter.mcp_url == "http://127.0.0.1:5508/mcp"


def test_a_restart_on_the_same_address_is_retried(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    controller = RunningController()
    adapter = McpAdapter(CONFIG, controller=controller)
    calls: list[int] = []

    class RestartingJava(FakeJava):
        def forward(self, message: dict[str, Any], **_kwargs: Any) -> dict[str, Any] | None:
            calls.append(1)
            if len(calls) == 1:
                raise _refused()
            return {"jsonrpc": "2.0", "id": message["id"], "result": {}}

    monkeypatch.setattr(mcp_module, "JavaMcpClient", RestartingJava)
    adapter.java = RestartingJava(controller.url, PROJECT, USER)

    response = adapter.handle(_request(1, "resources/list"))

    assert response == {"jsonrpc": "2.0", "id": 1, "result": {}}
    assert len(calls) == 2
    assert len(controller.targets) == 2


def test_a_failed_response_is_not_resent() -> None:
    adapter = McpAdapter(CONFIG, controller=RunningController())
    calls: list[int] = []

    class ServerErrorJava(FakeJava):
        def forward(self, message: dict[str, Any], **_kwargs: Any) -> dict[str, Any] | None:
            calls.append(1)
            raise HostError(
                "java_mcp_unavailable",
                "Java LocalCloud MCP request failed",
                {"connect_failed": False, "status_code": 503},
            )

    adapter.java = ServerErrorJava("http://127.0.0.1:49080", PROJECT, USER)

    response = adapter.handle(_request(1, "tools/call", name="localcloud_call_api"))

    assert response is not None and response["error"]["data"] == {"code": "java_mcp_unavailable"}
    assert calls == [1]


# Workspace roots -----------------------------------------------------------


def _git_repository(path: Path) -> Path:
    path.mkdir(parents=True)
    subprocess.run(["git", "init", "-q", str(path)], check=True)
    return path


def _initialized_with_roots(adapter: McpAdapter) -> dict[str, Any]:
    adapter.handle(
        _request(1, "initialize", protocolVersion="2025-06-18", capabilities={"roots": {}})
    )
    adapter.handle({"jsonrpc": "2.0", "method": "notifications/initialized"})
    (request,) = adapter.drain_outbox()
    return request


@pytest.mark.usefixtures("real_git")
def test_the_client_workspace_selects_the_project(tmp_path: Path) -> None:
    repository = _git_repository(tmp_path / "billing-api")
    controller = RunningController()
    adapter = McpAdapter(replace(CONFIG, project_source="default"), controller=controller)

    request = _initialized_with_roots(adapter)
    assert request["method"] == "roots/list"
    adapter.handle_response(
        {
            "jsonrpc": "2.0",
            "id": request["id"],
            "result": {"roots": [{"uri": repository.as_uri(), "name": "billing"}]},
        }
    )

    assert adapter.config is not None and adapter.config.project == "billing-api"
    assert adapter.config.project_source == "git"
    assert controller.ensured[-1] == "billing-api"
    assert FakeJava.clients[-1] == ("http://127.0.0.1:49080", "billing-api")


def test_an_explicit_project_ignores_the_client_workspace() -> None:
    adapter = McpAdapter(replace(CONFIG, project_source="flag"), controller=RunningController())

    adapter.handle(
        _request(1, "initialize", protocolVersion="2025-06-18", capabilities={"roots": {}})
    )
    adapter.handle({"jsonrpc": "2.0", "method": "notifications/initialized"})

    assert adapter.drain_outbox() == []


@pytest.mark.usefixtures("real_git")
def test_a_workspace_without_a_project_keeps_the_current_one(tmp_path: Path) -> None:
    adapter = McpAdapter(replace(CONFIG, project_source="default"), controller=RunningController())
    request = _initialized_with_roots(adapter)

    adapter.handle_response(
        {"jsonrpc": "2.0", "id": request["id"], "result": {"roots": [{"uri": tmp_path.as_uri()}]}}
    )

    assert adapter.config is not None and adapter.config.project == PROJECT


# Stdio server --------------------------------------------------------------


def _session(payload: dict[str, Any]) -> Any:
    from mcp import types
    from mcp.shared.message import SessionMessage

    return SessionMessage(types.jsonrpc_message_adapter.validate_python(payload))


def _serve(
    monkeypatch: pytest.MonkeyPatch,
    controller: Any,
    incoming: list[tuple[float, dict[str, Any]]],
) -> list[dict[str, Any]]:
    """Run the stdio server over in-memory streams; return what it wrote."""
    import mcp.server.stdio as stdio_module

    written: list[dict[str, Any]] = []
    monkeypatch.setattr(mcp_module, "Controller", lambda: controller)

    @asynccontextmanager
    async def fake_stdio_server():
        client_send, server_read = anyio.create_memory_object_stream(10)
        server_write, client_read = anyio.create_memory_object_stream(10)

        async def feed() -> None:
            async with client_send:
                for delay, payload in incoming:
                    await anyio.sleep(delay)
                    await client_send.send(_session(payload))
                # Let the last answers out before the client closes stdin.
                await anyio.sleep(0.3)

        async def collect() -> None:
            async with client_read:
                async for message in client_read:
                    written.append(message.message.model_dump(by_alias=True, exclude_unset=True))

        async with anyio.create_task_group() as tasks:
            tasks.start_soon(feed)
            tasks.start_soon(collect)
            yield server_read, server_write

    monkeypatch.setattr(stdio_module, "stdio_server", fake_stdio_server)
    anyio.run(mcp_module._run_sdk, CONFIG)
    return written


def test_a_long_call_does_not_hold_up_other_requests(monkeypatch: pytest.MonkeyPatch) -> None:
    class SlowJava(FakeJava):
        def forward(self, message: dict[str, Any], **_kwargs: Any) -> dict[str, Any] | None:
            if message.get("method") == "tools/call":
                time.sleep(0.5)
            return {"jsonrpc": "2.0", "id": message.get("id"), "result": {"done": message["method"]}}

    monkeypatch.setattr(mcp_module, "JavaMcpClient", SlowJava)

    written = _serve(
        monkeypatch,
        RunningController(),
        [
            (0.0, _request(1, "tools/call", name="localcloud_query_data")),
            (0.05, _request(2, "ping")),
            (0.05, _request(3, "tools/list")),
            (1.0, _request(4, "ping")),
        ],
    )

    assert [message["id"] for message in written] == [2, 3, 1, 4]


def test_a_cancelled_request_gets_no_response(monkeypatch: pytest.MonkeyPatch) -> None:
    class SlowJava(FakeJava):
        def forward(self, message: dict[str, Any], **_kwargs: Any) -> dict[str, Any] | None:
            if message.get("method") == "tools/call":
                time.sleep(0.3)
            return {"jsonrpc": "2.0", "id": message.get("id"), "result": {}}

    monkeypatch.setattr(mcp_module, "JavaMcpClient", SlowJava)

    written = _serve(
        monkeypatch,
        RunningController(),
        [
            (0.0, _request(1, "tools/call", name="localcloud_query_data")),
            (
                0.05,
                {
                    "jsonrpc": "2.0",
                    "method": "notifications/cancelled",
                    "params": {"requestId": 1},
                },
            ),
            (0.6, _request(2, "ping")),
        ],
    )

    assert [message["id"] for message in written] == [2]
