from __future__ import annotations

import argparse
import sys
import webbrowser
from pathlib import Path
from typing import Any
from urllib.parse import urlencode
import inspect

from . import __version__
from .config import (
    DEFAULT_DATA_VOLUME,
    DEFAULT_PROJECT,
    DEFAULT_USER,
    HostPaths,
    LocalCloudConfig,
    load_active_runtime,
    load_config,
)
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

ALIAS_HELP = "lc is an alias for localcloud; both commands behave identically."
AGENT_HELP = "Coding agents: run 'localcloud guide' before using LocalCloud."
_RUNTIME_COMMANDS = {
    "start", "restart", "reset", "stop", "status", "logs", "console", "env", "mcp"
}
_PROGRESS_COMMANDS = {
    "doctor", "cleanup", "start", "restart", "reset", "stop", "status", "logs", "console", "env"
}
_PLAIN_PULL_UPDATE_INTERVAL = 2.0


class _ExecutionObserver:
    def __init__(self, reporter: LifecycleReporter, *, debug: bool = False):
        self.reporter = reporter
        self.debug_enabled = debug
        self._seen_lines: set[str] = set()
        self._emitted_history: list[str] = []
        self._plain_pull_image: str | None = None
        self._plain_pull_update_at: float | None = None

    def debug(self, message: str) -> None:
        if self.debug_enabled:
            self.reporter.write_line(f"[debug] {message}")

    def image_pull(
        self,
        image: str,
        *,
        status: str,
        layer: str | None = None,
        current: int | None = None,
        total: int | None = None,
    ) -> None:
        if not self.reporter.capabilities.cursor and layer is not None:
            now = self.reporter.clock()
            if image != self._plain_pull_image:
                self._plain_pull_image = image
                self._plain_pull_update_at = None
            if (
                self._plain_pull_update_at is not None
                and now - self._plain_pull_update_at < _PLAIN_PULL_UPDATE_INTERVAL
            ):
                return
            self._plain_pull_update_at = now

        parts = ["Fetching"]
        if status:
            parts.append(status)
        if current is not None and total:
            percent = max(0, min(100, round(current / total * 100)))
            parts.extend(
                (
                    f"{percent}%",
                    f"{_format_bytes(current)} / {_format_bytes(total)}",
                )
            )
        if layer:
            parts.append(f"layer {layer[:12]}")
        parts.append(f"image {image!r}")
        self.reporter.update(f"{' · '.join(parts)}…")

    def config(self, command: str, config: LocalCloudConfig, args: argparse.Namespace) -> None:
        if command not in {"start", "restart", "reset", "stop", "status"}:
            return
        if command == "start":
            self.reporter.update(
                f"Checking data volume {config.data_volume!r} for project {config.project!r}…"
            )
            return
        if command == "reset":
            action = (
                "Resetting all data for"
                if args.all_projects
                else "Resetting project data in"
            )
        elif command == "stop":
            action = "Stopping"
        elif command == "status":
            action = "Inspecting"
        else:
            action = "Restarting"
        message = (
            f"{action} data volume {config.data_volume!r} "
            f"for project {config.project!r}…"
        )
        services: str | tuple[str, ...] = config.services or "default"
        panel = PanelContext(
            data_volume=config.data_volume,
            project=config.project,
            user=config.user,
            services=services,
            data=config.data,
            config=str(config.config_path) if config.config_path is not None else None,
        )
        self.reporter.update(message, panel)

    def starting(self, config: LocalCloudConfig) -> None:
        message = (
            f"Starting data volume {config.data_volume!r} "
            f"for project {config.project!r}…"
        )
        services: str | tuple[str, ...] = config.services or "default"
        panel = PanelContext(
            data_volume=config.data_volume,
            project=config.project,
            user=config.user,
            services=services,
            data=config.data,
            config=str(config.config_path) if config.config_path is not None else None,
        )
        self.reporter.update(message, panel)

    def doctor(self, args: argparse.Namespace) -> None:
        data_volume = getattr(args, "data_volume", None) or DEFAULT_DATA_VOLUME
        project = getattr(args, "project_id", None) or DEFAULT_PROJECT
        user = getattr(args, "user", None) or DEFAULT_USER
        services: str | tuple[str, ...] = "default"
        config_path: str | None = None
        try:
            local_config = load_config(directory=Path.cwd())
        except HostError:
            local_config = None
        if local_config is not None and local_config.config_path is not None:
            data_volume = local_config.data_volume
            project = local_config.project
            user = local_config.user
            services = local_config.services or "default"
            config_path = str(local_config.config_path)
        panel = PanelContext(
            data_volume=data_volume,
            project=project,
            user=user,
            services=services,
            data="persistent",
            config=config_path,
        )
        self.reporter.update("Inspecting Docker and legacy LocalCloud state…", panel)

    def runtime_logs(self, logs: str) -> None:
        if not logs or logs.startswith("<logs unavailable"):
            return
        batch = [line.rstrip("\r\n") for line in logs.splitlines() if line.rstrip("\r\n")]
        if not batch:
            return
        if not self._emitted_history:
            new_lines = batch
        else:
            max_k = min(len(self._emitted_history), len(batch))
            matched = False
            for k in range(max_k, 0, -1):
                if self._emitted_history[-k:] == batch[:k]:
                    new_lines = batch[k:]
                    matched = True
                    break
            if not matched:
                new_lines = [l for l in batch if l not in self._seen_lines]
        for line in new_lines:
            self._seen_lines.add(line)
            self._emitted_history.append(line)
            self.reporter.write_line(line)


def _format_bytes(value: int) -> str:
    amount = float(max(0, value))
    units = ("B", "KiB", "MiB", "GiB", "TiB")
    unit = units[0]
    for unit in units:
        if amount < 1024 or unit == units[-1]:
            break
        amount /= 1024
    precision = 0 if unit == "B" else 1
    return f"{amount:.{precision}f} {unit}"


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
    debug = bool(getattr(args, "debug", False))
    reporter = LifecycleReporter(verbose=verbose)
    reports_progress = args.command in _PROGRESS_COMMANDS
    if reports_progress:
        reporter.start(_initial_task(args))
    try:
        result = _execute(args, observer=_ExecutionObserver(reporter, debug=debug))
        if reports_progress:
            reporter.succeed(_success_message(args, result))
        _print_result(args, result, fields)
        return 0
    except HostError as error:
        if reports_progress:
            reporter.fail(error.message)
        _print_error(args, error)
        return 2
    except (KeyboardInterrupt, SystemExit):
        if reports_progress:
            reporter.fail("LocalCloud command interrupted")
        raise
    except Exception as error:
        # Anything that isn't a KeyboardInterrupt/SystemExit and wasn't
        # already raised as a HostError is an unexpected bug, not a user
        # interruption - give it the same clean-error treatment instead of a
        # raw traceback, and only surface the traceback when --debug is set.
        if reports_progress:
            reporter.fail("LocalCloud command failed unexpectedly")
        _print_error(
            args,
            HostError(
                "unexpected_error",
                f"LocalCloud command failed unexpectedly: {error}",
                {"type": type(error).__name__, "cause": str(error)},
            ),
        )
        if debug:
            raise
        return 1
    finally:
        reporter.close()


def _execute(args: argparse.Namespace, observer: _ExecutionObserver | None = None) -> Any:
    if args.command == "guide":
        from .agent_guide import render_agent_guide

        return render_agent_guide()

    from .controller import Controller

    controller = Controller()
    if args.command == "doctor":
        if observer is not None:
            observer.doctor(args)
        return controller.doctor()
    if args.command == "cleanup":
        return controller.cleanup(dry_run=args.dry_run)

    if args.command in _RUNTIME_COMMANDS:
        config = _command_config(controller, args)
        if observer is not None:
            observer.config(args.command, config, args)
        if args.command in {"start", "restart"}:
            command = getattr(controller, args.command)
            kwargs: dict[str, Any] = {}
            signature = inspect.signature(command)
            if "pull" in signature.parameters and getattr(args, "pull", False):
                kwargs["pull"] = True
            if "tail" in signature.parameters and hasattr(args, "tail"):
                kwargs["tail"] = args.tail
            if observer is not None and "observer" in signature.parameters:
                kwargs["observer"] = observer
            return command(config, **kwargs)
        if args.command == "reset":
            return controller.reset(config, all_projects=args.all_projects)
        if args.command == "stop":
            return controller.stop(config)
        if args.command == "status":
            return controller.status(config)
        if args.command == "logs":
            return controller.logs(config, tail=args.tail)
        if args.command == "mcp":
            from .mcp_stdio import run

            return run(config)
        target = controller.target(config)
        if args.command == "console":
            url = f"{target['url']}?{urlencode({'project': config.project, 'user': config.user})}"
            webbrowser.open(url)
            return {
                "status": "opened",
                "data_volume": config.data_volume,
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
    raise HostError(
        "unknown_command",
        "Unsupported LocalCloud command",
        {"command": args.command},
    )


def _command_config(controller: Any, args: argparse.Namespace) -> LocalCloudConfig:
    explicit_value = getattr(args, "config", None)
    explicit = Path(explicit_value) if explicit_value is not None else None
    overrides = {
        "directory": Path.cwd(),
        "data_volume": getattr(args, "data_volume", None),
        "project": getattr(args, "project_id", None),
        "user": getattr(args, "user", None),
        "container_name": getattr(args, "container_name", None),
        "network_name": getattr(args, "network_name", None),
        "skip_validation": getattr(args, "skip_config_validation", False),
    }
    paths = getattr(controller, "paths", None) or HostPaths.from_environment()
    active_diagnostics: list[dict[str, Any]] = []
    active = load_active_runtime(paths, active_diagnostics)
    snapshot = {
        "paths": paths,
        "active_runtime": active,
        "active_diagnostics": tuple(active_diagnostics),
    }
    preliminary = load_config(explicit=explicit, **overrides, **snapshot)
    if preliminary.config_path is not None:
        return preliminary

    remembered = controller.remembered_config(preliminary)
    implicit_active = (
        explicit is None
        and overrides["data_volume"] is None
        and active is not None
    )
    if remembered is None and implicit_active:
        snapshot["active_runtime"] = None
        preliminary = load_config(explicit=explicit, **overrides, **snapshot)
        remembered = controller.remembered_config(preliminary)
    if remembered is None:
        return preliminary
    return load_config(
        explicit=explicit,
        remembered=remembered,
        **overrides,
        **snapshot,
    )


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
    data_volume = getattr(args, "data_volume", None) or DEFAULT_DATA_VOLUME
    command = args.command
    if command == "doctor":
        return "Inspecting Docker and legacy LocalCloud state…"
    if command == "cleanup":
        return "Checking for cleanup candidates…" if args.dry_run else "Cleaning up LocalCloud state…"
    if command in {"start", "restart"}:
        return f"Preparing LocalCloud {command}…"
    if command == "reset":
        scope = "all data" if args.all_projects else "the selected project"
        return f"Preparing to reset {scope}…"
    if command == "stop":
        return f"Stopping runtime on data volume {data_volume!r}…"
    if command == "status":
        return f"Inspecting runtime on data volume {data_volume!r}…"
    if command == "logs":
        return f"Reading {args.tail} recent log lines from data volume {data_volume!r}…"
    if command == "console":
        return "Locating the selected project console…"
    if command == "env":
        return f"Generating {args.format} SDK configuration…"
    return f"Running LocalCloud {command}…"


def _success_message(args: argparse.Namespace, result: Any) -> str:
    command = args.command
    status = result.get("status") if isinstance(result, dict) else None
    if command == "start":
        if status == "already_running":
            return (
                "LocalCloud runtime is already running; run 'localcloud stop' "
                "first if you want to start again, or 'localcloud restart' to restart"
            )
        return "LocalCloud is ready"
    if command == "restart":
        return "LocalCloud is ready"
    if command == "doctor":
        return "LocalCloud checks completed"
    if command == "cleanup":
        return (
            "LocalCloud cleanup dry-run completed"
            if result.get("dry_run")
            else "LocalCloud cleanup completed"
        )
    if command == "reset":
        return "LocalCloud data reset completed"
    if command == "stop":
        return "LocalCloud runtime stopped" if status != "not_running" else "LocalCloud runtime was not running"
    if command == "status":
        return f"LocalCloud runtime is {status or 'inspected'}"
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
            "LocalCloud runtimes by Docker data volume, project context, SDK "
            "environments, and the MCP bridge."
        ),
        epilog=f"{ALIAS_HELP} {AGENT_HELP}",
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
    cleanup = commands.add_parser(
        "cleanup",
        help="Remove malformed LocalCloud Docker resources, stale runtime state, and legacy host files",
        description=(
            "Remove malformed LocalCloud Docker resources, stale runtime state, "
            "and legacy host files. Performs removal by default; use --dry-run "
            "to inspect without removing."
        ),
    )
    cleanup.add_argument(
        "--dry-run",
        action="store_true",
        help="Inspect what would be removed without deleting resources",
    )
    _add_output_options(cleanup, fields=True)

    lifecycle_help = {
        "start": "Start the runtime selected by data volume and prepare a project",
        "restart": "Restart the selected runtime and reapply volatile seed data",
        "reset": "Reset the selected project, or recreate all managed runtime data",
    }
    for name, help_text in lifecycle_help.items():
        command = commands.add_parser(name, help=help_text, description=f"{help_text}.")
        command.add_argument(
            "config",
            metavar="CONFIG",
            nargs="?",
            help=(
                "Versioned localcloud.yaml overlay. Otherwise use "
                "LOCALCLOUD_CONFIG, ./localcloud.yaml, the runtime's "
                "remembered file, or built-in defaults"
            ),
        )
        _add_context(command)
        _add_resource_names(command)
        _add_output_options(command, fields=True)
        command.add_argument(
            "--skip-config-validation",
            action="store_true",
            help=(
                "Proceed even if localcloud.yaml fails CLI-side host/context "
                "checks; the file is still passed through unchanged for "
                "LocalCloud to accept or reject. Also settable via "
                "LOCALCLOUD_SKIP_CONFIG_VALIDATION=1. Use only if CLI and "
                "LocalCloud validation disagree."
            ),
        )
        if name in {"start", "restart"}:
            command.add_argument(
                "--pull",
                action=argparse.BooleanOptionalAction,
                default=False,
                help=(
                    "Pull the latest image from the registry before running (default: --no-pull)"
                    if name == "start"
                    else "Pull the latest image from the registry before restarting (default: --no-pull)"
                ),
            )
            command.add_argument(
                "--tail",
                nargs="?",
                const=-1.0,
                default=5.0,
                type=_tail_seconds,
                metavar="SECONDS",
                help=(
                    "Duration in seconds to tail logs after start (default: 5; omit value for continuous streaming)"
                    if name == "start"
                    else "Duration in seconds to tail logs after restart (default: 5; omit value for continuous streaming)"
                ),
            )
        if name == "reset":
            command.add_argument(
                "--all-projects",
                action="store_true",
                help="Delete and recreate all managed runtime data instead of only the selected project",
            )

    stop = commands.add_parser(
        "stop",
        help="Stop the selected runtime without deleting persistent data",
        description="Stop the selected runtime without deleting persistent data.",
    )
    _add_data_volume(stop)
    _add_output_options(stop, fields=True)

    status = commands.add_parser(
        "status",
        help="Show runtime health, ownership, and Docker details",
        description="Show runtime health, ownership, and Docker details.",
    )
    _add_data_volume(status)
    _add_output_options(status, fields=True)

    logs = commands.add_parser(
        "logs",
        help="Print recent logs from the selected runtime",
        description="Print recent logs from the selected runtime.",
    )
    _add_data_volume(logs)
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
    _add_output_options(env_command, fields=True)

    mcp = commands.add_parser(
        "mcp",
        help="Run the stdio MCP bridge for the selected runtime",
        description="Run the stdio MCP bridge for the selected runtime.",
    )
    _add_context(mcp)
    _add_output_options(mcp, fields=False)
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
    parser.add_argument(
        "--debug",
        action="store_true",
        help="Print debug information including Docker commands executed",
    )


def _add_data_volume(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--data-volume",
        default=None,
        metavar="NAME",
        help=(
            "Docker volume mounted at /var/lib/localcloud "
            f"(default: active runtime or {DEFAULT_DATA_VOLUME})"
        ),
    )


def _add_context(parser: argparse.ArgumentParser) -> None:
    _add_data_volume(parser)
    parser.add_argument(
        "--project-id",
        default=None,
        metavar="ID",
        help="Project to create or select within the runtime",
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

def _tail_seconds(value: str) -> float:
    try:
        val = float(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError(
            f"Tail duration must be a valid number of seconds: {value!r}"
        ) from error
    if val < 0:
        raise argparse.ArgumentTypeError("Tail duration in seconds must be zero or greater")
    return val


def _non_negative_int(value: str) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        raise argparse.ArgumentTypeError("must be an integer") from None
    if parsed < 0:
        raise argparse.ArgumentTypeError("must be zero or greater")
    return parsed


if __name__ == "__main__":
    raise SystemExit(main())
