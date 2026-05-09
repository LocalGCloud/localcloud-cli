# Valkey Migration Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace custom Java Redis emulator with Valkey binary running as supervisord process, with AOF persistence and direct Jedis-based browsing/seeding.

**Architecture:** Valkey runs as standalone C binary managed by supervisord on port 6379. AOF persistence to mounted volume. Console Data Browser and seed loading use Jedis client to talk to Valkey directly. PostgreSQL `redis_data` table removed.

**Tech Stack:** Valkey 8.1 (via official Docker image), Jedis 5.x (Java Redis client), supervisord

**Design Doc:** `docs/plans/2026-05-08-valkey-migration-design.md`

---

### Task 1: Add Valkey Binary to Dockerfile

**Files:**
- Modify: `Dockerfile:131-141` (add valkey build stage)
- Modify: `Dockerfile:269-292` (copy valkey binaries)
- Create: `valkey.conf`

**Step 1: Create valkey.conf**

Create `/Users/jsenjaliya/src/my/localcloud/valkey.conf`:

```conf
# Valkey config — dev environment, no production overhead
bind 0.0.0.0
port 6379
protected-mode no
tcp-backlog 128

# AOF persistence only — no RDB snapshots
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
```

**Step 2: Add Valkey build stage to Dockerfile**

Add after the spanner-emulator build stage (around line 141):

```dockerfile
# ── Valkey (Redis-compatible) ──────────────────────────────────────────
FROM valkey/valkey:8.1-alpine AS valkey-build
```

**Step 3: Copy Valkey binaries in final stage**

Add after the spanner binary COPY lines (around line 271):

```dockerfile
# Valkey (Memorystore emulator)
COPY --from=valkey-build /usr/local/bin/valkey-server /usr/local/bin/valkey-server
COPY --from=valkey-build /usr/local/bin/valkey-cli /usr/local/bin/valkey-cli
COPY valkey.conf /etc/valkey.conf
```

**Step 4: Add redis-data directory creation**

Find the existing `mkdir -p /var/lib/localcloud/spanner-data` line and add alongside:

```dockerfile
RUN mkdir -p /var/lib/localcloud/redis-data \
    && chown -R localcloud:localcloud /var/lib/localcloud/redis-data
```

**Step 5: Verify EXPOSE 6379 exists**

Confirm line 361 already has `EXPOSE 6379`. No change needed.

**Step 6: Commit**

```bash
git add Dockerfile valkey.conf
git commit -m "feat: add Valkey binary to Docker image via multi-stage build"
```

---

### Task 2: Add Valkey to Supervisord

**Files:**
- Modify: `supervisord.conf` (add program block after bigquery-emulator, around line 95)

**Step 1: Add valkey program block**

Add after the `[program:bigquery-emulator]` block (after line 95):

```ini
[program:valkey]
command=/usr/local/bin/valkey-server /etc/valkey.conf
autostart=%(ENV_LOCALCLOUD_ENABLE_MEMORYSTORE)s
autorestart=true
priority=15
stopsignal=TERM
stopwaitsecs=10
user=localcloud
stdout_logfile=/var/log/localcloud/valkey-stdout.log
stdout_logfile_maxbytes=10MB
stdout_logfile_backups=3
stderr_logfile=/var/log/localcloud/valkey-stderr.log
stderr_logfile_maxbytes=10MB
stderr_logfile_backups=3
```

**Step 2: Commit**

```bash
git add supervisord.conf
git commit -m "feat: add Valkey as supervisord managed process"
```

---

### Task 3: Update services.yaml

**Files:**
- Modify: `services.yaml:186-194`

**Step 1: Change type from facade to external**

```yaml
memorystore:
  displayName: "Memorystore (Redis/Valkey)"
  port: 6379
  protocol: redis
  envVar: REDIS_HOST
  envValuePrefix: ""
  type: external
  defaultEnabled: true
  terraformEnvVar: GOOGLE_REDIS_CUSTOM_ENDPOINT
```

Only changes: `displayName` adds "(Redis/Valkey)", `type` changes from `facade` to `external`.

**Step 2: Commit**

```bash
git add services.yaml
git commit -m "feat: change memorystore service type to external for Valkey"
```

---

### Task 4: Add Jedis Dependency

**Files:**
- Modify: `localcloud-server/build.gradle:90-91`

**Step 1: Replace netty-codec-redis with Jedis**

Replace line 91:
```groovy
    // Netty Redis codec (aligned with Netty version from Armeria)
    implementation 'io.netty:netty-codec-redis:4.1.115.Final'
```

With:
```groovy
    // Jedis — Redis/Valkey client for browse + seed operations
    implementation 'redis.clients:jedis:5.2.0'
```

**Step 2: Remove RoaringBitmap and DataSketches dependencies**

Remove lines 93-97 (only used by MemorystoreStore):
```groovy
    // RoaringBitmap for efficient set operations (replaces HashSet for large cardinalities)
    implementation 'org.roaringbitmap:RoaringBitmap:1.3.0'

    // Apache DataSketches for HyperLogLog (better accuracy than vanilla)
    implementation 'org.apache.datasketches:datasketches-java:6.2.0'
```

These are only used by MemorystoreStore.java which will be deleted. Valkey handles HLL and sets natively.

**Step 3: Verify build compiles**

Run: `cd localcloud-server && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL (will have errors from deleted imports — fix in next tasks)

**Step 4: Commit**

```bash
git add localcloud-server/build.gradle
git commit -m "feat: replace netty-codec-redis with Jedis client"
```

---

### Task 5: Remove Custom Emulator Code

**Files:**
- Delete: `localcloud-server/src/main/java/com/localcloud/emulators/memorystore/MemorystoreEmulator.java`
- Delete: `localcloud-server/src/main/java/com/localcloud/emulators/memorystore/RedisCommandHandler.java`
- Delete: `localcloud-server/src/main/java/com/localcloud/emulators/memorystore/MemorystoreStore.java`
- Delete: `localcloud-server/src/main/java/com/localcloud/emulators/memorystore/LuaScriptEngine.java`
- Delete: `localcloud-server/src/test/java/com/localcloud/emulators/memorystore/MemorystoreStoreTest.java`
- Keep: `PubSubManager.java` — check if used outside memorystore first

**Step 1: Check PubSubManager usage**

Run: `grep -r "PubSubManager" localcloud-server/src/main/java/ --include="*.java" -l`

If only referenced from RedisCommandHandler.java → delete it too.
If referenced elsewhere → keep it, move to a shared package if needed.

**Step 2: Delete files**

```bash
rm localcloud-server/src/main/java/com/localcloud/emulators/memorystore/MemorystoreEmulator.java
rm localcloud-server/src/main/java/com/localcloud/emulators/memorystore/RedisCommandHandler.java
rm localcloud-server/src/main/java/com/localcloud/emulators/memorystore/MemorystoreStore.java
rm localcloud-server/src/main/java/com/localcloud/emulators/memorystore/LuaScriptEngine.java
rm localcloud-server/src/test/java/com/localcloud/emulators/memorystore/MemorystoreStoreTest.java
# If PubSubManager only used by RedisCommandHandler:
rm localcloud-server/src/main/java/com/localcloud/emulators/memorystore/PubSubManager.java
```

**Step 3: Remove empty directories if needed**

```bash
# If memorystore dir is now empty, remove it
rmdir localcloud-server/src/main/java/com/localcloud/emulators/memorystore/ 2>/dev/null || true
rmdir localcloud-server/src/test/java/com/localcloud/emulators/memorystore/ 2>/dev/null || true
```

**Step 4: Commit**

```bash
git add -A
git commit -m "refactor: remove custom Memorystore emulator code (~2,700 LOC)"
```

---

### Task 6: Update LocalCloudApplication — Remove Emulator Startup

**Files:**
- Modify: `localcloud-server/src/main/java/com/localcloud/LocalCloudApplication.java:34,327-333`

**Step 1: Remove MemorystoreEmulator import**

Delete line 34:
```java
import com.localcloud.emulators.memorystore.MemorystoreEmulator;
```

**Step 2: Replace emulator startup block**

Replace lines 327-333:
```java
        if (config.isServiceEnabled("memorystore")) {
            int redisPort = config.getServiceRegistry().getService("memorystore").port();
            MemorystoreEmulator memorystoreEmulator = new MemorystoreEmulator(dataSource, redisPort, config.getProjectId());
            memorystoreEmulator.start();
            gateway.registerRestEmulator("/redis", memorystoreEmulator, null);
            logger.info("Memorystore (Redis) emulator started on port {}", redisPort);
        }
```

With:
```java
        if (config.isServiceEnabled("memorystore")) {
            int redisPort = config.getServiceRegistry().getService("memorystore").port();
            logger.info("Memorystore (Valkey) running as external process on port {}", redisPort);
        }
```

No Java emulator to start — Valkey is managed by supervisord.

**Step 3: Verify build**

Run: `cd localcloud-server && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/LocalCloudApplication.java
git commit -m "refactor: remove in-process Memorystore emulator startup, Valkey is external"
```

---

### Task 7: Remove PostgreSQL redis_data Table

**Files:**
- Modify: `localcloud-server/src/main/java/com/localcloud/persistence/SchemaManager.java:222-234`

**Step 1: Remove redis_data table creation**

Delete lines 222-234:
```java
            // Memorystore (Redis): redis_data
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS redis_data (" +
                "    project_id VARCHAR(255) NOT NULL DEFAULT 'local-project'," +
                "    db_number INT NOT NULL DEFAULT 0," +
                "    key_name TEXT NOT NULL," +
                "    data_type VARCHAR(10) NOT NULL," +
                "    value JSONB NOT NULL DEFAULT '\"\"'," +
                "    ttl_expires_at TIMESTAMPTZ," +
                "    PRIMARY KEY (project_id, db_number, key_name)" +
                ")"
            );
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_redis_ttl ON redis_data (ttl_expires_at) WHERE ttl_expires_at IS NOT NULL");
```

**Step 2: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/persistence/SchemaManager.java
git commit -m "refactor: remove redis_data PostgreSQL table, Valkey uses AOF"
```

---

### Task 8: Rewrite BrowseService for Valkey

**Files:**
- Modify: `localcloud-server/src/main/java/com/localcloud/admin/BrowseService.java:546-573`

**Step 1: Add Jedis import**

Add to imports:
```java
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
```

**Step 2: Rewrite browseMemorystore method**

Replace lines 546-573:

```java
    // ========== Memorystore (Redis/Valkey) ==========

    private String browseMemorystore(String resourceType, String resourceId, String projectId) throws Exception {
        int redisPort = config.getServiceRegistry().getService("memorystore") != null
                ? config.getServiceRegistry().getService("memorystore").port() : 6379;

        List<Map<String, Object>> keys = new ArrayList<>();
        try (Jedis jedis = new Jedis("localhost", redisPort)) {
            ScanParams scanParams = new ScanParams().match("*").count(200);
            String cursor = "0";
            do {
                ScanResult<String> result = jedis.scan(cursor, scanParams);
                for (String key : result.getResult()) {
                    Map<String, Object> k = new LinkedHashMap<>();
                    k.put("key", key);
                    String type = jedis.type(key);
                    k.put("type", type);

                    Object value = switch (type) {
                        case "string" -> jedis.get(key);
                        case "hash" -> jedis.hgetAll(key);
                        case "list" -> jedis.lrange(key, 0, 99);
                        case "set" -> jedis.smembers(key);
                        case "zset" -> jedis.zrangeWithScores(key, 0, 99);
                        case "stream" -> "stream (use XRANGE to view)";
                        default -> "(unknown type: " + type + ")";
                    };
                    k.put("value", value);

                    long ttl = jedis.ttl(key);
                    k.put("ttl", ttl > 0 ? ttl : null);

                    keys.add(k);
                    if (keys.size() >= 200) break;
                }
                cursor = result.getCursor();
            } while (!"0".equals(cursor) && keys.size() < 200);
        } catch (Exception e) {
            logger.warn("Failed to browse Memorystore: {}", e.getMessage());
            return mapper.writeValueAsString(Map.of("keys", List.of(),
                    "error", "Cannot connect to Valkey on port " + redisPort + ": " + e.getMessage()));
        }
        return mapper.writeValueAsString(Map.of("keys", keys, "total", keys.size()));
    }
```

**Step 3: Verify build**

Run: `cd localcloud-server && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/admin/BrowseService.java
git commit -m "feat: rewrite Memorystore browse to query Valkey directly via Jedis"
```

---

### Task 9: Rewrite SeedService for Valkey

**Files:**
- Modify: `localcloud-server/src/main/java/com/localcloud/admin/SeedService.java:1250-1313,772-778`

**Step 1: Add Jedis import**

Add to imports:
```java
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
```

**Step 2: Rewrite seedMemorystore method**

Replace lines 1252-1313:

```java
    @SuppressWarnings("unchecked")
    private int seedMemorystore(Object msData) {
        if (!(msData instanceof Map)) return 0;
        Map<String, Object> ms = (Map<String, Object>) msData;
        int count = 0;
        int redisPort = config.getServiceRegistry().getService("memorystore") != null
                ? config.getServiceRegistry().getService("memorystore").port() : 6379;

        try (Jedis jedis = new Jedis("localhost", redisPort)) {
            Pipeline pipe = jedis.pipelined();

            // Seed string keys
            List<Map<String, Object>> keys = (List<Map<String, Object>>) ms.get("keys");
            if (keys != null) {
                for (Map<String, Object> entry : keys) {
                    String key = (String) entry.get("key");
                    String value = String.valueOf(entry.get("value"));
                    if (key != null && value != null) {
                        pipe.set(key, value);
                        if (entry.containsKey("ttl")) {
                            pipe.expire(key, ((Number) entry.get("ttl")).longValue());
                        }
                        count++;
                    }
                }
            }

            // Seed hashes
            List<Map<String, Object>> hashes = (List<Map<String, Object>>) ms.get("hashes");
            if (hashes != null) {
                for (Map<String, Object> entry : hashes) {
                    String key = (String) entry.get("key");
                    Map<String, Object> fields = (Map<String, Object>) entry.get("fields");
                    if (key != null && fields != null) {
                        Map<String, String> stringFields = new LinkedHashMap<>();
                        fields.forEach((k, v) -> stringFields.put(k, String.valueOf(v)));
                        pipe.hset(key, stringFields);
                        count++;
                    }
                }
            }

            // Seed lists
            List<Map<String, Object>> lists = (List<Map<String, Object>>) ms.get("lists");
            if (lists != null) {
                for (Map<String, Object> entry : lists) {
                    String key = (String) entry.get("key");
                    List<String> values = (List<String>) entry.get("values");
                    if (key != null && values != null && !values.isEmpty()) {
                        pipe.rpush(key, values.toArray(new String[0]));
                        count++;
                    }
                }
            }

            // Seed sets
            List<Map<String, Object>> sets = (List<Map<String, Object>>) ms.get("sets");
            if (sets != null) {
                for (Map<String, Object> entry : sets) {
                    String key = (String) entry.get("key");
                    List<String> members = (List<String>) entry.get("members");
                    if (key != null && members != null && !members.isEmpty()) {
                        pipe.sadd(key, members.toArray(new String[0]));
                        count++;
                    }
                }
            }

            pipe.sync();
        } catch (Exception e) {
            logger.warn("Failed to seed Memorystore via Valkey: {}", e.getMessage());
        }
        return count;
    }
```

**Step 3: Rewrite resetMemorystore method**

Replace lines 772-778:

```java
    private int resetMemorystore(String projectId) {
        int redisPort = config.getServiceRegistry().getService("memorystore") != null
                ? config.getServiceRegistry().getService("memorystore").port() : 6379;
        try (Jedis jedis = new Jedis("localhost", redisPort)) {
            jedis.flushAll();
            logger.info("Reset Memorystore: flushed all databases");
            return 1;
        } catch (Exception e) {
            logger.warn("Failed to reset Memorystore: {}", e.getMessage());
            return 0;
        }
    }
```

**Step 4: Verify build**

Run: `cd localcloud-server && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/admin/SeedService.java
git commit -m "feat: rewrite Memorystore seed/reset to use Jedis pipeline to Valkey"
```

---

### Task 10: Update docker-entrypoint.sh

**Files:**
- Modify: `docker-entrypoint.sh:206`

**Step 1: Verify memorystore service flag**

Line 206 should already have:
```bash
memorystore)    export LOCALCLOUD_ENABLE_MEMORYSTORE="true" ;;
```

No change needed — same env var drives both old emulator and new supervisord Valkey.

**Step 2: Add redis-data dir creation**

Find the section that creates data directories (near `mkdir -p /var/lib/localcloud/spanner-data`) and add:

```bash
mkdir -p /var/lib/localcloud/redis-data
```

**Step 3: Commit**

```bash
git add docker-entrypoint.sh
git commit -m "feat: ensure redis-data directory exists for Valkey AOF"
```

---

### Task 11: Build, Test, Verify

**Step 1: Run unit tests**

```bash
cd localcloud-server && ./gradlew test
```

Expected: BUILD SUCCESSFUL. Some tests may need updating if they reference deleted classes.

**Step 2: Build shadow JAR**

```bash
cd localcloud-server && ./gradlew shadowJar
```

**Step 3: Build Docker image**

```bash
docker build -t localcloud/localcloud:latest .
```

Expected: Valkey binary pulled from `valkey/valkey:8.1-alpine`, copied into final image.

**Step 4: Start container**

```bash
./start.sh
```

**Step 5: Verify Valkey is running**

```bash
docker exec localcloud valkey-cli PING
```

Expected: `PONG`

**Step 6: Verify AOF persistence**

```bash
docker exec localcloud valkey-cli SET test:key "hello"
docker exec localcloud valkey-cli GET test:key
# Expected: "hello"

docker exec localcloud ls -la /var/lib/localcloud/redis-data/
# Expected: appendonly.aof file exists
```

**Step 7: Verify browsing via API**

```bash
curl -s http://localhost:8080/_localcloud/browse?service=memorystore | python3 -m json.tool
```

Expected: JSON with `test:key` in keys list.

**Step 8: Verify seeding**

```bash
curl -s -X POST http://localhost:8080/_localcloud/seed -H "Content-Type: application/json" \
  -d '{"services":{"memorystore":{"keys":[{"key":"user:1","value":"John"},{"key":"user:2","value":"Jane"}]}}}'
```

Then browse again — should see `user:1` and `user:2`.

**Step 9: Verify reset**

```bash
curl -s -X POST http://localhost:8080/_localcloud/reset -H "Content-Type: application/json" \
  -d '{"services":["memorystore"]}'
docker exec localcloud valkey-cli DBSIZE
```

Expected: `(integer) 0`

**Step 10: Commit**

```bash
git add -A
git commit -m "feat: Valkey migration complete — verified end-to-end"
```

---

## Task Summary

| Task | Description | LOC Change |
|------|-------------|------------|
| 1 | Add Valkey binary to Dockerfile | +15 |
| 2 | Add Valkey to supervisord | +14 |
| 3 | Update services.yaml | ~2 |
| 4 | Add Jedis dependency | ~3 |
| 5 | Delete custom emulator code | **-2,700** |
| 6 | Update LocalCloudApplication | ~5 |
| 7 | Remove redis_data table | -13 |
| 8 | Rewrite BrowseService | ~40 (replaces ~25) |
| 9 | Rewrite SeedService | ~70 (replaces ~60) |
| 10 | Update docker-entrypoint.sh | +1 |
| 11 | Build, test, verify | 0 |

**Net: ~2,500 LOC deleted**
