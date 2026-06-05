package com.localcloud.emulators.workflows.stdlib;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Implements random.get_int for Cloud Workflows random number generation.
 */
public class RandomFunctions {

    public static void register(StdlibRegistry registry) {
        registry.register("random.get_int", args -> {
            if (args.size() < 2) throw new RuntimeException("random.get_int requires (min, max)");
            int min = ((Number) args.get(0)).intValue();
            int max = ((Number) args.get(1)).intValue();
            if (min > max) throw new RuntimeException("random.get_int: min (" + min + ") must be <= max (" + max + ")");
            if (max == Integer.MAX_VALUE && min <= 0) {
                throw new RuntimeException("random.get_int: range too large");
            }
            return ThreadLocalRandom.current().nextInt(min, max + 1);
        });
    }
}
