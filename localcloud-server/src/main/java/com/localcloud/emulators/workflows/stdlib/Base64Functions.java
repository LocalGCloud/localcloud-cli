package com.localcloud.emulators.workflows.stdlib;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

public class Base64Functions {
    public static void register(StdlibRegistry registry) {
        registry.register("base64.encode", args -> {
            if (args.isEmpty()) throw new RuntimeException("base64.encode requires an argument");
            String input = String.valueOf(args.get(0));
            return Base64.getEncoder().encodeToString(input.getBytes(StandardCharsets.UTF_8));
        });

        registry.register("base64.decode", args -> {
            if (args.isEmpty()) throw new RuntimeException("base64.decode requires an argument");
            String input = String.valueOf(args.get(0));
            try { return new String(Base64.getDecoder().decode(input), StandardCharsets.UTF_8); }
            catch (IllegalArgumentException e) { throw new RuntimeException("base64.decode failed: invalid Base64 input"); }
        });
    }
}
