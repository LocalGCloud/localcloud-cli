# LocalCloud MCP Integration

LocalCloud exposes an MCP server at `http://localhost:8080/mcp` so agents can discover LocalCloud services, generate localhost GCP SDK/Terraform configuration, inspect compatibility, browse local resources, query local data, and debug failures without using real Google Cloud.

Use this description when registering the server in an MCP client:

```text
LocalCloud MCP gives agents safe access to a local Google Cloud emulator. Use it to list available local GCP-compatible services, fetch SDK/gcloud/Terraform environment variables, check LocalCloud compatibility, browse local resources, query local data, read diagnostics, and avoid accidental calls to real Google Cloud.
```

## Required: Start LocalCloud First

The MCP server runs inside the LocalCloud gateway. Users must start the LocalCloud Docker container before registering or using the MCP server.

```bash
docker volume create localcloud-data

docker run -d --name localcloud \
  -p 8080:8080 \
  -p 4443:4443 \
  -p 8085:8085 \
  -p 8086:8086 \
  -p 8087:8087 \
  -p 9010:9010 \
  -p 9020:9020 \
  -p 9050:9050 \
  -p 9060:9060 \
  -p 6379:6379 \
  -m 4g \
  -v localcloud-data:/var/lib/localcloud \
  localcloud/localcloud:latest
```

Verify MCP is reachable:

```bash
curl -s http://localhost:8080/mcp | jq
```

Expected shape:

```json
{
  "name": "localcloud-mcp",
  "protocolVersion": "2025-11-25",
  "transport": "streamable-http",
  "endpoint": "/mcp"
}
```

## Register the MCP Server

### HTTP MCP Client

Use this when the client supports HTTP MCP servers:

```json
{
  "mcpServers": {
    "localcloud": {
      "type": "http",
      "url": "http://localhost:8080/mcp",
      "description": "LocalCloud MCP gives agents safe access to local Google Cloud-compatible resources, environment setup, compatibility data, resource browsing, diagnostics, and local-only query/debug tools."
    }
  }
}
```

### Stdio MCP Client

Use the stdio bridge when the MCP client can only launch a local command:

```json
{
  "mcpServers": {
    "localcloud": {
      "command": "/Users/jsenjaliya/src/AI/localcloud/scripts/localcloud-mcp-stdio.py",
      "args": [
        "--endpoint",
        "http://localhost:8080/mcp"
      ],
      "description": "Bridge stdio MCP clients to the LocalCloud Docker container's HTTP MCP endpoint. LocalCloud must already be running."
    }
  }
}
```

The stdio bridge does not start Docker. It only forwards MCP requests to the running LocalCloud container.

## What LocalCloud Provides

The endpoint supports MCP protocol version `2025-11-25` and currently implements:

- `initialize`
- `ping`
- `tools/list`
- `tools/call`
- `resources/list`
- `resources/templates/list`
- `resources/read`
- `prompts/list`
- `prompts/get`

The server is backed by LocalCloud's existing source-of-truth surfaces:

- `services.yaml` and `ServiceRegistry` for service ids, endpoints, protocols, env vars, Terraform vars, tiers, and default enablement.
- `CompatibilityRegistry` and `CapabilityCatalog` for supported, partial, and unsupported behavior.
- Admin APIs for `/env`, `/readiness`, `/diagnostics`, `/requests`, `/browse`, `/query`, `/seed`, `/reset`, and `/export` behavior.

The MCP server never falls back to real Google Cloud. Unsupported or unavailable behavior returns an explicit LocalCloud error or compatibility warning.

## Safety Model

The default mode is read-only.

| Capability | Default | Enable with |
|------------|---------|-------------|
| Read resources and call read-only tools | Enabled | Always available |
| Seed/import state | Disabled | `LOCALCLOUD_MCP_WRITE=true` |
| Reset data and manage fault injection | Disabled | `LOCALCLOUD_MCP_DESTRUCTIVE=true` |
| Accept remote/non-local HTTP clients | Disabled | `LOCALCLOUD_MCP_ALLOW_REMOTE=true` |

By default, `/mcp` rejects non-local `Origin` headers and remote socket addresses. Keep it bound to localhost unless you are deliberately exposing it inside a trusted development network.

## HTTP MCP Integration

Use the HTTP endpoint when the MCP client supports Streamable HTTP.

Example initialization request:

```bash
curl -s http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2025-11-25' \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
      "protocolVersion": "2025-11-25",
      "capabilities": {},
      "clientInfo": {"name": "example-agent", "version": "dev"}
    }
  }' | jq
```

List tools:

```bash
curl -s http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' | jq
```

Call a tool:

```bash
curl -s http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -d '{
    "jsonrpc":"2.0",
    "id":3,
    "method":"tools/call",
    "params": {
      "name": "localcloud_list_services",
      "arguments": {}
    }
  }' | jq
```

Read a resource:

```bash
curl -s http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -d '{
    "jsonrpc":"2.0",
    "id":4,
    "method":"resources/read",
    "params": {"uri": "localcloud://env/json"}
  }' | jq
```

## Test the Stdio Bridge

After the Docker container is running, test the bridge manually:

```bash
printf '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}\n' \
  | ./scripts/localcloud-mcp-stdio.py \
  | jq
```

The bridge reads one JSON-RPC message per stdin line, forwards it to `http://localhost:8080/mcp`, and writes the JSON-RPC response to stdout. It does not start the Docker container.

## Resources

Stable context is exposed as `localcloud://` resources.

| URI | Description |
|-----|-------------|
| `localcloud://services` | All known services, endpoints, env vars, protocols, enablement, and compatibility metadata |
| `localcloud://env/shell` | Shell exports for SDKs and gcloud |
| `localcloud://env/json` | JSON env var map |
| `localcloud://env/terraform` | Terraform Google provider custom endpoint exports |
| `localcloud://readiness` | Global readiness context |
| `localcloud://readiness/{service}` | Service-specific readiness context |
| `localcloud://compatibility` | Full compatibility registry |
| `localcloud://compatibility/{service}` | Service-specific compatibility entry |
| `localcloud://diagnostics/latest` | Current diagnostics bundle |
| `localcloud://terraform/readiness` | Terraform endpoint/readiness context |
| `localcloud://browse/{service}/{resourceType}` | Browse a resource type for a service |
| `localcloud://browse/{service}/{resourceType}/{resourceId}` | Browse a specific resource |

Examples:

```json
{"uri": "localcloud://services"}
{"uri": "localcloud://compatibility/bigquery"}
{"uri": "localcloud://browse/gcs/buckets"}
```

## Tools

Read-only tools are always available:

| Tool | Purpose |
|------|---------|
| `localcloud_list_services` | List LocalCloud services and metadata |
| `localcloud_get_service` | Inspect one service by id |
| `localcloud_get_env` | Generate shell, JSON, OAuth, Docker Compose, or Terraform env output |
| `localcloud_check_readiness` | Check global or service-specific readiness context |
| `localcloud_check_compatibility` | Check global or service-specific compatibility |
| `localcloud_browse_resources` | Browse LocalCloud resource inventory |
| `localcloud_read_resource` | Read a `localcloud://` resource URI |
| `localcloud_query_data` | Run a local-only query through LocalCloud query APIs |
| `localcloud_generate_sdk_env` | Generate SDK and gcloud env exports |
| `localcloud_generate_gcloud_env` | Generate gcloud endpoint override env exports |
| `localcloud_generate_terraform_env` | Generate Terraform endpoint env exports |
| `localcloud_validate_agent_config` | Check agent config text/env for accidental real Google Cloud endpoint usage |
| `localcloud_get_diagnostics` | Return current diagnostics |
| `localcloud_get_recent_requests` | Return request log entries |
| `localcloud_get_logs` | Return request-log-oriented diagnostics |
| `localcloud_export_state` | Export seed-compatible state |

Write and destructive tools are hidden from `tools/list` until enabled:

| Tool | Required flag |
|------|---------------|
| `localcloud_seed_project` | `LOCALCLOUD_MCP_WRITE=true` |
| `localcloud_import_state` | `LOCALCLOUD_MCP_WRITE=true` |
| `localcloud_reset_project` | `LOCALCLOUD_MCP_DESTRUCTIVE=true` |
| `localcloud_reset_service` | `LOCALCLOUD_MCP_DESTRUCTIVE=true` |
| `localcloud_create_fault` | `LOCALCLOUD_MCP_DESTRUCTIVE=true` |
| `localcloud_clear_faults` | `LOCALCLOUD_MCP_DESTRUCTIVE=true` |

`localcloud_import_state` is currently reserved for future import-state wiring and returns a tool-not-implemented error if called.

## Prompts

Prompts give agents reusable workflows:

| Prompt | Use |
|--------|-----|
| `use-localcloud-instead-of-gcp` | Configure SDKs/tools to localhost endpoints before running cloud code |
| `debug-localcloud-service` | Gather readiness, compatibility, diagnostics, and request evidence |
| `write-localcloud-integration-test` | Create local-only tests using LocalCloud env vars |
| `seed-localcloud-scenario` | Produce deterministic seed YAML |
| `terraform-with-localcloud` | Prepare Terraform endpoint env and readiness checks |
| `compatibility-aware-implementation` | Check compatibility before choosing APIs |

Example:

```bash
curl -s http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -d '{
    "jsonrpc":"2.0",
    "id":5,
    "method":"prompts/get",
    "params":{"name":"terraform-with-localcloud"}
  }' | jq
```

## Recommended Agent Workflow

1. Initialize the MCP session.
2. Read `localcloud://services`.
3. Call `localcloud_check_compatibility` for every target service before selecting APIs.
4. Call `localcloud_get_env` or `localcloud_generate_terraform_env` and apply the returned localhost endpoint config.
5. Run the integration against LocalCloud.
6. On failure, call `localcloud_get_diagnostics` and `localcloud_get_recent_requests`.
7. Do not call real Google Cloud as a fallback.

For Terraform work, always call `localcloud_generate_terraform_env` before `terraform plan` or `terraform apply`.

## Enabling Write or Destructive Operations

Use write mode only in disposable local environments:

```bash
docker run -d --name localcloud \
  -p 8080:8080 \
  -e LOCALCLOUD_MCP_WRITE=true \
  localcloud/localcloud:latest
```

Use destructive mode only when reset/fault tools are expected:

```bash
docker run -d --name localcloud \
  -p 8080:8080 \
  -e LOCALCLOUD_MCP_DESTRUCTIVE=true \
  localcloud/localcloud:latest
```

You can combine both flags, but agents should still ask for operator confirmation before reset, import, or fault-injection workflows.

## Troubleshooting

### `/mcp` returns 403

The request likely has a non-local `Origin` header or is coming from a non-local address. Use localhost, or explicitly set:

```bash
-e LOCALCLOUD_MCP_ALLOW_REMOTE=true
```

Only use remote mode on trusted networks.

### Tool is missing from `tools/list`

Write and destructive tools are hidden unless their safety flags are enabled. Check `curl http://localhost:8080/mcp | jq '.safety'`.

### Agent still calls real Google Cloud

Use `localcloud_validate_agent_config` on the generated config or prompt. Also apply the output from `localcloud_get_env` or `localcloud_generate_terraform_env` before running tests.

### Stdio client cannot connect

The stdio bridge does not start LocalCloud. Start the container first, then verify:

```bash
curl -s http://localhost:8080/mcp | jq
```

Then run the bridge manually:

```bash
printf '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}\n' \
  | ./scripts/localcloud-mcp-stdio.py \
  | jq
```

### Compatibility says partial or unsupported

That is expected for services where LocalCloud intentionally emulates a subset of Google Cloud behavior. Agents should treat compatibility metadata as authoritative and avoid unsupported API paths.
