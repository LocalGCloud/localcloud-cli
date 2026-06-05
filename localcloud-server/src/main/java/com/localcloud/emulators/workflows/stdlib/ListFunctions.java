package com.localcloud.emulators.workflows.stdlib;

import java.util.*;

public class ListFunctions {
    @SuppressWarnings("unchecked")
    public static void register(StdlibRegistry registry) {
        registry.register("list.concat", args -> {
            if (args.size() < 2) throw new RuntimeException("list.concat requires 2 lists");
            List<Object> result = new ArrayList<>((List<Object>) args.get(0));
            result.addAll((List<Object>) args.get(1));
            return result;
        });

        registry.register("list.prepend", args -> {
            if (args.size() < 2) throw new RuntimeException("list.prepend requires (list, element)");
            List<Object> result = new ArrayList<>();
            result.add(args.get(1));
            result.addAll((List<Object>) args.get(0));
            return result;
        });

        registry.register("list.sort", args -> {
            if (args.isEmpty()) throw new RuntimeException("list.sort requires a list");
            List<Object> list = new ArrayList<>((List<Object>) args.get(0));
            list.sort((a, b) -> {
                if (a instanceof Comparable && b instanceof Comparable) {
                    return ((Comparable) a).compareTo(b);
                }
                return String.valueOf(a).compareTo(String.valueOf(b));
            });
            return list;
        });

        registry.register("len", args -> {
            if (args.isEmpty()) throw new RuntimeException("len requires an argument");
            Object val = args.get(0);
            if (val instanceof List<?> l) return l.size();
            if (val instanceof Map<?, ?> m) return m.size();
            if (val instanceof String s) return s.length();
            throw new RuntimeException("len does not support " + val.getClass().getSimpleName());
        });

        registry.register("list.join", args -> {
            if (args.size() < 2) throw new RuntimeException("list.join requires (list, separator)");
            List<?> list = (List<?>) args.get(0);
            String separator = String.valueOf(args.get(1));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(separator);
                sb.append(list.get(i));
            }
            return sb.toString();
        });

        registry.register("list.remove", args -> {
            if (args.size() < 2) throw new RuntimeException("list.remove requires (list, element)");
            List<Object> result = new ArrayList<>((List<Object>) args.get(0));
            result.remove(args.get(1)); // removes first occurrence
            return result;
        });

        registry.register("list.slice", args -> {
            if (args.size() < 3) throw new RuntimeException("list.slice requires (list, start, end)");
            List<?> list = (List<?>) args.get(0);
            int start = ((Number) args.get(1)).intValue();
            int end = ((Number) args.get(2)).intValue();
            // Clamp to list bounds
            start = Math.max(0, Math.min(start, list.size()));
            end = Math.max(start, Math.min(end, list.size()));
            return new ArrayList<>(list.subList(start, end));
        });

        registry.register("list.has_value", args -> {
            if (args.size() < 2) throw new RuntimeException("list.has_value requires (list, element)");
            List<?> list = (List<?>) args.get(0);
            return list.contains(args.get(1));
        });

        registry.register("list.indexOf", args -> {
            if (args.size() < 2) throw new RuntimeException("list.indexOf requires (list, element)");
            List<?> list = (List<?>) args.get(0);
            return list.indexOf(args.get(1));
        });

        registry.register("list.reverse", args -> {
            if (args.isEmpty()) throw new RuntimeException("list.reverse requires a list");
            List<Object> result = new ArrayList<>((List<Object>) args.get(0));
            java.util.Collections.reverse(result);
            return result;
        });
    }
}
