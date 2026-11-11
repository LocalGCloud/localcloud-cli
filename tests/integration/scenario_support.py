from __future__ import annotations

import base64
import hashlib
import re
import socket
import sys
from collections.abc import Mapping
from typing import Any
from urllib.parse import urlparse

import httpx

from localcloud_cli.errors import HostError
from localcloud_cli.java_client import JavaMcpClient


def _progress(message: str) -> None:
    print(message, file=sys.stderr, flush=True)


def apply_scenario(client: JavaMcpClient, scenario: str, project: str) -> Any:
    """Apply through the client's bounded, progress-reporting call (see java_client)."""
    return client.apply_scenario(scenario, project, progress=_progress)


def get_scenario(client: JavaMcpClient, scenario: str) -> dict[str, Any]:
    """The scenario definition as the runtime under test publishes it."""
    definition = client.tool("localcloud_get_scenario", {"id": scenario})
    if not isinstance(definition, dict):
        raise _verification_error(scenario, "scenario-catalog", "scenario definition is missing")
    return definition


def scenario_artifact(
    client: JavaMcpClient, definition: Mapping[str, Any], name: str
) -> bytes:
    """Read one artifact file the runtime's scenario declares, through MCP resources/read on the
    entry's `uri`, and check it against the entry's sha256 and size."""
    scenario = str(definition.get("id"))
    files = definition.get("artifact_files")
    if not isinstance(files, list):
        raise _verification_error(
            scenario, "scenario-artifacts",
            "the runtime is too old for this test: localcloud_get_scenario has no artifact_files "
            "(scenario artifacts are served from runtimes newer than 0.1.17)",
        )
    entry = next(
        (item for item in files if isinstance(item, Mapping) and item.get("name") == name), None
    )
    if entry is None or not isinstance(entry.get("uri"), str):
        raise _verification_error(
            scenario, "scenario-artifacts", f"artifact_files has no entry for {name}",
            artifacts=[item.get("name") for item in files if isinstance(item, Mapping)],
        )
    uri = entry["uri"]
    result = client.rpc("resources/read", {"uri": uri})
    contents = result.get("contents") if isinstance(result, Mapping) else None
    if not isinstance(contents, list) or len(contents) != 1 or not isinstance(contents[0], Mapping):
        raise _verification_error(
            scenario, "scenario-artifacts", f"resources/read {uri} returned no single content",
        )
    content = contents[0]
    if isinstance(content.get("text"), str):
        data = content["text"].encode("utf-8")
    elif isinstance(content.get("blob"), str):
        data = base64.b64decode(content["blob"], validate=True)
    else:
        raise _verification_error(
            scenario, "scenario-artifacts", f"resources/read {uri} has neither text nor blob",
        )
    digest = hashlib.sha256(data).hexdigest()
    if digest != entry.get("sha256"):
        raise _verification_error(
            scenario, "scenario-artifacts", f"{name} does not match its sha256",
            expected=entry.get("sha256"), actual=digest,
        )
    if entry.get("size") is not None and int(entry["size"]) != len(data):
        raise _verification_error(
            scenario, "scenario-artifacts", f"{name} does not match its size",
            expected=entry.get("size"), actual=len(data),
        )
    return data


def checkpoint_project(client: JavaMcpClient, project: str, name: str) -> Any:
    return client.tool(
        "localcloud_checkpoint_project",
        {"project": project, "name": name},
    )


def verify_scenario(
    client: JavaMcpClient,
    scenario: str,
    project: str,
    endpoint_map: dict[str, Any],
) -> dict[str, Any]:
    definition = get_scenario(client, scenario)

    services = definition.get("services")
    if not isinstance(services, (list, tuple, set)):
        raise _verification_error(
            scenario, "scenario-catalog", "scenario services are malformed"
        )
    # The runtime lists inventory only for Cloud Tasks queues, so each service is verified through
    # the concrete checks the scenario declares below, not through localcloud_browse_resources.
    verified_services = [str(service) for service in services]

    declarations = definition.get("verification")
    if not isinstance(declarations, list) or not declarations:
        raise _verification_error(
            scenario,
            "scenario-catalog",
            "scenario has no concrete verification entries",
        )

    try:
        normalized_endpoints = {
            str(port): int(host_port) for port, host_port in endpoint_map.items()
        }
    except (AttributeError, TypeError, ValueError) as error:
        raise _verification_error(
            scenario, "scenario-catalog", "environment endpoint map is malformed"
        ) from error

    verified: list[str] = []
    data_plane_host = urlparse(client.url).hostname or "127.0.0.1"
    http_host = f"[{data_plane_host}]" if ":" in data_plane_host else data_plane_host
    headers = {
        "X-LocalCloud-Project": project,
        "X-LocalCloud-User": client.user,
        "X-Goog-User-Project": project,
    }
    with httpx.Client(timeout=client.timeout, headers=headers) as http_client:
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
                    response = http_client.get(url)
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
                        (data_plane_host, host_port), timeout=client.timeout
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


def _verification_declaration(scenario: str, raw: Any) -> dict[str, Any]:
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
