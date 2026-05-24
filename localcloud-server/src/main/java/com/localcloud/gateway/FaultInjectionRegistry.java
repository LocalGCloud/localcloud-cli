package com.localcloud.gateway;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory fault rule registry used by the gateway to inject local failures.
 */
public class FaultInjectionRegistry {

    private final ConcurrentHashMap<String, RuntimeRule> rules = new ConcurrentHashMap<>();

    public FaultRule add(Map<String, Object> request) {
        FaultRule rule = FaultRule.from(request);
        rules.put(rule.id(), new RuntimeRule(rule));
        return rule;
    }

    public boolean remove(String id) {
        return rules.remove(id) != null;
    }

    public void clear() {
        rules.clear();
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (RuntimeRule runtime : rules.values()) {
            result.add(runtime.snapshot());
        }
        result.sort((left, right) -> String.valueOf(left.get("id")).compareTo(String.valueOf(right.get("id"))));
        return result;
    }

    public FaultDecision evaluate(String service, String method, String path) {
        for (RuntimeRule runtime : rules.values()) {
            FaultRule rule = runtime.rule();
            if (!rule.enabled() || rule.isExpired()) {
                continue;
            }
            if (!rule.matches(service, method, path)) {
                continue;
            }
            if (!runtime.consume()) {
                continue;
            }
            if (ThreadLocalRandom.current().nextDouble() > rule.probability()) {
                continue;
            }
            return FaultDecision.matched(rule);
        }
        return FaultDecision.none();
    }

    private record RuntimeRule(FaultRule rule, AtomicInteger remaining) {
        RuntimeRule(FaultRule rule) {
            this(rule, new AtomicInteger(rule.requestLimit()));
        }

        boolean consume() {
            if (rule.requestLimit() <= 0) {
                return true;
            }
            int current = remaining.get();
            while (current > 0) {
                if (remaining.compareAndSet(current, current - 1)) {
                    return true;
                }
                current = remaining.get();
            }
            return false;
        }

        Map<String, Object> snapshot() {
            Map<String, Object> out = rule.toMap();
            out.put("remaining_requests", rule.requestLimit() <= 0 ? "unlimited" : remaining.get());
            out.put("expired", rule.isExpired());
            return out;
        }
    }

    public record FaultDecision(boolean matched, FaultRule rule) {
        static FaultDecision none() {
            return new FaultDecision(false, null);
        }

        static FaultDecision matched(FaultRule rule) {
            return new FaultDecision(true, rule);
        }
    }

    public record FaultRule(
            String id,
            String service,
            String method,
            String pathContains,
            int statusCode,
            String errorType,
            String message,
            long latencyMs,
            double probability,
            int requestLimit,
            Instant expiresAt,
            boolean enabled,
            Instant createdAt
    ) {
        static FaultRule from(Map<String, Object> request) {
            String service = stringValue(request.get("service"), null);
            if (service == null || service.isBlank()) {
                throw new IllegalArgumentException("service is required");
            }

            String errorType = stringValue(request.get("error_type"), "unavailable");
            long latencyMs = longValue(request.get("latency_ms"), 0);
            int statusCode = intValue(request.get("status_code"), defaultStatus(errorType));
            if (latencyMs < 0) {
                throw new IllegalArgumentException("latency_ms must be non-negative");
            }
            if (statusCode < 0 || statusCode > 599) {
                throw new IllegalArgumentException("status_code must be between 0 and 599");
            }

            double probability = doubleValue(request.get("probability"), 1.0);
            if (probability < 0.0 || probability > 1.0) {
                throw new IllegalArgumentException("probability must be between 0.0 and 1.0");
            }

            int requestLimit = intValue(request.get("request_limit"), 0);
            if (requestLimit < 0) {
                throw new IllegalArgumentException("request_limit must be non-negative");
            }

            Instant expiresAt = instantValue(request.get("expires_at"));
            return new FaultRule(
                    stringValue(request.get("id"), UUID.randomUUID().toString()),
                    service.trim(),
                    uppercaseOrNull(stringValue(request.get("method"), null)),
                    stringValue(request.get("path_contains"), null),
                    statusCode,
                    errorType,
                    stringValue(request.get("message"), "Injected LocalCloud fault"),
                    latencyMs,
                    probability,
                    requestLimit,
                    expiresAt,
                    booleanValue(request.get("enabled"), true),
                    Instant.now()
            );
        }

        boolean matches(String candidateService, String candidateMethod, String path) {
            if (!"*".equals(service) && !service.equals(candidateService)) {
                return false;
            }
            if (method != null && !method.equalsIgnoreCase(candidateMethod)) {
                return false;
            }
            return pathContains == null || path.contains(pathContains);
        }

        boolean isExpired() {
            return expiresAt != null && !expiresAt.isAfter(Instant.now());
        }

        boolean injectsResponse() {
            return statusCode > 0;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("service", service);
            out.put("method", method);
            out.put("path_contains", pathContains);
            out.put("status_code", statusCode);
            out.put("error_type", errorType);
            out.put("message", message);
            out.put("latency_ms", latencyMs);
            out.put("probability", probability);
            out.put("request_limit", requestLimit);
            out.put("expires_at", expiresAt == null ? null : expiresAt.toString());
            out.put("enabled", enabled);
            out.put("created_at", createdAt.toString());
            return out;
        }

        private static int defaultStatus(String errorType) {
            if ("timeout".equalsIgnoreCase(errorType)) {
                return 504;
            }
            if ("rate_limit".equalsIgnoreCase(errorType)) {
                return 429;
            }
            return 503;
        }

        private static String uppercaseOrNull(String value) {
            return value == null || value.isBlank() ? null : value.trim().toUpperCase();
        }

        private static String stringValue(Object raw, String fallback) {
            if (raw == null) {
                return fallback;
            }
            String value = String.valueOf(raw);
            return value.isBlank() ? fallback : value;
        }

        private static boolean booleanValue(Object raw, boolean fallback) {
            if (raw == null) {
                return fallback;
            }
            if (raw instanceof Boolean value) {
                return value;
            }
            return Boolean.parseBoolean(String.valueOf(raw));
        }

        private static int intValue(Object raw, int fallback) {
            if (raw == null) {
                return fallback;
            }
            if (raw instanceof Number value) {
                return value.intValue();
            }
            return Integer.parseInt(String.valueOf(raw));
        }

        private static long longValue(Object raw, long fallback) {
            if (raw == null) {
                return fallback;
            }
            if (raw instanceof Number value) {
                return value.longValue();
            }
            return Long.parseLong(String.valueOf(raw));
        }

        private static double doubleValue(Object raw, double fallback) {
            if (raw == null) {
                return fallback;
            }
            if (raw instanceof Number value) {
                return value.doubleValue();
            }
            return Double.parseDouble(String.valueOf(raw));
        }

        private static Instant instantValue(Object raw) {
            if (raw == null || String.valueOf(raw).isBlank()) {
                return null;
            }
            return Instant.parse(String.valueOf(raw));
        }
    }
}
