# Quickstart: Memorystore (Redis/Valkey) Emulator

## 1. Start LocalCloud with Memorystore

```bash
docker compose up -d
# Or
localcloud start --services gcs,pubsub,memorystore
```

## 2. Connect with redis-cli

```bash
redis-cli -h localhost -p 6379
> PING
PONG
> SET greeting "Hello from LocalCloud"
OK
> GET greeting
"Hello from LocalCloud"
```

## 3. Connect from Python

```python
import redis

r = redis.Redis(host='localhost', port=6379, decode_responses=True)

# Strings
r.set('user:session:abc123', 'Jay Senjaliya', ex=3600)
print(r.get('user:session:abc123'))  # "Jay Senjaliya"

# Hashes
r.hset('user:1', mapping={'name': 'Jay', 'email': 'jay@paypal.com', 'role': 'admin'})
print(r.hgetall('user:1'))  # {'name': 'Jay', 'email': 'jay@paypal.com', 'role': 'admin'}

# Lists
r.lpush('task-queue', 'process-payment', 'send-email', 'update-inventory')
print(r.rpop('task-queue'))  # 'process-payment'

# Sets
r.sadd('tags:article:1', 'redis', 'valkey', 'caching')
print(r.smembers('tags:article:1'))  # {'redis', 'valkey', 'caching'}
```

## 4. Connect from Java (Jedis)

```java
import redis.clients.jedis.Jedis;

try (Jedis jedis = new Jedis("localhost", 6379)) {
    jedis.set("mykey", "hello");
    System.out.println(jedis.get("mykey"));  // "hello"

    jedis.hset("user:1", "name", "Jay");
    System.out.println(jedis.hgetall("user:1"));
}
```

## 5. Seed Data

Add to your `seed.yaml`:

```yaml
services:
  memorystore:
    keys:
      - key: "config:app-name"
        value: "LocalCloud Demo"
      - key: "config:version"
        value: "1.0.0"
        ttl: 3600
    hashes:
      - key: "user:1"
        fields:
          name: "Jay Senjaliya"
          email: "JaySen@apache.com"
          role: "admin"
```

## 6. Switch to Production

```bash
# Remove the local override — your code now hits Google Memorystore
unset REDIS_HOST
# No code changes needed. Same redis-py/Jedis/Lettuce code works.
```
