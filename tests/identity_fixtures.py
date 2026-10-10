"""LocalCloud identity session fixtures shared by the CLI's tests.

The JSON files in tests/fixtures/identity are the wire messages `lc env --identity` exchanges
with LocalCloud. tests/test_identity_contract.py validates each one against LocalCloud's
published contract, and the fakes in the controller, Java client and CLI tests are built from
them, so no test invents its own copy of a session or profile.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

FIXTURES = Path(__file__).resolve().parent / "fixtures" / "identity"
_PROJECT = "local-gcp-project"
_ACCOUNT = f"default@{_PROJECT}.iam.gserviceaccount.com"
_SESSION = "wib-0123456789abcdef01234567"


def load(name: str) -> Any:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


CAPABILITY: str = load("session-created.json")["capability"]


def _adapted(name: str, project: str, account: str | None, session_id: str) -> dict[str, Any]:
    text = (FIXTURES / name).read_text(encoding="utf-8")
    text = text.replace(_SESSION, session_id).replace(
        _ACCOUNT, account or f"default@{project}.iam.gserviceaccount.com"
    )
    return json.loads(text.replace(_PROJECT, project))


def created_session(
    project: str = _PROJECT, account: str | None = None, session_id: str = _SESSION
) -> dict[str, Any]:
    """The POST /identity/sessions response for this project, account and session ID."""
    return _adapted("session-created.json", project, account, session_id)


def active_session(
    project: str = _PROJECT, account: str | None = None, session_id: str = _SESSION
) -> dict[str, Any]:
    """A GET /identity/sessions/{id} response after the relay's handshake."""
    return _adapted("session-active.json", project, account, session_id)
