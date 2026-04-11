"""Seed file processing - validates and loads seed YAML into the LocalCloud server."""

from pathlib import Path

import requests
import yaml


# Supported seed sections that can appear in a seed YAML file.
SUPPORTED_SECTIONS = frozenset({
    "gcs",
    "pubsub",
    "firestore",
    "bigquery",
    "secretmanager",
    "cloudtasks",
    "spanner",
    "bigtable",
    "memorystore",
})


class SeedProcessor:
    """Processes seed YAML files and loads data into a running LocalCloud server.

    The processor reads and validates a seed YAML file, then POSTs the content
    to the server's ``/_localcloud/seed`` endpoint which handles the actual
    data loading.
    """

    def __init__(self, base_url: str):
        """Initialise the processor.

        Args:
            base_url: The LocalCloud server URL, e.g. ``http://localhost:8080``.
        """
        self.base_url = base_url.rstrip("/")

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def load_seed_file(self, seed_path: str) -> dict:
        """Read, validate, and POST a seed YAML file to the server.

        Args:
            seed_path: Filesystem path to the seed YAML file.

        Returns:
            A dict with ``status`` (``"ok"`` or ``"error"``), an optional
            ``message``, and the ``response`` body from the server.

        Raises:
            FileNotFoundError: If *seed_path* does not exist.
            ValueError: If the seed data fails validation.
            requests.RequestException: On network / HTTP errors.
        """
        path = Path(seed_path)
        if not path.exists():
            raise FileNotFoundError(f"Seed file not found: {seed_path}")

        raw_text = path.read_text(encoding="utf-8")
        seed_data = yaml.safe_load(raw_text)

        if seed_data is None:
            raise ValueError("Seed file is empty or contains no valid YAML.")

        errors = self.validate_seed(seed_data)
        if errors:
            raise ValueError(
                "Seed file validation failed:\n  - " + "\n  - ".join(errors)
            )

        # POST the YAML content to the server seed endpoint.
        url = f"{self.base_url}/_localcloud/seed"
        resp = requests.post(
            url,
            data=raw_text,
            headers={"Content-Type": "application/x-yaml"},
            timeout=30.0,
        )
        resp.raise_for_status()

        try:
            body = resp.json()
        except ValueError:
            body = {"raw": resp.text}

        return {
            "status": "ok",
            "message": "Seed data loaded successfully.",
            "response": body,
        }

    def validate_seed(self, seed_data: dict) -> list[str]:
        """Validate the structure of parsed seed data.

        Returns a list of human-readable error strings.  An empty list means
        the data is valid.
        """
        errors: list[str] = []

        if not isinstance(seed_data, dict):
            errors.append("Seed data must be a YAML mapping (dict) at the top level.")
            return errors

        # ---- version ----
        if "version" not in seed_data:
            errors.append("Missing required top-level key: 'version'.")
        else:
            version = seed_data["version"]
            if not isinstance(version, (int, float, str)):
                errors.append(f"'version' must be a string or number, got {type(version).__name__}.")

        # ---- project ----
        if "project" not in seed_data:
            errors.append("Missing required top-level key: 'project'.")
        elif not isinstance(seed_data["project"], str):
            errors.append("'project' must be a string.")

        # ---- services ----
        if "services" not in seed_data:
            errors.append("Missing required top-level key: 'services'.")
        else:
            services = seed_data["services"]
            if not isinstance(services, dict):
                errors.append("'services' must be a mapping (dict).")
            else:
                for section_name in services:
                    if section_name not in SUPPORTED_SECTIONS:
                        errors.append(
                            f"Unknown service section '{section_name}'. "
                            f"Supported: {', '.join(sorted(SUPPORTED_SECTIONS))}."
                        )
                    elif not isinstance(services[section_name], (dict, list)):
                        errors.append(
                            f"Service section '{section_name}' must be a mapping or list."
                        )

        return errors
