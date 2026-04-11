"""Proxy requests to LocalCloud Admin API and emulator endpoints."""

import logging
import requests
from typing import Dict, Any, Optional

logger = logging.getLogger(__name__)


class BackendProxy:
    """Proxy to LocalCloud Admin API and emulator-specific endpoints."""

    def __init__(self, gateway_url: str = "http://localhost:8080",
                 gcs_url: str = "http://localhost:4443") -> None:
        self.gateway_url = gateway_url
        self.gcs_url = gcs_url
        self.timeout = 5.0

    def _get(self, url: str, params: Optional[Dict] = None, verify_ssl: bool = True) -> Dict[str, Any]:
        try:
            resp = requests.get(url, params=params, timeout=self.timeout, verify=verify_ssl)
            resp.raise_for_status()
            return resp.json()
        except requests.Timeout:
            return {"error": "Request timed out"}
        except requests.ConnectionError:
            return {"error": "Failed to connect to backend"}
        except requests.RequestException as e:
            return {"error": str(e)}

    def _post(self, url: str, data: Any = None, headers: Optional[Dict] = None) -> Dict[str, Any]:
        try:
            resp = requests.post(url, data=data, headers=headers, timeout=self.timeout)
            resp.raise_for_status()
            return resp.json() if resp.content else {"success": True}
        except requests.Timeout:
            return {"error": "Request timed out"}
        except requests.ConnectionError:
            return {"error": "Failed to connect to backend"}
        except requests.RequestException as e:
            return {"error": str(e)}

    # --- Admin API ---

    def get_health(self) -> Dict[str, Any]:
        return self._get(f"{self.gateway_url}/_localcloud/health")

    def get_services(self) -> Dict[str, Any]:
        return self._get(f"{self.gateway_url}/_localcloud/services")

    def get_requests(self) -> Dict[str, Any]:
        return self._get(f"{self.gateway_url}/_localcloud/requests")

    def get_env(self, fmt: str = "json", project: str = None) -> Dict[str, Any]:
        params = {"format": fmt}
        if project:
            params["project"] = project
        return self._get(f"{self.gateway_url}/_localcloud/env", params=params)

    def reset(self, project: str = None) -> Dict[str, Any]:
        url = f"{self.gateway_url}/_localcloud/reset"
        if project:
            url += f"?project={project}"
        return self._post(url)

    # --- Projects ---

    def list_projects(self) -> Dict[str, Any]:
        return self._get(f"{self.gateway_url}/_localcloud/projects")

    def create_project(self, data: str) -> Dict[str, Any]:
        return self._post(f"{self.gateway_url}/_localcloud/projects",
                          data=data, headers={"Content-Type": "application/json"})

    def delete_project(self, project_id: str) -> Dict[str, Any]:
        try:
            resp = requests.delete(f"{self.gateway_url}/_localcloud/projects/{project_id}",
                                   timeout=self.timeout)
            resp.raise_for_status()
            return resp.json() if resp.content else {"success": True}
        except requests.RequestException as e:
            return {"error": str(e)}

    def seed(self, yaml_data: str) -> Dict[str, Any]:
        return self._post(f"{self.gateway_url}/_localcloud/seed",
                          data=yaml_data, headers={"Content-Type": "application/yaml"})

    # --- Data Browse (direct emulator APIs) ---

    def browse_gcs(self, project: str = "local-project") -> Dict[str, Any]:
        data = self._get(f"{self.gateway_url}/_localcloud/browse/gcs")
        if "error" in data:
            return data
        # The gateway browse endpoint returns raw GCS API format
        items = data.get("items", [])
        if items:
            return {"buckets": [{"name": b["name"], "timeCreated": b.get("timeCreated", ""),
                                 "location": b.get("location", "")} for b in items]}
        return data

    def browse_gcs_objects(self, bucket: str) -> Dict[str, Any]:
        data = self._get(f"{self.gateway_url}/_localcloud/browse/gcs/buckets/{bucket}")
        if "error" in data:
            return data
        items = data.get("items", [])
        if items:
            return {"objects": [{"name": o["name"], "size": o.get("size", "0"),
                                 "contentType": o.get("contentType", ""),
                                 "updated": o.get("updated", "")} for o in items]}
        return data

    def browse_pubsub_topics(self) -> Dict[str, Any]:
        return self._get(f"{self.gateway_url}/_localcloud/browse/pubsub")

    def browse_pubsub_subscriptions(self) -> Dict[str, Any]:
        return self._get(f"{self.gateway_url}/_localcloud/browse/pubsub/subscriptions")

    def browse_secrets(self) -> Dict[str, Any]:
        return self._get(f"{self.gateway_url}/_localcloud/browse/secretmanager")

    def browse_cloudtasks_queues(self) -> Dict[str, Any]:
        return self._get(f"{self.gateway_url}/_localcloud/browse/cloudtasks")

    def browse_logging(self) -> Dict[str, Any]:
        return self._get(f"{self.gateway_url}/_localcloud/browse/logging")

    def browse_monitoring(self) -> Dict[str, Any]:
        return self._get(f"{self.gateway_url}/_localcloud/browse/monitoring")

    def browse_bigquery(self) -> Dict[str, Any]:
        return self._get(f"{self.gateway_url}/_localcloud/browse/bigquery")

    def browse_generic(self, service: str, path: str = "") -> Dict[str, Any]:
        url = f"{self.gateway_url}/_localcloud/browse/{service}"
        if path:
            url += f"/{path}"
        return self._get(url)

    # --- Data Mutation ---

    def _post_json(self, url: str, data: Any = None) -> Dict[str, Any]:
        try:
            resp = requests.post(url, json=data, timeout=self.timeout)
            resp.raise_for_status()
            return resp.json() if resp.content else {"success": True}
        except requests.Timeout:
            return {"error": "Request timed out"}
        except requests.ConnectionError:
            return {"error": "Failed to connect to backend"}
        except requests.RequestException as e:
            return {"error": str(e)}

    def mutate(self, service: str, path: str = "", data: Any = None) -> Dict[str, Any]:
        url = f"{self.gateway_url}/_localcloud/mutate/{service}"
        if path:
            url += f"/{path}"
        return self._post_json(url, data)

    def reset_service(self, service: str, data: Any = None) -> Dict[str, Any]:
        return self._post_json(f"{self.gateway_url}/_localcloud/reset/{service}", data)

    def export_state(self) -> str:
        try:
            resp = requests.get(f"{self.gateway_url}/_localcloud/export", timeout=10.0)
            resp.raise_for_status()
            return resp.text
        except requests.RequestException as e:
            logger.warning("Export failed: %s", e)
            return ""
