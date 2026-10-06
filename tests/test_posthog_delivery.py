"""Release gate: a real CLI failure reaches PostHog and can be queried back.

Opt-in through the ``posthog`` marker. It needs a PostHog personal API key with
``query:read`` scope and the ID of the project that owns the CLI's ingestion key:

    POSTHOG_PERSONAL_API_KEY=phx_... POSTHOG_PROJECT_ID=12345 \\
        uv run --extra test pytest -m posthog tests/test_posthog_delivery.py

``LOCALCLOUD_CLI_UNDER_TEST`` selects a frozen binary instead of this source tree,
``LOCALCLOUD_EXPECTED_COMMIT`` also checks the reported release commit, and
``LOCALCLOUD_REQUIRE_POSTHOG_CHECK=1`` fails instead of skipping without
credentials. Check runs use distinct IDs starting with ``lcc_0000`` so PostHog
can filter them out of product dashboards.
"""

from __future__ import annotations

import json
import os
import secrets
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

import httpx
import pytest

from localcloud_cli import __version__

pytestmark = pytest.mark.posthog

CHECK_ID_PREFIX = "lcc_0000"
_POLL_SECONDS = 20.0
_SEND_ATTEMPTS = 3
_COLUMNS = ("event", "source", "lib", "error_code", "lib_version", "commit_id")


def _credentials() -> tuple[str, str]:
    key = os.environ.get("POSTHOG_PERSONAL_API_KEY", "").strip()
    project = os.environ.get("POSTHOG_PROJECT_ID", "").strip()
    if key and project:
        return key, project
    if os.environ.get("LOCALCLOUD_REQUIRE_POSTHOG_CHECK") == "1":
        pytest.fail("POSTHOG_PERSONAL_API_KEY and POSTHOG_PROJECT_ID are required")
    pytest.skip("PostHog query credentials are not configured")


def _cli() -> list[str]:
    binary = os.environ.get("LOCALCLOUD_CLI_UNDER_TEST", "").strip()
    if binary:
        return [str(Path(binary).resolve())]
    return [sys.executable, "-m", "localcloud_cli"]


def _query(key: str, project: str, distinct_id: str) -> list[dict[str, Any]] | None:
    """Rows for this check's events, or None on a transient PostHog failure."""
    host = os.environ.get("POSTHOG_API_HOST", "").strip() or "https://us.posthog.com"
    try:
        response = httpx.post(
            f"{host.rstrip('/')}/api/projects/{project}/query/",
            headers={"Authorization": f"Bearer {key}"},
            json={
                "query": {
                    "kind": "HogQLQuery",
                    # distinct_id is generated below from hex digits only.
                    "query": (
                        "SELECT event, properties.source, properties.$lib, "
                        "properties.error_code, properties.$lib_version, "
                        "properties.commit_id FROM events "
                        f"WHERE distinct_id = '{distinct_id}' "
                        "AND timestamp > now() - INTERVAL 1 DAY"
                    ),
                }
            },
            timeout=30.0,
        )
    except httpx.TransportError:
        return None
    if response.status_code == 429 or response.status_code >= 500:
        return None
    response.raise_for_status()
    return [dict(zip(_COLUMNS, row)) for row in response.json()["results"]]


def test_startup_failure_telemetry_reaches_posthog(tmp_path: Path) -> None:
    key, project = _credentials()
    distinct_id = CHECK_ID_PREFIX + secrets.token_hex(6)
    home = tmp_path / "home"
    state = home / ".local" / "share" / "localcloud" / "telemetry.json"
    state.parent.mkdir(parents=True)
    state.write_text(json.dumps({"id": distinct_id}), encoding="utf-8")

    environment = {
        name: value
        for name, value in os.environ.items()
        if name not in {"LOCALCLOUD_TELEMETRY", "DO_NOT_TRACK", "LOCALCLOUD_CONFIG"}
        and not name.startswith("POSTHOG_")
    }
    environment.update(
        HOME=str(home),
        LOCALCLOUD_HOME=str(state.parent),
        # A Docker endpoint that cannot exist makes `start` fail the same way on every runner.
        DOCKER_HOST="unix:///nonexistent/localcloud-telemetry-check.sock",
    )
    # Each failed start retries the buffered batch, so a slow first connection
    # from a cold runner gets more chances before the gate fails.
    for _attempt in range(_SEND_ATTEMPTS):
        completed = subprocess.run(
            [*_cli(), "start"],
            cwd=tmp_path,
            env=environment,
            capture_output=True,
            text=True,
            timeout=120,
            check=False,
        )
        assert completed.returncode == 2, completed.stderr
        assert "docker_unavailable" in completed.stderr
        saved = json.loads(state.read_text(encoding="utf-8"))
        assert saved["id"] == distinct_id
        if saved["pending"] == []:
            break
    else:
        pytest.fail(f"PostHog did not accept the telemetry batch in {_SEND_ATTEMPTS} attempts")

    timeout = float(os.environ.get("POSTHOG_VERIFY_TIMEOUT", "600"))
    deadline = time.monotonic() + timeout
    rows: list[dict[str, Any]] = []
    while True:
        rows = _query(key, project, distinct_id) or rows
        if {"cli_startup_error", "cli_heartbeat"} <= {row["event"] for row in rows}:
            break
        if time.monotonic() >= deadline:
            pytest.fail(
                f"{distinct_id} events were not queryable after {timeout:.0f}s; saw {rows}. "
                "POSTHOG_PROJECT_ID must be the project that owns the key the CLI, "
                "server, and Console share (telemetry.DEFAULT_API_KEY)."
            )
        time.sleep(_POLL_SECONDS)

    events = {row["event"]: row for row in rows}
    assert events["cli_startup_error"]["error_code"] == "docker_unavailable"
    expected_commit = os.environ.get("LOCALCLOUD_EXPECTED_COMMIT", "").strip()
    for row in events.values():
        # The project is shared with the server, Console, and website.
        assert row["source"] == "cli"
        assert row["lib"] == "localcloud-cli"
        assert row["lib_version"] == __version__
        if expected_commit:
            assert row["commit_id"] == expected_commit
