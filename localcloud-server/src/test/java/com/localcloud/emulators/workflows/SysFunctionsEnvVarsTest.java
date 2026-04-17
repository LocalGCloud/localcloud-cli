package com.localcloud.emulators.workflows;

import com.localcloud.emulators.workflows.stdlib.StdlibRegistry;
import com.localcloud.emulators.workflows.stdlib.SysFunctions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SysFunctionsEnvVarsTest {

    private StdlibRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new StdlibRegistry();
        SysFunctions.register(registry);
    }

    @Test
    void getEnvReturnsTableValueFirst() {
        SysFunctions.setWorkflowEnvVars(Map.of("TEST_VAR", "table-value"));

        Object result = registry.get("sys.get_env").apply( List.of("TEST_VAR"));
        assertEquals("table-value", result);
    }

    @Test
    void getEnvFallsBackToOsEnv() {
        SysFunctions.setWorkflowEnvVars(Map.of()); // empty table

        // PATH is virtually always set on any OS
        Object result = registry.get("sys.get_env").apply( List.of("PATH"));
        assertNotNull(result);
    }

    @Test
    void getEnvReturnsNullWhenNotFound() {
        SysFunctions.setWorkflowEnvVars(Map.of()); // empty table

        Object result = registry.get("sys.get_env").apply( List.of("NONEXISTENT_VAR_12345"));
        assertNull(result);
    }

    @Test
    void tableValueOverridesOsEnv() {
        // PATH exists in OS env, but table value should win
        SysFunctions.setWorkflowEnvVars(Map.of("PATH", "overridden-path"));

        Object result = registry.get("sys.get_env").apply( List.of("PATH"));
        assertEquals("overridden-path", result);
    }

    @Test
    void setWorkflowEnvVarsWithNull() {
        SysFunctions.setWorkflowEnvVars(null); // should not throw

        // Should still work, falling back to OS env
        Object result = registry.get("sys.get_env").apply( List.of("NONEXISTENT_VAR_12345"));
        assertNull(result);
    }

    @Test
    void getEnvRequiresArgument() {
        assertThrows(RuntimeException.class, () -> {
            registry.get("sys.get_env").apply( List.of());
        });
    }
}
