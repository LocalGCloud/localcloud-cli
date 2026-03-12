"""Docker container management for LocalCloud."""

import time
from pathlib import Path
from typing import Optional

import docker
import docker.errors
import requests


# Default port mappings: host_port -> container_port
DEFAULT_PORT_MAPPINGS = {
    8080: 8080,   # Gateway (admin API)
    4443: 4443,   # Cloud Storage
    8085: 8085,   # Pub/Sub
    8086: 8086,   # Firestore
    8087: 8087,   # Bigtable
    9010: 9010,   # Spanner gRPC
    9020: 9020,   # Spanner REST
    9050: 9050,   # BigQuery REST
    9060: 9060,   # BigQuery gRPC
    6443: 6443,   # GKE k3d Kubernetes API
}

# Service -> (port, env_var)
SERVICE_ENV_VARS = {
    "storage":   (4443, "STORAGE_EMULATOR_HOST"),
    "pubsub":    (8085, "PUBSUB_EMULATOR_HOST"),
    "firestore": (8086, "FIRESTORE_EMULATOR_HOST"),
    "bigtable":  (8087, "BIGTABLE_EMULATOR_HOST"),
    "spanner":   (9010, "SPANNER_EMULATOR_HOST"),
    "bigquery":  (9050, "BIGQUERY_EMULATOR_HOST"),
}


class DockerManager:
    """Manages the LocalCloud Docker container lifecycle."""

    def __init__(
        self,
        image: str = "localcloud/localcloud:latest",
        container_name: str = "localcloud-main",
        gateway_port: int = 8080,
    ):
        self.image = image
        self.container_name = container_name
        self.gateway_port = gateway_port
        self._client = None

    @property
    def client(self):
        if self._client is None:
            self._client = docker.from_env()
        return self._client

    # -----------------------------------------------------------------
    # Image management
    # -----------------------------------------------------------------

    def pull_image(self):
        """Pull the LocalCloud Docker image. Returns the image object."""
        return self.client.images.pull(self.image)

    def image_exists(self) -> bool:
        """Check whether the configured image already exists locally."""
        try:
            self.client.images.get(self.image)
            return True
        except docker.errors.ImageNotFound:
            return False

    # -----------------------------------------------------------------
    # Container lifecycle
    # -----------------------------------------------------------------

    def start(
        self,
        project_id: str = "local-project",
        services: Optional[list[str]] = None,
        data_dir: str = "./localcloud-data",
        detach: bool = True,
    ) -> docker.models.containers.Container:
        """Create and start the LocalCloud container.

        If a container with the same name already exists and is stopped it will
        be removed before creating a new one.
        """
        # Remove stale container if present
        existing = self._get_container()
        if existing is not None:
            if existing.status == "running":
                raise RuntimeError(
                    f"Container '{self.container_name}' is already running. "
                    "Stop it first with 'localcloud stop'."
                )
            existing.remove(force=True)

        # Resolve data directory to an absolute path
        abs_data_dir = str(Path(data_dir).resolve())

        # Build environment variables
        env = {
            "LOCALCLOUD_PROJECT": project_id,
        }
        if services:
            env["LOCALCLOUD_SERVICES"] = ",".join(services)

        # Port bindings
        ports = {f"{cp}/tcp": hp for hp, cp in DEFAULT_PORT_MAPPINGS.items()}

        # Volume mounts
        volumes = {
            abs_data_dir: {"bind": "/var/lib/localcloud", "mode": "rw"},
            "/var/run/docker.sock": {"bind": "/var/run/docker.sock", "mode": "rw"},
        }

        container = self.client.containers.run(
            self.image,
            name=self.container_name,
            detach=detach,
            ports=ports,
            volumes=volumes,
            environment=env,
        )
        return container

    def stop(self, remove: bool = False, timeout: int = 10):
        """Stop the LocalCloud container.

        Args:
            remove: If True, remove the container after stopping.
            timeout: Seconds to wait before force-killing.
        """
        container = self._get_container()
        if container is None:
            raise RuntimeError(
                f"Container '{self.container_name}' not found."
            )

        if container.status == "running":
            container.stop(timeout=timeout)

        if remove:
            container.remove(force=True)

    def status(self) -> dict:
        """Return a dict with container status details.

        Returns a dictionary with keys: name, status, image, ports, created.
        If the container does not exist, returns a dict with status 'not_found'.
        """
        container = self._get_container()
        if container is None:
            return {
                "name": self.container_name,
                "status": "not_found",
            }
        container.reload()
        return {
            "name": container.name,
            "status": container.status,
            "image": str(container.image.tags[0]) if container.image.tags else str(container.image.id),
            "ports": container.ports,
            "created": container.attrs.get("Created", ""),
        }

    def logs(self, follow: bool = False, tail: int = 100):
        """Return container logs.

        Args:
            follow: Stream logs in real-time.
            tail: Number of lines from the end to return.

        Returns:
            If follow is False, returns the log string.
            If follow is True, returns a generator that yields log lines.
        """
        container = self._get_container()
        if container is None:
            raise RuntimeError(
                f"Container '{self.container_name}' not found."
            )

        if follow:
            return container.logs(stream=True, follow=True, tail=tail)
        else:
            return container.logs(tail=tail).decode("utf-8", errors="replace")

    def is_running(self) -> bool:
        """Check if the container exists and is running."""
        container = self._get_container()
        if container is None:
            return False
        container.reload()
        return container.status == "running"

    # -----------------------------------------------------------------
    # Server health / admin queries
    # -----------------------------------------------------------------

    def health_check(self, timeout: float = 2.0) -> dict:
        """Query /_localcloud/health and return the JSON response.

        Raises requests.RequestException on failure.
        """
        url = f"http://localhost:{self.gateway_port}/_localcloud/health"
        resp = requests.get(url, timeout=timeout)
        resp.raise_for_status()
        return resp.json()

    def wait_until_healthy(self, max_wait: int = 60, interval: float = 1.0) -> dict:
        """Poll the health endpoint until it returns successfully.

        Args:
            max_wait: Maximum seconds to wait.
            interval: Seconds between polls.

        Returns:
            The health check JSON response.

        Raises:
            TimeoutError: If the server does not become healthy in time.
        """
        deadline = time.time() + max_wait
        last_error = None
        while time.time() < deadline:
            try:
                return self.health_check(timeout=2.0)
            except Exception as exc:
                last_error = exc
                time.sleep(interval)

        raise TimeoutError(
            f"LocalCloud did not become healthy within {max_wait}s. "
            f"Last error: {last_error}"
        )

    def get_env_vars(self, fmt: str = "json", host: str = "localhost") -> str:
        """Query /_localcloud/env and return the raw response text.

        Args:
            fmt: 'shell', 'json', or 'docker-compose'.
            host: Host value to pass to the endpoint.
        """
        url = f"http://localhost:{self.gateway_port}/_localcloud/env"
        resp = requests.get(url, params={"format": fmt, "host": host}, timeout=5.0)
        resp.raise_for_status()
        return resp.text

    # -----------------------------------------------------------------
    # Internal helpers
    # -----------------------------------------------------------------

    def _get_container(self):
        """Return the container object or None if it does not exist."""
        try:
            return self.client.containers.get(self.container_name)
        except docker.errors.NotFound:
            return None
