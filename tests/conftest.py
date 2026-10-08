from __future__ import annotations

from pathlib import Path
from types import SimpleNamespace

import pytest


@pytest.fixture(autouse=True)
def _no_telemetry(monkeypatch: pytest.MonkeyPatch) -> None:
    # CLI tests must never reach PostHog; telemetry tests re-enable it explicitly.
    monkeypatch.setenv("LOCALCLOUD_TELEMETRY", "false")


@pytest.fixture(autouse=True)
def _no_host_probe(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    # Setup checks must never read this machine's Docker apps or run its commands;
    # host-check tests pass their own probe.
    import localcloud_cli.host_checks as host_checks

    monkeypatch.setattr(
        host_checks,
        "default_probe",
        lambda: host_checks.HostProbe(
            environ={},
            home=tmp_path / "probe-home",
            system="Linux",
            run=lambda _argv, _timeout: None,
            which=lambda _name: None,
            applications=(),
        ),
    )
    # Diagnostics that open their own Docker connection see an engine they
    # cannot identify, never this machine's.
    monkeypatch.setattr(
        host_checks, "_docker_client", lambda: SimpleNamespace(info=lambda: {})
    )
