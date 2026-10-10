from __future__ import annotations

import argparse
import math
import os
import shlex
import sys
import textwrap
import time
from dataclasses import replace
from pathlib import Path
from typing import TYPE_CHECKING, Any, Sequence, TextIO

from . import version_string
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
    fix_outcome_lines,
    parse_fields,
    render_error,
    render_findings,
    render_json,
    render_summary,
    terminal_capabilities,
    terminal_width,
    valid_field_paths,
    validate_fields,
)

if TYPE_CHECKING:
    from .config import LocalCloudConfig
    from .docker_runtime import DockerRunPlan, PortMapping
    from .host_checks import Finding
    from .telemetry import Telemetry

ALIAS_HELP = "lc is an alias for localcloud; both commands behave identically."
AGENT_HELP = "Coding agents: run 'localcloud guide' before using LocalCloud."
COMMAND_REQUIRED_NOTE = "Note: One of the <command> is required."
_RUNTIME_COMMANDS = {
    "start", "restart", "reset", "stop", "status", "logs", "console", "env", "mcp"
}
_PROGRESS_COMMANDS = {
    "doctor", "cleanup", "start", "restart", "reset", "stop", "status", "logs", "console", "env"
}
_PLAIN_PULL_UPDATE_INTERVAL = 2.0
# After a fix starts a Docker app (`open -a Docker` returns before the engine is up).
_DOCKER_WAIT_SECONDS = 120.0
_DOCKER_POLL_SECONDS = 2.0
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
    def __init__(
        self,
        reporter: LifecycleReporter,
        *,
        debug: bool = False,
        input_stream: TextIO | None = None,
        telemetry: Telemetry | None = None,
    ):
        self.reporter = reporter
        self.debug_enabled = debug
        self.telemetry = telemetry
        self.input_stream = sys.stdin if input_stream is None else input_stream
        self._seen_lines: set[str] = set()
        self._emitted_history: list[str] = []
        self._pull_image: str | None = None
        self._pull_layers: set[str] = set()
        self._pull_progress: dict[str, tuple[int, int]] = {}
        self._plain_pull_update_at: float | None = None
        # Outcomes of the setup fixes offered during this run.
        self.fixes: list[dict[str, Any]] = []

    def confirm_port_mapping(self, run_plan: DockerRunPlan) -> bool:
        mappings = run_plan.alternative_port_mappings()
        try:
            input_is_interactive = bool(self.input_stream.isatty())
        except (AttributeError, OSError):
            input_is_interactive = False
        if not self.reporter.capabilities.interactive or not input_is_interactive:
            raise HostError(
                "port_mapping_confirmation_required",
                "Canonical LocalCloud host ports are unavailable; rerun interactively or pass --accept-dynamic-ports",
                {
                    "mappings": _port_mapping_details(mappings),
                    "override": "--accept-dynamic-ports",
                },
            )
        lines = [
            "Canonical LocalCloud host ports are unavailable.",
            "Proposed host-to-container mappings:",
            *(
                f"  {host_ip + ':' if host_ip else ''}{host_port} -> {container_port}/{protocol}"
                for host_ip, host_port, container_port, protocol in mappings
            ),
        ]
        answer = self.reporter.prompt(
            lines,
            "Continue with these mappings? [y/N] ",
            input_stream=self.input_stream,
        )
        return answer.lower() in {"y", "yes"}

    def debug(self, message: str) -> None:
        if self.debug_enabled:
            self.reporter.write_line(f"[debug] {message}")

    def can_prompt(self) -> bool:
        """Prompts need a terminal on both ends, and never --verbose (JSON) output."""
        if self.reporter.verbose:
            return False
        try:
            input_is_interactive = bool(self.input_stream.isatty())
        except (AttributeError, OSError):
            input_is_interactive = False
        return self.reporter.capabilities.interactive and input_is_interactive

    def offer_fixes(self, findings: Sequence[Finding]) -> list[dict[str, Any]]:
        """Show each finding's fix and apply the ones the user accepts.

        Callers check can_prompt() first. A fix that can delete Docker data
        needs the typed word 'delete', after the LocalCloud volumes are named.
        """
        from .host_checks import apply_fix, localcloud_volumes

        outcomes: list[dict[str, Any]] = []
        for finding in findings:
            fix = finding.fix
            if fix is None:
                continue
            lines = [
                finding.message,
                *fix.notes,
                "Commands:",
                *(f"  {shlex.join(command)}" for command in fix.commands),
            ]
            question, accepted = f"{fix.summary}? [y/N] ", {"y", "yes"}
            if fix.deletes_data:
                lines.append(
                    "This can delete every Docker image, container and volume in that "
                    "VM, not only LocalCloud's."
                )
                volumes = localcloud_volumes()
                if volumes:
                    lines.append(f"LocalCloud volumes there: {', '.join(volumes)}")
                question, accepted = "Type 'delete' to continue, or press Enter to skip: ", {"delete"}
            answer = self.reporter.prompt(lines, question, input_stream=self.input_stream)
            outcome: dict[str, Any] = {"id": finding.id, "summary": fix.summary}
            if answer.lower() in accepted:
                outcome.update(apply_fix(fix, stream=self.reporter.stream))
            else:
                outcome["status"] = "declined"
            outcomes.append(outcome)
        self.fixes.extend(outcomes)
        return outcomes

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
        if self.telemetry is not None:
            self.telemetry.configure(config)
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

    def stopping(self, config: LocalCloudConfig, current: Any = None) -> None:
        target = getattr(current, "name", None) or getattr(current, "container_id", None)
        detail = f" {target!r}" if target else ""
        self.reporter.update(
            f"Found running container{detail}; stopping it…"
        )

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
        if self.telemetry is not None:
            self.telemetry.observe_logs(logs)
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


def _port_mapping_details(
    mappings: tuple[PortMapping, ...],
) -> list[dict[str, Any]]:
    return [
        {
            "host_ip": host_ip,
            "host_port": host_port,
            "container_port": container_port,
            "protocol": protocol,
        }
        for host_ip, host_port, container_port, protocol in mappings
    ]


def main(argv: list[str] | None = None) -> int:
    parser = _parser()
    try:
        args = parser.parse_args(argv)
    except SystemExit as exc:
        return exc.code if isinstance(exc.code, int) else 0
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
    telemetry: Telemetry | None = None
    # A dry run changes nothing, so it neither counts as a run nor sends events.
    if args.command in _RUNTIME_COMMANDS and not getattr(args, "dry_run", False):
        from .telemetry import Telemetry

        telemetry = Telemetry(args.command)
    result: Any = None
    failure: Exception | None = None
    interrupted = False
    if reports_progress:
        reporter.start(_initial_task(args))
    observer = _ExecutionObserver(reporter, debug=debug, telemetry=telemetry)
    try:
        if args.command == "update":
            from .update import update

            return update()
        result = _execute(args, observer=observer)
        findings = _diagnose(telemetry, None, result)
        failure_message = _result_failure_message(args, result)
        if reports_progress:
            if failure_message is None:
                reporter.succeed(_success_message(args, result))
            else:
                reporter.fail(failure_message)
        _print_result(args, result, fields)
        _offer_after_failure(args, observer, findings)
        return 1 if failure_message is not None else 0
    except HostError as error:
        failure = error
        findings = _diagnose(telemetry, error, None)
        if reports_progress:
            reporter.fail(error.message)
        _print_error(args, error)
        _offer_after_failure(args, observer, findings)
        return 2
    except KeyboardInterrupt:
        interrupted = True
        if args.command == "mcp":
            if terminal_capabilities(sys.stderr).interactive:
                print("MCP connection closed.", file=sys.stderr, flush=True)
            # The MCP SDK can leave its stdin worker blocked after cancellation.
            # A normal return then hangs in interpreter thread shutdown.
            os._exit(130)
        if reports_progress:
            reporter.fail("LocalCloud command interrupted")
        else:
            print("LocalCloud command interrupted.", file=sys.stderr, flush=True)
        return 130
    except SystemExit:
        interrupted = True
        if reports_progress:
            reporter.fail("LocalCloud command interrupted")
        raise
    except Exception as error:
        # Anything that isn't a KeyboardInterrupt/SystemExit and wasn't
        # already raised as a HostError is an unexpected bug, not a user
        # interruption - give it the same clean-error treatment instead of a
        # raw traceback, and only surface the traceback when --debug is set.
        failure = error
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
        if telemetry is not None:
            if not telemetry.configured:
                try:
                    # Docker failed before config resolution. Files, plus the config
                    # the last Docker-backed run used, still carry the opt-out and
                    # context for the failure report.
                    source = _FileConfigSource(telemetry.recorded_config())
                    telemetry.configure(_command_config(source, args), fallback=True)
                except Exception:
                    pass  # An unreadable config hides its opt-out, so stay silent.
            telemetry.finish(error=failure, result=result, interrupted=interrupted)


def _execute(args: argparse.Namespace, observer: _ExecutionObserver | None = None) -> Any:
    if args.command == "guide":
        from .agent_guide import render_agent_guide

        return render_agent_guide()
    if args.command == "update":
        from .update import update

        return update()
    if args.command == "mcp" and getattr(args, "mcp_subcommand", None) == "install":
        from .config import DEFAULT_CONFIG_NAME, _read_config, detect_git_project, load_config
        from .mcp_install import install_mcp_server

        project_id = args.project_id
        if project_id is None:
            local_config_file = Path.cwd() / DEFAULT_CONFIG_NAME
            has_local_project = False
            if local_config_file.is_file():
                try:
                    local_raw = _read_config(local_config_file)
                    if isinstance(local_raw, dict) and isinstance(local_raw.get("context"), dict):
                        has_local_project = bool(local_raw["context"].get("project"))
                except Exception:
                    pass
            if not has_local_project:
                project_id = detect_git_project(Path.cwd())

        config = load_config(
            directory=Path.cwd(),
            data_volume=args.data_volume,
            project=project_id,
            user=args.user,
            active_runtime=None,
        )
        return install_mcp_server(
            config,
            client=args.client,
            is_global=args.is_global,
            directory=Path.cwd(),
            command_override=getattr(args, "command_path", None),
            prefer_bare=getattr(args, "bare", False),
            explicit_project=(args.project_id is not None),
        )

    fix = args.command == "doctor" and getattr(args, "fix", False)
    if fix and (observer is None or not observer.can_prompt()):
        raise HostError(
            "fix_confirmation_required",
            "lc doctor --fix asks before each fix; run it in an interactive terminal "
            "without --verbose",
        )
    controller = _controller(args, observer)
    if args.command == "doctor":
        if observer is not None:
            observer.doctor(args)
        return _doctor_fix(controller, observer) if fix and observer else controller.doctor()
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
            pull = args.pull
            if args.dry_run:
                pull = (
                    True
                    if getattr(args, "pull_explicit", False) and args.pull
                    else False
                )
            return controller.start(
                config,
                pull=pull,
                ensure_project=args.project_id is not None,
                observer=observer,
                tail=args.tail,
                dry_run=args.dry_run,
                confirm_port_mapping=(
                    (lambda _plan: True)
                    if args.accept_dynamic_ports
                    else observer.confirm_port_mapping
                    if observer is not None
                    else None
                ),
            )
        if args.command == "restart":
            pull = args.pull
            if args.dry_run:
                pull = (
                    True
                    if getattr(args, "pull_explicit", False) and args.pull
                    else False
                )
            return controller.restart(
                config,
                pull=pull,
                ensure_project=args.project_id is not None,
                observer=observer,
                tail=args.tail,
                dry_run=args.dry_run,
                confirm_port_mapping=(
                    (lambda _plan: True)
                    if args.accept_dynamic_ports
                    else observer.confirm_port_mapping
                    if observer is not None
                    else None
                ),
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

            run_kwargs: dict[str, Any] = {"connect_timeout": args.connect_timeout}
            if getattr(args, "no_start", False):
                run_kwargs["auto_start"] = False
            return run(config, **run_kwargs)
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


def _controller(args: argparse.Namespace, observer: _ExecutionObserver | None) -> Any:
    """Connect to Docker. When it is unavailable, `start` and `doctor --fix` in a
    terminal offer to install or start it, then wait for it."""
    from .controller import Controller

    try:
        return Controller()
    except HostError as error:
        offers = (args.command == "start" and not getattr(args, "dry_run", False)) or (
            args.command == "doctor" and getattr(args, "fix", False)
        )
        if error.code != "docker_unavailable" or not offers:
            raise
        if observer is None or not observer.can_prompt():
            raise
        from .host_checks import inspect

        _, findings = inspect(error=str(error.details.get("cause") or error.message))
        docker_findings = [finding for finding in findings if finding.id.startswith("docker_")]
        outcomes = observer.offer_fixes(docker_findings)
        if not any(outcome["status"] == "applied" for outcome in outcomes):
            raise
        return _wait_for_docker(observer)


def _wait_for_docker(observer: _ExecutionObserver) -> Any:
    from .controller import Controller

    observer.reporter.update("Waiting for Docker to start…")
    deadline = time.monotonic() + _DOCKER_WAIT_SECONDS
    while True:
        try:
            return Controller()
        except HostError as error:
            if error.code != "docker_unavailable" or time.monotonic() >= deadline:
                raise
        time.sleep(_DOCKER_POLL_SECONDS)


def _doctor_fix(controller: Any, observer: _ExecutionObserver) -> dict[str, Any]:
    setup = controller.setup_report()
    if any(outcome["status"] == "applied" for outcome in observer.offer_fixes(setup[1])):
        setup = controller.setup_report()  # Report the host as the fixes left it.
    result = controller.doctor(setup)
    result["fixes"] = observer.fixes
    return result


def _diagnose(
    telemetry: Telemetry | None, error: HostError | None, result: Any
) -> list[Finding] | None:
    """Run the setup checks when a start counts as failed (telemetry's definition)."""
    if telemetry is None or not telemetry.startup_problem(error, result):
        return None
    from .host_checks import inspect

    if error is not None and error.code == "docker_unavailable":
        _, findings = inspect(error=str(error.details.get("cause") or error.message))
    else:
        _, findings = inspect()
    telemetry.note_findings([finding.id for finding in findings])
    serialized = [finding.to_dict() for finding in findings]
    if error is not None:
        error.details["setup_findings"] = serialized
    elif isinstance(result, dict):
        result["setup_findings"] = serialized
    return findings


def _offer_after_failure(
    args: argparse.Namespace, observer: _ExecutionObserver, findings: list[Finding] | None
) -> None:
    if not findings or not observer.can_prompt():
        return
    offered = {outcome["id"] for outcome in observer.fixes}
    outcomes = observer.offer_fixes([finding for finding in findings if finding.id not in offered])
    lines = fix_outcome_lines(outcomes)
    if any(outcome["status"] == "applied" for outcome in outcomes):
        lines.append(f"Run 'lc {args.command}' again.")
    for line in lines:
        print(line, file=sys.stderr, flush=True)


class _FileConfigSource:
    """Config selection without Docker; telemetry's record stands in for its memory."""

    paths = None

    def __init__(self, remembered: str | None) -> None:
        self._remembered = remembered

    def remembered_config(self, _config: LocalCloudConfig) -> str | None:
        return self._remembered


def _command_config(controller: Any, args: argparse.Namespace) -> LocalCloudConfig:
    from .config import DEFAULT_CONFIG_NAME, HostPaths, _read_config, detect_git_project, load_active_runtime, load_config

    explicit_value = getattr(args, "config", None)
    explicit = Path(explicit_value) if explicit_value is not None else None

    project = getattr(args, "project_id", None)
    if args.command == "mcp" and project is None:
        local_config_file = Path.cwd() / DEFAULT_CONFIG_NAME
        has_local_project = False
        if local_config_file.is_file():
            try:
                local_raw = _read_config(local_config_file)
                if isinstance(local_raw, dict) and isinstance(local_raw.get("context"), dict):
                    has_local_project = bool(local_raw["context"].get("project"))
            except Exception:
                pass
        if not has_local_project:
            project = detect_git_project(Path.cwd())

    overrides = {
        "directory": Path.cwd(),
        "data_volume": getattr(args, "data_volume", None),
        "project": project,
        "user": getattr(args, "user", None),
        "container_name": getattr(args, "container_name", None),
        "network_name": getattr(args, "network_name", None),
        "tls": getattr(args, "tls", None),
        "memory": getattr(args, "memory", None),
        "image": getattr(args, "image", None),
        "services": getattr(args, "services", None),
        "skip_validation": getattr(args, "skip_config_validation", False),
        "strict_port_validation": getattr(args, "strict_port_validation", False),
        "local_only": getattr(args, "local_only", False),
        "port_range": getattr(args, "port_range", None),
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
    if command == "mcp" and getattr(args, "mcp_subcommand", None) == "install":
        if isinstance(result, dict) and "results" in result:
            for r in result["results"]:
                verb = "Updated" if r.get("status") == "updated" else "Installed"
                loc = f"at {r['config_path']}" if "config_path" in r else f"via {r.get('method', 'cli')}"
                print(f"{verb} LocalCloud MCP server configuration for {r['client']} {loc}.")
        elif isinstance(result, dict) and ("config_path" in result or "method" in result):
            verb = "Updated" if result.get("status") == "updated" else "Installed"
            loc = f"at {result['config_path']}" if "config_path" in result else f"via {result.get('method', 'cli')}"
            print(f"{verb} LocalCloud MCP server configuration for {result['client']} {loc}.")
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
    findings = result.get("setup_findings") if isinstance(result, dict) else None
    if command != "doctor" and findings:  # doctor shows them as its Setup row
        print(
            render_findings(findings, color=terminal_capabilities(sys.stderr).color),
            file=sys.stderr,
        )


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
        findings = error.details.get("setup_findings")
        if findings:
            rendered = render_findings(findings, color=color, skip_message=error.message)
            if rendered:
                print(rendered, file=sys.stderr)


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
        if isinstance(result, dict) and result.get("setup_findings"):
            return "LocalCloud can start; see Setup for recommended changes"
        return "LocalCloud is ready to start"
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
    if (
        args.command == "doctor"
        and isinstance(result, dict)
        and any(fix.get("status") == "failed" for fix in result.get("fixes") or ())
    ):
        return "A setup fix failed; see Fixes"
    return None


class _SmartPullAction(argparse.BooleanOptionalAction):
    def __call__(
        self,
        parser: argparse.ArgumentParser,
        namespace: argparse.Namespace,
        values: Any,
        option_string: str | None = None,
    ) -> None:
        super().__call__(parser, namespace, values, option_string)
        setattr(namespace, "pull_explicit", True)


class _HelpFormatter(argparse.HelpFormatter):
    def _fill_text(self, text: str, width: int, indent: str) -> str:
        return "\n\n".join(
            textwrap.fill(para, width, initial_indent=indent, subsequent_indent=indent)
            for para in text.split("\n\n")
        )


class _LocalCloudParser(argparse.ArgumentParser):
    def parse_args(
        self,
        args: Sequence[str] | None = None,
        namespace: argparse.Namespace | None = None,
    ) -> argparse.Namespace:
        if args is None:
            args = sys.argv[1:]
        if not args:
            args = ["-h"]
        return super().parse_args(args, namespace)


def _parser() -> argparse.ArgumentParser:
    parser = _LocalCloudParser(
        prog="localcloud",
        description=(
            "Run Google Cloud-compatible services locally in Docker. Manage "
            "LocalCloud runtimes by Docker data volume, project context, SDK "
            "environments, and the MCP bridge."
        ),
        epilog=f"{COMMAND_REQUIRED_NOTE}\n\n{ALIAS_HELP} {AGENT_HELP}",
        formatter_class=_HelpFormatter,
    )
    parser.add_argument(
        "-v",
        "--version",
        action="version",
        version=version_string(),
    )
    commands = parser.add_subparsers(
        dest="command",
        required=True,
        metavar="<command>",
        help="one of the <command> is required",
        parser_class=argparse.ArgumentParser,
    )
    commands.add_parser(
        "update",
        help="Update the CLI to the latest release",
        description=(
            "Update the CLI through its script installer or Homebrew. "
            "Containers, volumes, and runtime configuration are unchanged."
        ),
    )
    commands.add_parser(
        "guide",
        help="Print guidance for coding agents using LocalCloud",
        description="Print guidance for coding agents using LocalCloud.",
    )
    doctor = commands.add_parser(
        "doctor",
        help="Check Docker access, the Docker host setup, and legacy LocalCloud state",
        description=(
            "Check Docker access, the Docker host setup (which Docker app, its VM "
            "type and Rosetta), and legacy LocalCloud state. With --fix, offer to "
            "install or start Docker and to switch the Docker VM to VZ with Rosetta."
        ),
    )
    doctor.add_argument(
        "--fix",
        action="store_true",
        help="Offer to fix what doctor finds, asking before each fix",
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
        "restart": "Restart the runtime and optionally prepare --project-id",
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
        command.add_argument(
            "--local-only",
            action="store_true",
            help="Publish Docker ports on 127.0.0.1 only (default: all host interfaces)",
        )
        command.add_argument(
            "--port-range",
            metavar="START-END",
            help=(
                "Publish on host ports from this range instead of the canonical "
                "ports (overrides host.port_range)"
            ),
        )
        if name in {"start", "restart"}:
            command.add_argument(
                "--accept-dynamic-ports",
                action="store_true",
                help=(
                    "Accept an exact alternative host-port mapping without an "
                    "interactive confirmation when canonical ports are unavailable"
                ),
            )
            command.add_argument(
                "--pull",
                action=_SmartPullAction,
                default=(name == "start"),
                help=(
                    "Check for a newer image on the registry and pull if available before running (default: --pull)"
                    if name == "start"
                    else "Check for a newer image on the registry and pull if available before restarting (default: --no-pull)"
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
        help="Run the stdio MCP bridge or manage MCP client installations",
        description="Run the stdio MCP bridge or manage MCP client installations.",
    )
    mcp_subparsers = mcp.add_subparsers(dest="mcp_subcommand")
    install = mcp_subparsers.add_parser(
        "install",
        help="Install LocalCloud MCP server configuration for an AI coding agent",
        description="Install LocalCloud MCP server configuration into a coding client config file.",
    )
    from .mcp_install import SUPPORTED_CLIENTS

    install.add_argument(
        "--client",
        choices=(*SUPPORTED_CLIENTS, "all"),
        default="cursor",
        help="Target AI client to configure (default: cursor)",
    )
    install.add_argument(
        "--global",
        dest="is_global",
        action="store_true",
        default=True,
        help="Install into user-level configuration (default: true)",
    )
    install.add_argument(
        "--project",
        dest="is_global",
        action="store_false",
        help="Install into project/workspace configuration instead of user-level configuration",
    )
    install.add_argument(
        "--command-path",
        dest="command_path",
        default=None,
        metavar="CMD",
        help="Command or binary path to invoke LocalCloud (e.g. 'lc', 'localcloud', '/opt/homebrew/bin/localcloud')",
    )
    install.add_argument(
        "--bare",
        action="store_true",
        help="Use bare 'localcloud' command instead of resolving an absolute path",
    )
    _add_context(install)
    _add_debug_option(install)

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
    mcp.add_argument(
        "--no-start",
        action="store_true",
        help="Do not automatically start LocalCloud if it is not running",
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
