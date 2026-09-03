"""LocalCloud per-user environment controller."""

from __future__ import annotations

import json
from pathlib import Path
import re


__version__ = "0.1.2"


def _load_release_metadata() -> tuple[str | None, str | None]:
    try:
        metadata = json.loads(
            Path(__file__).with_name("_release.json").read_text(encoding="utf-8")
        )
    except (OSError, json.JSONDecodeError):
        return None, None

    commit = metadata.get("commit") if isinstance(metadata, dict) else None
    release_date = metadata.get("release_date") if isinstance(metadata, dict) else None
    if not isinstance(commit, str) or not isinstance(release_date, str):
        return None, None
    if (
        re.fullmatch(r"[0-9a-f]{12}", commit) is None
        or re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", release_date) is None
    ):
        return None, None
    return commit, release_date


__release_commit__, __release_date__ = _load_release_metadata()


def version_string() -> str:
    """Return the canonical public version and embedded release provenance."""
    value = f"localcloud {__version__}"
    if __release_commit__ is not None and __release_date__ is not None:
        value += f" (commit {__release_commit__}, released {__release_date__})"
    return value
