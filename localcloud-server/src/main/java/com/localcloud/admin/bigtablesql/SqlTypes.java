package com.localcloud.admin.bigtablesql;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Type system for Bigtable SQL values.
 * Supports type inference, casting, safe casting, and type-aware comparison.
 */
public final class SqlTypes {

    public enum SqlType {
        STRING, INT64, FLOAT64, BYTES, BOOL, TIMESTAMP, NULL
    }

    private SqlTypes() {}

    /**
     * Infer the SQL type from a value by inspecting its string representation.
     */
    public static SqlType inferType(Object value) {
        if (value == null) return SqlType.NULL;
        if (value instanceof Boolean) return SqlType.BOOL;
        if (value instanceof Long || value instanceof Integer) return SqlType.INT64;
        if (value instanceof Double || value instanceof Float) return SqlType.FLOAT64;
        if (value instanceof byte[]) return SqlType.BYTES;
        if (value instanceof Instant) return SqlType.TIMESTAMP;

        String s = String.valueOf(value);
        if (s.isEmpty()) return SqlType.STRING;
        if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) return SqlType.BOOL;
        try {
            Long.parseLong(s);
            return SqlType.INT64;
        } catch (NumberFormatException ignored) {}
        try {
            Double.parseDouble(s);
            return SqlType.FLOAT64;
        } catch (NumberFormatException ignored) {}
        return SqlType.STRING;
    }

    /**
     * Cast a value to the target SQL type.
     * Throws BigtableSqlException on invalid conversion.
     */
    public static Object cast(Object value, SqlType target) {
        if (value == null) {
            if (target == SqlType.NULL) return null;
            throw new BigtableSqlException("Cannot CAST NULL to " + target);
        }

        String s = String.valueOf(value);

        return switch (target) {
            case NULL -> null;
            case STRING -> s;
            case INT64 -> {
                try {
                    yield Long.parseLong(s);
                } catch (NumberFormatException e) {
                    // Try parsing as double first, then truncate
                    try {
                        yield (long) Double.parseDouble(s);
                    } catch (NumberFormatException e2) {
                        throw new BigtableSqlException("Cannot CAST '" + s + "' to INT64");
                    }
                }
            }
            case FLOAT64 -> {
                try {
                    yield Double.parseDouble(s);
                } catch (NumberFormatException e) {
                    throw new BigtableSqlException("Cannot CAST '" + s + "' to FLOAT64");
                }
            }
            case BOOL -> {
                if ("true".equalsIgnoreCase(s)) yield Boolean.TRUE;
                if ("false".equalsIgnoreCase(s)) yield Boolean.FALSE;
                // Numeric: 0 = false, non-zero = true
                try {
                    yield Long.parseLong(s) != 0;
                } catch (NumberFormatException ignored) {}
                throw new BigtableSqlException("Cannot CAST '" + s + "' to BOOL");
            }
            case TIMESTAMP -> {
                // Try ISO-8601 format
                try {
                    yield Instant.parse(s);
                } catch (DateTimeParseException ignored) {}
                // Try epoch microseconds
                try {
                    long micros = Long.parseLong(s);
                    yield Instant.ofEpochSecond(micros / 1_000_000, (micros % 1_000_000) * 1_000);
                } catch (NumberFormatException ignored) {}
                throw new BigtableSqlException("Cannot CAST '" + s + "' to TIMESTAMP");
            }
            case BYTES -> {
                if (value instanceof byte[] b) yield b;
                yield s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
        };
    }

    /**
     * Safe cast: returns null on failure instead of throwing.
     */
    public static Object safeCast(Object value, SqlType target) {
        try {
            return cast(value, target);
        } catch (BigtableSqlException e) {
            return null;
        }
    }

    /**
     * Type-aware comparison.
     * Returns -1, 0, or 1 following Comparable conventions, or null if either value is null.
     */
    public static Integer compare(Object a, Object b) {
        if (a == null || b == null) return null;

        // Both numeric: compare as numbers
        Double numA = tryParseDouble(a);
        Double numB = tryParseDouble(b);
        if (numA != null && numB != null) {
            return Double.compare(numA, numB);
        }

        // Both booleans
        if (isBool(a) && isBool(b)) {
            boolean ba = toBool(a);
            boolean bb = toBool(b);
            return Boolean.compare(ba, bb);
        }

        // Default: compare as strings
        String sa = String.valueOf(a);
        String sb = String.valueOf(b);
        int cmp = sa.compareTo(sb);
        return Integer.signum(cmp);
    }

    /**
     * Convert value to long. Throws BigtableSqlException on failure.
     */
    public static long toInt64(Object value) {
        if (value == null) throw new BigtableSqlException("Cannot convert NULL to INT64");
        if (value instanceof Number n) return n.longValue();
        String s = String.valueOf(value);
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            try {
                return (long) Double.parseDouble(s);
            } catch (NumberFormatException e2) {
                throw new BigtableSqlException("Cannot convert '" + s + "' to INT64");
            }
        }
    }

    /**
     * Convert value to double. Throws BigtableSqlException on failure.
     */
    public static double toFloat64(Object value) {
        if (value == null) throw new BigtableSqlException("Cannot convert NULL to FLOAT64");
        if (value instanceof Number n) return n.doubleValue();
        String s = String.valueOf(value);
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new BigtableSqlException("Cannot convert '" + s + "' to FLOAT64");
        }
    }

    /**
     * Convert value to boolean. Throws BigtableSqlException on failure.
     */
    public static boolean toBool(Object value) {
        if (value == null) throw new BigtableSqlException("Cannot convert NULL to BOOL");
        if (value instanceof Boolean b) return b;
        String s = String.valueOf(value);
        if ("true".equalsIgnoreCase(s)) return true;
        if ("false".equalsIgnoreCase(s)) return false;
        try {
            return Long.parseLong(s) != 0;
        } catch (NumberFormatException ignored) {}
        throw new BigtableSqlException("Cannot convert '" + s + "' to BOOL");
    }

    /**
     * Convert value to string. Null returns "NULL".
     */
    public static String toString(Object value) {
        if (value == null) return "NULL";
        if (value instanceof Instant inst) return inst.toString();
        if (value instanceof byte[] bytes) return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        return String.valueOf(value);
    }

    // ─── Internal helpers ────────────────────────────────────────────────

    private static Double tryParseDouble(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isBool(Object value) {
        if (value instanceof Boolean) return true;
        String s = String.valueOf(value);
        return "true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s);
    }
}
