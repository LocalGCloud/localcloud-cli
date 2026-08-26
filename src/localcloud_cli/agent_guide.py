from __future__ import annotations

from .config import DEFAULT_IMAGE, DEFAULT_PROJECT, DEFAULT_USER


_SERVICE_INVENTORY = (
    ("gcs", "Cloud Storage", True),
    ("pubsub", "Pub/Sub", True),
    ("firestore", "Firestore", False),
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
            f"    - {service_id}  # {display_name}"
            if enabled
            else f"    # - {service_id}  # deactivated service: {display_name}"
        )
        for service_id, display_name, enabled in _SERVICE_INVENTORY
    )


_GUIDE = f"""LocalCloud coding-agent guide

Prerequisites

- Docker is running.
- The `localcloud` CLI is installed.
- Run commands from the directory that contains `localcloud.yaml`, when using
  configuration. The directory is only a configuration source, not identity.

Runtime and request identity

- Durable runtime identity is the named Docker volume mounted at
  `/var/lib/localcloud`. The default is `localcloud-data`.
- `--data-volume NAME` selects a different runtime. The CLI discovers the
  unique compatible LocalCloud container using that volume, even when another
  tool created the container.
- The default project is `{DEFAULT_PROJECT}`.
- The default caller is `{DEFAULT_USER}`, normalized to
  `{DEFAULT_USER}@localcloud.invalid` where an email principal is required.
- `--project-id ID` selects logical data inside the runtime. Only `start`
  creates a missing project; other context-selecting commands fail with
  recovery guidance when the project is unknown.
- `--user NAME` selects the attributed caller and never changes Docker
  identity.

Copy-paste first run

1. Check Docker without creating LocalCloud state:

   localcloud doctor

   Continue when the command exits successfully and returns `"status": "ok"`.
   Review any legacy-resource warning separately; LocalCloud never removes those
   resources automatically.

2. Start the runtime on the default data volume and configure the current
   shell:

   localcloud start
   eval "$(localcloud env)"

   `start` returns JSON containing `data_volume`, the selected container,
   runtime origin and per-resource ownership, selected project and caller,
   loopback SDK endpoints, and an `mcp` object. Repeating it is safe.

3. Copy the returned `mcp.command` and `mcp.args` into a stdio-only MCP client.
   For a Streamable HTTP client, use `mcp.direct_url` together with every
   returned `mcp.headers` entry so the selected project and caller are carried
   on each request. Generated MCP arguments always pin `--data-volume`, so a
   long-lived bridge cannot silently switch to a later active runtime.

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

Paste this versioned shared configuration into `localcloud.yaml`. Active
entries are default-enabled. Commented entries are available but deactivated by
default; uncomment one to include it in an explicit service set.

cat > localcloud.yaml <<'YAML'
version: 1
context:
  project: {DEFAULT_PROJECT}
  user: {DEFAULT_USER}
host:
  data_volume: localcloud-data
  seed: auto
  data: persistent
  image: {DEFAULT_IMAGE}
  memory: 4g
  docker_socket: false
  transparent_network: false
  environment: {{}}
services:
  enabled:
{_service_inventory()}
YAML
localcloud reset
eval "$(localcloud env)"

Every section is optional. Omit `services.enabled`, or set it to `default`, to
use image defaults. An explicit non-empty list is the complete enabled set;
unknown IDs fail startup. `host.data_volume` selects durable runtime identity
and may be overridden with `--data-volume`. `context.project` and
`context.user` select invocation context and may be overridden with
`--project-id` and `--user`; they never affect Docker identity. If
`host.image` is omitted, `LOCALCLOUD_IMAGE` wins; if that is also unset, the
selected active runtime's recorded image wins, then the CLI default.

`host.seed: auto` loads `seed.yaml` beside the selected config when it exists
and is otherwise a no-op. Set `host.seed: disabled` to disable seeding, or
provide an existing path relative to the config file. The selected file is
mounted read-only for the container; server/catalog values apply on restart
without being copied into Docker labels.

Project and runtime lifecycle

`localcloud reset` resets only the selected project and reapplies its configured
seed, preserving every other project on the selected data volume. Use
`localcloud reset --all-projects` only for an intentional full reset of a
fully CLI-managed runtime; attached containers, networks, or volumes are
rejected before Docker mutation. Persistent data survives stop/start and safe
managed configuration replacement. `stop` may stop an attached runtime but
never removes Docker resources the CLI does not own.

Legacy `instance:` and `volume_name:` configuration fields are rejected with a
`data_volume:` migration value. Legacy CLI flags such as `--instance` and
`--volume-name` are not accepted.

Useful commands

   localcloud status
   localcloud logs --tail 200
   localcloud console --project-id another-project --user build-agent
   localcloud restart
   localcloud stop
   localcloud start --data-volume isolated-data --project-id another-project

Develop and test only against the loopback endpoints returned by LocalCloud.
Public Google endpoints are never a fallback. If an SDK cannot use the returned
local endpoint, stop and fix its configuration instead of sending a real cloud
request.
"""


def render_agent_guide() -> str:
    return _GUIDE
