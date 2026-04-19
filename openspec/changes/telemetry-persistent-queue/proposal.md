## Why

The current TelemetryService uses best-effort delivery — if PostHog is unreachable when the hourly heartbeat fires, the event is silently dropped and the metrics for that hour are lost. Since containers often run in environments without internet access (air-gapped labs, CI pipelines, corporate networks), this means significant usage data is never captured.

## What Changes

- Persist unsent telemetry events to PostgreSQL instead of dropping them on failure
- On each heartbeat cycle, retry sending any queued events before sending the new one
- Cap the queue to prevent unbounded growth (e.g., keep last 168 events = 7 days of hourly heartbeats)
- On successful send, remove the event from the queue

## Capabilities

### New Capabilities
- `telemetry-queue`: Persist unsent telemetry events to a `telemetry_queue` table in PostgreSQL and retry on subsequent heartbeat cycles. Includes queue size cap and oldest-first delivery.

### Modified Capabilities

## Impact

- **Backend (Java)**: Modify `TelemetryService.java` to write failed events to DB and retry on next cycle. Add `telemetry_queue` table to `SchemaManager.java`.
- **Database**: New `telemetry_queue` table (id SERIAL, event_json TEXT, created_at TIMESTAMP).
- **No frontend changes**.
