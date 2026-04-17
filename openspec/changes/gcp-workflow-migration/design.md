## Context

The remote workflow source is an internal ephemeral environment tool that provisions K8s namespaces, deploys services as containers, and forks GCP Workflows to route traffic through those ephemeral services. Teams use it to test workflow changes against isolated service instances.

LocalCloud already emulates the GCP Workflows engine locally (expression evaluator, all step types, stdlib, connectors, callbacks). The gap is: there's no way to pull workflows from the remote source into LocalCloud, and no way to configure where service calls route.

A developer's workflow today: edit YAML → deploy to the remote environment → the environment forks to GCP → wait → test → repeat. Target: edit YAML locally → run instantly on LocalCloud → calls route wherever configured.

## Goals / Non-Goals

**Goals:**
- Users can import their workflows into LocalCloud from a remote source with one click
- Hardcoded remote proxy URLs are automatically rewritten to `${VAR}` patterns
- Users can switch all service routing between Local/Remote/Production with preset buttons
- Same workflow YAML works across all environments — only env vars change
- Zero workflow YAML editing required after import

**Non-Goals:**
- Replacing the remote source's K8s provisioning, service deployment, or environment management
- Running user services inside LocalCloud's container (users run their own services separately)
- Syncing changes back to the remote source (one-way import only)
- Supporting additional remote import sources beyond the current connector (GCP direct import is a future feature)

## Decisions

### 1. URL Rewriting Strategy: `${VAR}` Pattern

Imported workflow YAML uses environment variable patterns:
```yaml
# Remote source original:
url: http://10.179.131.124/proxy/jay-env/payment-service/api/charge

# After import:
url: ${PAYMENT_SERVICE_URL}/api/charge
```

The variable name is derived from the service name: `payment-service` → `PAYMENT_SERVICE_URL`. The rewriter extracts the remote proxy URL pattern (`/proxy/{env}/{service}/...`), isolates the service name and the path suffix, and generates the variable.

**Why `${VAR}` and not runtime routing maps:** The `${VAR}` pattern is already supported by the expression evaluator's template resolution. It's the standard GCP Workflows approach (`sys.get_env()`). Users can understand and edit it. And it works outside LocalCloud too — if they deploy the same YAML to GCP with Cloud Build env vars.

### 2. Environment Variables: PostgreSQL Table

```sql
CREATE TABLE workflow_env_vars (
    project_id VARCHAR(255) NOT NULL,
    var_name VARCHAR(255) NOT NULL,
    var_value TEXT,
    preset VARCHAR(50) DEFAULT 'default',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, var_name, preset)
);
```

Three preset rows per variable:
- `preset = 'local'` → `PAYMENT_SERVICE_URL = http://localhost:3001`
- `preset = 'remote'` → `PAYMENT_SERVICE_URL = http://10.179.131.124/proxy/jay/payment-service`
- `preset = 'production'` → `PAYMENT_SERVICE_URL = https://payment.prod.internal`

An `active_preset` setting (in existing `service_routing` or a new config table) tracks which preset is active.

### 3. `sys.get_env()` Resolution Order

When a workflow calls `sys.get_env("PAYMENT_SERVICE_URL")`:
1. Check `workflow_env_vars` table for the active preset → returns configured value
2. Fall back to OS `System.getenv()` → returns container env var if set
3. Return `null` if not found

This means env vars configured in the dashboard always override OS env vars, which is the expected behavior for a managed configuration system.

### 4. Template Resolution Enhancement

The expression evaluator's `evaluateTemplate()` already handles `${expr}`. For env var patterns:
- `${PAYMENT_SERVICE_URL}` is currently treated as a variable reference in the expression context
- We inject all workflow env vars into the execution context's initial variables
- This means `${PAYMENT_SERVICE_URL}/api/charge` resolves naturally — `PAYMENT_SERVICE_URL` is a variable that resolves to the URL, `+ "/api/charge"` is string concatenation

No expression evaluator changes needed — just inject env vars into `ExecutionContext` before execution.

### 5. Remote Source API Integration

The remote workflow source exposes these endpoints:
```
GET  /api/workflows/list?user={user}     → [{name, ...}]
GET  /api/workflows/source?user={user}&workflow={name}  → {source: "yaml..."}
GET  /api/workflows/templates            → [{name, ...}]
GET  /api/list                           → [{id, owner, namespace, services, ...}]
GET  /api/status/{envId}                 → {services: [...], endpoints: [...]}
```

The connector calls these to:
1. List available workflows for import
2. Fetch workflow YAML source
3. Discover deployed services and their proxy URLs (for auto-populating the Remote preset)

Connection config (source URL + username) stored in PostgreSQL config table.

### 6. UI Layout

**Workflows page — new "Import" section:**
```
┌─ Cloud Workflows ──────────────────────────────┐
│                                                  │
│  [Import from Remote]  [Environment ▾]           │
│                                                  │
│  ┌─ Import Modal ─────────────────────────────┐  │
│  │ Source URL: [http://10.179.131.124       ] │  │
│  │ Username:   [jay                          ] │  │
│  │ [Connect]                                   │  │
│  │                                             │  │
│  │ Workflows:                                  │  │
│  │ ☑ order-pipeline        12 steps            │  │
│  │ ☑ data-sync             8 steps             │  │
│  │ ☐ notify-dispatch       (already imported)  │  │
│  │                                             │  │
│  │ [Import Selected]                           │  │
│  └─────────────────────────────────────────────┘  │
│                                                  │
│  ┌─ Environment Vars ─────────────────────────┐  │
│  │ Preset: [Local] [Remote] [Production]       │  │
│  │                                             │  │
│  │ PAYMENT_SERVICE_URL  http://localhost:3001   │  │
│  │ ORDER_SERVICE_URL    http://localhost:3002   │  │
│  │ NOTIFY_SERVICE_URL   http://localhost:3003   │  │
│  │                                             │  │
│  │ [+ Add Variable]                            │  │
│  └─────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────┘
```

## Risks / Trade-offs

- [Source API changes] → Pin to known API paths. If the remote source changes its API, the connector breaks but workflows already imported continue to work. Add error handling with clear messages.
- [URL rewriting false positives] → The rewriter looks for the pattern `/proxy/{env}/{service}/`. If a workflow has URLs that match this pattern but aren't remote proxies, they'll be incorrectly rewritten. Mitigation: show the user what was rewritten during import and let them undo specific rewrites.
- [Env var naming collisions] → If two services produce the same `SERVICE_NAME_URL` variable name, the second overwrites the first. Mitigation: use the full service name including hyphens — `PAYMENT_SERVICE_URL` not `PAYMENTSERVICEURL`.
- [Network access to remote source] → The user's laptop must be able to reach the remote source server (internal network). If they're off-VPN, import and the Remote preset won't work — but the Local preset still works fine.
