package com.localcloud.emulators.workflows.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.*;

class CallbackManagerTest {
    private CallbackManager manager;

    @BeforeEach
    void setUp() { manager = new CallbackManager(); }

    @AfterEach
    void tearDown() { manager.shutdown(); }

    @Test void testCreateCallback() {
        String id = manager.createCallback();
        assertNotNull(id);
        assertTrue(manager.isPending(id));
    }

    @Test void testDeliverCallback() {
        String id = manager.createCallback();
        boolean delivered = manager.deliverCallback(id, Map.of("status", "ok"));
        assertTrue(delivered);
        assertFalse(manager.isPending(id));
    }

    @Test void testAwaitCallbackReceivesValue() throws Exception {
        String id = manager.createCallback();
        Map<String, Object> payload = Map.of("data", "test");

        // Deliver in background after 100ms
        CompletableFuture.runAsync(() -> {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            manager.deliverCallback(id, payload);
        });

        Object result = manager.awaitCallback(id, 5);
        assertEquals(payload, result);
    }

    @Test void testAwaitCallbackTimeout() {
        String id = manager.createCallback();
        assertThrows(WorkflowException.class, () -> manager.awaitCallback(id, 1));
    }

    @Test void testDeliverUnknownCallback() {
        assertFalse(manager.deliverCallback("nonexistent-id", Map.of()));
    }

    @Test void testCallbackSingleUse() {
        String id = manager.createCallback();
        assertTrue(manager.deliverCallback(id, "first"));
        assertFalse(manager.deliverCallback(id, "second")); // Already consumed
    }

    @Test void testMultipleCallbacks() {
        String id1 = manager.createCallback();
        String id2 = manager.createCallback();
        assertNotEquals(id1, id2);
        assertTrue(manager.isPending(id1));
        assertTrue(manager.isPending(id2));
    }

    @Test void testShutdownCancels() {
        String id = manager.createCallback();
        manager.shutdown();
        assertFalse(manager.isPending(id));
    }
}
