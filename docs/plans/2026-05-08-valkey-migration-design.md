# Valkey Migration — Replace Custom Memorystore Emulator

**Date:** 2026-05-08
**Status:** Approved

## Summary

Replace the custom Java Redis emulator (~2,700 LOC) with Valkey — an open-source Redis fork. Valkey runs as a supervisord process inside the container with AOF persistence on the mounted data volume. Console Data Browser queries Valkey directly via Jedis client. Dev-optimized config — no auth, no clustering, no replication, no TLS.

## Motivation

- **Command coverage:** Custom emulator has 52 commands. Valkey has 400+. Streams, Transactions, Blocking ops, Bitmaps all missing today.
- **Compatibility:** Real clients hit edge cases with custom RESP2 implementation. Valkey is wire-perfect.
- **Maintenance:** 2,700 LOC of hand-rolled Redis logic to maintain. Valkey eliminates this.

## Architecture

```
Before:                              After:

Client (port 6379)                   Client (port 6379)
    |                                    |
    v                                    v
Netty RESP2 Server (Java)            Valkey Server (C binary)
    |                                    |
    v                                    v
PostgreSQL redis_data table           AOF on mounted volume
                                      /var/lib/localcloud/redis-data/

Console Data Browser                 Console Data Browser
    |                                    |
    v                                    v
PostgreSQL SELECT                    Jedis client -> Valkey
                                     SCAN + TYPE + GET/HGETALL/...
```

## Components

### 1. Dockerfile — Valkey Binary

Use official Valkey Docker image as build stage. Copy binaries into final image.

```dockerfile
FROM valkey/valkey:8.1-alpine AS valkey-build

# In final stage:
COPY --from=valkey-build /usr/local/bin/valkey-server /usr/local/bin/valkey-server
COPY --from=valkey-build /usr/local/bin/valkey-cli /usr/local/bin/valkey-cli
```

Both arm64 and amd64 supported (Valkey official images are multi-arch).

### 2. valkey.conf — Dev-Optimized

No production overhead. Minimal config for local development:

```conf
# Network
bind 0.0.0.0
port 6379
protected-mode no
tcp-backlog 128

# Persistence — AOF only, no RDB snapshots
appendonly yes
appendfilename "appendonly.aof"
appendfsync everysec
auto-aof-rewrite-percentage 100
auto-aof-rewrite-min-size 64mb
save ""

# Storage
dir /var/lib/localcloud/redis-data
databases 16

# Memory — bounded for container coexistence
maxmemory 512mb
maxmemory-policy noeviction

# Dev settings — no overhead
loglevel notice
logfile ""
slowlog-log-slower-than -1
latency-monitor-threshold 0
hz 10

# No auth, no TLS, no clustering
# (dev environment only)
```

### 3. supervisord.conf

```ini
[program:valkey]
command=/usr/local/bin/valkey-server /etc/valkey.conf
autostart=%(ENV_LOCALCLOUD_ENABLE_MEMORYSTORE)s
autorestart=true
priority=15
stopsignal=TERM
stopwaitsecs=10
user=localcloud
stdout_logfile=/var/lib/localcloud/logs/valkey-stdout.log
stderr_logfile=/var/lib/localcloud/logs/valkey-stderr.log
stdout_logfile_maxbytes=10MB
stderr_logfile_maxbytes=10MB
```

### 4. services.yaml

```yaml
memorystore:
  displayName: "Memorystore (Redis)"
  port: 6379
  protocol: redis
  envVar: REDIS_HOST
  envValuePrefix: ""
  type: external          # Changed from 'facade' — now a supervisord process
  defaultEnabled: true
  terraformEnvVar: GOOGLE_REDIS_CUSTOM_ENDPOINT
```

### 5. BrowseService — Direct Valkey Queries

Add Jedis dependency to build.gradle. New browse code path:

```java
// Browse keys:
//   SCAN cursor MATCH * COUNT 100
//   For each key: TYPE key
//   String  -> GET key
//   Hash    -> HGETALL key
//   List    -> LRANGE key 0 -1
//   Set     -> SMEMBERS key
//   ZSet    -> ZRANGE key 0 -1 WITHSCORES
//   Stream  -> XRANGE key - + COUNT 100
//   TTL     -> TTL key
//
// Return same JSON shape as current browse response
```

### 6. SeedService — Redis Commands

Convert seed YAML into Valkey commands via Jedis pipeline:

```yaml
memorystore:
  - key: "user:1"
    type: string
    value: "John"
    ttl: 3600
```

Maps to:
- string -> `SET key value [EX ttl]`
- hash -> `HSET key field1 val1 field2 val2 ...`
- list -> `RPUSH key val1 val2 ...`
- set -> `SADD key member1 member2 ...`
- zset -> `ZADD key score1 member1 score2 member2 ...`

### 7. Code Deletion

**Delete:**
- `MemorystoreEmulator.java` — Netty TCP server bootstrap
- `RedisCommandHandler.java` — RESP command dispatch (1,169 LOC)
- `MemorystoreStore.java` — PostgreSQL JSONB operations (1,058 LOC)
- `MemorystoreStoreTest.java` — Unit tests for above

**Keep:**
- `PubSubManager.java` — shared by other services
- `LuaScriptEngine.java` — potentially reusable
- All other datastore/shared code

### 8. PostgreSQL Schema

Drop `redis_data` table from schema initialization. No migration needed — table simply not created on fresh containers.

### 9. Docker Entrypoint

Ensure data directory exists:

```bash
mkdir -p /var/lib/localcloud/redis-data
chown localcloud:localcloud /var/lib/localcloud/redis-data
```

## What You Gain

| Metric | Before (Custom) | After (Valkey) |
|--------|-----------------|----------------|
| Commands | 52 | 400+ |
| Wire compatibility | RESP2, partial | RESP2+RESP3, complete |
| Streams | No | Yes |
| Transactions | No | Yes (MULTI/EXEC/WATCH) |
| Blocking ops | No | Yes (BLPOP/BRPOP/BLMOVE) |
| Bitmaps | No | Yes |
| Cluster protocol | No | Available (disabled) |
| Java code | ~2,700 LOC | ~200 LOC (browse+seed) |
| Persistence | PostgreSQL JSONB | AOF (native, fast) |
| Performance | DB round-trip per op | In-memory, sub-ms |

## Non-Goals

- Production hardening (auth, TLS, replication)
- Redis Cluster mode
- Valkey modules
- Performance benchmarking
- Migration tooling for existing redis_data PostgreSQL data

## Dependencies

- `valkey/valkey:8.1-alpine` Docker image (build stage)
- `redis.clients:jedis` Java dependency (browse + seed)
