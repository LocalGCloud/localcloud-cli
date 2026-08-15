from __future__ import annotations

import re
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
    LocalCloudConfig,
    data_volume_lock,
    load_active_runtime,
    load_config,
    runtime_settings,
    save_active_runtime,
)
from .docker_runtime import DockerRuntime, RuntimeRecord
from .errors import HostError
from .java_client import JavaMcpClient


_LEGACY_LOCK = re.compile(r"^[0-9a-f]{64}\.lock$")


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
        return None if value in {None, DEFAULTS_CONFIG_LABEL} else str(value)

    def start(self, config: LocalCloudConfig) -> dict[str, Any]:
        with data_volume_lock(self.paths, config.data_volume):
            current = self.runtime.resolve(
                config, preferred_container_id=self._preferred_container_id(config)
            )
            changed_fields: list[str] = []
            fresh_data = False
            if current is None:
                environment = self.runtime.create(config)
                fresh_data = environment.volume_created
                status = "started"
            elif self._requires_managed_replacement(current, config):
                changed_fields = _changed_fields(current, config)
                environment = self._replace(current, config)
                fresh_data = environment.volume_created
                status = "reconfigured"
            elif current.state != "running":
                environment = self.runtime.start(config, current)
                status = "started"
            elif not self.runtime.is_ready(current):
                environment = self._recover_unready(config, current)
                status = "restarted"
            else:
                environment = current
                status = "already_running"

            project_created = self._ensure_project(
                environment, config.project, config.user
            )
            if config.seed_yaml is not None and (fresh_data or project_created):
                self._seed_project(environment, config)
            self._record_active(environment, config)
            return self._payload(
                status,
                environment,
                config,
                include_sdk=True,
                changed_fields=changed_fields,
            )

    def restart(self, config: LocalCloudConfig) -> dict[str, Any]:
        with data_volume_lock(self.paths, config.data_volume):
            current = self.runtime.resolve(
                config, preferred_container_id=self._preferred_container_id(config)
            )
            changed_fields: list[str] = []
            if current is None:
                environment = self.runtime.create(config)
                status = "restarted"
            elif self._requires_managed_replacement(current, config):
                changed_fields = _changed_fields(current, config)
                environment = self._replace(current, config)
                status = "reconfigured"
            else:
                environment = self.runtime.restart(config, current)
                status = "restarted"

            self._require_project(environment, config.project, config.user)
            if config.seed_yaml is not None:
                self._seed_project(environment, config, volatile_only=True)
            self._record_active(environment, config)
            return self._payload(
                status,
                environment,
                config,
                include_sdk=True,
                changed_fields=changed_fields,
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
                environment.url, config.project, config.user
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
            return self._absent_payload("not_created", config)
        if current.state != "running":
            return self._payload("stopped", current, config, include_sdk=False)
        if not self.runtime.is_ready(current):
            return self._payload("unhealthy", current, config, include_sdk=False)
        return self._payload("running", current, config, include_sdk=False)

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
        result["active_runtime"] = active_status
        result["active_runtime_diagnostics"] = active_diagnostics
        legacy_host_state = [
            name
            for name in (
                "state.db",
                "daemon.sock",
                "daemon.pid",
                "daemon.lock",
                "daemon.log",
            )
            if (self.paths.home / name).exists()
        ]
        legacy_locks: list[str] = []
        if self.paths.locks.is_dir():
            legacy_locks = sorted(
                path.name
                for path in self.paths.locks.iterdir()
                if path.is_file() and _LEGACY_LOCK.fullmatch(path.name)
            )
        result["legacy_host_state"] = legacy_host_state
        result["cli_version"] = __version__
        result["default_image"] = DEFAULT_IMAGE
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
                "Legacy host state and locks are not migrated or removed automatically; clean them up manually after confirming they are unused."
            )
        warning = " ".join(item for item in warnings if item).strip()
        if warning:
            result["warning"] = warning
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
    def _recover_unready(
        self, config: LocalCloudConfig, current: RuntimeRecord
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
        return self.runtime.restart(config, current)


    @staticmethod
    def _requires_managed_replacement(
        current: RuntimeRecord, config: LocalCloudConfig
    ) -> bool:
        return (
            current.ownership["container"] == "managed"
            and current.config_hash != config.config_hash
        )

    def _replace(
        self, current: RuntimeRecord, config: LocalCloudConfig
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
        preserve_volume = (
            current.data == "persistent" and config.data == "persistent"
        ) or current.ownership["data_volume"] == "attached"
        self.runtime.preflight_create(config, current)
        self.runtime.remove(
            config, current, remove_volume=not preserve_volume
        )
        return self.runtime.create(config)

    @staticmethod
    def _project_exists(
        environment: RuntimeRecord, project: str, user: str
    ) -> bool:
        try:
            return JavaMcpClient(
                environment.url, project, user
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

    def _ensure_project(
        self, environment: RuntimeRecord, project: str, user: str
    ) -> bool:
        client = JavaMcpClient(environment.url, project, user)
        try:
            if client.project_exists():
                return False
            client.create_project()
            if not client.project_exists():
                raise RuntimeError("project was not readable after creation")
            return True
        except Exception as error:
            raise HostError(
                "project_create_failed",
                "LocalCloud project could not be ensured",
                {
                    "data_volume": environment.data_volume,
                    "project": project,
                    "cause": str(error),
                },
            ) from error

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
    ) -> None:
        try:
            JavaMcpClient(
                environment.url, config.project, config.user
            ).seed_project(
                config.seed_yaml or "", volatile_only=volatile_only
            )
        except Exception as error:
            raise HostError(
                "seed_failed",
                "LocalCloud project seed could not be applied",
                {
                    "data_volume": config.data_volume,
                    "project": config.project,
                    "seed": str(config.seed_path) if config.seed_path else None,
                    "cause": str(error),
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
                container_id=environment.container_id,
            ),
        )

    def _preferred_container_id(self, config: LocalCloudConfig) -> str | None:
        active = load_active_runtime(self.paths)
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
            services: str | tuple[str, ...] = (
                "<default>" if config.services is None else config.services
            )
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
            services = environment.services
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
    return sorted(
        key
        for key in set(previous) | set(selected)
        if previous.get(key) != selected.get(key)
    )


def _public_config(value: str | None) -> str | None:
    return None if value in {None, DEFAULTS_CONFIG_LABEL} else str(Path(value).resolve())


def _public_services(value: str | list[str] | tuple[str, ...]) -> str | list[str]:
    if value == "<default>":
        return "default"
    if isinstance(value, str):
        return value.split(",") if value else "default"
    return list(value)
