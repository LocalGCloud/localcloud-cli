# Deltio Evaluation Report

**Project**: [jeffijoe/deltio](https://github.com/jeffijoe/deltio) — Rust-based Google Cloud Pub/Sub emulator alternative
**Date**: 2026-05-13
**Purpose**: Evaluate whether to replace the official Google Pub/Sub emulator in LocalCloud with Deltio, or build a new one.

---

## 1. Project Health

| Metric | Value |
|--------|-------|
| Stars | 28 |
| Contributors | 2 (bus factor = 1) |
| Age | 3+ years (since Apr 2023) |
| Latest release | v0.7.0 (Apr 2026) |
| Docker pulls | ~222K |
| License | MIT |
| CI | GitHub Actions (Linux/macOS/Windows) |

**Verdict**: Healthy but extremely low bus factor. Single maintainer. 222K pulls suggests real-world usage.

---

## 2. Feature Coverage vs Google Pub/Sub

### Publisher Service (gRPC)

| RPC | Google | Deltio | Gap |
|-----|--------|--------|-----|
| CreateTopic | ✅ | ✅ | — |
| UpdateTopic | ✅ | ❌ | Cannot update topic labels/config |
| Publish | ✅ | ✅ | — |
| GetTopic | ✅ | ✅ | — |
| ListTopics | ✅ | ✅ | — |
| ListTopicSubscriptions | ✅ | ✅ | — |
| ListTopicSnapshots | ✅ | ❌ | — |
| DeleteTopic | ✅ | ✅ | — |
| DetachSubscription | ✅ | ❌ | — |

### Subscriber Service (gRPC)

| RPC | Google | Deltio | Gap |
|-----|--------|--------|-----|
| CreateSubscription | ✅ | ✅ | — |
| GetSubscription | ✅ | ✅ | — |
| UpdateSubscription | ✅ | ❌ | Cannot modify subscription config |
| ListSubscriptions | ✅ | ✅ | — |
| DeleteSubscription | ✅ | ✅ | — |
| ModifyAckDeadline | ✅ | ✅ | — |
| Acknowledge | ✅ | ✅ | — |
| Pull | ✅ | ✅ | — |
| StreamingPull | ✅ | ✅ | — |
| ModifyPushConfig | ✅ | ❌ | — |
| Snapshots/Seek | ✅ | ❌ | — |

### Feature Comparison

| Feature | Google | Deltio | Impact for LocalCloud |
|---------|--------|--------|-----------------------|
| **gRPC API** | ✅ | ✅ | Compatible |
| **REST (HTTP/JSON) API** | ✅ | **❌** | **BLOCKING**: LocalCloud admin console and seed loading use REST |
| **Persistence** | None (same) | None (same) | Both lose data on restart |
| **Schemas** | ✅ | ❌ | Low impact |
| **Message ordering** | ✅ | ❌ (silently ignores) | Low for testing |
| **Exactly-once delivery** | ✅ | ❌ | Low for testing |
| **Push subscriptions** | ✅ | ✅ | Compatible |
| **Dead letter queues** | ✅ | ✅ | Compatible |
| **Retry policy** | ✅ | ✅ | Compatible |
| **Health check endpoint** | ❌ | ❌ | Both lack this |
| **Project isolation** | ✅ | ✅ (from resource names) | Compatible |

---

## 3. Critical Issues for LocalCloud

### 3.1 No REST API — BLOCKING

Deltio serves **gRPC only**. LocalCloud's admin operations rely on REST:

- `POST /_localcloud/seed` — sends seed data via YAML to the gateway, which proxies REST to emulators
- `GET /_localcloud/browse/pubsub` — admin console browsing via REST
- `POST /_localcloud/mutate/pubsub/...` — admin console mutations via REST

The gateway (`SeedService.java`, `BrowseService.java`, `MutateService.java`) hardcodes `"http://localhost:8085"` as the Pub/Sub base URL and uses JSON REST calls. Switching to Deltio would require either:

1. Adding a REST-to-gRPC transcoding layer (Envoy/grpc-gateway or custom Armeria transcoding)
2. Rewriting all admin-endpoints to use gRPC Java stubs for Pub/Sub

Both are substantial engineering effort.

### 3.2 No Persistence — Neutral (same as current)

Deltio is in-memory only. The current Google emulator is also in-memory only (the `--persistence` flag we tested was a no-op). So this is **not a regression** — both lose data on restart. LocalCloud's auto-seed mechanism already handles this.

### 3.3 Performance

| Metric | Google Emulator (Java) | Deltio (Rust) |
|--------|----------------------|---------------|
| Binary size | ~100MB + JRE (72MB) | ~7.5MB static binary |
| Memory per instance | ~300MB+ JVM heap | ~10-50MB RSS |
| Startup time | 5-15 seconds (JVM warmup) | <100ms |
| Degradation over time | Known issue (CPU/memory leak under load) | Purpose-built to avoid this |
| Throughput | Moderate | High (actor model, zero-copy message fan-out) |

Deltio is substantially more efficient. For LocalCloud's Docker image, switching would save ~100MB+ of image size (remove JDK dependency for Pub/Sub).

### 3.4 Operational Readiness

| Aspect | Google Emulator | Deltio |
|--------|----------------|--------|
| Health endpoint | None | None |
| Metrics | None | None |
| Graceful shutdown | Via SIGTERM | Via SIGTERM |
| Logging | Java stdout | env_logger (configurable) |
| Configuration | CLI flags only | CLI flags only |

Both are equally bare-bones operationally.

### 3.5 Maintenance Risk

| Risk | Google Emulator | Deltio |
|------|----------------|--------|
| Upstream | Google maintains (reliable) | Single maintainer (risky) |
| Bug fixes | Google-internal (opaque) | Open source (transparent) |
| Breaking changes | Rare | Possible (v0.x) |
| Security patches | Via gcloud SDK updates | Manual updates |

---

## 4. The Better Path: Build an In-Process Pub/Sub Emulator

Given the analysis, the recommended approach is:

**Build a lightweight in-process Pub/Sub emulator within LocalCloud's Java gateway server**, similar to how Secret Manager, Cloud Tasks, Logging, and Monitoring are implemented — as PostgreSQL-backed facade services.

### Why this is better than either option:

| Criterion | Google Emulator (current) | Deltio | In-process Java facade |
|-----------|--------------------------|--------|----------------------|
| REST API | ✅ | ❌ | ✅ (native, same as other facades) |
| Persistence | ❌ | ❌ | **✅ PostgreSQL-backed** |
| Performance | Poor | Excellent | Good (no IPC overhead) |
| Image size | ~172MB added | ~7.5MB added | **0MB added** (already in JRE) |
| Maintenance | Google (opaque) | Single person | **In-house** |
| API coverage | Full | Partial | **Core only (topics, subs, publish, pull, ack)** |
| Startup time | 5-15s | <100ms | **<10ms** (in-process) |
| Integration | External JAR | External binary | **In-process, no port management** |
| Complexity | Simple (just run JAR) | Simple (just run binary) | **Moderate (need to implement Pub/Sub logic)** |

### Implementation Scope for In-Process Facade

The Pub/Sub API surface needed (based on current LocalCloud usage):

**Publisher API:**
- `CreateTopic` — store in PostgreSQL `pubsub_topics` table
- `DeleteTopic` — remove topic + subscriptions
- `GetTopic` / `ListTopics` — read from PostgreSQL
- `Publish` — insert messages into `pubsub_messages` table, notify subscribers

**Subscriber API:**  
- `CreateSubscription` — store in PostgreSQL `pubsub_subscriptions` table  
- `DeleteSubscription` / `GetSubscription` / `ListSubscriptions`
- `Pull` — fetch undelivered messages from PostgreSQL
- `Acknowledge` — mark messages as consumed
- `ModifyAckDeadline` — extend/reduce ack deadline
- `StreamingPull` — server-sent events or long-poll over REST

**Tables:**
```sql
CREATE TABLE pubsub_topics (
    project_id TEXT NOT NULL,
    topic_id TEXT NOT NULL,
    labels JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (project_id, topic_id)
);

CREATE TABLE pubsub_subscriptions (
    project_id TEXT NOT NULL,
    subscription_id TEXT NOT NULL,
    topic_id TEXT NOT NULL,
    ack_deadline_seconds INT DEFAULT 10,
    push_config JSONB,
    labels JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (project_id, subscription_id),
    FOREIGN KEY (project_id, topic_id) REFERENCES pubsub_topics(project_id, topic_id)
);

CREATE TABLE pubsub_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id TEXT NOT NULL,
    topic_id TEXT NOT NULL,
    subscription_id TEXT,
    data BYTEA,
    attributes JSONB,
    message_id TEXT NOT NULL,
    published_at TIMESTAMPTZ DEFAULT NOW(),
    ack_deadline TIMESTAMPTZ,
    delivery_attempt INT DEFAULT 1,
    consumed BOOLEAN DEFAULT FALSE
);
```

**Estimated effort**: 1-2 weeks for a working implementation covering the core paths currently used by LocalCloud (seed, browse, mutate).

---

## 5. Conclusion

| Action | Recommended? | Rationale |
|--------|-------------|-----------|
| **Replace with Deltio** | ❌ | No REST API is a blocker. Requires transcoding layer, adds single-maintainer dependency risk |
| **Replace with in-process facade** | **✅** | Best fit: PostgreSQL persistence, native REST API, zero additional image size, in-house control |
| **Keep Google emulator** | ⏸️ Acceptable short-term | Works today. Auto-seed compensates for no persistence. Only change if performance or resource usage becomes a problem |

**Immediate next steps if building in-process facade:**
1. Create `pubsub_topics`, `pubsub_subscriptions`, `pubsub_messages` tables
2. Implement gRPC `Publisher` and `Subscriber` service stubs
3. Implement REST handlers for seed/browse/mutate (reuse existing pattern from Secret Manager facade)
4. Add to supervisord: start the Java gateway with Pub/Sub facade enabled
5. Remove the external Pub/Sub emulator JAR from the Docker build
