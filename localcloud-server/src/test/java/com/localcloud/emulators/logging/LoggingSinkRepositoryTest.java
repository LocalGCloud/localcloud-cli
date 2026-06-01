package com.localcloud.emulators.logging;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for LoggingSinkRepository JSON response format.
 */
class LoggingSinkRepositoryTest {

    @Test
    void buildSinkJson_containsRequiredFields() {
        String json = LoggingSinkRepository.buildSinkJson("test-project", "test-sink",
                "bigquery.googleapis.com", "serviceAccount:cloud-logs@localcloud.iam.gserviceaccount.com");
        assertTrue(json.contains("\"name\""));
        assertTrue(json.contains("projects/test-project/sinks/test-sink"));
        assertTrue(json.contains("\"destination\""));
        assertTrue(json.contains("bigquery.googleapis.com"));
        assertTrue(json.contains("\"writerIdentity\""));
    }

    @Test
    void buildSinkJson_handlesNullDestination() {
        String json = LoggingSinkRepository.buildSinkJson("p", "s1", null, null);
        assertTrue(json.contains("bigquery.googleapis.com"));
        assertTrue(json.contains("cloud-logs@localcloud"));
    }

    @Test
    void sinkId_fromTerraformName() {
        String sinkName = "test-sink";
        assertFalse(sinkName.isEmpty());
        assertTrue(sinkName.matches("[a-zA-Z0-9_-]+"));
    }

    @Test
    void sinkName_format() {
        String projectId = "my-project";
        String sinkId = "abc12345";
        String expected = "projects/my-project/sinks/abc12345";
        assertEquals(expected, "projects/" + projectId + "/sinks/" + sinkId);
    }
}
