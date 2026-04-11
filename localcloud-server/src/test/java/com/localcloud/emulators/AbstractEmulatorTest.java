package com.localcloud.emulators;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AbstractEmulator lifecycle, request counting, and env var logic.
 * Uses a concrete TestEmulator subclass -- no database or I/O required.
 */
class AbstractEmulatorTest {

    /**
     * Minimal concrete subclass that records which hooks were called.
     */
    static class TestEmulator extends AbstractEmulator {
        boolean startCalled = false;
        boolean stopCalled = false;
        boolean resetCalled = false;
        boolean shouldThrowOnStart = false;
        int doStartCallCount = 0;

        TestEmulator() {
            super("test", "Test Emulator", 8080, "rest", "TEST_EMULATOR_HOST");
        }

        TestEmulator(String name, String displayName, int port, String protocol, String envVarName) {
            super(name, displayName, port, protocol, envVarName);
        }

        @Override
        protected void doStart() throws Exception {
            doStartCallCount++;
            if (shouldThrowOnStart) {
                throw new RuntimeException("start failed");
            }
            startCalled = true;
        }

        @Override
        protected void doStop() {
            stopCalled = true;
        }

        @Override
        protected void doReset() {
            resetCalled = true;
        }
    }

    private TestEmulator emulator;

    @BeforeEach
    void setUp() {
        emulator = new TestEmulator();
    }

    // --- Accessors ---

    @Test
    void getName_returnsConstructorValue() {
        assertEquals("test", emulator.getName());
    }

    @Test
    void getDisplayName_returnsConstructorValue() {
        assertEquals("Test Emulator", emulator.getDisplayName());
    }

    @Test
    void getPort_returnsConstructorValue() {
        assertEquals(8080, emulator.getPort());
    }

    @Test
    void getProtocol_returnsConstructorValue() {
        assertEquals("rest", emulator.getProtocol());
    }

    @Test
    void getEnvVarName_returnsConstructorValue() {
        assertEquals("TEST_EMULATOR_HOST", emulator.getEnvVarName());
    }

    // --- getEnvVarValue ---

    @Test
    void getEnvVarValue_restProtocol_includesHttpPrefix() {
        assertEquals("http://localhost:8080", emulator.getEnvVarValue("localhost"));
    }

    @Test
    void getEnvVarValue_restProtocol_customHost() {
        assertEquals("http://10.0.0.1:8080", emulator.getEnvVarValue("10.0.0.1"));
    }

    @Test
    void getEnvVarValue_grpcProtocol_hostColonPort() {
        TestEmulator grpcEmulator = new TestEmulator(
                "grpc-test", "gRPC Test", 9090, "grpc", "GRPC_HOST");
        assertEquals("localhost:9090", grpcEmulator.getEnvVarValue("localhost"));
    }

    @Test
    void getEnvVarValue_grpcProtocol_noHttpPrefix() {
        TestEmulator grpcEmulator = new TestEmulator(
                "grpc-test", "gRPC Test", 9090, "grpc", "GRPC_HOST");
        String value = grpcEmulator.getEnvVarValue("myhost");
        assertFalse(value.startsWith("http://"));
        assertEquals("myhost:9090", value);
    }

    // --- Lifecycle: start ---

    @Test
    void start_setsRunningTrue() throws Exception {
        assertFalse(emulator.isRunning());
        emulator.start();
        assertTrue(emulator.isRunning());
        assertTrue(emulator.startCalled);
    }

    @Test
    void start_callsDoStart() throws Exception {
        emulator.start();
        assertEquals(1, emulator.doStartCallCount);
    }

    @Test
    void doubleStart_doesNotCallDoStartTwice() throws Exception {
        emulator.start();
        emulator.start(); // second call should be a no-op
        assertEquals(1, emulator.doStartCallCount);
        assertTrue(emulator.isRunning());
    }

    @Test
    void start_failure_rollsBackRunningFlag() {
        emulator.shouldThrowOnStart = true;
        assertThrows(RuntimeException.class, () -> emulator.start());
        assertFalse(emulator.isRunning(), "Running flag must be rolled back on start failure");
    }

    @Test
    void start_failure_preservesExceptionMessage() {
        emulator.shouldThrowOnStart = true;
        RuntimeException ex = assertThrows(RuntimeException.class, () -> emulator.start());
        assertEquals("start failed", ex.getMessage());
    }

    @Test
    void start_afterFailure_canRetry() throws Exception {
        emulator.shouldThrowOnStart = true;
        assertThrows(RuntimeException.class, () -> emulator.start());
        assertFalse(emulator.isRunning());

        // Now fix the issue and retry
        emulator.shouldThrowOnStart = false;
        emulator.start();
        assertTrue(emulator.isRunning());
        assertTrue(emulator.startCalled);
    }

    // --- Lifecycle: stop ---

    @Test
    void stop_setsRunningFalse() throws Exception {
        emulator.start();
        assertTrue(emulator.isRunning());
        emulator.stop();
        assertFalse(emulator.isRunning());
        assertTrue(emulator.stopCalled);
    }

    @Test
    void stop_whenNotRunning_doesNotCallDoStop() {
        emulator.stop();
        assertFalse(emulator.stopCalled);
    }

    // --- Reset ---

    @Test
    void reset_callsDoReset() throws Exception {
        emulator.start();
        emulator.reset();
        assertTrue(emulator.resetCalled);
    }

    @Test
    void reset_clearsRequestCount() throws Exception {
        emulator.start();
        emulator.incrementRequestCount();
        emulator.incrementRequestCount();
        assertEquals(2, emulator.getRequestCount());

        emulator.reset();
        assertEquals(0, emulator.getRequestCount());
    }

    // --- Request counting ---

    @Test
    void requestCount_startsAtZero() {
        assertEquals(0, emulator.getRequestCount());
    }

    @Test
    void incrementRequestCount_incrementsByOne() {
        emulator.incrementRequestCount();
        assertEquals(1, emulator.getRequestCount());
    }

    @Test
    void incrementRequestCount_multipleIncrements() {
        for (int i = 0; i < 5; i++) {
            emulator.incrementRequestCount();
        }
        assertEquals(5, emulator.getRequestCount());
    }

    // --- toString ---

    @Test
    void toString_containsRelevantInfo() {
        String str = emulator.toString();
        assertTrue(str.contains("Test Emulator"));
        assertTrue(str.contains("test"));
        assertTrue(str.contains("8080"));
        assertTrue(str.contains("rest"));
    }
}
