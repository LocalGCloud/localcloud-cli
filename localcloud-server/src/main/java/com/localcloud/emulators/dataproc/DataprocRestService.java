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
            if (clusterName == null || clusterName.isBlank())
                return RestResponseHelper.error(400, "Missing clusterName");

            if (repo.clusterExists(project, region, clusterName))
                return RestResponseHelper.error(409, "Cluster already exists");

            // Parse labels from the request body
            Map<String, String> labels = new HashMap<>();
            if (root.has("labels")) {
                var labelsNode = root.get("labels");
                var fieldNames = labelsNode.fieldNames();
                while (fieldNames.hasNext()) {
                    String key = fieldNames.next();
                    labels.put(key, labelsNode.path(key).asText(""));
                }
            }

            // Parse image version from config.softwareConfig
            String imageVersion = "";
            if (root.has("config") && root.get("config").has("softwareConfig")) {
                imageVersion = root.get("config").get("softwareConfig").path("imageVersion").asText("");
            }

            com.google.cloud.dataproc.v1.Cluster cluster = com.google.cloud.dataproc.v1.Cluster.newBuilder()
                    .setProjectId(project)
                    .putAllLabels(labels)
                    .setConfig(com.google.cloud.dataproc.v1.ClusterConfig.newBuilder()
                            .setSoftwareConfig(com.google.cloud.dataproc.v1.SoftwareConfig.newBuilder()
                                    .setImageVersion(imageVersion)))
                    .setStatus(com.google.cloud.dataproc.v1.ClusterStatus.newBuilder()
                            .setState(com.google.cloud.dataproc.v1.ClusterStatus.State.RUNNING)
                            .build())
                    .build();
            repo.createCluster(project, region, clusterName, cluster);

            // Start cluster container if in CLUSTER mode
            emulator.startClusterContainer(project, region, clusterName, cluster);

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
            result.put("clusterUuid", c.getClusterUuid());
            result.set("labels", RestResponseHelper.MAPPER.valueToTree(c.getLabelsMap()));
            result.set("status", RestResponseHelper.MAPPER.createObjectNode().put("state", "RUNNING"));
            var config = result.putObject("config");
            config.put("imageVersion", c.getConfig().getSoftwareConfig().getImageVersion());
            return RestResponseHelper.ok(result);
        } catch (Exception e) {
            return RestResponseHelper.error(500, e.getMessage());
        }
    }

    @Get("/projects/{project}/regions/{region}/clusters")
    public HttpResponse listClusters(@Param String project, @Param String region) {
        emulator.incrementRequestCount();
        try {
            List<com.google.cloud.dataproc.v1.Cluster> clusters = repo.listClusters(project, region);
            ObjectNode result = RestResponseHelper.MAPPER.createObjectNode();
            var array = result.putArray("clusters");
            for (var c : clusters) {
                ObjectNode item = array.addObject();
                item.put("projectId", c.getProjectId());
                item.put("clusterName", c.getClusterName());
                item.put("clusterUuid", c.getClusterUuid());
                item.set("labels", RestResponseHelper.MAPPER.valueToTree(c.getLabelsMap()));
                ObjectNode status = item.putObject("status");
                status.put("state", "RUNNING");
                var config = item.putObject("config");
                config.put("imageVersion", c.getConfig().getSoftwareConfig().getImageVersion());
            }
            return RestResponseHelper.ok(result);
        } catch (Exception e) {
            return RestResponseHelper.error(500, e.getMessage());
        }
    }

    @Delete("/projects/{project}/regions/{region}/clusters/{cluster}")
    public HttpResponse deleteCluster(@Param String project, @Param String region, @Param String cluster) {
        emulator.incrementRequestCount();
        try {
            emulator.stopClusterContainer(project, region, cluster);
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

    @Get("/projects/{project}/regions/{region}/jobs")
    public HttpResponse listJobs(@Param String project, @Param String region) {
        emulator.incrementRequestCount();
        try {
            List<com.google.cloud.dataproc.v1.Job> jobs = repo.listJobs(project, region);
            ObjectNode result = RestResponseHelper.MAPPER.createObjectNode();
            var array = result.putArray("jobs");
            for (var j : jobs) {
                ObjectNode item = array.addObject();
                item.put("jobId", j.getReference().getJobId());
                item.put("projectId", j.getReference().getProjectId());
                item.put("clusterName", j.getPlacement().getClusterName());
                item.put("status", j.getStatus().getState().name());
                item.put("done", j.getDone());
                if (!j.getDriverOutputResourceUri().isBlank())
                    item.put("driverOutputUri", j.getDriverOutputResourceUri());
            }
            return RestResponseHelper.ok(result);
        } catch (Exception e) {
            return RestResponseHelper.error(500, e.getMessage());
        }
    }

    @Get("/projects/{project}/regions/{region}/jobs/{jobId}")
    public HttpResponse getJob(@Param String project, @Param String region, @Param String jobId) {
        emulator.incrementRequestCount();
        try {
            com.google.cloud.dataproc.v1.Job j = repo.getJob(project, region, jobId);
            if (j == null) return RestResponseHelper.error(404, "Job not found: " + jobId);
            ObjectNode result = RestResponseHelper.MAPPER.createObjectNode();
            result.put("jobId", j.getReference().getJobId());
            result.put("projectId", j.getReference().getProjectId());
            result.put("clusterName", j.getPlacement().getClusterName());
            result.put("status", j.getStatus().getState().name());
            result.put("done", j.getDone());
            if (!j.getDriverOutputResourceUri().isBlank())
                result.put("driverOutputUri", j.getDriverOutputResourceUri());
            return RestResponseHelper.ok(result);
        } catch (Exception e) {
            return RestResponseHelper.error(500, e.getMessage());
        }
    }

    public HttpResponse cancelJob(String project, String region, String jobId) {
        emulator.incrementRequestCount();
        try {
            com.google.cloud.dataproc.v1.Job j = repo.getJob(project, region, jobId);
            if (j == null) return RestResponseHelper.error(404, "Job not found: " + jobId);
            com.google.cloud.dataproc.v1.Job cancelled = j.toBuilder()
                    .setStatus(com.google.cloud.dataproc.v1.JobStatus.newBuilder()
                            .setState(com.google.cloud.dataproc.v1.JobStatus.State.CANCELLED)
                            .setStateStartTime(com.localcloud.emulators.common.GrpcSupport
                                    .timestamp(java.time.Instant.now())))
                    .setDone(true)
                    .build();
            repo.updateJob(project, region, jobId,
                    com.google.cloud.dataproc.v1.JobStatus.State.CANCELLED.name(), cancelled);
            ObjectNode result = RestResponseHelper.MAPPER.createObjectNode();
            result.put("jobId", jobId);
            result.put("status", "CANCELLED");
            result.put("done", true);
            return RestResponseHelper.ok(result);
        } catch (Exception e) {
            return RestResponseHelper.error(500, e.getMessage());
        }
    }
}
