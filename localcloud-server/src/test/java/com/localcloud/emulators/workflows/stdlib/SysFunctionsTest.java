package com.localcloud.emulators.workflows.stdlib;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SysFunctionsTest {

    private StdlibRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new StdlibRegistry();
    }

    @Test
    void sysSleep_interrupted_throwsRuntimeException() {
        Thread.currentThread().interrupt();
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> registry.get("sys.sleep").apply(List.of(10)));
        assertTrue(ex.getMessage().contains("Cancelled"),
                "Should mention cancellation, got: " + ex.getMessage());
        Thread.interrupted(); // clear flag
    }

    @Test
    void sysSleep_normal_completesWithoutError() {
        assertDoesNotThrow(() -> registry.get("sys.sleep").apply(List.of(0.01)));
    }
}
