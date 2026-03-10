package com.localcloud.config;

import java.nio.file.Path;
import java.util.List;

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
    private String logVerbosity;
    private boolean persistenceEnabled;
    private String postgresHost;
    private int postgresPort;
    private String postgresDatabase;
    private String postgresUser;
    private String postgresPassword;

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
        config.logVerbosity = env("LOCALCLOUD_LOG_VERBOSITY", "info");
        config.persistenceEnabled = Boolean.parseBoolean(env("LOCALCLOUD_PERSISTENCE", "true"));

        String services = env("LOCALCLOUD_SERVICES",
                "gcs,pubsub,firestore,bigquery,secretmanager,cloudtasks,spanner,bigtable,logging,monitoring");
        config.enabledServices = List.of(services.split(","));

        config.postgresHost = env("LOCALCLOUD_PG_HOST", "localhost");
        config.postgresPort = intEnv("LOCALCLOUD_PG_PORT", 5432);
        config.postgresDatabase = env("LOCALCLOUD_PG_DATABASE", "localcloud");
        config.postgresUser = env("LOCALCLOUD_PG_USER", "localcloud");
        config.postgresPassword = env("LOCALCLOUD_PG_PASSWORD", "localcloud");

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
}
