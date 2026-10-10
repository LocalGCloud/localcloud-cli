from __future__ import annotations

import copy
from dataclasses import replace
from pathlib import Path
import signal
import sys
import threading
import time
from typing import Any, Callable
from urllib.parse import unquote, urlparse

import anyio
import httpx

from . import __version__
from .config import LocalCloudConfig, workspace_project
from .controller import Controller
from .errors import HostError
from .endpoints import transform_endpoint_payload
from .java_client import JavaMcpClient
from .output import terminal_capabilities


# Requests that last as long as the service work they start.
_LONG_METHODS = {"tools/call", "resources/read", "prompts/get"}
_LONG_REQUEST_TIMEOUT = httpx.Timeout(600.0, connect=5.0)
# How long a request waits for a runtime that is still connecting before the
# bridge answers without it. Clients time out startup after 10-60 seconds, so the
# handshake and lists stay short; calls may wait for a cold start.
_HANDSHAKE_WAIT = 4.0
_LIST_WAIT = 3.0
_CALL_WAIT = 90.0
_ROOTS_WAIT = 2.0
# A failed connection is retried at most this often.
_RETRY_INTERVAL = 5.0

# The protocol versions LocalCloud's MCP server negotiates; it answers any other
# request with the newest.
_RUNTIME_PROTOCOL_VERSIONS = ("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

STATUS_TOOL = "localcloud_runtime_status"
_STATUS_TOOL_DEFINITION = {
    "name": STATUS_TOOL,
    "title": "LocalCloud runtime status",
    "description": (
        "Report whether the LocalCloud runtime is ready and, if not, what to do. "
        "LocalCloud's tools, resources and prompts are listed once it is ready. "
        "Call this to wait for a runtime that is starting, or to retry after "
        "fixing the problem it reports."
    ),
    "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
    "annotations": {"readOnlyHint": True, "openWorldHint": False},
}
_UNAVAILABLE_INSTRUCTIONS = (
    "LocalCloud runs Google Cloud services locally. Its runtime is not ready yet: "
    f"call {STATUS_TOOL} to wait for it or to learn what to fix. LocalCloud's "
    "tools, resources and prompts are listed once it is ready. Never fall back to "
    "real Google Cloud."
)
_LIST_KEYS = {
    "tools/list": "tools",
    "resources/list": "resources",
    "resources/templates/list": "resourceTemplates",
    "prompts/list": "prompts",
}
_LIST_CHANGED = (
    "notifications/tools/list_changed",
    "notifications/resources/list_changed",
    "notifications/prompts/list_changed",
)
# Projects a client's workspace may replace: ones the bridge chose by itself.
_WORKSPACE_PROJECT_SOURCES = {"git", "default"}


Prepare = Callable[[], "tuple[Any, LocalCloudConfig]"]


class McpAdapter:
    """Forwards MCP traffic to the runtime's Java MCP server.

    The runtime connection is made in the background, so the client's handshake
    is answered even while LocalCloud starts or when it cannot: until it is
    ready the bridge lists a single status tool and answers calls with what to
    fix, then tells the client its lists changed."""

    def __init__(
        self,
        config: LocalCloudConfig | None,
        *,
        controller: Any | None = None,
        connect_timeout: float = 10.0,
        on_connecting: Callable[[str], None] | None = None,
        on_connected: Callable[[str], None] | None = None,
        auto_start: bool = True,
        prepare: Prepare | None = None,
        connect: bool = True,
    ):
        self.config = config
        self.controller = controller
        self.connect_timeout = connect_timeout
        self.on_connecting = on_connecting
        self.on_connected = on_connected
        self.auto_start = auto_start
        self._prepare = prepare
        self.mcp_url: str | None = None
        self.target: dict[str, Any] | None = None
        self.endpoint_map: dict[str, int] = {}
        self.java: Any = None

        self._lock = threading.Lock()
        self._state = "idle"  # idle, connecting, connected, failed
        self._error: HostError | None = None
        self._logged_error: str | None = None
        self._attempt_done = threading.Event()
        self._attempt_done.set()
        self._last_failure = 0.0
        self._generation = 0
        # The client has seen lists without the runtime's items.
        self._degraded = False
        self._client_initialized = False
        self._client_capabilities: dict[str, Any] = {}
        self._outbox: list[dict[str, Any]] = []
        self._cancelled: set[Any] = set()
        self._workspace: tuple[str, Any] | None = None
        self._roots_request = 0
        self._roots_pending: str | None = None
        self._roots_ready = threading.Event()
        self._roots_ready.set()

        if connect:
            # Synchronous connection for direct use: failures raise.
            self._connect_once()
            self._state = "connected"

    # Runtime connection ----------------------------------------------------

    def start_connecting(self) -> None:
        """Begin connecting in the background (used by the stdio server)."""
        self._maybe_start_attempt(force=True)

    def ensure_connected(self, wait: float, *, retry_now: bool = False) -> bool:
        """Connect if needed, waiting at most `wait` seconds. A failed
        connection is retried at most every few seconds unless `retry_now`."""
        self._maybe_start_attempt(force=retry_now)
        self._attempt_done.wait(wait)
        return self._state == "connected"

    def _maybe_start_attempt(self, *, force: bool = False) -> None:
        with self._lock:
            if self._state in {"connected", "connecting"}:
                return
            if (
                not force
                and self._state == "failed"
                and time.monotonic() - self._last_failure < _RETRY_INTERVAL
            ):
                return
            self._state = "connecting"
            self._attempt_done.clear()
        threading.Thread(
            target=self._attempt, name="localcloud-mcp-connect", daemon=True
        ).start()

    def _attempt(self) -> None:
        try:
            self._connect_once()
        except HostError as error:
            self._fail(error)
        except Exception as error:  # A bug must not leave requests waiting.
            self._fail(
                HostError(
                    "unexpected_error",
                    f"LocalCloud MCP could not connect: {error}",
                    {"type": type(error).__name__},
                )
            )
        else:
            with self._lock:
                self._state = "connected"
                self._error = None
                self._logged_error = None
                self._generation += 1
                if self._degraded:
                    self._degraded = False
                    self._outbox.extend(
                        {"jsonrpc": "2.0", "method": method} for method in _LIST_CHANGED
                    )
            if self.on_connected is not None and self.mcp_url is not None:
                self.on_connected(self.mcp_url)
        finally:
            self._attempt_done.set()

    def _fail(self, error: HostError) -> None:
        with self._lock:
            self._state = "failed"
            self._error = error
            self._last_failure = time.monotonic()
            report = error.code != self._logged_error
            self._logged_error = error.code
        if report:
            # Clients keep a server's stderr in their MCP logs.
            print(
                f"[localcloud mcp] {error.code}: {error.message}",
                file=sys.stderr,
                flush=True,
            )

    def _prepared(self) -> tuple[Any, LocalCloudConfig]:
        if self._prepare is not None and (self.controller is None or self.config is None):
            controller, config = self._prepare()
            self.controller = controller
            self.config = self._with_workspace(config)
        elif self.controller is None:
            self.controller = Controller()
        if self.config is None:
            raise HostError("invalid_config", "No LocalCloud configuration was resolved")
        return self.controller, self.config

    def _connect_once(self) -> None:
        controller, config = self._prepared()
        connecting_url: str | None = None

        def url_resolved(url: str) -> None:
            nonlocal connecting_url
            connecting_url = f"{url.rstrip('/')}/mcp"
            if self.on_connecting is not None:
                self.on_connecting(connecting_url)

        if self.auto_start:
            # Starts a stopped or missing runtime; never replaces a running one.
            controller.start(config, ensure_project=True, allow_replace=False)
        try:
            target = controller.target(
                config,
                readiness_timeout=self.connect_timeout,
                on_url_resolved=url_resolved,
                ensure_project=config.project_source in {"flag", "git"},
            )
        except HostError as error:
            if error.code == "runtime_readiness_timeout":
                if connecting_url is None:
                    url = error.details.get("url")
                    if isinstance(url, str) and url:
                        connecting_url = f"{url.rstrip('/')}/mcp"
                target_name = connecting_url or "the LocalCloud MCP endpoint"
                timeout_unit = "second" if self.connect_timeout == 1 else "seconds"
                raise HostError(
                    "mcp_connection_timeout",
                    (
                        f"Could not connect to LocalCloud MCP at {target_name} "
                        f"within {self.connect_timeout:g} {timeout_unit}."
                    ),
                    {
                        "data_volume": config.data_volume,
                        "project": config.project,
                        "url": connecting_url,
                        "timeout_seconds": self.connect_timeout,
                    },
                ) from error
            if error.code == "runtime_not_running":
                raise HostError(
                    "runtime_not_running",
                    "Run "
                    f"localcloud start --data-volume {config.data_volume} "
                    f"--project-id {config.project} --user {config.user} "
                    "before connecting MCP.",
                    {
                        "data_volume": config.data_volume,
                        "project": config.project,
                        "user": config.user,
                    },
                ) from error
            raise
        self._set_target(target)
        # A workspace reported while connecting selects another project.
        if self.config is not None and self.config.project != config.project:
            self._use_project(self.config)

    def _set_target(self, target: dict[str, Any]) -> None:
        assert self.config is not None
        try:
            endpoint_map = {
                str(canonical): int(host_port)
                for canonical, host_port in target["endpoint_map"].items()
            }
            url = target["url"].rstrip("/")
        except (KeyError, TypeError, ValueError, AttributeError) as error:
            raise HostError(
                "runtime_target_invalid",
                "LocalCloud runtime target could not be resolved for MCP",
                {"data_volume": self.config.data_volume, "cause": str(error)},
            ) from error
        self.endpoint_map = endpoint_map
        self.target = target
        self.mcp_url = f"{url}/mcp"
        self.java = JavaMcpClient(url, project=self.config.project, user=self.config.user)

    def _use_project(self, config: LocalCloudConfig) -> None:
        """Create `config.project` if needed and send requests to it."""
        target = self.controller.target(
            config, readiness_timeout=self.connect_timeout, ensure_project=True
        )
        self._set_target(target)

    def _reconnect(self, generation: int) -> bool:
        """After the runtime refused a connection (stopped, restarted, or moved
        ports), connect again unless another request already did."""
        with self._lock:
            if self._generation == generation and self._state == "connected":
                self._state = "idle"
        return self.ensure_connected(_CALL_WAIT)

    # Workspace roots -------------------------------------------------------

    def _with_workspace(self, config: LocalCloudConfig) -> LocalCloudConfig:
        if self._workspace is None or config.project_source not in _WORKSPACE_PROJECT_SOURCES:
            return config
        project, source = self._workspace
        return replace(config, project=project, project_source=source)

    def _wants_roots(self) -> bool:
        return "roots" in self._client_capabilities and (
            self.config is None or self.config.project_source in _WORKSPACE_PROJECT_SOURCES
        )

    def _request_roots(self) -> None:
        with self._lock:
            self._roots_request += 1
            self._roots_pending = f"localcloud-roots-{self._roots_request}"
            self._roots_ready.clear()
            self._outbox.append(
                {"jsonrpc": "2.0", "id": self._roots_pending, "method": "roots/list"}
            )

    def handle_response(self, message: dict[str, Any]) -> None:
        """A client's response to a request the bridge sent (roots/list)."""
        if message.get("id") != self._roots_pending:
            return
        try:
            result = message.get("result")
            if isinstance(result, dict):
                self.apply_roots(result)
        finally:
            self._roots_ready.set()

    def apply_roots(self, result: dict[str, Any]) -> None:
        """Select the project of the client's workspace, as a command run in it
        would."""
        roots = result.get("roots")
        selected = None
        for root in roots if isinstance(roots, list) else []:
            directory = _root_directory(root)
            if directory is not None:
                selected = workspace_project(directory)
                if selected is not None:
                    break
        if selected is None:
            return
        self._workspace = selected
        config = self.config
        if config is None:
            return
        updated = self._with_workspace(config)
        if updated.project == config.project:
            return
        self.config = updated
        if self._state == "connected":
            try:
                self._use_project(updated)
            except HostError as error:
                self._fail(error)

    # Requests --------------------------------------------------------------

    def drain_outbox(self) -> list[dict[str, Any]]:
        """Messages for the client; held until it finished initializing."""
        with self._lock:
            if not self._client_initialized:
                return []
            messages, self._outbox = self._outbox, []
        return messages

    def take_cancelled(self, request_id: Any) -> bool:
        with self._lock:
            if request_id in self._cancelled:
                self._cancelled.discard(request_id)
                return True
        return False

    def handle(self, message: dict[str, Any]) -> dict[str, Any] | None:
        method = message.get("method")
        if not isinstance(method, str):
            return None
        request_id = message.get("id")
        if request_id is None:
            self._notification(method, message)
            return None
        params = message.get("params")
        params = params if isinstance(params, dict) else {}
        if method == "ping":
            return {"jsonrpc": "2.0", "id": request_id, "result": {}}
        if method == "initialize":
            return self._initialize(message, params)
        if method == "server/discover" and self._state != "connected":
            # Newer clients then fall back to initialize.
            return _error(request_id, -32601, "Method not found", method)
        if method in _LIST_KEYS:
            return self._list(message)
        if method == "tools/call" and params.get("name") == STATUS_TOOL:
            return self._status_result(request_id)
        if not self.ensure_connected(_CALL_WAIT):
            return self._unavailable(message)
        if method == "tools/call":
            self._roots_ready.wait(_ROOTS_WAIT)
        return self._forward(message)

    def _notification(self, method: str, message: dict[str, Any]) -> None:
        if method == "notifications/cancelled":
            params = message.get("params")
            if isinstance(params, dict) and "requestId" in params:
                with self._lock:
                    self._cancelled.add(params["requestId"])
        elif method == "notifications/initialized":
            with self._lock:
                self._client_initialized = True
            if self._wants_roots():
                self._request_roots()
        elif method == "notifications/roots/list_changed":
            if self._wants_roots():
                self._request_roots()
        if self._state == "connected":
            try:
                self.java.forward(message)
            except Exception:
                pass  # Notifications have no reply to carry an error.

    def _initialize(
        self, message: dict[str, Any], params: dict[str, Any]
    ) -> dict[str, Any]:
        capabilities = params.get("capabilities")
        self._client_capabilities = capabilities if isinstance(capabilities, dict) else {}
        if self.ensure_connected(_HANDSHAKE_WAIT):
            response = self._forward(message)
            if "result" in response:
                return response
        self._degraded = True
        requested = params.get("protocolVersion")
        return {
            "jsonrpc": "2.0",
            "id": message["id"],
            "result": {
                "protocolVersion": (
                    requested
                    if requested in _RUNTIME_PROTOCOL_VERSIONS
                    else _RUNTIME_PROTOCOL_VERSIONS[0]
                ),
                "capabilities": {
                    "tools": {"listChanged": True},
                    "resources": {"listChanged": True},
                    "prompts": {"listChanged": True},
                },
                "serverInfo": {"name": "localcloud", "version": __version__},
                "instructions": _UNAVAILABLE_INSTRUCTIONS,
            },
        }

    def _list(self, message: dict[str, Any]) -> dict[str, Any]:
        if self.ensure_connected(_LIST_WAIT):
            response = self._forward(message)
            if "result" in response:
                return response
        self._degraded = True
        method = message["method"]
        items = [_STATUS_TOOL_DEFINITION] if method == "tools/list" else []
        return {"jsonrpc": "2.0", "id": message["id"], "result": {_LIST_KEYS[method]: items}}

    def status(self) -> dict[str, Any]:
        with self._lock:
            state, error = self._state, self._error
        config = self.config
        result: dict[str, Any] = {
            "state": {"connected": "ready", "connecting": "starting"}.get(
                state, "unavailable"
            ),
            "auto_start": self.auto_start,
        }
        if config is not None:
            result.update(
                data_volume=config.data_volume,
                project=config.project,
                project_source=config.project_source,
                user=config.user,
            )
        if self.mcp_url is not None:
            result["url"] = self.mcp_url
        if state == "connected":
            result["next_step"] = (
                "LocalCloud is ready. If its tools are missing from your tool list, "
                "reconnect the localcloud MCP server."
            )
            return result
        problem = error if state == "failed" and error is not None else _starting()
        result["error"] = {"code": problem.code, "message": problem.message}
        result["next_step"] = _recovery(problem, auto_start=self.auto_start)
        return result

    def _status_result(self, request_id: Any) -> dict[str, Any]:
        ready = self.ensure_connected(_CALL_WAIT, retry_now=True)
        status = self.status()
        lines = [f"LocalCloud is {status['state']}."]
        if "project" in status:
            lines.append(
                f"Project: {status['project']} · data volume: {status['data_volume']}"
                f" · user: {status['user']}"
            )
        if "error" in status:
            lines.append(status["error"]["message"])
        lines.append(status["next_step"])
        return {
            "jsonrpc": "2.0",
            "id": request_id,
            "result": {
                "content": [{"type": "text", "text": "\n".join(lines)}],
                "structuredContent": status,
                "isError": not ready,
            },
        }

    def _unavailable(self, message: dict[str, Any]) -> dict[str, Any]:
        with self._lock:
            state, error = self._state, self._error
        problem = error if state == "failed" and error is not None else _starting()
        text = f"{problem.message}\n{_recovery(problem, auto_start=self.auto_start)}"
        if message["method"] == "tools/call":
            return {
                "jsonrpc": "2.0",
                "id": message["id"],
                "result": {"content": [{"type": "text", "text": text}], "isError": True},
            }
        return _error(message["id"], -32000, text, {"code": problem.code})

    def _forward(self, message: dict[str, Any]) -> dict[str, Any]:
        request_id = message.get("id")
        method = str(message.get("method"))
        timeout = _LONG_REQUEST_TIMEOUT if method in _LONG_METHODS else None
        try:
            generation = self._generation
            try:
                response = self.java.forward(message, timeout=timeout)
            except HostError as error:
                if not error.details.get("connect_failed") or not self._reconnect(generation):
                    raise
                # Nothing reached the old address, so sending again is safe.
                response = self.java.forward(message, timeout=timeout)
            if response is None:
                raise HostError(
                    "java_mcp_invalid_response",
                    "Java LocalCloud MCP returned no response for a request",
                    {"method": method},
                )
            if method in {"tools/call", "resources/read"} and "result" in response:
                response = dict(response)
                response["result"] = transform_endpoint_payload(
                    response["result"], self.endpoint_map
                )
            if method == "tools/list" and isinstance(response.get("result"), dict):
                tools = response["result"].get("tools")
                if isinstance(tools, list):
                    # Fill display metadata while preserving the runtime's safety declarations.
                    response = copy.deepcopy(response)
                    for tool in response["result"]["tools"]:
                        if (
                            not isinstance(tool, dict)
                            or not isinstance(tool.get("name"), str)
                            or not tool["name"]
                        ):
                            continue
                        annotations = tool.get("annotations")
                        if annotations is None:
                            annotations = tool["annotations"] = {}
                        if not isinstance(annotations, dict):
                            continue
                        title = tool.get("title") or annotations.get("title")
                        if not title:
                            title = " ".join(
                                word.upper() if word in {"api", "sdk", "sql", "iam"} else word
                                for word in tool["name"].removeprefix("localcloud_").split("_")
                            )
                            title = title[:1].upper() + title[1:]
                        if not tool.get("title"):
                            tool["title"] = title
                        if not annotations.get("title"):
                            annotations["title"] = title
            return response
        except HostError as error:
            # Only the error code crosses the wire - `details` may carry
            # upstream RPC bodies, container ids, or other internals that
            # shouldn't be forwarded to MCP clients verbatim.
            return _error(request_id, -32000, error.message, {"code": error.code})
        except Exception as error:
            return _error(request_id, -32603, "LocalCloud MCP adapter failed", str(error))


def _error(request_id: Any, code: int, message: str, data: Any) -> dict[str, Any]:
    return {
        "jsonrpc": "2.0",
        "id": request_id,
        "error": {"code": code, "message": message, "data": data},
    }


def _starting() -> HostError:
    return HostError(
        "runtime_starting",
        "LocalCloud is still starting. The first start can take a minute, and "
        "longer while its image downloads.",
    )


def _recovery(error: HostError, *, auto_start: bool) -> str:
    retry = f"then call {STATUS_TOOL}"
    if error.code == "docker_unavailable":
        return (
            f"Ask the user to start Docker, {retry}. `lc doctor` diagnoses Docker "
            "setup problems."
        )
    if error.code in {"invalid_config", "invalid_config_directory"}:
        return f"Ask the user to fix their localcloud.yaml, {retry}."
    if not auto_start and error.code in {"runtime_not_running", "unknown_project"}:
        return (
            "This LocalCloud MCP server runs with --no-start. Ask the user to run "
            f"`lc start` in the project, {retry}."
        )
    if error.code in {"runtime_starting", "mcp_connection_timeout", "runtime_readiness_timeout"}:
        return (
            f"Call {STATUS_TOOL} to wait for it. If it stays unavailable, ask the "
            "user to check `lc status` and `lc logs`."
        )
    return f"Ask the user to run `lc doctor` and `lc start`, {retry}."


def _root_directory(root: Any) -> Path | None:
    uri = root.get("uri") if isinstance(root, dict) else None
    if not isinstance(uri, str):
        return None
    parsed = urlparse(uri)
    if parsed.scheme != "file":
        return None
    path = unquote(parsed.path)
    if sys.platform == "win32" and path.startswith("/") and path[2:3] == ":":
        path = path[1:]
    directory = Path(path)
    return directory if directory.is_dir() else None


def _raise_keyboard_interrupt(_signum: int, _frame: Any) -> None:
    # asyncio otherwise turns the first SIGINT into cancellation, but the MCP
    # stdin worker can block that cancellation indefinitely.
    raise KeyboardInterrupt


def run(
    config: LocalCloudConfig | None,
    *,
    connect_timeout: float = 10.0,
    auto_start: bool = True,
    prepare: Prepare | None = None,
) -> None:
    def report_connecting(mcp_url: str) -> None:
        if terminal_capabilities(sys.stderr).interactive:
            print(
                f"Connecting to LocalCloud MCP at {mcp_url} "
                f"(timeout: {connect_timeout:g}s)…",
                file=sys.stderr,
                flush=True,
            )

    previous_sigint = signal.signal(signal.SIGINT, _raise_keyboard_interrupt)
    try:
        anyio.run(
            _run_sdk, config, connect_timeout, report_connecting, auto_start, prepare
        )
    finally:
        signal.signal(signal.SIGINT, previous_sigint)


async def _run_sdk(
    config: LocalCloudConfig | None,
    connect_timeout: float = 10.0,
    on_connecting: Callable[[str], None] | None = None,
    auto_start: bool = True,
    prepare: Prepare | None = None,
) -> None:
    from mcp import types
    from mcp.server.stdio import stdio_server
    from mcp.shared.message import SessionMessage

    interactive = terminal_capabilities(sys.stderr).interactive

    def report_connected(mcp_url: str) -> None:
        if interactive:
            print(f"Connected to LocalCloud at {mcp_url}", file=sys.stderr, flush=True)

    adapter = McpAdapter(
        config,
        connect_timeout=connect_timeout,
        on_connecting=on_connecting,
        on_connected=report_connected,
        auto_start=auto_start,
        prepare=prepare,
        connect=False,
    )
    adapter.start_connecting()

    async with stdio_server() as (read_stream, write_stream):
        async with read_stream, write_stream:
            if interactive:
                print(
                    "Accepting MCP requests over stdio. Press Ctrl-C to close.",
                    file=sys.stderr,
                    flush=True,
                )

            async def send(payload: dict[str, Any]) -> None:
                try:
                    parsed = types.jsonrpc_message_adapter.validate_python(payload)
                except Exception as error:
                    # A malformed response (e.g. an upstream Java MCP payload
                    # forwarded verbatim that doesn't match the local schema)
                    # must not kill the bridge - degrade to a clean error.
                    if "id" not in payload:
                        return
                    parsed = types.jsonrpc_message_adapter.validate_python(
                        _error(
                            payload.get("id"),
                            -32603,
                            "LocalCloud MCP adapter produced an invalid response",
                            str(error),
                        )
                    )
                await write_stream.send(SessionMessage(parsed))

            async def respond(message: dict[str, Any]) -> None:
                response = await anyio.to_thread.run_sync(
                    adapter.handle, message, abandon_on_cancel=True
                )
                if response is not None and not adapter.take_cancelled(response.get("id")):
                    await send(response)

            async def accept_response(message: dict[str, Any]) -> None:
                await anyio.to_thread.run_sync(
                    adapter.handle_response, message, abandon_on_cancel=True
                )

            async def deliver_outbox() -> None:
                while True:
                    for payload in adapter.drain_outbox():
                        await send(payload)
                    await anyio.sleep(0.2)

            async with anyio.create_task_group() as tasks:
                tasks.start_soon(deliver_outbox)
                async for incoming in read_stream:
                    if isinstance(incoming, Exception):
                        await send(_error(None, -32700, "Parse error", str(incoming)))
                        continue
                    message = incoming.message.model_dump(by_alias=True, exclude_unset=True)
                    if "method" not in message:
                        # The client's answer to a request the bridge sent.
                        tasks.start_soon(accept_response, message)
                        continue
                    # Requests run concurrently: a long call does not hold up
                    # pings, cancellations, or other calls.
                    tasks.start_soon(respond, message)
                tasks.cancel_scope.cancel()
