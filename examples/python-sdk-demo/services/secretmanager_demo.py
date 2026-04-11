"""Google Cloud Secret Manager demo using the official Python SDK."""

import uuid

import grpc
from google.api_core.client_options import ClientOptions
from google.auth.credentials import AnonymousCredentials
from google.cloud import secretmanager


def _make_client() -> secretmanager.SecretManagerServiceClient:
    """Create a Secret Manager client pointing at LocalCloud."""
    import os

    host = os.environ.get("SECRET_MANAGER_EMULATOR_HOST", "localhost:8080")
    channel = grpc.insecure_channel(host)
    transport = secretmanager.SecretManagerServiceClient.get_transport_class("grpc")(
        host=f"http://{host}",
        credentials=AnonymousCredentials(),
        channel=channel,
    )
    return secretmanager.SecretManagerServiceClient(transport=transport)


def run(project_id: str, keep_data: bool = False) -> list[tuple[str, bool, str]]:
    """Run Secret Manager demo operations. Returns list of (operation, success, detail)."""
    results = []
    client = _make_client()
    secret_id = f"demo-secret-{uuid.uuid4().hex[:8]}"
    parent = f"projects/{project_id}"
    secret_name = f"{parent}/secrets/{secret_id}"
    payload = b"s3cr3t-v4lue"

    # 1. Create secret with labels
    try:
        client.create_secret(
            request={
                "parent": parent,
                "secret_id": secret_id,
                "secret": {
                    "replication": {"automatic": {}},
                    "labels": {"env": "dev", "team": "platform"},
                },
            }
        )
        results.append(("Create secret with labels", True, secret_id))
    except Exception as e:
        results.append(("Create secret with labels", False, str(e)))
        return results

    # 2. Get secret metadata
    try:
        secret_meta = client.get_secret(request={"name": secret_name})
        assert secret_meta.name == secret_name, f"expected {secret_name}, got {secret_meta.name}"
        results.append(("Get secret metadata", True, secret_name))
    except Exception as e:
        results.append(("Get secret metadata", False, str(e)))

    # 3. Add version (string payload)
    try:
        version = client.add_secret_version(
            request={
                "parent": secret_name,
                "payload": {"data": payload},
            }
        )
        version_id = version.name.split("/")[-1]
        results.append(("Add version", True, f"version {version_id}"))
    except Exception as e:
        results.append(("Add version", False, str(e)))
        version_id = "1"

    # 4. Access version
    try:
        response = client.access_secret_version(
            request={"name": f"{secret_name}/versions/{version_id}"}
        )
        assert response.payload.data == payload, "payload mismatch"
        results.append(("Access version", True, "payload matches"))
    except Exception as e:
        results.append(("Access version", False, str(e)))

    # 5. Add binary payload version
    binary_payload = bytes(range(256))
    try:
        version_bin = client.add_secret_version(
            request={
                "parent": secret_name,
                "payload": {"data": binary_payload},
            }
        )
        version_bin_id = version_bin.name.split("/")[-1]
        response = client.access_secret_version(
            request={"name": f"{secret_name}/versions/{version_bin_id}"}
        )
        assert response.payload.data == binary_payload, "binary payload mismatch"
        results.append(("Binary payload version", True, f"256 bytes, version {version_bin_id}"))
    except Exception as e:
        results.append(("Binary payload version", False, str(e)))

    # 6. Access latest
    try:
        response = client.access_secret_version(
            request={"name": f"{secret_name}/versions/latest"}
        )
        assert response.payload.data == binary_payload, "latest payload mismatch"
        results.append(("Access latest", True, "payload matches"))
    except Exception as e:
        results.append(("Access latest", False, str(e)))

    # 7. List secrets
    try:
        secrets = list(client.list_secrets(request={"parent": parent}))
        secret_names = [s.name for s in secrets]
        assert secret_name in secret_names, f"{secret_name} not in {secret_names}"
        results.append(("List secrets", True, f"{len(secrets)} secret(s)"))
    except Exception as e:
        results.append(("List secrets", False, str(e)))

    # 8. List versions
    try:
        versions = list(client.list_secret_versions(request={"parent": secret_name}))
        assert len(versions) >= 2, f"expected >=2 versions, got {len(versions)}"
        results.append(("List versions", True, f"{len(versions)} version(s)"))
    except Exception as e:
        results.append(("List versions", False, str(e)))

    # 9. Disable version
    try:
        client.disable_secret_version(
            request={"name": f"{secret_name}/versions/{version_id}"}
        )
        results.append(("Disable version", True, f"version {version_id}"))
    except Exception as e:
        results.append(("Disable version", False, str(e)))

    # 10. Enable version
    try:
        client.enable_secret_version(
            request={"name": f"{secret_name}/versions/{version_id}"}
        )
        results.append(("Enable version", True, f"version {version_id}"))
    except Exception as e:
        results.append(("Enable version", False, str(e)))

    # 11. Destroy version
    try:
        client.destroy_secret_version(
            request={"name": f"{secret_name}/versions/{version_id}"}
        )
        results.append(("Destroy version", True, f"version {version_id}"))
    except Exception as e:
        results.append(("Destroy version", False, str(e)))

    # 12. Delete secret
    if not keep_data:
        try:
            client.delete_secret(request={"name": secret_name})
            results.append(("Delete secret", True, secret_id))
        except Exception as e:
            results.append(("Delete secret", False, str(e)))
    else:
        results.append(("Skip cleanup", True, "data preserved for inspection"))

    return results
