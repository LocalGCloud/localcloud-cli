package com.localcloud.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

class FaultInjectionRegistryTest {

    @Test
    void matchingRuleReturnsDecision() {
        FaultInjectionRegistry registry = new FaultInjectionRegistry();
        registry.add(Map.of(
                "id", "gcs-down",
                "service", "gcs",
                "method", "GET",
                "path_contains", "/storage/v1/b",
                "status_code", 503,
                "message", "GCS unavailable"
        ));

        var decision = registry.evaluate("gcs", "GET", "/storage/v1/b");

        assertTrue(decision.matched());
        assertEquals("gcs-down", decision.rule().id());
        assertEquals(503, decision.rule().statusCode());
    }

    @Test
    void nonMatchingServiceDoesNotInjectFault() {
        FaultInjectionRegistry registry = new FaultInjectionRegistry();
        registry.add(Map.of("service", "gcs", "status_code", 503));

        assertFalse(registry.evaluate("pubsub", "GET", "/pubsub/v1/projects/p/topics").matched());
    }

    @Test
    void requestLimitStopsMatchingAfterConfiguredCount() {
        FaultInjectionRegistry registry = new FaultInjectionRegistry();
        registry.add(Map.of(
                "service", "gcs",
                "status_code", 503,
                "request_limit", 1
        ));

        assertTrue(registry.evaluate("gcs", "GET", "/storage/v1/b").matched());
        assertFalse(registry.evaluate("gcs", "GET", "/storage/v1/b").matched());
    }

    @Test
    void expiredRuleDoesNotMatch() {
        FaultInjectionRegistry registry = new FaultInjectionRegistry();
        registry.add(Map.of(
                "service", "gcs",
                "status_code", 503,
                "expires_at", Instant.now().minusSeconds(1).toString()
        ));

        assertFalse(registry.evaluate("gcs", "GET", "/storage/v1/b").matched());
    }
}
