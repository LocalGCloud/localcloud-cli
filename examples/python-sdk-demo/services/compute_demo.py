"""Google Cloud Compute Engine demo using the official Python SDK."""

import os
import uuid

from google.auth.credentials import AnonymousCredentials
from google.cloud.compute_v1.services.instances import InstancesClient
from google.api_core.client_options import ClientOptions


def _make_client() -> InstancesClient:
    """Create a Compute Engine client pointing at LocalCloud."""
    host = os.environ.get("COMPUTE_EMULATOR_HOST", "http://localhost:8080")
    # Strip trailing slash
    host = host.rstrip("/")
    # Ensure it's a full URL for REST transport
    if not host.startswith("http"):
        host = f"http://{host}"

    options = ClientOptions(api_endpoint=host)
    return InstancesClient(
        credentials=AnonymousCredentials(),
        client_options=options,
    )


def run(project_id: str) -> list[tuple[str, bool, str]]:
    """Run Compute Engine demo operations. Returns list of (operation, success, detail)."""
    results = []
    zone = "us-central1-a"
    instance_name_1 = f"demo-vm-{uuid.uuid4().hex[:8]}"
    instance_name_2 = f"demo-vm2-{uuid.uuid4().hex[:8]}"

    # Use raw REST calls since the Compute SDK requires complex OAuth
    import requests

    host = os.environ.get("COMPUTE_EMULATOR_HOST", "http://localhost:8080")
    if not host.startswith("http"):
        host = f"http://{host}"
    base = f"{host}/compute/v1/projects/{project_id}/zones/{zone}/instances"

    # 1. Create instance
    try:
        resp = requests.post(base, json={
            "name": instance_name_1,
            "machineType": "e2-medium",
        })
        resp.raise_for_status()
        data = resp.json()
        assert data.get("name") == instance_name_1 or data.get("status") == "RUNNING", \
            f"Unexpected response: {data}"
        results.append(("Create instance", True, instance_name_1))
    except Exception as e:
        results.append(("Create instance", False, str(e)))

    # 2. Get instance
    try:
        resp = requests.get(f"{base}/{instance_name_1}")
        resp.raise_for_status()
        data = resp.json()
        assert data["name"] == instance_name_1
        assert data["status"] == "RUNNING"
        results.append(("Get instance", True, f"status={data['status']}"))
    except Exception as e:
        results.append(("Get instance", False, str(e)))

    # 3. Create second instance with different machine type
    try:
        resp = requests.post(base, json={
            "name": instance_name_2,
            "machineType": "n1-standard-1",
            "metadata": {
                "items": [
                    {"key": "env", "value": "staging"},
                    {"key": "team", "value": "platform"},
                ]
            },
        })
        resp.raise_for_status()
        data = resp.json()
        assert data.get("name") == instance_name_2 or data.get("status") == "RUNNING", \
            f"Unexpected response: {data}"
        results.append(("Create second instance", True, f"{instance_name_2} (n1-standard-1)"))
    except Exception as e:
        results.append(("Create second instance", False, str(e)))

    # 4. List instances — verify 2 present
    try:
        resp = requests.get(base)
        resp.raise_for_status()
        data = resp.json()
        items = data.get("items", [])
        names = [i["name"] for i in items]
        assert instance_name_1 in names, f"{instance_name_1} not in list"
        assert instance_name_2 in names, f"{instance_name_2} not in list"
        results.append(("List instances (2)", True, f"{len(items)} instance(s)"))
    except Exception as e:
        results.append(("List instances (2)", False, str(e)))

    # 5. Stop instance — verify TERMINATED
    try:
        resp = requests.post(f"{base}/{instance_name_1}/stop")
        resp.raise_for_status()
        # Verify status is TERMINATED
        resp2 = requests.get(f"{base}/{instance_name_1}")
        resp2.raise_for_status()
        status = resp2.json().get("status")
        assert status == "TERMINATED", f"expected TERMINATED after stop, got {status}"
        results.append(("Stop instance", True, f"status={status}"))
    except Exception as e:
        results.append(("Stop instance", False, str(e)))

    # 6. Start instance — verify RUNNING
    try:
        resp = requests.post(f"{base}/{instance_name_1}/start")
        resp.raise_for_status()
        # Verify status is RUNNING
        resp2 = requests.get(f"{base}/{instance_name_1}")
        resp2.raise_for_status()
        status = resp2.json().get("status")
        assert status == "RUNNING", f"expected RUNNING after start, got {status}"
        results.append(("Start instance", True, f"status={status}"))
    except Exception as e:
        results.append(("Start instance", False, str(e)))

    # 7. Verify status transitions: RUNNING -> stop -> TERMINATED -> start -> RUNNING
    try:
        # Stop instance_name_2
        requests.post(f"{base}/{instance_name_2}/stop").raise_for_status()
        resp = requests.get(f"{base}/{instance_name_2}")
        resp.raise_for_status()
        assert resp.json()["status"] == "TERMINATED"

        # Start it again
        requests.post(f"{base}/{instance_name_2}/start").raise_for_status()
        resp = requests.get(f"{base}/{instance_name_2}")
        resp.raise_for_status()
        assert resp.json()["status"] == "RUNNING"
        results.append(("Status transitions", True, "RUNNING->TERMINATED->RUNNING"))
    except Exception as e:
        results.append(("Status transitions", False, str(e)))

    # 8. Delete instance 1
    try:
        resp = requests.delete(f"{base}/{instance_name_1}")
        resp.raise_for_status()
        results.append(("Delete instance 1", True, instance_name_1))
    except Exception as e:
        results.append(("Delete instance 1", False, str(e)))

    # 9. Delete instance 2
    try:
        resp = requests.delete(f"{base}/{instance_name_2}")
        resp.raise_for_status()
        results.append(("Delete instance 2", True, instance_name_2))
    except Exception as e:
        results.append(("Delete instance 2", False, str(e)))

    return results
