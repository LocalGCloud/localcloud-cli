package com.localcloud.emulators.workflows.stdlib;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

public class JsonFunctions {
    private static final ObjectMapper mapper = new ObjectMapper();

    public static void register(StdlibRegistry registry) {
        registry.register("json.decode", args -> {
            if (args.isEmpty()) throw new RuntimeException("json.decode requires a string argument");
            String json = String.valueOf(args.get(0));
            try { return mapper.readValue(json, Object.class); }
            catch (Exception e) { throw new RuntimeException("json.decode failed: " + e.getMessage()); }
        });

        registry.register("json.encode", args -> {
            if (args.isEmpty()) throw new RuntimeException("json.encode requires an argument");
            try { return mapper.writeValueAsString(args.get(0)); }
            catch (Exception e) { throw new RuntimeException("json.encode failed: " + e.getMessage()); }
        });

        registry.register("json.encode_to_string", args -> {
            if (args.isEmpty()) throw new RuntimeException("json.encode_to_string requires an argument");
            try { return mapper.writeValueAsString(args.get(0)); }
            catch (Exception e) { throw new RuntimeException("json.encode_to_string failed: " + e.getMessage()); }
        });
    }
}
