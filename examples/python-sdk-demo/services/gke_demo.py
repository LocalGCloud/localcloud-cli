"""Google Kubernetes Engine (GKE) demo using the official Python SDK."""

import os
import uuid

import grpc
from google.auth.credentials import AnonymousCredentials
return ClusterManagerClient(transport=transport)


def run(project_id: str, keep_data: bool = False) -> list[tuple[str, bool, str]]:
    """Run GKE demo operations. Returns list of (operation, success, detail)."""
    results = []
    client = _make_client()
    location = "us-central1"
    cluster_id_1 = f"demo-cluster-{uuid.uuid4().hex[:8]}"
    cluster_id_2 = f"demo-cluster2-{uuid.uuid4().hex[:8]}"
    parent = f"projects/{project_id}/locations/{location}"

    # 1. Create cluster (version 1.28)
    try:
        operation = client.create_cluster(
            request={
                "parent": parent,
                "cluster": {
                    "name": cluster_id_1,
                    "initial_node_count": 1,
                    "initial_cluster_version": "1.28",
                },
            }
        )
        assert operation.status is not None
        results.append(("Create cluster", True, f"{cluster_id_1} (v1.28)"))
    except Exception as e:
        results.append(("Create cluster", False, str(e)))

    # 2. Get cluster and verify status
    try:
        cluster = client.get_cluster(
            request={"name": f"{parent}/clusters/{cluster_id_1}"}
        )
        assert cluster.name == cluster_id_1
        assert cluster.status is not None, "cluster status should be set"
        results.append(("Get cluster", True, f"status={cluster.status}, endpoint={cluster.endpoint}"))
    except Exception as e:
        results.append(("Get cluster", False, str(e)))

    # 3. Create second cluster with different version (1.27)
    try:
        operation2 = client.create_cluster(
            request={
                "parent": parent,
                "cluster": {
                    "name": cluster_id_2,
                    "initial_node_count": 2,
                    "initial_cluster_version": "1.27",
                },
            }
        )
        assert operation2.status is not None
        # Verify different version
        cluster2 = client.get_cluster(
            request={"name": f"{parent}/clusters/{cluster_id_2}"}
        )
        assert cluster2.initial_cluster_version == "1.27", \
            f"expected v1.27, got {cluster2.initial_cluster_version}"
        results.append(("Create second cluster", True, f"{cluster_id_2} (v1.27)"))
    except Exception as e:
        results.append(("Create second cluster", False, str(e)))

    # 4. List clusters — verify count=2
    try:
        response = client.list_clusters(request={"parent": parent})
        cluster_names = [c.name for c in response.clusters]
        assert cluster_id_1 in cluster_names, f"{cluster_id_1} not in {cluster_names}"
        assert cluster_id_2 in cluster_names, f"{cluster_id_2} not in {cluster_names}"
        results.append(("List clusters (2)", True, f"{len(response.clusters)} cluster(s)"))
    except Exception as e:
        results.append(("List clusters (2)", False, str(e)))

    # 5. Get server config
    try:
        config = client.get_server_config(request={"name": f"{parent}/serverConfig"})
        assert config.default_cluster_version != ""
        results.append(("Get server config", True, f"default={config.default_cluster_version}"))
    except Exception as e:
        results.append(("Get server config", False, str(e)))

    # 6. Delete cluster 1
    if not keep_data:
        try:
            operation = client.delete_cluster(
                request={"name": f"{parent}/clusters/{cluster_id_1}"}
            )
            results.append(("Delete cluster 1", True, cluster_id_1))
        except Exception as e:
            results.append(("Delete cluster 1", False, str(e)))
    else:
        results.append(("Skip cleanup", True, "data preserved for inspection"))

    # 7. Delete cluster 2
    if not keep_data:
        try:
            operation = client.delete_cluster(
                request={"name": f"{parent}/clusters/{cluster_id_2}"}
            )
            results.append(("Delete cluster 2", True, cluster_id_2))
        except Exception as e:
            results.append(("Delete cluster 2", False, str(e)))
    else:
        results.append(("Skip cleanup", True, "data preserved for inspection"))

    return results
