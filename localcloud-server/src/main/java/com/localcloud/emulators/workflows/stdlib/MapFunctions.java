package com.localcloud.emulators.workflows.stdlib;

import java.util.*;

public class MapFunctions {
    @SuppressWarnings("unchecked")
    public static void register(StdlibRegistry registry) {
        registry.register("map.get", args -> {
            if (args.size() < 2) throw new RuntimeException("map.get requires (map, key) or (map, key, default)");
            Map<String, Object> map = (Map<String, Object>) args.get(0);
            String key = String.valueOf(args.get(1));
            if (map.containsKey(key)) return map.get(key);
            if (args.size() >= 3) return args.get(2); // default value
            return null;
        });

        registry.register("map.keys", args -> {
            if (args.isEmpty()) throw new RuntimeException("map.keys requires a map");
            Map<String, Object> map = (Map<String, Object>) args.get(0);
            return new ArrayList<>(map.keySet());
        });

        registry.register("map.values", args -> {
            if (args.isEmpty()) throw new RuntimeException("map.values requires a map");
            Map<String, Object> map = (Map<String, Object>) args.get(0);
            return new ArrayList<>(map.values());
        });

        registry.register("map.merge", args -> {
            if (args.size() < 2) throw new RuntimeException("map.merge requires 2 maps");
            Map<String, Object> result = new LinkedHashMap<>((Map<String, Object>) args.get(0));
            result.putAll((Map<String, Object>) args.get(1));
            return result;
        });

        registry.register("map.delete", args -> {
            if (args.size() < 2) throw new RuntimeException("map.delete requires (map, key)");
            Map<String, Object> result = new LinkedHashMap<>((Map<String, Object>) args.get(0));
            result.remove(String.valueOf(args.get(1)));
            return result;
        });

        registry.register("map.merge_nested", args -> {
            if (args.size() < 2) throw new RuntimeException("map.merge_nested requires 2 maps");
            return mergeNested((Map<String, Object>) args.get(0), (Map<String, Object>) args.get(1));
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mergeNested(Map<String, Object> left, Map<String, Object> right) {
        Map<String, Object> result = new LinkedHashMap<>(left);
        for (Map.Entry<String, Object> entry : right.entrySet()) {
            Object existing = result.get(entry.getKey());
            Object incoming = entry.getValue();
            if (existing instanceof Map<?, ?> existingMap && incoming instanceof Map<?, ?> incomingMap) {
                result.put(entry.getKey(), mergeNested((Map<String, Object>) existingMap,
                        (Map<String, Object>) incomingMap));
            } else {
                result.put(entry.getKey(), incoming);
            }
        }
        return result;
    }
}
