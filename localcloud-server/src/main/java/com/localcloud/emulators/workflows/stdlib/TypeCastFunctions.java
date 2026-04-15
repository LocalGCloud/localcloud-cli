package com.localcloud.emulators.workflows.stdlib;

import java.util.List;

public class TypeCastFunctions {
    public static void register(StdlibRegistry registry) {
        registry.register("int", args -> {
            if (args.isEmpty()) throw new RuntimeException("int() requires an argument");
            Object val = args.get(0);
            if (val instanceof Integer) return val;
            if (val instanceof Number n) return n.intValue();
            if (val instanceof String s) {
                try { return Integer.parseInt(s); }
                catch (NumberFormatException e) {
                    try { return (int) Double.parseDouble(s); }
                    catch (NumberFormatException e2) { throw new RuntimeException("Cannot convert '" + s + "' to int"); }
                }
            }
            if (val instanceof Boolean b) return b ? 1 : 0;
            throw new RuntimeException("Cannot convert " + val.getClass().getSimpleName() + " to int");
        });

        registry.register("double", args -> {
            if (args.isEmpty()) throw new RuntimeException("double() requires an argument");
            Object val = args.get(0);
            if (val instanceof Double) return val;
            if (val instanceof Number n) return n.doubleValue();
            if (val instanceof String s) {
                try { return Double.parseDouble(s); }
                catch (NumberFormatException e) { throw new RuntimeException("Cannot convert '" + s + "' to double"); }
            }
            if (val instanceof Boolean b) return b ? 1.0 : 0.0;
            throw new RuntimeException("Cannot convert " + val.getClass().getSimpleName() + " to double");
        });

        registry.register("string", args -> {
            if (args.isEmpty()) throw new RuntimeException("string() requires an argument");
            return String.valueOf(args.get(0));
        });
    }
}
