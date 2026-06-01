package com.localcloud.emulators.alloydb;

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
import com.localcloud.common.RestResponseHelper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * REST endpoints for AlloyDB cluster management.
 * <p>
 * The gRPC HTTP/JSON transcoding does not correctly map the Terraform provider v6/v7
 * paths for AlloyDB cluster CRUD, so we provide explicit REST handlers.
 */
public class AlloyDBRestService {

    private final AlloyDBRepository repo;
    private final AlloyDBEmulator emulator;

    public AlloyDBRestService(AlloyDBRepository repo, AlloyDBEmulator emulator) {
        this.repo = repo;
        this.emulator = emulator;
    }

    @Post("/projects/{project}/locations/{location}/clusters")
    public HttpResponse createCluster(ServiceRequestContext ctx, @Param String project,
                                      @Param String location, String body) {
        emulator.incrementRequestCount();
        try {
            var root = RestResponseHelper.parseBody(body);
            String clusterId = ctx.queryParams().get("clusterId");
            if (clusterId == null || clusterId.isBlank()) {
                clusterId = root.path("cluster").path("clusterId").asText(null);
                if (clusterId == null) clusterId = root.path("clusterId").asText(null);
            }
            if (clusterId == null || clusterId.isBlank()) return RestResponseHelper.error(400, "Missing clusterId");
            if (repo.clusterExists(project, location, clusterId)) return RestResponseHelper.error(409, "Cluster already exists");

            String displayName = root.path("cluster").path("displayName").asText(clusterId);
            String network = root.path("cluster").path("network").asText("projects/" + project + "/global/networks/default");
            String now = Instant.now().toString();

            Cluster cluster = Cluster.newBuilder()
                    .setName("projects/" + project + "/locations/" + location + "/clusters/" + clusterId)
                    .setDisplayName(displayName)
                    .setNetworkConfig(Cluster.NetworkConfig.newBuilder().setNetwork(network).build())
                    .setState(Cluster.State.READY)
                    .build();
            repo.createCluster(project, location, clusterId, cluster);

            ObjectNode op = RestResponseHelper.MAPPER.createObjectNode();
            op.put("name", "projects/" + project + "/locations/" + location + "/operations/" + UUID.randomUUID().toString().substring(0, 8));
            op.put("done", true);
            ObjectNode resp = op.putObject("response");
            resp.put("@type", "type.googleapis.com/google.cloud.alloydb.v1.Cluster");
            resp.put("name", cluster.getName());
            resp.put("displayName", displayName);
            resp.put("state", "READY");
            resp.put("network", network);
            resp.put("uid", UUID.randomUUID().toString());
            resp.put("createTime", now);
            resp.put("updateTime", now);
            resp.set("labels", root.path("cluster").has("labels") ? root.path("cluster").get("labels") : RestResponseHelper.MAPPER.createObjectNode());
            return RestResponseHelper.ok(op);
        } catch (Exception e) {
            return RestResponseHelper.error(500, "Failed to create cluster: " + e.getMessage());
        }
    }

    @Get("/projects/{project}/locations/{location}/clusters/{cluster}")
    public HttpResponse getCluster(@Param String project, @Param String location, @Param String cluster) {
        emulator.incrementRequestCount();
        try {
            Cluster c = repo.getCluster(project, location, cluster);
            if (c == null) return RestResponseHelper.error(404, "Cluster not found");
            ObjectNode result = RestResponseHelper.MAPPER.createObjectNode();
            result.put("name", c.getName());
            result.put("displayName", c.getDisplayName());
            result.put("state", "READY");
            result.put("network", c.getNetworkConfig().getNetwork());
            result.put("uid", UUID.randomUUID().toString());
            if (c.getCreateTime().getSeconds() > 0) {
                result.put("createTime", Instant.ofEpochSecond(c.getCreateTime().getSeconds(), c.getCreateTime().getNanos()).toString());
            }
            return RestResponseHelper.ok(result);
        } catch (Exception e) {
            return RestResponseHelper.error(500, "Failed to get cluster: " + e.getMessage());
        }
    }

    @Get("/projects/{project}/locations/{location}/clusters")
    public HttpResponse listClusters(@Param String project, @Param String location) {
        emulator.incrementRequestCount();
        try {
            List<Cluster> clusters = repo.listClusters(project, location);
            ObjectNode result = RestResponseHelper.MAPPER.createObjectNode();
            ArrayNode arr = result.putArray("clusters");
            for (Cluster c : clusters) {
                ObjectNode node = arr.addObject();
                node.put("name", c.getName());
                node.put("displayName", c.getDisplayName());
                node.put("state", "READY");
                node.put("network", c.getNetworkConfig().getNetwork());
            }
            return RestResponseHelper.ok(result);
        } catch (Exception e) {
            return RestResponseHelper.error(500, "Failed to list clusters: " + e.getMessage());
        }
    }
}
