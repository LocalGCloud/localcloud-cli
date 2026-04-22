package com.localcloud.emulators.workflows.stdlib;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class TimeFunctions {
    private static final DateTimeFormatter RFC3339 = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    public static void register(StdlibRegistry registry) {
        registry.register("time.format", TimeFunctions::format);
        registry.register("time.parse", TimeFunctions::parse);
    }

    private static Object format(List<Object> args) {
        if (args.isEmpty()) throw new RuntimeException("time.format requires (timestamp[, timezone])");
        double epochSeconds = ((Number) args.get(0)).doubleValue();
        long epochMillis = (long) (epochSeconds * 1000);
        Instant instant = Instant.ofEpochMilli(epochMillis);
        ZoneId zone = ZoneId.of("UTC");
        if (args.size() >= 2 && args.get(1) != null) {
            zone = ZoneId.of(String.valueOf(args.get(1)));
        }
        return ZonedDateTime.ofInstant(instant, zone).format(RFC3339);
    }

    private static Object parse(List<Object> args) {
        if (args.isEmpty()) throw new RuntimeException("time.parse requires (string)");
        String s = String.valueOf(args.get(0));
        try {
            ZonedDateTime zdt = ZonedDateTime.parse(s, DateTimeFormatter.ISO_DATE_TIME);
            return zdt.toInstant().toEpochMilli() / 1000.0;
        } catch (Exception e) {
            try {
                Instant instant = Instant.parse(s);
                return instant.toEpochMilli() / 1000.0;
            } catch (Exception e2) {
                throw new RuntimeException("time.parse failed to parse: " + s);
            }
        }
    }
}
