from __future__ import annotations

import json
import os
import time
from pathlib import Path
from urllib.parse import urlparse
from uuid import uuid4

import grpc
import httpx
import pytest
from google.api_core.client_options import ClientOptions
from google.api_core.exceptions import NotFound
from google.auth.credentials import AnonymousCredentials
from google.cloud import bigquery, dataproc_v1, pubsub_v1, storage
from google.cloud.dataproc_v1.services.job_controller.transports import (
    JobControllerGrpcTransport,
)
from google.pubsub_v1.services.publisher.transports import PublisherGrpcTransport
from google.pubsub_v1.services.subscriber.transports import SubscriberGrpcTransport

from integration._support import (
    assert_loopback_url,
    controller_for,
    dataproc_container,
    exec_checked,
    get_bytes,
    parent_container,
    put_bytes,
    write_config,
)
from integration.scenario_support import (
    apply_scenario,
    checkpoint_project,
    verify_scenario,
)
from localcloud_cli.java_client import JavaMcpClient
from localcloud_cli.docker_runtime import VOLUME_NAME_LABEL


pytestmark = pytest.mark.docker


def _bigquery_client(project: str, endpoint: str) -> bigquery.Client:
    return bigquery.Client(
        project=project,
        credentials=AnonymousCredentials(),
        client_options={"api_endpoint": endpoint},
    )


def _storage_client(project: str, endpoint: str) -> storage.Client:
    return storage.Client(
        project=project,
        credentials=AnonymousCredentials(),
        client_options=ClientOptions(api_endpoint=endpoint),
    )


def _query_rows(client: bigquery.Client, sql: str) -> list[tuple]:
    return [tuple(row.values()) for row in client.query(sql).result(timeout=30)]


def _psql(container, database: str, sql: str) -> list[str]:
    output = exec_checked(
        container,
        [
            "psql",
            "-h",
            "127.0.0.1",
            "-p",
            "5375",
            "-U",
            "localcloud",
            "-d",
            database,
            "-v",
            "ON_ERROR_STOP=1",
            "-At",
            "-c",
            sql,
        ],
    )
    return [line for line in output.splitlines() if line]


def _cloudsql_summary(container, physical_database: str) -> list[str]:
    return _psql(
        container,
        physical_database,
        "SELECT customer_id || '|' || COUNT(*) || '|' || "
        "to_char(SUM(amount), 'FM999999990.00') FROM orders "
        "GROUP BY customer_id ORDER BY customer_id",
    )


def _require_full_olap_prerequisites(runtime, image: str) -> None:
    memory = int(runtime.client.info().get("MemTotal", 0))
    if memory < 4 * 1024**3:
        pytest.fail(
            "full_olap_prerequisite_memory: Docker exposes less than 4 GiB "
            f"({memory} bytes)"
        )
    try:
        runtime.client.images.get(image)
    except Exception as error:
        pytest.fail(f"full_olap_prerequisite_localcloud_image: {error}")


def test_full_olap_sdk_fault_checkpoint_restore_and_cleanup(tmp_path: Path) -> None:
    if os.environ.get("LOCALCLOUD_RUN_FULL_OLAP") != "1":
        pytest.skip("set LOCALCLOUD_RUN_FULL_OLAP=1 to run the 4 GiB Docker acceptance path")
    controller, runtime, image = controller_for(tmp_path)
    _require_full_olap_prerequisites(runtime, image)
    data_volume = f"olap-{uuid4().hex[:12]}"
    config = write_config(
        tmp_path,
        image,
        services=[
            "gcs",
            "bigquery",
            "logging",
            "monitoring",
            "cloudsql",
            "pubsub",
            "cloudfunctions",
            "cloudscheduler",
            "dataproc",
        ],
        data_volume=data_volume,
        data="ephemeral",
        docker_socket=True,
        seed=None,
    )
    started = controller.start(config)
    target = controller.target(config)
    project = started["project"]
    user = started["user"]
    endpoint_map = target["endpoint_map"]
    gateway = target["url"]
    fault_created = False

    try:
        scenario_java = JavaMcpClient(gateway, project, user)
        applied = apply_scenario(scenario_java, "olap-application", project)
        assert applied["project"] == project
        verify_scenario(
            scenario_java, "olap-application", project, endpoint_map
        )
        assert_loopback_url(gateway)
        parent = parent_container(runtime, config)
        parent.reload()
        assert parent.attrs["HostConfig"]["Memory"] >= 4 * 1024**3
        dataproc = dataproc_container(runtime, config)
        clusters_response = httpx.get(
            f"{gateway}/v1/projects/{project}/regions/us-central1/clusters",
            timeout=10,
        )
        clusters_response.raise_for_status()
        clusters = clusters_response.json()["clusters"]
        cluster = next(
            item
            for item in clusters
            if item["clusterName"] == "analytics-spark"
        )
        metrics_mapping = cluster["portMappings"]["metrics"]
        metrics_host_port = int(metrics_mapping["host"])
        metrics_container_port = int(metrics_mapping["container"])
        assert metrics_host_port > 0
        assert metrics_host_port != metrics_container_port

        consoles_response = httpx.get(
            f"{gateway}/v1/projects/{project}/regions/us-central1/"
            "clusters/analytics-spark/consoles",
            timeout=10,
        )
        consoles_response.raise_for_status()
        metrics_console_url = consoles_response.json()["consoles"]["metrics"]
        assert_loopback_url(metrics_console_url)
        assert urlparse(metrics_console_url).port == metrics_host_port

        metrics_url = f"http://127.0.0.1:{metrics_host_port}/metrics"
        metrics_response = httpx.get(metrics_url, timeout=10)
        metrics_response.raise_for_status()
        assert {
            "cpu_percent",
            "memory_usage_mb",
            "uptime_seconds",
        }.issubset(metrics_response.json())

        bq_endpoint = f"http://127.0.0.1:{endpoint_map['5372']}"
        gcs_endpoint = f"http://127.0.0.1:{endpoint_map['5366']}"
        pubsub_endpoint = f"127.0.0.1:{endpoint_map['5367']}"
        for endpoint in (bq_endpoint, gcs_endpoint, f"http://{pubsub_endpoint}"):
            assert_loopback_url(endpoint)

        bq = _bigquery_client(project, bq_endpoint)
        seeded_summary = _query_rows(
            bq,
            f"SELECT customer_id, COUNT(*) AS order_count, "
            f"ROUND(SUM(amount), 2) AS total_amount "
            f"FROM `{project}.agent_analytics.orders` "
            "GROUP BY customer_id ORDER BY customer_id",
        )
        assert seeded_summary == [
            ("cust-001", 2, 150.5),
            ("cust-002", 1, 80.0),
        ]

        physical = _psql(
            parent,
            "localcloud",
            "SELECT physical_name FROM cloudsql_databases "
            f"WHERE project_id='{project}' AND instance_id='analytics-postgres' "
            "AND database_name='analytics'",
        )
        assert len(physical) == 1
        physical_database = physical[0]
        assert _cloudsql_summary(parent, physical_database) == [
            "cust-001|2|150.50",
            "cust-002|1|80.00",
        ]
        _psql(
            parent,
            physical_database,
            "INSERT INTO orders (order_id, customer_id, amount, status) "
            "VALUES ('ord-acceptance', 'cust-002', 20.00, 'completed')",
        )
        accepted_cloudsql_summary = [
            "cust-001|2|150.50",
            "cust-002|2|100.00",
        ]
        assert _cloudsql_summary(parent, physical_database) == accepted_cloudsql_summary

        channel = grpc.insecure_channel(pubsub_endpoint)
        publisher = pubsub_v1.PublisherClient(
            transport=PublisherGrpcTransport(channel=channel)
        )
        subscriber = pubsub_v1.SubscriberClient(
            transport=SubscriberGrpcTransport(channel=channel)
        )
        topic = publisher.topic_path(project, "order-events")
        subscription = subscriber.subscription_path(project, "order-events-agent")
        published_id = publisher.publish(
            topic,
            b'{"event_id":"event-acceptance","event_type":"order.completed"}',
            source="olap-acceptance",
        ).result(timeout=10)
        pulled = subscriber.pull(
            request={"subscription": subscription, "max_messages": 10}, timeout=10
        )
        assert published_id
        assert any(
            message.message.data
            == b'{"event_id":"event-acceptance","event_type":"order.completed"}'
            for message in pulled.received_messages
        )
        subscriber.acknowledge(
            request={
                "subscription": subscription,
                "ack_ids": [message.ack_id for message in pulled.received_messages],
            }
        )

        gcs = _storage_client(project, gcs_endpoint)
        orders_jsonl = (
            gcs.bucket(f"{project}-olap-input")
            .blob("orders/orders.jsonl")
            .download_as_bytes(timeout=30)
        )
        script = Path(
            "../localcloud-server/src/main/resources/agent-scenarios/"
            "olap-application/customer_order_summary.py"
        ).read_bytes()
        put_bytes(dataproc, "/localcloud/tmp/cluster/orders.jsonl", orders_jsonl)
        put_bytes(
            dataproc,
            "/localcloud/tmp/cluster/customer_order_summary.py",
            script,
        )

        dataproc_channel = grpc.insecure_channel(gateway.removeprefix("http://"))
        dataproc_client = dataproc_v1.JobControllerClient(
            transport=JobControllerGrpcTransport(channel=dataproc_channel)
        )
        output_table = f"{project}.agent_analytics.customer_order_summary"
        with pytest.raises(NotFound):
            bq.get_table(output_table)
        submitted = dataproc_client.submit_job(
            request={
                "project_id": project,
                "region": "us-central1",
                "job": {
                    "reference": {"job_id": "olap-acceptance"},
                    "placement": {"cluster_name": "analytics-spark"},
                    "pyspark_job": {
                        "main_python_file_uri": (
                            "file:///localcloud/tmp/cluster/customer_order_summary.py"
                        ),
                        "args": [
                            "file:///localcloud/tmp/cluster/orders.jsonl",
                            "/localcloud/tmp/cluster/customer-order-summary.jsonl",
                        ],
                    },
                },
            },
            timeout=180,
        )
        final_job = dataproc_client.get_job(
            request={
                "project_id": project,
                "region": "us-central1",
                "job_id": submitted.reference.job_id,
            },
            timeout=30,
        )
        if final_job.status.state != dataproc_v1.JobStatus.State.DONE:
            driver_output = get_bytes(
                dataproc, final_job.driver_output_resource_uri
            ).decode("utf-8", errors="replace")
            pytest.fail(
                f"Dataproc job ended in {final_job.status.state.name}: {driver_output}"
            )
        output_summary = _query_rows(
            bq,
            f"SELECT customer_id, order_count, total_amount FROM `{output_table}` "
            "ORDER BY customer_id",
        )
        assert output_summary == [
            ("cust-001", 2, 150.5),
            ("cust-002", 1, 80.0),
        ]

        java = JavaMcpClient(gateway, project, user)
        checkpoint = checkpoint_project(java, project, "olap-baseline")
        expected_checkpoint_services = {"gcs", "pubsub", "bigquery", "cloudsql"}
        if not expected_checkpoint_services.issubset(set(checkpoint["services"])):
            server_log = parent.exec_run(
                [
                    "sh",
                    "-c",
                    "tail -n 200 /var/log/localcloud/localcloud-server-stdout.log",
                ]
            ).output.decode("utf-8", errors="replace")
            pytest.fail(
                f"checkpoint omitted services: {checkpoint['services']}\\n{server_log}"
            )

        fault_id = "olap-project-cloudsql-read"
        created_fault = json.loads(java.tool(
            "localcloud_create_fault",
            {
                "fault": {
                    "id": fault_id,
                    "service": "cloudsql",
                    "method": "GET",
                    "path_contains": f"/projects/{project}/",
                    "status_code": 503,
                    "error_type": "unavailable",
                    "message": "OLAP acceptance fault",
                    "request_limit": 1,
                }
            },
        ))
        fault_created = True
        assert created_fault["id"] == fault_id
        fault_response = httpx.get(
            f"{gateway}/sql/v1beta4/projects/{project}/instances/analytics-postgres",
            timeout=10,
        )
        assert fault_response.status_code == 503
        assert fault_id in fault_response.text

        diagnostics = {}
        for _ in range(20):
            diagnostics = java.tool("localcloud_get_diagnostics", {})
            if any(
                request.get("status_code") == 503
                for request in diagnostics["recent_requests"]
            ):
                break
            time.sleep(0.1)
        assert diagnostics["project_id"] == project
        assert any(fault["id"] == fault_id for fault in diagnostics["active_faults"])
        assert any(
            request.get("project") == project
            and request.get("service") == "cloudsql"
            and request.get("status_code") == 503
            for request in diagnostics["recent_requests"]
        )
        java.tool("localcloud_clear_faults", {})
        fault_created = False

        bq.delete_table(output_table)
        with pytest.raises(NotFound):
            bq.get_table(output_table)
        _psql(
            parent,
            physical_database,
            "INSERT INTO orders (order_id, customer_id, amount, status) "
            "VALUES ('ord-mutated', 'cust-mutated', 999.00, 'completed')",
        )
        assert any("cust-mutated" in row for row in _cloudsql_summary(parent, physical_database))
        diff = java.tool("localcloud_diff_project", {"name": "olap-baseline"})
        assert diff["changed"] is True

        restored = java.tool(
            "localcloud_restore_project",
            {"name": "olap-baseline", "replace": True},
        )
        assert restored["status"] == "restored"
        assert _query_rows(
            bq,
            f"SELECT customer_id, order_count, total_amount FROM `{output_table}` "
            "ORDER BY customer_id",
        ) == output_summary
        assert _cloudsql_summary(parent, physical_database) == accepted_cloudsql_summary
        reset = controller.reset(config)
        assert reset["status"] == "reset"
    finally:
        if fault_created:
            try:
                JavaMcpClient(gateway, project, user).tool(
                    "localcloud_clear_faults", {}
                )
            except Exception:
                pass
        controller.stop(config)

    assert runtime.resolve(config) is None
    assert runtime.client.containers.list(
        all=True,
        filters={
            "label": f"{VOLUME_NAME_LABEL}={config.data_volume}"
        },
    ) == []
