from __future__ import annotations

import time
from dataclasses import replace
from pathlib import Path
from typing import Any

from . import __version__
from .config import (
    ACTIVE_RUNTIME_SCHEMA_VERSION,
    DEFAULTS_CONFIG_LABEL,
    DEFAULT_IMAGE,
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
from .docker_runtime import DockerRuntime, RuntimeRecord
from .errors import HostError
from .java_client import JavaMcpClient, is_retryable_java_error

_START_READINESS_TIMEOUT = 60.0
_READINESS_REQUEST_TIMEOUT = 5.0
_READINESS_POLL_INTERVAL = 1.0


class Controller:
    def __init__(
        self,
        runtime: DockerRuntime | None = None,
        paths: HostPaths | None = None,
    ):
        self.runtime = runtime if runtime is not None else DockerRuntime()
        self.paths = paths if paths is not None else HostPaths.from_environment()

    def remembered_config(self, config: LocalCloudConfig) -> str | None:
        current = self.runtime.resolve(
            config, preferred_container_id=self._preferred_container_id(config)
        )
        if current is None:
            return None
        value = current.config_path
        return (
            DEFAULTS_CONFIG_LABEL
            if value in {None, DEFAULTS_CONFIG_LABEL}
            else str(value)
        )

    def start(
        self,
        config: LocalCloudConfig,
        *,
        pull: bool = False,
        observer: Any | None = None,
        tail: float | None = 0.0,
    ) -> dict[str, Any]:
        start_time = time.monotonic()
        deadline = start_time + _START_READINESS_TIMEOUT
        with data_volume_lock(self.paths, config.data_volume):
            current = self.runtime.resolve(
                config, preferred_container_id=self._preferred_container_id(config)
            )
            changed_fields: list[str] = []
            fresh_data = False
            if current is None:
                if observer is not None and hasattr(observer, "starting"):
                    observer.starting(config)
                prepared_image = self.runtime.preflight_create(
                    config,
                    pull=pull,
                    observer=observer,
                )
                deadline = time.monotonic() + _START_READINESS_TIMEOUT
                self._remaining_readiness(
                    deadline,
                    config,
                    current,
                    "docker_health",
                )
                environment = self.runtime.create(
                    config,
                    readiness_deadline=deadline,
                    observer=observer,
                    prepared_image=prepared_image,
                )
                self._emit_runtime_logs(observer, config, environment)
                fresh_data = environment.volume_created
                status = "started"
            elif pull or self._requires_managed_replacement(current, config):
                if observer is not None and hasattr(observer, "starting"):
                    observer.starting(config)
                changed_fields = _changed_fields(current, config)
                prepared_image = None
                if (
                    current.ownership["container"] == "managed"
                    and current.ownership["network"] == "managed"
                ):
                    prepared_image = self.runtime.preflight_create(
                        config,
                        current,
                        pull=pull,
                        observer=observer,
                    )
                    deadline = time.monotonic() + _START_READINESS_TIMEOUT
                self._remaining_readiness(
                    deadline,
                    config,
                    current,
                    "docker_health",
                )
                environment = self._replace(
                    current,
                    config,
                    pull=pull,
                    readiness_deadline=deadline,
                    observer=observer,
                    prepared_image=prepared_image,
                )
                fresh_data = environment.volume_created
                status = "reconfigured" if self._requires_managed_replacement(current, config) else "started"
            elif current.state != "running":
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
                status = "started"
            elif not self.runtime.is_ready(current):
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
                status = "already_running"
            else:
                environment = current
                status = "already_running"
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
                logs=self._runtime_logs(config, environment) if status != "already_running" else None,
            )

    def restart(
        self,
        config: LocalCloudConfig,
        *,
        pull: bool = False,
        observer: Any | None = None,
        tail: float | None = 0.0,
    ) -> dict[str, Any]:
        start_time = time.monotonic()
        with data_volume_lock(self.paths, config.data_volume):
            current = self.runtime.resolve(
                config, preferred_container_id=self._preferred_container_id(config)
            )
            changed_fields: list[str] = []
            if current is None:
                environment = self.runtime.create(config, pull=pull, observer=observer)
                self._emit_runtime_logs(observer, config, environment)
            elif (
                pull
                or self._requires_managed_replacement(current, config)
                or (
                    current.ownership["container"] == "managed"
                    and current.configured_image_id
                    and current.image_id
                    and current.configured_image_id != current.image_id
                )
            ):
                changed_fields = _changed_fields(current, config)
                environment = self._replace(
                    current,
                    config,
                    pull=pull,
                    observer=observer,
                )
                status = (
                    "reconfigured"
                    if self._requires_managed_replacement(current, config)
                    or (
                        current.configured_image_id
                        and current.image_id
                        and current.configured_image_id != current.image_id
                    )
                    else "restarted"
                )
            else:
                environment = self.runtime.restart(config, current, observer=observer)
                self._emit_runtime_logs(observer, config, environment)
                status = "restarted"
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
        self, config: LocalCloudConfig, *, all_projects: bool = False
    ) -> dict[str, Any]:
        with data_volume_lock(self.paths, config.data_volume):
            if all_projects:
                current = self.runtime.resolve(
                    config,
                    preferred_container_id=self._preferred_container_id(config),
                )
                self.runtime.preflight_create(config, current)
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
                self.runtime.purge(config)
                environment = self.runtime.create(config)
                self._require_project(environment, config.project, config.user)
                if config.seed_yaml is not None:
                    self._seed_project(environment, config)
                return self._payload(
                    "reset",
                    environment,
                    config,
                    include_sdk=True,
                    reset_scope="all_projects",
                )

            environment = self._ensure_running(config)
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

    def stop(self, config: LocalCloudConfig) -> dict[str, Any]:
        with data_volume_lock(self.paths, config.data_volume):
            current = self.runtime.resolve(
                config, preferred_container_id=self._preferred_container_id(config)
            )
            if current is None:
                return self._absent_payload("not_running", config)
            if current.state != "running":
                return self._payload(
                    "not_running", current, config, include_sdk=False
                )
            if current.data == "ephemeral" and current.origin == "managed":
                self.runtime.remove(config, current, remove_volume=True)
                removed = replace(
                    current,
                    state="removed",
                    health=None,
                    url=None,
                    endpoint_map={},
                )
                return self._payload("stopped", removed, config, include_sdk=False)
            stopped = self.runtime.stop(config, current)
            return self._payload("stopped", stopped, config, include_sdk=False)

    def status(self, config: LocalCloudConfig) -> dict[str, Any]:
        current = self.runtime.resolve(
            config, preferred_container_id=self._preferred_container_id(config)
        )
        if current is None:
            result = self._absent_payload("not_created", config)
        elif current.state != "running":
            result = self._payload("stopped", current, config, include_sdk=False)
        elif not self.runtime.is_ready(current):
            result = self._payload("unhealthy", current, config, include_sdk=False)
        else:
            result = self._payload("running", current, config, include_sdk=False)

        image_name = str(result["container"]["configured_image"])
        result["container"]["image_details"] = self.runtime.image_details(image_name)
        return result

    def logs(self, config: LocalCloudConfig, tail: int = 200) -> dict[str, Any]:
        current = self.runtime.resolve(
            config,
            preferred_container_id=self._preferred_container_id(config),
            require=True,
        )
        assert current is not None
        result = self._payload("logs", current, config, include_sdk=False)
        result["logs"] = self.runtime.logs(config, current, tail=tail)
        return result

    def target(self, config: LocalCloudConfig) -> dict[str, Any]:
        current = self.runtime.resolve(
            config, preferred_container_id=self._preferred_container_id(config)
        )
        recovery = (
            f"Run localcloud start --data-volume {config.data_volume} "
            f"--project-id {config.project} --user {config.user} before connecting."
        )
        if (
            current is None
            or current.state != "running"
            or not self.runtime.is_ready(current)
        ):
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
        return {
            "url": current.url,
            "endpoint_map": dict(current.endpoint_map),
        }


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
    ) -> RuntimeRecord:
        if current.ownership["container"] != "managed":
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
        return self.runtime.restart(
            config,
            current,
            readiness_deadline=readiness_deadline,
        )


    @staticmethod
    def _requires_managed_replacement(
        current: RuntimeRecord, config: LocalCloudConfig
    ) -> bool:
        return (
            current.ownership["container"] == "managed"
            and current.config_hash != config.config_hash
        )
    def _replace(
        self,
        current: RuntimeRecord,
        config: LocalCloudConfig,
        *,
        pull: bool = False,
        readiness_deadline: float | None = None,
        observer: Any | None = None,
        prepared_image: tuple[Any, bool] | None = None,
    ) -> RuntimeRecord:
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
            config, current, remove_volume=not preserve_volume
        )
        try:
            environment = self.runtime.create(
                config,
                readiness_deadline=readiness_deadline,
                observer=observer,
                prepared_image=prepared_image,
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
        environment: RuntimeRecord, project: str, user: str
    ) -> bool:
        try:
            return JavaMcpClient(
                _runtime_url(environment), project, user
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
                "image_status": self.runtime.image_status(config.image),
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
                "url": url,
                "configured_image": environment.configured_image,
                "actual_image": environment.actual_image,
                "image_id": environment.image_id,
                "image_status": environment.image_status or self.runtime.image_status(environment.configured_image),
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
        self, status: str, config: LocalCloudConfig
    ) -> dict[str, Any]:
        return self._payload(status, None, config, include_sdk=False)


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
