# Feature Specification: Memorystore (Redis/Valkey) Emulator

**Feature Branch**: `002-memorystore-emulator`
**Created**: 2026-04-07
**Status**: Draft
**Input**: User description: "Add emulator for Memorystore (Redis/ValKey) on top of PostgreSQL with RESP protocol support and optimized key-value storage."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Connect Redis Client to LocalCloud (Priority: P1)

A developer starts LocalCloud and connects their application's Redis client (any language) to `localhost:6379`. The application uses standard Redis commands (GET, SET, DEL, EXPIRE) through official Redis client libraries. No code changes are needed beyond setting `REDIS_HOST=localhost:6379`. The developer's caching, session management, and key-value storage code works identically to how it would against Google Memorystore for Redis.

**Why this priority**: Without basic Redis protocol (RESP) compatibility and core key-value operations, the emulator has no value. This is the fundamental MVP — a developer must be able to connect any Redis client and run basic commands.

**Independent Test**: Can be fully tested by connecting `redis-cli` to `localhost:6379`, running `SET mykey hello`, `GET mykey`, `DEL mykey`, and verifying correct responses. Delivers basic caching and key-value storage capability.

**Acceptance Scenarios**:

1. **Given** LocalCloud is running with Memorystore enabled, **When** a developer connects `redis-cli` to `localhost:6379`, **Then** the connection succeeds and `PING` returns `PONG`
2. **Given** a connected Redis client, **When** the developer runs `SET name "Jay"` followed by `GET name`, **Then** the response is `"Jay"`
3. **Given** a connected Redis client, **When** the developer runs `SET temp "data" EX 5` and waits 6 seconds, **Then** `GET temp` returns `nil` (key expired)
4. **Given** a connected Redis client, **When** the developer runs `DEL name` followed by `EXISTS name`, **Then** the response is `0`
5. **Given** a connected Redis client, **When** the developer runs an unsupported command, **Then** the response is a clear error message: `ERR unknown command '<command>'`

---

### User Story 2 - Use Redis Data Structures Locally (Priority: P1)

A developer uses Redis data structures (hashes, lists, sets, sorted sets) through their application code. These structures are commonly used for caching user profiles (hashes), task queues (lists), tag tracking (sets), and leaderboards (sorted sets). All data persists across container restarts.

**Why this priority**: Data structures beyond strings represent the second most common Redis use case. Without them, developers using Memorystore for anything beyond simple caching cannot use LocalCloud.

**Independent Test**: Can be tested by running hash, list, set, and sorted set commands via `redis-cli` and verifying all operations return correct results.

**Acceptance Scenarios**:

1. **Given** a connected Redis client, **When** the developer runs `HSET user:1 name "Jay" email "jay@paypal.com"` followed by `HGETALL user:1`, **Then** the response contains both field-value pairs
2. **Given** a connected Redis client, **When** the developer runs `LPUSH queue task1 task2 task3` followed by `LRANGE queue 0 -1`, **Then** all three items are returned in correct order (task3 first)
3. **Given** a connected Redis client, **When** the developer runs `SADD tags redis valkey memcached` and then `SISMEMBER tags redis`, **Then** the response is `1`
4. **Given** a connected Redis client, **When** the developer runs `ZADD leaderboard 100 player1 200 player2` and then `ZRANGE leaderboard 0 -1 WITHSCORES`, **Then** players are returned sorted by score ascending

---

### User Story 3 - Seed and Browse Memorystore Data (Priority: P2)

A developer includes Memorystore data in their `seed.yaml` file to pre-populate keys on startup. They can also browse stored keys through the LocalCloud web console's Data Browser, viewing key names, types, TTLs, and values.

**Why this priority**: Seed data support and visual browsing are essential for developer experience but not required for core Redis compatibility.

**Independent Test**: Can be tested by adding a `memorystore` section to `seed.yaml`, running `localcloud seed seed.yaml`, and verifying keys exist via `redis-cli KEYS *`.

**Acceptance Scenarios**:

1. **Given** a seed.yaml file with a `memorystore` section containing key-value pairs, **When** the developer loads the seed file, **Then** all keys are available via Redis client
2. **Given** Memorystore has stored keys, **When** the developer opens the Data Browser and selects "Memorystore", **Then** they see a list of keys with their types and TTLs
3. **Given** a key exists in Memorystore, **When** the developer clicks on it in the Data Browser, **Then** the value is displayed appropriately (strings as text, hashes as key-value table, lists as ordered items, sets as unordered items)

---

### User Story 4 - Monitor Memorystore Usage and Estimated Costs (Priority: P3)

A developer views Memorystore usage metrics in the LocalCloud console's Usage page, including total keys stored, command counts, and estimated GCP Memorystore costs. The emulator appears in the dashboard, services page, and all admin endpoints.

**Why this priority**: Usage metrics and cost estimation enhance developer experience but don't affect core functionality.

**Independent Test**: Can be tested by performing Memorystore operations, navigating to the Usage page, and verifying Memorystore appears with request counts and cost estimates.

**Acceptance Scenarios**:

1. **Given** the developer has performed Memorystore operations, **When** they view the Usage page, **Then** Memorystore shows request count and estimated GCP cost
2. **Given** Memorystore is enabled, **When** the developer views the dashboard, **Then** it appears as a service card with healthy status and port 6379

---

### Edge Cases

- What happens when a client sends a command with wrong number of arguments? System returns: `ERR wrong number of arguments for '<command>' command`
- What happens when a client uses SELECT to switch to a database number > 15? System returns: `ERR DB index is out of range`
- What happens when INCR is used on a non-integer value? System returns: `ERR value is not an integer or out of range`
- What happens when a data type operation is used on the wrong type? System returns: `WRONGTYPE Operation against a key holding the wrong kind of value`
- What happens when a key with TTL is accessed after expiration? System returns `nil` and the key is cleaned up
- What happens when DEL is called on a non-existent key? System returns `0` (number of keys deleted)
- What happens when SET is called with both NX and XX options? System returns an error per Redis specification

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST accept TCP connections on a configurable port (default 6379) using the RESP2 (Redis Serialization Protocol v2) wire format
- **FR-002**: System MUST support Redis string commands: GET, SET (with EX/PX/NX/XX options), MGET, MSET, INCR, DECR, INCRBY, DECRBY, APPEND, STRLEN, SETNX, GETSET
- **FR-003**: System MUST support Redis hash commands: HGET, HSET, HMGET, HDEL, HEXISTS, HGETALL, HKEYS, HVALS, HLEN, HINCRBY
- **FR-004**: System MUST support Redis list commands: LPUSH, RPUSH, LPOP, RPOP, LRANGE, LLEN, LINDEX, LSET, LTRIM
- **FR-005**: System MUST support Redis set commands: SADD, SREM, SMEMBERS, SCARD, SISMEMBER, SINTER, SUNION, SDIFF
- **FR-006**: System MUST support Redis sorted set commands: ZADD, ZREM, ZRANGE (with WITHSCORES), ZRANGEBYSCORE, ZCARD, ZSCORE, ZRANK, ZCOUNT
- **FR-007**: System MUST support key management commands: DEL, EXISTS, TYPE, KEYS (with glob patterns), EXPIRE, TTL, PEXPIRE, PTTL, PERSIST, RENAME
- **FR-008**: System MUST support connection/server commands: PING, ECHO, SELECT, QUIT, FLUSHDB, FLUSHALL, DBSIZE, INFO, COMMAND
- **FR-009**: System MUST persist all data so it survives container restarts when using volume mounts
- **FR-010**: System MUST support key expiration (TTL) with automatic cleanup of expired keys
- **FR-011**: System MUST support 16 logical databases (SELECT 0 through SELECT 15) per Redis specification
- **FR-012**: System MUST return Redis-compatible error responses for unsupported commands, wrong argument counts, and type mismatches
- **FR-013**: System MUST be registered in the service registry with port 6379, env var `REDIS_HOST`, and protocol type `redis`
- **FR-014**: System MUST support Memorystore seed data loading via the existing seed file format
- **FR-015**: System MUST be browsable in the web console Data Browser showing keys, types, values, and TTLs
- **FR-016**: System MUST appear in the Usage & Cost Estimates page with request counts and estimated GCP Memorystore pricing ($0.049/GB-hour for Basic tier)

### Key Entities

- **RedisKey**: Represents a key in the key-value store. Attributes: database number (0-15), key name (binary-safe string), data type (string/hash/list/set/sorted_set), expiration timestamp (optional), creation time
- **StringValue**: The value associated with a string-type key. Stored as binary-safe bytes supporting strings up to 512MB
- **HashField**: A field-value pair within a hash-type key. Attributes: field name (string), value (binary-safe bytes)
- **ListElement**: An element in a list-type key. Attributes: index position (supports negative indexing), value (binary-safe bytes)
- **SetMember**: A member of a set-type key. Attributes: member value (unique within the set, binary-safe)
- **SortedSetMember**: A member of a sorted set-type key. Attributes: member value (unique), score (floating-point number for ordering)

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Standard Redis client libraries (Python redis-py, Node ioredis, Java Jedis/Lettuce, Go go-redis) connect and perform basic operations with zero code changes beyond setting the host/port
- **SC-002**: The emulator supports at least 50 Redis commands covering strings, hashes, lists, sets, sorted sets, keys, and connection management (~85% of common developer use cases)
- **SC-003**: All stored data persists across container restarts when using volume mounts
- **SC-004**: Key expiration (TTL) works correctly — expired keys return nil and are cleaned up within 10 seconds of expiration
- **SC-005**: The emulator handles 100 concurrent Redis client connections without errors
- **SC-006**: Developers can run their first successful Redis command within 2 minutes of starting LocalCloud
- **SC-007**: Memorystore appears correctly in the web console dashboard, services page, data browser, and usage metrics

## Assumptions

- Single-node Redis emulation is sufficient (no Redis Cluster, Sentinel, or replication support)
- RESP2 protocol is sufficient for local development (RESP3 support deferred)
- Lua scripting (EVAL/EVALSHA) is deferred to a future iteration
- Redis Pub/Sub is deferred — LocalCloud already has a dedicated Pub/Sub emulator via Google's official emulator
- Redis Streams data type is deferred to a future iteration
- Transaction support (MULTI/EXEC/WATCH) is deferred to a future iteration
- ACL/password authentication is handled by LocalCloud's IAM middleware, not Redis AUTH command
- Performance priorities: correctness and persistence over raw speed — latency will be higher than real Redis but acceptable for local development (sub-10ms for simple operations is the target)
