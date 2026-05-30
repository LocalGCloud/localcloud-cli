package com.localcloud.emulators.cloudresourcemanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localcloud.admin.ProjectService;
import com.localcloud.config.LocalCloudConfig;
import com.localcloud.integration.TestDataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CloudResourceManagerRestService via ProjectService.
 * Tests the full create/read/list/delete/update lifecycle against H2.
 */
class CloudResourceManagerRestServiceTest {

    private TestDataSource testDataSource;
    private ProjectService projectService;
    private LocalCloudConfig config;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        testDataSource = TestDataSource.create("crm-test");
        projectService = new ProjectService(testDataSource.getDataSource());
        config = LocalCloudConfig.fromEnvironment();
    }

    @AfterEach
    void tearDown() {
        if (testDataSource != null) {
            testDataSource.close();
        }
    }

    @Test
    void createAndGetProject() throws Exception {
        Map<String, Object> created = projectService.createProject("test-project", "Test Project");
        assertNotNull(created);
        assertEquals("test-project", created.get("project_id"));
        assertEquals("Test Project", created.get("display_name"));
        assertEquals("ACTIVE", created.get("state"));
        assertEquals("{}", created.get("labels"));
        assertNotNull(created.get("created_at"));

        Map<String, Object> fetched = projectService.getProject("test-project");
        assertNotNull(fetched);
        assertEquals("test-project", fetched.get("project_id"));
    }

    @Test
    void createProjectWithLabels() throws Exception {
        String labelsJson = "{\"env\":\"test\",\"team\":\"platform\"}";
        Map<String, Object> created = projectService.createProject("labeled-project", "Labeled", labelsJson);
        assertNotNull(created);
        assertEquals("labeled-project", created.get("project_id"));
        assertEquals(labelsJson, created.get("labels"));
    }

    @Test
    void createDuplicateProject_returnsExisting() throws Exception {
        projectService.createProject("dup-project", "First");
        Map<String, Object> second = projectService.createProject("dup-project", "Second");
        assertNotNull(second);
        assertEquals("dup-project", second.get("project_id"));
        // displayName stays as the first one (ON CONFLICT DO NOTHING)
        assertEquals("First", second.get("display_name"));
    }

    @Test
    void listProjects() throws Exception {
        projectService.createProject("proj-a", "Project A");
        projectService.createProject("proj-b", "Project B");

        List<Map<String, Object>> projects = projectService.listProjects();
        assertEquals(2, projects.size());
        assertTrue(projects.stream().anyMatch(p -> "proj-a".equals(p.get("project_id"))));
        assertTrue(projects.stream().anyMatch(p -> "proj-b".equals(p.get("project_id"))));
    }

    @Test
    void getProject_notFound() throws Exception {
        assertNull(projectService.getProject("nonexistent"));
    }

    @Test
    void updateProject() throws Exception {
        projectService.createProject("update-me", "Original Name");

        Map<String, Object> updated = projectService.updateProject("update-me", "New Name", null);
        assertNotNull(updated);
        assertEquals("New Name", updated.get("display_name"));

        // Verify labels stay default
        assertEquals("{}", updated.get("labels"));
    }

    @Test
    void updateProject_labels() throws Exception {
        projectService.createProject("labels-test", "Labels Test");

        String labels = "{\"tier\":\"production\",\"region\":\"us-east1\"}";
        Map<String, Object> updated = projectService.updateProject("labels-test", null, labels);
        assertNotNull(updated);
        assertEquals(labels, updated.get("labels"));
        assertEquals("Labels Test", updated.get("display_name")); // unchanged
    }

    @Test
    void deleteProject() throws Exception {
        projectService.createProject("to-delete", "Delete Me");
        assertNotNull(projectService.getProject("to-delete"));

        projectService.deleteProject("to-delete", config.getProjectId());
        assertNull(projectService.getProject("to-delete"));
    }

    @Test
    void deleteProject_cannotDeleteDefault() {
        assertThrows(IllegalArgumentException.class, () ->
                projectService.deleteProject(config.getProjectId(), config.getProjectId()));
    }

    @Test
    void projectExists() throws Exception {
        assertFalse(projectService.projectExists("ghost-project"));
        projectService.createProject("real-project", "Real");
        assertTrue(projectService.projectExists("real-project"));
    }

    // --- Response shape tests ---

    @Test
    void responseShape_toGoogleProject() throws Exception {
        projectService.createProject("shape-test", "Shape Test", "{\"key\":\"val\"}");
        Map<String, Object> project = projectService.getProject("shape-test");

        // Verify all fields needed for Google shape
        assertEquals("projects/shape-test", "projects/" + project.get("project_id"));
        assertEquals("shape-test", project.get("project_id"));
        assertEquals("ACTIVE", project.get("state"));
        assertEquals("Shape Test", project.get("display_name"));
        assertEquals("{\"key\":\"val\"}", project.get("labels"));
        assertNotNull(project.get("created_at"));

        // Verify labels parse as JSON
        JsonNode labelsNode = mapper.readTree((String) project.get("labels"));
        assertTrue(labelsNode.has("key"));
        assertEquals("val", labelsNode.get("key").asText());
    }
}
