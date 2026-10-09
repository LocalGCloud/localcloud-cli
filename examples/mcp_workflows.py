"""Exercise LocalCloud MCP discovery and three standard Google SDK workflows.

Requires mcp==2.0.0, google-cloud-storage, google-cloud-pubsub, and google-cloud-bigquery.
Start a test runtime with GCS, Pub/Sub, and BigQuery, then pass its --data-volume.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
from urllib.parse import urlsplit
from uuid import uuid4

from google.auth.credentials import AnonymousCredentials
from google.cloud import bigquery, pubsub_v1, storage
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


def sdk_workflows(env: dict) -> None:
    for key in ("STORAGE_EMULATOR_HOST", "PUBSUB_EMULATOR_HOST", "BIGQUERY_EMULATOR_HOST"):
        endpoint = env[key]
        host = urlsplit(endpoint if "://" in endpoint else "//" + endpoint).hostname
        if host not in {"127.0.0.1", "localhost", "::1"}:
            raise ValueError(f"Refusing a non-local {key}")
        os.environ[key] = endpoint
    project = env["GOOGLE_CLOUD_PROJECT"]
    credentials = AnonymousCredentials()
    name = "lc-mcp-" + uuid4().hex[:16]

    client = storage.Client(project=project, credentials=credentials)
    bucket = client.create_bucket(name)
    try:
        blob = bucket.blob("agent-test.txt")
        blob.upload_from_string("LocalCloud agent integration test", timeout=30)
        assert blob.download_as_text(timeout=30) == "LocalCloud agent integration test"
        print("PASS Cloud Storage: upload and read back the exact content")
    finally:
        bucket.delete(force=True, timeout=30)
        client.close()

    publisher = pubsub_v1.PublisherClient(credentials=credentials)
    subscriber = pubsub_v1.SubscriberClient(credentials=credentials)
    topic = publisher.topic_path(project, name)
    subscription = subscriber.subscription_path(project, name)
    topic_created = subscription_created = False
    try:
        publisher.create_topic(request={"name": topic}, timeout=30)
        topic_created = True
        subscriber.create_subscription(request={"name": subscription, "topic": topic}, timeout=30)
        subscription_created = True
        publisher.publish(topic, b"LocalCloud agent message").result(timeout=30)
        messages = subscriber.pull(request={"subscription": subscription, "max_messages": 1}, timeout=30)
        assert len(messages.received_messages) == 1
        message = messages.received_messages[0]
        assert message.message.data == b"LocalCloud agent message"
        subscriber.acknowledge(request={"subscription": subscription, "ack_ids": [message.ack_id]}, timeout=30)
        print("PASS Pub/Sub: publish, pull, verify, and acknowledge")
    finally:
        try:
            if subscription_created:
                subscriber.delete_subscription(request={"subscription": subscription}, timeout=30)
        finally:
            if topic_created:
                publisher.delete_topic(request={"topic": topic}, timeout=30)
            publisher.stop()
            subscriber.close()

    client = bigquery.Client(project=project, credentials=credentials,
                            client_options={"api_endpoint": env["BIGQUERY_EMULATOR_HOST"]})
    try:
        rows = list(client.query("SELECT 1 AS mcp_smoke").result(timeout=30))
        assert len(rows) == 1 and rows[0].mcp_smoke == 1
        print("PASS BigQuery: SELECT 1 returns the expected row")
    finally:
        client.close()
    print("Cleanup complete: only uniquely named example resources were removed")


async def main(command: str, volume: str) -> None:
    bridge_env = {"DOCKER_CONTEXT": os.environ["DOCKER_CONTEXT"]} if "DOCKER_CONTEXT" in os.environ else None
    params = StdioServerParameters(command=command, args=["mcp", "--no-start", "--data-volume", volume], env=bridge_env)
    async with stdio_client(params) as (read, write):
        async with ClientSession(read, write) as session:
            initialized = await session.initialize()
            tools = await session.list_tools()
            print(f"Connected to LocalCloud runtime {initialized.server_info.version}: {len(tools.tools)} tools")
            for name, arguments in [
                ("localcloud_list_services", {}),
                ("localcloud_check_readiness", {}),
                ("localcloud_check_compatibility", {}),
                ("localcloud_get_diagnostics", {}),
            ]:
                result = await session.call_tool(name, arguments)
                assert not result.model_dump(by_alias=True).get("isError"), name
                print(f"PASS MCP: {name}")
            environment = await session.call_tool("localcloud_get_env", {"format": "json"})
            response = environment.model_dump(by_alias=True)
            assert not response.get("isError"), "localcloud_get_env"
            env = response["structuredContent"]["result"]
            assert isinstance(env, dict)
            print("PASS MCP: SDK configuration validates against its output schema")
            query = await session.call_tool("localcloud_query_data", {"service": "bigquery", "sql": "SELECT 1 AS mcp_smoke"})
            response = query.model_dump(by_alias=True)
            assert not response.get("isError"), "localcloud_query_data"
            result = json.loads(response["structuredContent"]["result"])
            assert result["columns"] == ["mcp_smoke"] and result["row_count"] == 1
            assert len(result["rows"]) == 1 and str(result["rows"][0][0]) == "1"
            print("PASS MCP: BigQuery query returns the expected row")
            await asyncio.to_thread(sdk_workflows, env)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--command", default="localcloud", help="LocalCloud CLI executable or plugin launcher")
    parser.add_argument("--data-volume", required=True, help="Your test runtime's named Docker volume")
    args = parser.parse_args()
    asyncio.run(main(args.command, args.data_volume))
