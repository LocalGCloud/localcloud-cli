package com.localcloud.emulators.memorystore;

import java.util.*;
import java.util.regex.*;

/**
 * Simple Lua script executor for Redis EVAL/EVALSHA commands.
 * Supports basic Lua and redis.call() substitution.
 */
public class LuaScriptEngine {

    private final MemorystoreStore store;
    private final Map<String, byte[]> scriptCache = new HashMap<>();

    public LuaScriptEngine(MemorystoreStore store) {
        this.store = store;
    }

    /**
     * Load a script into the cache.
     * @return SHA1 hash of the script
     */
    public String scriptLoad(String script) {
        String sha = Integer.toHexString(script.hashCode());
        scriptCache.put(sha, script.getBytes());
        return sha;
    }

    /**
     * Flush all cached scripts.
     */
    public void scriptFlush() {
        scriptCache.clear();
    }

    /**
     * Execute EVAL with keys and arguments.
     */
    public String eval(String script, int numKeys, List<String> keys, List<String> args) {
        Map<String, String> localVars = new LinkedHashMap<>();
        for (int i = 0; i < numKeys && i < keys.size(); i++) {
            localVars.put("KEYS[" + (i + 1) + "]", keys.get(i));
        }
        for (int i = 0; i < args.size(); i++) {
            localVars.put("ARGV[" + (i + 1) + "]", args.get(i));
        }
        
        return executeScript(script, localVars);
    }

    /**
     * Execute EVALSHA - execute a cached script by SHA.
     */
    public String evalsha(String sha, int numKeys, List<String> keys, List<String> args) {
        byte[] script = scriptCache.get(sha);
        if (script == null) {
            return "-NOSCRIPT No script matching SHA";
        }
        return eval(new String(script), numKeys, keys, args);
    }

    private String executeScript(String script, Map<String, String> vars) {
        try {
            // Replace local variables
            for (Map.Entry<String, String> e : vars.entrySet()) {
                script = script.replace(e.getKey(), "\"" + e.getValue() + "\"");
            }
            
            // Parse and execute the script
            return executeLua(script);
        } catch (Exception e) {
            return "-ERR " + e.getMessage();
        }
    }

    private String executeLua(String script) {
        // Simple Lua interpreter
        // Extract redis.call() commands from the script
        Pattern callPattern = Pattern.compile(
            "redis\\.call\\s*\\(\\s*(\\w+)\\s*,?\\s*(.*?)\\s*\\)",
            Pattern.CASE_INSENSITIVE
        );
        
        List<String> commands = new ArrayList<>();
        int lastEnd = 0;
        
        Matcher matcher = callPattern.matcher(script);
        while (matcher.find()) {
            String before = script.substring(lastEnd, matcher.start()).trim();
            if (!before.isEmpty() && !before.equals("then") && !before.equals("do")) {
                commands.add(before);
            }
            String cmd = matcher.group(1).toUpperCase();
            String args = matcher.group(2);
            commands.add(cmd + " " + args);
            lastEnd = matcher.end();
        }
        
        if (commands.isEmpty()) {
            // No redis.call - evaluate as expression
            return evaluateExpression(script.trim());
        }
        
        // Execute commands
        List<String> results = new ArrayList<>();
        for (String cmd : commands) {
            results.add(executeRedisCommand(cmd.trim()));
        }
        
        if (results.size() == 1) {
            return results.get(0);
        }
        return results.toString();
    }

    private String executeRedisCommand(String cmdLine) {
        String[] parts = parseArgs(cmdLine);
        if (parts.length == 0) return "";
        
        String cmd = parts[0].toUpperCase();
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);
        
        try {
            return switch (cmd) {
                case "GET" -> {
                    String v = store.getString(0, args[0]);
                    yield v != null ? v : "(nil)";
                }
                case "SET" -> {
                    long ttl = args.length > 2 ? Long.parseLong(args[2]) : 0;
                    store.setString(0, args[0], args[1], ttl);
                    yield "OK";
                }
                case "SETEX" -> {
                    long ttl = Long.parseLong(args[1]);
                    store.setString(0, args[0], args[2], ttl);
                    yield "OK";
                }
                case "DEL" -> {
                    int count = store.deleteKeys(0, List.of(args));
                    yield Integer.toString(count);
                }
                case "INCR", "INCRBY" -> {
                    String v = store.getString(0, args[0]);
                    int current = v != null ? Integer.parseInt(v) : 0;
                    int delta = parts.length > 2 ? Integer.parseInt(args[1]) : 1;
                    int newVal = current + delta;
                    store.setString(0, args[0], Integer.toString(newVal), 0);
                    yield Integer.toString(newVal);
                }
                case "DECR", "DECRBY" -> {
                    String v = store.getString(0, args[0]);
                    int current = v != null ? Integer.parseInt(v) : 0;
                    int delta = parts.length > 2 ? Integer.parseInt(args[1]) : 1;
                    int newVal = current - delta;
                    store.setString(0, args[0], Integer.toString(newVal), 0);
                    yield Integer.toString(newVal);
                }
                case "HGET" -> {
                    Map<String, String> h = store.getHash(0, args[0]);
                    yield h != null && h.containsKey(args[1]) ? h.get(args[1]) : "(nil)";
                }
                case "HSET" -> {
                    Map<String, String> field = Map.of(args[1], args.length > 2 ? args[2] : "");
                    int count = store.setHashFields(0, args[0], field);
                    yield Integer.toString(count);
                }
                case "HLEN" -> {
                    Map<String, String> h = store.getHash(0, args[0]);
                    yield Integer.toString(h != null ? h.size() : 0);
                }
                case "LPUSH", "RPUSH" -> {
                    boolean left = cmd.equals("LPUSH");
                    store.listPush(0, args[0], List.of(args[1]), left);
                    yield "OK";
                }
                case "LLEN" -> {
                    List<String> l = store.getList(0, args[0]);
                    yield Integer.toString(l != null ? l.size() : 0);
                }
                case "SADD" -> {
                    int count = store.addSetMembers(0, args[0], 
                        Arrays.asList(Arrays.copyOfRange(args, 1, args.length)));
                    yield Integer.toString(count);
                }
                case "SCARD" -> {
                    Set<String> s = store.getSetMembers(0, args[0]);
                    yield Integer.toString(s != null ? s.size() : 0);
                }
                case "ZADD" -> {
                    List<MemorystoreStore.SortedSetEntry> entries = List.of(
                        new MemorystoreStore.SortedSetEntry(args[1], Double.parseDouble(args[0])));
                    int count = store.addSortedSetMembers(0, args[0], entries);
                    yield Integer.toString(count);
                }
                case "ZCARD" -> {
                    List<MemorystoreStore.SortedSetEntry> z = store.getSortedSet(0, args[0]);
                    yield Integer.toString(z != null ? z.size() : 0);
                }
                case "EXPIRE" -> {
                    boolean result = store.setExpire(0, args[0], Long.parseLong(args[1]));
                    yield result ? "1" : "0";
                }
                case "PERSIST" -> {
                    boolean result = store.persist(0, args[0]);
                    yield result ? "1" : "0";
                }
                case "TYPE" -> {
                    String t = store.type(0, args[0]);
                    yield t != null ? t : "none";
                }
                default -> "(unknown command: " + cmd + ")";
            };
        } catch (Exception e) {
            return "-ERR " + e.getMessage();
        }
    }

    private String[] parseArgs(String cmdLine) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        char quoteChar = '"';
        
        for (int i = 0; i < cmdLine.length(); i++) {
            char c = cmdLine.charAt(i);
            if (!inQuote && (c == '"' || c == '\'')) {
                inQuote = true;
                quoteChar = c;
            } else if (inQuote && c == quoteChar) {
                inQuote = false;
            } else if (!inQuote && c == ' ') {
                if (current.length() > 0) {
                    args.add(current.toString());
                    current = new StringBuilder();
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            args.add(current.toString());
        }
        return args.toArray(new String[0]);
    }

    private String evaluateExpression(String expr) {
        expr = expr.trim();
        try {
            // Simple numeric expression
            if (expr.matches("^-?\\d+$")) {
                return expr;
            }
            // Simple return statement
            if (expr.startsWith("return ")) {
                expr = expr.substring(7).trim();
                if (expr.startsWith("\"")) {
                    return expr.substring(1, expr.length() - 1);
                }
                return evaluateExpression(expr);
            }
            // Handle tonumber
            if (expr.startsWith("tonumber(") && expr.endsWith(")")) {
                String inner = expr.substring(8, expr.length() - 1);
                return evaluateExpression(inner);
            }
        } catch (Exception e) {
            // Fall through
        }
        return "-ERR unable to evaluate expression";
    }
}