"""Redaction shared by telemetry and by the diagnostics errors carry.

Secrets never leave the CLI: not in telemetry, not in an error an MCP client
reads, and not in a log tail printed with an error. ``SECRET_SCRUBBERS`` removes
credentials wherever text goes; ``GENERIC_SCRUBBERS`` additionally removes what
can identify a person (home directories, long opaque values) and is used for
text sent off this machine.
"""

from __future__ import annotations

import re

_SECRET_NAME = (
    r"[\w.-]{0,40}(?:token|secret(?!manager)|passw(?:or)?d|pwd|api[_-]?key"
    r"|access[_-]?key|private[_-]?key|authorization|credentials?)[\w.-]{0,40}"
)
SECRET_SCRUBBERS: tuple[tuple[re.Pattern[str], str], ...] = (
    (
        re.compile(
            r"-----BEGIN [A-Z ]*PRIVATE KEY-----.*?(?:-----END [A-Z ]*PRIVATE KEY-----|\Z)",
            re.DOTALL,
        ),
        "<redacted private key>",
    ),
    (re.compile(r"://[^/\s:@]+:[^/\s@]+@"), "://<credentials>@"),
    (re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+"), "<email>"),
    (
        re.compile(
            rf"(?i)\b({_SECRET_NAME}[\"']?\s*[=:]\s*)"
            r"(?:(?:bearer|basic|token)\s+)?(?:\"[^\"]*\"|'[^']*'|[^\s\"',;&)}\]]+)"
        ),
        r"\1<redacted>",
    ),
    (re.compile(r"(?i)\b(bearer)\s+[\w.~+/=-]{8,}"), r"\1 <redacted>"),
    # JSON Web Tokens and Google OAuth access tokens, wherever they appear.
    (re.compile(r"\beyJ[\w-]{8,}\.[\w-]{8,}\.[\w-]*"), "<redacted>"),
    (re.compile(r"\bya29\.[\w.-]{8,}"), "<redacted>"),
)
GENERIC_SCRUBBERS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"(?:/Users|/home)/[^/\s]+|/root\b"), "~"),
    (re.compile(r"[A-Za-z]:\\Users\\[^\\\s]+"), "~"),
    (re.compile(r"[A-Za-z0-9_+=-]{32,}"), "<redacted>"),
)

LOG_TAIL_LINES = 40
LOG_TAIL_BYTES = 4096
_ANSI = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")


def redact_secrets(value: str) -> str:
    """`value` without credentials, tokens, passwords or e-mail addresses."""
    for pattern, replacement in SECRET_SCRUBBERS:
        value = pattern.sub(replacement, value)
    return value


def redacted_log_tail(
    logs: str,
    *,
    max_lines: int = LOG_TAIL_LINES,
    max_bytes: int = LOG_TAIL_BYTES,
) -> str:
    """The newest lines of `logs`, redacted, within `max_lines` and `max_bytes`.

    Whole lines are kept; a single line longer than the budget keeps its end."""
    text = redact_secrets(_ANSI.sub("", logs.replace("\r\n", "\n")))
    lines = [line for line in text.split("\n") if line.strip()][-max_lines:]
    kept: list[str] = []
    size = 0
    for line in reversed(lines):
        cost = len(line.encode("utf-8")) + (1 if kept else 0)
        if size + cost > max_bytes:
            if not kept:
                encoded = line.encode("utf-8")[-max_bytes:]
                kept.append(encoded.decode("utf-8", errors="ignore"))
            break
        kept.append(line)
        size += cost
    return "\n".join(reversed(kept))
