from __future__ import annotations


_SERVICE_INVENTORY = (
    ("gcs", "Cloud Storage", True),
    ("pubsub", "Pub/Sub", True),
    ("firestore", "Firestore", True),
    ("bigtable", "Bigtable", True),
    ("spanner", "Spanner", True),
    ("bigquery", "BigQuery", True),
    ("sheets", "Google Sheets", True),
    ("secretmanager", "Secret Manager", True),
    ("cloudtasks", "Cloud Tasks", True),
    ("cloudscheduler", "Cloud Scheduler", True),
    ("cloudfunctions", "Cloud Functions (2nd Gen)", True),
    ("alloydb", "AlloyDB", True),
    ("dataproc", "Dataproc", True),
    ("cloudiam", "Cloud IAM", True),
    ("cloudresourcemanager", "Cloud Resource Manager", True),
    ("serviceusage", "Service Usage", True),
    ("cloudbilling", "Cloud Billing", True),
    ("logging", "Cloud Logging", True),
    ("monitoring", "Cloud Monitoring", True),
    ("gke", "GKE", False),
    ("compute", "Compute Engine", False),
    ("cloudrun", "Cloud Run", False),
    ("memorystore", "Memorystore (Redis/Valkey)", True),
    ("workflows", "Cloud Workflows", True),
    ("vertexai", "Vertex AI", False),
    ("kms", "Cloud KMS", False),
    ("cloudsql", "Cloud SQL", True),
)


def _service_inventory() -> str:
    return "\n".join(
        (
            f"  - {service_id}  # {display_name}"
            if enabled
            else f"  # - {service_id}  # deactivated service: {display_name}"
        )
        for service_id, display_name, enabled in _SERVICE_INVENTORY
    )


_GUIDE = f"""LocalCloud coding-agent guide

Prerequisites

- Docker is running.
- The `localcloud` CLI is installed.
- Run commands from the directory that contains `localcloud.yaml`, when using
  configuration. The directory is only a configuration source, not identity.

Identity defaults

- The default instance is one shared Docker stack: container and network
  `localcloud`, with volume `localcloud-data`.
- The default project is `local-gcp-project`.
- The default caller is `local-developer`, normalized to
  `local-developer@localcloud.invalid` where an email principal is required.
- `--project-id ID` selects logical data without creating another Docker stack.
  Only `start` creates a missing project; other context-selecting commands fail
  with recovery guidance when the project is unknown.
- `--user NAME` selects the attributed caller. Use `--instance NAME` only when
  a separate deterministic Docker stack is intentional.

Copy-paste first run

1. Check Docker without creating LocalCloud state:

   localcloud doctor

   Continue when the command exits successfully and returns `"status": "ok"`.
   Review any legacy-resource warning separately; LocalCloud never removes those
   resources automatically.

2. Start the shared default instance and configure the current shell:

   localcloud start
   eval "$(localcloud env)"

   `start` returns JSON containing the ready container, selected project and
   caller, loopback SDK endpoints, and an `mcp` object. Repeating it is safe.

3. Copy the returned `mcp.command` and `mcp.args` into a stdio-only MCP client.
   For a Streamable HTTP client, use `mcp.direct_url` together with every
   returned `mcp.headers` entry so the selected project and caller are carried
   on each request. Reconnect after `restart` or `reset`; the stdio bridge binds
   to one running instance.

MCP API-catalog-first workflow

1. Read `localcloud://api/catalog`, or search it with
   `localcloud_get_api_catalog`.
2. Select the documented management `operation_id`; never guess a raw route.
3. Call `localcloud_call_api` with that operation ID and its typed path/query
   parameters. Write and destructive operations require the corresponding
   `LOCALCLOUD_MCP_WRITE` and `LOCALCLOUD_MCP_DESTRUCTIVE` server settings.
4. For Google-compatible services, call `localcloud_list_services`, readiness,
   compatibility, and agent-config validation tools before application traffic.

Optional copy-paste configuration

Paste this complete canonical service inventory into `localcloud.yaml`. Active
entries are default-enabled. Commented entries are available but deactivated by
default; uncomment one to include it in an explicit service set.

cat > localcloud.yaml <<'YAML'
services:
{_service_inventory()}
seed: auto
data: persistent
image: localcloud/localcloud:latest
memory: 4g
docker_socket: false
transparent_network: false
environment: {{}}
YAML
localcloud reset
eval "$(localcloud env)"

Every field is optional. Omit `services`, or set it to `default`, to use image
defaults. An explicit non-empty list is the complete enabled set; unknown IDs
fail startup. `project:` and `user:` select invocation context and may be
overridden with `--project-id` and `--user`. They never affect Docker identity.
If `image` is omitted, `LOCALCLOUD_IMAGE` wins before the default image.

`seed: auto` loads `seed.yaml` beside the selected config when it exists and is
otherwise a no-op. Set `seed: null` to disable seeding, or provide an existing
path relative to the config file.

Project and instance lifecycle

`localcloud reset` resets only the selected project and reapplies its configured
seed, preserving every other project in the shared instance. Use
`localcloud reset --all-projects` only for an intentional instance-wide data
reset. Persistent data survives stop/start and safe configuration replacement.
With `data: ephemeral`, `stop` removes that instance's container, network, and
volume.

Useful commands

   localcloud status
   localcloud logs --tail 200
   localcloud console --project-id another-project --user build-agent
   localcloud restart
   localcloud stop
   localcloud start --instance isolated --project-id another-project

Develop and test only against the loopback endpoints returned by LocalCloud.
Public Google endpoints are never a fallback. If an SDK cannot use the returned
local endpoint, stop and fix its configuration instead of sending a real cloud
request.
"""


def render_agent_guide() -> str:
    return _GUIDE
