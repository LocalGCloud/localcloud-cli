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
    }
}
