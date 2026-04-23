package com.localcloud.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central service registry loaded from {@code services.yaml}.
 * Single source of truth for service definitions (ports, env vars, health checks, etc.).
 *
 * <p>File discovery order:
 * <ol>
 *   <li>{@code /etc/localcloud/services.yaml}</li>
 *   <li>classpath {@code services.yaml}</li>
 *   <li>{@code ./services.yaml} (current working directory)</li>
 * </ol>
 */
public class ServiceRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ServiceRegistry.class);

    private final int gatewayPort;
    private final Map<String, ServiceDefinition> services;

    private ServiceRegistry(int gatewayPort, Map<String, ServiceDefinition> services) {
        this.gatewayPort = gatewayPort;
        this.services = Collections.unmodifiableMap(services);
    }

    // ---- Records ----

    /**
     * Complete definition of a single emulator service.
     *
     * @param id             service key (e.g. "gcs", "pubsub")
     * @param displayName    human-readable name (e.g. "Cloud Storage")
     * @param port           resolved port number (gateway sentinel replaced)
     * @param protocol       "rest" or "grpc"
     * @param envVar         environment variable name clients should set
     * @param envValuePrefix prefix for the env value ("http://" or "")
     * @param type           "external" (supervisord) or "facade" (in-process)
     * @param defaultEnabled whether the service is on by default
     * @param gcloudApiName  gcloud API name for CLOUDSDK_API_ENDPOINT_OVERRIDES (e.g. "storage", "secretmanager")
     * @param gcloudPort     optional port override for gcloud REST endpoint (e.g. Spanner REST on 9020)
     * @param additionalPorts optional map of extra named ports
     * @param healthCheck    optional health check definition (external only)
     * @param terraformEnvVar Terraform Google provider env var name (e.g. "GOOGLE_STORAGE_CUSTOM_ENDPOINT")
     */
    public record ServiceDefinition(
            String id, String displayName, int port, String protocol,
            String envVar, String envValuePrefix, String type,
            boolean defaultEnabled, String gcloudApiName, int gcloudPort,
            Map<String, Integer> additionalPorts,
            HealthCheckDef healthCheck,
            String terraformEnvVar
    ) {
        /**
         * Build the full environment variable value for a given host.
         * Example: "http://" + "localhost" + ":" + 4443 -> "http://localhost:4443"
         */
        public String envValue(String host) {
            return envValuePrefix + host + ":" + port;
        }

        /**
         * Build the CLOUDSDK_API_ENDPOINT_OVERRIDES env var name.
         * Returns null if this service has no gcloud API mapping.
         */
        public String gcloudEnvVar() {
            if (gcloudApiName == null || gcloudApiName.isEmpty()) {
                return null;
            }
            return "CLOUDSDK_API_ENDPOINT_OVERRIDES_" + gcloudApiName.toUpperCase();
        }

        /**
         * Build the gcloud endpoint URL for a given host.
         * Uses gcloudPort if set, otherwise falls back to the service port.
         */
        public String gcloudEndpoint(String host) {
            int effectivePort = gcloudPort > 0 ? gcloudPort : port;
            return "http://" + host + ":" + effectivePort + "/";
        }

        /** True if this is an external (supervisord-managed) process. */
        public boolean isExternal() {
            return "external".equals(type);
        }

        /** True if this is an in-process facade on the gateway port. */
        public boolean isFacade() {
            return "facade".equals(type);
        }
    }

    /**
     * Health check configuration for external services.
     *
     * @param type "tcp" or "http"
     * @param path optional HTTP path (for http type)
     * @param port optional port override (defaults to service port)
     */
    public record HealthCheckDef(String type, String path, Integer port) {}

    // ---- Loading ----

    /**
     * Load the service registry, resolving the "gateway" port sentinel.
     *
     * @param gatewayPort the actual gateway port to substitute for "gateway"
     * @return a populated ServiceRegistry
     */
    public static ServiceRegistry load(int gatewayPort) {
        try {
            InputStream is = findServicesYaml();
            if (is == null) {
                throw new IllegalStateException(
                        "services.yaml not found in /etc/localcloud/, classpath, or working directory");
            }

            ObjectMapper yamlMapper = new YAMLMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> root = yamlMapper.readValue(is, Map.class);

            // Parse gateway port from YAML (used as fallback if not provided)
            @SuppressWarnings("unchecked")
            Map<String, Object> gatewaySection = (Map<String, Object>) root.get("gateway");
            if (gatewaySection != null && gatewayPort == 0) {
                Object yamlPort = gatewaySection.get("port");
                if (yamlPort instanceof Number n) {
                    gatewayPort = n.intValue();
                }
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> servicesSection = (Map<String, Object>) root.get("services");
            if (servicesSection == null) {
                throw new IllegalStateException("services.yaml must contain a 'services' section");
            }

            Map<String, ServiceDefinition> defs = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : servicesSection.entrySet()) {
                String serviceId = entry.getKey();
                @SuppressWarnings("unchecked")
                Map<String, Object> svcMap = (Map<String, Object>) entry.getValue();
                defs.put(serviceId, parseServiceDef(serviceId, svcMap, gatewayPort));
            }

            logger.info("Loaded {} service definitions from services.yaml", defs.size());
            return new ServiceRegistry(gatewayPort, defs);

        } catch (IOException e) {
            throw new RuntimeException("Failed to load services.yaml", e);
        }
    }

    private static InputStream findServicesYaml() throws IOException {
        // 1. /etc/localcloud/services.yaml
        Path etcPath = Path.of("/etc/localcloud/services.yaml");
        if (Files.isReadable(etcPath)) {
            logger.debug("Loading services.yaml from {}", etcPath);
            return Files.newInputStream(etcPath);
        }

        // 2. Classpath
        InputStream classpathStream = ServiceRegistry.class.getClassLoader()
                .getResourceAsStream("services.yaml");
        if (classpathStream != null) {
            logger.debug("Loading services.yaml from classpath");
            return classpathStream;
        }

        // 3. Current working directory
        Path cwdPath = Path.of("services.yaml");
        if (Files.isReadable(cwdPath)) {
            logger.debug("Loading services.yaml from {}", cwdPath.toAbsolutePath());
            return Files.newInputStream(cwdPath);
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private static ServiceDefinition parseServiceDef(String id, Map<String, Object> map, int gatewayPort) {
        String displayName = (String) map.getOrDefault("displayName", id);

        // Port: can be an integer or the string "gateway"
        int port;
        Object portValue = map.get("port");
        if (portValue instanceof Number n) {
            port = n.intValue();
        } else if ("gateway".equals(String.valueOf(portValue))) {
            port = gatewayPort;
        } else {
            port = gatewayPort; // fallback
        }

        String protocol = (String) map.getOrDefault("protocol", "rest");
        String envVar = (String) map.getOrDefault("envVar", "");
        String envValuePrefix = (String) map.getOrDefault("envValuePrefix", "");
        String type = (String) map.getOrDefault("type", "facade");
        boolean defaultEnabled = Boolean.TRUE.equals(map.get("defaultEnabled"));
        String gcloudApiName = (String) map.get("gcloudApiName");
        int gcloudPort = 0;
        Object gcloudPortVal = map.get("gcloudPort");
        if (gcloudPortVal instanceof Number n) {
            gcloudPort = n.intValue();
        }

        // Additional ports (optional)
        Map<String, Integer> additionalPorts = Collections.emptyMap();
        Object addPorts = map.get("additionalPorts");
        if (addPorts instanceof Map) {
            Map<String, Object> raw = (Map<String, Object>) addPorts;
            additionalPorts = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                if (e.getValue() instanceof Number n) {
                    additionalPorts.put(e.getKey(), n.intValue());
                }
            }
        }

        // Health check (optional)
        HealthCheckDef healthCheck = null;
        Object hcObj = map.get("healthCheck");
        if (hcObj instanceof Map) {
            Map<String, Object> hcMap = (Map<String, Object>) hcObj;
            String hcType = (String) hcMap.getOrDefault("type", "tcp");
            String hcPath = (String) hcMap.get("path");
            Integer hcPort = null;
            Object hcPortVal = hcMap.get("port");
            if (hcPortVal instanceof Number n) {
                hcPort = n.intValue();
            }
            healthCheck = new HealthCheckDef(hcType, hcPath, hcPort);
        }

        String terraformEnvVar = (String) map.get("terraformEnvVar");

        return new ServiceDefinition(id, displayName, port, protocol,
                envVar, envValuePrefix, type, defaultEnabled,
                gcloudApiName, gcloudPort, additionalPorts, healthCheck,
                terraformEnvVar);
    }

    // ---- Lookup methods ----

    /**
     * Get a service definition by its ID (e.g. "gcs", "pubsub").
     *
     * @param name the service identifier
     * @return the definition, or null if not found
     */
    public ServiceDefinition getService(String name) {
        return services.get(name);
    }

    /**
     * Return all service definitions keyed by service ID.
     */
    public Map<String, ServiceDefinition> getAllServices() {
        return services;
    }

    /**
     * Return the list of service IDs that are enabled by default.
     */
    public List<String> getDefaultEnabledNames() {
        return services.values().stream()
                .filter(ServiceDefinition::defaultEnabled)
                .map(ServiceDefinition::id)
                .collect(Collectors.toList());
    }

    /**
     * Return the resolved gateway port.
     */
    public int getGatewayPort() {
        return gatewayPort;
    }
}
