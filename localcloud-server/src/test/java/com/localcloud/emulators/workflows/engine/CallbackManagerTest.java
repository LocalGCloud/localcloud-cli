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

    @Test void testDeliverBeforeAwait() throws Exception {
        String callbackId = manager.createCallback();
        // Deliver BEFORE await
        assertTrue(manager.deliverCallback(callbackId, Map.of("key", "value")));
        // Await should return immediately with the delivered payload
        Object result = manager.awaitCallback(callbackId, 5);
        assertNotNull(result);
        assertTrue(result instanceof Map);
        assertEquals("value", ((Map<?, ?>) result).get("key"));
    }

    @Test void testShutdownCancels() {
        String id = manager.createCallback();
        manager.shutdown();
        assertFalse(manager.isPending(id));
    }

    // --- Execution tracking and cancellation tests ---

    @Test
    void createCallback_withExecutionId_tracksMapping() {
        String callbackId = manager.createCallback("exec-1");
        assertNotNull(callbackId);
        assertTrue(manager.isPending(callbackId));
    }

    @Test
    void cancelCallbacksForExecution_unblocksPendingWait() throws Exception {
        String callbackId = manager.createCallback("exec-cancel");
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Future<?> future = exec.submit(() -> {
            assertThrows(WorkflowException.class,
                    () -> manager.awaitCallback(callbackId, 30));
        });
        Thread.sleep(100);
        manager.cancelCallbacksForExecution("exec-cancel");
        assertDoesNotThrow(() -> future.get(2, TimeUnit.SECONDS));
        exec.shutdown();
    }

    @Test
    void cancelCallbacksForExecution_nonExistentExecution_doesNotThrow() {
        assertDoesNotThrow(() -> manager.cancelCallbacksForExecution("no-such-execution"));
    }

    @Test
    void createCallback_noExecutionId_backwardCompatible() {
        String callbackId = manager.createCallback();
        assertNotNull(callbackId);
        assertTrue(manager.isPending(callbackId));
    }

    @Test
    void getExecutionIdForCallback_returnsCorrectMapping() {
        String callbackId = manager.createCallback("exec-lookup");
        assertEquals("exec-lookup", manager.getExecutionIdForCallback(callbackId));
    }

    @Test
    void getExecutionIdForCallback_noExecutionId_returnsNull() {
        String callbackId = manager.createCallback();
        assertNull(manager.getExecutionIdForCallback(callbackId));
    }
}
