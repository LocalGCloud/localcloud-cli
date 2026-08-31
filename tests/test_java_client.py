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



def test_tool_error_with_empty_content_list_raises_clean_host_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def post(url: str, **kwargs: Any) -> FakeResponse:
        return FakeResponse(
            {"result": {"isError": True, "content": []}}
        )

    monkeypatch.setattr(java_client_module.httpx, "post", post)

    client = JavaMcpClient("http://127.0.0.1:49080", PROJECT, USER)
    with pytest.raises(HostError) as caught:
        client.tool("localcloud_seed_project", {})

    assert caught.value.code == "java_tool_error"
    assert caught.value.message == "Java MCP tool failed"


@pytest.mark.parametrize(
    ("status_code", "retryable"),
    [(403, False), (408, True), (429, True), (503, True)],
)
def test_mcp_transport_preserves_http_failure_details(
    monkeypatch: pytest.MonkeyPatch,
    status_code: int,
    retryable: bool,
) -> None:
    url = "http://127.0.0.1:49080/mcp"
    request = java_client_module.httpx.Request("POST", url)
    response = java_client_module.httpx.Response(
        status_code, request=request
    )

    def post(*_args: Any, **_kwargs: Any) -> FakeResponse:
        raise java_client_module.httpx.HTTPStatusError(
            f"HTTP {status_code}",
            request=request,
            response=response,
        )

    monkeypatch.setattr(java_client_module.httpx, "post", post)

    with pytest.raises(HostError) as caught:
        JavaMcpClient(
            "http://127.0.0.1:49080", PROJECT, USER
        ).rpc("tools/list")

    assert caught.value.code == "java_mcp_unavailable"
    assert caught.value.details == {
        "url": url,
        "method": "tools/list",
        "cause": f"HTTP {status_code}",
        "retryable": retryable,
        "status_code": status_code,
    }
    assert (
        java_client_module.is_retryable_java_error(caught.value)
        is retryable
    )


def test_mcp_transport_marks_connection_failure_retryable(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    url = "http://127.0.0.1:49080/mcp"
    request = java_client_module.httpx.Request("POST", url)

    def post(*_args: Any, **_kwargs: Any) -> FakeResponse:
        raise java_client_module.httpx.ConnectError(
            "connection refused", request=request
        )

    monkeypatch.setattr(java_client_module.httpx, "post", post)

    with pytest.raises(HostError) as caught:
        JavaMcpClient(
            "http://127.0.0.1:49080", PROJECT, USER
        ).rpc("tools/list")

    assert caught.value.details["retryable"] is True
    assert "status_code" not in caught.value.details


def test_mcp_transport_marks_remote_disconnect_retryable(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    url = "http://127.0.0.1:49080/mcp"
    request = java_client_module.httpx.Request("POST", url)

    def post(*_args: Any, **_kwargs: Any) -> FakeResponse:
        raise java_client_module.httpx.RemoteProtocolError(
            "server disconnected",
            request=request,
        )

    monkeypatch.setattr(java_client_module.httpx, "post", post)

    with pytest.raises(HostError) as caught:
        JavaMcpClient(
            "http://127.0.0.1:49080", PROJECT, USER
        ).rpc("tools/list")

    assert caught.value.details["retryable"] is True


def test_project_api_transport_preserves_http_failure_details(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    url = "http://127.0.0.1:49080/projects"
    request = java_client_module.httpx.Request("GET", url)
    response = java_client_module.httpx.Response(503, request=request)

    def request_call(*_args: Any, **_kwargs: Any) -> FakeResponse:
        raise java_client_module.httpx.HTTPStatusError(
            "HTTP 503", request=request, response=response
        )

    monkeypatch.setattr(
        java_client_module.httpx, "request", request_call
    )

    with pytest.raises(HostError) as caught:
        JavaMcpClient(
            "http://127.0.0.1:49080", PROJECT, USER
        )._project_api("GET")

    assert caught.value.code == "java_project_api_unavailable"
    assert caught.value.details["url"] == url
    assert caught.value.details["method"] == "GET"
    assert caught.value.details["status_code"] == 503
    assert caught.value.details["retryable"] is True

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

    def request(method: str, url: str, **_kwargs: Any) -> FakeResponse:
        calls.append(f"{method} {url}")
        return FakeResponse(projects)

    monkeypatch.setattr(java_client_module.httpx, "request", request)

    assert client.list_projects() == projects
    assert client.project_exists() is True
    assert calls == [
        "GET http://127.0.0.1:49080/projects",
        "GET http://127.0.0.1:49080/projects",
    ]


def test_create_project_uses_lifecycle_api_and_reset_remains_an_mcp_operation(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = JavaMcpClient("http://127.0.0.1:49080", PROJECT, USER)
    tool_calls: list[tuple[str, dict[str, Any]]] = []
    api_calls: list[tuple[str, str, dict[str, Any]]] = []

    def tool(name: str, arguments: dict[str, Any]) -> dict[str, Any]:
        tool_calls.append((name, arguments))
        return {"project_id": PROJECT, "operation": name}

    def request(method: str, url: str, **kwargs: Any) -> FakeResponse:
        api_calls.append((method, url, kwargs))
        return FakeResponse({"project_id": PROJECT})

    monkeypatch.setattr(client, "tool", tool)
    monkeypatch.setattr(java_client_module.httpx, "request", request)

    assert client.create_project()["project_id"] == PROJECT
    assert client.reset_project()["project_id"] == PROJECT
    assert tool_calls == [
        ("localcloud_reset_project", {"project": PROJECT}),
    ]
    assert api_calls[0][0:2] == (
        "POST",
        "http://127.0.0.1:49080/projects",
    )
    assert api_calls[0][2]["json"] == {"project_id": PROJECT}


def test_project_lifecycle_uses_rest_without_calling_mcp(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = JavaMcpClient("http://127.0.0.1:49080", PROJECT, USER)
    projects = [{"project_id": PROJECT}]
    calls: list[dict[str, Any]] = []

    def request(method: str, url: str, **kwargs: Any) -> FakeResponse:
        calls.append({"method": method, "url": url, **kwargs})
        payload = projects if method == "GET" else projects[0]
        return FakeResponse(payload)

    monkeypatch.setattr(
        client,
        "tool",
        lambda *_args, **_kwargs: pytest.fail("project lifecycle must not call MCP"),
    )
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


def test_project_creation_is_independent_of_mcp_write_gate(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = JavaMcpClient("http://127.0.0.1:49080", PROJECT, USER)
    monkeypatch.setattr(
        client,
        "tool",
        lambda *_args, **_kwargs: pytest.fail("MCP write gate must not be consulted"),
    )
    monkeypatch.setattr(
        java_client_module.httpx,
        "request",
        lambda *_args, **_kwargs: FakeResponse({"project_id": PROJECT}),
    )

    assert client.create_project() == {"project_id": PROJECT}


def test_environment_uses_management_api_without_calling_mcp(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = JavaMcpClient("http://127.0.0.1:49080", PROJECT, USER)
    calls: list[dict[str, Any]] = []

    def get(url: str, **kwargs: Any) -> FakeResponse:
        calls.append({"url": url, **kwargs})
        return FakeResponse({"GOOGLE_CLOUD_PROJECT": PROJECT})

    monkeypatch.setattr(
        client,
        "tool",
        lambda *_args, **_kwargs: pytest.fail("environment lookup must not call MCP"),
    )
    monkeypatch.setattr(java_client_module.httpx, "get", get)

    assert client.environment("json") == {"GOOGLE_CLOUD_PROJECT": PROJECT}
    assert calls == [
        {
            "url": "http://127.0.0.1:49080/env",
            "params": {"format": "json", "project": PROJECT},
            "headers": {
                "Accept": "application/json",
                "X-LocalCloud-Project": PROJECT,
                "X-LocalCloud-User": USER,
            },
            "timeout": 60.0,
        }
    ]


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
