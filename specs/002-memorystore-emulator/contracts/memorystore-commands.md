# Contract: Memorystore (Redis/Valkey) Commands

**Protocol**: RESP2 (Redis Serialization Protocol v2)
**Port**: 6379 (configurable)
**Env Var**: `REDIS_HOST=localhost:6379`

## Supported Commands (52)

### Connection & Server (9)

| Command | Syntax | Description |
|---------|--------|-------------|
| PING | `PING [message]` | Returns PONG or echoes message |
| ECHO | `ECHO message` | Echoes message back |
| QUIT | `QUIT` | Closes connection |
| SELECT | `SELECT db` | Switch database (0-15) |
| AUTH | `AUTH password` | Stub — always returns OK in permissive mode |
| INFO | `INFO [section]` | Returns server information |
| DBSIZE | `DBSIZE` | Returns number of keys in current database |
| FLUSHDB | `FLUSHDB` | Clears current database |
| FLUSHALL | `FLUSHALL` | Clears all 16 databases |

### Strings (14)

| Command | Syntax | Description |
|---------|--------|-------------|
| GET | `GET key` | Get string value |
| SET | `SET key value [EX s] [PX ms] [NX\|XX]` | Set string value with options |
| SETNX | `SETNX key value` | Set if not exists |
| SETEX | `SETEX key seconds value` | Set with expiration |
| MGET | `MGET key [key ...]` | Get multiple values |
| MSET | `MSET key value [key value ...]` | Set multiple values |
| INCR | `INCR key` | Increment integer value by 1 |
| INCRBY | `INCRBY key increment` | Increment by integer |
| INCRBYFLOAT | `INCRBYFLOAT key increment` | Increment by float |
| DECR | `DECR key` | Decrement integer value by 1 |
| DECRBY | `DECRBY key decrement` | Decrement by integer |
| APPEND | `APPEND key value` | Append to string |
| STRLEN | `STRLEN key` | Get string length |
| GETSET | `GETSET key value` | Set and return old value |

### Keys (13)

| Command | Syntax | Description |
|---------|--------|-------------|
| DEL | `DEL key [key ...]` | Delete keys |
| EXISTS | `EXISTS key [key ...]` | Check key existence |
| EXPIRE | `EXPIRE key seconds` | Set TTL in seconds |
| EXPIREAT | `EXPIREAT key timestamp` | Set TTL as Unix timestamp |
| PEXPIRE | `PEXPIRE key milliseconds` | Set TTL in milliseconds |
| PEXPIREAT | `PEXPIREAT key ms-timestamp` | Set TTL as Unix ms timestamp |
| TTL | `TTL key` | Get remaining TTL in seconds |
| PTTL | `PTTL key` | Get remaining TTL in milliseconds |
| PERSIST | `PERSIST key` | Remove TTL |
| TYPE | `TYPE key` | Get key data type |
| RENAME | `RENAME key newkey` | Rename a key |
| KEYS | `KEYS pattern` | Find keys matching glob pattern |
| SCAN | `SCAN cursor [MATCH pattern] [COUNT count]` | Incrementally iterate keys |

### Hashes (10)

| Command | Syntax | Description |
|---------|--------|-------------|
| HGET | `HGET key field` | Get hash field value |
| HSET | `HSET key field value [field value ...]` | Set hash fields |
| HMGET | `HMGET key field [field ...]` | Get multiple hash fields |
| HDEL | `HDEL key field [field ...]` | Delete hash fields |
| HEXISTS | `HEXISTS key field` | Check field exists |
| HGETALL | `HGETALL key` | Get all fields and values |
| HKEYS | `HKEYS key` | Get all field names |
| HVALS | `HVALS key` | Get all values |
| HLEN | `HLEN key` | Get number of fields |
| HINCRBY | `HINCRBY key field increment` | Increment field by integer |

### Lists (9)

| Command | Syntax | Description |
|---------|--------|-------------|
| LPUSH | `LPUSH key value [value ...]` | Push to head |
| RPUSH | `RPUSH key value [value ...]` | Push to tail |
| LPOP | `LPOP key [count]` | Pop from head |
| RPOP | `RPOP key [count]` | Pop from tail |
| LRANGE | `LRANGE key start stop` | Get range of elements |
| LLEN | `LLEN key` | Get list length |
| LINDEX | `LINDEX key index` | Get element by index |
| LSET | `LSET key index value` | Set element by index |
| LTRIM | `LTRIM key start stop` | Trim list to range |

### Sets (8)

| Command | Syntax | Description |
|---------|--------|-------------|
| SADD | `SADD key member [member ...]` | Add members |
| SREM | `SREM key member [member ...]` | Remove members |
| SMEMBERS | `SMEMBERS key` | Get all members |
| SCARD | `SCARD key` | Get set cardinality |
| SISMEMBER | `SISMEMBER key member` | Check membership |
| SINTER | `SINTER key [key ...]` | Intersection |
| SUNION | `SUNION key [key ...]` | Union |
| SDIFF | `SDIFF key [key ...]` | Difference |

### Sorted Sets (8)

| Command | Syntax | Description |
|---------|--------|-------------|
| ZADD | `ZADD key score member [score member ...]` | Add with scores |
| ZREM | `ZREM key member [member ...]` | Remove members |
| ZRANGE | `ZRANGE key start stop [WITHSCORES]` | Get by rank range |
| ZRANGEBYSCORE | `ZRANGEBYSCORE key min max [WITHSCORES]` | Get by score range |
| ZCARD | `ZCARD key` | Get cardinality |
| ZSCORE | `ZSCORE key member` | Get member score |
| ZRANK | `ZRANK key member` | Get member rank |
| ZCOUNT | `ZCOUNT key min max` | Count in score range |

## Not Supported (v1)

- Transactions: MULTI, EXEC, DISCARD, WATCH
- Lua scripting: EVAL, EVALSHA, SCRIPT
- Pub/Sub: SUBSCRIBE, PUBLISH (use LocalCloud's dedicated Pub/Sub emulator)
- Streams: XADD, XREAD, XRANGE, XGROUP
- Blocking: BLPOP, BRPOP, BLMOVE, BRPOPLPUSH
- Geo: GEOADD, GEODIST, GEOSEARCH
- HyperLogLog: PFADD, PFCOUNT, PFMERGE
- Bitmap: SETBIT, GETBIT, BITCOUNT, BITOP
- Cluster: CLUSTER, READONLY, READWRITE
- ACL: ACL LIST, ACL SETUSER (use LocalCloud IAM)
- Persistence: SAVE, BGSAVE, BGREWRITEAOF
- Config: CONFIG GET/SET (managed by service registry)
- Client: CLIENT LIST, CLIENT KILL
- Debug: DEBUG, SLOWLOG, MONITOR, LATENCY

Unsupported commands return: `ERR unknown command '<command>'`
