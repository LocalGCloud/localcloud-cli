from __future__ import annotations

from pathlib import Path
from types import SimpleNamespace

import pytest


@pytest.fixture(autouse=True)
def _no_telemetry(monkeypatch: pytest.MonkeyPatch) -> None:
    # CLI tests must never reach PostHog; telemetry tests re-enable it explicitly.
    monkeypatch.setenv("LOCALCLOUD_TELEMETRY", "false")


_REAL_GIT_MAIN_CHECKOUT = None


@pytest.fixture(autouse=True)
def _no_git_project(monkeypatch: pytest.MonkeyPatch) -> None:
    # Tests run inside this repository's checkout; the selected project must not
    # depend on it. Tests of git-derived projects use the `real_git` fixture.
    import localcloud_cli.config as config

    global _REAL_GIT_MAIN_CHECKOUT
    if _REAL_GIT_MAIN_CHECKOUT is None:
        _REAL_GIT_MAIN_CHECKOUT = config._git_main_checkout
    monkeypatch.setattr(config, "_git_main_checkout", lambda _directory: None)


@pytest.fixture
def real_git(monkeypatch: pytest.MonkeyPatch) -> None:
    """Derive projects from real git repositories (created under tmp_path)."""
    import localcloud_cli.config as config

    _REAL_GIT_MAIN_CHECKOUT.cache_clear()
    monkeypatch.setattr(config, "_git_main_checkout", _REAL_GIT_MAIN_CHECKOUT)


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
