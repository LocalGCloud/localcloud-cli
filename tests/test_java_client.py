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
        "connect_failed": False,
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
    assert caught.value.details["connect_failed"] is True
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


def test_create_and_reset_project_use_lifecycle_apis_not_mcp_tools(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = JavaMcpClient("http://127.0.0.1:49080", PROJECT, USER)
    api_calls: list[tuple[str, str, dict[str, Any]]] = []
    reports = [
        {"project_id": PROJECT},
        {"status": "success", "project": PROJECT, "seed_restored": False},
    ]

    def tool(name: str, arguments: dict[str, Any]) -> None:
        pytest.fail(f"lifecycle call used MCP tool {name}")

    def request(method: str, url: str, **kwargs: Any) -> FakeResponse:
        api_calls.append((method, url, kwargs))
        return FakeResponse(reports.pop(0))

    monkeypatch.setattr(client, "tool", tool)
    monkeypatch.setattr(java_client_module.httpx, "request", request)

    assert client.create_project()["project_id"] == PROJECT
    assert client.reset_project()["status"] == "success"
    assert api_calls[0][0:2] == (
        "POST",
        "http://127.0.0.1:49080/projects",
    )
    assert api_calls[0][2]["json"] == {"project_id": PROJECT}
    # The Console's reset API: no LOCALCLOUD_MCP_DESTRUCTIVE gate.
    assert api_calls[1][0:2] == ("POST", "http://127.0.0.1:49080/reset")
    assert api_calls[1][2]["params"] == {"project": PROJECT}
    assert api_calls[1][2]["json"] == {"restore_seed": False}


def test_partial_project_reset_reports_each_failed_service(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = JavaMcpClient("http://127.0.0.1:49080", PROJECT, USER)
    report = {
        "status": "partial_failure",
        "failures": [{"service": "dataproc", "error": "cluster busy"}],
    }
    monkeypatch.setattr(
        java_client_module.httpx,
        "request",
        lambda *_args, **_kwargs: FakeResponse(report),
    )

    with pytest.raises(HostError) as caught:
        client.reset_project()

    assert caught.value.code == "project_reset_incomplete"
    assert caught.value.details["cause"] == "dataproc: cluster busy"


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


def test_identity_session_api_calls_carry_project_and_caller(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[dict[str, Any]] = []

    def request(method: str, url: str, **kwargs: Any) -> FakeResponse:
        calls.append({"method": method, "url": url, **kwargs})
        return FakeResponse({"id": "wib-1", "state": "PENDING"})

    monkeypatch.setattr(java_client_module.httpx, "request", request)
    client = JavaMcpClient("http://127.0.0.1:49080", PROJECT, USER)

    assert client.create_identity_session("sa@agent-project.iam.gserviceaccount.com")["id"] == "wib-1"
    client.identity_session("wib-1")
    client.delete_identity_session("wib/../x")

    assert [(call["method"], call["url"]) for call in calls] == [
        ("POST", "http://127.0.0.1:49080/identity/sessions"),
        ("GET", "http://127.0.0.1:49080/identity/sessions/wib-1"),
        ("DELETE", "http://127.0.0.1:49080/identity/sessions/wib%2F..%2Fx"),
    ]
    assert calls[0]["json"] == {
        "projectId": PROJECT,
        "serviceAccount": "sa@agent-project.iam.gserviceaccount.com",
    }
    assert all(call["headers"]["X-LocalCloud-Project"] == PROJECT for call in calls)
    assert all(call["headers"]["X-LocalCloud-User"] == USER for call in calls)


@pytest.mark.parametrize(
    ("status", "payload", "code", "message"),
    [
        (404, None, "identity_sessions_unsupported", "does not provide identity sessions"),
        (403, {"error": {"code": 403, "message": "Permission 'iam.serviceAccounts.actAs' denied"}},
         "identity_session_failed", "iam.serviceAccounts.actAs"),
        (404, {"error": {"code": 404, "message": "Service account x was not found"}},
         "identity_session_failed", "was not found"),
    ],
)
def test_identity_session_api_errors_are_specific(
    monkeypatch: pytest.MonkeyPatch, status: int, payload: Any, code: str, message: str
) -> None:
    class Failed(FakeResponse):
        def json(self) -> Any:
            if self.payload is None:
                raise ValueError("not JSON")
            return self.payload

    monkeypatch.setattr(
        java_client_module.httpx, "request",
        lambda method, url, **kwargs: Failed(payload, status_code=status),
    )
    with pytest.raises(HostError) as caught:
        JavaMcpClient("http://127.0.0.1:49080", PROJECT, USER).create_identity_session()
    assert caught.value.code == code
    assert message in caught.value.message


def test_localcloud_calls_never_go_through_an_environment_proxy(monkeypatch: pytest.MonkeyPatch) -> None:
    """A developer's HTTP(S)_PROXY cannot reach their loopback LocalCloud and must not see its calls."""
    import identity_fixtures
    from recording_http import recording_server

    def gateway(method: str, target: str, headers: dict[str, str]) -> tuple[int, Any]:
        return 200, identity_fixtures.active_session()

    with recording_server() as (proxy_port, proxied), recording_server(gateway) as (gateway_port, served):
        for variable in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy"):
            monkeypatch.setenv(variable, f"http://127.0.0.1:{proxy_port}")
        monkeypatch.delenv("NO_PROXY", raising=False)
        monkeypatch.delenv("no_proxy", raising=False)
        # A client created now reads the proxy variables, as a fresh `lc` process would.
        monkeypatch.setattr(java_client_module, "_SHARED_CLIENT", None)
        session = JavaMcpClient(f"http://127.0.0.1:{gateway_port}", PROJECT, USER).identity_session(
            "wib-0123456789abcdef01234567"
        )
        java_client_module.get_shared_http_client().close()

    assert session["state"] == "ACTIVE"
    assert [hit["target"] for hit in served] == ["/identity/sessions/wib-0123456789abcdef01234567"]
    assert proxied == [], "the proxy saw a LocalCloud call"
