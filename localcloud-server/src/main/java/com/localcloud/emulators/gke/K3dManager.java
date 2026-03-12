package com.localcloud.emulators.gke;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Wraps the k3d CLI for creating/managing lightweight Kubernetes clusters.
 * All cluster names are prefixed with "lc-" to avoid collisions.
 */
public class K3dManager {

    private static final Logger logger = LoggerFactory.getLogger(K3dManager.class);
    private static final String CLUSTER_PREFIX = "lc-";

    /**
     * Create a k3d cluster.
     *
     * @param name    cluster name (will be prefixed with "lc-")
     * @param apiPort Kubernetes API port to expose
     * @return the full cluster name (with prefix)
     */
    public String createCluster(String name, int apiPort) {
        String fullName = CLUSTER_PREFIX + name;
        String cmd = String.format("k3d cluster create %s --api-port %d --no-lb --wait", fullName, apiPort);
        execCommand(cmd, 120);
        logger.info("Created k3d cluster: {} (API port {})", fullName, apiPort);
        return fullName;
    }

    /**
     * Get the kubeconfig for a cluster.
     */
    public String getKubeconfig(String name) {
        String fullName = name.startsWith(CLUSTER_PREFIX) ? name : CLUSTER_PREFIX + name;
        return execCommand("k3d kubeconfig get " + fullName, 30);
    }

    /**
     * Delete a k3d cluster.
     */
    public void deleteCluster(String name) {
        String fullName = name.startsWith(CLUSTER_PREFIX) ? name : CLUSTER_PREFIX + name;
        execCommand("k3d cluster delete " + fullName, 60);
        logger.info("Deleted k3d cluster: {}", fullName);
    }

    /**
     * Check if a cluster exists.
     */
    public boolean clusterExists(String name) {
        String fullName = name.startsWith(CLUSTER_PREFIX) ? name : CLUSTER_PREFIX + name;
        try {
            String output = execCommand("k3d cluster list -o json", 15);
            return output.contains("\"" + fullName + "\"");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Delete all clusters with the "lc-" prefix.
     */
    public void deleteAllClusters() {
        try {
            String output = execCommand("k3d cluster list --no-headers", 15);
            for (String line : output.split("\n")) {
                String clusterName = line.trim().split("\\s+")[0];
                if (clusterName.startsWith(CLUSTER_PREFIX)) {
                    try {
                        deleteCluster(clusterName);
                    } catch (Exception e) {
                        logger.warn("Failed to delete cluster {}: {}", clusterName, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to list k3d clusters for cleanup: {}", e.getMessage());
        }
    }

    private String execCommand(String command, int timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("Command timed out: " + command);
            }
            if (process.exitValue() != 0) {
                throw new RuntimeException("Command failed (exit " + process.exitValue() + "): " + output);
            }
            return output;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute: " + command, e);
        }
    }
}
