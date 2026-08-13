from __future__ import annotations

import argparse
import sys
import webbrowser
from pathlib import Path
from typing import Any
from urllib.parse import urlencode

from . import __version__
from .config import DEFAULT_INSTANCE, LocalCloudConfig, load_config, validate_instance
from .errors import HostError
from .output import (
    LifecycleReporter,
    PanelContext,
    parse_fields,
    render_error,
    render_json,
    render_summary,
    terminal_capabilities,
    validate_fields,
)

AGENT_HELP = "Coding agents: run 'localcloud guide' before using LocalCloud."
_CONFIG_COMMANDS = {"start", "restart", "reset", "console", "env", "mcp"}
_PROGRESS_COMMANDS = {"doctor", "start", "restart", "reset", "stop", "status", "logs", "console", "env"}


class _ExecutionObserver:
    def __init__(self, reporter: LifecycleReporter):
        self.reporter = reporter

    def config(self, command: str, config: LocalCloudConfig, args: argparse.Namespace) -> None:
        if command not in {"start", "restart", "reset"}:
            return
        if command == "reset":
            action = (
                "Resetting all data for"
                if args.all_projects
                else "Resetting project data in"
            )
        else:
            action = "Starting" if command == "start" else "Restarting"
        message = (
            f"{action} instance {config.instance!r} "
            f"for project {config.project!r}…"
        )
        if command == "reset":
            self.reporter.update(message)
            return
        services: str | tuple[str, ...] = config.services or "default"
        panel = PanelContext(
            instance=config.instance,
            project=config.project,
            user=config.user,
            services=services,
            data=config.data,
            config=str(config.config_path) if config.config_path is not None else None,
        )
        self.reporter.update(message, panel)


def main(argv: list[str] | None = None) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    try:
        fields = parse_fields(getattr(args, "fields", None))
        validate_fields(args.command, fields)
    except (HostError, ValueError) as error:
        host_error = (
            error
            if isinstance(error, HostError)
            else HostError("invalid_output_field", str(error))
        )
        _print_error(args, host_error)
        return 2

    verbose = bool(getattr(args, "verbose", False))
    reporter = LifecycleReporter(verbose=verbose)
    reports_progress = args.command in _PROGRESS_COMMANDS
    if reports_progress:
        reporter.start(_initial_task(args))
    try:
        result = _execute(args, observer=_ExecutionObserver(reporter))
        if reports_progress:
            reporter.succeed(_success_message(args, result))
        _print_result(args, result, fields)
        return 0
    except HostError as error:
        if reports_progress:
            reporter.fail(error.message)
        _print_error(args, error)
        return 2
    except BaseException:
        if reports_progress:
            reporter.fail("LocalCloud command interrupted")
        raise
    finally:
        reporter.close()


def _execute(args: argparse.Namespace, observer: _ExecutionObserver | None = None) -> Any:
    if args.command == "guide":
        from .agent_guide import render_agent_guide

        return render_agent_guide()

    from .controller import Controller

    controller = Controller()
    if args.command == "doctor":
        return controller.doctor()

    if args.command in _CONFIG_COMMANDS:
        config = _command_config(controller, args)
        if observer is not None:
            observer.config(args.command, config, args)
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


def _command_config(controller: Any, args: argparse.Namespace) -> LocalCloudConfig:
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


def _print_result(args: argparse.Namespace, result: Any, fields: list[str]) -> None:
    if result is None:
        return
    command = args.command
    if command == "logs" and not args.verbose:
        value = result.get("logs", "") if isinstance(result, dict) else result
        _print_native(str(value))
        return
    color = terminal_capabilities(sys.stdout).color
    if command == "env":
        if isinstance(result, str):
            _print_native(result)
        else:
            print(render_json(result, color=color))
        return
    if isinstance(result, str):
        _print_native(result)
        return
    if getattr(args, "verbose", False):
        print(render_json(result, color=color))
        return
    rendered = render_summary(command, result, fields, color=color)
    if rendered:
        print(rendered)


def _print_native(value: str) -> None:
    sys.stdout.write(value)
    if value and not value.endswith("\n"):
        sys.stdout.write("\n")


def _print_error(args: argparse.Namespace, error: HostError) -> None:
    color = terminal_capabilities(sys.stderr).color
    if getattr(args, "verbose", False):
        print(render_json(error.to_dict(), color=color), file=sys.stderr)
    else:
        print(render_error(error, color=color), file=sys.stderr)


def _initial_task(args: argparse.Namespace) -> str:
    instance = getattr(args, "instance", None) or DEFAULT_INSTANCE
    command = args.command
    if command == "doctor":
        return "Inspecting Docker and legacy LocalCloud state…"
    if command in {"start", "restart"}:
        return f"Preparing LocalCloud {command}…"
    if command == "reset":
        scope = "all data" if args.all_projects else "the selected project"
        return f"Preparing to reset {scope}…"
    if command == "stop":
        return f"Stopping instance {instance!r}…"
    if command == "status":
        return f"Inspecting instance {instance!r}…"
    if command == "logs":
        return f"Reading {args.tail} recent log lines from instance {instance!r}…"
    if command == "console":
        return "Locating the selected project console…"
    if command == "env":
        return f"Generating {args.format} SDK configuration…"
    return f"Running LocalCloud {command}…"


def _success_message(args: argparse.Namespace, result: Any) -> str:
    command = args.command
    status = result.get("status") if isinstance(result, dict) else None
    if command in {"start", "restart"}:
        return "LocalCloud is ready"
    if command == "doctor":
        return "LocalCloud checks completed"
    if command == "reset":
        return "LocalCloud data reset completed"
    if command == "stop":
        return "LocalCloud instance stopped" if status != "not_running" else "LocalCloud instance was not running"
    if command == "status":
        return f"LocalCloud instance is {status or 'inspected'}"
    if command == "logs":
        return "LocalCloud logs loaded"
    if command == "console":
        return "LocalCloud console opened"
    if command == "env":
        return "SDK configuration generated"
    return f"LocalCloud {command} completed"


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="localcloud",
        description=(
            "Run Google Cloud-compatible services locally in Docker. Manage "
            "LocalCloud instances, project context, SDK environments, and the MCP bridge."
        ),
        epilog=AGENT_HELP,
    )
    parser.add_argument(
        "--version",
        action="version",
        version=f"%(prog)s {__version__}",
    )
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser(
        "guide",
        help="Print guidance for coding agents using LocalCloud",
        description="Print guidance for coding agents using LocalCloud.",
    )
    doctor = commands.add_parser(
        "doctor",
        help="Check Docker access and detect legacy LocalCloud state",
        description="Check Docker access and detect legacy LocalCloud state.",
    )
    _add_output_options(doctor, fields=True)

    lifecycle_help = {
        "start": "Start an instance and prepare the selected project",
        "restart": "Restart an instance and reapply volatile seed data",
        "reset": "Reset the selected project, or recreate all instance data",
    }
    for name, help_text in lifecycle_help.items():
        command = commands.add_parser(name, help=help_text, description=f"{help_text}.")
        command.add_argument(
            "config",
            metavar="CONFIG",
            nargs="?",
            help=(
                "Configuration file. Otherwise use ./localcloud.yaml, the instance's "
                "remembered file, or built-in defaults"
            ),
        )
        _add_context(command)
        _add_resource_names(command)
        _add_output_options(command, fields=True)
        if name == "reset":
            command.add_argument(
                "--all-projects",
                action="store_true",
                help="Delete and recreate all instance data instead of only the selected project",
            )

    stop = commands.add_parser(
        "stop",
        help="Stop an instance without deleting persistent data",
        description="Stop an instance without deleting persistent data.",
    )
    _add_instance(stop)
    _add_output_options(stop, fields=True)

    status = commands.add_parser(
        "status",
        help="Show instance health and runtime details",
        description="Show instance health and runtime details.",
    )
    _add_instance(status)
    _add_output_options(status, fields=True)

    logs = commands.add_parser(
        "logs",
        help="Print recent logs from an instance",
        description="Print recent logs from an instance.",
    )
    _add_instance(logs)
    logs.add_argument(
        "--tail",
        type=_non_negative_int,
        default=200,
        metavar="LINES",
        help="Number of recent lines to print (default: 200)",
    )
    _add_output_options(logs, fields=False)

    console = commands.add_parser(
        "console",
        help="Open the web console for the selected project and user",
        description="Open the web console for the selected project and user.",
    )
    _add_context(console)
    _add_output_options(console, fields=True)

    env_command = commands.add_parser(
        "env",
        help="Print SDK configuration for the selected project",
        description="Print SDK configuration for the selected project.",
    )
    _add_context(env_command)
    env_command.add_argument(
        "--format",
        choices=("shell", "json", "terraform", "docker-compose"),
        default="shell",
        help="Output format (default: shell)",
    )

    mcp = commands.add_parser(
        "mcp",
        help="Run the stdio MCP bridge for a running instance",
        description="Run the stdio MCP bridge for a running instance.",
    )
    _add_context(mcp)
    return parser


def _add_output_options(parser: argparse.ArgumentParser, *, fields: bool) -> None:
    if fields:
        group = parser.add_mutually_exclusive_group()
        group.add_argument(
            "--verbose",
            action="store_true",
            help="Print the complete JSON result instead of the concise summary",
        )
        group.add_argument(
            "--fields",
            action="append",
            metavar="PATH[,PATH...]",
            help="Add comma-separated JSON paths to the default summary",
        )
    else:
        parser.add_argument(
            "--verbose",
            action="store_true",
            help="Print the complete JSON result instead of the command payload",
        )


def _add_instance(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--instance",
        default=None,
        metavar="NAME",
        help=f"Docker-backed LocalCloud instance name (default: {DEFAULT_INSTANCE})",
    )


def _add_context(parser: argparse.ArgumentParser) -> None:
    _add_instance(parser)
    parser.add_argument(
        "--project-id",
        default=None,
        metavar="ID",
        help="Project to create or select within the instance",
    )
    parser.add_argument(
        "--user",
        default=None,
        metavar="NAME",
        help="Caller identity sent to LocalCloud services",
    )


def _add_resource_names(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--container-name",
        default=None,
        metavar="NAME",
        help="Override the managed Docker container name",
    )
    parser.add_argument(
        "--network-name",
        default=None,
        metavar="NAME",
        help="Override the managed Docker network name",
    )
    parser.add_argument(
        "--volume-name",
        default=None,
        metavar="NAME",
        help="Override the managed Docker volume name",
    )


def _non_negative_int(value: str) -> int:
    parsed = int(value)
    if parsed < 0:
        raise argparse.ArgumentTypeError("must be zero or greater")
    return parsed


if __name__ == "__main__":
    raise SystemExit(main())
