# Anonymous Telemetry Design

## Goal

Add lightweight, anonymous telemetry to understand adoption, service usage, and stability — without collecting any personally identifiable information.

## What's Sent

### Heartbeat (every hour)

A single POST to PostHog containing aggregated local stats:

```json
{
  "event": "heartbeat",
  "distinct_id": "sha256(machine-id + container-id)",
  "properties": {
    "version": "1.0.0",
    "uptime_hours": 12,
    "container_starts": 3,
    "os_arch": "arm64",
    "os_name": "Linux",
    "java_version": "25.0.2",
    "memory_limit_mb": 4096,
    "pg_version": "17",
    "services_enabled": ["gcs", "pubsub", "firestore", "..."],
    "services_disabled": ["gke", "compute", "cloudrun"],
    "services_healthy": 12,
    "services_total": 15,
    "requests_gcs": 1240,
    "requests_pubsub": 890,
    "requests_total": 4200,
    "projects_count": 2,
    "seed_loaded": true,
    "credential_source": "none",
    "routing_remote_count": 0,
    "errors_last_hour": 3,
    "error_services": ["spanner"],
    "console_requests": 150
  }
}
```

### Error events (real-time, immediate)

Sent on service crash or repeated failure:

```json
{
  "event": "service_error",
  "distinct_id": "sha256(...)",
  "properties": {
    "service": "spanner",
    "error_type": "process_exit",
    "exit_code": 1,
    "version": "1.0.0",
    "os_arch": "arm64"
  }
}
```

## Architecture

- **No SDK** — raw `java.net.http.HttpClient` POST to `https://app.posthog.com/capture`
- **Collected locally** — aggregated from existing `UsageMetricsRepository`, `HealthCheckService`, `ProcessHealthChecker`
- **Sent hourly** — scheduled via `ScheduledExecutorService`
- **Persistent queue** — if PostHog is unreachable, events are persisted to `telemetry_queue` table and retried next hour. Queue capped at 168 events (7 days). No metrics lost unless both PostHog and PostgreSQL are down.
- **Opt-out** — `LOCALCLOUD_TELEMETRY=false` disables entirely
- **Configurable** — `LOCALCLOUD_EVENT_API_KEY` env var for PostHog project key, `LOCALCLOUD_POSTHOG_URL` for self-hosted PostHog
- **Anonymous** — `distinct_id` is SHA-256 of hostname + machine-id + MAC address. IP geo-enrichment via PostHog (processes IP for city/country, discards raw IP)

## Privacy

- No user identity, email, or account info
- No request payloads or data content
- No IP address logging (PostHog project setting: disable IP collection)
- Instance ID is a one-way hash — cannot be reversed
- All data is aggregate counters, not individual actions
- Users can opt out with a single env var

## Opt-out

```bash
docker run -e LOCALCLOUD_TELEMETRY=false ...
```

Or in docker-compose.yml:
```yaml
environment:
  LOCALCLOUD_TELEMETRY: "false"
```

## Files

| File | Change |
|------|--------|
| **New:** `TelemetryService.java` | Collects stats, sends hourly POST, persistent queue for retries |
| `LocalCloudApplication.java` | Instantiate, start, and stop TelemetryService |
| `SchemaManager.java` | Add `telemetry_queue` table |
| `Dockerfile` | Add `LOCALCLOUD_TELEMETRY` and `LOCALCLOUD_EVENT_API_KEY` ENV defaults |
| `README.md` | Document telemetry + opt-out |

## PostHog Configuration

- Project: Create "LocalCloud" project on PostHog Cloud
- API key: Stored as constant in `TelemetryService.java` (public project key, not secret)
- Disable IP collection in PostHog project settings
- PostHog MCP server available for querying dashboards from Claude
