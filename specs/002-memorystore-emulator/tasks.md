# Tasks: Memorystore (Redis/Valkey) Emulator

**Input**: Design documents from `/specs/002-memorystore-emulator/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/memorystore-commands.md

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to

---

## Phase 1: Setup

**Purpose**: Add dependency and configuration for Memorystore service

- [x] T001 Add `io.netty:netty-codec-redis` dependency to `localcloud-server/build.gradle`
- [x] T002 Add `redis_data` table to `localcloud-server/src/main/java/com/localcloud/persistence/SchemaManager.java`
- [x] T003 Add memorystore service entry to `services.yaml` (port 6379, protocol redis, type facade, defaultEnabled true)
- [x] T004 [P] Add memorystore case to `docker-entrypoint.sh`
- [x] T005 [P] Add `EXPOSE 6379` and `LOCALCLOUD_ENABLE_MEMORYSTORE` to `Dockerfile`
- [x] T006 [P] Add port 6379 mapping to `docker-compose.yml`

**Checkpoint**: Configuration complete. `./gradlew compileJava` succeeds with new dependency.

---

## Phase 2: Foundational (Blocking)

**Purpose**: Core persistence and server infrastructure. MUST complete before commands.

- [x] T007 Create `MemorystoreStore.java` in `localcloud-server/src/main/java/com/localcloud/emulators/memorystore/` — PostgreSQL JSONB persistence: getString/setString, getHash/setHashField, getList/pushList/popList, getSet/addSetMember, getSortedSet/addSortedSetMember, deleteKey, setTtl, getType, keyExists, flushDb, dbSize, keys(pattern)
- [x] T008 Create `MemorystoreEmulator.java` in `localcloud-server/src/main/java/com/localcloud/emulators/memorystore/` — extends AbstractEmulator, Netty ServerBootstrap on port 6379, pipeline: RedisDecoder → BulkStringAggregator → ArrayAggregator → RedisCommandHandler → RedisEncoder. doStart() binds port, doStop() shuts down, doReset() calls FLUSHALL
- [x] T009 Register MemorystoreEmulator in `localcloud-server/src/main/java/com/localcloud/LocalCloudApplication.java` — create if `config.isServiceEnabled("memorystore")`, start, bind Netty server

**Checkpoint**: `redis-cli -p 6379 PING` returns PONG (connection works, no commands yet)

---

## Phase 3: User Story 1 — Connect Redis Client (Priority: P1) MVP

**Goal**: Developers connect any Redis client and run string + key commands

**Independent Test**: `redis-cli SET mykey hello && redis-cli GET mykey` returns `"hello"`

### Implementation for User Story 1

- [x] T010 [US1] Implement server commands in `RedisCommandHandler.java`: PING, ECHO, QUIT, SELECT (db switch), INFO (stub), DBSIZE, FLUSHDB, FLUSHALL — in `localcloud-server/src/main/java/com/localcloud/emulators/memorystore/RedisCommandHandler.java`
- [x] T011 [US1] Implement string commands in `RedisCommandHandler.java`: GET, SET (with EX/PX/NX/XX flags), SETNX, SETEX, MGET, MSET, INCR, INCRBY, INCRBYFLOAT, DECR, DECRBY, APPEND, STRLEN
- [x] T012 [US1] Implement key commands in `RedisCommandHandler.java`: DEL, EXISTS, EXPIRE, EXPIREAT, PEXPIRE, TTL, PTTL, PERSIST, TYPE, RENAME, KEYS (glob pattern matching)
- [x] T013 [US1] Implement TTL lazy expiry in `MemorystoreStore.java`: all read queries add `WHERE ttl_expires_at IS NULL OR ttl_expires_at > NOW()`
- [x] T014 [US1] Implement unsupported command fallback: return `ERR unknown command '<cmd>'` for any unrecognized command

**Checkpoint**: US1 complete. `redis-cli` can SET/GET/DEL/EXPIRE keys. `./gradlew build` passes.

---

## Phase 4: User Story 2 — Redis Data Structures (Priority: P1)

**Goal**: Developers use hashes, lists, sets, sorted sets through Redis clients

**Independent Test**: `redis-cli HSET user:1 name Jay && redis-cli HGETALL user:1` returns field-value pairs

### Implementation for User Story 2

- [x] T015 [US2] Implement hash commands in `RedisCommandHandler.java`: HGET, HSET, HMGET, HDEL, HEXISTS, HGETALL, HKEYS, HVALS, HLEN, HINCRBY — using JSONB object operations in `MemorystoreStore.java`
- [x] T016 [US2] Implement list commands in `RedisCommandHandler.java`: LPUSH, RPUSH, LPOP, RPOP, LRANGE, LLEN, LINDEX, LSET, LTRIM — using JSONB array operations in `MemorystoreStore.java`
- [x] T017 [P] [US2] Implement set commands in `RedisCommandHandler.java`: SADD, SREM, SMEMBERS, SCARD, SISMEMBER, SINTER, SUNION, SDIFF — using JSONB array with uniqueness checks in `MemorystoreStore.java`
- [x] T018 [P] [US2] Implement sorted set commands in `RedisCommandHandler.java`: ZADD, ZREM, ZRANGE (WITHSCORES), ZRANGEBYSCORE, ZCARD, ZSCORE, ZRANK, ZCOUNT — using JSONB array of {m,s} objects in `MemorystoreStore.java`
- [x] T019 [US2] Implement WRONGTYPE error enforcement: return `WRONGTYPE Operation against a key holding the wrong kind of value` when a command is used on the wrong data type

**Checkpoint**: US2 complete. All 5 data types work. `./gradlew build` passes.

---

## Phase 5: User Story 3 — Seed and Browse (Priority: P2)

**Goal**: Developers seed Memorystore data from YAML and browse keys in the web console

**Independent Test**: Add `memorystore` section to seed.yaml, POST to `/_localcloud/seed`, verify keys via `redis-cli KEYS *`

### Implementation for User Story 3

- [x] T020 [US3] Add memorystore seed support in `localcloud-server/src/main/java/com/localcloud/admin/SeedService.java` — parse `memorystore.keys` and `memorystore.hashes` from seed YAML, insert into redis_data table
- [x] T021 [US3] Add memorystore browse endpoint in `localcloud-server/src/main/java/com/localcloud/admin/BrowseService.java` — query redis_data table, return keys with types, values, and TTLs
- [x] T022 [P] [US3] Add `/api/browse/memorystore` route in `localcloud-console/backend/app.py` and proxy method in `localcloud-console/backend/proxy.py`
- [x] T023 [P] [US3] Add seed section `memorystore` to `localcloud-cli/src/localcloud/seed_processor.py` SUPPORTED_SECTIONS

**Checkpoint**: US3 complete. Seed loads data, console shows keys.

---

## Phase 6: User Story 4 — Usage Metrics (Priority: P3)

**Goal**: Memorystore appears in dashboard, services, and usage pages with cost estimates

### Implementation for User Story 4

- [x] T024 [US4] Add Memorystore GCP pricing to `localcloud-console/src/pages/Usage.jsx` GCP_PRICING map: `memorystore: { label: 'Memorystore', unit: 'per GB-hour', price: 0.049 }`

**Checkpoint**: US4 complete. Memorystore shows in all console pages.

---

## Phase 7: Polish

- [x] T025 Create `localcloud-server/src/test/java/com/localcloud/emulators/memorystore/MemorystoreTest.java` — unit tests for MemorystoreStore CRUD, command dispatch, TTL expiry, WRONGTYPE errors
- [x] T026 Update `DEVELOPER_GUIDE.md` and `README.md` — add Memorystore to service tables, port map, env var reference
- [x] T027 Build Docker image and verify end-to-end: `docker compose build && docker compose up -d && redis-cli -p 6379 PING`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies
- **Phase 2 (Foundational)**: Depends on Phase 1
- **Phase 3 (US1)**: Depends on Phase 2 — this is the MVP
- **Phase 4 (US2)**: Depends on Phase 2 (can run parallel with US1 since all in same files, but logically after)
- **Phase 5 (US3)**: Depends on Phase 2 (seed/browse are independent of command implementation)
- **Phase 6 (US4)**: No code dependencies (just console config)
- **Phase 7 (Polish)**: After all stories

### Parallel Opportunities

- T004, T005, T006 can run in parallel (different config files)
- T017, T018 can run in parallel (set + sorted set are independent operations)
- T022, T023 can run in parallel (console backend + CLI are independent)
- US3 and US4 can run in parallel with US1/US2

---

## Implementation Strategy

### MVP (US1 only): 14 tasks (T001-T014)

1. Setup (T001-T006) — config and dependency
2. Foundation (T007-T009) — store + server + registration
3. US1 (T010-T014) — string/key commands + TTL
4. **STOP**: Test with `redis-cli`. This is a shippable MVP.

### Full delivery: 27 tasks total

Add US2 (T015-T019), US3 (T020-T023), US4 (T024), Polish (T025-T027).
