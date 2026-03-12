"""Proxy requests to LocalCloud backend."""

import requests
import json
from typing import Optional

class BackendProxy:
    """Make requests to LocalCloud Java backend."""

    def __init__(self, host: str = "localhost", port: int = 8080):
        self.base_url = f"http://{host}:{port}"
        self.timeout = 5.0

    def _get(self, path: str, params: dict = None):
        """Make GET request to backend."""
        try:
            resp = requests.get(
                f"{self.base_url}{path}",
                params=params,
                timeout=self.timeout
            )
            resp.raise_for_status()
            return resp.json()
        except Exception as e:
            return {"error": str(e)}

    def get_status(self):
        """Get system status from /_localcloud/health."""
        return self._get("/_localcloud/health")

    def get_services(self):
        """Get service list from /_localcloud/health."""
        data = self._get("/_localcloud/health")
        return {"services": data.get("services", [])}

    def get_service(self, service_name: str):
        """Get single service from service list."""
        data = self.get_services()
        for svc in data.get("services", []):
            if svc.get("name") == service_name:
                return svc
        return {"error": f"Service {service_name} not found"}

    def get_firestore_collections(self):
        """Get Firestore collections."""
        # TODO: Implement via Firestore API
        return {"collections": []}

    def get_bigquery_datasets(self):
        """Get BigQuery datasets."""
        # TODO: Implement via BigQuery API
        return {"datasets": []}

    def get_gcs_buckets(self):
        """Get GCS buckets."""
        # TODO: Implement via GCS API
        return {"buckets": []}
