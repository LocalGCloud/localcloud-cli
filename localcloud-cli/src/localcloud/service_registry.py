"""Service registry - loads service definitions from services.yaml."""

from pathlib import Path
from typing import Optional
import os
import yaml


class ServiceDefinition:
    """A single service definition from the registry."""
    def __init__(self, id: str, data: dict, gateway_port: int):
        self.id = id
        self.display_name = data.get("displayName", id)
        raw_port = data.get("port", gateway_port)
        self.port = gateway_port if raw_port == "gateway" else int(raw_port)
        self.protocol = data.get("protocol", "grpc")
        self.env_var = data.get("envVar", "")
        self.env_value_prefix = data.get("envValuePrefix", "")
        self.type = data.get("type", "facade")
        self.default_enabled = data.get("defaultEnabled", False)
        self.additional_ports = data.get("additionalPorts", {})
        self.health_check = data.get("healthCheck", {})
        self.gcloud_api_name = data.get("gcloudApiName")
        self.gcloud_port = data.get("gcloudPort", 0)

    def env_value(self, host: str = "localhost") -> str:
        return f"{self.env_value_prefix}{host}:{self.port}"

    def gcloud_env_var(self) -> Optional[str]:
        """Return the CLOUDSDK_API_ENDPOINT_OVERRIDES_* env var name, or None."""
        if not self.gcloud_api_name:
            return None
        return f"CLOUDSDK_API_ENDPOINT_OVERRIDES_{self.gcloud_api_name.upper()}"

    def gcloud_endpoint(self, host: str = "localhost") -> str:
        """Return the gcloud REST endpoint URL."""
        effective_port = self.gcloud_port if self.gcloud_port else self.port
        return f"http://{host}:{effective_port}/"

    @property
    def is_external(self) -> bool:
        return self.type == "external"

    @property
    def is_facade(self) -> bool:
        return self.type == "facade"


class ServiceRegistry:
    """Central registry of all LocalCloud service definitions."""

    def __init__(self, path: Optional[str] = None):
        yaml_path = self._find_yaml(path)
        with open(yaml_path, encoding="utf-8") as f:
            data = yaml.safe_load(f)

        self.gateway_port = data.get("gateway", {}).get("port", 8080)
        self._services: dict[str, ServiceDefinition] = {}
        for svc_id, svc_data in data.get("services", {}).items():
            self._services[svc_id] = ServiceDefinition(svc_id, svc_data, self.gateway_port)

    def _find_yaml(self, explicit_path: Optional[str] = None) -> str:
        # 1. Explicit path
        if explicit_path and Path(explicit_path).exists():
            return explicit_path
        # 2. Env var
        env_path = os.environ.get("LOCALCLOUD_SERVICES_YAML")
        if env_path and Path(env_path).exists():
            return env_path
        # 3. Walk up from this file to find repo root
        current = Path(__file__).resolve().parent
        for _ in range(10):
            candidate = current / "services.yaml"
            if candidate.exists():
                return str(candidate)
            current = current.parent
        # 4. Container path
        container_path = Path("/etc/localcloud/services.yaml")
        if container_path.exists():
            return str(container_path)
        raise FileNotFoundError("Cannot find services.yaml")

    def get_service(self, name: str) -> Optional[ServiceDefinition]:
        return self._services.get(name)

    def all_services(self) -> dict[str, ServiceDefinition]:
        return dict(self._services)

    def get_default_enabled(self) -> list[str]:
        return [s.id for s in self._services.values() if s.default_enabled]

    def get_port_mappings(self, enabled_services: Optional[list[str]] = None) -> dict[int, int]:
        """Get container:host port mappings for enabled services."""
        ports = {self.gateway_port: self.gateway_port}
        services = enabled_services or self.get_default_enabled()
        for svc_id in services:
            svc = self._services.get(svc_id)
            if svc:
                if svc.is_external:
                    ports[svc.port] = svc.port
                # Additional ports are mapped regardless of service type
                for extra_port in svc.additional_ports.values():
                    ports[extra_port] = extra_port
        return ports

    def get_env_vars(
        self,
        host: str = "localhost",
        enabled_services: Optional[list[str]] = None,
        project_id: str = "local-project",
    ) -> dict[str, str]:
        """Get env var name->value for enabled services (SDK + gcloud CLI).

        Args:
            host: Hostname for endpoint URLs.
            enabled_services: List of service IDs to include. If None, includes all.
            project_id: GCP project ID for CLOUDSDK_CORE_PROJECT.
        """
        env = {}
        for svc in self._services.values():
            if enabled_services and svc.id not in enabled_services:
                continue
            if svc.env_var:
                env[svc.env_var] = svc.env_value(host)
        # gcloud CLI endpoint overrides
        for svc in self._services.values():
            if enabled_services and svc.id not in enabled_services:
                continue
            gcloud_var = svc.gcloud_env_var()
            if gcloud_var:
                env[gcloud_var] = svc.gcloud_endpoint(host)
        env["CLOUDSDK_CORE_PROJECT"] = project_id
        env["CLOUDSDK_AUTH_ACCESS_TOKEN"] = "localcloud-dev-token"
        return env

    def generate_compose_yaml(
        self,
        enabled_services: Optional[list[str]] = None,
        project_id: str = "local-project",
    ) -> str:
        """Generate a docker-compose.yml with ports for enabled services only."""
        services = enabled_services or self.get_default_enabled()
        port_mappings = self.get_port_mappings(enabled_services=services)

        # Build port lines with comments
        port_lines = []
        # Gateway first
        port_lines.append(
            f'      - "127.0.0.1:{self.gateway_port}:{self.gateway_port}"'
            f"    # Gateway (Admin API)"
        )
        # Service ports
        for svc_id in services:
            svc = self._services.get(svc_id)
            if not svc:
                continue
            if svc.is_external and svc.port in port_mappings:
                port_lines.append(
                    f'      - "127.0.0.1:{svc.port}:{svc.port}"'
                    f"    # {svc.display_name}"
                )
            for label, extra_port in svc.additional_ports.items():
                if extra_port in port_mappings:
                    port_lines.append(
                        f'      - "127.0.0.1:{extra_port}:{extra_port}"'
                        f"    # {svc.display_name} ({label})"
                    )

        services_csv = ",".join(services)
        ports_block = "\n".join(port_lines)

        return f"""\
# Generated by: localcloud compose --services {services_csv}
# Service ports derived from services.yaml
services:
  localcloud:
    image: localcloud/localcloud:latest
    container_name: localcloud-main
    ports:
{ports_block}
    mem_limit: 4g
    environment:
      LOCALCLOUD_PROJECT: "${{LOCALCLOUD_PROJECT:-{project_id}}}"
      LOCALCLOUD_SERVICES: "{services_csv}"
    volumes:
      - localcloud-data:/var/lib/localcloud
      - ./services.yaml:/etc/localcloud/services.yaml:ro
      - "${{LOCALCLOUD_SEED_FILE:-./seed.yaml}}:/etc/localcloud/seed.yaml:ro"
      - /var/run/docker.sock:/var/run/docker.sock
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:{self.gateway_port}/_localcloud/health"]
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  localcloud-data:
"""


# Module-level convenience: load registry once
_registry: Optional[ServiceRegistry] = None

def get_registry(path: Optional[str] = None) -> ServiceRegistry:
    global _registry
    if _registry is None:
        _registry = ServiceRegistry(path)
    return _registry
