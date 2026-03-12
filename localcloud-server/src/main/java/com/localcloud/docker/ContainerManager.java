package com.localcloud.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Manages Docker container lifecycle for emulated infrastructure services
 * (Compute Engine instances, Cloud Run services, etc.).
 * All containers are labelled with {@code localcloud.managed=true} for cleanup.
 */
public class ContainerManager {

    private static final Logger logger = LoggerFactory.getLogger(ContainerManager.class);

    private final DockerClient docker;

    public ContainerManager(DockerClient docker) {
        this.docker = docker;
    }

    /**
     * Pull an image, create a container, and start it.
     *
     * @param image  Docker image (e.g. "ubuntu:22.04")
     * @param name   container name
     * @param ports  container port → host port mappings
     * @param env    environment variables
     * @param labels additional labels (merged with managed label)
     * @return the container ID
     */
    public String createAndStart(String image, String name,
                                 Map<Integer, Integer> ports,
                                 Map<String, String> env,
                                 Map<String, String> labels) {
        // Pull image if not present
        try {
            docker.inspectImageCmd(image).exec();
        } catch (Exception e) {
            logger.info("Pulling image {}...", image);
            try {
                docker.pullImageCmd(image).start().awaitCompletion();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while pulling image " + image, ie);
            }
        }

        // Build environment list
        List<String> envList = new ArrayList<>();
        if (env != null) {
            env.forEach((k, v) -> envList.add(k + "=" + v));
        }

        // Build labels
        var allLabels = new java.util.HashMap<>(labels != null ? labels : Map.of());
        allLabels.put("localcloud.managed", "true");

        // Build port bindings
        Ports portBindings = new Ports();
        List<ExposedPort> exposedPorts = new ArrayList<>();
        if (ports != null) {
            ports.forEach((containerPort, hostPort) -> {
                ExposedPort ep = ExposedPort.tcp(containerPort);
                exposedPorts.add(ep);
                portBindings.bind(ep, Ports.Binding.bindPort(hostPort));
            });
        }

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withPortBindings(portBindings);

        CreateContainerResponse container = docker.createContainerCmd(image)
                .withName(name)
                .withEnv(envList)
                .withLabels(allLabels)
                .withExposedPorts(exposedPorts)
                .withHostConfig(hostConfig)
                .exec();

        docker.startContainerCmd(container.getId()).exec();
        logger.info("Started container {} ({})", name, container.getId().substring(0, 12));
        return container.getId();
    }

    /**
     * Stop a running container.
     */
    public void stop(String containerId) {
        try {
            docker.stopContainerCmd(containerId).withTimeout(10).exec();
            logger.info("Stopped container {}", containerId.substring(0, 12));
        } catch (Exception e) {
            logger.warn("Failed to stop container {}: {}", containerId.substring(0, 12), e.getMessage());
        }
    }

    /**
     * Remove a container (force).
     */
    public void remove(String containerId) {
        try {
            docker.removeContainerCmd(containerId).withForce(true).exec();
            logger.info("Removed container {}", containerId.substring(0, 12));
        } catch (Exception e) {
            logger.warn("Failed to remove container {}: {}", containerId.substring(0, 12), e.getMessage());
        }
    }

    /**
     * Get the status of a container (e.g. "running", "exited").
     */
    public String getStatus(String containerId) {
        try {
            InspectContainerResponse info = docker.inspectContainerCmd(containerId).exec();
            InspectContainerResponse.ContainerState state = info.getState();
            return state != null && state.getStatus() != null ? state.getStatus() : "unknown";
        } catch (Exception e) {
            return "not_found";
        }
    }

    /**
     * List containers matching a specific label key=value.
     */
    public List<Container> listByLabel(String key, String value) {
        return docker.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of(key, value))
                .exec();
    }

    /**
     * Get the Docker client (for advanced operations).
     */
    public DockerClient getDockerClient() {
        return docker;
    }
}
