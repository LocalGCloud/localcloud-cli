package com.localcloud.emulators.gke;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for K3dManager name validation logic.
 * These tests do NOT call external processes (k3d CLI).
 * They only test the input validation that was added as a security fix.
 */
class K3dManagerTest {

    private K3dManager manager;
    private Method validateMethod;

    @BeforeEach
    void setUp() throws Exception {
        manager = new K3dManager();
        // Access the private validateClusterName method via reflection
        validateMethod = K3dManager.class.getDeclaredMethod("validateClusterName", String.class);
        validateMethod.setAccessible(true);
    }

    private void validate(String name) throws Exception {
        try {
            validateMethod.invoke(manager, name);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof Exception ex) {
                throw ex;
            }
            throw new RuntimeException(e.getCause());
        }
    }

    // --- Valid names ---

    @ParameterizedTest
    @ValueSource(strings = {"my-cluster", "test123", "a", "cluster-with-hyphens",
            "ABC", "MixedCase99", "a1b2c3", "name_with_underscore"})
    void validateClusterName_validNames(String name) {
        assertDoesNotThrow(() -> validate(name));
    }

    @Test
    void validateClusterName_maxLength63() {
        String name63 = "a".repeat(63);
        assertDoesNotThrow(() -> validate(name63));
    }

    // --- Invalid names ---

    @Test
    void validateClusterName_null() {
        assertThrows(IllegalArgumentException.class, () -> validate(null));
    }

    @Test
    void validateClusterName_empty() {
        assertThrows(IllegalArgumentException.class, () -> validate(""));
    }

    @Test
    void validateClusterName_blank() {
        assertThrows(IllegalArgumentException.class, () -> validate("   "));
    }

    @Test
    void validateClusterName_tooLong() {
        String name64 = "a".repeat(64);
        assertThrows(IllegalArgumentException.class, () -> validate(name64));
    }

    @Test
    void validateClusterName_semicolonInjection() {
        assertThrows(IllegalArgumentException.class, () -> validate("a;b"));
    }

    @Test
    void validateClusterName_commandSubstitution() {
        assertThrows(IllegalArgumentException.class, () -> validate("$(cmd)"));
    }

    @Test
    void validateClusterName_containsSpaces() {
        assertThrows(IllegalArgumentException.class, () -> validate("a b"));
    }

    @Test
    void validateClusterName_startsWithHyphen() {
        assertThrows(IllegalArgumentException.class, () -> validate("-leading"));
    }

    @Test
    void validateClusterName_startsWithUnderscore() {
        assertThrows(IllegalArgumentException.class, () -> validate("_leading"));
    }

    @Test
    void validateClusterName_backtickInjection() {
        assertThrows(IllegalArgumentException.class, () -> validate("`whoami`"));
    }

    @Test
    void validateClusterName_pipeInjection() {
        assertThrows(IllegalArgumentException.class, () -> validate("name|cat"));
    }

    @Test
    void validateClusterName_slashInName() {
        assertThrows(IllegalArgumentException.class, () -> validate("../../etc/passwd"));
    }

    // --- CLUSTER_PREFIX constant ---

    @Test
    void clusterPrefix_isLc() throws Exception {
        Field prefixField = K3dManager.class.getDeclaredField("CLUSTER_PREFIX");
        prefixField.setAccessible(true);
        assertEquals("lc-", prefixField.get(null));
    }

    // --- SAFE_NAME pattern ---

    @Test
    void safeNamePattern_rejectsLeadingSpecialChars() throws Exception {
        Field patternField = K3dManager.class.getDeclaredField("SAFE_NAME");
        patternField.setAccessible(true);
        Pattern pattern = (Pattern) patternField.get(null);
        assertFalse(pattern.matcher("-abc").matches());
        assertFalse(pattern.matcher("_abc").matches());
        assertTrue(pattern.matcher("abc").matches());
        assertTrue(pattern.matcher("a-b").matches());
        assertTrue(pattern.matcher("a_b").matches());
    }
}
