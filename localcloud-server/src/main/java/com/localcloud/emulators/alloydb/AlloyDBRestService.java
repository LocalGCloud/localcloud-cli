package com.localcloud.emulators.alloydb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.alloydb.v1.Cluster;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * REST endpoints for AlloyDB cluster management.
 * <p>
 * The gRPC HTTP/JSON transcoding does not correctly map the Terraform provider v6
 * paths for AlloyDB cluster CRUD, so we provide explicit REST handlers.
 * <p>
 * Registered at /v1 under the AlloyDB emulator's prefix.
 */
public class AlloyDBRestService {

    private final AlloyDBRepository repo;
    private final AlloyDBEmulator emulator;
    private final ObjectMapper mapper = new ObjectMapper();

    public AlloyDBRestService(AlloyDBRepository repo, AlloyDBEmulator emulator) {
        this.repo = repo;
        this.emulator = emulator;
    }

    @Post("/projects/{project}/locations/{location}/clusters")
    public HttpResponse createCluster(ServiceRequestContext ctx, @Param String project,
                                      @Param String location, String body) {
        emulator.incrementRequestCount();
        try {
            // Parse clusterId from query param or body
            String clusterId = ctx.queryParams().get("clusterId");
            if (clusterId == null || clusterId.isBlank()) {
                var root = mapper.readTree(body);
                clusterId = root.path("cluster").path("clusterId").asText(null);
                if (clusterId == null) clusterId = root.path("clusterId").asText(null);
            }
            if (clusterId == null || clusterId.isBlank()) {
                return error(400, "Missing required parameter: clusterId");
            }

            if (repo.clusterExists(project, location, clusterId)) {
                return error(409, "Cluster already exists: " + clusterId);
            }

            var root = mapper.readTree(body);
            String displayName = root.path("cluster").path("displayName").asText(clusterId);
            String network = root.path("cluster").path("network").asText("projects/" + project + "/global/networks/default");

            // Build cluster proto and store
            String now = Instant.now().toString();
            Cluster cluster = Cluster.newBuilder()
                    .setName("projects/" + project + "/locations/" + location + "/clusters/" + clusterId)
                    .setDisplayName(displayName)
                    .setNetwork(network)
                    .setState(Cluster.State.READY)
                    .build();
            repo.createCluster(project, location, clusterId, cluster);

            // Return LRO response with JSON cluster
            ObjectNode operation = mapper.createObjectNode();
            operation.put("name", "projects/" + project + "/locations/" + location + "/operations/" + UUID.randomUUID().toString().substring(0, 8));
            operation.put("done", true);
            ObjectNode response = operation.putObject("response");
            response.put("@type", "type.googleapis.com/google.cloud.alloydb.v1.Cluster");
            response.put("name", "projects/" + project + "/locations/" + location + "/clusters/" + clusterId);
            response.put("displayName", displayName);
            response.put("state", "READY");
            response.put("network", network);
            response.put("uid", UUID.randomUUID().toString());
            response.put("createTime", now);
            response.put("updateTime", now);
            if (root.path("cluster").has("labels")) {
                response.set("labels", root.path("cluster").get("labels"));
            } else {
                response.set("labels", mapper.createObjectNode());
            }
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(operation));
        } catch (Exception e) {
            return error(500, "Failed to create cluster: " + e.getMessage());
        }
    }

    @Get("/projects/{project}/locations/{location}/clusters/{cluster}")
    public HttpResponse getCluster(@Param String project, @Param String location, @Param String cluster) {
        emulator.incrementRequestCount();
        try {
            Cluster c = repo.getCluster(project, location, cluster);
            if (c == null) {
                return error(404, "Cluster not found: " + cluster);
            }
            ObjectNode result = mapper.createObjectNode();
            result.put("name", c.getName());
            result.put("displayName", c.getDisplayName());
            result.put("state", "READY");
            result.put("network", c.getNetwork());
            result.put("uid", UUID.randomUUID().toString());
            if (c.getCreateTime().getSeconds() > 0) {
                result.put("createTime", Instant.ofEpochSecond(c.getCreateTime().getSeconds(), c.getCreateTime().getNanos()).toString());
            }
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(result));
        } catch (Exception e) {
            return error(500, "Failed to get cluster: " + e.getMessage());
        }
    }

    @Get("/projects/{project}/locations/{location}/clusters")
    public HttpResponse listClusters(@Param String project, @Param String location) {
        emulator.incrementRequestCount();
        try {
            List<Cluster> clusters = repo.listClusters(project, location);
            ObjectNode result = mapper.createObjectNode();
            ArrayNode arr = result.putArray("clusters");
            for (Cluster c : clusters) {
                ObjectNode node = arr.addObject();
                node.put("name", c.getName());
                node.put("displayName", c.getDisplayName());
                node.put("state", "READY");
                node.put("network", c.getNetwork());
            }
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(result));
        } catch (Exception e) {
            return error(500, "Failed to list clusters: " + e.getMessage());
        }
    }

    private HttpResponse error(int code, String message) {
        ObjectNode out = mapper.createObjectNode();
        ObjectNode inner = out.putObject("error");
        inner.put("code", code);
        inner.put("message", message);
        inner.put("status", String.valueOf(code));
        return HttpResponse.of(HttpStatus.valueOf(code), MediaType.JSON, out.toString());
    }
}
