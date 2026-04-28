package com.localcloud.emulators.workflows.stdlib;

import java.util.UUID;

public class UuidFunctions {
    public static void register(StdlibRegistry registry) {
        registry.register("uuid.generate", args -> UUID.randomUUID().toString());
    }
}
