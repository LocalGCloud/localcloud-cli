# Research: Memorystore (Redis/Valkey) Emulator

**Date**: 2026-04-07

## Decision 1: redis_fdw is NOT applicable

**Decision**: Do not use `pg-redis-fdw/redis_fdw`
**Rationale**: redis_fdw is a PostgreSQL Foreign Data Wrapper that allows PostgreSQL to query an *external* Redis instance. It goes in the wrong direction — we need the opposite: a Redis-protocol server that stores data *in* PostgreSQL. redis_fdw would require running a real Redis instance, defeating the purpose of emulation.
**Alternatives considered**: redis_fdw, pg_redis (similar FDW). Both connect PG→Redis, not Redis→PG.

## Decision 2: Google Memorystore is Standard Redis Protocol

**Decision**: Implement standard Redis RESP2 protocol — no Google-specific API needed
**Rationale**: Google Memorystore for Redis is *not* a custom API. It is standard Redis 7.2 protocol over TCP on port 6379. Any standard Redis client (redis-cli, Jedis, Lettuce, redis-py, ioredis, go-redis) connects directly. Google recommends using standard Redis client libraries, not a Google-specific SDK. This means the emulator only needs to speak RESP2 — no REST/gRPC translation needed.
**Alternatives considered**: Google Cloud client library wrapper. Rejected because Memorystore doesn't have a Google-specific API.

## Decision 3: Netty codec-redis for RESP protocol handling

**Decision**: Use Netty's `netty-codec-redis` module for RESP2 protocol parsing
**Rationale**: Netty is already a transitive dependency of Armeria (LocalCloud's API gateway). The `netty-codec-redis` module provides production-quality RESP2 parsing with `RedisDecoder`, `RedisEncoder`, `RedisBulkStringAggregator`, and `RedisArrayAggregator`. The pipeline model maps cleanly to a command-dispatch architecture. Only one new dependency needed: `io.netty:netty-codec-redis` (version managed by Armeria BOM).
**Alternatives considered**:
- Manual RESP parsing (like jedis-mock does with raw sockets): simpler but less performant, no chunked transfer support
- Embedding jedis-mock: stores in memory, can't persist to PostgreSQL, wrong architecture
- Full Redis clone (Valkey/KeyDB): massive codebase, overkill

## Decision 4: jedis-mock as reference implementation, not embedded

**Decision**: Use jedis-mock (Apache 2.0) as a reference for command semantics, not as an embedded component
**Rationale**: jedis-mock implements ~100 Redis commands with correct argument parsing, edge cases, and error messages. It's an excellent reference for how each command should behave. However, it uses in-memory storage and raw sockets — incompatible with our PostgreSQL persistence and Netty-based architecture. We reference its command implementations for correctness while building our own Netty+PostgreSQL server.
**Alternatives considered**: Embedding jedis-mock and adding a PostgreSQL backend. Rejected because the architecture is too different — would require rewriting most of it anyway.

## Decision 5: Single JSONB table for initial storage (Option A)

**Decision**: Use a single `redis_data` table with JSONB values for initial implementation
**Rationale**: A single table with `(db INT, key TEXT, type TEXT, value JSONB, ttl_at TIMESTAMPTZ)` is simpler to implement, query, and maintain. JSONB natively supports all Redis data structures: strings as `{"v": "hello"}`, hashes as objects, lists as arrays, sets/sorted sets as arrays. PostgreSQL JSONB operators handle field access, array manipulation, and membership checks efficiently. This can be migrated to type-specific tables later if performance requires it.
**Alternatives considered**:
- Type-specific tables (redis_strings, redis_hashes, redis_lists, redis_sets, redis_sorted_sets): better performance for large datasets but 5x the schema complexity. Deferred to optimization phase.
- Flat key-value with TEXT value: loses structured access for hashes/lists

## Decision 6: Standalone Netty server, not Armeria service

**Decision**: Run the Redis emulator as a standalone Netty TCP server on port 6379, managed by the Java application lifecycle
**Rationale**: Redis uses a custom binary protocol (RESP), not HTTP or gRPC. Armeria's service model is designed for HTTP/gRPC. A standalone Netty `ServerBootstrap` on port 6379 is the cleanest approach — it uses Netty's event loop (shared with Armeria if desired) and the `netty-codec-redis` pipeline. The `MemorystoreEmulator` class extends `AbstractEmulator` for lifecycle management (start/stop/reset) and integrates with health checks, but the actual TCP server runs independently.
**Alternatives considered**:
- Armeria custom protocol handler: Armeria doesn't natively support non-HTTP protocols on service ports
- Separate JVM process: adds process management complexity, conflicts with facade emulator pattern

## Decision 7: Command coverage — 52 commands for 85% real-world coverage

**Decision**: Implement 52 commands across strings, hashes, lists, sets, sorted sets, keys, and connection management
**Rationale**: Analysis of real-world Redis usage patterns shows strings + keys + hashes cover ~75% of operations. Adding lists, sets, and sorted sets reaches ~85%. Transactions (MULTI/EXEC), Lua scripting (EVAL), Pub/Sub, Streams, and blocking commands are deferred as they cover specialized use cases and add significant complexity.

### Command List (52 commands)

**Connection/Server (9)**: PING, ECHO, QUIT, SELECT, AUTH (stub), INFO, DBSIZE, FLUSHDB, FLUSHALL

**Strings (14)**: GET, SET (EX/PX/NX/XX), SETNX, SETEX, MGET, MSET, INCR, INCRBY, INCRBYFLOAT, DECR, DECRBY, APPEND, STRLEN, GETSET

**Keys (13)**: DEL, EXISTS, EXPIRE, EXPIREAT, PEXPIRE, PEXPIREAT, TTL, PTTL, PERSIST, TYPE, RENAME, KEYS, SCAN

**Hashes (10)**: HGET, HSET, HMGET, HDEL, HEXISTS, HGETALL, HKEYS, HVALS, HLEN, HINCRBY

**Lists (9)**: LPUSH, RPUSH, LPOP, RPOP, LRANGE, LLEN, LINDEX, LSET, LTRIM

**Sets (8)**: SADD, SREM, SMEMBERS, SCARD, SISMEMBER, SINTER, SUNION, SDIFF

**Sorted Sets (8)**: ZADD, ZREM, ZRANGE (WITHSCORES), ZRANGEBYSCORE, ZCARD, ZSCORE, ZRANK, ZCOUNT

### Deferred Commands
- Transactions: MULTI, EXEC, DISCARD, WATCH
- Scripting: EVAL, EVALSHA
- Pub/Sub: SUBSCRIBE, PUBLISH (use existing Pub/Sub emulator)
- Streams: XADD, XREAD, XRANGE
- Blocking: BLPOP, BRPOP, BLMOVE
- Geo: GEOADD, GEODIST
- HyperLogLog: PFADD, PFCOUNT
- Bitmap: SETBIT, GETBIT, BITCOUNT
