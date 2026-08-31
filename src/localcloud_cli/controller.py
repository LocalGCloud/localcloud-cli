from __future__ import annotations

from contextlib import nullcontext
import shlex
import time
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Any, Callable

from . import __version__
from .config import (
    ACTIVE_RUNTIME_SCHEMA_VERSION,
    ActiveRuntime,
    HostPaths,
    LEGACY_HOST_FILES,
    LEGACY_LOCK_PATTERN,
    LocalCloudConfig,
    clear_active_runtime,
    clear_legacy_host_state,
    data_volume_lock,
    load_active_runtime,
    load_config,
    runtime_settings,
    save_active_runtime,
)
from .constants import DEFAULTS_CONFIG_LABEL, DEFAULT_IMAGE
from .docker_runtime import DockerRunPlan, DockerRuntime, RuntimeRecord
from .errors import HostError
from .java_client import JavaMcpClient, is_retryable_java_error

_START_READINESS_TIMEOUT = 60.0
_READINESS_REQUEST_TIMEOUT = 5.0
_READINESS_POLL_INTERVAL = 1.0


@dataclass(frozen=True)
class _LifecyclePlan:
    action: str
    reason: str
    commands: tuple[str, ...]
    current: RuntimeRecord | None = None
    run_plan: DockerRunPlan | None = None
    prepared_image: tuple[Any, bool] | None = None

    def render(self) -> str:
        lines = [
            f"# action: {self.action}",
            f"# reason: {self.reason}",
        ]
        lines.extend(self.commands or ("# no mutations planned",))
        return "\n".join(lines)


class Controller:
    def __init__(
        self,
        runtime: DockerRuntime | None = None,
        paths: HostPaths | None = None,
    ):
        self.runtime = runtime if runtime is not None else DockerRuntime()
        self.paths = paths if paths is not None else HostPaths.from_environment()
        # Config selection already resolves Docker. Keep that exact result for
        # one immediate, matching read-only command instead of inspecting twice.
        self._remembered_resolution: (
            tuple[LocalCloudConfig, RuntimeRecord | None] | None
        ) = None

    def remembered_config(self, config: LocalCloudConfig) -> str | None:
        current = self.runtime.resolve(
            config, preferred_container_id=self._preferred_container_id(config)
        )
        self._remembered_resolution = (config, current)
        if current is None:
            return None
        value = current.config_path
        return (
            DEFAULTS_CONFIG_LABEL
            if value in {None, DEFAULTS_CONFIG_LABEL}
            else str(value)
        )

    def _resolve_runtime(
        self,
        config: LocalCloudConfig,
        *,
        require: bool = False,
        reuse_remembered: bool = False,
    ) -> RuntimeRecord | None:
        remembered = self._remembered_resolution
        self._remembered_resolution = None
        if (
            reuse_remembered
            and remembered is not None
            and remembered[0] == config
            and (remembered[1] is not None or not require)
        ):
            return remembered[1]
        return self.runtime.resolve(
            config,
            preferred_container_id=self._preferred_container_id(config),
            require=require,
        )


    def start(
        self,
        config: LocalCloudConfig,
        *,
        pull: bool = False,
        ensure_project: bool = False,
        observer: Any | None = None,
        tail: float | None = 0.0,
        dry_run: bool = False,
    ) -> dict[str, Any] | str:
        if dry_run and pull:
            raise HostError(
                "dry_run_pull_conflict",
                "Dry-run cannot be combined with --pull because previews are strictly read-only",
                {"image": config.image},
            )
        start_time = time.monotonic()
        deadline = start_time + _START_READINESS_TIMEOUT
        with (
            nullcontext()
            if dry_run
            else data_volume_lock(self.paths, config.data_volume)
        ):
            current = self._resolve_runtime(config)
            changed_fields: list[str] = []
            fresh_data = False
            prepared_image: tuple[Any, bool] | None = None
            run_plan: DockerRunPlan | None = None
            commands: tuple[str, ...] = ()

            if current is None:
                action = "create"
                reason = "no container uses the selected data volume"
                if (
                    not dry_run
                    and observer is not None
                    and hasattr(observer, "starting")
                ):
                    observer.starting(config)
                prepared_image = self.runtime.preflight_create(
                    config,
                    pull=pull,
                    observer=observer,
                    local_only=dry_run,
                )
                run_plan = self.runtime.plan_run(config, prepared_image[0])
                commands = self.runtime.preview_create_commands(config, run_plan)
                status = "started"
            elif pull or self._requires_managed_replacement(current, config):
                action = "replace"
                reason = (
                    "an image pull was requested"
                    if pull
                    else "managed runtime configuration changed"
                )
                _validate_replacement(current, config)
                if (
                    not dry_run
                    and observer is not None
                    and hasattr(observer, "starting")
                ):
                    observer.starting(config)
                changed_fields = _changed_fields(current, config)
                prepared_image = self.runtime.preflight_create(
                    config,
                    current,
                    pull=pull,
                    observer=observer,
                    local_only=dry_run,
                )
                run_plan = self.runtime.plan_run(
                    config,
                    prepared_image[0],
                    replacing=current,
                )
                preserve_volume = (
                    current.data == "persistent"
                    or current.ownership["data_volume"] == "attached"
                )
                preserve_network = current.network_name == config.network_name
                commands = (
                    *self.runtime.preview_remove_commands(
                        config,
                        current,
                        remove_volume=not preserve_volume,
                        remove_network=not preserve_network,
                    ),
                    *self.runtime.preview_create_commands(
                        config,
                        run_plan,
                        volume_exists=preserve_volume,
                        network_exists=True if preserve_network else None,
                    ),
                )
                status = (
                    "reconfigured"
                    if self._requires_managed_replacement(current, config)
                    else "started"
                )
            elif current.state != "running":
                action = "start"
                reason = "the selected container is stopped"
                target = current.name or current.container_id or config.container_name
                commands = (shlex.join(["docker", "start", target]),)
                status = "started"
            elif not self.runtime.is_ready(current):
                action = "wait"
                reason = "the selected container is running but not ready"
                status = "already_running"
            else:
                action = "reuse"
                reason = "the selected container is already running and ready"
                status = "already_running"

            commands = (
                *commands,
                *(
                    (
                        f"[LocalCloud API] ensure explicitly selected "
                        f"project={config.project!r} user={config.user!r}",
                    )
                    if ensure_project
                    else ()
                ),
                *(
                    ("[conditional LocalCloud seed] apply when project or data is created",)
                    if config.seed_yaml is not None
                    else ()
                ),
                "[host state] record active runtime",
            )
            plan = _LifecyclePlan(
                action=action,
                reason=reason,
                commands=commands,
                current=current,
                run_plan=run_plan,
                prepared_image=prepared_image,
            )
            _debug_plan(observer, plan, self.runtime, config)
            if dry_run:
                return plan.render()

            if action == "create":
                deadline = time.monotonic() + _START_READINESS_TIMEOUT
                environment = self.runtime.create(
                    config,
                    readiness_deadline=deadline,
                    observer=observer,
                    prepared_image=prepared_image,
                    run_plan=run_plan,
                )
                self._emit_runtime_logs(observer, config, environment)
                fresh_data = environment.volume_created
            elif action == "replace":
                deadline = time.monotonic() + _START_READINESS_TIMEOUT
                environment = self._replace(
                    current,
                    config,
                    pull=pull,
                    readiness_deadline=deadline,
                    observer=observer,
                    prepared_image=prepared_image,
                    run_plan=run_plan,
                )
                fresh_data = environment.volume_created
            elif action in {"start", "wait"}:
                if observer is not None and hasattr(observer, "starting"):
                    observer.starting(config)
                self._remaining_readiness(
                    deadline,
                    config,
                    current,
                    "docker_health",
                )
                environment = self.runtime.start(
                    config,
                    current,
                    readiness_deadline=deadline,
                    observer=observer,
                )
                self._emit_runtime_logs(observer, config, environment)
            else:
                assert current is not None
                environment = current

            project_created = False
            if ensure_project:
                project_created = self._ensure_project(
                    environment,
                    config,
                    deadline=deadline,
                )
            if config.seed_yaml is not None and (fresh_data or project_created):
                self._seed_project(
                    environment,
                    config,
                    timeout=self._remaining_readiness(
                        deadline,
                        config,
                        environment,
                        "seed",
                    ),
                )
            self._record_active(environment, config)
            if status != "already_running":
                self._tail_runtime_logs(
                    observer, config, environment, tail=tail, start_time=start_time
                )
            return self._payload(
                status,
                environment,
                config,
                include_sdk=True,
                changed_fields=changed_fields,
                logs=(
                    self._runtime_logs(config, environment)
                    if status != "already_running"
                    else None
                ),
            )

    def restart(
        self,
        config: LocalCloudConfig,
        *,
        pull: bool = False,
        ensure_project: bool = False,
        observer: Any | None = None,
        tail: float | None = 0.0,
        dry_run: bool = False,
    ) -> dict[str, Any] | str:
        if dry_run and pull:
            raise HostError(
                "dry_run_pull_conflict",
                "Dry-run cannot be combined with --pull because previews are strictly read-only",
                {"image": config.image},
            )
        start_time = time.monotonic()
        with (
            nullcontext()
            if dry_run
            else data_volume_lock(self.paths, config.data_volume)
        ):
            current = self._resolve_runtime(config)
            changed_fields: list[str] = []
            prepared_image: tuple[Any, bool] | None = None
            run_plan: DockerRunPlan | None = None
            image_changed = bool(
                current is not None
                and current.ownership["container"] == "managed"
                and current.configured_image_id
                and current.image_id
                and current.configured_image_id != current.image_id
            )
            if current is None:
                action = "create"
                reason = "no container uses the selected data volume"
                prepared_image = self.runtime.preflight_create(
                    config,
                    pull=pull,
                    observer=observer,
                    local_only=dry_run,
                )
                run_plan = self.runtime.plan_run(config, prepared_image[0])
                commands = self.runtime.preview_create_commands(config, run_plan)
                status = "restarted"
            elif (
                pull
                or self._requires_managed_replacement(current, config)
                or image_changed
            ):
                action = "replace"
                reason = (
                    "an image pull was requested"
                    if pull
                    else "the configured image changed"
                    if image_changed
                    else "managed runtime configuration changed"
                )
                _validate_replacement(current, config)
                changed_fields = _changed_fields(current, config)
                prepared_image = self.runtime.preflight_create(
                    config,
                    current,
                    pull=pull,
                    observer=observer,
                    local_only=dry_run,
                )
                run_plan = self.runtime.plan_run(
                    config,
                    prepared_image[0],
                    replacing=current,
                )
                preserve_volume = (
                    current.data == "persistent"
                    or current.ownership["data_volume"] == "attached"
                )
                preserve_network = current.network_name == config.network_name
                commands = (
                    *self.runtime.preview_remove_commands(
                        config,
                        current,
                        remove_volume=not preserve_volume,
                        remove_network=not preserve_network,
                    ),
                    *self.runtime.preview_create_commands(
                        config,
                        run_plan,
                        volume_exists=preserve_volume,
                        network_exists=True if preserve_network else None,
                    ),
                )
                status = (
                    "reconfigured"
                    if self._requires_managed_replacement(current, config)
                    or image_changed
                    else "restarted"
                )
            else:
                action = "restart"
                reason = "the selected container is conforming"
                target = current.name or current.container_id or config.container_name
                commands = (
                    shlex.join(["docker", "restart", "-t", "20", target]),
                )
                status = "restarted"

            commands = (
                *commands,
                *(
                    (
                        f"[LocalCloud API] ensure explicitly selected "
                        f"project={config.project!r} user={config.user!r}",
                    )
                    if ensure_project
                    else ()
                ),
                *(
                    ("[LocalCloud seed] reapply volatile seed data",)
                    if config.seed_yaml is not None
                    else ()
                ),
                "[host state] record active runtime",
            )
            plan = _LifecyclePlan(
                action=action,
                reason=reason,
                commands=commands,
                current=current,
                run_plan=run_plan,
                prepared_image=prepared_image,
            )
            _debug_plan(observer, plan, self.runtime, config)
            if dry_run:
                return plan.render()

            if action == "create":
                environment = self.runtime.create(
                    config,
                    observer=observer,
                    prepared_image=prepared_image,
                    run_plan=run_plan,
                )
            elif action == "replace":
                environment = self._replace(
                    current,
                    config,
                    pull=pull,
                    observer=observer,
                    prepared_image=prepared_image,
                    run_plan=run_plan,
                )
            else:
                assert current is not None
                environment = self.runtime.restart(
                    config, current, observer=observer
                )
            if action != "replace":
                self._emit_runtime_logs(observer, config, environment)
            if ensure_project:
                self._ensure_project(
                    environment,
                    config,
                    deadline=time.monotonic() + _START_READINESS_TIMEOUT,
                )
            if config.seed_yaml is not None:
                self._seed_project(environment, config, volatile_only=True)
            self._tail_runtime_logs(
                observer, config, environment, tail=tail, start_time=start_time
            )
            self._record_active(environment, config)
            return self._payload(
                status,
                environment,
                config,
                include_sdk=True,
                changed_fields=changed_fields,
                logs=self._runtime_logs(config, environment),
            )

    def reset(
        self,
        config: LocalCloudConfig,
        *,
        all_projects: bool = False,
        observer: Any | None = None,
        dry_run: bool = False,
    ) -> dict[str, Any] | str:
        with (
            nullcontext()
            if dry_run
            else data_volume_lock(self.paths, config.data_volume)
        ):
            current = self._resolve_runtime(config)
            if all_projects:
                ownership = self.runtime.recreation_ownership(config)
                if any(value != "managed" for value in ownership.values()):
                    raise HostError(
                        "ownership_forbidden",
                        "Resetting all projects requires fully managed runtime resources",
                        {
                            "data_volume": config.data_volume,
                            "ownership": ownership,
                        },
                    )
                manual_steps = self._manual_purge_steps(config, current)
                plan = _LifecyclePlan(
                    action="reset-all",
                    reason=(
                        "recreating all managed data requires deleting the "
                        "Docker data volume, which localcloud never does for you"
                    ),
                    commands=manual_steps,
                    current=current,
                )
                if dry_run:
                    return plan.render()
                raise HostError(
                    "manual_volume_removal_required",
                    (
                        "Recreating all managed runtime data means deleting the "
                        "Docker data volume. localcloud does not remove data "
                        "volumes; run the listed steps yourself, then start again."
                    ),
                    {
                        "data_volume": config.data_volume,
                        "steps": list(manual_steps),
                    },
                )

            prepared_image: tuple[Any, bool] | None = None
            run_plan: DockerRunPlan | None = None
            if current is None:
                action = "create"
                reason = "no container uses the selected data volume"
                prepared_image = self.runtime.preflight_create(
                    config,
                    observer=observer,
                    local_only=dry_run,
                )
                run_plan = self.runtime.plan_run(config, prepared_image[0])
                commands = self.runtime.preview_create_commands(config, run_plan)
            elif self._requires_managed_replacement(current, config):
                action = "replace"
                reason = "managed runtime configuration changed"
                _validate_replacement(current, config)
                prepared_image = self.runtime.preflight_create(
                    config,
                    current,
                    observer=observer,
                    local_only=dry_run,
                )
                run_plan = self.runtime.plan_run(
                    config,
                    prepared_image[0],
                    replacing=current,
                )
                preserve_volume = (
                    current.data == "persistent"
                    or current.ownership["data_volume"] == "attached"
                )
                preserve_network = current.network_name == config.network_name
                commands = (
                    *self.runtime.preview_remove_commands(
                        config,
                        current,
                        remove_volume=not preserve_volume,
                        remove_network=not preserve_network,
                    ),
                    *self.runtime.preview_create_commands(
                        config,
                        run_plan,
                        volume_exists=preserve_volume,
                        network_exists=True if preserve_network else None,
                    ),
                )
            elif current.state != "running":
                action = "start"
                reason = "the selected container is stopped"
                target = current.name or current.container_id or config.container_name
                commands = (shlex.join(["docker", "start", target]),)
            elif not self.runtime.is_ready(current):
                _validate_unready_recovery(current, config)
                action = "recover"
                reason = "the managed container is running but not ready"
                target = current.name or current.container_id or config.container_name
                commands = (
                    shlex.join(["docker", "restart", "-t", "20", target]),
                )
            else:
                action = "no-op"
                reason = "the selected container is already running and ready"
                commands = ()

            runtime_action = action
            commands = (
                *commands,
                f"[LocalCloud API] reset project={config.project!r} user={config.user!r}",
                *(
                    ("[LocalCloud seed] apply configured seed data",)
                    if config.seed_yaml is not None
                    else ()
                ),
            )
            plan = _LifecyclePlan(
                action="reset-project",
                reason=reason,
                commands=commands,
                current=current,
                run_plan=run_plan,
                prepared_image=prepared_image,
            )
            _debug_plan(observer, plan, self.runtime, config)
            if dry_run:
                return plan.render()

            if runtime_action == "create":
                environment = self.runtime.create(
                    config,
                    observer=observer,
                    prepared_image=prepared_image,
                    run_plan=run_plan,
                )
            elif runtime_action == "replace":
                environment = self._replace(
                    current,
                    config,
                    observer=observer,
                    prepared_image=prepared_image,
                    run_plan=run_plan,
                )
            elif runtime_action == "start":
                environment = self.runtime.start(
                    config, current, observer=observer
                )
            elif runtime_action == "recover":
                environment = self._recover_unready(
                    config, current, observer=observer
                )
            else:
                assert current is not None
                environment = current

            self._require_project(environment, config.project, config.user)
            client = JavaMcpClient(
                _runtime_url(environment), config.project, config.user
            )
            try:
                client.reset_project()
            except Exception as error:
                raise HostError(
                    "project_reset_failed",
                    "LocalCloud project could not be reset",
                    {
                        "data_volume": config.data_volume,
                        "project": config.project,
                        "cause": str(error),
                    },
                ) from error
            if config.seed_yaml is not None:
                self._seed_project(environment, config)
            return self._payload(
                "reset",
                environment,
                config,
                include_sdk=True,
                reset_scope="project",
            )

    def stop(
        self,
        config: LocalCloudConfig,
        *,
        observer: Any | None = None,
        dry_run: bool = False,
    ) -> dict[str, Any] | str:
        with (
            nullcontext()
            if dry_run
            else data_volume_lock(self.paths, config.data_volume)
        ):
            current = self._resolve_runtime(config)
            if current is None:
                plan = _LifecyclePlan(
                    action="no-op",
                    reason="no container uses the selected data volume",
                    commands=(),
                )
            elif current.state != "running":
                plan = _LifecyclePlan(
                    action="no-op",
                    reason="the selected container is already stopped",
                    commands=(),
                    current=current,
                )
            elif current.data == "ephemeral" and current.origin == "managed":
                plan = _LifecyclePlan(
                    action="remove",
                    reason="managed ephemeral runtimes are removed when stopped",
                    commands=self.runtime.preview_remove_commands(
                        config,
                        current,
                        remove_volume=True,
                    ),
                    current=current,
                )
            else:
                target = current.name or current.container_id or config.container_name
                plan = _LifecyclePlan(
                    action="stop",
                    reason="the selected container is running",
                    commands=(
                        shlex.join(["docker", "stop", "-t", "20", target]),
                    ),
                    current=current,
                )
            _debug_plan(observer, plan, self.runtime, config)
            if dry_run:
                return plan.render()
            if current is None:
                return self._absent_payload("not_running", config)
            if current.state != "running":
                return self._payload(
                    "not_running", current, config, include_sdk=False
                )
            if plan.action == "remove":
                self.runtime.remove(
                    config,
                    current,
                    remove_volume=True,
                    observer=observer,
                )
                removed = replace(
                    current,
                    state="removed",
                    health=None,
                    url=None,
                    endpoint_map={},
                    published_ports={},
                )
                return self._payload(
                    "stopped", removed, config, include_sdk=False
                )
            stopped = self.runtime.stop(
                config, current, observer=observer
            )
            return self._payload("stopped", stopped, config, include_sdk=False)

    def status(self, config: LocalCloudConfig) -> dict[str, Any]:
        current = self._resolve_runtime(config, reuse_remembered=True)
        if current is None:
            status = "not_created"
        elif current.state != "running":
            status = "stopped"
        elif not self.runtime.is_ready(current):
            status = "unhealthy"
        else:
            status = "running"

        image_name = current.configured_image if current is not None else config.image
        image_details = self.runtime.image_details(image_name)
        image_status = (
            "available locally"
            if image_details["location"] == "Local"
            else "not available locally"
        )
        result = (
            self._absent_payload(status, config, image_status=image_status)
            if current is None
            else self._payload(
                status,
                current,
                config,
                include_sdk=False,
                image_status=image_status,
            )
        )
        result["container"]["image_details"] = image_details
        return result

    def logs(self, config: LocalCloudConfig, tail: int = 200) -> dict[str, Any]:
        current = self._resolve_runtime(
            config, require=True, reuse_remembered=True
        )
        assert current is not None
        result = self._payload("logs", current, config, include_sdk=False)
        result["logs"] = self.runtime.logs(config, current, tail=tail)
        return result

    def target(
        self,
        config: LocalCloudConfig,
        *,
        readiness_timeout: float | None = None,
        on_url_resolved: Callable[[str], None] | None = None,
    ) -> dict[str, Any]:
        if readiness_timeout is not None and readiness_timeout <= 0:
            raise ValueError("readiness_timeout must be positive")

        current = self._resolve_runtime(config, reuse_remembered=True)
        recovery = (
            f"Run localcloud start --data-volume {config.data_volume} "
            f"--project-id {config.project} --user {config.user} before connecting."
        )
        if current is None or current.state != "running" or not current.url:
            raise HostError(
                "runtime_not_running",
                recovery,
                {
                    "data_volume": config.data_volume,
                    "project": config.project,
                    "user": config.user,
                },
            )

        url = current.url.rstrip("/")
        target = {
            "url": url,
            "connect_url": current.connect_url or url,
            "endpoint_map": dict(current.endpoint_map),
        }
        if on_url_resolved is not None:
            on_url_resolved(url)

        if readiness_timeout is None:
            if not self.runtime.is_ready(current):
                raise HostError(
                    "runtime_not_running",
                    recovery,
                    {
                        "data_volume": config.data_volume,
                        "project": config.project,
                        "user": config.user,
                    },
                )
            if not self._project_exists(current, config.project, config.user):
                raise HostError(
                    "unknown_project",
                    (
                        f"Project {config.project!r} does not exist; run localcloud start "
                        f"--data-volume {config.data_volume} --project-id {config.project}."
                    ),
                    {
                        "data_volume": config.data_volume,
                        "project": config.project,
                    },
                )
            return target

        deadline = time.monotonic() + readiness_timeout
        last_error: HostError | None = None
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                details: dict[str, Any] = {
                    "data_volume": config.data_volume,
                    "project": config.project,
                    "url": url,
                    "timeout_seconds": readiness_timeout,
                    "phase": "target",
                }
                if last_error is not None:
                    details["last_error"] = last_error.to_dict()
                raise HostError(
                    "runtime_readiness_timeout",
                    "LocalCloud did not become operational before the connection deadline",
                    details,
                )

            if self.runtime.is_ready(current, timeout=min(3.0, remaining)):
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    continue
                try:
                    project_exists = self._project_exists(
                        current,
                        config.project,
                        config.user,
                        timeout=min(_READINESS_REQUEST_TIMEOUT, remaining),
                    )
                except HostError as error:
                    cause = error.__cause__
                    if (
                        not isinstance(cause, HostError)
                        or not is_retryable_java_error(cause)
                    ):
                        raise
                    last_error = error
                else:
                    if not project_exists:
                        raise HostError(
                            "unknown_project",
                            (
                                f"Project {config.project!r} does not exist; "
                                "run localcloud start "
                                f"--data-volume {config.data_volume} "
                                f"--project-id {config.project}."
                            ),
                            {
                                "data_volume": config.data_volume,
                                "project": config.project,
                            },
                        )
                    return target

            remaining = deadline - time.monotonic()
            if remaining > 0:
                time.sleep(min(_READINESS_POLL_INTERVAL, remaining))


    def doctor(self) -> dict[str, Any]:
        result = self.runtime.doctor()
        active_status, active_diagnostics = self._active_runtime_info()
        result["active_runtime"] = active_status
        result["active_runtime_diagnostics"] = active_diagnostics
        legacy_host_state, legacy_locks = self._legacy_host_state()
        result["legacy_host_state"] = legacy_host_state
        result["cli_version"] = __version__
        image_details = self.runtime.image_details(DEFAULT_IMAGE)
        result["default_image"] = f"{DEFAULT_IMAGE} {image_details['formatted']}"
        result["image_details"] = image_details
        result["legacy_locks"] = legacy_locks
        warnings = [result.get("warning", "")]
        if active_diagnostics:
            warnings.append(
                "Active runtime state is malformed and was ignored; start a runtime to replace it."
            )
        if active_status is not None and active_status["state"] != "current":
            warnings.append(
                "The active runtime record is stale or invalid; Docker state will be revalidated before use."
            )
        if legacy_host_state or legacy_locks:
            warnings.append(
                "Legacy host state and locks are present; run 'localcloud cleanup' (or 'lc cleanup') to remove them."
            )
        warning = " ".join(item for item in warnings if item).strip()
        if warning:
            result["warning"] = warning
        return result

    def _active_runtime_info(
        self,
    ) -> tuple[dict[str, Any] | None, list[dict[str, Any]]]:
        active_diagnostics: list[dict[str, Any]] = []
        active = load_active_runtime(self.paths, active_diagnostics)
        active_status: dict[str, Any] | None = None
        if active is not None:
            try:
                selected = load_config(
                    directory=self.paths.home,
                    data_volume=active.data_volume,
                    paths=self.paths,
                )
                current = self.runtime.resolve(
                    selected, preferred_container_id=active.container_id
                )
                active_status = {
                    "data_volume": active.data_volume,
                    "configured_image": active.image,
                    "container_id": active.container_id,
                    "state": "current"
                    if current is not None
                    and current.container_id == active.container_id
                    else "stale",
                    "resolved_container_id": current.container_id
                    if current is not None
                    else None,
                }
            except HostError as error:
                active_status = {
                    "data_volume": active.data_volume,
                    "configured_image": active.image,
                    "container_id": active.container_id,
                    "state": "invalid",
                    "error": error.to_dict(),
                }
        return active_status, active_diagnostics

    def _legacy_host_state(self) -> tuple[list[str], list[str]]:
        legacy_host_files = [
            name
            for name in LEGACY_HOST_FILES
            if (self.paths.home / name).exists()
        ]
        legacy_locks: list[str] = []
        if self.paths.locks.is_dir():
            legacy_locks = sorted(
                path.name
                for path in self.paths.locks.iterdir()
                if path.is_file() and LEGACY_LOCK_PATTERN.fullmatch(path.name)
            )
        return legacy_host_files, legacy_locks

    def cleanup(
        self,
        *,
        confirm: bool | None = None,
        dry_run: bool = False,
    ) -> dict[str, Any]:
        is_dry_run = not confirm if confirm is not None else dry_run
        docker_report = self.runtime.doctor()
        invalid_ownership = docker_report.get("invalid_ownership", [])
        active_status, active_diagnostics = self._active_runtime_info()
        active_stale = bool(active_diagnostics) or (
            active_status is not None and active_status["state"] != "current"
        )
        legacy_host_state, legacy_locks = self._legacy_host_state()
        result: dict[str, Any] = {
            "status": "ok",
            "dry_run": is_dry_run,
            "docker_resources": invalid_ownership,
            "active_runtime_stale": active_stale,
            "legacy_host_state": legacy_host_state,
            "legacy_locks": legacy_locks,
            "failures": [],
        }
        if is_dry_run:
            return result
        docker_cleanup = self.runtime.cleanup_resources(invalid_ownership)
        result["docker_resources"] = docker_cleanup["removed"]
        result["failures"] = docker_cleanup["failures"]
        if active_stale:
            clear_active_runtime(self.paths)
        cleared = clear_legacy_host_state(self.paths)
        result["legacy_host_state"] = cleared["files"]
        result["legacy_locks"] = cleared["locks"]
        if result["failures"]:
            result["status"] = "partial"
        return result

    def _ensure_running(self, config: LocalCloudConfig) -> RuntimeRecord:
        current = self.runtime.resolve(
            config, preferred_container_id=self._preferred_container_id(config)
        )
        if current is None:
            return self.runtime.create(config)
        if self._requires_managed_replacement(current, config):
            return self._replace(current, config)
        if current.state != "running":
            return self.runtime.start(config, current)
        if not self.runtime.is_ready(current):
            return self._recover_unready(config, current)
        return current

    def _emit_runtime_logs(
        self,
        observer: Any | None,
        config: LocalCloudConfig,
        environment: RuntimeRecord,
        *,
        tail: int = 12,
        since: float | None = None,
    ) -> float:
        poll_time = time.time()
        if observer is None or not hasattr(observer, "runtime_logs"):
            return poll_time
        try:
            logs = self.runtime.logs(config, environment, tail=tail, since=since)
        except Exception:
            return poll_time
        if logs:
            observer.runtime_logs(logs)
        return poll_time

    def _tail_runtime_logs(
        self,
        observer: Any | None,
        config: LocalCloudConfig,
        environment: RuntimeRecord,
        *,
        tail: float | None = 0.0,
        start_time: float | None = None,
    ) -> None:
        if observer is None or not hasattr(observer, "runtime_logs"):
            return
        # `since` narrows each poll to logs produced after the previous poll,
        # instead of re-fetching (and re-diffing) the last 100 lines every
        # 0.5s. Only the first poll pulls a backlog; every poll after that is
        # a cheap incremental fetch.
        if tail is None or tail < 0:
            try:
                since: float | None = None
                while True:
                    since = self._emit_runtime_logs(
                        observer, config, environment, tail=100, since=since
                    )
                    time.sleep(0.5)
            except KeyboardInterrupt:
                pass
            return
        if tail <= 0:
            return
        effective_start = start_time if start_time is not None else time.monotonic()
        since = None
        while True:
            since = self._emit_runtime_logs(
                observer, config, environment, tail=100, since=since
            )
            elapsed = time.monotonic() - effective_start
            remaining = tail - elapsed
            if remaining <= 0:
                break
            time.sleep(min(0.5, remaining))
        self._emit_runtime_logs(observer, config, environment, tail=100, since=since)

    def _runtime_logs(
        self, config: LocalCloudConfig, environment: RuntimeRecord, *, tail: int = 20
    ) -> str:
        try:
            return self.runtime.logs(config, environment, tail=tail)
        except Exception:
            return ""

    def _recover_unready(
        self,
        config: LocalCloudConfig,
        current: RuntimeRecord,
        *,
        readiness_deadline: float | None = None,
        observer: Any | None = None,
    ) -> RuntimeRecord:
        _validate_unready_recovery(current, config)
        return self.runtime.restart(
            config,
            current,
            readiness_deadline=readiness_deadline,
            observer=observer,
        )


    @staticmethod
    def _requires_managed_replacement(
        current: RuntimeRecord, config: LocalCloudConfig
    ) -> bool:
        return (
            current.ownership["container"] == "managed"
            and current.config_hash != config.config_hash
        )

    @staticmethod
    def _manual_purge_steps(
        config: LocalCloudConfig, current: RuntimeRecord | None
    ) -> tuple[str, ...]:
        """Steps a user runs by hand to recreate all data on a volume.

        `reset --all-projects` used to shell out `docker volume rm`; deleting a
        data volume is now always the user's explicit action, so the command
        prints these instead of executing anything."""
        steps: list[str] = []
        if current is not None:
            steps.append(
                shlex.join(
                    ["localcloud", "stop", "--data-volume", config.data_volume]
                )
            )
        steps.append("# deletes ALL projects and data on this volume:")
        steps.append(
            shlex.join(["docker", "volume", "rm", "-f", config.data_volume])
        )
        steps.append(
            shlex.join(
                [
                    "localcloud",
                    "start",
                    "--data-volume",
                    config.data_volume,
                    "--project-id",
                    config.project,
                    "--user",
                    config.user,
                ]
            )
        )
        return tuple(steps)
    def _replace(
        self,
        current: RuntimeRecord,
        config: LocalCloudConfig,
        *,
        pull: bool = False,
        readiness_deadline: float | None = None,
        observer: Any | None = None,
        prepared_image: tuple[Any, bool] | None = None,
        run_plan: DockerRunPlan | None = None,
    ) -> RuntimeRecord:
        _validate_replacement(current, config)
        # Never discard a volume that currently holds persistent data as a side
        # effect of an implicit reconfigure (a `start`/`restart` after a config
        # change): `remove()` runs before `create()` can prove the new
        # container will actually come up, so a target that changes `data`
        # away from "persistent" must not be treated as license to delete data
        # that already exists. Deleting on-disk data is reserved for an
        # explicit purge/reset; here we key only off whether the *existing*
        # volume was ever persistent, not the new target setting.
        preserve_volume = (
            current.data == "persistent"
            or current.ownership["data_volume"] == "attached"
        )
        # A managed network never varies by config beyond its name (it's
        # always `docker network create --driver bridge <name>`), so there's
        # no need to tear it down and recreate it on every reconfigure -
        # only when the target network name actually changes.
        preserve_network = current.network_name == config.network_name
        prepared_image = (
            prepared_image
            if prepared_image is not None
            else self.runtime.preflight_create(
                config,
                current,
                pull=pull,
                observer=observer,
            )
        )
        _, was_pulled = prepared_image
        if (
            was_pulled
            and observer is not None
            and hasattr(observer, "starting")
        ):
            observer.starting(config)
        self.runtime.remove(
            config,
            current,
            remove_volume=not preserve_volume,
            remove_network=not preserve_network,
            observer=observer,
        )
        try:
            environment = self.runtime.create(
                config,
                readiness_deadline=readiness_deadline,
                observer=observer,
                prepared_image=prepared_image,
                run_plan=run_plan,
            )
        except Exception as error:
            if isinstance(error, HostError):
                error.details["previous_runtime_removed"] = True
                error.details["data_volume_removed"] = not preserve_volume
            raise
        self._emit_runtime_logs(observer, config, environment)
        return environment

    @staticmethod
    def _project_exists(
        environment: RuntimeRecord,
        project: str,
        user: str,
        *,
        timeout: float = 60.0,
    ) -> bool:
        try:
            return JavaMcpClient(
                _runtime_url(environment),
                project,
                user,
                timeout=timeout,
            ).project_exists()
        except Exception as error:
            raise HostError(
                "project_lookup_failed",
                "LocalCloud project catalog could not be read",
                {
                    "data_volume": environment.data_volume,
                    "project": project,
                    "cause": str(error),
                },
            ) from error

    @staticmethod
    def _error_details(error: Exception) -> dict[str, Any]:
        if isinstance(error, HostError):
            return error.to_dict()
        return {
            "type": type(error).__name__,
            "message": str(error),
        }

    def _remaining_readiness(
        self,
        deadline: float,
        config: LocalCloudConfig,
        environment: RuntimeRecord | None,
        phase: str,
        last_error: Exception | None = None,
    ) -> float:
        remaining = deadline - time.monotonic()
        if remaining > 0:
            return remaining
        details: dict[str, Any] = {
            "data_volume": config.data_volume,
            "container_id": (
                environment.container_id
                if environment is not None
                else None
            ),
            "project": config.project,
            "timeout_seconds": _START_READINESS_TIMEOUT,
            "phase": phase,
        }
        if last_error is not None:
            details["last_error"] = self._error_details(last_error)
        raise HostError(
            "runtime_readiness_timeout",
            "LocalCloud did not become operational before the readiness deadline",
            details,
        )

    def _readiness_client(
        self,
        environment: RuntimeRecord,
        project: str,
        user: str,
        *,
        deadline: float,
        config: LocalCloudConfig,
        phase: str,
        last_error: Exception | None = None,
    ) -> JavaMcpClient:
        remaining = self._remaining_readiness(
            deadline,
            config,
            environment,
            phase,
            last_error,
        )
        return JavaMcpClient(
            _runtime_url(environment),
            project,
            user,
            timeout=min(_READINESS_REQUEST_TIMEOUT, remaining),
        )

    def _wait_for_readiness_retry(
        self,
        deadline: float,
        config: LocalCloudConfig,
        environment: RuntimeRecord,
        phase: str,
        last_error: Exception,
    ) -> None:
        remaining = self._remaining_readiness(
            deadline,
            config,
            environment,
            phase,
            last_error,
        )
        time.sleep(min(_READINESS_POLL_INTERVAL, remaining))

    def _ensure_project(
        self,
        environment: RuntimeRecord,
        config: LocalCloudConfig,
        *,
        deadline: float,
    ) -> bool:
        project = config.project
        user = config.user
        last_error: Exception | None = None
        while True:
            client = self._readiness_client(
                environment,
                project,
                user,
                deadline=deadline,
                config=config,
                phase="project_catalog",
                last_error=last_error,
            )
            try:
                projects = client.list_projects()
                break
            except Exception as error:
                if not is_retryable_java_error(error):
                    raise HostError(
                        "project_create_failed",
                        "LocalCloud project could not be ensured",
                        {
                            "data_volume": environment.data_volume,
                            "project": project,
                            "cause": self._error_details(error),
                        },
                    ) from error
                last_error = error
                self._wait_for_readiness_retry(
                    deadline,
                    config,
                    environment,
                    "project_catalog",
                    error,
                )

        if any(item.get("project_id") == project for item in projects):
            return False

        client = self._readiness_client(
            environment,
            project,
            user,
            deadline=deadline,
            config=config,
            phase="project_create",
        )
        creation_error: Exception | None = None
        try:
            client.create_project()
        except Exception as error:
            if not is_retryable_java_error(error):
                raise HostError(
                    "project_create_failed",
                    "LocalCloud project could not be ensured",
                    {
                        "data_volume": environment.data_volume,
                        "project": project,
                        "cause": self._error_details(error),
                    },
                ) from error
            creation_error = error

        last_transport_error = creation_error
        last_error: Exception | None = creation_error
        while True:
            client = self._readiness_client(
                environment,
                project,
                user,
                deadline=deadline,
                config=config,
                phase="project_visibility",
                last_error=last_error,
            )
            visibility_error: Exception = RuntimeError(
                "project was not readable after creation"
            )
            try:
                if client.project_exists():
                    return True
            except Exception as error:
                if not is_retryable_java_error(error):
                    raise HostError(
                        "project_create_failed",
                        "LocalCloud project could not be ensured",
                        {
                            "data_volume": environment.data_volume,
                            "project": project,
                            "cause": self._error_details(error),
                        },
                    ) from error
                last_transport_error = error
                visibility_error = error
            last_error = last_transport_error or visibility_error
            self._wait_for_readiness_retry(
                deadline,
                config,
                environment,
                "project_visibility",
                last_error,
            )

    def _require_project(
        self, environment: RuntimeRecord, project: str, user: str
    ) -> None:
        if self._project_exists(environment, project, user):
            return
        raise HostError(
            "unknown_project",
            (
                f"Project {project!r} does not exist; run localcloud start "
                f"--data-volume {environment.data_volume} --project-id {project}."
            ),
            {"data_volume": environment.data_volume, "project": project},
        )

    @staticmethod
    def _seed_project(
        environment: RuntimeRecord,
        config: LocalCloudConfig,
        *,
        volatile_only: bool = False,
        timeout: float = 60.0,
    ) -> None:
        try:
            JavaMcpClient(
                _runtime_url(environment),
                config.project,
                config.user,
                timeout=timeout,
            ).seed_project(
                config.seed_yaml or "", volatile_only=volatile_only
            )
        except Exception as error:
            cause = (
                error.to_dict()
                if isinstance(error, HostError)
                else {
                    "type": type(error).__name__,
                    "message": str(error),
                }
            )
            raise HostError(
                "seed_failed",
                "LocalCloud project seed could not be applied",
                {
                    "data_volume": config.data_volume,
                    "project": config.project,
                    "seed": str(config.seed_path) if config.seed_path else None,
                    "cause": cause,
                },
            ) from error

    def _record_active(
        self, environment: RuntimeRecord, config: LocalCloudConfig
    ) -> None:
        save_active_runtime(
            self.paths,
            ActiveRuntime(
                schema_version=ACTIVE_RUNTIME_SCHEMA_VERSION,
                data_volume=config.data_volume,
                image=config.image,
                container_id=_active_container_id(environment),
                container_name=environment.name,
                network_name=environment.network_name,
            ),
        )

    def _preferred_container_id(self, config: LocalCloudConfig) -> str | None:
        active = load_active_runtime(
            self.paths, data_volume=config.data_volume
        )
        if active is None or active.data_volume != config.data_volume:
            return None
        return active.container_id

    def _payload(
        self,
        status: str,
        environment: RuntimeRecord | None,
        config: LocalCloudConfig,
        *,
        include_sdk: bool,
        changed_fields: list[str] | None = None,
        reset_scope: str | None = None,
        logs: str | None = None,
        image_status: str | None = None,
    ) -> dict[str, Any]:
        if environment is None:
            data_volume = config.data_volume
            origin = None
            ownership = {
                "container": "absent",
                "network": "absent",
                "data_volume": "absent",
            }
            url = None
            endpoint_map: dict[str, int] = {}
            services: str | tuple[str, ...] = "unavailable"
            data = config.data
            container = {
                "name": config.container_name,
                "id": None,
                "state": "absent",
                "health": None,
                "url": None,
                "configured_image": config.image,
                "actual_image": None,
                "image_id": None,
                "image_status": image_status or self.runtime.image_status(config.image),
            }
            network = {
                "name": config.network_name,
                "ownership": "absent",
            }
            mount = {
                "type": "volume",
                "source": config.data_volume,
                "destination": "/var/lib/localcloud",
                "mode": "rw",
                "read_write": True,
            }
            drift: dict[str, dict[str, Any]] = {}
            recorded_config_path = None
        else:
            data_volume = environment.data_volume
            origin = environment.origin
            ownership = dict(environment.ownership)
            url = environment.url
            endpoint_map = environment.endpoint_map
            effective_services = self.runtime.effective_services(environment)
            services = (
                "unavailable" if effective_services is None else effective_services
            )
            data = environment.data
            container = {
                "name": environment.name,
                "id": environment.container_id,
                "state": environment.state,
                "health": environment.health,
                "url": environment.connect_url or url,
                "configured_image": environment.configured_image,
                "actual_image": environment.actual_image,
                "image_id": environment.image_id,
                "image_status": (
                    environment.image_status
                    or image_status
                    or self.runtime.image_status(environment.configured_image)
                ),
            }
            network = {
                "name": environment.network_name,
                "ownership": ownership.get("network"),
            }
            mount = dict(environment.mount)
            drift = dict(environment.drift)
            recorded_config_path = environment.config_path

        sdk_env: Any = {}
        if include_sdk and url:
            from .endpoints import environment_config

            sdk_env = environment_config(
                {"url": url, "endpoint_map": endpoint_map},
                config.project,
                config.user,
                output_format="json",
            )
            if not isinstance(sdk_env, dict):
                raise HostError(
                    "invalid_environment",
                    "Java MCP JSON environment configuration must be an object",
                )
        result: dict[str, Any] = {
            "status": status,
            "data_volume": data_volume,
            "origin": origin,
            "ownership": ownership,
            "config": _public_config(
                recorded_config_path
                or (str(config.config_path) if config.config_path else None)
            ),
            "services": _public_services(services),
            "data": data,
            "container": container,
            "network": network,
            "mount": mount,
            "drift": drift,
            "sdk_env": sdk_env,
        }
        if config.diagnostics:
            result["diagnostics"] = list(config.diagnostics)
        if include_sdk:
            result["project"] = config.project
            result["user"] = config.user
            result["mcp"] = {
                "command": "localcloud",
                "args": [
                    "mcp",
                    "--data-volume",
                    data_volume,
                    "--project-id",
                    config.project,
                    "--user",
                    config.user,
                ],
                "direct_url": f"{url}/mcp" if url else None,
                "headers": {
                    "X-LocalCloud-Project": config.project,
                    "X-LocalCloud-User": config.user,
                },
            }
        if changed_fields:
            result["changed_fields"] = changed_fields
        if reset_scope is not None:
            result["reset_scope"] = reset_scope
        if logs is not None:
            result["logs"] = logs
        return result

    def _absent_payload(
        self,
        status: str,
        config: LocalCloudConfig,
        *,
        image_status: str | None = None,
    ) -> dict[str, Any]:
        return self._payload(
            status,
            None,
            config,
            include_sdk=False,
            image_status=image_status,
        )


def _debug_plan(
    observer: Any | None,
    plan: _LifecyclePlan,
    runtime: DockerRuntime,
    config: LocalCloudConfig,
) -> None:
    if observer is None or not hasattr(observer, "debug"):
        return
    if hasattr(observer, "debug_enabled") and not observer.debug_enabled:
        return
    run_plan = plan.run_plan
    if run_plan is None and plan.current is not None:
        run_plan = runtime.inspect_run_plan(config, plan.current)
    if run_plan is not None:
        observer.debug(run_plan.command())


def _validate_unready_recovery(
    current: RuntimeRecord,
    config: LocalCloudConfig,
) -> None:
    if current.ownership["container"] == "managed":
        return
    raise HostError(
        "attached_runtime_unhealthy",
        "The attached LocalCloud runtime is not ready; inspect it or run localcloud restart explicitly",
        {
            "data_volume": config.data_volume,
            "container_id": current.container_id,
            "url": current.url,
            "state": current.state,
        },
    )


def _validate_replacement(
    current: RuntimeRecord,
    config: LocalCloudConfig,
) -> None:
    if current.ownership["container"] != "managed":
        raise HostError(
            "ownership_forbidden",
            "Attached LocalCloud containers cannot be reconfigured",
            {
                "data_volume": config.data_volume,
                "ownership": current.ownership,
            },
        )
    if current.ownership["network"] != "managed":
        raise HostError(
            "ownership_forbidden",
            "Reconfiguration requires a managed runtime network",
            {
                "data_volume": config.data_volume,
                "ownership": current.ownership,
            },
        )


def _changed_fields(
    current: RuntimeRecord, config: LocalCloudConfig
) -> list[str]:
    previous = current.runtime_settings
    selected = runtime_settings(config)
    if previous is None:
        return ["configuration"]
    changed = set(
        key
        for key in set(previous) | set(selected)
        if previous.get(key) != selected.get(key)
    )
    if (
        current.configured_image_id
        and current.image_id
        and current.configured_image_id != current.image_id
    ):
        changed.add("image")
    return sorted(changed)


def _public_config(value: str | None) -> str:
    if value is None or value == DEFAULTS_CONFIG_LABEL:
        return "built-in defaults"
    return str(Path(value).resolve())



def _runtime_url(environment: RuntimeRecord) -> str:
    if environment.url is None:
        raise HostError(
            "runtime_url_missing",
            "LocalCloud runtime URL is unavailable",
            {
                "data_volume": environment.data_volume,
                "container_id": environment.container_id,
            },
        )
    return environment.url


def _active_container_id(environment: RuntimeRecord) -> str:
    if environment.container_id is None:
        raise HostError(
            "runtime_identity_missing",
            "LocalCloud runtime container id is unavailable",
            {
                "data_volume": environment.data_volume,
                "url": environment.url,
            },
        )
    return environment.container_id


def _public_services(value: str | list[str] | tuple[str, ...]) -> str | list[str]:
    if value in ("unavailable", "<default>"):
        return "default" if value == "<default>" else "unavailable"
    if isinstance(value, str):
        return value.split(",") if value else "default"
    return list(value)
