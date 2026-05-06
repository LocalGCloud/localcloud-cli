package com.localcloud.emulators.memorystore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.localcloud.emulators.AbstractEmulator;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.redis.ArrayRedisMessage;
import io.netty.handler.codec.redis.ErrorRedisMessage;
import io.netty.handler.codec.redis.FullBulkStringRedisMessage;
import io.netty.handler.codec.redis.InlineCommandRedisMessage;
import io.netty.handler.codec.redis.IntegerRedisMessage;
import io.netty.handler.codec.redis.RedisMessage;
import io.netty.handler.codec.redis.SimpleStringRedisMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netty channel handler that decodes RESP2 commands and dispatches them
 * to the {@link MemorystoreStore} persistence layer.
 * Implements 50 Redis commands across all major data types.
 */
public class RedisCommandHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(RedisCommandHandler.class);

    private final MemorystoreStore store;
    private final AbstractEmulator emulator;
    private final LuaScriptEngine luaEngine;
    private int currentDb = 0;

    public RedisCommandHandler(MemorystoreStore store, AbstractEmulator emulator) {
        this.store = store;
        this.emulator = emulator;
        this.luaEngine = new LuaScriptEngine(store);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        List<String> args;

        if (msg instanceof ArrayRedisMessage arrayMsg) {
            args = extractArgs(arrayMsg);
        } else if (msg instanceof InlineCommandRedisMessage inlineMsg) {
            // Inline commands: "PING\r\n" or "SET key value\r\n"
            String content = inlineMsg.content();
            if (content == null || content.isBlank()) {
                return; // ignore empty
            }
            args = new ArrayList<>(List.of(content.trim().split("\\s+")));
        } else {
            ctx.writeAndFlush(error("invalid command format"));
            return;
        }

        try {
            emulator.incrementRequestCount();
            if (args.isEmpty()) {
                ctx.writeAndFlush(error("empty command"));
                return;
            }

            String cmd = args.get(0).toUpperCase();
            RedisMessage response = dispatch(ctx, cmd, args);
            if (response != null) {
                ctx.writeAndFlush(response);
            }
        } catch (Exception e) {
            logger.error("Error processing Redis command", e);
            ctx.writeAndFlush(error("internal error: " + e.getMessage()));
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.warn("Redis connection error: {}", cause.getMessage());
        ctx.close();
    }

    private RedisMessage dispatch(ChannelHandlerContext ctx, String cmd, List<String> args) {
        return switch (cmd) {
            // Server commands (T010)
            case "PING" -> handlePing(args);
            case "ECHO" -> handleEcho(args);
            case "QUIT" -> handleQuit(ctx);
            case "SELECT" -> handleSelect(args);
            case "INFO" -> handleInfo(args);
            case "DBSIZE" -> handleDbSize();
            case "FLUSHDB" -> handleFlushDb();
            case "FLUSHALL" -> handleFlushAll();
            case "COMMAND" -> handleCommand(args);

            // String commands (T011)
            case "GET" -> handleGet(args);
            case "SET" -> handleSet(args);
            case "SETNX" -> handleSetNx(args);
            case "SETEX" -> handleSetEx(args);
            case "MGET" -> handleMget(args);
            case "MSET" -> handleMset(args);
            case "INCR" -> handleIncr(args, 1);
            case "INCRBY" -> handleIncrBy(args);
            case "INCRBYFLOAT" -> handleIncrByFloat(args);
            case "DECR" -> handleIncr(args, -1);
            case "DECRBY" -> handleDecrBy(args);
            case "APPEND" -> handleAppend(args);
            case "STRLEN" -> handleStrlen(args);

            // Key commands (T012)
            case "DEL" -> handleDel(args);
            case "EXISTS" -> handleExists(args);
            case "EXPIRE" -> handleExpire(args, false);
            case "PEXPIRE" -> handleExpire(args, true);
            case "EXPIREAT" -> handleExpireAt(args);
            case "TTL" -> handleTtl(args, false);
            case "PTTL" -> handleTtl(args, true);
            case "PERSIST" -> handlePersist(args);
            case "TYPE" -> handleType(args);
            case "RENAME" -> handleRename(args);
            case "KEYS" -> handleKeys(args);

            // Lua scripting commands
            case "EVAL" -> handleEval(args);
            case "EVALSHA" -> handleEvalSha(args);
            case "SCRIPT" -> handleScript(args);

            // Hash commands (T015)
            case "HGET" -> handleHget(args);
            case "HSET" -> handleHset(args);
            case "HMGET" -> handleHmget(args);
            case "HDEL" -> handleHdel(args);
            case "HEXISTS" -> handleHexists(args);
            case "HGETALL" -> handleHgetall(args);
            case "HKEYS" -> handleHkeys(args);
            case "HVALS" -> handleHvals(args);
            case "HLEN" -> handleHlen(args);
            case "HINCRBY" -> handleHincrby(args);

            // List commands (T016)
            case "LPUSH" -> handleLpush(args);
            case "RPUSH" -> handleRpush(args);
            case "LPOP" -> handleLpop(args);
            case "RPOP" -> handleRpop(args);
            case "LRANGE" -> handleLrange(args);
            case "LLEN" -> handleLlen(args);
            case "LINDEX" -> handleLindex(args);
            case "LSET" -> handleLset(args);
            case "LTRIM" -> handleLtrim(args);

            // Pub/Sub commands
            case "SUBSCRIBE" -> handleSubscribe(args);
            case "PSUBSCRIBE" -> handlePSubscribe(args);
            case "UNSUBSCRIBE" -> handleUnsubscribe(args);
            case "PUNSUBSCRIBE" -> handlePUnsubscribe(args);
            case "PUBLISH" -> handlePublish(args);
            case "PUBSUB" -> handlePubSub(args);

            // Set commands (T017)
            case "SADD" -> handleSadd(args);
            case "SREM" -> handleSrem(args);
            case "SMEMBERS" -> handleSmembers(args);
            case "SCARD" -> handleScard(args);
            case "SISMEMBER" -> handleSismember(args);
            case "SINTER" -> handleSinter(args);
            case "SUNION" -> handleSunion(args);
            case "SDIFF" -> handleSdiff(args);

            // Sorted set commands (T018)
            case "ZADD" -> handleZadd(args);
            case "ZREM" -> handleZrem(args);
            case "ZRANGE" -> handleZrange(args);
            case "ZRANGEBYSCORE" -> handleZrangebyscore(args);
            case "ZCARD" -> handleZcard(args);
            case "ZSCORE" -> handleZscore(args);
            case "ZRANK" -> handleZrank(args);
            case "ZCOUNT" -> handleZcount(args);

            // T014: Unknown command fallback
            default -> error("unknown command '" + cmd.toLowerCase() + "'");
        };
    }

    // =========================================================================
    // Server commands (T010)
    // =========================================================================

    private RedisMessage handlePing(List<String> args) {
        if (args.size() > 1) {
            return bulkString(args.get(1));
        }
        return new SimpleStringRedisMessage("PONG");
    }

    private RedisMessage handleEcho(List<String> args) {
        if (args.size() < 2) return error("wrong number of arguments for 'echo' command");
        return bulkString(args.get(1));
    }

    private RedisMessage handleQuit(ChannelHandlerContext ctx) {
        ctx.writeAndFlush(ok());
        ctx.close();
        return null;
    }

    private RedisMessage handleSelect(List<String> args) {
        if (args.size() < 2) return error("wrong number of arguments for 'select' command");
        try {
            int db = Integer.parseInt(args.get(1));
            if (db < 0 || db > 15) return error("DB index is out of range");
            this.currentDb = db;
            return ok();
        } catch (NumberFormatException e) {
            return error("value is not an integer or out of range");
        }
    }

    private RedisMessage handleInfo(List<String> args) {
        String info = "# Server\r\nredis_version:7.0.0-localcloud\r\n" +
                      "# Clients\r\nconnected_clients:1\r\n" +
                      "# Memory\r\nused_memory:0\r\n" +
                      "# Keyspace\r\ndb" + currentDb + ":keys=" + store.dbSize(currentDb) + "\r\n";
        return bulkString(info);
    }

    private RedisMessage handleDbSize() {
        return integer(store.dbSize(currentDb));
    }

    private RedisMessage handleFlushDb() {
        store.flushDb(currentDb);
        return ok();
    }

    private RedisMessage handleFlushAll() {
        store.flushAll();
        return ok();
    }

    private RedisMessage handleCommand(List<String> args) {
        // Minimal COMMAND support for client compatibility
        return array(List.of());
    }

    // =========================================================================
    // Lua scripting commands
    // =========================================================================

    private RedisMessage handleEval(List<String> args) {
        if (args.size() < 3) return error("wrong number of arguments for 'eval' command");
        String script = args.get(1);
        int numKeys;
        try {
            numKeys = Integer.parseInt(args.get(2));
        } catch (NumberFormatException e) {
            return error("numkeys must be an integer");
        }
        List<String> keys = new ArrayList<>();
        List<String> argv = new ArrayList<>();
        for (int i = 0; i < numKeys && i + 3 < args.size(); i++) {
            keys.add(args.get(3 + i));
        }
        for (int i = 3 + numKeys; i < args.size(); i++) {
            argv.add(args.get(i));
        }
        String result = luaEngine.eval(script, numKeys, keys, argv);
        return bulkString(result);
    }

    private RedisMessage handleEvalSha(List<String> args) {
        if (args.size() < 3) return error("wrong number of arguments for 'evalsha' command");
        String sha = args.get(1);
        int numKeys;
        try {
            numKeys = Integer.parseInt(args.get(2));
        } catch (NumberFormatException e) {
            return error("numkeys must be an integer");
        }
        List<String> keys = new ArrayList<>();
        List<String> argv = new ArrayList<>();
        for (int i = 0; i < numKeys && i + 3 < args.size(); i++) {
            keys.add(args.get(3 + i));
        }
        for (int i = 3 + numKeys; i < args.size(); i++) {
            argv.add(args.get(i));
        }
        String result = luaEngine.evalsha(sha, numKeys, keys, argv);
        if (result.startsWith("-NOSCRIPT")) {
            return error(result.substring(1));
        }
        return bulkString(result);
    }

    private RedisMessage handleScript(List<String> args) {
        if (args.size() < 2) return error("wrong number of arguments for 'script' command");
        String subCmd = args.get(1).toUpperCase();
        if (subCmd.equals("LOAD")) {
            if (args.size() < 3) return error("wrong number of arguments for 'script load' command");
            String sha = luaEngine.scriptLoad(args.get(2));
            return bulkString(sha);
        } else if (subCmd.equals("FLUSH")) {
            luaEngine.scriptFlush();
            return ok();
        } else if (subCmd.equals("EXISTS")) {
            return array(List.of());
        } else {
            return error("SCRIPT subcommand must be LOAD, FLUSH, or EXISTS");
        }
    }

    // =========================================================================
    // String commands (T011)
    // =========================================================================

    private RedisMessage handleGet(List<String> args) {
        if (args.size() < 2) return error("wrong number of arguments for 'get' command");
        String key = args.get(1);
        // T019: WRONGTYPE check
        String type = store.type(currentDb, key);
        if (type != null && !"string".equals(type)) return wrongType();
        String val = store.getString(currentDb, key);
        return val != null ? bulkString(val) : nullBulk();
    }

    private RedisMessage handleSet(List<String> args) {
        if (args.size() < 3) return error("wrong number of arguments for 'set' command");
        String key = args.get(1);
        String value = args.get(2);
        long ttlSeconds = 0;
        boolean nx = false, xx = false;

        for (int i = 3; i < args.size(); i++) {
            String flag = args.get(i).toUpperCase();
            switch (flag) {
                case "EX" -> {
                    if (i + 1 >= args.size()) return error("syntax error");
                    try { ttlSeconds = Long.parseLong(args.get(++i)); } catch (NumberFormatException e) { return error("value is not an integer or out of range"); }
                }
                case "PX" -> {
                    if (i + 1 >= args.size()) return error("syntax error");
                    try {
                        long ms = Long.parseLong(args.get(++i));
                        ttlSeconds = (ms + 999) / 1000; // ceiling division
                    } catch (NumberFormatException e) { return error("value is not an integer or out of range"); }
                }
                case "NX" -> nx = true;
                case "XX" -> xx = true;
                default -> { return error("syntax error"); }
            }
        }

        boolean set = store.setStringConditional(currentDb, key, value, ttlSeconds, nx, xx);
        if (nx || xx) {
            return set ? ok() : nullBulk();
        }
        return ok();
    }

    private RedisMessage handleSetNx(List<String> args) {
        if (args.size() < 3) return error("wrong number of arguments for 'setnx' command");
        boolean set = store.setStringConditional(currentDb, args.get(1), args.get(2), 0, true, false);
        return integer(set ? 1 : 0);
    }

    private RedisMessage handleSetEx(List<String> args) {
        if (args.size() < 4) return error("wrong number of arguments for 'setex' command");
        try {
            long ttl = Long.parseLong(args.get(2));
            if (ttl <= 0) return error("invalid expire time in 'setex' command");
            store.setString(currentDb, args.get(1), args.get(3), ttl);
            return ok();
        } catch (NumberFormatException e) {
            return error("value is not an integer or out of range");
        }
    }

    private RedisMessage handleMget(List<String> args) {
        if (args.size() < 2) return error("wrong number of arguments for 'mget' command");
        List<RedisMessage> results = new ArrayList<>();
        for (int i = 1; i < args.size(); i++) {
            String val = store.getString(currentDb, args.get(i));
            results.add(val != null ? bulkString(val) : nullBulk());
        }
        return array(results);
    }

    private RedisMessage handleMset(List<String> args) {
        if (args.size() < 3 || (args.size() - 1) % 2 != 0) return error("wrong number of arguments for 'mset' command");
        for (int i = 1; i < args.size(); i += 2) {
            store.setString(currentDb, args.get(i), args.get(i + 1), 0);
        }
        return ok();
    }

    private RedisMessage handleIncr(List<String> args, long delta) {
        if (args.size() < 2) return error("wrong number of arguments for 'incr' command");
        String key = args.get(1);
        // T019: WRONGTYPE check
        String type = store.type(currentDb, key);
        if (type != null && !"string".equals(type)) return wrongType();

        String current = store.getString(currentDb, key);
        long val;
        if (current == null) {
            val = 0;
        } else {
            try { val = Long.parseLong(current); } catch (NumberFormatException e) { return error("value is not an integer or out of range"); }
        }
        val += delta;
        store.setString(currentDb, key, String.valueOf(val), 0);
        return integer(val);
    }

    private RedisMessage handleIncrBy(List<String> args) {
        if (args.size() < 3) return error("wrong number of arguments for 'incrby' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"string".equals(type)) return wrongType();
        long delta;
        try { delta = Long.parseLong(args.get(2)); } catch (NumberFormatException e) { return error("value is not an integer or out of range"); }
        String current = store.getString(currentDb, key);
        long val = current != null ? parseLongSafe(current) : 0;
        if (current != null) {
            try { val = Long.parseLong(current); } catch (NumberFormatException e) { return error("value is not an integer or out of range"); }
        }
        val += delta;
        store.setString(currentDb, key, String.valueOf(val), 0);
        return integer(val);
    }

    private RedisMessage handleIncrByFloat(List<String> args) {
        if (args.size() < 3) return error("wrong number of arguments for 'incrbyfloat' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"string".equals(type)) return wrongType();
        double delta;
        try { delta = Double.parseDouble(args.get(2)); } catch (NumberFormatException e) { return error("value is not a valid float"); }
        String current = store.getString(currentDb, key);
        double val = 0;
        if (current != null) {
            try { val = Double.parseDouble(current); } catch (NumberFormatException e) { return error("value is not a valid float"); }
        }
        val += delta;
        String result = formatDouble(val);
        store.setString(currentDb, key, result, 0);
        return bulkString(result);
    }

    private RedisMessage handleDecrBy(List<String> args) {
        if (args.size() < 3) return error("wrong number of arguments for 'decrby' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"string".equals(type)) return wrongType();
        long delta;
        try { delta = Long.parseLong(args.get(2)); } catch (NumberFormatException e) { return error("value is not an integer or out of range"); }
        String current = store.getString(currentDb, key);
        long val = 0;
        if (current != null) {
            try { val = Long.parseLong(current); } catch (NumberFormatException e) { return error("value is not an integer or out of range"); }
        }
        val -= delta;
        store.setString(currentDb, key, String.valueOf(val), 0);
        return integer(val);
    }

    private RedisMessage handleAppend(List<String> args) {
        if (args.size() < 3) return error("wrong number of arguments for 'append' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"string".equals(type)) return wrongType();
        String current = store.getString(currentDb, key);
        String newVal = (current != null ? current : "") + args.get(2);
        store.setString(currentDb, key, newVal, 0);
        return integer(newVal.length());
    }

    private RedisMessage handleStrlen(List<String> args) {
        if (args.size() < 2) return error("wrong number of arguments for 'strlen' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"string".equals(type)) return wrongType();
        String val = store.getString(currentDb, key);
        return integer(val != null ? val.length() : 0);
    }

    // =========================================================================
    // Key commands (T012)
    // =========================================================================

    private RedisMessage handleDel(List<String> args) {
        if (args.size() < 2) return error("wrong number of arguments for 'del' command");
        List<String> keys = args.subList(1, args.size());
        int deleted = store.deleteKeys(currentDb, keys);
        return integer(deleted);
    }

    private RedisMessage handleExists(List<String> args) {
        if (args.size() < 2) return error("wrong number of arguments for 'exists' command");
        int count = 0;
        for (int i = 1; i < args.size(); i++) {
            if (store.exists(currentDb, args.get(i))) count++;
        }
        return integer(count);
    }

    private RedisMessage handleExpire(List<String> args, boolean millis) {
        if (args.size() < 3) return error("wrong number of arguments for 'expire' command");
        try {
            long ttl = Long.parseLong(args.get(2));
            long ms = millis ? ttl : ttl * 1000;
            boolean set = store.setExpire(currentDb, args.get(1), ms);
            return integer(set ? 1 : 0);
        } catch (NumberFormatException e) {
            return error("value is not an integer or out of range");
        }
    }

    private RedisMessage handleExpireAt(List<String> args) {
        if (args.size() < 3) return error("wrong number of arguments for 'expireat' command");
        try {
            long timestamp = Long.parseLong(args.get(2));
            boolean set = store.setExpireAt(currentDb, args.get(1), timestamp);
            return integer(set ? 1 : 0);
        } catch (NumberFormatException e) {
            return error("value is not an integer or out of range");
        }
    }

    private RedisMessage handleTtl(List<String> args, boolean millis) {
        if (args.size() < 2) return error("wrong number of arguments for 'ttl' command");
        long ttlMs = store.getTtl(currentDb, args.get(1));
        if (ttlMs == -2 || ttlMs == -1) return integer(ttlMs);
        return integer(millis ? ttlMs : ttlMs / 1000);
    }

    private RedisMessage handlePersist(List<String> args) {
        if (args.size() < 2) return error("wrong number of arguments for 'persist' command");
        boolean removed = store.persist(currentDb, args.get(1));
        return integer(removed ? 1 : 0);
    }

    private RedisMessage handleType(List<String> args) {
        if (args.size() < 2) return error("wrong number of arguments for 'type' command");
        String type = store.type(currentDb, args.get(1));
        if (type == null) return new SimpleStringRedisMessage("none");
        return new SimpleStringRedisMessage(type);
    }

    private RedisMessage handleRename(List<String> args) {
        if (args.size() < 3) return error("wrong number of arguments for 'rename' command");
        if (!store.exists(currentDb, args.get(1))) return error("no such key");
        store.rename(currentDb, args.get(1), args.get(2));
        return ok();
    }

    private RedisMessage handleKeys(List<String> args) {
        if (args.size() < 2) return error("wrong number of arguments for 'keys' command");
        List<String> keys = store.keys(currentDb, args.get(1));
        List<RedisMessage> result = new ArrayList<>();
        for (String k : keys) result.add(bulkString(k));
        return array(result);
    }

    // =========================================================================
    // Hash commands (T015)
    // =========================================================================

    private RedisMessage handleHget(List<String> args) {
        if (args.size() < 3) return error("wrong number of arguments for 'hget' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"hash".equals(type)) return wrongType();
        Map<String, String> hash = store.getHash(currentDb, key);
        if (hash == null) return nullBulk();
        String val = hash.get(args.get(2));
        return val != null ? bulkString(val) : nullBulk();
    }

    private RedisMessage handleHset(List<String> args) {
        if (args.size() < 4 || (args.size() - 2) % 2 != 0) return error("wrong number of arguments for 'hset' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"hash".equals(type)) return wrongType();
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 2; i < args.size(); i += 2) {
            fields.put(args.get(i), args.get(i + 1));
        }
        int added = store.setHashFields(currentDb, key, fields);
        return integer(added);
    }

    private RedisMessage handleHmget(List<String> args) {
        if (args.size() < 3) return error("wrong number of arguments for 'hmget' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"hash".equals(type)) return wrongType();
        Map<String, String> hash = store.getHash(currentDb, key);
        List<RedisMessage> results = new ArrayList<>();
        for (int i = 2; i < args.size(); i++) {
            String val = hash != null ? hash.get(args.get(i)) : null;
            results.add(val != null ? bulkString(val) : nullBulk());
        }
        return array(results);
    }

    private RedisMessage handleHdel(List<String> args) {
        if (args.size() < 3) return error("wrong number of arguments for 'hdel' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"hash".equals(type)) return wrongType();
        int removed = store.removeHashFields(currentDb, key, args.subList(2, args.size()));
        return integer(removed);
    }

    private RedisMessage handleHexists(List<String> args) {
        if (args.size() < 3) return error("wrong number of arguments for 'hexists' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"hash".equals(type)) return wrongType();
        Map<String, String> hash = store.getHash(currentDb, key);
        return integer(hash != null && hash.containsKey(args.get(2)) ? 1 : 0);
    }

    private RedisMessage handleHgetall(List<String> args) {
        if (args.size() < 2) return error("wrong number of arguments for 'hgetall' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"hash".equals(type)) return wrongType();
        Map<String, String> hash = store.getHash(currentDb, key);
        List<RedisMessage> results = new ArrayList<>();
        if (hash != null) {
            for (var entry : hash.entrySet()) {
                results.add(bulkString(entry.getKey()));
                results.add(bulkString(entry.getValue()));
            }
        }
        return array(results);
    }

    private RedisMessage handleHkeys(List<String> args) {
        if (args.size() < 2) return error("wrong number of arguments for 'hkeys' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"hash".equals(type)) return wrongType();
        Map<String, String> hash = store.getHash(currentDb, key);
        List<RedisMessage> results = new ArrayList<>();
        if (hash != null) {
            for (String k : hash.keySet()) results.add(bulkString(k));
        }
        return array(results);
    }

    private RedisMessage handleHvals(List<String> args) {
        if (args.size() < 2) return error("wrong number of arguments for 'hvals' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"hash".equals(type)) return wrongType();
        Map<String, String> hash = store.getHash(currentDb, key);
        List<RedisMessage> results = new ArrayList<>();
        if (hash != null) {
            for (String v : hash.values()) results.add(bulkString(v));
        }
        return array(results);
    }

    private RedisMessage handleHlen(List<String> args) {
        if (args.size() < 2) return error("wrong number of arguments for 'hlen' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"hash".equals(type)) return wrongType();
        Map<String, String> hash = store.getHash(currentDb, key);
        return integer(hash != null ? hash.size() : 0);
    }

    private RedisMessage handleHincrby(List<String> args) {
        if (args.size() < 4) return error("wrong number of arguments for 'hincrby' command");
        String key = args.get(1);
        String field = args.get(2);
        String type = store.type(currentDb, key);
        if (type != null && !"hash".equals(type)) return wrongType();
        long delta;
        try { delta = Long.parseLong(args.get(3)); } catch (NumberFormatException e) { return error("value is not an integer or out of range"); }
        Map<String, String> hash = store.getHash(currentDb, key);
        long val = 0;
        if (hash != null && hash.containsKey(field)) {
            try { val = Long.parseLong(hash.get(field)); } catch (NumberFormatException e) { return error("hash value is not an integer"); }
        }
        val += delta;
        Map<String, String> update = new LinkedHashMap<>();
        update.put(field, String.valueOf(val));
        store.setHashFields(currentDb, key, update);
        return integer(val);
    }

    // =========================================================================
    // List commands (T016)
    // =========================================================================

    private RedisMessage handleLpush(List<String> args) {
        if (args.size() < 3) return error("wrong number of arguments for 'lpush' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"list".equals(type)) return wrongType();
        long len = store.listPush(currentDb, key, args.subList(2, args.size()), true);
        return integer(len);
    }

    private RedisMessage handleRpush(List<String> args) {
        if (args.size() < 3) return error("wrong number of arguments for 'rpush' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"list".equals(type)) return wrongType();
        long len = store.listPush(currentDb, key, args.subList(2, args.size()), false);
        return integer(len);
    }

    private RedisMessage handleLpop(List<String> args) {
        if (args.size() < 2) return error("wrong number of arguments for 'lpop' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"list".equals(type)) return wrongType();
        String val = store.listPop(currentDb, key, true);
        return val != null ? bulkString(val) : nullBulk();
    }

    private RedisMessage handleRpop(List<String> args) {
        if (args.size() < 2) return error("wrong number of arguments for 'rpop' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"list".equals(type)) return wrongType();
        String val = store.listPop(currentDb, key, false);
        return val != null ? bulkString(val) : nullBulk();
    }

    private RedisMessage handleLrange(List<String> args) {
        if (args.size() < 4) return error("wrong number of arguments for 'lrange' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"list".equals(type)) return wrongType();
        int start, stop;
        try {
            start = Integer.parseInt(args.get(2));
            stop = Integer.parseInt(args.get(3));
        } catch (NumberFormatException e) { return error("value is not an integer or out of range"); }
        List<String> range = store.listRange(currentDb, key, start, stop);
        List<RedisMessage> results = new ArrayList<>();
        for (String v : range) results.add(bulkString(v));
        return array(results);
    }

    private RedisMessage handleLlen(List<String> args) {
        if (args.size() < 2) return error("wrong number of arguments for 'llen' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"list".equals(type)) return wrongType();
        return integer(store.listLen(currentDb, key));
    }

    private RedisMessage handleLindex(List<String> args) {
        if (args.size() < 3) return error("wrong number of arguments for 'lindex' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"list".equals(type)) return wrongType();
        int index;
        try { index = Integer.parseInt(args.get(2)); } catch (NumberFormatException e) { return error("value is not an integer or out of range"); }
        String val = store.listIndex(currentDb, key, index);
        return val != null ? bulkString(val) : nullBulk();
    }

    private RedisMessage handleLset(List<String> args) {
        if (args.size() < 4) return error("wrong number of arguments for 'lset' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"list".equals(type)) return wrongType();
        int index;
        try { index = Integer.parseInt(args.get(2)); } catch (NumberFormatException e) { return error("value is not an integer or out of range"); }
        if (!store.exists(currentDb, key)) return error("no such key");
        boolean set = store.listSet(currentDb, key, index, args.get(3));
        return set ? ok() : error("index out of range");
    }

    private RedisMessage handleLtrim(List<String> args) {
        if (args.size() < 4) return error("wrong number of arguments for 'ltrim' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"list".equals(type)) return wrongType();
        int start, stop;
        try {
            start = Integer.parseInt(args.get(2));
            stop = Integer.parseInt(args.get(3));
        } catch (NumberFormatException e) { return error("value is not an integer or out of range"); }
        store.listTrim(currentDb, key, start, stop);
        return ok();
    }

    // =========================================================================
    // Set commands (T017)
    // =========================================================================

    private RedisMessage handleSadd(List<String> args) {
        if (args.size() < 3) return error("wrong number of arguments for 'sadd' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"set".equals(type)) return wrongType();
        int added = store.addSetMembers(currentDb, key, args.subList(2, args.size()));
        return integer(added);
    }

    private RedisMessage handleSrem(List<String> args) {
        if (args.size() < 3) return error("wrong number of arguments for 'srem' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"set".equals(type)) return wrongType();
        int removed = store.removeSetMembers(currentDb, key, args.subList(2, args.size()));
        return integer(removed);
    }

    private RedisMessage handleSmembers(List<String> args) {
        if (args.size() < 2) return error("wrong number of arguments for 'smembers' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"set".equals(type)) return wrongType();
        Set<String> members = store.getSetMembers(currentDb, key);
        List<RedisMessage> results = new ArrayList<>();
        if (members != null) {
            for (String m : members) results.add(bulkString(m));
        }
        return array(results);
    }

    private RedisMessage handleScard(List<String> args) {
        if (args.size() < 2) return error("wrong number of arguments for 'scard' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"set".equals(type)) return wrongType();
        Set<String> members = store.getSetMembers(currentDb, key);
        return integer(members != null ? members.size() : 0);
    }

    private RedisMessage handleSismember(List<String> args) {
        if (args.size() < 3) return error("wrong number of arguments for 'sismember' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"set".equals(type)) return wrongType();
        Set<String> members = store.getSetMembers(currentDb, key);
        return integer(members != null && members.contains(args.get(2)) ? 1 : 0);
    }

    private RedisMessage handleSinter(List<String> args) {
        if (args.size() < 2) return error("wrong number of arguments for 'sinter' command");
        Set<String> result = null;
        for (int i = 1; i < args.size(); i++) {
            String type = store.type(currentDb, args.get(i));
            if (type != null && !"set".equals(type)) return wrongType();
            Set<String> members = store.getSetMembers(currentDb, args.get(i));
            if (members == null) members = Set.of();
            if (result == null) {
                result = new HashSet<>(members);
            } else {
                result.retainAll(members);
            }
        }
        List<RedisMessage> results = new ArrayList<>();
        if (result != null) {
            for (String m : result) results.add(bulkString(m));
        }
        return array(results);
    }

    private RedisMessage handleSunion(List<String> args) {
        if (args.size() < 2) return error("wrong number of arguments for 'sunion' command");
        Set<String> result = new HashSet<>();
        for (int i = 1; i < args.size(); i++) {
            String type = store.type(currentDb, args.get(i));
            if (type != null && !"set".equals(type)) return wrongType();
            Set<String> members = store.getSetMembers(currentDb, args.get(i));
            if (members != null) result.addAll(members);
        }
        List<RedisMessage> results = new ArrayList<>();
        for (String m : result) results.add(bulkString(m));
        return array(results);
    }

    private RedisMessage handleSdiff(List<String> args) {
        if (args.size() < 2) return error("wrong number of arguments for 'sdiff' command");
        Set<String> result = null;
        for (int i = 1; i < args.size(); i++) {
            String type = store.type(currentDb, args.get(i));
            if (type != null && !"set".equals(type)) return wrongType();
            Set<String> members = store.getSetMembers(currentDb, args.get(i));
            if (members == null) members = Set.of();
            if (result == null) {
                result = new HashSet<>(members);
            } else {
                result.removeAll(members);
            }
        }
        List<RedisMessage> results = new ArrayList<>();
        if (result != null) {
            for (String m : result) results.add(bulkString(m));
        }
        return array(results);
    }

    // =========================================================================
    // Sorted set commands (T018)
    // =========================================================================

    private RedisMessage handleZadd(List<String> args) {
        if (args.size() < 4 || (args.size() - 2) % 2 != 0) return error("wrong number of arguments for 'zadd' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"zset".equals(type)) return wrongType();
        List<MemorystoreStore.SortedSetEntry> entries = new ArrayList<>();
        for (int i = 2; i < args.size(); i += 2) {
            double score;
            try { score = Double.parseDouble(args.get(i)); } catch (NumberFormatException e) { return error("value is not a valid float"); }
            entries.add(new MemorystoreStore.SortedSetEntry(args.get(i + 1), score));
        }
        int added = store.addSortedSetMembers(currentDb, key, entries);
        return integer(added);
    }

    private RedisMessage handleZrem(List<String> args) {
        if (args.size() < 3) return error("wrong number of arguments for 'zrem' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"zset".equals(type)) return wrongType();
        int removed = store.removeSortedSetMembers(currentDb, key, args.subList(2, args.size()));
        return integer(removed);
    }

    private RedisMessage handleZrange(List<String> args) {
        if (args.size() < 4) return error("wrong number of arguments for 'zrange' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"zset".equals(type)) return wrongType();
        int start, stop;
        try {
            start = Integer.parseInt(args.get(2));
            stop = Integer.parseInt(args.get(3));
        } catch (NumberFormatException e) { return error("value is not an integer or out of range"); }
        boolean withScores = args.size() > 4 && "WITHSCORES".equalsIgnoreCase(args.get(4));

        List<MemorystoreStore.SortedSetEntry> entries = store.getSortedSet(currentDb, key);
        if (entries == null) return array(List.of());

        int len = entries.size();
        if (start < 0) start = len + start;
        if (stop < 0) stop = len + stop;
        if (start < 0) start = 0;
        if (stop >= len) stop = len - 1;

        List<RedisMessage> results = new ArrayList<>();
        for (int i = start; i <= stop && i < len; i++) {
            results.add(bulkString(entries.get(i).member()));
            if (withScores) results.add(bulkString(formatDouble(entries.get(i).score())));
        }
        return array(results);
    }

    private RedisMessage handleZrangebyscore(List<String> args) {
        if (args.size() < 4) return error("wrong number of arguments for 'zrangebyscore' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"zset".equals(type)) return wrongType();
        double min = parseScoreBound(args.get(2), Double.NEGATIVE_INFINITY);
        double max = parseScoreBound(args.get(3), Double.POSITIVE_INFINITY);
        boolean withScores = args.size() > 4 && "WITHSCORES".equalsIgnoreCase(args.get(4));

        List<MemorystoreStore.SortedSetEntry> entries = store.getSortedSet(currentDb, key);
        if (entries == null) return array(List.of());

        List<RedisMessage> results = new ArrayList<>();
        for (var e : entries) {
            if (e.score() >= min && e.score() <= max) {
                results.add(bulkString(e.member()));
                if (withScores) results.add(bulkString(formatDouble(e.score())));
            }
        }
        return array(results);
    }

    private RedisMessage handleZcard(List<String> args) {
        if (args.size() < 2) return error("wrong number of arguments for 'zcard' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"zset".equals(type)) return wrongType();
        List<MemorystoreStore.SortedSetEntry> entries = store.getSortedSet(currentDb, key);
        return integer(entries != null ? entries.size() : 0);
    }

    private RedisMessage handleZscore(List<String> args) {
        if (args.size() < 3) return error("wrong number of arguments for 'zscore' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"zset".equals(type)) return wrongType();
        List<MemorystoreStore.SortedSetEntry> entries = store.getSortedSet(currentDb, key);
        if (entries == null) return nullBulk();
        for (var e : entries) {
            if (e.member().equals(args.get(2))) {
                return bulkString(formatDouble(e.score()));
            }
        }
        return nullBulk();
    }

    private RedisMessage handleZrank(List<String> args) {
        if (args.size() < 3) return error("wrong number of arguments for 'zrank' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"zset".equals(type)) return wrongType();
        List<MemorystoreStore.SortedSetEntry> entries = store.getSortedSet(currentDb, key);
        if (entries == null) return nullBulk();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).member().equals(args.get(2))) {
                return integer(i);
            }
        }
        return nullBulk();
    }

    private RedisMessage handleZcount(List<String> args) {
        if (args.size() < 4) return error("wrong number of arguments for 'zcount' command");
        String key = args.get(1);
        String type = store.type(currentDb, key);
        if (type != null && !"zset".equals(type)) return wrongType();
        double min = parseScoreBound(args.get(2), Double.NEGATIVE_INFINITY);
        double max = parseScoreBound(args.get(3), Double.POSITIVE_INFINITY);
        List<MemorystoreStore.SortedSetEntry> entries = store.getSortedSet(currentDb, key);
        if (entries == null) return integer(0);
        long count = entries.stream().filter(e -> e.score() >= min && e.score() <= max).count();
        return integer(count);
    }

    // =========================================================================
    // RESP response helpers
    // =========================================================================

    private static SimpleStringRedisMessage ok() {
        return new SimpleStringRedisMessage("OK");
    }

    private static ErrorRedisMessage error(String msg) {
        return new ErrorRedisMessage("ERR " + msg);
    }

    private static ErrorRedisMessage wrongType() {
        return new ErrorRedisMessage("WRONGTYPE Operation against a key holding the wrong kind of value");
    }

    private static IntegerRedisMessage integer(long n) {
        return new IntegerRedisMessage(n);
    }

    private static FullBulkStringRedisMessage bulkString(String s) {
        if (s == null) return nullBulk();
        ByteBuf buf = Unpooled.copiedBuffer(s, StandardCharsets.UTF_8);
        return new FullBulkStringRedisMessage(buf);
    }

    private static FullBulkStringRedisMessage nullBulk() {
        return FullBulkStringRedisMessage.NULL_INSTANCE;
    }

    private static ArrayRedisMessage array(List<RedisMessage> list) {
        return new ArrayRedisMessage(list);
    }

    // =========================================================================
    // Utility methods
    // =========================================================================

    private static List<String> extractArgs(ArrayRedisMessage msg) {
        List<String> args = new ArrayList<>();
        for (RedisMessage child : msg.children()) {
            if (child instanceof FullBulkStringRedisMessage bulk) {
                if (bulk.isNull()) {
                    args.add("");
                } else {
                    args.add(bulk.content().toString(StandardCharsets.UTF_8));
                }
            }
        }
        return args;
    }

    private static double parseScoreBound(String s, double defaultInf) {
        if ("-inf".equalsIgnoreCase(s)) return Double.NEGATIVE_INFINITY;
        if ("+inf".equalsIgnoreCase(s) || "inf".equalsIgnoreCase(s)) return Double.POSITIVE_INFINITY;
        // Exclusive bound with '(' prefix
        if (s.startsWith("(")) {
            try {
                return Double.parseDouble(s.substring(1));
            } catch (NumberFormatException e) {
                return defaultInf;
            }
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return defaultInf;
        }
    }

    private static String formatDouble(double d) {
        if (d == (long) d) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    private static long parseLongSafe(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }

    // =========================================================================
    // Pub/Sub commands
    // =========================================================================

    private RedisMessage handleSubscribe(List<String> args) {
        // SUBSCRIBE - return success but don't track (simplified for compatibility)
        if (args.size() < 2) return error("wrong number of arguments for 'subscribe' command");
        return array(List.of(simpleString("subscribed"), simpleString(args.get(1)), integer(1)));
    }

    private RedisMessage handlePSubscribe(List<String> args) {
        // PSUBSCRIBE - return success but don't track (simplified)
        if (args.size() < 2) return error("wrong number of arguments for 'psubscribe' command");
        return array(List.of(simpleString("psubscribed"), simpleString(args.get(1)), integer(1)));
    }

    private RedisMessage handleUnsubscribe(List<String> args) {
        return array(List.of());
    }

    private RedisMessage handlePUnsubscribe(List<String> args) {
        return array(List.of());
    }

    private RedisMessage handlePublish(List<String> args) {
        if (args.size() < 3) return error("wrong number of arguments for 'publish' command");
        // PUBLISH - simply return 0 subs for now (pub/sub routing needs connection tracking)
        return integer(0);
    }

    private RedisMessage handlePubSub(List<String> args) {
        if (args.size() < 2) return error("wrong number of arguments for 'pubsub' command");
        String subCmd = args.get(1).toUpperCase();
        if (subCmd.equals("NUMSUB")) {
            return array(List.of());
        } else if (subCmd.equals("CHANNELS")) {
            return array(List.of());
        }
        return error("PUBSUB subcommand must be NUMSUB or CHANNELS");
    }

    private RedisMessage simpleString(String s) {
        return new SimpleStringRedisMessage(s);
    }
}
