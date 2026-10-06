from __future__ import annotations

import itertools
from typing import Any

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
    }
    if isinstance(status_code, int):
        details["status_code"] = status_code
    return HostError(code, message, details)


_ORIGINAL_HTTPX_GET = httpx.get
_ORIGINAL_HTTPX_POST = httpx.post
_ORIGINAL_HTTPX_REQUEST = httpx.request
_SHARED_CLIENT: httpx.Client | None = None


def get_shared_http_client() -> httpx.Client:
    global _SHARED_CLIENT
    if _SHARED_CLIENT is None or _SHARED_CLIENT.is_closed:
        _SHARED_CLIENT = httpx.Client()
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

    def forward(self, message: dict[str, Any]) -> dict[str, Any] | None:
        return self._post_mcp(message, allow_empty=True)

    def rpc(self, method: str, params: dict[str, Any] | None = None) -> Any:
        payload = {
            "jsonrpc": "2.0",
            "id": next(self._ids),
            "method": method,
            "params": params or {},
        }
        body = self._post_mcp(payload, allow_empty=False)
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
    ) -> dict[str, Any] | None:
        method = str(message.get("method") or "")
        try:
            response = self._http_request(
                "POST",
                f"{self.url}/mcp",
                json=message,
                headers=self._headers(),
                timeout=self.timeout,
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
