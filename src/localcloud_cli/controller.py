from __future__ import annotations

import re
from pathlib import Path
from typing import Any

from . import __version__

from .config import (
    DEFAULTS_CONFIG_LABEL,
    DEFAULT_IMAGE,
    DEFAULT_PROJECT,
    DEFAULT_USER,
    HostPaths,
    LocalCloudConfig,
    instance_lock,
    validate_instance,
    validate_project,
    validate_user,
)
from .docker_runtime import DockerRuntime, resource_names
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

    def remembered_config(self, instance: str) -> str | None:
        selected = validate_instance(instance)
        current = self.runtime.inspect(selected)
        if current is None:
            return None
        value = current.get("config_path")
        return None if value in {None, DEFAULTS_CONFIG_LABEL} else str(value)

    def start(self, config: LocalCloudConfig) -> dict[str, Any]:
        with instance_lock(self.paths, config.instance):
            current = self.runtime.inspect(config.instance)
            changed_fields: list[str] = []
            fresh_data = False
            if current is None:
                environment = self.runtime.create(config)
                fresh_data = bool(environment.get("volume_created", True))
                status = "started"
            elif current["config_hash"] != config.config_hash:
                changed_fields = _changed_fields(current, config)
                environment = self._replace(current, config)
                fresh_data = bool(environment.get("volume_created", False))
                status = "reconfigured"
            elif current["state"] == "running":
                environment = current
                status = "already_running"
            else:
                environment = self.runtime.start(current)
                status = "started"

            project_created = self._ensure_project(environment, config.project, config.user)
            if config.seed_yaml is not None and (fresh_data or project_created):
                self._seed_project(environment, config)
            return self._payload(
                status,
                environment,
                project=config.project,
                user=config.user,
                include_sdk=True,
                changed_fields=changed_fields,
            )

    def restart(self, config: LocalCloudConfig) -> dict[str, Any]:
        with instance_lock(self.paths, config.instance):
            current = self.runtime.inspect(config.instance)
            changed_fields: list[str] = []
            if current is None:
                environment = self.runtime.create(config)
                status = "restarted"
            elif current["config_hash"] != config.config_hash:
                changed_fields = _changed_fields(current, config)
                environment = self._replace(current, config)
                status = "reconfigured"
            else:
                if current["state"] == "running":
                    self.runtime.stop(current)
                environment = self.runtime.start(current)
                status = "restarted"

            self._require_project(environment, config.project, config.user)
            if config.seed_yaml is not None:
                self._seed_project(environment, config, volatile_only=True)
            return self._payload(
                status,
                environment,
                project=config.project,
                user=config.user,
                include_sdk=True,
                changed_fields=changed_fields,
            )

    def reset(
        self, config: LocalCloudConfig, *, all_projects: bool = False
    ) -> dict[str, Any]:
        with instance_lock(self.paths, config.instance):
            if all_projects:
                current = self.runtime.inspect(config.instance)
                if current is None:
                    self.runtime.purge(config.instance)
                else:
                    self.runtime.remove(current, remove_volume=True)
                environment = self.runtime.create(config)
                self._require_project(environment, config.project, config.user)
                if config.seed_yaml is not None:
                    self._seed_project(environment, config)
                return self._payload(
                    "reset",
                    environment,
                    project=config.project,
                    user=config.user,
                    include_sdk=True,
                    reset_scope="all_projects",
                )

            environment = self._ensure_running(config)
            self._require_project(environment, config.project, config.user)
            client = JavaMcpClient(environment["url"], config.project, config.user)
            try:
                client.reset_project()
            except Exception as error:
                raise HostError(
                    "project_reset_failed",
                    "LocalCloud project could not be reset",
                    {
                        "instance": config.instance,
                        "project": config.project,
                        "cause": str(error),
                    },
                ) from error
            if config.seed_yaml is not None:
                self._seed_project(environment, config)
            return self._payload(
                "reset",
                environment,
                project=config.project,
                user=config.user,
                include_sdk=True,
                reset_scope="project",
            )

    def stop(self, instance: str) -> dict[str, Any]:
        selected = validate_instance(instance)
        with instance_lock(self.paths, selected):
            current = self.runtime.inspect(selected)
            if current is None:
                return self._absent_payload("not_running", selected)
            if current["data"] == "ephemeral":
                self.runtime.remove(current, remove_volume=True)
                removed = dict(current, state="removed", url=None, endpoint_map={})
                return self._payload("stopped", removed, include_sdk=False)
            if current["state"] == "running":
                self.runtime.stop(current)
            stopped = dict(current, state="stopped", url=None)
            return self._payload("stopped", stopped, include_sdk=False)

    def status(self, instance: str) -> dict[str, Any]:
        selected = validate_instance(instance)
        current = self.runtime.inspect(selected)
        if current is None:
            return self._absent_payload("not_created", selected)
        if current["state"] != "running":
            return self._payload("stopped", current, include_sdk=False)
        if not self.runtime.is_ready(current):
            return self._payload("unhealthy", current, include_sdk=False)
        return self._payload("running", current, include_sdk=False)

    def logs(self, instance: str, tail: int = 200) -> dict[str, Any]:
        selected = validate_instance(instance)
        current = self.runtime.inspect(selected)
        if current is None:
            raise HostError(
                "instance_not_created",
                f"No LocalCloud instance named {selected!r} exists",
                {"instance": selected},
            )
        result = self._payload("logs", current, include_sdk=False)
        result["logs"] = self.runtime.logs(current, tail=tail)
        return result

    def target(
        self,
        instance: str,
        project: str = DEFAULT_PROJECT,
        user: str = DEFAULT_USER,
    ) -> dict[str, Any]:
        selected_instance = validate_instance(instance)
        selected_project = validate_project(project)
        selected_user = validate_user(user)
        current = self.runtime.inspect(selected_instance)
        recovery = (
            f"Run localcloud start --instance {selected_instance} "
            f"--project-id {selected_project} --user {selected_user} before connecting."
        )
        if (
            current is None
            or current["state"] != "running"
            or not self.runtime.is_ready(current)
        ):
            raise HostError(
                "instance_not_running",
                recovery,
                {
                    "instance": selected_instance,
                    "project": selected_project,
                    "user": selected_user,
                },
            )
        if not self._project_exists(current, selected_project, selected_user):
            raise HostError(
                "unknown_project",
                (
                    f"Project {selected_project!r} does not exist; run localcloud start "
                    f"--instance {selected_instance} --project-id {selected_project}."
                ),
                {"instance": selected_instance, "project": selected_project},
            )
        return {
            "instance": selected_instance,
            "project": selected_project,
            "user": selected_user,
            "url": current["url"],
            "endpoint_map": dict(current["endpoint_map"]),
            "services": _public_services(current["services"]),
            "data": current["data"],
            "config_hash": current["config_hash"],
            "config_path": _public_config(current["config_path"]),
            "container": current["name"],
            "network": current["network_name"],
            "volume": current["volume_name"],
            "labels": dict(current["labels"]),
        }

    def mcp_target(self, instance: str, project: str, user: str) -> dict[str, Any]:
        return self.target(instance, project, user)

    def release_mcp_target(self, instance: str, project: str, user: str) -> None:
        validate_instance(instance)
        validate_project(project)
        validate_user(user)

    def doctor(self) -> dict[str, Any]:
        result = self.runtime.doctor()
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
        if legacy_host_state or legacy_locks:
            warning = (
                "Legacy host state and locks are not migrated or removed automatically; "
                "clean them up manually after confirming they are unused."
            )
            result["warning"] = f"{result.get('warning', '')} {warning}".strip()
        return result

    def _ensure_running(self, config: LocalCloudConfig) -> dict[str, Any]:
        current = self.runtime.inspect(config.instance)
        if current is None:
            return self.runtime.create(config)
        if current["config_hash"] != config.config_hash:
            return self._replace(current, config)
        if current["state"] != "running":
            return self.runtime.start(current)
        if not self.runtime.is_ready(current):
            self.runtime.stop(current)
            return self.runtime.start(current)
        return current

    def _replace(
        self, current: dict[str, Any], config: LocalCloudConfig
    ) -> dict[str, Any]:
        preserve_volume = (
            current["data"] == "persistent" and config.data == "persistent"
        )
        self.runtime.remove(current, remove_volume=not preserve_volume)
        return self.runtime.create(config)

    @staticmethod
    def _project_exists(
        environment: dict[str, Any], project: str, user: str
    ) -> bool:
        try:
            return JavaMcpClient(environment["url"], project, user).project_exists()
        except Exception as error:
            raise HostError(
                "project_lookup_failed",
                "LocalCloud project catalog could not be read",
                {
                    "instance": environment["instance"],
                    "project": project,
                    "cause": str(error),
                },
            ) from error

    def _ensure_project(
        self, environment: dict[str, Any], project: str, user: str
    ) -> bool:
        client = JavaMcpClient(environment["url"], project, user)
        try:
            if client.project_exists():
                return False
            client.create_project()
            return True
        except Exception as error:
            raise HostError(
                "project_create_failed",
                "LocalCloud project could not be ensured",
                {
                    "instance": environment["instance"],
                    "project": project,
                    "cause": str(error),
                },
            ) from error
    def _require_project(
        self, environment: dict[str, Any], project: str, user: str
    ) -> None:
        if self._project_exists(environment, project, user):
            return
        raise HostError(
            "unknown_project",
            (
                f"Project {project!r} does not exist; run localcloud start "
                f"--instance {environment['instance']} --project-id {project}."
            ),
            {"instance": environment["instance"], "project": project},
        )


    @staticmethod
    def _seed_project(
        environment: dict[str, Any],
        config: LocalCloudConfig,
        *,
        volatile_only: bool = False,
    ) -> None:
        try:
            JavaMcpClient(
                environment["url"], config.project, config.user
            ).seed_project(
                config.seed_yaml or "", volatile_only=volatile_only
            )
        except Exception as error:
            raise HostError(
                "seed_failed",
                "LocalCloud project seed could not be applied",
                {
                    "instance": config.instance,
                    "project": config.project,
                    "seed": str(config.seed_path) if config.seed_path else None,
                    "cause": str(error),
                },
            ) from error

    def _payload(
        self,
        status: str,
        environment: dict[str, Any],
        *,
        project: str | None = None,
        user: str | None = None,
        include_sdk: bool,
        changed_fields: list[str] | None = None,
        reset_scope: str | None = None,
    ) -> dict[str, Any]:
        url = environment.get("url") if include_sdk else None
        sdk_env: Any = {}
        if include_sdk and url and project is not None and user is not None:
            from .endpoints import environment_config

            sdk_env = environment_config(
                environment, project, user, output_format="json"
            )
            if not isinstance(sdk_env, dict):
                raise HostError(
                    "invalid_environment",
                    "Java MCP JSON environment configuration must be an object",
                )
        result: dict[str, Any] = {
            "status": status,
            "instance": environment["instance"],
            "config": _public_config(environment["config_path"]),
            "services": _public_services(environment["services"]),
            "data": environment["data"],
            "container": {
                "name": environment["name"],
                "state": environment["state"],
                "url": url,
            },
            "network": environment["network_name"],
            "volume": environment["volume_name"],
            "sdk_env": sdk_env,
        }
        if project is not None and user is not None:
            result["project"] = project
            result["user"] = user
            result["mcp"] = {
                "command": "localcloud",
                "args": [
                    "mcp",
                    "--instance",
                    environment["instance"],
                    "--project-id",
                    project,
                    "--user",
                    user,
                ],
                "direct_url": f"{url}/mcp" if url else None,
                "headers": {
                    "X-LocalCloud-Project": project,
                    "X-LocalCloud-User": user,
                },
            }
        if changed_fields:
            result["changed_fields"] = changed_fields
        if reset_scope is not None:
            result["reset_scope"] = reset_scope
        return result

    def _absent_payload(self, status: str, instance: str) -> dict[str, Any]:
        names = resource_names(instance)
        return self._payload(
            status,
            {
                "instance": instance,
                "name": names["container"],
                "network_name": names["network"],
                "volume_name": names["volume"],
                "state": "absent",
                "url": None,
                "endpoint_map": {},
                "config_path": DEFAULTS_CONFIG_LABEL,
                "services": "<default>",
                "data": "persistent",
            },
            include_sdk=False,
        )


def _changed_fields(
    current: dict[str, Any], config: LocalCloudConfig
) -> list[str]:
    previous = current.get("instance_config")
    selected = _instance_config(config)
    if not isinstance(previous, dict):
        return ["configuration"]
    return sorted(
        key for key in set(previous) | set(selected) if previous.get(key) != selected.get(key)
    )


def _instance_config(config: LocalCloudConfig) -> dict[str, Any]:
    return {
        "instance": config.instance,
        "services": list(config.services) if config.services is not None else None,
        "data": config.data,
        "image": config.image,
        "memory": config.memory,
        "docker_socket": config.docker_socket,
        "transparent_network": config.transparent_network,
        "environment": dict(config.environment),
        "container_name": config.container_name,
        "network_name": config.network_name,
        "volume_name": config.volume_name,
    }


def _public_config(value: str | None) -> str | None:
    return None if value in {None, DEFAULTS_CONFIG_LABEL} else str(Path(value).resolve())


def _public_services(value: str) -> str | list[str]:
    return "default" if value == "<default>" else value.split(",")
