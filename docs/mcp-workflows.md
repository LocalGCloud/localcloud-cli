# Three local cloud workflows for your coding agent

LocalCloud MCP gives an agent the service catalog, readiness information, and SDK settings it needs to test a cloud application locally. These examples use standard Google Cloud SDKs and synthetic data. Docker, **CLI 0.1.9+**, and **runtime 0.1.5+** are required.

[Website](https://local.cloud/) · [MCP setup guide](https://local.cloud/docs/mcp/) · [Runnable example](../examples/mcp_workflows.py)

## Run the complete example

From this repository, start a separate runtime for the examples:

```sh
lc start --image agentcloud/localcloud:0.1.5 \
  --data-volume localcloud-mcp-demo \
  --services gcs,pubsub,bigquery --local-only --accept-dynamic-ports
```

Run the Python example with its optional SDK dependencies using [uv](https://docs.astral.sh/uv/):

```sh
uv run --with mcp==2.0.0 --with google-cloud-storage \
  --with google-cloud-pubsub --with google-cloud-bigquery \
  examples/mcp_workflows.py --data-volume localcloud-mcp-demo
```

The script connects through MCP, validates discovery and tool results, obtains the runtime's actual endpoints, and rejects non-loopback SDK hosts. It then runs the three assertions below using anonymous local credentials. MCP SDK 2.0.0 is the example's client-library version; it is independent of the LocalCloud CLI and runtime versions.

When finished, stop only the example runtime:

```sh
lc stop --data-volume localcloud-mcp-demo
```

The named data volume remains available for reuse. The script removes its uniquely named bucket, topic, and subscription. It does not reset your project or remove other application resources.

## 1. Upload an object and verify its content

Agent prompt:

> Use LocalCloud MCP to check Cloud Storage readiness and obtain the SDK environment. Create a uniquely named test bucket, upload `agent-test.txt`, read it back, and assert that its content is unchanged. Delete only the bucket created by this test. Use the returned local endpoints and do not fall back to real Google Cloud.

The example uses `storage.Client`, uploads a known string, and checks an exact download match. A `finally` block removes its bucket, including the test object.

## 2. Publish and consume a message

Agent prompt:

> Check Pub/Sub readiness through LocalCloud MCP and get its local SDK endpoint. Create a uniquely named topic and subscription, publish a known message, pull it, assert the payload, and acknowledge it. Clean up only this test's topic and subscription, including when an assertion fails.

The example waits for the publish result, pulls one message, checks the exact bytes, and acknowledges the received message before cleanup.

## 3. Query local BigQuery data

Agent prompt:

> Use LocalCloud MCP to inspect BigQuery compatibility and obtain the local endpoint. Run `SELECT 1 AS mcp_smoke` with the standard BigQuery SDK and assert that it returns exactly one row with value 1. Keep the API endpoint explicit and use no real cloud credentials.

The example creates `bigquery.Client` with the discovered API endpoint and checks the returned row. You can also ask the agent to run the same SQL using `localcloud_query_data` with `{"service":"bigquery","sql":"SELECT 1 AS mcp_smoke"}`.

## Demonstrated output

The example produced these results against the full packaged runtime 0.1.5 candidate on Linux ARM64, through the published macOS ARM64 CLI 0.1.9:

```text
PASS MCP: localcloud_list_services
PASS MCP: localcloud_check_readiness
PASS MCP: localcloud_check_compatibility
PASS MCP: localcloud_get_diagnostics
PASS MCP: SDK configuration validates against its output schema
PASS MCP: BigQuery query returns the expected row
PASS Cloud Storage: upload and read back the exact content
PASS Pub/Sub: publish, pull, verify, and acknowledge
PASS BigQuery: SELECT 1 returns the expected row
Cleanup complete: only uniquely named example resources were removed
```

[Watch the recorded verification run](assets/localcloud-mcp-demo.mp4). The same assertions also passed against the exact AMD64 release image running under emulation in an isolated ARM64 VM.

This is packaged-runtime evidence. It does not establish Google Cloud production parity or native hardware qualification on every platform. If a required service is disabled or unavailable, stop and inspect readiness and diagnostics before proceeding.
