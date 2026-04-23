package com.localcloud;

import java.nio.file.Files;
import java.nio.file.Path;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.file.FileService;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.localcloud.admin.AdminApiService;
import com.localcloud.admin.BrowseService;
import com.localcloud.admin.CredentialBroker;
import com.localcloud.admin.ExportService;
import com.localcloud.admin.MutateService;
import com.localcloud.admin.ProjectService;
import com.localcloud.admin.QueryService;
import com.localcloud.admin.SeedService;
import com.localcloud.admin.ServiceConfigRepository;
import com.localcloud.admin.TelemetryService;
import com.localcloud.admin.ServiceRoutingRepository;
import com.localcloud.admin.UsageMetricsRepository;
import com.localcloud.config.LocalCloudConfig;
import com.localcloud.emulators.secretmanager.SecretManagerEmulator;
import com.localcloud.emulators.cloudtasks.CloudTasksEmulator;
import com.localcloud.emulators.logging.LoggingEmulator;
import com.localcloud.emulators.monitoring.MonitoringEmulator;
import com.localcloud.emulators.compute.ComputeEmulator;
import com.localcloud.emulators.cloudrun.CloudRunEmulator;
import com.localcloud.emulators.gke.GkeEmulator;
import com.localcloud.emulators.gke.K3dManager;
import com.localcloud.emulators.memorystore.MemorystoreEmulator;
import com.localcloud.emulators.workflows.WorkflowsCallbackService;
import com.localcloud.emulators.workflows.WorkflowsEmulator;
import com.localcloud.emulators.workflows.WorkflowEnvVarsRepository;
import com.localcloud.emulators.workflows.WorkflowEnvVarsService;
import com.localcloud.emulators.workflows.WorkflowConnectorService;
import com.localcloud.docker.ContainerManager;
import com.localcloud.docker.DockerClientProvider;
import com.localcloud.gateway.ApiGateway;
import com.localcloud.gateway.HealthCheckService;
import com.localcloud.gateway.IamMiddleware;
import com.localcloud.gateway.ServiceGatingDecorator;
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
    private final ProjectService projectService;
    private final AdminApiService adminApiService;
    private final ServiceConfigRepository serviceConfigRepository;
    private final TelemetryService telemetryService;
    private final BrowseService browseService;
    private final MutateService mutateService;
    private final SeedService seedService;
    private final ExportService exportService;
    private final CredentialBroker credentialBroker;
    private final QueryService queryService;
    private IamMiddleware iamMiddleware;
    private Server server;

    public LocalCloudApplication(LocalCloudConfig config) {
        this.config = config;
        this.dataSource = new PostgresDataSource(config);
        this.schemaManager = new SchemaManager(dataSource);
        this.gateway = new ApiGateway();
        this.requestLogger = new RequestLogger();
        this.processHealthChecker = new ProcessHealthChecker(config, config.getServiceRegistry());
        var usageMetrics = new UsageMetricsRepository(dataSource);
        this.healthCheckService = new HealthCheckService(config, gateway, processHealthChecker, usageMetrics);
        this.projectService = new ProjectService(dataSource);
        var routingRepository = new ServiceRoutingRepository(dataSource);
        this.serviceConfigRepository = new ServiceConfigRepository(dataSource);
        this.credentialBroker = new CredentialBroker(config);
        this.adminApiService = new AdminApiService(config, requestLogger, projectService, routingRepository, credentialBroker, serviceConfigRepository);
        this.telemetryService = new TelemetryService(config, usageMetrics, processHealthChecker, projectService, dataSource);
        this.browseService = new BrowseService(config, dataSource, config.getServiceRegistry(), usageMetrics);
        this.mutateService = new MutateService(config, dataSource, config.getServiceRegistry());
        var workflowsStore = new com.localcloud.emulators.workflows.WorkflowsStore(dataSource);
        this.seedService = new SeedService(config, dataSource, config.getServiceRegistry(), workflowsStore);
        this.exportService = new ExportService(config, dataSource, config.getServiceRegistry());
        this.queryService = new QueryService(config, dataSource, config.getServiceRegistry(), usageMetrics);
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
        try {
            schemaManager.initialize(config.getProjectId());
        } catch (Exception e) {
            if (config.isPersistenceEnabled()) {
                throw e; // Required — fail hard
            }
            logger.warn("Database unavailable (persistence disabled): {}", e.getMessage());
        }

        // Load persisted service config and merge with env-based config
        try {
            var persistedConfig = serviceConfigRepository.findAll();
            if (!persistedConfig.isEmpty()) {
                config.mergePersistedConfig(persistedConfig);
                logger.info("Loaded {} persisted service configs", persistedConfig.size());
            }
        } catch (Exception e) {
            logger.warn("Failed to load persisted service config: {}", e.getMessage());
        }

        // Build Armeria server
        ServerBuilder sb = Server.builder();
        sb.http(config.getGatewayPort());

        // IAM middleware — applied to all services
        iamMiddleware = new IamMiddleware(config);
        sb.decorator(iamMiddleware);

        // Service gating — returns 503 for requests to disabled facade services
        sb.decorator(new ServiceGatingDecorator(config));

        // Register admin/health check annotated services
        sb.annotatedService("/_localcloud", healthCheckService);
        sb.annotatedService("/_localcloud", adminApiService);
        sb.annotatedService("/_localcloud/browse", browseService);
        sb.annotatedService("/_localcloud/mutate", mutateService);
        sb.annotatedService("/_localcloud", seedService);
        sb.annotatedService("/_localcloud", exportService);
        sb.annotatedService("/_localcloud", queryService);

        // Dashboard static files (served from classpath resources)
        sb.serviceUnder("/_localcloud/dashboard/",
                FileService.of(ClassLoader.getSystemClassLoader(), "dashboard"));

        // Console static files (served from filesystem in Docker container)
        // Armeria matches most-specific routes first, so /_localcloud/* API routes take priority
        Path consoleDist = Path.of("/opt/localcloud/console/dist");
        if (Files.isDirectory(consoleDist)) {
            sb.serviceUnder("/", FileService.of(consoleDist));
            logger.info("Console UI served from {}", consoleDist);
        }
        boolean consoleAvailable = Files.isDirectory(consoleDist);

        // Register facade gRPC services (backed by PostgreSQL, running in-process)
        // Enable HTTP/JSON transcoding so gRPC services are also accessible via REST
        // (uses google.api.http annotations from proto files — enables gcloud CLI support).
        //
        // DUAL REGISTRATION: Secret Manager and Cloud Tasks register both gRPC (here)
        // and explicit REST services (annotatedService below). The explicit REST handlers
        // are registered FIRST and take priority in Armeria's route resolution, handling
        // Terraform-compatible CRUD operations. gRPC transcoding handles remaining
        // operations (e.g. secret versions, task operations) that the REST services
        // don't explicitly cover.
        var grpcBuilder = GrpcService.builder()
                .enableHttpJsonTranscoding(true);
        boolean hasGrpcServices = false;

        if (config.isServiceEnabled("secretmanager")) {
            SecretManagerEmulator secretManagerEmulator = new SecretManagerEmulator(dataSource);
            secretManagerEmulator.start();
            grpcBuilder.addService(secretManagerEmulator.getServiceImpl());
            gateway.registerGrpcEmulator(secretManagerEmulator, secretManagerEmulator.getServiceImpl());
            // REST endpoints for Terraform compatibility
            sb.annotatedService("/v1", new com.localcloud.emulators.secretmanager.SecretManagerRestService(
                    secretManagerEmulator.getStore(), secretManagerEmulator));
            hasGrpcServices = true;
            logger.info("Secret Manager facade registered on gateway port {}", config.getGatewayPort());
        }

        if (config.isServiceEnabled("cloudtasks")) {
            CloudTasksEmulator cloudTasksEmulator = new CloudTasksEmulator(dataSource);
            cloudTasksEmulator.start();
            grpcBuilder.addService(cloudTasksEmulator.getServiceImpl());
            gateway.registerGrpcEmulator(cloudTasksEmulator, cloudTasksEmulator.getServiceImpl());
            // REST endpoints for Terraform compatibility
            sb.annotatedService("/v2", new com.localcloud.emulators.cloudtasks.CloudTasksRestService(
                    cloudTasksEmulator.getStore(), cloudTasksEmulator));
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

        // Infrastructure services (require Docker socket)
        ContainerManager containerManager = null;
        boolean needsDocker = config.isServiceEnabled("compute")
                || config.isServiceEnabled("cloudrun")
                || config.isServiceEnabled("gke");
        if (needsDocker) {
            try {
                containerManager = new ContainerManager(DockerClientProvider.getClient());
                logger.info("Docker client initialized for infrastructure services");
            } catch (Exception e) {
                logger.warn("Docker not available — infrastructure services will use simulated mode: {}", e.getMessage());
            }
        }

        if (config.isServiceEnabled("compute")) {
            ContainerManager cm = containerManager != null ? containerManager : new ContainerManager(null);
            ComputeEmulator computeEmulator = new ComputeEmulator(dataSource, cm, credentialBroker);
            computeEmulator.start();
            sb.annotatedService("/compute/v1", computeEmulator.getRestService());
            gateway.registerRestEmulator("/compute/v1", computeEmulator, null);
            logger.info("Compute Engine facade registered on gateway port {}", config.getGatewayPort());
        }

        if (config.isServiceEnabled("cloudrun")) {
            ContainerManager cm = containerManager != null ? containerManager : new ContainerManager(null);
            CloudRunEmulator cloudRunEmulator = new CloudRunEmulator(dataSource, cm, credentialBroker);
            cloudRunEmulator.start();
            grpcBuilder.addService(cloudRunEmulator.getServicesService());
            grpcBuilder.addService(cloudRunEmulator.getRevisionsService());
            gateway.registerGrpcEmulator(cloudRunEmulator,
                    cloudRunEmulator.getServicesService(), cloudRunEmulator.getRevisionsService());
            hasGrpcServices = true;
            logger.info("Cloud Run facade registered on gateway port {}", config.getGatewayPort());
        }

        if (config.isServiceEnabled("gke")) {
            K3dManager k3dManager = new K3dManager(credentialBroker);
            GkeEmulator gkeEmulator = new GkeEmulator(dataSource, k3dManager);
            gkeEmulator.start();
            grpcBuilder.addService(gkeEmulator.getClusterManagerService());
            gateway.registerGrpcEmulator(gkeEmulator, gkeEmulator.getClusterManagerService());
            hasGrpcServices = true;
            logger.info("GKE facade registered on gateway port {}", config.getGatewayPort());
        }

        if (config.isServiceEnabled("memorystore")) {
            int redisPort = config.getServiceRegistry().getService("memorystore").port();
            MemorystoreEmulator memorystoreEmulator = new MemorystoreEmulator(dataSource, redisPort, config.getProjectId());
            memorystoreEmulator.start();
            gateway.registerRestEmulator("/redis", memorystoreEmulator, null);
            logger.info("Memorystore (Redis) emulator started on port {}", redisPort);
        }

        if (config.isServiceEnabled("workflows")) {
            WorkflowsEmulator workflowsEmulator = new WorkflowsEmulator(dataSource);
            workflowsEmulator.start();
            var workflowsGrpc = new com.localcloud.emulators.workflows.WorkflowsGrpcServiceImpl(workflowsEmulator.getWorkflowsService());
            var executionsGrpc = new com.localcloud.emulators.workflows.ExecutionsGrpcServiceImpl(workflowsEmulator.getWorkflowsService());
            grpcBuilder.addService(workflowsGrpc);
            grpcBuilder.addService(executionsGrpc);
            gateway.registerGrpcEmulator(workflowsEmulator, workflowsGrpc, executionsGrpc);

            // Workflow env vars and connector services (register before callbacks to avoid path conflicts)
            var envVarsRepo = new WorkflowEnvVarsRepository(dataSource);
            workflowsEmulator.getWorkflowsService().setEnvVarsRepository(envVarsRepo);
            var envVarsService = new WorkflowEnvVarsService(config, envVarsRepo);
            sb.annotatedService("/_localcloud/workflow-env", envVarsService);
            var connectorService = new WorkflowConnectorService(config, envVarsRepo,
                    workflowsEmulator.getWorkflowsService().getStore());
            sb.annotatedService("/_localcloud/workflow", connectorService);

            // Register callback HTTP endpoint so external systems can wake waiting executions
            WorkflowsCallbackService callbackService = new WorkflowsCallbackService(
                    workflowsEmulator.getWorkflowsService().getCallbackManager());
            sb.annotatedService("/_localcloud/workflows", callbackService);

            // Wire WorkflowsServiceImpl into MutateService so console executions
            // route through the full execution path (connectors, callbacks, env vars)
            mutateService.setWorkflowsService(workflowsEmulator.getWorkflowsService());

            hasGrpcServices = true;
            logger.info("Cloud Workflows facade registered on gateway port {}", config.getGatewayPort());
        }

        if (hasGrpcServices) {
            sb.service(grpcBuilder.build());
        }

        // Global fallback for unsupported GCP API paths (Principle IV: Transparent Limitations)
        // Only registered when console is not serving from / (to avoid duplicate serviceUnder)
        if (!consoleAvailable) {
            sb.serviceUnder("/", (ctx, req) -> {
                String method = req.method().toString().replace("\\", "\\\\").replace("\"", "\\\"");
                String path = ctx.path().replace("\\", "\\\\").replace("\"", "\\\"");
                return HttpResponse.of(HttpStatus.NOT_IMPLEMENTED, MediaType.JSON,
                    """
                    {
                      "error": {
                        "code": 501,
                        "message": "This API endpoint is not emulated by LocalCloud: %s %s",
                        "status": "UNIMPLEMENTED",
                        "details": [
                          {
                            "@type": "type.googleapis.com/google.rpc.ErrorInfo",
                            "reason": "ENDPOINT_NOT_EMULATED",
                            "domain": "localcloud",
                            "metadata": {
                              "suggestion": "Check /_localcloud/services for available emulated services, or see contracts/emulated-services.md for supported operations."
                            }
                          }
                        ]
                      }
                    }
                    """.formatted(method, path));
            });
        }

        server = sb.build();
        server.start().join();

        int port = server.activeLocalPort();
        logger.info("=================================================");
        logger.info("  LocalCloud Server started successfully");
        logger.info("  Project ID:  {}", config.getProjectId());
        logger.info("  Gateway:     http://localhost:{}", port);
        logger.info("  Health:      http://localhost:{}/_localcloud/health", port);
        logger.info("  Console:     http://localhost:{}", port);
        logger.info("  Dashboard:   http://localhost:{}/_localcloud/dashboard/", port);
        logger.info("  Persistence: {}", config.isPersistenceEnabled() ? "enabled (" + config.getDataDir() + ")" : "disabled");
        logger.info("  Services:    {}", config.getEnabledServices());
        logger.info("=================================================");

        // Start anonymous telemetry (opt-out: LOCALCLOUD_TELEMETRY=false)
        telemetryService.start();

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
        // Stop Armeria server first (stop accepting new requests)
        if (server != null) {
            server.stop().join();
            logger.info("LocalCloud server stopped");
        }

        // Stop telemetry scheduler
        telemetryService.stop();

        // Flush usage metrics before stopping emulators
        healthCheckService.shutdown();

        // Stop all registered emulators (facade services)
        for (var emulator : gateway.getEmulators()) {
            try {
                emulator.stop();
            } catch (Exception e) {
                logger.warn("Error stopping emulator {}: {}", emulator.getName(), e.getMessage());
            }
        }

        // Close HTTP clients
        processHealthChecker.close();
        if (iamMiddleware != null) {
            iamMiddleware.close();
        }
        seedService.close();

        // Close database last
        dataSource.close();
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
