from __future__ import annotations

from contextlib import nullcontext
from pathlib import Path
from typing import Any

import pytest
import yaml

import localcloud_cli.java_client as java_client_module
from localcloud_cli.errors import HostError
from localcloud_cli.java_client import JavaMcpClient


PROJECT = "agent-project"
USER = "integration-agent"
SCENARIO_ROOT = (
    Path(__file__).parents[2]
    / "localcloud-server"
    / "src"
    / "main"
    / "resources"
    / "agent-scenarios"
)
SCENARIO_SERVICES = {
    "analytics-smoke": ["gcs", "bigquery", "logging", "monitoring"],
    "olap-application": [
        "gcs",
        "bigquery",
        "logging",
        "monitoring",
        "cloudsql",
        "pubsub",
        "cloudfunctions",
        "cloudscheduler",
        "dataproc",
    ],
}


class FakeResponse:
    def __init__(self, payload: Any):
        self.payload = payload

    def raise_for_status(self) -> None:
        return None

    def json(self) -> Any:
        return self.payload


class FakeHttpClient:
    responses: dict[str, Any] = {}
    calls: list[str] = []

    def __init__(self, **_: Any):
        pass

    def __enter__(self) -> FakeHttpClient:
        return self

    def __exit__(self, *_: Any) -> None:
        return None

    def get(self, url: str) -> FakeResponse:
        self.calls.append(url)
        return FakeResponse(self.responses[url])


def _scenario_definition(scenario: str) -> dict[str, Any]:
    definition = yaml.safe_load((SCENARIO_ROOT / scenario / "scenario.yaml").read_text())
    definition["services"] = SCENARIO_SERVICES[scenario]
    return definition


def _render(value: Any) -> Any:
    return value.replace("${project}", PROJECT) if isinstance(value, str) else value


def _set_json_path(
    path: str, value: Any, payload: dict[str, Any] | None = None
) -> dict[str, Any]:
    payload = {} if payload is None else payload
    cursor = payload
    segments = path.split(".")
    for segment in segments[:-1]:
        cursor = cursor.setdefault(segment, {})
    cursor[segments[-1]] = value
    return payload


def _install_definition(
    monkeypatch: pytest.MonkeyPatch, definition: dict[str, Any]
) -> JavaMcpClient:
    client = JavaMcpClient("http://127.0.0.1:49080", PROJECT, USER)

    def tool(name: str, arguments: dict[str, Any]) -> Any:
        if name == "localcloud_get_scenario":
            return definition
        if name == "localcloud_browse_resources":
            return {
                "items": [{"name": f"{PROJECT}-expected"}],
                "project": arguments["project"],
            }
        raise AssertionError(name)

    monkeypatch.setattr(client, "tool", tool)
    monkeypatch.setattr(java_client_module.httpx, "Client", FakeHttpClient)
    return client


@pytest.mark.parametrize("scenario", ["analytics-smoke", "olap-application"])
def test_packaged_seeded_resources_pass_concrete_checks_on_dynamic_ports(
    monkeypatch: pytest.MonkeyPatch, scenario: str
) -> None:
    definition = _scenario_definition(scenario)
    canonical_ports = {int(entry["port"]) for entry in definition["verification"]}
    endpoint_map = {str(port): port + 25000 for port in canonical_ports}
    responses: dict[str, Any] = {}
    for entry in definition["verification"]:
        if entry["type"] != "http_json":
            continue
        expect = entry["expect"]
        expected = _render(expect.get("equals", [{"present": True}]))
        url = (
            f"http://127.0.0.1:{endpoint_map[str(entry['port'])]}"
            f"{_render(entry['path'])}"
        )
        responses[url] = _set_json_path(
            expect["json_path"], expected, responses.get(url)
        )
    FakeHttpClient.responses = responses
    FakeHttpClient.calls = []
    tcp_calls: list[tuple[str, int]] = []

    def connect(address: tuple[str, int], timeout: float) -> Any:
        assert timeout == 60.0
        tcp_calls.append(address)
        return nullcontext()

    monkeypatch.setattr(java_client_module.socket, "create_connection", connect)
    client = _install_definition(monkeypatch, definition)

    result = client.verify_scenario(scenario, PROJECT, endpoint_map)

    assert result["verified"] == [entry["id"] for entry in definition["verification"]]
    assert result["verified_services"] == SCENARIO_SERVICES[scenario]
    assert all("${project}" not in url for url in FakeHttpClient.calls)
    assert all(
        f":{endpoint_map[str(entry['port'])]}" in next(
            url for url in FakeHttpClient.calls if _render(entry["path"]) in url
        )
        for entry in definition["verification"]
        if entry["type"] == "http_json"
    )
    if scenario == "olap-application":
        assert tcp_calls == [("127.0.0.1", endpoint_map["24090"])]



def test_metadata_only_runtime_cannot_satisfy_runtime_scenario(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    definition = _scenario_definition("olap-application")
    runtime_check = next(
        entry
        for entry in definition["verification"]
        if entry["id"] == "dataproc-runtime-provider"
    )
    definition = {"services": ["dataproc"], "verification": [runtime_check]}
    endpoint_map = {"24080": 49080}
    FakeHttpClient.responses = {
        "http://127.0.0.1:49080/runtime/status": {
            "mode": "metadata-only",
            "available": False,
        }
    }
    FakeHttpClient.calls = []
    client = _install_definition(monkeypatch, definition)

    with pytest.raises(HostError) as caught:
        client.verify_scenario("olap-application", PROJECT, endpoint_map)

    assert caught.value.code == "scenario_verification_failed"
    assert caught.value.details["verification_id"] == "dataproc-runtime-provider"


def test_empty_dataproc_child_probe_cannot_activate_scenario(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    definition = _scenario_definition("olap-application")
    consoles_check = next(
        entry
        for entry in definition["verification"]
        if entry["id"] == "dataproc-spark-consoles"
    )
    definition = {"services": ["dataproc"], "verification": [consoles_check]}
    endpoint_map = {"24080": 49080}
    FakeHttpClient.responses = {
        (
            "http://127.0.0.1:49080/v1/projects/agent-project/"
            "regions/us-central1/clusters/analytics-spark/consoles"
        ): {"consoles": {}}
    }
    FakeHttpClient.calls = []
    client = _install_definition(monkeypatch, definition)

    with pytest.raises(HostError) as caught:
        client.verify_scenario("olap-application", PROJECT, endpoint_map)

    assert caught.value.code == "scenario_verification_failed"
    assert caught.value.details["verification_id"] == "dataproc-spark-consoles"

@pytest.mark.parametrize(
    ("payload", "reason"),
    [
        ({}, "response is empty"),
        ({"kind": "storage#bucket"}, "is missing"),
        ({"name": "wrong-bucket"}, "wrong value"),
    ],
)
def test_browse_only_false_positive_fails_with_verification_id(
    monkeypatch: pytest.MonkeyPatch, payload: dict[str, Any], reason: str
) -> None:
    verification = {
        "id": "gcs-concrete-bucket",
        "service": "gcs",
        "type": "http_json",
        "port": 24081,
        "path": "/storage/v1/b/${project}-expected",
        "expect": {
            "json_path": "name",
            "equals": "${project}-expected",
        },
    }
    definition = {"services": ["gcs"], "verification": [verification]}
    endpoint_map = {"24081": 49181}
    url = f"http://127.0.0.1:49181/storage/v1/b/{PROJECT}-expected"
    FakeHttpClient.responses = {url: payload}
    FakeHttpClient.calls = []
    client = _install_definition(monkeypatch, definition)

    with pytest.raises(HostError) as caught:
        client.verify_scenario("analytics-smoke", PROJECT, endpoint_map)

    assert caught.value.code == "scenario_verification_failed"
    assert caught.value.details["verification_id"] == "gcs-concrete-bucket"
    assert "gcs-concrete-bucket" in caught.value.message
    assert reason in caught.value.message


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