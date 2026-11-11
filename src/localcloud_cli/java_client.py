from __future__ import annotations

import itertools
import os
import threading
import time
from collections.abc import Callable, Mapping
from typing import Any
from urllib.parse import quote

import httpx

from .constants import DEFAULT_PROJECT, DEFAULT_USER
from .errors import HostError


def is_retryable_java_error(error: BaseException) -> bool:
    return (
        isinstance(error, HostError)
        and error.details.get("retryable") is True
    )


def _transport_error(
    code: str,
    message: str,
    error: Exception,
    *,
    url: str,
    method: str,
) -> HostError:
    response = getattr(error, "response", None)
    status_code = getattr(response, "status_code", None)
    retryable = isinstance(
        error,
        (
            httpx.NetworkError,
            httpx.TimeoutException,
            httpx.RemoteProtocolError,
        ),
    )
    if isinstance(status_code, int):
        retryable = (
            status_code in {408, 429}
            or 500 <= status_code < 600
        )
    details: dict[str, Any] = {
        "url": url,
        "method": method,
        "cause": str(error),
        "retryable": retryable,
        # Nothing reached the server, so resending cannot repeat its effects.
        "connect_failed": isinstance(error, httpx.ConnectError),
    }
    if isinstance(status_code, int):
        details["status_code"] = status_code
    return HostError(code, message, details)


# Applying a scenario can start Dataproc clusters, and their first start pulls an image, which
# takes minutes. The bound is the caller's, in seconds.
SCENARIO_APPLY_TIMEOUT_ENV = "LOCALCLOUD_SCENARIO_APPLY_TIMEOUT"
DEFAULT_SCENARIO_APPLY_TIMEOUT = 900.0
_PROGRESS_INTERVAL = 15.0
_TASK_POLL_INTERVAL = 2.0
_TASK_DONE = {"completed", "failed", "cancelled"}


def scenario_apply_timeout(environ: Mapping[str, str] | None = None) -> float:
    """The seconds a scenario apply may take: LOCALCLOUD_SCENARIO_APPLY_TIMEOUT,
    else 15 minutes."""
    raw = (os.environ if environ is None else environ).get(SCENARIO_APPLY_TIMEOUT_ENV, "")
    if not str(raw).strip():
        return DEFAULT_SCENARIO_APPLY_TIMEOUT
    try:
        value = float(raw)
    except ValueError:
        value = 0.0
    if not value > 0:
        raise HostError(
            "invalid_scenario_apply_timeout",
            f"{SCENARIO_APPLY_TIMEOUT_ENV} must be a positive number of seconds",
            {"value": raw},
        )
    return value


_ORIGINAL_HTTPX_GET = httpx.get
_ORIGINAL_HTTPX_POST = httpx.post
_ORIGINAL_HTTPX_REQUEST = httpx.request
_SHARED_CLIENT: httpx.Client | None = None


def get_shared_http_client() -> httpx.Client:
    """The client for LocalCloud's own listeners, which are always on this machine.

    It ignores HTTP_PROXY, HTTPS_PROXY and ALL_PROXY: a developer's proxy cannot reach their
    loopback LocalCloud, and must never see its requests.

    It keeps no idle connections. An idle `lc mcp` never reuses a pooled one,
    so after the runtime restarts each would stay half-closed (CLOSE_WAIT) for
    as long as the client runs, and the port forwarder's end with it.
    """
    global _SHARED_CLIENT
    if _SHARED_CLIENT is None or _SHARED_CLIENT.is_closed:
        _SHARED_CLIENT = httpx.Client(
            trust_env=False, limits=httpx.Limits(max_keepalive_connections=0)
        )
    return _SHARED_CLIENT


class JavaMcpClient:
    """Thin HTTP client for lifecycle calls delegated to the authoritative Java MCP."""

    def __init__(
        self,
        url: str,
        project: str = DEFAULT_PROJECT,
        user: str = DEFAULT_USER,
        timeout: float = 60.0,
    ):
        self.url = url.rstrip("/")
        self.project = project
        self.user = user
        self.timeout = timeout
        self._ids = itertools.count(1)

    def _http_request(self, method: str, url: str, **kwargs: Any) -> httpx.Response:
        if method == "POST" and httpx.post is not _ORIGINAL_HTTPX_POST:
            return httpx.post(url, **kwargs)
        if method == "GET" and httpx.get is not _ORIGINAL_HTTPX_GET:
            return httpx.get(url, **kwargs)
        if httpx.request is not _ORIGINAL_HTTPX_REQUEST:
            return httpx.request(method, url, **kwargs)
        return get_shared_http_client().request(method, url, **kwargs)

    def forward(
        self,
        message: dict[str, Any],
        *,
        timeout: float | httpx.Timeout | None = None,
    ) -> dict[str, Any] | None:
        return self._post_mcp(message, allow_empty=True, timeout=timeout)

    def rpc(
        self,
        method: str,
        params: dict[str, Any] | None = None,
        *,
        timeout: float | httpx.Timeout | None = None,
    ) -> Any:
        payload = {
            "jsonrpc": "2.0",
            "id": next(self._ids),
            "method": method,
            "params": params or {},
        }
        body = self._post_mcp(payload, allow_empty=False, timeout=timeout)
        assert body is not None
        if "error" in body:
            rpc_error = body["error"]
            if not isinstance(rpc_error, dict):
                raise HostError(
                    "java_mcp_invalid_response",
                    "Java LocalCloud MCP returned a malformed error response",
                    {"method": method},
                )
            details: dict[str, Any] = {"method": method, "error": rpc_error}
            if method == "tools/call" and isinstance(params, dict):
                tool_name = params.get("name")
                if isinstance(tool_name, str):
                    details["tool"] = tool_name
            raise HostError(
                "java_mcp_error",
                str(rpc_error.get("message") or "Java MCP error"),
                details,
            )
        if "result" not in body:
            raise HostError(
                "java_mcp_invalid_response",
                "Java LocalCloud MCP response has no result",
                {"method": method},
            )
        return body["result"]

    def _post_mcp(
        self,
        message: dict[str, Any],
        *,
        allow_empty: bool,
        timeout: float | httpx.Timeout | None = None,
    ) -> dict[str, Any] | None:
        method = str(message.get("method") or "")
        try:
            response = self._http_request(
                "POST",
                f"{self.url}/mcp",
                json=message,
                headers=self._headers(),
                timeout=self.timeout if timeout is None else timeout,
            )
            response.raise_for_status()
        except Exception as error:
            raise _transport_error(
                "java_mcp_unavailable",
                "Java LocalCloud MCP request failed",
                error,
                url=f"{self.url}/mcp",
                method=method,
            ) from error
        if response.status_code == 202 or not response.content:
            if allow_empty:
                return None
            raise HostError(
                "java_mcp_invalid_response",
                "Java LocalCloud MCP returned an empty response",
                {"method": method},
            )
        try:
            body = response.json()
        except Exception as error:
            raise HostError(
                "java_mcp_invalid_response",
                "Java LocalCloud MCP returned malformed JSON",
                {"method": method, "cause": str(error)},
            ) from error
        if not isinstance(body, dict):
            raise HostError(
                "java_mcp_invalid_response",
                "Java LocalCloud MCP returned a non-object response",
                {"method": method},
            )
        return body

    def _headers(self) -> dict[str, str]:
        return {
            "Accept": "application/json, text/event-stream",
            "X-LocalCloud-Project": self.project,
            "X-LocalCloud-User": self.user,
        }

    def tool(self, name: str, arguments: dict[str, Any] | None = None) -> Any:
        values = dict(arguments or {})
        if "project" not in values:
            values["project"] = self.project
        result = self.rpc("tools/call", {"name": name, "arguments": values})
        return self._tool_result(name, result)

    def _tool_result(self, name: str, result: Any) -> Any:
        if not isinstance(result, dict):
            raise HostError(
                "java_mcp_invalid_response",
                "Java LocalCloud MCP returned an invalid tool result",
                {"tool": name},
            )
        if result.get("isError"):
            content = result.get("content") or []
            first = content[0] if content and isinstance(content[0], dict) else {}
            text = first.get("text", "Java MCP tool failed")
            raise HostError("java_tool_error", text, {"tool": name})
        structured = result.get("structuredContent")
        if isinstance(structured, dict) and "result" in structured:
            return structured["result"]
        content = result.get("content", [])
        return content[0].get("text") if content else None

    def apply_scenario(
        self,
        scenario: str,
        project: str | None = None,
        *,
        replace: bool = False,
        timeout: float | None = None,
        progress: Callable[[str], None] | None = None,
        clock: Callable[[], float] = time.monotonic,
        sleep: Callable[[float], None] = time.sleep,
    ) -> Any:
        """`localcloud_apply_scenario`, allowed `timeout` seconds (default
        scenario_apply_timeout()) instead of the client's request timeout.

        The call asks for an MCP task, so it works either way a runtime answers
        a long apply: with the result once the apply finishes (the request then
        stays open up to the bound), or at once with a task that is polled with
        tasks/get until it finishes. `progress` hears how long it has waited."""
        budget = scenario_apply_timeout() if timeout is None else float(timeout)
        started = clock()
        deadline = started + budget
        arguments: dict[str, Any] = {"id": scenario, "project": project or self.project}
        if replace:
            arguments["replace"] = True
        params = {
            "name": "localcloud_apply_scenario",
            "arguments": arguments,
            "task": {"ttl": int(budget * 1000)},
        }

        def report(state: str) -> None:
            if progress is not None:
                progress(
                    f"Applying scenario {scenario}: {state}, "
                    f"{clock() - started:.0f}s of up to {budget:.0f}s"
                )

        def timed_out(cause: str) -> HostError:
            return HostError(
                "scenario_apply_timeout",
                (
                    f"Scenario {scenario} did not finish applying within {budget:.0f} seconds. "
                    "A first apply can pull a Dataproc image, which takes minutes; the runtime "
                    "may still be applying it. Set "
                    f"{SCENARIO_APPLY_TIMEOUT_ENV} to a larger number of seconds to wait longer."
                ),
                {"scenario": scenario, "timeout_seconds": budget, "cause": cause},
            )

        finished = threading.Event()

        def ticker() -> None:
            while not finished.wait(_PROGRESS_INTERVAL):
                report("waiting for the runtime")

        if progress is not None:
            threading.Thread(target=ticker, daemon=True).start()
        try:
            result = self.rpc(
                "tools/call", params, timeout=httpx.Timeout(budget, connect=5.0)
            )
        except HostError as error:
            if isinstance(error.__cause__, httpx.TimeoutException):
                raise timed_out(str(error.__cause__)) from error
            raise
        finally:
            finished.set()
        task = result.get("task") if isinstance(result, dict) else None
        if isinstance(task, dict) and "content" not in result:
            task_id = task.get("taskId")
            status = task.get("status")
            while status not in _TASK_DONE:
                if status == "input_required":
                    raise HostError(
                        "scenario_apply_input_required",
                        f"Scenario {scenario}'s apply task asks for input this client cannot give",
                        {"scenario": scenario, "task": task},
                    )
                if clock() >= deadline:
                    raise timed_out(f"task {task_id} is still {status}")
                report(f"task {status}")
                sleep(_TASK_POLL_INTERVAL)
                task = self.rpc("tasks/get", {"taskId": task_id})
                status = task.get("status") if isinstance(task, dict) else None
            remaining = max(deadline - clock(), self.timeout)
            result = self.rpc(
                "tasks/result",
                {"taskId": task_id},
                timeout=httpx.Timeout(remaining, connect=5.0),
            )
        report("done")
        return self._tool_result("localcloud_apply_scenario", result)

    def _project_api(
        self, method: str, payload: dict[str, Any] | None = None
    ) -> Any:
        url = f"{self.url}/projects"
        headers = self._headers()
        headers["Accept"] = "application/json"
        request_args: dict[str, Any] = {
            "headers": headers,
            "timeout": self.timeout,
        }
        if payload is not None:
            request_args["json"] = payload
        try:
            response = self._http_request(method, url, **request_args)
            response.raise_for_status()
            return response.json()
        except Exception as error:
            raise _transport_error(
                "java_project_api_unavailable",
                "Java LocalCloud project API request failed",
                error,
                url=url,
                method=method,
            ) from error

    def list_projects(self) -> list[dict[str, Any]]:
        projects = self._project_api("GET")
        if not isinstance(projects, list) or not all(
            isinstance(project, dict) for project in projects
        ):
            raise HostError(
                "java_mcp_invalid_response",
                "Java LocalCloud returned an invalid project catalog",
            )
        return projects

    def project_exists(self) -> bool:
        return any(
            project.get("project_id") == self.project
            for project in self.list_projects()
        )

    def create_project(self) -> dict[str, Any]:
        result = self._project_api("POST", {"project_id": self.project})
        return self._project_result("localcloud_create_project", result)

    def environment(self, output_format: str) -> Any:
        url = f"{self.url}/env"
        headers = self._headers()
        headers["Accept"] = (
            "application/json" if output_format == "json" else "text/plain"
        )
        try:
            response = self._http_request(
                "GET",
                url,
                params={"format": output_format, "project": self.project},
                headers=headers,
                timeout=self.timeout,
            )
            response.raise_for_status()
            return response.json() if output_format == "json" else response.text
        except Exception as error:
            raise _transport_error(
                "java_env_api_unavailable",
                "Java LocalCloud environment API request failed",
                error,
                url=url,
                method="GET",
            ) from error

    def _identity_api(
        self,
        method: str,
        path: str,
        payload: dict[str, Any] | None = None,
        *,
        timeout: float | None = None,
    ) -> dict[str, Any]:
        """Call LocalCloud's /identity/sessions API as this project and user."""
        url = f"{self.url}{path}"
        headers = self._headers()
        headers["Accept"] = "application/json"
        request_args: dict[str, Any] = {
            "headers": headers,
            "timeout": self.timeout if timeout is None else timeout,
        }
        if payload is not None:
            request_args["json"] = payload
        try:
            response = self._http_request(method, url, **request_args)
        except Exception as error:
            raise _transport_error(
                "java_identity_api_unavailable",
                "LocalCloud identity session API request failed",
                error,
                url=url,
                method=method,
            ) from error
        body: Any = None
        try:
            body = response.json()
        except Exception:
            body = None
        if response.status_code >= 400:
            message = None
            if isinstance(body, dict) and isinstance(body.get("error"), dict):
                message = body["error"].get("message")
            if response.status_code in {404, 405} and message is None:
                raise HostError(
                    "identity_sessions_unsupported",
                    "This LocalCloud image does not provide identity sessions; "
                    "update the image and restart LocalCloud",
                    {"url": url, "status_code": response.status_code},
                )
            raise HostError(
                "identity_session_failed",
                str(message or f"LocalCloud returned HTTP {response.status_code}"),
                {
                    "url": url,
                    "method": method,
                    "status_code": response.status_code,
                    "retryable": response.status_code in {408, 429}
                    or response.status_code >= 500,
                },
            )
        if not isinstance(body, dict):
            raise HostError(
                "java_mcp_invalid_response",
                "LocalCloud returned an invalid identity session response",
                {"url": url, "method": method},
            )
        return body

    def create_identity_session(self, service_account: str | None = None) -> dict[str, Any]:
        """Create a 12-hour host relay session; the result holds its capability once."""
        payload: dict[str, Any] = {"projectId": self.project}
        if service_account:
            payload["serviceAccount"] = service_account
        return self._identity_api("POST", "/identity/sessions", payload)

    def identity_session(
        self, session_id: str, *, timeout: float | None = None
    ) -> dict[str, Any]:
        return self._identity_api(
            "GET", f"/identity/sessions/{quote(session_id, safe='')}", timeout=timeout
        )

    def delete_identity_session(
        self, session_id: str, *, timeout: float | None = None
    ) -> dict[str, Any]:
        return self._identity_api(
            "DELETE", f"/identity/sessions/{quote(session_id, safe='')}", timeout=timeout
        )

    def reset_project(self) -> dict[str, Any]:
        """Clear the project's service data; samples are reloaded from the Console.

        Uses the server's reset API, as the Console does. The MCP reset tool is
        gated behind LOCALCLOUD_MCP_DESTRUCTIVE to keep agents from resetting
        data, which a person running `lc reset` should not have to enable.
        """
        url = f"{self.url}/reset"
        headers = self._headers()
        headers["Accept"] = "application/json"
        try:
            response = self._http_request(
                "POST",
                url,
                params={"project": self.project},
                json={"restore_seed": False},
                headers=headers,
                timeout=self.timeout,
            )
            response.raise_for_status()
            report = response.json()
        except Exception as error:
            raise _transport_error(
                "java_reset_api_unavailable",
                "Java LocalCloud reset API request failed",
                error,
                url=url,
                method="POST",
            ) from error
        if not isinstance(report, dict):
            raise HostError(
                "java_mcp_invalid_response",
                "Java LocalCloud returned an invalid reset report",
                {"project": self.project},
            )
        if report.get("status") != "success":
            failures = report.get("failures") or []
            raise HostError(
                "project_reset_incomplete",
                "Some services could not be reset",
                {
                    "project": self.project,
                    "status": report.get("status"),
                    "cause": "; ".join(
                        f"{item.get('service')}: {item.get('error')}"
                        for item in failures
                        if isinstance(item, dict)
                    ),
                    "failures": failures,
                },
            )
        return report

    def _project_result(self, tool: str, result: Any) -> dict[str, Any]:
        if not isinstance(result, dict):
            raise HostError(
                "java_mcp_invalid_response",
                "Java LocalCloud MCP returned an invalid project result",
                {"tool": tool, "project": self.project},
            )
        return result
