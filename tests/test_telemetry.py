from __future__ import annotations

import json
import re
from collections.abc import Mapping
from datetime import UTC, datetime
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest

from localcloud_cli import telemetry
from localcloud_cli.errors import HostError
from localcloud_cli.telemetry import (
    HEARTBEAT_SECONDS,
    MAX_PENDING,
    RETRY_SECONDS,
    Telemetry,
)

NOW = 1_800_000_000.0


def _stamp(seconds: float) -> str:
    return datetime.fromtimestamp(seconds, UTC).strftime(
        "%Y-%m-%dT%H:%M:%S.123456789Z"
    )


def _config(**environment: str) -> SimpleNamespace:
    return SimpleNamespace(
        environment=dict(environment),
        effective_services=("gcs", "pubsub"),
        memory="4g",
        tls_enabled=False,
        docker_socket_mode="auto",
        image="agentcloud/localcloud:1.2.3",
        project="payments-dev",
        user="alice",
        data_volume="localcloud-data",
        container_name="localcloud",
        network_name="localcloud",
        config_path=None,
    )


class Sink:
    def __init__(self, delivered: bool = True) -> None:
        self.delivered = delivered
        self.batches: list[list[dict[str, Any]]] = []
        self.environments: list[Mapping[str, str]] = []

    def __call__(self, events: list[dict[str, Any]], environment: Mapping[str, str]) -> bool:
        self.batches.append([dict(event) for event in events])
        self.environments.append(environment)
        return self.delivered

    def events(self) -> list[str]:
        return [event["event"] for batch in self.batches for event in batch]


@pytest.fixture
def sink(monkeypatch: pytest.MonkeyPatch) -> Sink:
    monkeypatch.delenv("LOCALCLOUD_TELEMETRY", raising=False)
    monkeypatch.delenv("DO_NOT_TRACK", raising=False)
    monkeypatch.delenv("CI", raising=False)
    monkeypatch.delenv("DOCKER_HOST", raising=False)
    recorder = Sink()
    monkeypatch.setattr(telemetry, "_deliver", recorder)
    return recorder


def _run(
    state: Path,
    command: str,
    *,
    at: float = NOW,
    config: SimpleNamespace | None = None,
    logs: str = "",
    error: BaseException | None = None,
    result: Any = None,
    interrupted: bool = False,
    fallback: bool = False,
) -> None:
    session = Telemetry(command, state_path=state, clock=lambda: at)
    session.configure(config or _config(), fallback=fallback)  # type: ignore[arg-type]
    session.observe_logs(logs)
    session.finish(error=error, result=result, interrupted=interrupted)


def _startup_event(sink: Sink) -> dict[str, Any]:
    [event] = [event for batch in sink.batches for event in batch if event["event"] == "cli_startup_error"]
    return event


def test_startup_failure_reports_scrubbed_log_signatures(sink: Sink, tmp_path: Path) -> None:
    state = tmp_path / "telemetry.json"
    _run(state, "stop", at=NOW - 10)  # first-run heartbeat out of the way
    sink.batches.clear()
    logs = "\n".join(
        [
            f"{_stamp(NOW - 3600)} [FAILED] Service Old (old) failed: stale",
            f"{_stamp(NOW)} [  OK  ] Started Cloud Storage (gcs)",
            f"{_stamp(NOW)} \x1b[1;31m[FAILED]\x1b[0m Service Spanner (spanner) failed: exit_1 in 12.34s",
            f"{_stamp(NOW)} ERROR: cannot read /Users/alice/keys/sa.json for payments-dev token=abc",
        ]
    )
    error = HostError(
        "health_timeout",
        "LocalCloud did not become healthy",
        {"timeout_seconds": 120, "last_error": "HTTP 503", "logs": logs},
    )

    _run(state, "start", at=NOW + 1, error=error)

    [[event]] = sink.batches
    properties = event["properties"]
    assert event["event"] == "cli_startup_error"
    assert properties["outcome"] == "failed"
    assert properties["error_code"] == "health_timeout"
    assert properties["cause"] == "HTTP 503"
    assert properties["log_errors"] == [
        "[FAILED] Service Spanner (spanner) failed: exit_1",
        "ERROR: cannot read ~/keys/sa.json for <project> token=<redacted>",
    ]
    assert properties["error_message"] == "LocalCloud did not become healthy"
    assert properties["services_enabled"] == ["gcs", "pubsub"]
    assert properties["services_enabled_count"] == 2
    assert properties["image_tag"] == "1.2.3"
    assert json.loads(state.read_text())["pending"] == []


def test_every_event_is_identifiable_as_cli(sink: Sink, tmp_path: Path) -> None:
    """The project is shared with the server, Console, and website; never blur sources."""
    _run(tmp_path / "telemetry.json", "start", error=HostError("health_timeout", "x"))

    events = [event for batch in sink.batches for event in batch]
    assert [event["event"] for event in events] == ["cli_startup_error", "cli_heartbeat"]
    for event in events:
        properties = event["properties"]
        assert event["event"].startswith("cli_")
        assert properties["source"] == "cli"
        assert properties["$lib"] == "localcloud-cli"
        assert properties["$process_person_profile"] is False
        # The server's IDs are lc_<hex>; CLI IDs never collide with that prefix.
        assert re.fullmatch(r"lcc_[0-9a-f]{16}", event["distinct_id"])
        assert properties["distinct_id"] == event["distinct_id"]


@pytest.mark.parametrize(
    ("raw", "scrubbed"),
    [
        ("DB_PASSWORD=hunter2 next", "DB_PASSWORD=<redacted> next"),
        ("PGPASSWORD=hunter2", "PGPASSWORD=<redacted>"),
        ("client_secret=abc&x=1", "client_secret=<redacted>&x=1"),
        ('{"password": "hunter 2", "x": 1}', '{"password": <redacted>, "x": 1}'),
        ("Authorization: Bearer abc123def", "Authorization: <redacted>"),
        ("sent Bearer abc123def456 upstream", "sent Bearer <redacted> upstream"),
        ("X-Api-Key: k1", "X-Api-Key: <redacted>"),
        ("https://bob:pw@db.internal/x", "https://<credentials>@db.internal/x"),
        ("secretmanager: bind failed", "secretmanager: bind failed"),
    ],
)
def test_scrubber_redacts_secret_forms(
    sink: Sink, tmp_path: Path, raw: str, scrubbed: str
) -> None:
    session = Telemetry("start", state_path=tmp_path / "telemetry.json")
    session.configure(_config())  # type: ignore[arg-type]

    assert session._scrub(raw) == scrubbed


def test_scrubber_hides_identifying_names_and_paths(
    sink: Sink, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("DOCKER_HOST", "tcp://build01.acme.internal:2375")
    monkeypatch.setenv("LOCALCLOUD_HOME", "/srv/acme/localcloud")
    monkeypatch.chdir(tmp_path)
    config = _config()
    config.data_volume = "acme-payments"
    config.container_name = "acme-lc"
    config.network_name = "acme-net"
    config.image = "registry.acme.corp/team/localcloud:1.2"
    config.config_path = Path("/opt/acme/localcloud.yaml")
    session = Telemetry("start", state_path=tmp_path / "telemetry.json")
    session.configure(config)  # type: ignore[arg-type]

    raw = (
        "HTTPConnectionPool(host='build01.acme.internal') lock /srv/acme/localcloud/locks/"
        "acme-payments.lock acme-lc on acme-net pull registry.acme.corp/team/localcloud:1.2 "
        f"from registry.acme.corp reading /opt/acme/localcloud.yaml in {tmp_path}/src"
    )

    assert session._scrub(raw) == (
        "HTTPConnectionPool(host='<docker-host>') lock <localcloud-home>/locks/"
        "<data-volume>.lock <container> on <network> pull <image>:1.2 "
        "from <registry> reading <config> in <cwd>/src"
    )[: telemetry._MAX_TEXT]


def test_default_names_are_kept(sink: Sink, tmp_path: Path) -> None:
    session = Telemetry("start", state_path=tmp_path / "telemetry.json")
    session.configure(_config())  # type: ignore[arg-type]

    raw = "localcloud-data on localcloud as local-developer in local-gcp-project"
    assert session._scrub(raw) == raw


def test_successful_start_with_failed_service_is_reported(sink: Sink, tmp_path: Path) -> None:
    state = tmp_path / "telemetry.json"
    _run(state, "stop", at=NOW - 10)
    sink.batches.clear()

    _run(
        state,
        "start",
        result={"status": "started", "logs": f"{_stamp(NOW)} [FAILED] Service GKE (gke) failed: x"},
    )

    [[event]] = sink.batches
    assert event["properties"]["outcome"] == "started_with_errors"
    assert event["properties"]["log_errors"] == ["[FAILED] Service GKE (gke) failed: x"]


def test_interrupted_start_is_not_reported_as_started(sink: Sink, tmp_path: Path) -> None:
    state = tmp_path / "telemetry.json"
    _run(state, "start", interrupted=True)
    assert sink.events() == ["cli_heartbeat"]

    _run(
        state,
        "start",
        at=NOW + 1,
        logs=f"{_stamp(NOW + 1)} [FAILED] Service GKE (gke) failed: x",
        interrupted=True,
    )

    assert _startup_event(sink)["properties"]["outcome"] == "interrupted"


@pytest.mark.parametrize(
    "code",
    ["port_mapping_declined", "dry_run_pull_conflict", "host_lock_failed", "runtime_not_found"],
)
def test_user_input_errors_are_not_runtime_health(
    sink: Sink, tmp_path: Path, code: str
) -> None:
    _run(tmp_path / "telemetry.json", "start", error=HostError(code, "x"))

    assert sink.events() == ["cli_heartbeat"]


@pytest.mark.parametrize("drift", [-60.0, 0.0, 60.0])
def test_docker_clock_drift_keeps_current_lines_and_drops_old_ones(
    sink: Sink, tmp_path: Path, drift: float
) -> None:
    daemon_now = NOW + drift
    logs = "\n".join(
        [
            f"{_stamp(daemon_now - 30)} [FAILED] Service Old (old) failed: earlier run",
            f"{_stamp(daemon_now - 1)} [FAILED] Service GKE (gke) failed: current",
            f"{_stamp(daemon_now)} [  OK  ] Started Cloud Storage (gcs)",
        ]
    )

    _run(tmp_path / "telemetry.json", "start", at=NOW, logs=logs, error=HostError("health_timeout", "x"))

    assert _startup_event(sink)["properties"]["log_errors"] == [
        "[FAILED] Service GKE (gke) failed: current"
    ]


def test_quiet_container_falls_back_to_host_clock(sink: Sink, tmp_path: Path) -> None:
    logs = f"{_stamp(NOW - 3600)} [FAILED] Service Old (old) failed: earlier run"

    _run(tmp_path / "telemetry.json", "start", logs=logs, error=HostError("health_timeout", "x"))

    assert _startup_event(sink)["properties"]["log_errors"] == []


def test_only_lifecycle_commands_send(sink: Sink, tmp_path: Path) -> None:
    state = tmp_path / "telemetry.json"
    _run(state, "start", at=NOW, result={"logs": f"{_stamp(NOW)} [  OK  ] Started"})
    _run(state, "env", at=NOW + 60)
    _run(state, "start", at=NOW + 120)
    assert sink.events() == ["cli_heartbeat"]

    for command in ("status", "env", "logs", "console", "mcp"):
        _run(state, command, at=NOW + HEARTBEAT_SECONDS)
    assert sink.events() == ["cli_heartbeat"]  # a heartbeat is due, but only counted

    _run(state, "stop", at=NOW + HEARTBEAT_SECONDS)
    assert sink.events() == ["cli_heartbeat", "cli_heartbeat"]
    assert sink.batches[-1][0]["properties"]["commands"] == {
        "console": 1,
        "env": 2,
        "logs": 1,
        "mcp": 1,
        "start": 1,
        "status": 1,
        "stop": 1,
    }


def test_failed_delivery_keeps_a_handful_and_backs_off(sink: Sink, tmp_path: Path) -> None:
    state = tmp_path / "telemetry.json"
    sink.delivered = False
    for index in range(MAX_PENDING + 3):
        _run(state, "start", at=NOW + index, error=HostError("docker_unavailable", "down"))

    saved = json.loads(state.read_text())
    assert len(saved["pending"]) == MAX_PENDING
    assert all(len(batch) <= MAX_PENDING for batch in sink.batches)

    attempts = len(sink.batches)
    _run(state, "stop", at=NOW + 60)
    assert len(sink.batches) == attempts  # no new error: wait for the retry window

    sink.delivered = True
    _run(state, "status", at=NOW + 10 + RETRY_SECONDS)
    assert len(sink.batches) == attempts  # status never sends
    _run(state, "stop", at=NOW + 10 + RETRY_SECONDS)
    assert len(sink.batches) == attempts + 1
    assert json.loads(state.read_text())["pending"] == []


def test_unwritable_state_sends_nothing(sink: Sink, tmp_path: Path) -> None:
    blocker = tmp_path / "not-a-directory"
    blocker.write_text("")

    _run(blocker / "telemetry.json", "start", error=HostError("docker_unavailable", "down"))

    assert sink.batches == []


def test_interrupted_send_keeps_events_and_exit_status(
    sink: Sink, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    def interrupt(_events: Any, _environment: Any) -> bool:
        raise KeyboardInterrupt

    monkeypatch.setattr(telemetry, "_deliver", interrupt)
    state = tmp_path / "telemetry.json"

    _run(state, "start", error=HostError("docker_unavailable", "down"))  # must not raise

    saved = json.loads(state.read_text())
    assert [event["event"] for event in saved["pending"]] == ["cli_startup_error", "cli_heartbeat"]
    assert saved["retry_after"] == NOW + RETRY_SECONDS


def test_corrupt_or_future_state_recovers(sink: Sink, tmp_path: Path) -> None:
    state = tmp_path / "telemetry.json"
    state.write_text(
        '{"id": "lcc_0123456789abcdef", "last_heartbeat": 9e15, "retry_after": 9e15,'
        ' "startup_errors": Infinity, "commands": {"env": NaN, "start": 2},'
        ' "pending": [{"event": "cli_heartbeat"}]}'
    )

    _run(state, "stop")

    [batch] = sink.batches
    assert [event["event"] for event in batch] == ["cli_heartbeat", "cli_heartbeat"]
    assert batch[1]["distinct_id"] == "lcc_0123456789abcdef"
    assert batch[1]["properties"]["commands"] == {"start": 2, "stop": 1}
    assert batch[1]["properties"]["startup_errors"] == 0


@pytest.mark.parametrize(
    ("variable", "value", "in_config"),
    [
        ("LOCALCLOUD_TELEMETRY", "false", False),
        ("LOCALCLOUD_TELEMETRY", "0", False),
        ("DO_NOT_TRACK", "1", False),
        ("LOCALCLOUD_TELEMETRY", "false", True),
    ],
)
def test_opt_out_sends_nothing_and_drops_buffer(
    sink: Sink,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    variable: str,
    value: str,
    in_config: bool,
) -> None:
    state = tmp_path / "telemetry.json"
    sink.delivered = False
    _run(state, "start", error=HostError("docker_unavailable", "down"))
    assert state.exists()
    sink.batches.clear()

    config = _config(**{variable: value}) if in_config else _config()
    if not in_config:
        monkeypatch.setenv(variable, value)
    sink.delivered = True
    _run(state, "start", at=NOW + RETRY_SECONDS, config=config, error=HostError("x", "y"))

    assert sink.batches == []
    if in_config:
        # Only a marker remains, so runs without Docker still honor the opt-out.
        assert json.loads(state.read_text()) == {"disabled": True}
    else:
        assert not state.exists()


def test_config_opt_out_is_honored_when_docker_is_unreachable(
    sink: Sink, tmp_path: Path
) -> None:
    state = tmp_path / "telemetry.json"
    _run(state, "start", config=_config(LOCALCLOUD_TELEMETRY="false"))

    _run(state, "start", fallback=True, error=HostError("docker_unavailable", "down"))
    assert sink.batches == []

    _run(state, "start", error=HostError("health_timeout", "x"))  # opted back in
    assert _startup_event(sink)["properties"]["error_code"] == "health_timeout"
    assert "disabled" not in json.loads(state.read_text())


def test_docker_backed_runs_record_their_config_path(sink: Sink, tmp_path: Path) -> None:
    state = tmp_path / "telemetry.json"
    config = _config()
    config.config_path = Path("/work/project/localcloud.yaml")

    _run(state, "status", config=config)
    assert Telemetry("start", state_path=state).recorded_config() == "/work/project/localcloud.yaml"

    _run(state, "status", config=_config(), fallback=True)
    assert Telemetry("start", state_path=state).recorded_config() == "/work/project/localcloud.yaml"


def test_unresolved_config_is_silent(sink: Sink, tmp_path: Path) -> None:
    state = tmp_path / "telemetry.json"
    Telemetry("start", state_path=state).finish(error=HostError("invalid_config", "bad"))

    assert sink.batches == []
    assert not state.exists()


def test_custom_image_registry_is_not_reported(sink: Sink, tmp_path: Path) -> None:
    config = _config()
    config.image = "registry.corp.example/localcloud:1.2.3"
    _run(tmp_path / "telemetry.json", "stop", config=config)

    assert sink.batches[0][0]["properties"]["image_tag"] == "custom"


@pytest.mark.parametrize(
    ("status", "dropped"),
    [(200, True), (400, True), (429, False), (503, False), (None, False)],
)
def test_delivery_status_decides_retry(
    monkeypatch: pytest.MonkeyPatch, status: int | None, dropped: bool
) -> None:
    import httpx

    requests: list[dict[str, Any]] = []

    def post(url: str, *, json: dict[str, Any], timeout: float) -> Any:
        requests.append({"url": url, "json": json, "timeout": timeout})
        if status is None:
            raise httpx.ConnectError("offline")
        return SimpleNamespace(status_code=status)

    monkeypatch.setattr(httpx, "post", post)
    monkeypatch.setenv("LOCALCLOUD_POSTHOG_URL", "https://events.example/i/v0/e/")
    monkeypatch.setenv("LOCALCLOUD_EVENT_API_KEY", "phc_test")

    assert telemetry._deliver([{"event": "cli_heartbeat"}], {}) is dropped
    assert requests == [
        {
            "url": "https://events.example/batch/",
            "json": {"api_key": "phc_test", "batch": [{"event": "cli_heartbeat"}]},
            "timeout": telemetry.SEND_DEADLINE_SECONDS,
        }
    ]


def test_delivery_reads_overrides_from_config_environment(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    import httpx

    requests: list[tuple[str, str]] = []

    def post(url: str, *, json: dict[str, Any], timeout: float) -> Any:
        requests.append((url, json["api_key"]))
        return SimpleNamespace(status_code=200)

    monkeypatch.setattr(httpx, "post", post)
    monkeypatch.delenv("LOCALCLOUD_POSTHOG_URL", raising=False)
    monkeypatch.delenv("LOCALCLOUD_EVENT_API_KEY", raising=False)

    environment = {
        "LOCALCLOUD_POSTHOG_URL": "https://corp.example/ph/i/v0/e/",
        "LOCALCLOUD_EVENT_API_KEY": "phc_corp",
    }
    assert telemetry._deliver([{"event": "cli_heartbeat"}], environment)
    assert requests == [("https://corp.example/ph/batch/", "phc_corp")]


@pytest.mark.parametrize(
    ("configured", "batch"),
    [
        ("https://us.i.posthog.com/i/v0/e/", "https://us.i.posthog.com/batch/"),
        ("https://corp.example/ph/i/v0/e", "https://corp.example/ph/batch/"),
        ("https://corp.example/ph/capture/", "https://corp.example/ph/batch/"),
        ("https://corp.example/ph/batch/", "https://corp.example/ph/batch/"),
        ("https://corp.example/ph", "https://corp.example/ph/batch/"),
        ("http://127.0.0.1:8080", "http://127.0.0.1:8080/batch/"),
    ],
)
def test_batch_url_keeps_proxy_prefix(configured: str, batch: str) -> None:
    assert telemetry._batch_url(configured) == batch
