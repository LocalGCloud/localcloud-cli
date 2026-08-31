"""Fast public entry point for lightweight LocalCloud commands."""

from __future__ import annotations

import sys

from . import version_string


def main(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    if args == ["--version"]:
        print(version_string())
        return 0
    if args == ["guide"]:
        try:
            from .agent_guide import render_agent_guide

            guide = render_agent_guide()
            sys.stdout.write(guide)
            if guide and not guide.endswith("\n"):
                sys.stdout.write("\n")
        except Exception:
            # Preserve the CLI's structured error handling on the cold failure path.
            pass
        else:
            return 0

    from .cli import main as cli_main

    return cli_main(args)
