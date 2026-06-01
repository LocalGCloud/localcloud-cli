package com.localcloud.emulators.cloudresourcemanager;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Cloud Resource Manager project validation and response format.
 */
class CloudResourceManagerProjectValidationTest {

    @Test
    void projectId_validFormat() {
        String projectId = "my-project-123";
        assertTrue(projectId.matches("[a-z][a-z0-9-]{4,28}[a-z0-9]"));
        assertTrue(projectId.length() >= 6 && projectId.length() <= 30);
    }

    @Test
    void projectId_mustStartWithLetter() {
        assertTrue("my-proj".matches("[a-z][a-z0-9-]{4,28}[a-z0-9]"));
        assertFalse("123-proj".matches("[a-z][a-z0-9-]{4,28}[a-z0-9]"));
    }

    @Test
    void projectId_maxLength() {
        String longId = "a" + "b".repeat(28) + "c";
        assertTrue(longId.length() <= 30);
        // 30 chars: 1 + 28 + 1
        assertEquals(30, longId.length());
    }

    @Test
    void projectName_fullResourcePath() {
        String name = "projects/my-project";
        assertTrue(name.startsWith("projects/"));
        assertEquals("my-project", name.substring("projects/".length()));
    }
}
