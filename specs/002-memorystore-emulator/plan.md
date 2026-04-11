# Implementation Plan: Memorystore (Redis/Valkey) Emulator

**Branch**: `002-memorystore-emulator` | **Date**: 2026-04-07 | **Spec**: [spec.md](spec.md)

## Summary

Add Memorystore for Redis/Valkey as the 14th emulated GCP service. A lightweight RESP2 server on port 6379, backed by PostgreSQL JSONB. Standard Redis clients work with zero code changes.

Google Memorystore IS standard Redis — no Google-specific API. We just speak RESP2.

## Technical Context

**Language**: Java 21, Python 3.11+ (CLI/console)
**New Dependency**: `io.netty:netty-codec-redis` (1 dependency, version from Armeria BOM)
**Storage**: PostgreSQL — single `redis_data` table with JSONB
**Scope**: 50 commands, 5 data types, 16 databases

## Constitution Check

All 5 principles pass. No violations.

## Source Files (4 new Java + config changes)

```text
localcloud-server/src/main/java/com/localcloud/emulators/memorystore/
├── MemorystoreEmulator.java       # AbstractEmulator + Netty ServerBootstrap (lifecycle + TCP server)
├── MemorystoreStore.java          # PostgreSQL JSONB persistence (all data types)
└── RedisCommandHandler.java       # Netty handler: RESP parsing → command dispatch → response
                                   # All 50 commands implemented as methods in this one class

localcloud-server/src/test/java/com/localcloud/emulators/memorystore/
└── MemorystoreTest.java           # All command tests in one file
```

**Config changes** (existing files):
- `build.gradle` — add `netty-codec-redis` dependency
- `SchemaManager.java` — add `redis_data` table
- `LocalCloudApplication.java` — register emulator
- `services.yaml` — add memorystore entry
- `Dockerfile` — EXPOSE 6379
- `docker-entrypoint.sh` — add memorystore case
- `docker-compose.yml` — add port 6379
- `DataBrowser.jsx` — add Memorystore tab (already has the tab from console redesign)
- `backend/app.py` — add `/api/browse/memorystore` route

## Architecture

```
Redis Client → TCP 6379 → Netty Pipeline → RedisCommandHandler → MemorystoreStore → PostgreSQL
```

**Netty pipeline** (4 stages, all from `netty-codec-redis`):
```
RedisDecoder → BulkStringAggregator → ArrayAggregator → RedisCommandHandler → RedisEncoder
```

**Threading**: Synchronous. Command handler runs on Netty worker thread, makes blocking JDBC calls via HikariCP. For a local dev emulator at <100 ops/sec, this is fine. Redis itself is single-threaded.

**TTL**: Lazy expiry on read (`WHERE ttl_expires_at IS NULL OR ttl_expires_at > NOW()`). Background cleanup every 5s optional.

## PostgreSQL Schema

```sql
CREATE TABLE redis_data (
    db_number INT NOT NULL DEFAULT 0,
    key_name TEXT NOT NULL,
    data_type VARCHAR(10) NOT NULL,  -- string, hash, list, set, zset
    value JSONB NOT NULL DEFAULT '""',
    ttl_expires_at TIMESTAMPTZ,
    PRIMARY KEY (db_number, key_name)
);
CREATE INDEX idx_redis_ttl ON redis_data (ttl_expires_at) WHERE ttl_expires_at IS NOT NULL;
```

Value encoding: strings as `"hello"`, hashes as `{"f":"v"}`, lists as `["a","b"]`, sets as `["a","b"]`, sorted sets as `[{"m":"a","s":1.0}]`.

## Command Set (50 commands)

| Category | Count | Commands |
|----------|-------|----------|
| Server | 7 | PING, ECHO, QUIT, SELECT, INFO, DBSIZE, FLUSHDB, FLUSHALL |
| Strings | 13 | GET, SET (EX/PX/NX/XX), SETNX, SETEX, MGET, MSET, INCR, INCRBY, INCRBYFLOAT, DECR, DECRBY, APPEND, STRLEN |
| Keys | 11 | DEL, EXISTS, EXPIRE, EXPIREAT, TTL, PTTL, PERSIST, TYPE, RENAME, KEYS, PEXPIRE |
| Hashes | 10 | HGET, HSET, HMGET, HDEL, HEXISTS, HGETALL, HKEYS, HVALS, HLEN, HINCRBY |
| Lists | 9 | LPUSH, RPUSH, LPOP, RPOP, LRANGE, LLEN, LINDEX, LSET, LTRIM |
| Sets | 8 | SADD, SREM, SMEMBERS, SCARD, SISMEMBER, SINTER, SUNION, SDIFF |
| Sorted Sets | 8 | ZADD, ZREM, ZRANGE, ZRANGEBYSCORE, ZCARD, ZSCORE, ZRANK, ZCOUNT |

**Deferred**: SCAN, AUTH, MULTI/EXEC, EVAL, Pub/Sub, Streams, blocking ops, Geo, HyperLogLog, Bitmap.

## Phases

### Phase 1: Foundation + MVP (P1)
1. Add `netty-codec-redis` to `build.gradle`
2. Add `redis_data` table to `SchemaManager.java`
3. Add memorystore to `services.yaml`, `Dockerfile`, `docker-entrypoint.sh`, `docker-compose.yml`
4. Create `MemorystoreStore.java` — CRUD for all data types via JSONB SQL
5. Create `MemorystoreEmulator.java` — Netty server + AbstractEmulator lifecycle
6. Create `RedisCommandHandler.java` — all 50 commands as methods
7. Register in `LocalCloudApplication.java`
8. Create `MemorystoreTest.java` — tests for core commands

### Phase 2: Integration (P2-P3)
9. Seed support in `SeedService.java`
10. Browse endpoint in `BrowseService.java`
11. Console Data Browser tab + Usage pricing
12. Documentation updates
