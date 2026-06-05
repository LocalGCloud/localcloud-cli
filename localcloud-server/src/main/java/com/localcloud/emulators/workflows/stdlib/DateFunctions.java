package com.localcloud.emulators.workflows.stdlib;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Implements date.* functions for Cloud Workflows date/time operations.
 *
 * date format patterns use Java DateTimeFormatter conventions (e.g., "yyyy-MM-dd").
 */
public class DateFunctions {

    private static final Map<String, ChronoUnit> UNIT_MAP = new LinkedHashMap<>();
    static {
        UNIT_MAP.put("seconds", ChronoUnit.SECONDS);
        UNIT_MAP.put("minutes", ChronoUnit.MINUTES);
        UNIT_MAP.put("hours", ChronoUnit.HOURS);
        UNIT_MAP.put("days", ChronoUnit.DAYS);
        UNIT_MAP.put("months", ChronoUnit.MONTHS);
        UNIT_MAP.put("years", ChronoUnit.YEARS);
    }

    public static void register(StdlibRegistry registry) {
        registry.register("date.now", args -> {
            return Instant.now().toEpochMilli() / 1000.0;
        });

        registry.register("date.format", args -> {
            if (args.size() < 2) throw new RuntimeException("date.format requires (timestamp, format[, timezone])");
            double epochSeconds = ((Number) args.get(0)).doubleValue();
            String pattern = String.valueOf(args.get(1));
            ZoneId zone = ZoneId.of("UTC");
            if (args.size() >= 3 && args.get(2) != null) {
                zone = ZoneId.of(String.valueOf(args.get(2)));
            }
            long epochMillis = (long) (epochSeconds * 1000);
            Instant instant = Instant.ofEpochMilli(epochMillis);
            ZonedDateTime zdt = ZonedDateTime.ofInstant(instant, zone);
            try {
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern(pattern).withZone(zone);
                return fmt.format(zdt);
            } catch (IllegalArgumentException e) {
                throw new RuntimeException("date.format: invalid format pattern: " + pattern);
            }
        });

        registry.register("date.parse", args -> {
            if (args.size() < 2) throw new RuntimeException("date.parse requires (string, format[, timezone])");
            String dateString = String.valueOf(args.get(0));
            String pattern = String.valueOf(args.get(1));
            ZoneId zone = ZoneId.of("UTC");
            if (args.size() >= 3 && args.get(2) != null) {
                zone = ZoneId.of(String.valueOf(args.get(2)));
            }
            try {
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern(pattern).withZone(zone);
                LocalDateTime ldt = LocalDateTime.parse(dateString, fmt);
                ZonedDateTime zdt = ldt.atZone(zone);
                return zdt.toInstant().toEpochMilli() / 1000.0;
            } catch (DateTimeParseException e) {
                throw new RuntimeException("date.parse: cannot parse '" + dateString +
                        "' with format '" + pattern + "': " + e.getMessage());
            } catch (IllegalArgumentException e) {
                throw new RuntimeException("date.parse: invalid format pattern: " + pattern);
            }
        });

        registry.register("date.add", args -> {
            if (args.size() < 3) throw new RuntimeException("date.add requires (timestamp, amount, unit)");
            double epochSeconds = ((Number) args.get(0)).doubleValue();
            long amount = ((Number) args.get(1)).longValue();
            String unit = String.valueOf(args.get(2)).toLowerCase();
            ChronoUnit chronoUnit = UNIT_MAP.get(unit);
            if (chronoUnit == null) throw new RuntimeException(
                    "date.add: unknown unit '" + unit + "'. Supported: " + UNIT_MAP.keySet());
            long epochMillis = (long) (epochSeconds * 1000);
            Instant instant = Instant.ofEpochMilli(epochMillis);
            Instant result = instant.plus(amount, chronoUnit);
            return result.toEpochMilli() / 1000.0;
        });

        registry.register("date.diff", args -> {
            if (args.size() < 3) throw new RuntimeException("date.diff requires (timestamp1, timestamp2, unit)");
            double epochSeconds1 = ((Number) args.get(0)).doubleValue();
            double epochSeconds2 = ((Number) args.get(1)).doubleValue();
            String unit = String.valueOf(args.get(2)).toLowerCase();
            ChronoUnit chronoUnit = UNIT_MAP.get(unit);
            if (chronoUnit == null) throw new RuntimeException(
                    "date.diff: unknown unit '" + unit + "'. Supported: " + UNIT_MAP.keySet());
            Instant instant1 = Instant.ofEpochMilli((long) (epochSeconds1 * 1000));
            Instant instant2 = Instant.ofEpochMilli((long) (epochSeconds2 * 1000));
            return (double) chronoUnit.between(instant1, instant2);
        });
    }
}
