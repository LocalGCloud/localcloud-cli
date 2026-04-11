package com.localcloud.emulators.cloudtasks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CloudTasksStore static parsing helpers.
 * These tests exercise pure logic only and require no database.
 */
class CloudTasksStoreTest {

    // --- parseQueueName ---

    @Test
    void parseQueueName_validFormat() {
        String[] result = CloudTasksStore.parseQueueName(
                "projects/p/locations/l/queues/q");
        assertEquals(3, result.length);
        assertEquals("p", result[0]);
        assertEquals("l", result[1]);
        assertEquals("q", result[2]);
    }

    @Test
    void parseQueueName_realWorldValues() {
        String[] result = CloudTasksStore.parseQueueName(
                "projects/my-project/locations/us-central1/queues/email-queue");
        assertEquals("my-project", result[0]);
        assertEquals("us-central1", result[1]);
        assertEquals("email-queue", result[2]);
    }

    @Test
    void parseQueueName_tooFewSegments() {
        assertThrows(IllegalArgumentException.class,
                () -> CloudTasksStore.parseQueueName("projects/p/locations/l"));
    }

    @Test
    void parseQueueName_tooManySegments() {
        assertThrows(IllegalArgumentException.class,
                () -> CloudTasksStore.parseQueueName(
                        "projects/p/locations/l/queues/q/tasks/t"));
    }

    @Test
    void parseQueueName_wrongProjectsKeyword() {
        assertThrows(IllegalArgumentException.class,
                () -> CloudTasksStore.parseQueueName(
                        "orgs/p/locations/l/queues/q"));
    }

    @Test
    void parseQueueName_wrongLocationsKeyword() {
        assertThrows(IllegalArgumentException.class,
                () -> CloudTasksStore.parseQueueName(
                        "projects/p/regions/l/queues/q"));
    }

    @Test
    void parseQueueName_wrongQueuesKeyword() {
        assertThrows(IllegalArgumentException.class,
                () -> CloudTasksStore.parseQueueName(
                        "projects/p/locations/l/topics/q"));
    }

    @Test
    void parseQueueName_emptyString() {
        assertThrows(IllegalArgumentException.class,
                () -> CloudTasksStore.parseQueueName(""));
    }

    // --- parseTaskName ---

    @Test
    void parseTaskName_validFormat() {
        String[] result = CloudTasksStore.parseTaskName(
                "projects/p/locations/l/queues/q/tasks/t");
        assertEquals(4, result.length);
        assertEquals("p", result[0]);
        assertEquals("l", result[1]);
        assertEquals("q", result[2]);
        assertEquals("t", result[3]);
    }

    @Test
    void parseTaskName_realWorldValues() {
        String[] result = CloudTasksStore.parseTaskName(
                "projects/my-project/locations/us-east1/queues/worker/tasks/task-abc-123");
        assertEquals("my-project", result[0]);
        assertEquals("us-east1", result[1]);
        assertEquals("worker", result[2]);
        assertEquals("task-abc-123", result[3]);
    }

    @Test
    void parseTaskName_tooFewSegments() {
        assertThrows(IllegalArgumentException.class,
                () -> CloudTasksStore.parseTaskName(
                        "projects/p/locations/l/queues/q"));
    }

    @Test
    void parseTaskName_wrongTasksKeyword() {
        assertThrows(IllegalArgumentException.class,
                () -> CloudTasksStore.parseTaskName(
                        "projects/p/locations/l/queues/q/jobs/t"));
    }

    @Test
    void parseTaskName_tooManySegments() {
        assertThrows(IllegalArgumentException.class,
                () -> CloudTasksStore.parseTaskName(
                        "projects/p/locations/l/queues/q/tasks/t/extra/x"));
    }

    // --- parseLocationName ---

    @Test
    void parseLocationName_validFormat() {
        String[] result = CloudTasksStore.parseLocationName(
                "projects/p/locations/l");
        assertEquals(2, result.length);
        assertEquals("p", result[0]);
        assertEquals("l", result[1]);
    }

    @Test
    void parseLocationName_realWorldValues() {
        String[] result = CloudTasksStore.parseLocationName(
                "projects/my-project/locations/europe-west1");
        assertEquals("my-project", result[0]);
        assertEquals("europe-west1", result[1]);
    }

    @Test
    void parseLocationName_tooFewSegments() {
        assertThrows(IllegalArgumentException.class,
                () -> CloudTasksStore.parseLocationName("projects/p"));
    }

    @Test
    void parseLocationName_tooManySegments() {
        assertThrows(IllegalArgumentException.class,
                () -> CloudTasksStore.parseLocationName(
                        "projects/p/locations/l/queues/q"));
    }

    @Test
    void parseLocationName_wrongLocationsKeyword() {
        assertThrows(IllegalArgumentException.class,
                () -> CloudTasksStore.parseLocationName(
                        "projects/p/regions/l"));
    }

    @Test
    void parseLocationName_emptyString() {
        assertThrows(IllegalArgumentException.class,
                () -> CloudTasksStore.parseLocationName(""));
    }
}
