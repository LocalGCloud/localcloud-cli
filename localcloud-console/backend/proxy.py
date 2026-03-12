"""Proxy requests to LocalCloud backend."""

import logging
import requests
from typing import Dict, Any, Optional

logger = logging.getLogger(__name__)


class BackendProxy:
    """Make requests to LocalCloud Java backend."""

    def __init__(self, host: str = "localhost", port: int = 8080) -> None:
        """Initialize backend proxy.

        Args:
            host: Hostname of the backend server
            port: Port number of the backend server
        """
        self.base_url = f"http://{host}:{port}"
        self.timeout = 5.0

    def _get(self, path: str, params: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """Make GET request to backend.

        Args:
            path: API endpoint path
            params: Optional query parameters

        Returns:
            JSON response from backend or error dict
        """
        try:
            resp = requests.get(
                f"{self.base_url}{path}",
                params=params,
                timeout=self.timeout
            )
            resp.raise_for_status()
            return resp.json()
        except requests.Timeout:
            logger.error(f"Request timeout to {path}")
            return {"error": "Backend request timed out"}
        except requests.ConnectionError as e:
            logger.error(f"Connection error to backend: {e}")
            return {"error": "Failed to connect to backend"}
        except requests.RequestException as e:
            logger.error(f"Request error: {e}")
            return {"error": "Backend error"}
        except Exception as e:
            logger.error(f"Unexpected error in _get: {e}", exc_info=True)
            return {"error": "An unexpected error occurred"}

    def get_status(self) -> Dict[str, Any]:
        """Get system status from /_localcloud/health.

        Returns:
            Status dict with services and health information
        """
        return self._get("/_localcloud/health")

    def get_services(self) -> Dict[str, Any]:
        """Get service list from /_localcloud/health.

        Returns:
            Dict with services array
        """
        data = self._get("/_localcloud/health")
        return {"services": data.get("services", [])}

    def get_service(self, service_name: str) -> Dict[str, Any]:
        """Get single service from service list.

        Args:
            service_name: Name of the service to retrieve

        Returns:
            Service details or error dict
        """
        data = self.get_services()
        for svc in data.get("services", []):
            if svc.get("name") == service_name:
                return svc
        return {"error": f"Service {service_name} not found"}

    def get_firestore_collections(self) -> Dict[str, Any]:
        """Get Firestore collections.

        Returns:
            Dict with collections array (currently empty pending API implementation)
        """
        # TODO: Implement via Firestore API
        return {"collections": []}

    def get_bigquery_datasets(self) -> Dict[str, Any]:
        """Get BigQuery datasets.

        Returns:
            Dict with datasets array (currently empty pending API implementation)
        """
        # TODO: Implement via BigQuery API
        return {"datasets": []}

    def get_gcs_buckets(self) -> Dict[str, Any]:
        """Get GCS buckets.

        Returns:
            Dict with buckets array (currently empty pending API implementation)
        """
        # TODO: Implement via GCS API
        return {"buckets": []}
