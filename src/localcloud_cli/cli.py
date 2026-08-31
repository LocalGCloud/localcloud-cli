from __future__ import annotations

import argparse
import math
import os
import sys
from dataclasses import replace
from pathlib import Path
from typing import TYPE_CHECKING, Any

from . import __version__
from .constants import (
    DEFAULT_DATA_VOLUME,
    DEFAULT_IMAGE,
    DEFAULT_MEMORY,
    DEFAULT_PROJECT,
    DEFAULT_USER,
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
    terminal_width,
    valid_field_paths,
    validate_fields,
)

if TYPE_CHECKING:
    from .config import LocalCloudConfig

ALIAS_HELP = "lc is an alias for localcloud; both commands behave identically."
AGENT_HELP = "Coding agents: run 'localcloud guide' before using LocalCloud."
_RUNTIME_COMMANDS = {
    "start", "restart", "reset", "stop", "status", "logs", "console", "env", "mcp"
}
_PROGRESS_COMMANDS = {
    "doctor", "cleanup", "start", "restart", "reset", "stop", "status", "logs", "console", "env"
}
_PLAIN_PULL_UPDATE_INTERVAL = 2.0
_PULL_DOWNLOAD_STATUSES = {
    "already exists",
    "download complete",
    "downloading",
    "pull complete",
    "pulling fs layer",
    "verifying checksum",
    "waiting",
}


class _ExecutionObserver:
    def __init__(self, reporter: LifecycleReporter, *, debug: bool = False):
        self.reporter = reporter
        self.debug_enabled = debug
        self._seen_lines: set[str] = set()
        self._emitted_history: list[str] = []
        self._pull_image: str | None = None
        self._pull_layers: set[str] = set()
        self._pull_progress: dict[str, tuple[int, int]] = {}
        self._plain_pull_update_at: float | None = None

    def debug(self, message: str) -> None:
        if self.debug_enabled:
            self.reporter.write_line(f"[debug] {message}")

    def warning(self, message: str) -> None:
        line = f"Warning: {message}"
        if self.reporter.enabled:
            self.reporter.write_line(line)
            return
        self.reporter.stream.write(f"{line}\n")
        self.reporter.stream.flush()

    def image_pull(
        self,
        image: str,
        *,
        status: str,
        layer: str | None = None,
        current: int | None = None,
        total: int | None = None,
    ) -> None:
        if image != self._pull_image:
            self._pull_image = image
            self._pull_layers.clear()
            self._pull_progress.clear()
            self._plain_pull_update_at = None

        normalized_status = status.strip().lower()
        if layer is not None:
            self._pull_layers.add(layer)
            if (
                normalized_status == "downloading"
                and current is not None
                and total is not None
                and total > 0
            ):
                self._pull_progress[layer] = (
                    max(0, min(current, total)),
                    total,
                )
            elif normalized_status in {
                "already exists",
                "download complete",
                "pull complete",
            }:
                previous = self._pull_progress.get(layer)
                if previous is not None:
                    self._pull_progress[layer] = (previous[1], previous[1])

        if not self.reporter.capabilities.cursor and layer is not None:
            now = self.reporter.clock()
            if (
                self._plain_pull_update_at is not None
                and now - self._plain_pull_update_at < _PLAIN_PULL_UPDATE_INTERVAL
            ):
                return
            self._plain_pull_update_at = now

        if normalized_status == "contacting registry":
            message = f"Contacting registry for image {image!r}…"
        elif normalized_status == "pull complete" and layer is None:
            message = f"Downloaded image {image!r}…"
        elif self._pull_progress and normalized_status in _PULL_DOWNLOAD_STATUSES:
            downloaded = sum(value[0] for value in self._pull_progress.values())
            download_size = sum(value[1] for value in self._pull_progress.values())
            percent = max(0, min(100, round(downloaded / download_size * 100)))
            layer_count = len(self._pull_layers)
            layer_text = "layer" if layer_count == 1 else "layers"
            message = (
                f"Downloading {percent}% overall · "
                f"{_format_bytes(downloaded)} / {_format_bytes(download_size)} · "
                f"{layer_count} {layer_text} · image {image!r}…"
            )
        else:
            layer_count = len(self._pull_layers)
            layer_text = "layer" if layer_count == 1 else "layers"
            detail = f" · {layer_count} {layer_text}" if layer_count else ""
            message = f"Fetching image {image!r} · {status}{detail}…"
        self.reporter.update(message)

    def config(self, command: str, config: LocalCloudConfig, args: argparse.Namespace) -> None:
        if command not in {
            "start",
            "restart",
            "reset",
            "stop",
            "status",
            "logs",
            "console",
            "env",
        }:
            return
        if command == "start":
            message = (
                "Starting LocalCloud container on data volume: "
                f"{config.data_volume!r}…"
            )
        elif command == "restart":
            message = (
                "Restarting LocalCloud container on data volume: "
                f"{config.data_volume!r}…"
            )
        elif command == "stop":
            message = (
                "Stopping LocalCloud container on data volume: "
                f"{config.data_volume!r}…"
            )
        elif command == "status":
            message = (
                "Checking LocalCloud container on data volume: "
                f"{config.data_volume!r}…"
            )
        elif command == "logs":
            unit = "line" if args.tail == 1 else "lines"
            message = (
                f"Reading {args.tail} recent log {unit} from data volume: "
                f"{config.data_volume!r}…"
            )
        elif command == "console":
            message = (
                f"Opening LocalCloud console for project {config.project!r} "
                f"on data volume: {config.data_volume!r}…"
            )
        elif command == "env":
            message = (
                f"Generating {args.format} SDK configuration for project "
                f"{config.project!r}…"
            )
        elif args.all_projects:
            message = (
                "Resetting all LocalCloud data on data volume: "
                f"{config.data_volume!r}…"
            )
        else:
            message = (
                f"Resetting project {config.project!r} on data volume: "
                f"{config.data_volume!r}…"
            )
        config_source = (
            str(config.config_path)
            if config.config_path is not None
            else "built-in defaults"
        )
        self.debug(
            f"Command config: command={command} source={config_source!r} "
            f"data_volume={config.data_volume!r} project={config.project!r} "
            f"container={getattr(config, 'container_name', '<unknown>')!r} "
            f"network={getattr(config, 'network_name', '<unknown>')!r} "
            f"image={getattr(config, 'image', '<unknown>')!r} "
            f"dry_run={bool(getattr(args, 'dry_run', False))} "
            f"pull={bool(getattr(args, 'pull', False))}"
        )
        if getattr(args, "dry_run", False):
            return
        if command not in {"reset", "status"}:
            self.reporter.update(message)
            return
        services: str | tuple[str, ...] = config.services or "default"
        if command == "status":
            heading = "Checking LocalCloud status"
        elif args.all_projects:
            heading = "Resetting all LocalCloud data"
        else:
            heading = "Resetting project data"
        panel = PanelContext(
            data_volume=config.data_volume,
            project=config.project,
            user=config.user,
            services=services,
            data=config.data,
            config=str(config.config_path) if config.config_path is not None else None,
            heading=heading,
        )
        self.reporter.update(message, panel)

    def starting(self, config: LocalCloudConfig) -> None:
        self.reporter.update(
            "Starting LocalCloud container on data volume: "
            f"{config.data_volume!r}…"
        )

    def doctor(self, args: argparse.Namespace) -> None:
        from .config import load_config

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
            heading="Checking LocalCloud setup",
        )
        self.reporter.update("Checking Docker and LocalCloud state…", panel)

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
        failure_message = _result_failure_message(args, result)
        if reports_progress:
            if failure_message is None:
                reporter.succeed(_success_message(args, result))
            else:
                reporter.fail(failure_message)
        _print_result(args, result, fields)
        return 1 if failure_message is not None else 0
    except HostError as error:
        if reports_progress:
            reporter.fail(error.message)
        _print_error(args, error)
        return 2
    except KeyboardInterrupt:
        if args.command == "mcp":
            if terminal_capabilities(sys.stderr).interactive:
                print("MCP connection closed.", file=sys.stderr, flush=True)
            # The MCP SDK can leave its stdin worker blocked after cancellation.
            # A normal return then hangs in interpreter thread shutdown.
            os._exit(130)
        if reports_progress:
            reporter.fail("LocalCloud command interrupted")
        raise
    except SystemExit:
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
        if args.command in {"start", "restart"} and getattr(args, "debug", False):
            config = replace(
                config,
                environment={
                    **config.environment,
                    "LOCALCLOUD_LOG_LEVEL": "DEBUG",
                    "LOCALCLOUD_STARTUP_METRICS": "true",
                },
            )
        if observer is not None:
            observer.config(args.command, config, args)
        if args.command == "start":
            return controller.start(
                config,
                pull=args.pull,
                ensure_project=args.project_id is not None,
                observer=observer,
                tail=args.tail,
                dry_run=args.dry_run,
            )
        if args.command == "restart":
            return controller.restart(
                config,
                pull=args.pull,
                ensure_project=args.project_id is not None,
                observer=observer,
                tail=args.tail,
                dry_run=args.dry_run,
            )
        if args.command == "reset":
            return controller.reset(
                config,
                all_projects=args.all_projects,
                observer=observer,
                dry_run=args.dry_run,
            )
        if args.command == "stop":
            return controller.stop(
                config,
                observer=observer,
                dry_run=args.dry_run,
            )
        if args.command == "status":
            return controller.status(config)
        if args.command == "logs":
            return controller.logs(config, tail=args.tail)
        if args.command == "mcp":
            from .mcp_stdio import run

            return run(config, connect_timeout=args.connect_timeout)
        target = controller.target(config)
        if args.command == "console":
            import webbrowser
            from urllib.parse import urlencode

            connect_url = target.get("connect_url") or target["url"]
            url = f"{connect_url}?{urlencode({'project': config.project, 'user': config.user})}"
            details = {
                "data_volume": config.data_volume,
                "project": config.project,
                "user": config.user,
                "url": url,
            }
            try:
                opened = webbrowser.open(url)
            except Exception as error:
                raise HostError(
                    "console_open_failed",
                    "Could not open the LocalCloud console automatically; open the URL manually",
                    {**details, "cause": str(error)},
                ) from error
            if not opened:
                raise HostError(
                    "console_open_failed",
                    "Could not open the LocalCloud console automatically; open the URL manually",
                    details,
                )
            return {"status": "opened", **details}
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
    from .config import HostPaths, load_active_runtime, load_config

    explicit_value = getattr(args, "config", None)
    explicit = Path(explicit_value) if explicit_value is not None else None
    overrides = {
        "directory": Path.cwd(),
        "data_volume": getattr(args, "data_volume", None),
        "project": getattr(args, "project_id", None),
        "user": getattr(args, "user", None),
        "container_name": getattr(args, "container_name", None),
        "network_name": getattr(args, "network_name", None),
        "tls": getattr(args, "tls", None),
        "memory": getattr(args, "memory", None),
        "image": getattr(args, "image", None),
        "services": getattr(args, "services", None),
        "skip_validation": getattr(args, "skip_config_validation", False),
        "strict_port_validation": getattr(args, "strict_port_validation", False),
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
    capabilities = terminal_capabilities(sys.stdout)
    color = capabilities.color
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
    rendered = render_summary(
        command,
        result,
        fields,
        color=color,
        width=terminal_width(sys.stdout) if capabilities.interactive else None,
    )
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
    command = args.command
    if command == "doctor":
        return "Checking Docker and LocalCloud state…"
    if command == "cleanup":
        return (
            "Checking LocalCloud cleanup candidates…"
            if args.dry_run
            else "Cleaning up LocalCloud state…"
        )
    if command in {"start", "restart", "reset", "stop"} and getattr(
        args, "dry_run", False
    ):
        return f"Planning LocalCloud {command} without making changes…"
    if command == "start":
        return "Preparing to start LocalCloud…"
    if command == "restart":
        return "Preparing to restart LocalCloud…"
    if command == "reset":
        return (
            "Preparing to reset all LocalCloud data…"
            if args.all_projects
            else "Preparing to reset project data…"
        )
    if command == "stop":
        return "Preparing to stop LocalCloud…"
    if command == "status":
        return "Preparing to check LocalCloud status…"
    if command == "logs":
        return "Preparing to read LocalCloud logs…"
    if command == "console":
        return "Preparing to open the LocalCloud console…"
    if command == "env":
        return f"Preparing {args.format} SDK configuration…"
    return f"Running LocalCloud {command}…"


def _success_message(args: argparse.Namespace, result: Any) -> str:
    command = args.command
    status = result.get("status") if isinstance(result, dict) else None
    if command in {"start", "restart", "reset", "stop"} and getattr(
        args, "dry_run", False
    ):
        return f"LocalCloud {command} dry-run completed"
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
        if status == "not_created":
            data_volume = result.get("data_volume")
            if data_volume:
                return (
                    "No LocalCloud container exists on data volume: "
                    f"{data_volume!r}"
                )
            return "No LocalCloud container exists for the selected data volume"
        if status in {"running", "stopped", "unhealthy"}:
            return f"LocalCloud container is {status}"
        return "LocalCloud status checked"
    if command == "logs":
        return "LocalCloud logs loaded"
    if command == "console":
        return "LocalCloud console opened"
    if command == "env":
        return "SDK configuration generated"
    return f"LocalCloud {command} completed"


def _result_failure_message(
    args: argparse.Namespace, result: Any
) -> str | None:
    if (
        args.command == "cleanup"
        and isinstance(result, dict)
        and result.get("status") == "partial"
    ):
        return "LocalCloud cleanup completed with failures"
    return None


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
    _add_output_options(doctor, fields=True, command_name="doctor")
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
    _add_output_options(cleanup, fields=False)

    lifecycle_help = {
        "start": "Start the runtime and prepare a project only when --project-id is explicit",
        "restart": "Restart the runtime, optionally prepare --project-id, and reapply volatile seed data",
        "reset": "Reset the selected project (use --all-projects for manual full-recreate steps)",
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
        _add_output_options(command, fields=True, command_name=name)
        command.add_argument(
            "--dry-run",
            action="store_true",
            help="Print the planned Docker and LocalCloud mutations without making changes",
        )
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
        command.add_argument(
            "--strict-port-validation",
            action="store_true",
            help=(
                "Fail before Docker mutation when image EXPOSE metadata differs "
                "from the canonical LocalCloud capability set (default: warn and continue)"
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
            command.add_argument(
                "--tls",
                action=argparse.BooleanOptionalAction,
                default=None,
                help=(
                    "Enable TLS on the LocalCloud runtime (default: disabled). "
                    "Pass --tls to enable or --no-tls to override an enabled "
                    "host.environment.LOCALCLOUD_TLS_ENABLED value"
                ),
            )
            command.add_argument(
                "--memory",
                default=None,
                metavar="LIMIT",
                help=(
                    f"Docker memory limit, e.g. 4g or 512m (default: {DEFAULT_MEMORY}). "
                    "Overrides host.memory in localcloud.yaml when set"
                ),
            )
            command.add_argument(
                "--image",
                default=None,
                metavar="IMAGE",
                help=(
                    f"Docker image to run (default: {DEFAULT_IMAGE}). Overrides "
                    "host.image in localcloud.yaml and LOCALCLOUD_IMAGE when set"
                ),
            )
            command.add_argument(
                "--services",
                default=None,
                type=_services_argument,
                metavar="ID[,ID...]|default",
                help=(
                    "Comma-separated service IDs to enable, or 'default' to reset "
                    "to the built-in set. Overrides services.enabled in "
                    "localcloud.yaml when set"
                ),
            )
        if name == "reset":
            command.add_argument(
                "--all-projects",
                action="store_true",
                help="Print the manual steps to recreate all data on the volume (localcloud never deletes a data volume itself)",
            )

    stop = commands.add_parser(
        "stop",
        help="Stop the selected runtime without deleting persistent data",
        description="Stop the selected runtime without deleting persistent data.",
    )
    _add_data_volume(stop)
    _add_output_options(stop, fields=True, command_name="stop")
    stop.add_argument(
        "--dry-run",
        action="store_true",
        help="Print the planned Docker mutation without making changes",
    )

    status = commands.add_parser(
        "status",
        help="Show runtime health, ownership, and Docker details",
        description="Show runtime health, ownership, and Docker details.",
    )
    _add_data_volume(status)
    _add_output_options(status, fields=True, command_name="status")

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
    _add_output_options(console, fields=False)

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
    _add_debug_option(env_command)

    mcp = commands.add_parser(
        "mcp",
        help="Run the stdio MCP bridge for the selected runtime",
        description="Run the stdio MCP bridge for the selected runtime.",
    )
    _add_context(mcp)
    mcp.add_argument(
        "--connect-timeout",
        type=_positive_seconds,
        default=10.0,
        metavar="SECONDS",
        help=(
            "Maximum seconds to wait for the LocalCloud MCP endpoint "
            "(default: 10)"
        ),
    )
    _add_debug_option(mcp)
    return parser


def _add_output_options(
    parser: argparse.ArgumentParser,
    *,
    fields: bool,
    command_name: str | None = None,
) -> None:
    if fields:
        if command_name is None:
            raise ValueError("command_name is required when fields are enabled")
        valid_paths = ", ".join(valid_field_paths(command_name))
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
            help=(
                "Add comma-separated JSON paths to the default summary. "
                f"Valid paths: {valid_paths}"
            ),
        )
    else:
        parser.add_argument(
            "--verbose",
            action="store_true",
            help="Print the complete JSON result instead of the command payload",
        )
    _add_debug_option(parser)


def _add_debug_option(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--debug",
        action="store_true",
        help="Show copyable Docker commands, execution details, and readiness diagnostics",
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

def _positive_seconds(value: str) -> float:
    try:
        parsed = float(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError(
            f"must be a valid number of seconds: {value!r}"
        ) from error
    if not math.isfinite(parsed) or parsed <= 0:
        raise argparse.ArgumentTypeError(
            "must be a finite number greater than zero"
        )
    return parsed


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


def _services_argument(value: str) -> list[str] | str:
    if value.strip().lower() == "default":
        return "default"
    services = [item.strip() for item in value.split(",") if item.strip()]
    if not services:
        raise argparse.ArgumentTypeError(
            "must be 'default' or a comma-separated list of service IDs"
        )
    return services


if __name__ == "__main__":
    raise SystemExit(main())
