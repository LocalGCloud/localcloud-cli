-- GKE: Emulator Schema + Sample Data
-- Features: clusters with versions, node pools, kubeconfig,
--           k3d integration, cluster states

CREATE TABLE IF NOT EXISTS gke_clusters (
    project_id      VARCHAR(255) NOT NULL,
    location        VARCHAR(255) NOT NULL,
    cluster_id      VARCHAR(255) NOT NULL,
    status          VARCHAR(20) DEFAULT 'PROVISIONING',
    k3d_cluster_name VARCHAR(255),
    endpoint        VARCHAR(512),
    cluster_version VARCHAR(20) DEFAULT '1.28',
    node_count      INT DEFAULT 1,
    node_machine_type VARCHAR(100) DEFAULT 'e2-standard-2',
    min_node_count  INT DEFAULT 1,
    max_node_count  INT DEFAULT 10,
    location_type   VARCHAR(20) DEFAULT 'zonal',
    network         VARCHAR(255) DEFAULT 'default',
    subnet          VARCHAR(255) DEFAULT 'default',
    kubeconfig      TEXT,
    labels          JSONB DEFAULT '{}',
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, location, cluster_id)
);

INSERT INTO gke_clusters (project_id, location, cluster_id, status, k3d_cluster_name, cluster_version, node_count, node_machine_type, min_node_count, max_node_count, location_type, network, subnet, labels, created_at) VALUES
    ('local-project', 'us-central1', 'prod-cluster', 'RUNNING', 'k3d-prod', '1.30', 5, 'e2-standard-4', 3, 20, 'zonal', 'default', 'default',
     '{"env":"production","team":"platform","critical":"true"}', '2024-01-15T08:00:00Z'),

    ('local-project', 'us-central1', 'staging-cluster', 'RUNNING', 'k3d-staging', '1.30', 3, 'e2-standard-2', 1, 10, 'zonal', 'default', 'default',
     '{"env":"staging","team":"engineering"}', '2024-03-01T10:00:00Z'),

    ('local-project', 'europe-west1', 'dev-cluster', 'RUNNING', 'k3d-dev-eu', '1.29', 2, 'e2-standard-2', 1, 5, 'zonal', 'dev-vpc', 'dev-subnet',
     '{"env":"development","team":"engineering","region":"eu"}', '2024-06-01T14:00:00Z'),

    ('local-project', 'us-central1', 'ml-cluster', 'RUNNING', 'k3d-ml', '1.30', 3, 'n2-standard-8', 1, 10, 'zonal', 'ml-vpc', 'ml-subnet',
     '{"env":"production","team":"ml","accelerator":"gpu"}', '2024-04-15T09:00:00Z'),

    ('local-project', 'us-west1', 'data-cluster', 'PROVISIONING', NULL, '1.30', 0, 'e2-standard-4', 1, 15, 'regional', 'data-vpc', 'data-subnet',
     '{"env":"production","team":"data","purpose":"analytics"}', '2025-05-20T08:00:00Z'),

    ('local-project', 'us-central1', 'secure-cluster', 'RUNNING', 'k3d-secure', '1.29', 3, 'e2-standard-4', 1, 10, 'zonal', 'secure-vpc', 'secure-subnet',
     '{"env":"production","team":"security","compliance":"soc2"}', '2024-08-01T11:00:00Z'),

    ('local-project', 'us-central1', 'test-cluster', 'STOPPED', NULL, '1.28', 0, 'e2-standard-2', 0, 5, 'zonal', 'default', 'default',
     '{"env":"testing","team":"qa"}', '2024-02-01T08:00:00Z'),

    ('demo-project', 'us-central1', 'demo-cluster', 'RUNNING', 'k3d-demo', '1.29', 1, 'e2-small', 1, 3, 'zonal', 'default', 'default',
     '{"env":"demo","team":"sales"}', '2025-01-01T00:00:00Z');

-- Query: cluster health summary
-- SELECT location_type, COUNT(*) as total,
--        SUM(CASE WHEN status = 'RUNNING' THEN 1 ELSE 0 END) as running
-- FROM gke_clusters
-- GROUP BY location_type;

-- Query: compute capacity across clusters
-- SELECT cluster_id, location, cluster_version,
--        node_count * (CASE
--            WHEN node_machine_type LIKE 'e2-standard-4' THEN 4
--            WHEN node_machine_type LIKE 'e2-standard-2' THEN 2
--            WHEN node_machine_type LIKE 'n2-standard-8' THEN 8
--            ELSE 1 END
--        ) as total_vcpus
-- FROM gke_clusters WHERE status = 'RUNNING'
-- ORDER BY total_vcpus DESC;
