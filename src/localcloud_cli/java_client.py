from __future__ import annotations

import itertools
import re
import socket
from collections.abc import Mapping
from typing import Any
from urllib.parse import urlparse

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

    def request(self, method: str, params: dict[str, Any] | None = None) -> Any:
        return self.rpc(method, params)

    def forward(self, message: dict[str, Any]) -> dict[str, Any] | None:
        headers = self._headers()
        try:
            response = httpx.post(
                f"{self.url}/mcp",
                json=message,
                headers=headers,
                timeout=self.timeout,
            )
            response.raise_for_status()
            if response.status_code == 202 or not response.content:
                return None
            body = response.json()
        except Exception as error:
            raise HostError(
                "java_mcp_unavailable",
                "Java LocalCloud MCP request failed",
                {
                    "url": self.url,
                    "method": message.get("method"),
                    "cause": str(error),
                },
            ) from error
        if not isinstance(body, dict):
            raise HostError(
                "java_mcp_invalid_response",
                "Java LocalCloud MCP returned a non-object response",
                {"method": message.get("method")},
            )
        return body

    def rpc(self, method: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        headers = self._headers()
        payload = {
            "jsonrpc": "2.0",
            "id": next(self._ids),
            "method": method,
            "params": params or {},
        }
        try:
            response = httpx.post(
                f"{self.url}/mcp", json=payload, headers=headers, timeout=self.timeout
            )
            response.raise_for_status()
            body = response.json()
        except Exception as error:
            raise HostError(
                "java_mcp_unavailable", "Java LocalCloud MCP request failed",
                {"url": self.url, "method": method, "cause": str(error)},
            ) from error
        if "error" in body:
            raise HostError(
                "java_mcp_error", body["error"].get("message", "Java MCP error"),
                {"method": method, "error": body["error"]},
            )
        return body["result"]

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


    def apply_scenario(self, scenario: str, project: str) -> Any:
        return self.tool("localcloud_apply_scenario", {"id": scenario, "project": project})


    def checkpoint_project(self, project: str, name: str) -> Any:
        return self.tool("localcloud_checkpoint_project", {"project": project, "name": name})

    def verify_scenario(
        self,
        scenario: str,
        project: str,
        endpoint_map: dict[str, Any],
    ) -> dict[str, Any]:
        definition = self.tool("localcloud_get_scenario", {"id": scenario})
        if not isinstance(definition, dict):
            raise _verification_error(
                scenario, "scenario-catalog", "scenario definition is missing"
            )

        services = definition.get("services")
        if not isinstance(services, (list, tuple, set)):
            raise _verification_error(
                scenario, "scenario-catalog", "scenario services are malformed"
            )
        verified_services: list[str] = []
        for service_value in services:
            service = str(service_value)
            inventory_id = f"inventory-{service}"
            try:
                inventory = self.tool(
                    "localcloud_browse_resources",
                    {"service": service, "project": project},
                )
            except Exception as error:
                raise _verification_error(
                    scenario,
                    inventory_id,
                    "authoritative inventory browse failed",
                    service=service,
                    cause=str(error),
                ) from error
            if isinstance(inventory, Mapping) and inventory.get("error") is True:
                raise _verification_error(
                    scenario,
                    inventory_id,
                    "authoritative inventory browse returned an error",
                    service=service,
                    inventory=dict(inventory),
                )
            verified_services.append(service)

        declarations = definition.get("verification")
        if not isinstance(declarations, list) or not declarations:
            raise _verification_error(
                scenario, "scenario-catalog", "scenario has no concrete verification entries"
            )

        normalized_endpoints: dict[str, int] = {}
        try:
            normalized_endpoints = {
                str(port): int(host_port) for port, host_port in endpoint_map.items()
            }
        except (AttributeError, TypeError, ValueError) as error:
            raise _verification_error(
                scenario, "scenario-catalog", "environment endpoint map is malformed"
            ) from error

        verified: list[str] = []
        data_plane_host = urlparse(self.url).hostname or "127.0.0.1"
        http_host = (
            f"[{data_plane_host}]" if ":" in data_plane_host else data_plane_host
        )
        headers = {
            "X-LocalCloud-Project": project,
            "X-LocalCloud-User": self.user,
            "X-Goog-User-Project": project,
        }
        with httpx.Client(timeout=self.timeout, headers=headers) as client:
            for raw in declarations:
                verification = _verification_declaration(scenario, raw)
                verification_id = verification["id"]
                canonical_port = str(verification["port"])
                host_port = normalized_endpoints.get(canonical_port)
                if host_port is None or host_port <= 0:
                    raise _verification_error(
                        scenario,
                        verification_id,
                        "resolved host port is missing",
                        canonical_port=int(canonical_port),
                    )
                verification_type = verification["type"]
                if verification_type == "http_json":
                    path = _render_project(
                        scenario,
                        verification_id,
                        verification["path"],
                        project,
                    )
                    if not path.startswith("/") or path.startswith("//"):
                        raise _verification_error(
                            scenario, verification_id, "HTTP path is not relative"
                        )
                    url = f"http://{http_host}:{host_port}{path}"
                    try:
                        response = client.get(url)
                        response.raise_for_status()
                        payload = response.json()
                    except Exception as error:
                        raise _verification_error(
                            scenario,
                            verification_id,
                            "HTTP JSON resource check failed",
                            url=url,
                            cause=str(error),
                        ) from error
                    if not payload:
                        raise _verification_error(
                            scenario,
                            verification_id,
                            "HTTP JSON resource response is empty",
                            url=url,
                        )
                    _assert_expectation(
                        scenario,
                        verification_id,
                        payload,
                        verification["expect"],
                        project,
                    )
                elif verification_type == "tcp":
                    try:
                        with socket.create_connection(
                            (data_plane_host, host_port), timeout=self.timeout
                        ):
                            pass
                    except OSError as error:
                        raise _verification_error(
                            scenario,
                            verification_id,
                            "TCP data plane is unreachable",
                            host=data_plane_host,
                            port=host_port,
                            cause=str(error),
                        ) from error
                else:
                    raise _verification_error(
                        scenario,
                        verification_id,
                        f"unsupported verification type {verification_type}",
                    )
                verified.append(verification_id)
        return {
            "scenario": scenario,
            "verified_services": verified_services,
            "verified": verified,
        }


_PLACEHOLDER = re.compile(r"\$\{([^}]+)}")
_MISSING = object()


def _verification_declaration(
    scenario: str, raw: Any
) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise _verification_error(
            scenario, "scenario-catalog", "verification entry is malformed"
        )
    verification_id = raw.get("id")
    if not isinstance(verification_id, str) or not verification_id:
        raise _verification_error(
            scenario, "scenario-catalog", "verification id is missing"
        )
    try:
        port = int(raw["port"])
    except (KeyError, TypeError, ValueError) as error:
        raise _verification_error(
            scenario, verification_id, "verification port is malformed"
        ) from error
    verification_type = raw.get("type")
    if verification_type not in {"http_json", "tcp"}:
        raise _verification_error(
            scenario,
            verification_id,
            f"unsupported verification type {verification_type}",
        )
    path = raw.get("path", "")
    expect = raw.get("expect", {})
    if not isinstance(path, str) or not isinstance(expect, dict):
        raise _verification_error(
            scenario, verification_id, "verification declaration is malformed"
        )
    if verification_type == "http_json" and (not path or not expect):
        raise _verification_error(
            scenario, verification_id, "HTTP JSON verification is incomplete"
        )
    if verification_type == "tcp" and (path or expect):
        raise _verification_error(
            scenario, verification_id, "TCP verification cannot declare HTTP fields"
        )
    return {
        "id": verification_id,
        "type": verification_type,
        "port": port,
        "path": path,
        "expect": expect,
    }


def _render_project(
    scenario: str,
    verification_id: str,
    value: str,
    project: str,
) -> str:
    placeholders = set(_PLACEHOLDER.findall(value))
    if placeholders - {"project"}:
        raise _verification_error(
            scenario,
            verification_id,
            "verification contains an unsupported placeholder",
            placeholders=sorted(placeholders),
        )
    return value.replace("${project}", project)


def _assert_expectation(
    scenario: str,
    verification_id: str,
    payload: Any,
    expectation: dict[str, Any],
    project: str,
) -> None:
    json_path_value = expectation.get("json_path")
    if not isinstance(json_path_value, str) or not json_path_value:
        raise _verification_error(
            scenario, verification_id, "verification expectation is malformed"
        )
    json_path = _render_project(
        scenario, verification_id, json_path_value, project
    )
    actual = _json_path(payload, json_path)
    if actual is _MISSING:
        raise _verification_error(
            scenario,
            verification_id,
            f"expected JSON path {json_path} is missing",
        )
    if "equals" in expectation:
        expected = expectation["equals"]
        if isinstance(expected, str):
            expected = _render_project(
                scenario, verification_id, expected, project
            )
        if actual != expected:
            raise _verification_error(
                scenario,
                verification_id,
                f"JSON path {json_path} has the wrong value",
                expected=expected,
                actual=actual,
            )
    elif expectation.get("non_empty") is True:
        if not actual:
            raise _verification_error(
                scenario,
                verification_id,
                f"JSON path {json_path} is empty",
            )
    else:
        raise _verification_error(
            scenario, verification_id, "verification expectation is malformed"
        )


def _json_path(payload: Any, path: str) -> Any:
    value = payload
    for segment in path.split("."):
        if not isinstance(value, Mapping) or segment not in value:
            return _MISSING
        value = value[segment]
    return value


def _verification_error(
    scenario: str,
    verification_id: str,
    reason: str,
    **details: Any,
) -> HostError:
    return HostError(
        "scenario_verification_failed",
        f"Scenario {scenario} verification {verification_id} failed: {reason}",
        {
            "scenario": scenario,
            "verification_id": verification_id,
            **details,
        },
    )