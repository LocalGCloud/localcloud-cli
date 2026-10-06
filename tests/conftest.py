from __future__ import annotations

import pytest


@pytest.fixture(autouse=True)
def _no_telemetry(monkeypatch: pytest.MonkeyPatch) -> None:
    # CLI tests must never reach PostHog; telemetry tests re-enable it explicitly.
    monkeypatch.setenv("LOCALCLOUD_TELEMETRY", "false")
