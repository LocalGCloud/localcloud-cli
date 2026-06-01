-- Compute Engine: Emulator Schema + Sample Data
-- Features: instances with machine types, disks, snapshots,
--           networks, firewalls, metadata, IP addresses
--
-- Run against LocalCloud PostgreSQL.

CREATE TABLE IF NOT EXISTS compute_instances (
    project_id      VARCHAR(255) NOT NULL,
    zone            VARCHAR(255) NOT NULL,
    instance_name   VARCHAR(255) NOT NULL,
    machine_type    VARCHAR(255) DEFAULT 'e2-medium',
    status          VARCHAR(20) DEFAULT 'PROVISIONING',
    container_id    VARCHAR(255),
    container_image VARCHAR(512) DEFAULT 'ubuntu:22.04',
    network_ip      VARCHAR(45),
    external_ip     VARCHAR(45),
    subnet          VARCHAR(255),
    network         VARCHAR(255),
    tags            JSONB DEFAULT '[]',
    metadata        JSONB DEFAULT '{}',
    service_account VARCHAR(512),
    cpu_platform    VARCHAR(100),
    gpus            INT DEFAULT 0,
    gpu_type        VARCHAR(100),
    confidential    BOOLEAN DEFAULT FALSE,
    enable_display  BOOLEAN DEFAULT FALSE,
    deletion_protection BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, zone, instance_name)
);

INSERT INTO compute_instances (project_id, zone, instance_name, machine_type, status, container_image, network_ip, external_ip, subnet, network, tags, metadata, service_account, cpu_platform, gpus, gpu_type, deletion_protection, created_at) VALUES
    ('local-project', 'us-central1-a', 'web-server-01', 'e2-standard-4', 'RUNNING', 'debian-12-bookworm-v20250514',
     '10.128.0.10', '34.67.123.45', 'default', 'default',
     '["http-server", "https-server", "web"]',
     '{"startup-script":"#!/bin/bash\napt-get update\napt-get install -y nginx\necho \"Hello World\" > /var/www/html/index.html","enable-oslogin":"TRUE"}',
     'web-sa@local-project.iam.gserviceaccount.com', 'Intel Cascade Lake', 0, NULL, TRUE, '2024-01-15T08:00:00Z'),

    ('local-project', 'us-central1-a', 'web-server-02', 'e2-standard-4', 'RUNNING', 'debian-12-bookworm-v20250514',
     '10.128.0.11', '34.67.124.46', 'default', 'default',
     '["http-server", "https-server", "web"]',
     '{"startup-script":"#!/bin/bash\napt-get update\napt-get install -y nginx","enable-oslogin":"TRUE"}',
     'web-sa@local-project.iam.gserviceaccount.com', 'Intel Cascade Lake', 0, NULL, TRUE, '2024-01-15T08:05:00Z'),

    ('local-project', 'us-central1-b', 'api-server-01', 'e2-standard-8', 'RUNNING', 'ubuntu-2204-jammy-v20250514',
     '10.128.0.20', '34.67.125.47', 'api-subnet', 'api-vpc',
     '["api-server", "internal-only"]',
     '{"enable-oslogin":"TRUE","proxy-mode":"envoy"}',
     'api-sa@local-project.iam.gserviceaccount.com', 'AMD Milan', 0, NULL, TRUE, '2024-02-01T10:00:00Z'),

    ('local-project', 'us-central1-b', 'api-server-02', 'e2-standard-8', 'RUNNING', 'ubuntu-2204-jammy-v20250514',
     '10.128.0.21', '', 'api-subnet', 'api-vpc',
     '["api-server", "internal-only"]',
     '{}',
     'api-sa@local-project.iam.gserviceaccount.com', 'AMD Milan', 0, NULL, TRUE, '2024-02-01T10:05:00Z'),

    ('local-project', 'us-west1-a', 'worker-pool-01', 'n2-highcpu-16', 'RUNNING', 'cos-105-lts',
     '10.132.0.10', '', 'workers-subnet', 'workers-vpc',
     '["worker", "preemptible"]',
     '{"preemptible":"true","maintenance-policy":"TERMINATE"}',
     'worker-sa@local-project.iam.gserviceaccount.com', 'Intel Ice Lake', 0, NULL, FALSE, '2024-03-01T14:00:00Z'),

    ('local-project', 'us-west1-a', 'worker-pool-02', 'n2-highcpu-16', 'RUNNING', 'cos-105-lts',
     '10.132.0.11', '', 'workers-subnet', 'workers-vpc',
     '["worker", "preemptible"]',
     '{"preemptible":"true","maintenance-policy":"TERMINATE"}',
     'worker-sa@local-project.iam.gserviceaccount.com', 'Intel Ice Lake', 0, NULL, FALSE, '2024-03-01T14:05:00Z'),

    ('local-project', 'us-central1-f', 'db-server-01', 'n2-standard-32', 'RUNNING', 'ubuntu-2204-jammy-v20250514',
     '10.128.0.30', '', 'db-subnet', 'db-vpc',
     '["database", "no-external-ip"]',
     '{"enable-oslogin":"TRUE","db-role":"primary"}',
     'db-sa@local-project.iam.gserviceaccount.com', 'Intel Ice Lake', 0, NULL, TRUE, '2024-01-20T09:00:00Z'),

    ('local-project', 'us-central1-f', 'db-server-02', 'n2-standard-32', 'RUNNING', 'ubuntu-2204-jammy-v20250514',
     '10.128.0.31', '', 'db-subnet', 'db-vpc',
     '["database", "no-external-ip"]',
     '{"enable-oslogin":"TRUE","db-role":"standby"}',
     'db-sa@local-project.iam.gserviceaccount.com', 'Intel Ice Lake', 0, NULL, TRUE, '2024-01-20T09:05:00Z'),

    ('local-project', 'us-central1-a', 'ml-training-01', 'a2-highgpu-4g', 'STOPPED', 'cuda-deeplearning-20250514',
     '10.128.0.40', '', 'ml-subnet', 'ml-vpc',
     '["ml", "gpu", "training"]',
     '{"enable-oslogin":"TRUE","framework":"pytorch","job":"train-2025-05-20"}',
     'ml-sa@local-project.iam.gserviceaccount.com', 'Intel Cascade Lake', 4, 'nvidia-tesla-a100', TRUE, '2024-04-01T08:00:00Z'),

    ('local-project', 'us-central1-c', 'bastion-host', 'e2-micro', 'RUNNING', 'ubuntu-minimal-2204-jammy-v20250514',
     '10.128.0.250', '35.202.1.100', 'default', 'default',
     '["bastion", "ssh-only"]',
     '{"enable-oslogin":"TRUE","block-project-ssh-keys":"TRUE"}',
     'bastion-sa@local-project.iam.gserviceaccount.com', 'Intel Broadwell', 0, NULL, FALSE, '2024-01-05T12:00:00Z'),

    ('local-project', 'us-central1-a', 'dev-container-01', 'e2-small', 'PROVISIONING', 'debian-12-bookworm-v20250514',
     '', '', 'default', 'default',
     '["dev", "ephemeral"]',
     '{}',
     'dev-sa@local-project.iam.gserviceaccount.com', '', 0, NULL, FALSE, '2025-05-20T08:00:00Z'),

    ('demo-project', 'us-central1-a', 'demo-app-01', 'e2-medium', 'RUNNING', 'ubuntu-2204-jammy-v20250514',
     '10.128.0.100', '34.67.200.50', 'default', 'default',
     '["http-server"]',
     '{"enable-oslogin":"TRUE"}',
     NULL, 'Intel Broadwell', 0, NULL, FALSE, '2025-01-01T00:00:00Z');

-- Query: instance counts by zone, status, machine type
-- SELECT zone, status, machine_type, COUNT(*) as count
-- FROM compute_instances
-- GROUP BY zone, status, machine_type ORDER BY zone, status;

-- Query: instances with public IPs (potential security concern)
-- SELECT instance_name, zone, external_ip, tags
-- FROM compute_instances
-- WHERE external_ip != '' AND external_ip IS NOT NULL
-- ORDER BY zone;
