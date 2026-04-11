# Data Model: Memorystore (Redis/Valkey) Emulator

## Entities

### RedisData (Primary Storage)

Single table storing all Redis data types using JSONB.

| Field | Type | Description |
|-------|------|-------------|
| db_number | INTEGER | Logical database (0-15) |
| key_name | TEXT | Redis key (binary-safe) |
| data_type | VARCHAR(20) | `string`, `hash`, `list`, `set`, `zset` |
| value | JSONB | Type-specific structured value (see below) |
| ttl_expires_at | TIMESTAMPTZ | Absolute expiration time, NULL = no expiry |
| created_at | TIMESTAMPTZ | Key creation timestamp |
| updated_at | TIMESTAMPTZ | Last modification timestamp |

**Primary Key**: `(db_number, key_name)`
**Indexes**: `idx_ttl ON (ttl_expires_at) WHERE ttl_expires_at IS NOT NULL`

### Value Encoding by Data Type

| Redis Type | JSONB Structure | Example |
|------------|----------------|---------|
| string | `"hello"` | `"hello world"` |
| hash | `{"field": "value", ...}` | `{"name": "Jay", "email": "jay@paypal.com"}` |
| list | `["item1", "item2", ...]` | `["task3", "task2", "task1"]` |
| set | `["member1", "member2", ...]` | `["redis", "valkey", "memcached"]` |
| zset | `[{"m": "player1", "s": 100}, ...]` | `[{"m": "alice", "s": 95.5}, {"m": "bob", "s": 88.0}]` |

### State Transitions

```
Key Lifecycle:
  [not exists] --SET/HSET/LPUSH/etc--> [active]
  [active] --EXPIRE--> [active with TTL]
  [active with TTL] --PERSIST--> [active without TTL]
  [active with TTL] --ttl expires--> [expired] --cleanup--> [not exists]
  [active] --DEL--> [not exists]
  [active] --FLUSHDB--> [not exists]
```

### Relationships

- A RedisData row belongs to exactly one logical database (0-15)
- Keys are unique within a database but can repeat across databases
- Data type is immutable once set — operations on wrong type return WRONGTYPE error
- TTL is independent of data type — any key can have an expiration
