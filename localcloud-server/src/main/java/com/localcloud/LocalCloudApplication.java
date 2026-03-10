package com.localcloud;

import java.nio.file.Files;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.file.FileService;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.localcloud.admin.AdminApiService;
import com.localcloud.admin.BrowseService;
import com.localcloud.admin.SeedService;
import com.localcloud.config.LocalCloudConfig;
import com.localcloud.emulators.secretmanager.SecretManagerEmulator;
import com.localcloud.emulators.cloudtasks.CloudTasksEmulator;
import com.localcloud.emulators.logging.LoggingEmulator;
import com.localcloud.emulators.monitoring.MonitoringEmulator;
import com.localcloud.gateway.ApiGateway;
import com.localcloud.gateway.HealthCheckService;
import com.localcloud.gateway.ProcessHealthChecker;
import com.localcloud.gateway.RequestLogger;
import com.localcloud.persistence.PostgresDataSource;
import com.localcloud.persistence.SchemaManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for the LocalCloud server.
 * Bootstraps the Armeria server with REST gateway, admin endpoints,
 * and facade gRPC services. External emulators (GCS, Pub/Sub, Firestore,
 * Bigtable, Spanner, BigQuery) run as separate processes managed by
 * supervisord; this server only handles admin APIs and facade services.
 */
public class LocalCloudApplication {

    private static final Logger logger = LoggerFactory.getLogger(LocalCloudApplication.class);

    private final LocalCloudConfig config;
    private final PostgresDataSource dataSource;
    private final SchemaManager schemaManager;
    private final ApiGateway gateway;
    private final RequestLogger requestLogger;
    private final ProcessHealthChecker processHealthChecker;
    private final HealthCheckService healthCheckService;
    private final AdminApiService adminApiService;
    private final BrowseService browseService;
    private final SeedService seedService;
    private Server server;

    public LocalCloudApplication(LocalCloudConfig config) {
        this.config = config;
        this.dataSource = new PostgresDataSource(config);
        this.schemaManager = new SchemaManager(dataSource);
        this.gateway = new ApiGateway();
        this.requestLogger = new RequestLogger();
        this.processHealthChecker = new ProcessHealthChecker(config);
        this.healthCheckService = new HealthCheckService(config, gateway, processHealthChecker);
        this.adminApiService = new AdminApiService(config, requestLogger);
        this.browseService = new BrowseService(dataSource.getDataSource());
        this.seedService = new SeedService(config);
    }

    /**
     * Build and start the Armeria server.
     */
    public void start() throws Exception {
        // Ensure data directory exists
        if (config.isPersistenceEnabled()) {
            Files.createDirectories(config.getDataDir());
        }

        // Initialize database schema
        schemaManager.initialize();

        // Build Armeria server
        ServerBuilder sb = Server.builder();
        sb.http(config.getGatewayPort());

        // Register admin/health check annotated services
        sb.annotatedService("/_localcloud", healthCheckService);
        sb.annotatedService("/_localcloud", adminApiService);
        sb.annotatedService("/_localcloud/browse", browseService);
        sb.annotatedService("/_localcloud", seedService);

        // Dashboard static files (served from classpath resources)
        sb.serviceUnder("/_localcloud/dashboard/",
                FileService.of(ClassLoader.getSystemClassLoader(), "dashboard"));

        // Root endpoint - simple info response
        sb.service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                """
                {
                  "name": "LocalCloud",
                  "version": "0.1.0",
                  "project_id": "%s",
                  "health": "/_localcloud/health",
                  "dashboard": "/_localcloud/dashboard/"
                }
                """.formatted(config.getProjectId())));

        // Register facade gRPC services (backed by PostgreSQL, running in-process)
        var grpcBuilder = GrpcService.builder();
        boolean hasGrpcServices = false;

        if (config.isServiceEnabled("secretmanager")) {
            SecretManagerEmulator secretManagerEmulator = new SecretManagerEmulator(dataSource);
            secretManagerEmulator.start();
            grpcBuilder.addService(secretManagerEmulator.getServiceImpl());
            gateway.registerGrpcEmulator(secretManagerEmulator, secretManagerEmulator.getServiceImpl());
            hasGrpcServices = true;
            logger.info("Secret Manager facade registered on gateway port {}", config.getGatewayPort());
        }

        if (config.isServiceEnabled("cloudtasks")) {
            CloudTasksEmulator cloudTasksEmulator = new CloudTasksEmulator(dataSource);
            cloudTasksEmulator.start();
            grpcBuilder.addService(cloudTasksEmulator.getServiceImpl());
            gateway.registerGrpcEmulator(cloudTasksEmulator, cloudTasksEmulator.getServiceImpl());
            hasGrpcServices = true;
            logger.info("Cloud Tasks facade registered on gateway port {}", config.getGatewayPort());
        }

        if (config.isServiceEnabled("logging")) {
            LoggingEmulator loggingEmulator = new LoggingEmulator(dataSource);
            loggingEmulator.start();
            grpcBuilder.addService(loggingEmulator.getLoggingService());
            gateway.registerGrpcEmulator(loggingEmulator, loggingEmulator.getLoggingService());
            hasGrpcServices = true;
            logger.info("Cloud Logging facade registered on gateway port {}", config.getGatewayPort());
        }

        if (config.isServiceEnabled("monitoring")) {
            MonitoringEmulator monitoringEmulator = new MonitoringEmulator(dataSource);
            monitoringEmulator.start();
            grpcBuilder.addService(monitoringEmulator.getMonitoringService());
            gateway.registerGrpcEmulator(monitoringEmulator, monitoringEmulator.getMonitoringService());
            hasGrpcServices = true;
            logger.info("Cloud Monitoring facade registered on gateway port {}", config.getGatewayPort());
        }

        if (hasGrpcServices) {
            sb.service(grpcBuilder.build());
        }

        server = sb.build();
        server.start().join();

        int port = server.activeLocalPort();
        logger.info("=================================================");
        logger.info("  LocalCloud Server started successfully");
        logger.info("  Project ID:  {}", config.getProjectId());
        logger.info("  Gateway:     http://localhost:{}", port);
        logger.info("  Health:      http://localhost:{}/_localcloud/health", port);
        logger.info("  Dashboard:   http://localhost:{}/_localcloud/dashboard/", port);
        logger.info("  Persistence: {}", config.isPersistenceEnabled() ? "enabled (" + config.getDataDir() + ")" : "disabled");
        logger.info("  Services:    {}", config.getEnabledServices());
        logger.info("=================================================");

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown signal received, stopping LocalCloud...");
            stop();
        }, "localcloud-shutdown"));
    }

    /**
     * Gracefully stop the server and release resources.
     */
    public void stop() {
        // Stop all registered emulators (facade services)
        for (var emulator : gateway.getEmulators()) {
            try {
                emulator.stop();
            } catch (Exception e) {
                logger.warn("Error stopping emulator {}: {}", emulator.getName(), e.getMessage());
            }
        }

        // Close database
        dataSource.close();

        // Stop Armeria server
        if (server != null) {
            server.stop().join();
            logger.info("LocalCloud server stopped");
        }
    }

    // --- Accessors for components ---

    public LocalCloudConfig getConfig() {
        return config;
    }

    public ApiGateway getGateway() {
        return gateway;
    }

    public RequestLogger getRequestLogger() {
        return requestLogger;
    }

    public PostgresDataSource getDataSource() {
        return dataSource;
    }

    public ProcessHealthChecker getProcessHealthChecker() {
        return processHealthChecker;
    }

    public Server getServer() {
        return server;
    }

    /**
     * Main entry point.
     */
    public static void main(String[] args) {
        try {
            LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
            LocalCloudApplication app = new LocalCloudApplication(config);
            app.start();
        } catch (Exception e) {
            logger.error("Failed to start LocalCloud: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
}
