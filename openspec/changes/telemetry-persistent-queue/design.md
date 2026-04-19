## Context

`TelemetryService` currently sends heartbeat events via HTTP POST to PostHog. On failure, the event is silently discarded. The `previousCounts` map resets, so the next heartbeat only captures the delta from that point — the failed hour's data is gone.

## Goals / Non-Goals

**Goals:**
- Never lose telemetry data — persist to PostgreSQL on send failure
- Drain queued events on next successful connection
- Bound queue size to prevent DB bloat

**Non-Goals:**
- Real-time delivery guarantees
- Exactly-once delivery (at-least-once is fine — PostHog deduplicates by event ID)
- Separate retry thread or exponential backoff

## Decisions

### D1: Store failed events in PostgreSQL

**Choice:** New `telemetry_queue` table with columns `(id SERIAL PRIMARY KEY, event_json TEXT NOT NULL, created_at TIMESTAMP DEFAULT NOW())`. On send failure, INSERT the JSON payload. On next heartbeat, SELECT oldest events first, attempt to send each, DELETE on success.

**Why PostgreSQL over file?** PostgreSQL is already running and managed. No filesystem permission issues. Atomic INSERT/DELETE. Queryable for debugging.

### D2: Queue cap of 168 events (7 days)

**Choice:** After inserting a new event, DELETE oldest rows when count exceeds 168. This keeps ~7 days of hourly heartbeats.

**Why 168?** One week of hourly events. Enough to survive a long weekend offline. Small footprint (~100 KB total).

### D3: Drain queue before sending new heartbeat

**Choice:** On each heartbeat cycle: (1) try sending queued events oldest-first, (2) collect and send new heartbeat, (3) if new heartbeat fails, queue it. Stop draining on first failure (network is down).

**Why oldest-first?** Preserves chronological order in PostHog. Stops on first failure to avoid hammering a down endpoint.

## Risks / Trade-offs

- **[Risk] Queue grows if offline for weeks** → Mitigated by D2 (cap at 168, oldest dropped).
- **[Risk] PostgreSQL is also down** → INSERT fails silently, event lost. Acceptable — if PG is down, bigger problems exist.
