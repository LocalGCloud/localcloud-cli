package com.localcloud.config;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

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

    private LocalCloudConfig() {
    }

    /**
     * Load configuration from environment variables with sensible defaults.
     */
    public static LocalCloudConfig fromEnvironment() {
        LocalCloudConfig config = new LocalCloudConfig();

        config.projectId = env("LOCALCLOUD_PROJECT", "local-project");
        config.dataDir = Path.of(env("LOCALCLOUD_DATA_DIR", "/var/lib/localcloud"));
        config.gatewayPort = intEnv("LOCALCLOUD_PORT", 8080);
        config.iamMode = env("LOCALCLOUD_IAM_MODE", "permissive");
        config.iamPolicyFile = env("LOCALCLOUD_IAM_POLICY_FILE", "");
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
        config.postgresPort = intEnv("LOCALCLOUD_PG_PORT", 5432);
        config.postgresDatabase = env("LOCALCLOUD_PG_DATABASE", "localcloud");
        config.postgresUser = env("LOCALCLOUD_PG_USER", "localcloud");
        config.postgresPassword = env("LOCALCLOUD_PG_PASSWORD", "localcloud");

        // Initialize enabled services map from the enabled services list
        config.enabledServicesMap = new ConcurrentHashMap<>();
        for (String svc : config.enabledServices) {
            config.enabledServicesMap.put(svc, true);
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

    public String getLogVerbosity() {
        return logVerbosity;
    }

    public boolean isPersistenceEnabled() {
        return persistenceEnabled;
    }

    public boolean isServiceEnabled(String serviceName) {
        return enabledServices.contains(serviceName);
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
}
