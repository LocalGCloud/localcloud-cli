"""Diagnostics a runtime container that fails to start carries, and where they go.

The CLI prints them, MCP clients read an allowlisted part of them, and secrets
never appear in any of them."""

from __future__ import annotations

import io
import json
from pathlib import Path
from typing import Any

import pytest

import localcloud_cli.docker_runtime as runtime_module
import localcloud_cli.mcp_stdio as mcp_module
from localcloud_cli import __version__
from localcloud_cli.cli import main
from localcloud_cli.docker_runtime import DockerRuntime, start_failure_diagnostics
from localcloud_cli.errors import HostError
from localcloud_cli.mcp_stdio import STATUS_TOOL, McpAdapter, error_diagnostics
from localcloud_cli.output import render_error, render_json
from localcloud_cli.redaction import (
    LOG_TAIL_BYTES,
    LOG_TAIL_LINES,
    redact_secrets,
    redacted_log_tail,
)

from test_docker_runtime import (  # noqa: F401 - fixtures
    Resource,
    _add_external,
    _config,
    ready_runtime,
)
from test_cli import FakeController, fake_controller  # noqa: F401 - fixture
from test_mcp_stdio import (  # noqa: F401 - fixtures
    CONFIG,
    FakeJava,
    RunningController,
    _degraded_adapter,
    _patch_java,
    _request,
)

SECRET_LOG = "\n".join(
    [
        "\x1b[32m[  OK  ]\x1b[0m Started Cloud Storage (gcs)",
        "connecting to postgres://admin:hunter2@db.internal:5432/app",
        "Authorization: Bearer abcdefghijklmnopqrstuvwxyz0123",
        "password=s3cr3t-value api_key: 'key-material'",
        "token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.c2lnbmF0dXJl here",
        "oauth ya29.a0AfH6SMBexampleaccesstoken used",
        "owner alice@example.test",
        "-----BEGIN PRIVATE KEY-----",
        "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC",
        "-----END PRIVATE KEY-----",
        "FATAL refusing to open the data volume",
    ]
)
SECRETS = (
    "hunter2",
    "abcdefghijklmnopqrstuvwxyz0123",
    "s3cr3t-value",
    "key-material",
    "eyJzdWIiOiJ1c2VyIn0",
    "ya29.a0AfH6SMB",
    "alice@example.test",
    "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC",
    "\x1b[",
)


def _assert_no_secrets(text: str) -> None:
    for secret in SECRETS:
        assert secret not in text


# Redaction ------------------------------------------------------------------


def test_redaction_removes_credentials_tokens_and_keys() -> None:
    redacted = redacted_log_tail(SECRET_LOG)

    _assert_no_secrets(redacted)
    assert "postgres://<credentials>@db.internal:5432/app" in redacted
    assert "Authorization: <redacted>" in redacted
    assert "<redacted private key>" in redacted
    assert "<email>" in redacted
    assert redacted.splitlines()[0] == "[  OK  ] Started Cloud Storage (gcs)"
    assert redacted.splitlines()[-1] == "FATAL refusing to open the data volume"


def test_redaction_keeps_ordinary_text() -> None:
    text = "Service Spanner (spanner) failed: exit_1 on port 9010"
    assert redact_secrets(text) == text


def test_log_tail_is_bounded_by_lines_and_bytes() -> None:
    many = "\n".join(f"line {index}" for index in range(500))
    tail = redacted_log_tail(many)
    assert tail.splitlines() == [f"line {index}" for index in range(460, 500)]
    assert len(tail.splitlines()) == LOG_TAIL_LINES

    wide = "\n".join(f"{index:03d} " + "x" * 300 for index in range(40))
    tail = redacted_log_tail(wide)
    assert len(tail.encode()) <= LOG_TAIL_BYTES
    assert tail.splitlines()[-1].startswith("039 ")

    single = "y" * 10_000 + "END"
    tail = redacted_log_tail(single)
    assert len(tail.encode()) == LOG_TAIL_BYTES
    assert tail.endswith("END")


# What Docker reports about a container that failed to start ---------------------


class _Image:
    attrs = {
        "RepoDigests": [
            "agentcloud/localcloud@sha256:" + "a" * 64,
        ]
    }


def _failed_container(**state: Any) -> Resource:
    container = Resource(
        "lc-diagnostics",
        {
            "com.localcloud.runtime-ownership": "data-volume-v1",
            runtime_module.CONFIG_LABEL: '{"environment": {"TOKEN": "x"}}',
            runtime_module.CONFIG_PATH_LABEL: "/somewhere/localcloud.yaml",
            "org.opencontainers.image.version": "0.1.17",
            "unrelated.label": "ignored",
        },
        resource_id="c" * 64,
        state="exited",
        image="agentcloud/localcloud:lite",
        image_id="sha256:" + "b" * 64,
    )
    container.attrs["State"].update(
        {"ExitCode": 3, "OOMKilled": False, "Error": "", **state}
    )
    container.image = _Image()  # type: ignore[attr-defined]
    container.logs = lambda **_kwargs: SECRET_LOG.encode()  # type: ignore[assignment]
    return container


def test_start_failure_diagnostics_describe_the_container_and_image() -> None:
    diagnostics = start_failure_diagnostics(
        _failed_container(Error="pull token=abc123secret failed")
    )

    assert diagnostics["cli_version"] == __version__
    assert diagnostics["container"] == "lc-diagnostics"
    assert diagnostics["container_id"] == "c" * 64
    assert diagnostics["state"] == "exited"
    assert diagnostics["exit_code"] == 3
    assert diagnostics["oom_killed"] is False
    assert diagnostics["docker_error"] == "pull token=<redacted> failed"
    assert diagnostics["image"] == "agentcloud/localcloud:lite"
    assert diagnostics["image_id"] == "sha256:" + "b" * 64
    assert diagnostics["image_digest"] == "agentcloud/localcloud@sha256:" + "a" * 64
    # Ownership and OCI version labels only; never the stored configuration.
    assert diagnostics["labels"] == {
        "com.localcloud.runtime-ownership": "data-volume-v1",
        "org.opencontainers.image.version": "0.1.17",
    }
    assert diagnostics["logs_tail"].endswith("FATAL refusing to open the data volume")
    _assert_no_secrets(json.dumps(diagnostics))


def test_start_failure_diagnostics_tolerate_missing_inspection() -> None:
    class Bare:
        id = "bare"
        name = "bare"
        attrs = None

        def logs(self, **_kwargs: Any) -> bytes:
            raise RuntimeError("daemon gone")

    diagnostics = start_failure_diagnostics(Bare())

    assert diagnostics["exit_code"] is None
    assert diagnostics["oom_killed"] is None
    assert diagnostics["image"] is None
    assert diagnostics["image_digest"] is None
    assert diagnostics["state"] == "unknown"
    assert diagnostics["logs_tail"] == "<logs unavailable: daemon gone>"


def test_readiness_failure_carries_diagnostics_instead_of_raw_logs(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    container = _failed_container(OOMKilled=True, ExitCode=137)
    monkeypatch.setattr(runtime_module.time, "monotonic", lambda: 0.0)

    with pytest.raises(HostError) as caught:
        DockerRuntime.wait_ready(
            "http://127.0.0.1:5380", deadline=1.0, container=container
        )

    error = caught.value
    assert error.code == "container_start_failed"
    assert error.message == "LocalCloud runtime container exited before becoming healthy"
    assert error.details["state"] == "exited"
    assert error.details["exit_code"] == 137
    assert error.details["image"] == "agentcloud/localcloud:lite"
    assert "logs" not in error.details
    assert error.details["diagnostics"]["oom_killed"] is True
    _assert_no_secrets(json.dumps(error.to_dict()))


@pytest.mark.parametrize(
    ("cause", "code"),
    [
        ("OCI runtime create failed: token=abc123secret", "container_start_failed"),
        (
            "driver failed programming external connectivity: "
            "Bind for 127.0.0.1:5380 failed: port is already allocated",
            "port_no_longer_available",
        ),
    ],
)
def test_docker_start_failure_carries_diagnostics(
    tmp_path: Path,
    ready_runtime: tuple[DockerRuntime, Any],
    cause: str,
    code: str,
) -> None:
    runtime, client = ready_runtime
    external = _add_external(client, state="exited")
    external.attrs["State"].update({"ExitCode": 128, "OOMKilled": False})
    external.logs = lambda **_kwargs: SECRET_LOG.encode()  # type: ignore[assignment]

    def refuse() -> None:
        raise RuntimeError(cause)

    external.start = refuse  # type: ignore[assignment]
    config = _config(tmp_path)
    record = runtime.resolve(config)
    assert record is not None

    with pytest.raises(HostError) as caught:
        runtime.start(config, record)

    error = caught.value
    assert error.code == code
    assert error.details["container_id"] == external.id
    assert "logs" not in error.details
    assert "abc123secret" not in error.details["cause"]
    diagnostics = error.details["diagnostics"]
    assert diagnostics["exit_code"] == 128
    assert diagnostics["container_id"] == external.id
    assert external.reload_calls >= 1
    _assert_no_secrets(json.dumps(error.to_dict()))


# CLI output -------------------------------------------------------------------


def _start_failure() -> HostError:
    container = _failed_container(Error="exec failed token=abc123secret")
    diagnostics = start_failure_diagnostics(container)
    return HostError(
        "container_start_failed",
        "LocalCloud runtime container exited before becoming healthy",
        {
            "container": diagnostics["container"],
            "state": "exited",
            "exit_code": diagnostics["exit_code"],
            "image": diagnostics["image"],
            "diagnostics": diagnostics,
        },
    )


def test_plain_error_shows_exit_code_image_and_newest_log_lines() -> None:
    rendered = render_error(_start_failure())
    lines = rendered.splitlines()

    assert lines[0] == (
        "Error [container_start_failed] "
        "LocalCloud runtime container exited before becoming healthy"
    )
    assert "Exit Code: 3" in lines
    assert "Image: agentcloud/localcloud:lite" in lines
    # Fields the details already show are not repeated.
    assert sum(line.startswith("Exit Code:") for line in lines) == 1
    assert sum(line.startswith("Image:") for line in lines) == 1
    assert "OOM Killed: false" in lines
    assert "Docker Error: exec failed token=<redacted>" in lines
    assert f"CLI Version: {__version__}" in lines
    tail_start = lines.index("Last Log Lines:")
    tail = lines[tail_start + 1 :]
    assert 0 < len(tail) <= 10
    assert tail[-1] == "  FATAL refusing to open the data volume"
    _assert_no_secrets(rendered)


def test_json_error_carries_the_full_redacted_diagnostics(
    fake_controller: None,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    error = _start_failure()

    def fail(_self: Any, _config: Any, **_kwargs: Any) -> dict[str, Any]:
        raise error

    monkeypatch.setattr(FakeController, "start", fail)

    assert main(["start", "--verbose"]) == 2

    err = capsys.readouterr().err
    payload = json.loads(err[err.index("{") : err.rindex("}") + 1])
    assert payload["error"] is True
    assert payload["code"] == "container_start_failed"
    diagnostics = payload["details"]["diagnostics"]
    assert diagnostics["exit_code"] == 3
    assert diagnostics["logs_tail"].endswith("FATAL refusing to open the data volume")
    _assert_no_secrets(err)
    assert render_json(error.to_dict())  # serialisable as is


# MCP --------------------------------------------------------------------------


def test_mcp_diagnostics_are_an_allowlisted_redacted_subset() -> None:
    error = _start_failure()
    error.details["diagnostics"]["logs_tail"] += "\npassword=leaked"
    error.details["diagnostics"]["state"] = {"nested": "dropped"}

    diagnostics = error_diagnostics(error)

    assert diagnostics is not None
    assert set(diagnostics) == {
        "cli_version",
        "exit_code",
        "image",
        "image_digest",
        "image_id",
        "logs_tail",
        "oom_killed",
    }
    assert diagnostics["exit_code"] == 3
    assert "password=<redacted>" in diagnostics["logs_tail"]
    # Never container names or ids, labels or Docker's own error text.
    serialised = json.dumps(diagnostics)
    assert "lc-diagnostics" not in serialised
    assert "c" * 64 not in serialised
    assert "runtime-ownership" not in serialised
    _assert_no_secrets(serialised)
    assert error_diagnostics(HostError("docker_unavailable", "down")) is None


def test_mcp_status_and_unavailable_calls_carry_diagnostics_and_recovery() -> None:
    adapter, _ = _degraded_adapter(_start_failure())

    status = adapter.handle(_request(1, "tools/call", name=STATUS_TOOL))
    call = adapter.handle(_request(2, "tools/call", name="localcloud_list_services"))
    read = adapter.handle(_request(3, "resources/read", uri="localcloud://services"))

    assert status is not None and call is not None and read is not None
    structured = status["result"]["structuredContent"]
    assert structured["state"] == "unavailable"
    assert structured["error"]["code"] == "container_start_failed"
    assert structured["error"]["diagnostics"]["exit_code"] == 3
    assert "lc restart --pull" in structured["next_step"]
    assert "keeps persistent data" in structured["next_step"]
    status_text = status["result"]["content"][0]["text"]
    assert "exit code 3" in status_text
    assert "FATAL refusing to open the data volume" in status_text

    call_text = call["result"]["content"][0]["text"]
    assert call["result"]["isError"] is True
    assert "exit code 3" in call_text and "lc restart --pull" in call_text

    data = read["error"]["data"]
    assert data["code"] == "container_start_failed"
    assert data["diagnostics"]["image"] == "agentcloud/localcloud:lite"
    for response in (status, call, read):
        _assert_no_secrets(json.dumps(response))
        assert "lc-diagnostics" not in json.dumps(response)


def test_mcp_port_conflict_has_its_own_recovery() -> None:
    adapter, _ = _degraded_adapter(
        HostError("port_no_longer_available", "A host port is in use")
    )

    status = adapter.handle(_request(1, "tools/call", name=STATUS_TOOL))

    assert status is not None
    next_step = status["result"]["structuredContent"]["next_step"]
    assert "holds a host port" in next_step and "lc restart" in next_step


def test_forwarded_error_carries_only_allowlisted_diagnostics() -> None:
    error = _start_failure()

    class FailingJava(FakeJava):
        def forward(self, message: dict[str, Any], **_kwargs: Any) -> dict[str, Any] | None:
            raise error

    adapter = McpAdapter(CONFIG, controller=RunningController())
    adapter.java = FailingJava("http://127.0.0.1:49080", "agent-project-1", "integration-agent")

    response = adapter.handle(_request(1, "tools/call", name="localcloud_list_services"))

    assert response is not None
    data = response["error"]["data"]
    assert data["code"] == "container_start_failed"
    assert data["diagnostics"] == error_diagnostics(error)
    assert "lc-diagnostics" not in json.dumps(response)
    _assert_no_secrets(json.dumps(response))


@pytest.mark.parametrize("debug", [False, True])
def test_debug_prints_diagnostics_on_stderr_only(
    debug: bool, monkeypatch: pytest.MonkeyPatch
) -> None:
    stderr = io.StringIO()
    monkeypatch.setattr(mcp_module.sys, "stderr", stderr)

    _degraded_adapter(_start_failure(), debug=debug)

    lines = stderr.getvalue().splitlines()
    assert lines[0] == (
        "[localcloud mcp] container_start_failed: "
        "LocalCloud runtime container exited before becoming healthy"
    )
    if debug:
        assert len(lines) == 2
        prefix = "[localcloud mcp] diagnostics: "
        assert lines[1].startswith(prefix)
        assert json.loads(lines[1][len(prefix) :])["exit_code"] == 3
        _assert_no_secrets(lines[1])
    else:
        assert len(lines) == 1


