from __future__ import annotations

import argparse
import json
import sys
import webbrowser
from pathlib import Path
from typing import Any
from urllib.parse import urlencode

from .config import DEFAULT_INSTANCE, load_config, validate_instance
from .errors import HostError

AGENT_HELP = "Coding agents: run 'localcloud guide' before using LocalCloud."
_CONFIG_COMMANDS = {"start", "restart", "reset", "console", "env", "mcp"}


def main(argv: list[str] | None = None) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    try:
        result = _execute(args)
        if result is not None:
            if isinstance(result, str):
                print(result)
            else:
                print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    except HostError as error:
        print(json.dumps(error.to_dict(), indent=2, sort_keys=True), file=sys.stderr)
        return 2


def _execute(args: argparse.Namespace) -> Any:
    if args.command == "guide":
        from .agent_guide import render_agent_guide

        return render_agent_guide()

    from .controller import Controller

    controller = Controller()
    if args.command == "doctor":
        return controller.doctor()

    if args.command in _CONFIG_COMMANDS:
        config = _command_config(controller, args)
        if args.command in {"start", "restart"}:
            return getattr(controller, args.command)(config)
        if args.command == "reset":
            return controller.reset(config, all_projects=args.all_projects)
        if args.command == "mcp":
            from .mcp_stdio import run

            return run(config.instance, config.project, config.user)
        target = controller.target(config.instance, config.project, config.user)
        if args.command == "console":
            url = f"{target['url']}?{urlencode({'project': config.project, 'user': config.user})}"
            webbrowser.open(url)
            return {
                "status": "opened",
                "instance": config.instance,
                "project": config.project,
                "user": config.user,
                "url": url,
            }
        if args.command == "env":
            from .endpoints import environment_config

            return environment_config(
                target,
                config.project,
                config.user,
                output_format=args.format,
            )

    instance = validate_instance(args.instance)
    if args.command == "stop":
        return controller.stop(instance)
    if args.command == "status":
        return controller.status(instance)
    if args.command == "logs":
        return controller.logs(instance, tail=args.tail)
    raise HostError(
        "unknown_command", "Unsupported LocalCloud command", {"command": args.command}
    )


def _command_config(controller: Any, args: argparse.Namespace):
    explicit = Path(args.config) if getattr(args, "config", None) is not None else None
    overrides = {
        "directory": Path.cwd(),
        "instance": args.instance,
        "project": args.project_id,
        "user": args.user,
        "container_name": getattr(args, "container_name", None),
        "network_name": getattr(args, "network_name", None),
        "volume_name": getattr(args, "volume_name", None),
    }
    preliminary = load_config(explicit=explicit, **overrides)
    if preliminary.config_path is not None:
        return preliminary
    remembered = controller.remembered_config(preliminary.instance)
    if remembered is None:
        return preliminary
    return load_config(explicit=explicit, remembered=remembered, **overrides)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="localcloud",
        description="Shared LocalCloud instance lifecycle and MCP bridge.",
        epilog=AGENT_HELP,
    )
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("guide", help="Print the coding-agent operating guide")
    commands.add_parser("doctor", help="Diagnose Docker and legacy local state")

    for name in ("start", "restart", "reset"):
        command = commands.add_parser(name, help=f"{name.title()} a LocalCloud instance")
        command.add_argument("config", metavar="CONFIG", nargs="?")
        _add_context(command)
        _add_resource_names(command)
        if name == "reset":
            command.add_argument(
                "--all-projects",
                action="store_true",
                help="Recreate instance data instead of resetting only the selected project",
            )

    for name in ("stop", "status"):
        command = commands.add_parser(name, help=f"{name.title()} a LocalCloud instance")
        _add_instance(command)

    logs = commands.add_parser("logs", help="Read LocalCloud instance logs")
    _add_instance(logs)
    logs.add_argument("--tail", type=int, default=200)

    console = commands.add_parser("console", help="Open the LocalCloud console")
    _add_context(console)

    env_command = commands.add_parser("env", help="Print local SDK configuration")
    _add_context(env_command)
    env_command.add_argument(
        "--format",
        choices=("shell", "json", "terraform", "docker-compose"),
        default="shell",
    )

    mcp = commands.add_parser("mcp", help="Bridge stdio to the running Java MCP")
    _add_context(mcp)
    return parser


def _add_instance(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--instance",
        default=None,
        metavar="NAME",
        help=f"Docker instance name (default: {DEFAULT_INSTANCE})",
    )


def _add_context(parser: argparse.ArgumentParser) -> None:
    _add_instance(parser)
    parser.add_argument("--project-id", default=None, metavar="ID")
    parser.add_argument("--user", default=None, metavar="NAME")


def _add_resource_names(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--container-name", default=None, metavar="NAME")
    parser.add_argument("--network-name", default=None, metavar="NAME")
    parser.add_argument("--volume-name", default=None, metavar="NAME")


if __name__ == "__main__":
    raise SystemExit(main())
