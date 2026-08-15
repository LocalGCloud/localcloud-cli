from __future__ import annotations

import itertools
from typing import Any

import httpx

from .config import DEFAULT_PROJECT, DEFAULT_USER
from .errors import HostError


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
            raise HostError(
                "java_mcp_error",
                str(rpc_error.get("message") or "Java MCP error"),
                {"method": method, "error": rpc_error},
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
            response = httpx.post(
                f"{self.url}/mcp",
                json=message,
                headers=self._headers(),
                timeout=self.timeout,
            )
            response.raise_for_status()
        except Exception as error:
            raise HostError(
                "java_mcp_unavailable",
                "Java LocalCloud MCP request failed",
                {"url": self.url, "method": method, "cause": str(error)},
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
            text = result.get("content", [{}])[0].get("text", "Java MCP tool failed")
            raise HostError("java_tool_error", text, {"tool": name})
        structured = result.get("structuredContent")
        if isinstance(structured, dict) and "result" in structured:
            return structured["result"]
        content = result.get("content", [])
        return content[0].get("text") if content else None

    @staticmethod
    def _is_missing_tool(error: HostError, name: str) -> bool:
        if error.message != "Tool not found":
            return False
        if error.details.get("tool") == name:
            return True
        rpc_error = error.details.get("error")
        return isinstance(rpc_error, dict) and rpc_error.get("data") == name

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
            response = httpx.request(method, url, **request_args)
            response.raise_for_status()
            return response.json()
        except Exception as error:
            raise HostError(
                "java_project_api_unavailable",
                "Java LocalCloud project API request failed",
                {"url": url, "method": method, "cause": str(error)},
            ) from error

    def list_projects(self) -> list[dict[str, Any]]:
        try:
            projects = self.tool("localcloud_list_projects")
        except HostError as error:
            if not self._is_missing_tool(error, "localcloud_list_projects"):
                raise
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
        try:
            result = self.tool(
                "localcloud_create_project", {"project": self.project}
            )
        except HostError as error:
            if not self._is_missing_tool(error, "localcloud_create_project"):
                raise
            result = self._project_api("POST", {"project_id": self.project})
        return self._project_result("localcloud_create_project", result)

    def reset_project(self) -> dict[str, Any]:
        return self._project_result(
            "localcloud_reset_project",
            self.tool("localcloud_reset_project", {"project": self.project}),
        )

    def _project_result(self, tool: str, result: Any) -> dict[str, Any]:
        if not isinstance(result, dict):
            raise HostError(
                "java_mcp_invalid_response",
                "Java LocalCloud MCP returned an invalid project result",
                {"tool": tool, "project": self.project},
            )
        return result

    def seed_project(self, yaml: str, *, volatile_only: bool = False) -> Any:
        return self.tool(
            "localcloud_seed_project",
            {"yaml": yaml, "volatileOnly": volatile_only},
        )
