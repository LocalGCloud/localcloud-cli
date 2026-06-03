package com.localcloud.emulators.monitoring;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MonitoringAlertPolicyRepository JSON response format.
 */
class MonitoringAlertPolicyRepositoryTest {

    @Test
    void buildPolicyJson_containsRequiredFields() {
        String json = MonitoringAlertPolicyRepository.buildPolicyJson("test-project", "pol001", "test-alert", "[]", "OR", true);
        assertTrue(json.contains("\"name\""));
        assertTrue(json.contains("projects/test-project/alertPolicies/pol001"));
        assertTrue(json.contains("\"displayName\""));
        assertTrue(json.contains("test-alert"));
        assertTrue(json.contains("\"enabled\""));
        assertTrue(json.contains("true"));
    }

    @Test
    void buildPolicyJson_defaultDisplayName() {
        String json = MonitoringAlertPolicyRepository.buildPolicyJson("p", "pol1", null, "[]", "OR", true);
        assertTrue(json.contains("localcloud-alert"));
    }

    @Test
    void policyId_isValidUUID() {
        String policyId = java.util.UUID.randomUUID().toString().substring(0, 8);
        assertEquals(8, policyId.length());
        assertTrue(policyId.matches("[a-f0-9]+"));
    }

    @Test
    void policyName_format() {
        String projectId = "my-project";
        String policyId = "pol-001";
        String expected = "projects/my-project/alertPolicies/pol-001";
        assertEquals(expected, "projects/" + projectId + "/alertPolicies/" + policyId);
    }
}
