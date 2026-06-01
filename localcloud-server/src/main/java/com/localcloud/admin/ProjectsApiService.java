package com.localcloud.admin;

import static com.localcloud.admin.AdminApiSupport.*;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.localcloud.config.LocalCloudConfig;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Project CRUD operations. Extracted from AdminApiService.
 */
public class ProjectsApiService {

    private static final Logger logger = LoggerFactory.getLogger(ProjectsApiService.class);
    private final LocalCloudConfig config;
    private final ProjectService projectService;

    public ProjectsApiService(LocalCloudConfig config, ProjectService projectService) {
        this.config = config;
        this.projectService = projectService;
    }

    @Get("/projects")
    public HttpResponse listProjects() {
        try {
            var projects = projectService.listProjects();
            String json = mapper().writerWithDefaultPrettyPrinter().writeValueAsString(projects);
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, json);
        } catch (Exception e) {
            logger.error("Error listing projects", e);
            return errorResponse(e);
        }
    }

    @SuppressWarnings("unchecked")
    @Post("/projects")
    public HttpResponse createProject(AggregatedHttpRequest request) {
        try {
            Map<String, Object> body = mapper().readValue(request.contentUtf8(), Map.class);
            String projectId = (String) body.get("project_id");
            String displayName = (String) body.getOrDefault("display_name", projectId);
            String location = (String) body.get("location");
            String zone = (String) body.get("zone");

            if (projectId == null || projectId.isBlank()) {
                return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON,
                        mapper().writeValueAsString(Map.of("error", true, "message", "project_id is required")));
            }
            var project = projectService.createProject(projectId, displayName, location, zone);
            return HttpResponse.of(HttpStatus.CREATED, MediaType.JSON,
                    mapper().writerWithDefaultPrettyPrinter().writeValueAsString(project));
        } catch (Exception e) {
            logger.error("Error creating project", e);
            return errorResponse(e);
        }
    }

    @Delete("/projects/{id}")
    public HttpResponse deleteProject(@Param("id") String projectId) {
        try {
            projectService.deleteProject(projectId, config.getProjectId());
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper().writeValueAsString(Map.of("deleted", true, "project_id", projectId)));
        } catch (IllegalArgumentException e) {
            try {
                return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON,
                        mapper().writeValueAsString(Map.of("error", true, "message", e.getMessage())));
            } catch (Exception ex) {
                return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT_UTF_8, e.getMessage());
            }
        } catch (Exception e) {
            logger.error("Error deleting project '{}'", projectId, e);
            return errorResponse(e);
        }
    }
}
