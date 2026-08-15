from __future__ import annotations

from typing import Any

import pytest

import localcloud_cli.java_client as java_client_module
from localcloud_cli.errors import HostError
from localcloud_cli.java_client import JavaMcpClient

PROJECT = "agent-project"
USER = "integration-agent"


class FakeResponse:
    def __init__(
        self,
        payload: Any,
        *,
        status_code: int = 200,
        content: bytes = b"{}",
    ):
        self.payload = payload
        self.status_code = status_code
        self.content = content

    def raise_for_status(self) -> None:
        return None

    def json(self) -> Any:
        return self.payload


def test_seed_project_calls_authoritative_java_tool(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = JavaMcpClient("http://127.0.0.1:49080", PROJECT, USER)
    calls: list[tuple[str, dict[str, Any]]] = []
    monkeypatch.setattr(
        client,
        "tool",
        lambda name, arguments: calls.append((name, arguments))
        or {"status": "seeded"},
    )

    assert client.seed_project("services: {}") == {"status": "seeded"}
    assert client.seed_project(
        "services: {pubsub: {topics: []}}", volatile_only=True
    ) == {"status": "seeded"}
    assert calls == [
        (
            "localcloud_seed_project",
            {"yaml": "services: {}", "volatileOnly": False},
        ),
        (
            "localcloud_seed_project",
            {
                "yaml": "services: {pubsub: {topics: []}}",
                "volatileOnly": True,
            },
        ),
    ]

def test_rpc_transport_sends_selected_project_and_caller_headers(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[dict[str, Any]] = []

    def post(url: str, **kwargs: Any) -> FakeResponse:
        calls.append({"url": url, **kwargs})
        return FakeResponse({"result": {"projects": []}})

    monkeypatch.setattr(java_client_module.httpx, "post", post)

    result = JavaMcpClient(
        "http://127.0.0.1:49080", PROJECT, USER
    ).rpc("tools/list")

    assert result == {"projects": []}
    assert calls[0]["headers"]["X-LocalCloud-Project"] == PROJECT
    assert calls[0]["headers"]["X-LocalCloud-User"] == USER


@pytest.mark.parametrize("operation", ["rpc", "forward"])
def test_mcp_transports_reject_non_object_responses(
    monkeypatch: pytest.MonkeyPatch,
    operation: str,
) -> None:
    monkeypatch.setattr(
        java_client_module.httpx,
        "post",
        lambda *_args, **_kwargs: FakeResponse([]),
    )
    client = JavaMcpClient("http://127.0.0.1:49080", PROJECT, USER)

    with pytest.raises(HostError) as caught:
        if operation == "rpc":
            client.rpc("tools/list")
        else:
            client.forward({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})

    assert caught.value.code == "java_mcp_invalid_response"
    assert caught.value.details["method"] == "tools/list"


def test_mcp_transport_reports_malformed_json_as_invalid_response(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    response = FakeResponse(None)

    def invalid_json() -> Any:
        raise ValueError("invalid JSON")

    response.json = invalid_json
    monkeypatch.setattr(
        java_client_module.httpx,
        "post",
        lambda *_args, **_kwargs: response,
    )

    with pytest.raises(HostError) as caught:
        JavaMcpClient("http://127.0.0.1:49080", PROJECT, USER).rpc("tools/list")

    assert caught.value.code == "java_mcp_invalid_response"
    assert caught.value.details["method"] == "tools/list"


def test_project_catalog_and_selected_project_existence(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = JavaMcpClient("http://127.0.0.1:49080", PROJECT, USER)
    projects = [
        {"project_id": "another-project"},
        {"project_id": PROJECT},
    ]
    calls: list[str] = []
    monkeypatch.setattr(
        client,
        "tool",
        lambda name: calls.append(name) or projects,
    )

    assert client.list_projects() == projects
    assert client.project_exists() is True
    assert calls == ["localcloud_list_projects", "localcloud_list_projects"]


def test_create_and_reset_project_use_selected_project(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = JavaMcpClient("http://127.0.0.1:49080", PROJECT, USER)
    calls: list[tuple[str, dict[str, Any]]] = []

    def tool(name: str, arguments: dict[str, Any]) -> dict[str, Any]:
        calls.append((name, arguments))
        return {"project_id": PROJECT, "operation": name}

    monkeypatch.setattr(client, "tool", tool)

    assert client.create_project()["project_id"] == PROJECT
    assert client.reset_project()["project_id"] == PROJECT
    assert calls == [
        ("localcloud_create_project", {"project": PROJECT}),
        ("localcloud_reset_project", {"project": PROJECT}),
    ]


def test_project_lifecycle_falls_back_to_rest_when_mcp_tools_are_missing(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = JavaMcpClient("http://127.0.0.1:49080", PROJECT, USER)
    projects = [{"project_id": PROJECT}]
    calls: list[dict[str, Any]] = []

    def missing_tool(
        name: str, arguments: dict[str, Any] | None = None
    ) -> Any:
        raise HostError("java_tool_error", "Tool not found", {"tool": name})

    def request(method: str, url: str, **kwargs: Any) -> FakeResponse:
        calls.append({"method": method, "url": url, **kwargs})
        payload = projects if method == "GET" else projects[0]
        return FakeResponse(payload)

    monkeypatch.setattr(client, "tool", missing_tool)
    monkeypatch.setattr(java_client_module.httpx, "request", request)

    assert client.list_projects() == projects
    assert client.create_project() == projects[0]
    assert calls == [
        {
            "method": "GET",
            "url": "http://127.0.0.1:49080/projects",
            "headers": {
                "Accept": "application/json",
                "X-LocalCloud-Project": PROJECT,
                "X-LocalCloud-User": USER,
            },
            "timeout": 60.0,
        },
        {
            "method": "POST",
            "url": "http://127.0.0.1:49080/projects",
            "headers": {
                "Accept": "application/json",
                "X-LocalCloud-Project": PROJECT,
                "X-LocalCloud-User": USER,
            },
            "timeout": 60.0,
            "json": {"project_id": PROJECT},
        },
    ]


def test_project_lifecycle_does_not_bypass_mcp_write_gate(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = JavaMcpClient("http://127.0.0.1:49080", PROJECT, USER)
    write_error = HostError(
        "java_tool_error",
        "Tool localcloud_create_project requires LOCALCLOUD_MCP_WRITE=true",
        {"tool": "localcloud_create_project"},
    )

    def blocked_tool(name: str, arguments: dict[str, Any]) -> Any:
        raise write_error

    monkeypatch.setattr(client, "tool", blocked_tool)
    monkeypatch.setattr(
        java_client_module.httpx,
        "request",
        lambda *_args, **_kwargs: pytest.fail("REST fallback must not run"),
    )

    with pytest.raises(HostError) as caught:
        client.create_project()

    assert caught.value is write_error


def test_forward_preserves_json_rpc_envelope_and_identity_headers(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    request = {
        "jsonrpc": "2.0",
        "id": 41,
        "method": "tasks/list",
        "params": {"cursor": "next"},
    }
    response_payload = {
        "jsonrpc": "2.0",
        "id": 41,
        "result": {"tasks": []},
    }
    calls: list[dict[str, Any]] = []

    class ForwardResponse:
        status_code = 200
        content = b"{}"

        def raise_for_status(self) -> None:
            return None

        def json(self) -> dict[str, Any]:
            return response_payload

    def post(url: str, **kwargs: Any) -> ForwardResponse:
        calls.append({"url": url, **kwargs})
        return ForwardResponse()

    monkeypatch.setattr(java_client_module.httpx, "post", post)

    result = JavaMcpClient(
        "http://127.0.0.1:49080", PROJECT, USER
    ).forward(request)

    assert result == response_payload
    assert calls[0]["json"] is request
    assert calls[0]["headers"]["X-LocalCloud-Project"] == PROJECT
    assert calls[0]["headers"]["X-LocalCloud-User"] == USER



def test_default_client_transport_uses_shared_project_and_caller(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[dict[str, Any]] = []

    class AcceptedResponse:
        status_code = 202
        content = b""

        def raise_for_status(self) -> None:
            return None

    def post(url: str, **kwargs: Any) -> AcceptedResponse:
        calls.append({"url": url, **kwargs})
        return AcceptedResponse()

    monkeypatch.setattr(java_client_module.httpx, "post", post)

    result = JavaMcpClient("http://127.0.0.1:49080").forward(
        {"jsonrpc": "2.0", "method": "notifications/initialized"}
    )

    assert result is None
    assert calls[0]["headers"]["X-LocalCloud-Project"] == "local-gcp-project"
    assert calls[0]["headers"]["X-LocalCloud-User"] == "local-developer"