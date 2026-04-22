package com.localcloud.emulators.workflows.stdlib;

import java.util.*;
import java.util.function.Function;

/**
 * Registry of all standard library functions for Cloud Workflows.
 * Functions are organized by namespace (http, sys, json, base64, math, text, list, map).
 */
public class StdlibRegistry {
    private final Map<String, Function<List<Object>, Object>> functions = new HashMap<>();

    public StdlibRegistry() {
        HttpFunctions.register(this);
        SysFunctions.register(this);
        JsonFunctions.register(this);
        Base64Functions.register(this);
        MathFunctions.register(this);
        TextFunctions.register(this);
        ListFunctions.register(this);
        MapFunctions.register(this);
        TypeCastFunctions.register(this);
        EventsFunctions.register(this);
        HashFunctions.register(this);
    }

    public void register(String name, Function<List<Object>, Object> func) {
        functions.put(name, func);
    }

    public Function<List<Object>, Object> get(String name) {
        return functions.get(name);
    }

    public boolean has(String name) {
        return functions.containsKey(name);
    }

    public Map<String, Function<List<Object>, Object>> getAll() {
        return Collections.unmodifiableMap(functions);
    }
}
