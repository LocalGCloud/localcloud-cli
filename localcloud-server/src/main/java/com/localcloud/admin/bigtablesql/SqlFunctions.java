package com.localcloud.admin.bigtablesql;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Function registry for Bigtable SQL with ~50 GoogleSQL-compatible functions.
 * Covers string, math, timestamp, and conditional function families.
 */
public final class SqlFunctions {

    @FunctionalInterface
    public interface SqlFunction {
        Object apply(List<Object> args);
    }

    private static final Map<String, SqlFunction> REGISTRY = new LinkedHashMap<>();

    private static final Set<String> AGGREGATES = Set.of(
            "COUNT", "SUM", "AVG", "MIN", "MAX", "STRING_AGG", "ARRAY_AGG", "COUNT_DISTINCT");

    private static final int MAX_PATTERN_LENGTH = 1000;
    private static final int MAX_FORMAT_OUTPUT = 10000;
    private static final int MAX_STRING_REPEAT = 100000;

    static {
        // ─── String functions ────────────────────────────────────────────

        register("CONCAT", args -> {
            StringBuilder sb = new StringBuilder();
            for (Object arg : args) {
                if (arg == null) return null; // GoogleSQL: CONCAT returns NULL if any arg is NULL
                sb.append(SqlTypes.toString(arg));
            }
            return sb.toString();
        });

        register("UPPER", args -> {
            requireArgs("UPPER", args, 1);
            if (args.get(0) == null) return null;
            return SqlTypes.toString(args.get(0)).toUpperCase();
        });

        register("LOWER", args -> {
            requireArgs("LOWER", args, 1);
            if (args.get(0) == null) return null;
            return SqlTypes.toString(args.get(0)).toLowerCase();
        });

        register("LENGTH", args -> {
            requireArgs("LENGTH", args, 1);
            if (args.get(0) == null) return null;
            return (long) SqlTypes.toString(args.get(0)).length();
        });

        register("BYTE_LENGTH", args -> {
            requireArgs("BYTE_LENGTH", args, 1);
            if (args.get(0) == null) return null;
            return (long) SqlTypes.toString(args.get(0)).getBytes(StandardCharsets.UTF_8).length;
        });

        register("SUBSTR", args -> {
            // SUBSTR(s, pos [, len]) — 1-based indexing
            requireMinArgs("SUBSTR", args, 2);
            if (args.get(0) == null) return null;
            String s = SqlTypes.toString(args.get(0));
            int pos = (int) SqlTypes.toInt64(args.get(1));
            // GoogleSQL: pos is 1-based; 0 is treated as 1
            int start = Math.max(pos - 1, 0);
            if (start >= s.length()) return "";
            if (args.size() >= 3) {
                int len = (int) SqlTypes.toInt64(args.get(2));
                if (len < 0) throw new BigtableSqlException("SUBSTR: length must be non-negative");
                int end = Math.min(start + len, s.length());
                return s.substring(start, end);
            }
            return s.substring(start);
        });

        register("TRIM", args -> {
            requireArgs("TRIM", args, 1);
            if (args.get(0) == null) return null;
            return SqlTypes.toString(args.get(0)).strip();
        });

        register("LTRIM", args -> {
            requireArgs("LTRIM", args, 1);
            if (args.get(0) == null) return null;
            return SqlTypes.toString(args.get(0)).stripLeading();
        });

        register("RTRIM", args -> {
            requireArgs("RTRIM", args, 1);
            if (args.get(0) == null) return null;
            return SqlTypes.toString(args.get(0)).stripTrailing();
        });

        register("REPLACE", args -> {
            // REPLACE(s, old, new)
            requireArgs("REPLACE", args, 3);
            if (args.get(0) == null) return null;
            String s = SqlTypes.toString(args.get(0));
            String oldStr = SqlTypes.toString(args.get(1));
            String newStr = SqlTypes.toString(args.get(2));
            return s.replace(oldStr, newStr);
        });

        register("REVERSE", args -> {
            requireArgs("REVERSE", args, 1);
            if (args.get(0) == null) return null;
            return new StringBuilder(SqlTypes.toString(args.get(0))).reverse().toString();
        });

        register("STARTS_WITH", args -> {
            requireArgs("STARTS_WITH", args, 2);
            if (args.get(0) == null || args.get(1) == null) return null;
            return SqlTypes.toString(args.get(0)).startsWith(SqlTypes.toString(args.get(1)));
        });

        register("ENDS_WITH", args -> {
            requireArgs("ENDS_WITH", args, 2);
            if (args.get(0) == null || args.get(1) == null) return null;
            return SqlTypes.toString(args.get(0)).endsWith(SqlTypes.toString(args.get(1)));
        });

        register("LPAD", args -> {
            // LPAD(s, len, pad)
            requireMinArgs("LPAD", args, 2);
            if (args.get(0) == null) return null;
            String s = SqlTypes.toString(args.get(0));
            int targetLen = (int) SqlTypes.toInt64(args.get(1));
            if (targetLen > MAX_STRING_REPEAT) throw new BigtableSqlException("LPAD: target length exceeds maximum of " + MAX_STRING_REPEAT);
            String pad = args.size() >= 3 ? SqlTypes.toString(args.get(2)) : " ";
            if (pad.isEmpty()) return s;
            if (s.length() >= targetLen) return s.substring(0, targetLen);
            StringBuilder sb = new StringBuilder();
            while (sb.length() + s.length() < targetLen) {
                sb.append(pad);
            }
            // Trim excess padding
            String padding = sb.substring(0, targetLen - s.length());
            return padding + s;
        });

        register("RPAD", args -> {
            // RPAD(s, len, pad)
            requireMinArgs("RPAD", args, 2);
            if (args.get(0) == null) return null;
            String s = SqlTypes.toString(args.get(0));
            int targetLen = (int) SqlTypes.toInt64(args.get(1));
            if (targetLen > MAX_STRING_REPEAT) throw new BigtableSqlException("RPAD: target length exceeds maximum of " + MAX_STRING_REPEAT);
            String pad = args.size() >= 3 ? SqlTypes.toString(args.get(2)) : " ";
            if (pad.isEmpty()) return s;
            if (s.length() >= targetLen) return s.substring(0, targetLen);
            StringBuilder sb = new StringBuilder(s);
            while (sb.length() < targetLen) {
                sb.append(pad);
            }
            return sb.substring(0, targetLen);
        });

        register("REPEAT", args -> {
            // REPEAT(s, count)
            requireArgs("REPEAT", args, 2);
            if (args.get(0) == null) return null;
            String s = SqlTypes.toString(args.get(0));
            int count = (int) SqlTypes.toInt64(args.get(1));
            if (count < 0) throw new BigtableSqlException("REPEAT: count must be non-negative");
            if (count > MAX_STRING_REPEAT) throw new BigtableSqlException("REPEAT: count exceeds maximum of " + MAX_STRING_REPEAT);
            return s.repeat(count);
        });

        register("REGEXP_CONTAINS", args -> {
            requireArgs("REGEXP_CONTAINS", args, 2);
            if (args.get(0) == null || args.get(1) == null) return null;
            String s = SqlTypes.toString(args.get(0));
            String pattern = SqlTypes.toString(args.get(1));
            return compilePattern("REGEXP_CONTAINS", pattern).matcher(s).find();
        });

        register("REGEXP_EXTRACT", args -> {
            // REGEXP_EXTRACT(s, pattern) — returns first capturing group or full match
            requireArgs("REGEXP_EXTRACT", args, 2);
            if (args.get(0) == null || args.get(1) == null) return null;
            String s = SqlTypes.toString(args.get(0));
            String pattern = SqlTypes.toString(args.get(1));
            Matcher m = compilePattern("REGEXP_EXTRACT", pattern).matcher(s);
            if (!m.find()) return null;
            // Return first capturing group if present, otherwise full match
            return m.groupCount() > 0 ? m.group(1) : m.group(0);
        });

        register("REGEXP_REPLACE", args -> {
            // REGEXP_REPLACE(s, pattern, replacement)
            requireArgs("REGEXP_REPLACE", args, 3);
            if (args.get(0) == null) return null;
            String s = SqlTypes.toString(args.get(0));
            String pattern = SqlTypes.toString(args.get(1));
            String replacement = SqlTypes.toString(args.get(2));
            return compilePattern("REGEXP_REPLACE", pattern).matcher(s).replaceAll(replacement);
        });

        register("TO_HEX", args -> {
            requireArgs("TO_HEX", args, 1);
            if (args.get(0) == null) return null;
            byte[] bytes;
            if (args.get(0) instanceof byte[] b) {
                bytes = b;
            } else {
                bytes = SqlTypes.toString(args.get(0)).getBytes(StandardCharsets.UTF_8);
            }
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        });

        register("FROM_HEX", args -> {
            requireArgs("FROM_HEX", args, 1);
            if (args.get(0) == null) return null;
            String hex = SqlTypes.toString(args.get(0));
            if (hex.length() % 2 != 0) {
                throw new BigtableSqlException("FROM_HEX: hex string must have even length");
            }
            byte[] bytes = new byte[hex.length() / 2];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        });

        register("TO_BASE64", args -> {
            requireArgs("TO_BASE64", args, 1);
            if (args.get(0) == null) return null;
            byte[] bytes;
            if (args.get(0) instanceof byte[] b) {
                bytes = b;
            } else {
                bytes = SqlTypes.toString(args.get(0)).getBytes(StandardCharsets.UTF_8);
            }
            return Base64.getEncoder().encodeToString(bytes);
        });

        register("FROM_BASE64", args -> {
            requireArgs("FROM_BASE64", args, 1);
            if (args.get(0) == null) return null;
            String encoded = SqlTypes.toString(args.get(0));
            byte[] decoded = Base64.getDecoder().decode(encoded);
            return new String(decoded, StandardCharsets.UTF_8);
        });

        register("FORMAT", args -> {
            // FORMAT('%s %d', a, b) — simplified Java-style formatting
            requireMinArgs("FORMAT", args, 1);
            if (args.get(0) == null) return null;
            String fmt = SqlTypes.toString(args.get(0));
            Object[] fmtArgs = new Object[args.size() - 1];
            for (int i = 1; i < args.size(); i++) {
                fmtArgs[i - 1] = args.get(i) == null ? "NULL" : args.get(i);
            }
            try {
                String result = String.format(fmt, fmtArgs);
                if (result.length() > MAX_FORMAT_OUTPUT) {
                    return result.substring(0, MAX_FORMAT_OUTPUT);
                }
                return result;
            } catch (java.util.IllegalFormatException e) {
                throw new BigtableSqlException("FORMAT error: " + e.getMessage());
            } catch (Exception e) {
                throw new BigtableSqlException("FORMAT error: " + e.getMessage());
            }
        });

        // ─── Math functions ──────────────────────────────────────────────

        register("ABS", args -> {
            requireArgs("ABS", args, 1);
            if (args.get(0) == null) return null;
            double v = SqlTypes.toFloat64(args.get(0));
            // Return integer type if input was integer
            if (isIntegerValue(args.get(0))) return Math.abs(SqlTypes.toInt64(args.get(0)));
            return Math.abs(v);
        });

        SqlFunction ceilFn = args -> {
            requireArgs("CEIL/CEILING", args, 1);
            if (args.get(0) == null) return null;
            return Math.ceil(SqlTypes.toFloat64(args.get(0)));
        };
        register("CEIL", ceilFn);
        register("CEILING", ceilFn);

        register("FLOOR", args -> {
            requireArgs("FLOOR", args, 1);
            if (args.get(0) == null) return null;
            return Math.floor(SqlTypes.toFloat64(args.get(0)));
        });

        register("ROUND", args -> {
            // ROUND(x [, digits])
            requireMinArgs("ROUND", args, 1);
            if (args.get(0) == null) return null;
            double v = SqlTypes.toFloat64(args.get(0));
            if (args.size() >= 2) {
                int digits = (int) SqlTypes.toInt64(args.get(1));
                double factor = Math.pow(10, digits);
                return Math.round(v * factor) / factor;
            }
            return (double) Math.round(v);
        });

        register("TRUNC", args -> {
            // TRUNC(x [, digits]) — truncate toward zero
            requireMinArgs("TRUNC", args, 1);
            if (args.get(0) == null) return null;
            double v = SqlTypes.toFloat64(args.get(0));
            if (args.size() >= 2) {
                int digits = (int) SqlTypes.toInt64(args.get(1));
                double factor = Math.pow(10, digits);
                return ((long) (v * factor)) / factor;
            }
            return (double) (long) v;
        });

        register("MOD", args -> {
            // MOD(x, y)
            requireArgs("MOD", args, 2);
            if (args.get(0) == null || args.get(1) == null) return null;
            long x = SqlTypes.toInt64(args.get(0));
            long y = SqlTypes.toInt64(args.get(1));
            if (y == 0) throw new BigtableSqlException("MOD: division by zero");
            return x % y;
        });

        SqlFunction powFn = args -> {
            requireArgs("POW/POWER", args, 2);
            if (args.get(0) == null || args.get(1) == null) return null;
            return Math.pow(SqlTypes.toFloat64(args.get(0)), SqlTypes.toFloat64(args.get(1)));
        };
        register("POW", powFn);
        register("POWER", powFn);

        register("SQRT", args -> {
            requireArgs("SQRT", args, 1);
            if (args.get(0) == null) return null;
            double v = SqlTypes.toFloat64(args.get(0));
            if (v < 0) throw new BigtableSqlException("SQRT: cannot take square root of negative number");
            return Math.sqrt(v);
        });

        register("SIGN", args -> {
            requireArgs("SIGN", args, 1);
            if (args.get(0) == null) return null;
            double v = SqlTypes.toFloat64(args.get(0));
            if (v > 0) return 1L;
            if (v < 0) return -1L;
            return 0L;
        });

        register("GREATEST", args -> {
            requireMinArgs("GREATEST", args, 1);
            Object best = null;
            for (Object arg : args) {
                if (arg == null) continue;
                if (best == null) {
                    best = arg;
                } else {
                    Integer cmp = SqlTypes.compare(arg, best);
                    if (cmp != null && cmp > 0) best = arg;
                }
            }
            return best;
        });

        register("LEAST", args -> {
            requireMinArgs("LEAST", args, 1);
            Object best = null;
            for (Object arg : args) {
                if (arg == null) continue;
                if (best == null) {
                    best = arg;
                } else {
                    Integer cmp = SqlTypes.compare(arg, best);
                    if (cmp != null && cmp < 0) best = arg;
                }
            }
            return best;
        });

        // ─── Timestamp functions ─────────────────────────────────────────

        register("CURRENT_TIMESTAMP", args -> Instant.now().toString());

        register("UNIX_MICROS", args -> {
            // timestamp string to epoch microseconds
            requireArgs("UNIX_MICROS", args, 1);
            if (args.get(0) == null) return null;
            String ts = SqlTypes.toString(args.get(0));
            try {
                Instant inst = Instant.parse(ts);
                return inst.getEpochSecond() * 1_000_000 + inst.getNano() / 1_000;
            } catch (Exception e) {
                throw new BigtableSqlException("UNIX_MICROS: invalid timestamp '" + ts + "'");
            }
        });

        register("TIMESTAMP_MICROS", args -> {
            // epoch microseconds to timestamp string
            requireArgs("TIMESTAMP_MICROS", args, 1);
            if (args.get(0) == null) return null;
            long micros = SqlTypes.toInt64(args.get(0));
            Instant inst = Instant.ofEpochSecond(micros / 1_000_000, (micros % 1_000_000) * 1_000);
            return inst.toString();
        });

        // ─── Conditional functions ───────────────────────────────────────

        register("IF", args -> {
            // IF(cond, true_val, false_val)
            requireArgs("IF", args, 3);
            if (args.get(0) == null) return args.get(2);
            boolean cond = SqlTypes.toBool(args.get(0));
            return cond ? args.get(1) : args.get(2);
        });

        register("IFNULL", args -> {
            // IFNULL(val, default)
            requireArgs("IFNULL", args, 2);
            return args.get(0) != null ? args.get(0) : args.get(1);
        });

        register("NULLIF", args -> {
            // NULLIF(a, b) — returns null if a == b
            requireArgs("NULLIF", args, 2);
            if (args.get(0) == null && args.get(1) == null) return null;
            if (args.get(0) == null || args.get(1) == null) return args.get(0);
            Integer cmp = SqlTypes.compare(args.get(0), args.get(1));
            return (cmp != null && cmp == 0) ? null : args.get(0);
        });

        register("COALESCE", args -> {
            // Return first non-null
            requireMinArgs("COALESCE", args, 1);
            for (Object arg : args) {
                if (arg != null) return arg;
            }
            return null;
        });
    }

    private SqlFunctions() {}

    /**
     * Evaluate a named function with the given arguments.
     */
    public static Object evaluate(String name, List<Object> args) {
        SqlFunction fn = REGISTRY.get(name.toUpperCase());
        if (fn == null) {
            throw new BigtableSqlException("Unknown function: " + name);
        }
        return fn.apply(args);
    }

    /**
     * Check if a function name is registered.
     */
    public static boolean isFunction(String name) {
        return REGISTRY.containsKey(name.toUpperCase());
    }

    /**
     * Check if a function name is an aggregate function.
     */
    public static boolean isAggregate(String name) {
        return AGGREGATES.contains(name.toUpperCase());
    }

    // ─── Internal helpers ────────────────────────────────────────────────

    private static void register(String name, SqlFunction fn) {
        REGISTRY.put(name, fn);
    }

    private static void requireArgs(String name, List<Object> args, int expected) {
        if (args.size() != expected) {
            throw new BigtableSqlException(name + " requires " + expected + " argument(s), got " + args.size());
        }
    }

    private static void requireMinArgs(String name, List<Object> args, int min) {
        if (args.size() < min) {
            throw new BigtableSqlException(name + " requires at least " + min + " argument(s), got " + args.size());
        }
    }

    private static Pattern compilePattern(String funcName, String pattern) {
        if (pattern.length() > MAX_PATTERN_LENGTH) {
            throw new BigtableSqlException(funcName + ": regex pattern exceeds maximum length of " + MAX_PATTERN_LENGTH);
        }
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new BigtableSqlException(funcName + ": invalid regex pattern: " + e.getMessage());
        }
    }

    private static boolean isIntegerValue(Object value) {
        if (value instanceof Long || value instanceof Integer) return true;
        if (value == null) return false;
        String s = String.valueOf(value);
        try {
            Long.parseLong(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
