package com.localcloud.config;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central configuration for the LocalCloud server.
 * Can be loaded from environment variables, system properties, or application.yaml.
 */
public class LocalCloudConfig {

    private String projectId;
    private Path dataDir;
    private List<String> enabledServices;
    private int gatewayPort;
    private String iamMode;
    private String iamPolicyFile;
    private boolean iamLogWarnings;
    private String logVerbosity;
    private boolean persistenceEnabled;
    private String postgresHost;
    private int postgresPort;
    private String postgresDatabase;
    private String postgresUser;
    private String postgresPassword;
    private ServiceRegistry serviceRegistry;
    private String gcpCredentialSource;
    private String gcpCredentialAdcPath;
    private String gcpCredentialSaKeyPath;
    private ConcurrentHashMap<String, Boolean> enabledServicesMap;
    private ConcurrentHashMap<String, String> configSourceMap;
    private String apiKey;
    private String licenseServerUrl;
    private String dataprocRegistry;
    private List<Path> runtimeWorkspaceRoots;
    private static final Logger logger = LoggerFactory.getLogger(LocalCloudConfig.class);

    private LocalCloudConfig() {
    }

    /**
     * Load configuration from environment variables with sensible defaults.
     */
    public static LocalCloudConfig fromEnvironment() {
        LocalCloudConfig config = new LocalCloudConfig();

        config.projectId = env("LOCALCLOUD_PROJECT", "local-project");
        config.dataDir = Path.of(env("LOCALCLOUD_DATA_DIR", "/var/lib/localcloud"));
        config.gatewayPort = 24080;
        config.iamMode = env("LOCALCLOUD_IAM_MODE", "permissive");
        config.iamPolicyFile = env("LOCALCLOUD_IAM_POLICY_FILE", "");
        config.iamLogWarnings = Boolean.parseBoolean(env("LOCALCLOUD_IAM_LOG_WARNINGS", "true"));
        config.logVerbosity = env("LOCALCLOUD_LOG_VERBOSITY", "info");
        config.persistenceEnabled = Boolean.parseBoolean(env("LOCALCLOUD_PERSISTENCE", "true"));

        // Load service registry from services.yaml
        config.serviceRegistry = ServiceRegistry.load(config.gatewayPort);

        String services = env("LOCALCLOUD_SERVICES",
                String.join(",", config.serviceRegistry.getDefaultEnabledNames()));
        config.enabledServices = Arrays.stream(services.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        config.gcpCredentialSource = env("LOCALCLOUD_GCP_CREDENTIAL_SOURCE", "none");
        config.gcpCredentialAdcPath = env("LOCALCLOUD_GCP_ADC_PATH", "/credentials/adc/application_default_credentials.json");
        config.gcpCredentialSaKeyPath = env("LOCALCLOUD_GCP_SA_KEY_PATH", "/credentials/sa-key.json");

        config.postgresHost = env("LOCALCLOUD_PG_HOST", "localhost");
        config.postgresPort = 24090;
        config.postgresDatabase = env("LOCALCLOUD_PG_DATABASE", "localcloud");
        config.postgresUser = env("LOCALCLOUD_PG_USER", "localcloud");
        config.postgresPassword = env("LOCALCLOUD_PG_PASSWORD", "localcloud");
        config.apiKey = env("LOCALCLOUD_API_KEY", "");
        config.licenseServerUrl = env("LOCALCLOUD_LICENSE_SERVER", "none");
        config.dataprocRegistry = env("LOCALCLOUD_DATAPROC_REGISTRY", "docker.io/jaysen2apache/dataproc");
        config.runtimeWorkspaceRoots = Arrays.stream(env("LOCALCLOUD_RUNTIME_WORKSPACES",
                        config.dataDir.resolve("workspaces").toString()).split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Path::of)
                .toList();

        // Initialize enabled services map and track config source
        config.enabledServicesMap = new ConcurrentHashMap<>();
        config.configSourceMap = new ConcurrentHashMap<>();

        String localcloudServicesEnv = env("LOCALCLOUD_SERVICES", "");
        boolean localcloudServicesSet = !localcloudServicesEnv.isBlank();

        for (String svc : config.enabledServices) {
            config.enabledServicesMap.put(svc, true);
            config.configSourceMap.put(svc, localcloudServicesSet ? "env" : "default");
        }

        // For all services: apply individual LOCALCLOUD_ENABLE_* flags when LOCALCLOUD_SERVICES is not set
        for (String svcId : config.serviceRegistry.getAllServices().keySet()) {
            if (localcloudServicesSet) {
                // LOCALCLOUD_SERVICES controls everything — already handled above
                if (!config.enabledServicesMap.containsKey(svcId)) {
                    config.enabledServicesMap.put(svcId, false);
                    config.configSourceMap.put(svcId, "env");
                }
            } else {
                // Check individual LOCALCLOUD_ENABLE_* flag
                String envKey = "LOCALCLOUD_ENABLE_" + svcId.toUpperCase();
                String envVal = env(envKey, null);
                if (envVal != null && !envVal.isBlank()) {
                    // Individual flag explicitly set — apply it
                    boolean enabled = Boolean.parseBoolean(envVal);
                    config.enabledServicesMap.put(svcId, enabled);
                    config.configSourceMap.put(svcId, "env");
                } else if (!config.enabledServicesMap.containsKey(svcId)) {
                    // No flag set, not in defaults — use services.yaml default
                    config.enabledServicesMap.put(svcId, false);
                    config.configSourceMap.put(svcId, "default");
                }
            }
        }

        return config;
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            value = System.getProperty(name.toLowerCase().replace('_', '.'));
        }
        return value != null && !value.isBlank() ? value : defaultValue;
    }

    private static int intEnv(String name, int defaultValue) {
        String value = env(name, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // --- Getters ---

    public String getProjectId() {
        return projectId;
    }

    public Path getDataDir() {
        return dataDir;
    }

    public List<String> getEnabledServices() {
        return enabledServices;
    }

    public int getGatewayPort() {
        return gatewayPort;
    }

    public String getIamMode() {
        return iamMode;
    }

    public String getIamPolicyFile() {
        return iamPolicyFile;
    }

    public boolean isIamLogWarningsEnabled() {
        return iamLogWarnings;
    }

    public String getLogVerbosity() {
        return logVerbosity;
    }

    public boolean isPersistenceEnabled() {
        return persistenceEnabled;
    }

    public boolean isServiceEnabled(String serviceName) {
        return isServiceDynamicallyEnabled(serviceName);
    }

    public String getPostgresHost() {
        return postgresHost;
    }

    public int getPostgresPort() {
        return postgresPort;
    }

    public String getPostgresDatabase() {
        return postgresDatabase;
    }

    public String getPostgresUser() {
        return postgresUser;
    }

    public String getPostgresPassword() {
        return postgresPassword;
    }

    public ServiceRegistry getServiceRegistry() {
        return serviceRegistry;
    }

    public String getGcpCredentialSource() {
        return gcpCredentialSource;
    }

    public String getGcpCredentialAdcPath() {
        return gcpCredentialAdcPath;
    }

    public String getGcpCredentialSaKeyPath() {
        return gcpCredentialSaKeyPath;
    }

    public String getApiKey() { return apiKey; }
    public String getLicenseServerUrl() { return licenseServerUrl; }
    public String getDataprocRegistry() { return dataprocRegistry; }
    public List<Path> getRuntimeWorkspaceRoots() { return runtimeWorkspaceRoots; }

    /**
     * Check if a service is dynamically enabled (supports runtime toggling).
     * Falls back to the static enabledServices list if not present in the dynamic map.
     */
    public boolean isServiceDynamicallyEnabled(String serviceName) {
        return enabledServicesMap.getOrDefault(serviceName, false);
    }

    /**
     * Dynamically enable or disable a service at runtime.
     */
    public void setServiceEnabled(String serviceName, boolean enabled) {
        enabledServicesMap.put(serviceName, enabled);
    }

    /**
     * Get the source of a service's enabled/disabled config ("env", "persisted", or "default").
     */
    public String getConfigSource(String serviceName) {
        return configSourceMap.getOrDefault(serviceName, "default");
    }

    /**
     * Get all config sources.
     */
    public Map<String, String> getConfigSourceMap() {
        return configSourceMap;
    }

    /**
     * Merge persisted service config from the database.
     * Only applies to services whose source is "default" (not overridden by env vars).
     */
    public void mergePersistedConfig(Map<String, Boolean> persistedConfig) {
        for (Map.Entry<String, Boolean> entry : persistedConfig.entrySet()) {
            String serviceId = entry.getKey();
            boolean enabled = entry.getValue();
            String currentSource = configSourceMap.getOrDefault(serviceId, "default");

            // Only apply persisted config if not locked by env var
            if (!"env".equals(currentSource)) {
                enabledServicesMap.put(serviceId, enabled);
                configSourceMap.put(serviceId, "persisted");
                logger.debug("Service {} config loaded from persistence: enabled={}", serviceId, enabled);
            }
        }
    }
}
