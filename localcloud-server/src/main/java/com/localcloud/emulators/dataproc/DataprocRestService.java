package com.localcloud.emulators.dataproc;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.Delete;
import com.localcloud.common.RestResponseHelper;

import java.util.*;

/**
 * REST endpoints for Dataproc cluster management.
 * <p>
 * The gRPC HTTP/JSON transcoding does not correctly map the Terraform provider v6
 * paths for Dataproc cluster CRUD, so we provide explicit REST handlers.
 */
public class DataprocRestService {

    private final DataprocRepository repo;
    private final DataprocEmulator emulator;

    public DataprocRestService(DataprocRepository repo, DataprocEmulator emulator) {
        this.repo = repo;
        this.emulator = emulator;
    }

    @Post("/projects/{project}/regions/{region}/clusters")
    public HttpResponse createCluster(@Param String project, @Param String region, String body) {
        emulator.incrementRequestCount();
        try {
            var root = RestResponseHelper.parseBody(body);
            String clusterName = root.path("clusterName").asText(null);
            if (clusterName == null) clusterName = root.path("projectId") != null ? root.path("clusterName").asText(null) : null;
            if (clusterName == null || clusterName.isBlank()) return RestResponseHelper.error(400, "Missing clusterName");

            if (repo.clusterExists(project, region, clusterName)) return RestResponseHelper.error(409, "Cluster already exists");

            com.google.cloud.dataproc.v1.Cluster cluster = com.google.cloud.dataproc.v1.Cluster.newBuilder()
                    .setProjectId(project)
                    .setClusterName(clusterName)
                    .setStatus(com.google.cloud.dataproc.v1.ClusterStatus.newBuilder()
                            .setState(com.google.cloud.dataproc.v1.ClusterStatus.State.RUNNING)
                            .build())
                    .build();
            repo.createCluster(project, region, clusterName, cluster);

            ObjectNode op = RestResponseHelper.MAPPER.createObjectNode();
            op.put("name", "projects/" + project + "/regions/" + region + "/operations/create-" + clusterName);
            op.put("done", true);
            ObjectNode resp = op.putObject("response");
            resp.put("@type", "type.googleapis.com/google.cloud.dataproc.v1.Cluster");
            resp.put("projectId", project);
            resp.put("clusterName", clusterName);
            resp.set("labels", root.has("labels") ? root.get("labels") : RestResponseHelper.MAPPER.createObjectNode());
            ObjectNode status = resp.putObject("status");
            status.put("state", "RUNNING");
            return RestResponseHelper.ok(op);
        } catch (Exception e) {
            return RestResponseHelper.error(500, e.getMessage());
        }
    }

    @Get("/projects/{project}/regions/{region}/clusters/{cluster}")
    public HttpResponse getCluster(@Param String project, @Param String region, @Param String cluster) {
        emulator.incrementRequestCount();
        try {
            com.google.cloud.dataproc.v1.Cluster c = repo.getCluster(project, region, cluster);
            if (c == null) return RestResponseHelper.error(404, "Cluster not found");
            ObjectNode result = RestResponseHelper.MAPPER.createObjectNode();
            result.put("projectId", c.getProjectId());
            result.put("clusterName", c.getClusterName());
            result.set("status", RestResponseHelper.MAPPER.createObjectNode().put("state", "RUNNING"));
            return RestResponseHelper.ok(result);
        } catch (Exception e) {
            return RestResponseHelper.error(500, e.getMessage());
        }
    }

    @Get("/projects/{project}/regions/{region}/clusters")
    public HttpResponse listClusters(@Param String project, @Param String region) {
        emulator.incrementRequestCount();
        try {
            ObjectNode result = RestResponseHelper.MAPPER.createObjectNode();
            result.putArray("clusters");
            return RestResponseHelper.ok(result);
        } catch (Exception e) {
            return RestResponseHelper.error(500, e.getMessage());
        }
    }

    @Delete("/projects/{project}/regions/{region}/clusters/{cluster}")
    public HttpResponse deleteCluster(@Param String project, @Param String region, @Param String cluster) {
        emulator.incrementRequestCount();
        try {
            repo.deleteCluster(project, region, cluster);
        } catch (Exception e) { /* ignore */ }
        try {
            ObjectNode op = RestResponseHelper.MAPPER.createObjectNode();
            op.put("name", "projects/" + project + "/regions/" + region + "/operations/delete-" + cluster);
            op.put("done", true);
            return RestResponseHelper.ok(op);
        } catch (Exception e) {
            return RestResponseHelper.error(500, e.getMessage());
        }
    }
}
