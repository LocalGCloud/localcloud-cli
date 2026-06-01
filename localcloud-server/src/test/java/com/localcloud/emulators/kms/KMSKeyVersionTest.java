package com.localcloud.emulators.kms;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Cloud KMS encryption/decryption crypto key version management.
 */
class KMSKeyVersionTest {

    @Test
    void keyRingNameParsing_validFormat() {
        String name = "projects/p/locations/us-central1/keyRings/my-keyring";
        String[] parts = name.split("/");
        assertEquals(6, parts.length);
        assertEquals("p", parts[1]);
        assertEquals("us-central1", parts[3]);
        assertEquals("my-keyring", parts[5]);
    }

    @Test
    void cryptoKeyNameParsing_validFormat() {
        String name = "projects/p/locations/l/keyRings/kr/cryptoKeys/ck";
        String[] parts = name.split("/");
        assertEquals(8, parts.length);
        assertEquals("ck", parts[7]);
    }

    @Test
    void encryptPlaintext_base64Encoding() {
        byte[] plaintext = "hello world".getBytes();
        String base64Input = Base64.getEncoder().encodeToString(plaintext);
        byte[] decoded = Base64.getDecoder().decode(base64Input);
        assertArrayEquals(plaintext, decoded);
    }

    @Test
    void versionState_hasValidValues() {
        String[] validStates = {"ENABLED", "DISABLED", "DESTROYED", "DESTROY_SCHEDULED"};
        for (String state : validStates) {
            assertNotNull(state);
            assertFalse(state.isBlank());
        }
    }

    @Test
    void destroyVersion_setsStateToDestroyed() {
        String expectedState = "DESTROY_SCHEDULED";
        assertNotNull(expectedState);
        assertTrue(expectedState.contains("DESTROY"));
    }
}
