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

        registry.register("math.sqrt", args -> {
            double val = requireNumber(args, "math.sqrt").doubleValue();
            if (val < 0) throw new RuntimeException("math.sqrt requires a non-negative number");
            return Math.sqrt(val);
        });

        registry.register("math.pow", args -> {
            if (args.size() < 2) throw new RuntimeException("math.pow requires (base, exponent)");
            return Math.pow(((Number) args.get(0)).doubleValue(), ((Number) args.get(1)).doubleValue());
        });

        registry.register("math.log", args -> {
            double val = requireNumber(args, "math.log").doubleValue();
            if (val <= 0) throw new RuntimeException("math.log requires a positive number");
            return Math.log(val);
        });

        registry.register("math.add", args -> {
            if (args.size() < 2) throw new RuntimeException("math.add requires (a, b)");
            Number a = (Number) args.get(0), b = (Number) args.get(1);
            if (a instanceof Integer && b instanceof Integer) return a.intValue() + b.intValue();
            return a.doubleValue() + b.doubleValue();
        });

        registry.register("math.sub", args -> {
            if (args.size() < 2) throw new RuntimeException("math.sub requires (a, b)");
            Number a = (Number) args.get(0), b = (Number) args.get(1);
            if (a instanceof Integer && b instanceof Integer) return a.intValue() - b.intValue();
            return a.doubleValue() - b.doubleValue();
        });

        registry.register("math.multiply", args -> {
            if (args.size() < 2) throw new RuntimeException("math.multiply requires (a, b)");
            Number a = (Number) args.get(0), b = (Number) args.get(1);
            if (a instanceof Integer && b instanceof Integer) return a.intValue() * b.intValue();
            return a.doubleValue() * b.doubleValue();
        });

        registry.register("math.divide", args -> {
            if (args.size() < 2) throw new RuntimeException("math.divide requires (a, b)");
            double divisor = ((Number) args.get(1)).doubleValue();
            if (divisor == 0) throw new RuntimeException("math.divide: division by zero");
            return ((Number) args.get(0)).doubleValue() / divisor;
        });
    }

    private static Number requireNumber(List<Object> args, String name) {
        if (args.isEmpty()) throw new RuntimeException(name + " requires a number argument");
        if (!(args.get(0) instanceof Number)) throw new RuntimeException(name + " requires a number, got " + args.get(0).getClass().getSimpleName());
        return (Number) args.get(0);
    }
}
