package com.localcloud.emulators.workflows.stdlib;

import java.util.List;

public class MathFunctions {
    public static void register(StdlibRegistry registry) {
        registry.register("math.abs", args -> {
            Number n = requireNumber(args, "math.abs");
            if (n instanceof Integer i) return Math.abs(i);
            return Math.abs(n.doubleValue());
        });
        registry.register("math.ceil", args -> (int) Math.ceil(requireNumber(args, "math.ceil").doubleValue()));
        registry.register("math.floor", args -> (int) Math.floor(requireNumber(args, "math.floor").doubleValue()));
        registry.register("math.round", args -> (int) Math.round(requireNumber(args, "math.round").doubleValue()));
        registry.register("math.max", args -> {
            if (args.size() < 2) throw new RuntimeException("math.max requires 2 arguments");
            double a = ((Number) args.get(0)).doubleValue(), b = ((Number) args.get(1)).doubleValue();
            if (args.get(0) instanceof Integer && args.get(1) instanceof Integer) return Math.max((int) a, (int) b);
            return Math.max(a, b);
        });
        registry.register("math.min", args -> {
            if (args.size() < 2) throw new RuntimeException("math.min requires 2 arguments");
            double a = ((Number) args.get(0)).doubleValue(), b = ((Number) args.get(1)).doubleValue();
            if (args.get(0) instanceof Integer && args.get(1) instanceof Integer) return Math.min((int) a, (int) b);
            return Math.min(a, b);
        });
    }

    private static Number requireNumber(List<Object> args, String name) {
        if (args.isEmpty()) throw new RuntimeException(name + " requires a number argument");
        if (!(args.get(0) instanceof Number)) throw new RuntimeException(name + " requires a number, got " + args.get(0).getClass().getSimpleName());
        return (Number) args.get(0);
    }
}
