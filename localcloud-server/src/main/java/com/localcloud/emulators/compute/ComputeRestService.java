package com.localcloud.emulators.compute;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.*;
import com.localcloud.admin.UnsupportedOperationResponses;
import com.localcloud.docker.ContainerManager;
import com.localcloud.emulators.iam.IAMPolicyRestHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Armeria annotated REST service implementing the Compute Engine instances API.
 * Routes: /compute/v1/projects/{project}/zones/{zone}/instances[/{instance}]
 */
public class ComputeRestService {

    private static final Logger logger = LoggerFactory.getLogger(ComputeRestService.class);

    private final ComputeStore store;
    private final ContainerManager containerManager;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ComputeEmulator emulator;
    private final IAMPolicyRestHandler iamHandler;

    public ComputeRestService(ComputeStore store, ContainerManager containerManager, ComputeEmulator emulator) {
        this(store, containerManager, emulator, null);
    }

    public ComputeRestService(ComputeStore store, ContainerManager containerManager, ComputeEmulator emulator, IAMPolicyRestHandler iamHandler) {
        this.store = store;
        this.containerManager = containerManager;
        this.emulator = emulator;
        this.iamHandler = iamHandler;
    }

    @Post("/projects/{project}/zones/{zone}/instances")
    public HttpResponse insertInstance(@Param String project, @Param String zone,
                                       String body) {
        emulator.incrementRequestCount();
        try {
            ObjectNode reqBody = (ObjectNode) mapper.readTree(body);
            String name = reqBody.path("name").asText("");
            if (name.isEmpty()) {
                return errorResponse(HttpStatus.BAD_REQUEST, "Instance name is required");
            }

            // Check if already exists
            if (store.getInstance(project, zone, name).isPresent()) {
                return errorResponse(HttpStatus.CONFLICT, "Instance " + name + " already exists");
            }

            String machineType = reqBody.path("machineType").asText("e2-medium");
            String containerImage = "ubuntu:22.04";
            // Check if a container image is specified in metadata
            if (reqBody.has("metadata") && reqBody.get("metadata").has("items")) {
                for (var item : reqBody.get("metadata").get("items")) {
                    if ("container-image".equals(item.path("key").asText())) {
                        containerImage = item.path("value").asText(containerImage);
                    }
                }
            }

            String containerName = "lc-compute-" + project + "-" + name;
            String containerId;
            try {
                String credPath = emulator.getCredentialBroker() != null
                        ? emulator.getCredentialBroker().getCredentialFilePath() : null;
                String credProject = emulator.getCredentialBroker() != null
                        && emulator.getCredentialBroker().getProject() != null
                        ? emulator.getCredentialBroker().getProject() : project;
                containerId = containerManager.createAndStart(
                        containerImage, containerName,
                        Map.of(), Map.of(),
                        Map.of("localcloud.service", "compute",
                               "localcloud.project", project,
                               "localcloud.instance", name),
                        credPath, credProject);
            } catch (Exception e) {
                logger.warn("Failed to create container for instance {}: {}", name, e.getMessage());
                containerId = "simulated-" + name;
            }

            // Generate a pseudo-unique IP in the 10.128.0.0/20 range
            int ipSuffix = (name.hashCode() & 0xFF) + 2; // 2-257
            String networkIp = "10.128.0." + Math.min(ipSuffix, 254);

            var instance = new ComputeStore.Instance(
                    project, zone, name, machineType, "RUNNING",
                    containerId, containerImage, networkIp,
                    reqBody.has("metadata") ? reqBody.get("metadata").toString() : "{}",
                    null);
            store.insertInstance(instance);

            return jsonResponse(HttpStatus.OK, instanceToJson(
                    store.getInstance(project, zone, name).orElse(instance)));
        } catch (Exception e) {
            logger.error("insertInstance failed", e);
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Get("/projects/{project}/zones/{zone}/instances/{instance}")
    public HttpResponse getInstance(@Param String project, @Param String zone,
                                     @Param String instance) {
        emulator.incrementRequestCount();
        try {
            return store.getInstance(project, zone, instance)
                    .map(i -> jsonResponse(HttpStatus.OK, instanceToJson(i)))
                    .orElse(errorResponse(HttpStatus.NOT_FOUND,
                            "Instance " + instance + " not found"));
        } catch (Exception e) {
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Get("/projects/{project}/zones/{zone}/instances")
    public HttpResponse listInstances(@Param String project, @Param String zone) {
        emulator.incrementRequestCount();
        try {
            var instances = store.listInstances(project, zone);
            ObjectNode response = mapper.createObjectNode();
            response.put("kind", "compute#instanceList");
            ArrayNode items = response.putArray("items");
            for (var inst : instances) {
                items.add(instanceToJson(inst));
            }
            return jsonResponse(HttpStatus.OK, response);
        } catch (Exception e) {
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Post("/projects/{project}/zones/{zone}/instances/{instance}/start")
    public HttpResponse startInstance(@Param String project, @Param String zone,
                                      @Param String instance) {
        emulator.incrementRequestCount();
        try {
            var opt = store.getInstance(project, zone, instance);
            if (opt.isEmpty()) {
                return errorResponse(HttpStatus.NOT_FOUND, "Instance " + instance + " not found");
            }
            var inst = opt.get();
            if (inst.containerId() != null && !inst.containerId().startsWith("simulated-")) {
                try {
                    containerManager.getDockerClient().startContainerCmd(inst.containerId()).exec();
                } catch (Exception e) {
                    logger.warn("Failed to start container: {}", e.getMessage());
                }
            }
            store.updateStatus(project, zone, instance, "RUNNING");
            return jsonResponse(HttpStatus.OK, operationJson(project, zone, "start", instance));
        } catch (Exception e) {
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Post("/projects/{project}/zones/{zone}/instances/{instance}/stop")
    public HttpResponse stopInstance(@Param String project, @Param String zone,
                                     @Param String instance) {
        emulator.incrementRequestCount();
        try {
            var opt = store.getInstance(project, zone, instance);
            if (opt.isEmpty()) {
                return errorResponse(HttpStatus.NOT_FOUND, "Instance " + instance + " not found");
            }
            var inst = opt.get();
            if (inst.containerId() != null && !inst.containerId().startsWith("simulated-")) {
                containerManager.stop(inst.containerId());
            }
            store.updateStatus(project, zone, instance, "TERMINATED");
            return jsonResponse(HttpStatus.OK, operationJson(project, zone, "stop", instance));
        } catch (Exception e) {
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Delete("/projects/{project}/zones/{zone}/instances/{instance}")
    public HttpResponse deleteInstance(@Param String project, @Param String zone,
                                       @Param String instance) {
        emulator.incrementRequestCount();
        try {
            var opt = store.getInstance(project, zone, instance);
            if (opt.isEmpty()) {
                return errorResponse(HttpStatus.NOT_FOUND, "Instance " + instance + " not found");
            }
            var inst = opt.get();
            if (inst.containerId() != null && !inst.containerId().startsWith("simulated-")) {
                containerManager.stop(inst.containerId());
                containerManager.remove(inst.containerId());
            }
            store.deleteInstance(project, zone, instance);
            return jsonResponse(HttpStatus.OK, operationJson(project, zone, "delete", instance));
        } catch (Exception e) {
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Post("/projects/{project}/zones/{zone}/disks")
    public HttpResponse insertDisk(@Param String project, @Param String zone, String body) {
        emulator.incrementRequestCount();
        return UnsupportedOperationResponses.rest("compute", "disks.insert", "rest",
                "Use instance metadata workflows; persistent disks are not emulated yet.");
    }

    // --- JSON helpers ---

    private ObjectNode instanceToJson(ComputeStore.Instance inst) {
        ObjectNode json = mapper.createObjectNode();
        json.put("kind", "compute#instance");
        json.put("id", inst.instanceName().hashCode() & 0x7FFFFFFF);
        json.put("name", inst.instanceName());
        json.put("zone", "projects/" + inst.projectId() + "/zones/" + inst.zone());
        json.put("machineType", "projects/" + inst.projectId() + "/zones/" + inst.zone() + "/machineTypes/" + inst.machineType());
        json.put("status", inst.status());
        json.put("selfLink", "projects/" + inst.projectId() + "/zones/" + inst.zone() + "/instances/" + inst.instanceName());

        // Network interfaces
        ArrayNode networkInterfaces = json.putArray("networkInterfaces");
        ObjectNode nic = networkInterfaces.addObject();
        nic.put("networkIP", inst.networkIp() != null ? inst.networkIp() : "10.128.0.2");
        nic.put("name", "nic0");

        if (inst.createdAt() != null) {
            json.put("creationTimestamp", inst.createdAt().toInstant().toString());
        }
        return json;
    }

    // IAM Policy endpoints are handled by the generic catch-all in LocalCloudApplication.

    private ObjectNode operationJson(String project, String zone, String operationType, String target) {
        ObjectNode op = mapper.createObjectNode();
        op.put("kind", "compute#operation");
        op.put("id", String.valueOf(System.nanoTime()));
        op.put("name", "operation-" + System.currentTimeMillis());
        op.put("zone", "projects/" + project + "/zones/" + zone);
        op.put("operationType", operationType);
        op.put("targetLink", "projects/" + project + "/zones/" + zone + "/instances/" + target);
        op.put("status", "DONE");
        return op;
    }

    private HttpResponse jsonResponse(HttpStatus status, ObjectNode json) {
        return HttpResponse.of(status, MediaType.JSON, json.toString());
    }

    private HttpResponse errorResponse(HttpStatus status, String message) {
        ObjectNode error = mapper.createObjectNode();
        ObjectNode errorDetail = error.putObject("error");
        errorDetail.put("code", status.code());
        errorDetail.put("message", message);
        return HttpResponse.of(status, MediaType.JSON, error.toString());
    }
}
