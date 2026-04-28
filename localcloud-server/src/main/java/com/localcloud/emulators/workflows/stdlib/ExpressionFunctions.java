package com.localcloud.emulators.workflows.stdlib;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ExpressionFunctions {
    public static void register(StdlibRegistry registry) {
        registry.register("default", args -> {
            if (args.size() < 2) throw new RuntimeException("default requires (value, default_value)");
            return args.get(0) != null ? args.get(0) : args.get(1);
        });

        registry.register("if", args -> {
            if (args.size() < 3) throw new RuntimeException("if requires (condition, true_value, false_value)");
            return isTruthy(args.get(0)) ? args.get(1) : args.get(2);
        });

        registry.register("keys", args -> {
            if (args.isEmpty() || !(args.get(0) instanceof Map<?, ?> map)) {
                throw new RuntimeException("keys requires a map");
            }
            return new ArrayList<>(map.keySet());
        });

        registry.register("get_type", args -> {
            if (args.isEmpty()) throw new RuntimeException("get_type requires an argument");
            Object value = args.get(0);
            if (value == null) return "null";
            if (value instanceof Boolean) return "boolean";
            if (value instanceof Integer || value instanceof Long) return "integer";
            if (value instanceof Number) return "double";
            if (value instanceof String) return "string";
            if (value instanceof List<?>) return "list";
            if (value instanceof Map<?, ?>) return "map";
            return value.getClass().getSimpleName();
        });
    }

    private static boolean isTruthy(Object val) {
        if (val instanceof Boolean b) return b;
        if (val == null) return false;
        if (val instanceof Number n) return n.doubleValue() != 0;
        if (val instanceof String s) return !s.isEmpty();
        if (val instanceof List<?> l) return !l.isEmpty();
        if (val instanceof Map<?, ?> m) return !m.isEmpty();
        return true;
    }
}
