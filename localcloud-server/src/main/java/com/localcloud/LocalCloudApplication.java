package com.localcloud;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.file.FileService;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.localcloud.admin.AdminApiService;
import com.localcloud.admin.BrowseService;
import com.localcloud.admin.CredentialBroker;
import com.localcloud.admin.ExportService;
import com.localcloud.admin.FaultInjectionService;
import com.localcloud.admin.MutateService;
import com.localcloud.admin.ProjectService;
import com.localcloud.admin.GraphQLGateway;
import com.localcloud.admin.QueryHistoryRepository;
import com.localcloud.admin.QueryService;
import com.localcloud.admin.SeedService;
import com.localcloud.admin.ServiceConfigRepository;
import com.localcloud.admin.SnapshotService;
import com.localcloud.admin.TelemetryService;
import com.localcloud.admin.ServiceRoutingRepository;
import com.localcloud.admin.UsageMetricsRepository;
import com.localcloud.config.LocalCloudConfig;
import com.localcloud.emulators.secretmanager.SecretManagerEmulator;
import com.localcloud.emulators.cloudtasks.CloudTasksEmulator;
import com.localcloud.emulators.scheduler.CloudSchedulerEmulator;
import com.localcloud.emulators.functions.CloudFunctionsEmulator;
import com.localcloud.emulators.alloydb.AlloyDBEmulator;
import com.localcloud.emulators.dataproc.DataprocEmulator;
import com.localcloud.emulators.iam.IAMEmulator;
import com.localcloud.emulators.iam.IAMPolicyRestHandler;
import com.localcloud.emulators.iam.IAMRepository;
import com.localcloud.emulators.logging.LoggingEmulator;
import com.localcloud.emulators.monitoring.MonitoringEmulator;
import com.localcloud.emulators.compute.ComputeEmulator;
import com.localcloud.emulators.cloudrun.CloudRunEmulator;
import com.localcloud.emulators.gke.GkeEmulator;
import com.localcloud.emulators.gke.K3dManager;
import com.localcloud.emulators.cloudsql.CloudSqlEmulator;
import com.localcloud.emulators.bigtable.BigtableEmulator;
import com.localcloud.emulators.memorystore.MemorystoreEmulator;
import com.localcloud.emulators.kms.KmsEmulator;
import com.localcloud.emulators.vertexai.VertexAiEmulator;
import com.localcloud.emulators.workflows.WorkflowsCallbackService;
import com.localcloud.emulators.workflows.WorkflowsEmulator;
import com.localcloud.emulators.workflows.WorkflowEnvVarsRepository;
import com.localcloud.emulators.workflows.WorkflowEnvVarsService;
import com.localcloud.emulators.workflows.WorkflowConnectorService;
import com.localcloud.emulators.workflows.WorkflowsGrpcServiceImpl;
import com.localcloud.emulators.workflows.ExecutionsGrpcServiceImpl;
import com.localcloud.emulators.workflows.WorkflowsRestService;
import com.localcloud.emulators.pubsub.PubSubRestService;
import com.localcloud.emulators.pubsub.PubSubStore;
import com.localcloud.emulators.secretmanager.SecretManagerRestService;
import com.localcloud.emulators.cloudtasks.CloudTasksRestService;
import com.localcloud.emulators.cloudresourcemanager.CloudResourceManagerRestService;
import com.localcloud.emulators.oauth2.OAuth2RestService;
import com.localcloud.docker.ContainerManager;
import com.localcloud.docker.DockerClientProvider;
import com.localcloud.gateway.ApiGateway;
import com.localcloud.gateway.FaultInjectionDecorator;
import com.localcloud.gateway.FaultInjectionRegistry;
import com.localcloud.gateway.HealthCheckService;
import com.localcloud.gateway.IamMiddleware;
import com.localcloud.gateway.MetadataServerService;
import com.localcloud.gateway.ServiceGatingDecorator;
import com.localcloud.gateway.ProcessHealthChecker;
import com.localcloud.gateway.RequestLogger;
import com.localcloud.gateway.SpannerIamService;
import com.localcloud.persistence.PostgresDataSource;
import com.localcloud.persistence.SchemaManager;
import com.localcloud.config.ServiceRegistry.ServiceDefinition;
import com.localcloud.licensing.LicenseTier;
import com.localcloud.licensing.LicenseTierProvider;
import com.localcloud.licensing.StaticLicenseTierProvider;
import com.localcloud.sync.SyncApiService;
import com.localcloud.sync.CredentialEncryption;
import com.localcloud.sync.SyncCredentialRepository;
import com.localcloud.sync.SyncManifestRepository;
import com.localcloud.sync.SyncService;

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
    private final FaultInjectionRegistry faultInjectionRegistry;
    private final ProcessHealthChecker processHealthChecker;
    private final HealthCheckService healthCheckService;
    private final ProjectService projectService;
    private AdminApiService adminApiService;
    private final ServiceConfigRepository serviceConfigRepository;
    private final TelemetryService telemetryService;
    private final BrowseService browseService;
    private final MutateService mutateService;
    private final SeedService seedService;
    private final ExportService exportService;
    private final CredentialBroker credentialBroker;
    private final QueryService queryService;
    private final QueryHistoryRepository queryHistoryRepo;
    private CloudSqlEmulator cloudSqlEmulator;
    private BigtableEmulator bigtableEmulator;
    private MemorystoreEmulator memorystoreEmulator;
    private IamMiddleware iamMiddleware;
    private Server server;
    private final IAMPolicyRestHandler iamPolicyRestHandler;

    // Stored for deferred AdminApiService construction after license validation
    private final ServiceRoutingRepository routingRepository;

    public LocalCloudApplication(LocalCloudConfig config) {
        this.config = config;
        this.dataSource = new PostgresDataSource(config);
        this.schemaManager = new SchemaManager(dataSource);
        this.gateway = new ApiGateway();
        this.requestLogger = new RequestLogger();
        this.faultInjectionRegistry = new FaultInjectionRegistry();
        this.processHealthChecker = new ProcessHealthChecker(config, config.getServiceRegistry());
        var usageMetrics = new UsageMetricsRepository(dataSource);
        this.queryHistoryRepo = new QueryHistoryRepository(dataSource);
        this.healthCheckService = new HealthCheckService(config, gateway, processHealthChecker, usageMetrics);
        this.projectService = new ProjectService(dataSource);
        this.routingRepository = new ServiceRoutingRepository(dataSource);
        this.serviceConfigRepository = new ServiceConfigRepository(dataSource);
        this.credentialBroker = new CredentialBroker(config);
        // adminApiService is constructed in start() after license validation (needs LicenseTierProvider)
        this.telemetryService = new TelemetryService(config, usageMetrics, processHealthChecker, projectService, dataSource);
        this.browseService = new BrowseService(config, dataSource, config.getServiceRegistry(), usageMetrics);
        this.mutateService = new MutateService(config, dataSource, config.getServiceRegistry());
        var workflowsStore = new com.localcloud.emulators.workflows.WorkflowsStore(dataSource);
        this.seedService = new SeedService(config, dataSource, config.getServiceRegistry(), workflowsStore);
        this.exportService = new ExportService(config, dataSource, config.getServiceRegistry());
        this.queryService = new QueryService(config, dataSource, config.getServiceRegistry(), usageMetrics, queryHistoryRepo);
        this.iamPolicyRestHandler = new IAMPolicyRestHandler(new IAMRepository(dataSource));
        this.cloudSqlEmulator = new CloudSqlEmulator(dataSource, config.getGatewayPort(), iamPolicyRestHandler);
        this.bigtableEmulator = new BigtableEmulator(dataSource, config.getGatewayPort(), iamPolicyRestHandler);
        this.memorystoreEmulator = new MemorystoreEmulator(dataSource, config.getGatewayPort(), iamPolicyRestHandler);
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

        // --- License validation ---
        // For Phase 1: public key is null when LOCALCLOUD_LICENSE_PUBLIC_KEY is not set
        // (bypass mode uses online validator which accepts "none" server)
        // In Phase 2, embed the production Ed25519 public key here
        java.security.PublicKey licensePublicKey = null;
        try {
            String pubKeyEnv = System.getenv("LOCALCLOUD_LICENSE_PUBLIC_KEY");
            if (pubKeyEnv != null && !pubKeyEnv.isBlank()) {
                licensePublicKey = com.localcloud.licensing.KeyGenerator.decodePublicKey(pubKeyEnv);
            }
        } catch (Exception e) {
            logger.warn("Failed to load license public key: {}", e.getMessage());
        }

        var licenseManager = new com.localcloud.licensing.LicenseManager(
                config.getApiKey().isBlank() ? null : config.getApiKey(),
                config.getLicenseServerUrl(),
                config.getDataDir(),
                licensePublicKey);

        com.localcloud.licensing.LicenseResult licenseResult = licenseManager.validate();

        if (!licenseResult.isValid()) {
            logger.error("=== LICENSE VALIDATION FAILED ===");
            logger.error(licenseResult.errorMessage());
            logger.error("Set LOCALCLOUD_API_KEY or visit https://localcloud.dev to get a license key.");
            logger.error("================================");
            System.exit(1);
        }

        logger.info("License: tier={}, email={}, device={}", licenseResult.tier(), licenseResult.email(),
                licenseManager.getDeviceId().substring(0, 8) + "...");

        // Create tier provider and AdminApiService now that the license tier is known
        LicenseTier currentTier = licenseResult.tier() != null ? licenseResult.tier() : LicenseTier.COMMUNITY;
        LicenseTierProvider tierProvider = new StaticLicenseTierProvider(currentTier);
        this.adminApiService = new AdminApiService(config, requestLogger, projectService,
                routingRepository, credentialBroker, serviceConfigRepository, tierProvider, faultInjectionRegistry);

        // Apply tier-based service gating using minTier from services.yaml
        for (Map.Entry<String, ServiceDefinition> entry : config.getServiceRegistry().getAllServices().entrySet()) {
            String serviceName = entry.getKey();
            ServiceDefinition def = entry.getValue();
            LicenseTier required = def.minTier() != null ? def.minTier() : LicenseTier.COMMUNITY;
            if (!currentTier.includes(required)) {
                config.setServiceEnabled(serviceName, false);
                logger.info("Service '{}' disabled — requires {} tier (current: {})",
                        serviceName, required, currentTier);
            }
        }

        // Build Armeria server
        ServerBuilder sb = Server.builder();
        sb.http(config.getGatewayPort());

        // Load console SPA HTML early so we can use it in the routing decorator
        Path consoleDist = Path.of("/opt/localcloud/console/dist");
        boolean consoleAvailable = Files.isDirectory(consoleDist);
        String spaIndex = null;
        if (consoleAvailable) {
            spaIndex = Files.readString(consoleDist.resolve("index.html"));
            healthCheckService.setSpaHtml(spaIndex);
        }

        // IAM middleware — applied to all services
        iamMiddleware = new IamMiddleware(config);
        sb.decorator(iamMiddleware);

        // Service gating — returns 503 for requests to disabled facade services
        sb.decorator(new ServiceGatingDecorator(config));

        // Fault injection — optional local failures for resilience testing
        sb.decorator(new FaultInjectionDecorator(faultInjectionRegistry));

        // SPA routing decorator: serve index.html for browser GET requests before
        // they reach annotated services. This makes hard refresh work for /usage,
        // /logs, /settings, and all client-side routes.
        if (spaIndex != null) {
            final String spaHtml = spaIndex;
            sb.decorator((delegate, ctx, req) -> {
                if (req.method() != HttpMethod.GET) {
                    return delegate.serve(ctx, req);
                }
                String path = ctx.path();
                String lastSegment = path.substring(path.lastIndexOf('/') + 1);
                // Let static assets through (they have file extensions)
                if (lastSegment.contains(".")) {
                    return delegate.serve(ctx, req);
                }
                // Check if browser is requesting HTML (not an API call)
                String acceptHeader = req.headers().get("accept");
                if (acceptHeader == null || !acceptHeader.contains("text/html")) {
                    return delegate.serve(ctx, req);
                }
                ResponseHeaders spaHeaders = ResponseHeaders.builder(HttpStatus.OK)
                        .add("Content-Type", "text/html; charset=utf-8")
                        .add("Cache-Control", "no-cache")
                        .build();
                return HttpResponse.of(spaHeaders, HttpData.ofUtf8(spaHtml));
            });
        }

        // Register developer-facing admin services at root-level paths.
        sb.annotatedService("/", healthCheckService);
        sb.annotatedService("/", adminApiService);
        sb.annotatedService("/browse", browseService);
        sb.annotatedService("/mutate", mutateService);
        sb.annotatedService("/", seedService);
        sb.annotatedService("/", exportService);
        sb.annotatedService("/", queryService);
        sb.annotatedService("/", new SnapshotService(config, exportService, seedService));
        sb.annotatedService("/", new FaultInjectionService(faultInjectionRegistry));
        sb.annotatedService("/computeMetadata/v1", new MetadataServerService(config));

        // Cloud SQL Admin API facade (REST)
        if (config.isServiceEnabled("cloudsql")) {
            cloudSqlEmulator.start();
            sb.annotatedService("/sql/v1", cloudSqlEmulator.getRestService());
            sb.annotatedService("/sql/v1beta4", cloudSqlEmulator.getRestService());
            sb.annotatedService("/sqladmin/v1", cloudSqlEmulator.getRestService());
            sb.annotatedService("/sqladmin/v1beta4", cloudSqlEmulator.getRestService());
            // The Terraform provider constructs database paths without the version prefix
            // (e.g., POST /projects/.../instances/.../databases instead of /sql/v1beta4/projects/...)
            sb.annotatedService("/", cloudSqlEmulator.getRestService());
            seedService.setCloudSqlEmulator(cloudSqlEmulator);
            mutateService.setCloudSqlEmulator(cloudSqlEmulator);
            logger.info("Cloud SQL Admin API facade registered at /sql/v1, /sql/v1beta4, /sqladmin/v1, /sqladmin/v1beta4, /");
        }

        // Bigtable Admin API facade (REST)
        if (config.isServiceEnabled("bigtable")) {
            bigtableEmulator.start();
            // Console/browse paths
            sb.annotatedService("/bigtable/admin/v2", bigtableEmulator.getAdminService());
            // Terraform provider path: GOOGLE_BIGTABLE_CUSTOM_ENDPOINT replaces
            // bigtableadmin.googleapis.com base → hits /v2/projects/.../instances
            sb.annotatedService("/v2", bigtableEmulator.getAdminService());
            seedService.setBigtableEmulator(bigtableEmulator);
            mutateService.setBigtableEmulator(bigtableEmulator);
            logger.info("Bigtable Admin API facade registered at /bigtable/admin/v2 and /v2");

            // Manual route for modifyColumnFamilies — Armeria's annotation parser treats ':'
            // as a path-parameter regex delimiter, so {table}:modifyColumnFamilies is invalid.
            sb.service(
                Route.builder()
                    .methods(HttpMethod.POST)
                    .path("regex:^/bigtable/admin/v2/projects/(?<project>[^/]+)/instances/(?<instance>[^/]+)/tables/(?<table>[^/]+):modifyColumnFamilies$")
                    .build(),
                (ctx, req) -> {
                    var aggregated = req.aggregate().join();
                    return bigtableEmulator.getAdminService().modifyColumnFamilies(
                        ctx.pathParam("project"), ctx.pathParam("instance"), ctx.pathParam("table"),
                        aggregated.contentUtf8());
                });
            // Same route for the /v2 prefix used by Terraform provider
            sb.service(
                Route.builder()
                    .methods(HttpMethod.POST)
                    .path("regex:^/v2/projects/(?<project>[^/]+)/instances/(?<instance>[^/]+)/tables/(?<table>[^/]+):modifyColumnFamilies$")
                    .build(),
                (ctx, req) -> {
                    var aggregated = req.aggregate().join();
                    return bigtableEmulator.getAdminService().modifyColumnFamilies(
                        ctx.pathParam("project"), ctx.pathParam("instance"), ctx.pathParam("table"),
                        aggregated.contentUtf8());
                });
            logger.info("Bigtable modifyColumnFamilies routes registered");
        }

        // Memorystore Admin API facade (REST)
        if (config.isServiceEnabled("memorystore")) {
            memorystoreEmulator.start();
            sb.annotatedService("/redis/v1", memorystoreEmulator.getAdminService());
            seedService.setMemorystoreEmulator(memorystoreEmulator);
            mutateService.setMemorystoreEmulator(memorystoreEmulator);
            logger.info("Memorystore Admin API facade registered at /redis/v1");
        }

        // Pub/Sub Admin API facade (REST) — Terraform uses REST admin API, but the
        // external Pub/Sub emulator (port 8085) only serves gRPC data-plane.
        // This REST service handles topic and subscription CRUD via PostgreSQL.
        if (config.isServiceEnabled("pubsub")) {
            var pubsubStore = new PubSubStore(dataSource.getDataSource());
            var pubsubRestService = new PubSubRestService(pubsubStore);
            sb.annotatedService("/v1", pubsubRestService);
            // Also register at root — the Google Pub/Sub REST client sends paths
            // without the /v1/ version prefix (e.g., /projects/.../topics/...).
            sb.annotatedService("/", pubsubRestService);
            logger.info("Pub/Sub Admin API REST facade registered at /v1 and /");
        }

        // =============================================================================
        // Register facade gRPC services (backed by PostgreSQL, running in-process).
        // Enable HTTP/JSON transcoding so gRPC services are also accessible via REST
        // (uses google.api.http annotations from proto files — enables gcloud CLI support).
        //
        // DUAL REGISTRATION: Secret Manager and Cloud Tasks register both gRPC (here)
        // and explicit REST annotated services. The explicit REST handlers handle
        // Terraform-compatible CRUD operations. gRPC transcoding handles remaining
        // operations (e.g. secret versions, task operations) that the REST services
        // don't explicitly cover.
        // =============================================================================

        // Infrastructure services (Compute, Cloud Run, GKE) require Docker socket
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

        var grpcBuilder = GrpcService.builder()
                .enableHttpJsonTranscoding(true);
        boolean hasGrpcServices = true;
        grpcBuilder.addService(new com.localcloud.gateway.OperationsGrpcService());

        // Service Usage API endpoints — registered as manual regex routes to avoid
        // prefix collisions with SecretManager and CRM on the shared /v1 path.
        // (Armeria can route annotated services on the same prefix incorrectly.)
        if (config.isServiceEnabled("serviceusage")) {
            var suService = new com.localcloud.emulators.serviceusage.ServiceUsageRestService();
            sb.service(Route.builder().methods(HttpMethod.GET)
                    .path("regex:^/v1/projects/(?<project>[^/]+)/services/(?<service>[^/]+)$")
                    .build(), (ctx, req) ->
                            suService.getService(ctx.pathParam("project"), ctx.pathParam("service")));
            sb.service(Route.builder().methods(HttpMethod.GET)
                    .path("regex:^/v1/projects/(?<project>[^/]+)/services$")
                    .build(), (ctx, req) -> suService.listServices(ctx.pathParam("project")));
            sb.service(Route.builder().methods(HttpMethod.POST)
                    .path("regex:^/v1/projects/(?<project>[^/]+)/services/(?<service>[^:]+):enable$")
                    .build(), (ctx, req) -> suService.enableService(ctx.pathParam("project"), ctx.pathParam("service")));
            sb.service(Route.builder().methods(HttpMethod.POST)
                    .path("regex:^/v1/projects/(?<project>[^/]+)/services:batchEnable$")
                    .build(), (ctx, req) -> {
                        var agg = req.aggregate().join();
                        return suService.batchEnableServices(ctx.pathParam("project"), agg.contentUtf8());
                    });
            logger.info("Service Usage API endpoints registered via regex routes on /v1");
        }

        // Cloud Billing API endpoints — manual regex routes (same /v1 prefix safety).
        if (config.isServiceEnabled("cloudbilling")) {
            var cbService = new com.localcloud.emulators.cloudbilling.CloudBillingRestService();
            sb.service(Route.builder().methods(HttpMethod.GET)
                    .path("regex:^/v1/projects/(?<projectId>[^/]+)/billingInfo$")
                    .build(), (ctx, req) ->
                            cbService.getBillingInfo(ctx.pathParam("projectId")));
            sb.service(Route.builder().methods(HttpMethod.PUT)
                    .path("regex:^/v1/projects/(?<projectId>[^/]+)/billingInfo$")
                    .build(), (ctx, req) -> {
                        var agg = req.aggregate().join();
                        return cbService.updateBillingInfo(ctx.pathParam("projectId"), agg.contentUtf8());
                    });
            sb.service(Route.builder().methods(HttpMethod.GET)
                    .path("regex:^/v1/billingAccounts$")
                    .build(), (ctx, req) -> cbService.listBillingAccounts());
            sb.service(Route.builder().methods(HttpMethod.GET)
                    .path("regex:^/v1/billingAccounts/(?<accountName>[^/]+)/projects$")
                    .build(), (ctx, req) ->
                            cbService.listProjectBillingInfo(ctx.pathParam("accountName")));
            logger.info("Cloud Billing API endpoints registered via regex routes on /v1");
        }

        // Secret Manager: gRPC + explicit REST (Terraform compatibility)
        if (config.isServiceEnabled("secretmanager")) {
            SecretManagerEmulator secretManagerEmulator = new SecretManagerEmulator(dataSource);
            secretManagerEmulator.start();
            grpcBuilder.addService(secretManagerEmulator.getServiceImpl());
            gateway.registerGrpcEmulator(secretManagerEmulator, secretManagerEmulator.getServiceImpl());
            sb.annotatedService("/v1", new SecretManagerRestService(
                    secretManagerEmulator.getStore(), secretManagerEmulator, iamPolicyRestHandler));
            hasGrpcServices = true;
            logger.info("Secret Manager facade registered on gateway port {}", config.getGatewayPort());
        }

        // Cloud Tasks: gRPC + explicit REST (Terraform compatibility)
        if (config.isServiceEnabled("cloudtasks")) {
            CloudTasksEmulator cloudTasksEmulator = new CloudTasksEmulator(dataSource);
            cloudTasksEmulator.start();
            grpcBuilder.addService(cloudTasksEmulator.getServiceImpl());
            gateway.registerGrpcEmulator(cloudTasksEmulator, cloudTasksEmulator.getServiceImpl());
            sb.annotatedService("/v2", new CloudTasksRestService(
                    cloudTasksEmulator.getStore(), cloudTasksEmulator, iamPolicyRestHandler));
            hasGrpcServices = true;
            logger.info("Cloud Tasks facade registered on gateway port {}", config.getGatewayPort());
        }

        // Cloud Scheduler: gRPC + explicit REST (Terraform compatibility)
        if (config.isServiceEnabled("cloudscheduler")) {
            CloudSchedulerEmulator schedulerEmulator = new CloudSchedulerEmulator(dataSource);
            schedulerEmulator.start();
            grpcBuilder.addService(schedulerEmulator.getServiceImpl());
            gateway.registerGrpcEmulator(schedulerEmulator, schedulerEmulator.getServiceImpl());
            sb.annotatedService("/v1", schedulerEmulator.getRestService());
            // Regex route to intercept before gRPC transcoding
            sb.service(Route.builder().methods(HttpMethod.POST)
                    .path("regex:^/v1/projects/(?<project>[^/]+)/locations/(?<location>[^/]+)/jobs$")
                    .build(), (ctx, req) -> {
                        var agg = req.aggregate().join();
                        return schedulerEmulator.getRestService().createJob(
                                ctx.pathParam("project"), ctx.pathParam("location"), agg.contentUtf8());
                    });
            sb.service(Route.builder().methods(HttpMethod.GET)
                    .path("regex:^/v1/projects/(?<project>[^/]+)/locations/(?<location>[^/]+)/jobs/(?<job>[^/]+)$")
                    .build(), (ctx, req) ->
                            schedulerEmulator.getRestService().getJob(ctx.pathParam("project"),
                                    ctx.pathParam("location"), ctx.pathParam("job")));
            sb.service(Route.builder().methods(HttpMethod.DELETE)
                    .path("regex:^/v1/projects/(?<project>[^/]+)/locations/(?<location>[^/]+)/jobs/(?<job>[^/]+)$")
                    .build(), (ctx, req) ->
                            schedulerEmulator.getRestService().deleteJob(ctx.pathParam("project"),
                                    ctx.pathParam("location"), ctx.pathParam("job")));
            seedService.setCloudSchedulerEmulator(schedulerEmulator);
            hasGrpcServices = true;
            logger.info("Cloud Scheduler facade registered on gateway port {}", config.getGatewayPort());
        }

        // Cloud Functions (2nd gen): REST only (Terraform compatibility)
        // Note: gRPC service is NOT registered — Terraform provider uses gRPC protocol
        // which bypasses our REST handlers and triggers SDK credential validation.
        // By providing only REST handlers, Terraform falls back to REST API calls.
        if (config.isServiceEnabled("cloudfunctions")) {
            CloudFunctionsEmulator functionsEmulator = new CloudFunctionsEmulator(dataSource);
            functionsEmulator.start();
            grpcBuilder.addService(functionsEmulator.getServiceImpl());
            gateway.registerGrpcEmulator(functionsEmulator, functionsEmulator.getServiceImpl());
            sb.annotatedService("/v1", functionsEmulator.getRestService());
            // Cloud Functions 2nd gen API uses /v2 prefix. Register REST handler
            // via explicit regex routes to intercept before gRPC HTTP/2 handler.
            sb.annotatedService("/v2", functionsEmulator.getRestService());
            // Also intercept /v2 HTTPS paths to prevent gRPC 401 from blocking REST calls
            sb.service(Route.builder().methods(HttpMethod.POST)
                    .path("regex:^/v2/projects/(?<project>[^/]+)/locations/(?<location>[^/]+)/functions$")
                    .build(), (ctx, req) -> {
                        var agg = req.aggregate().join();
                        return functionsEmulator.getRestService().createFunction(ctx,
                                ctx.pathParam("project"),
                                ctx.pathParam("location"), agg.contentUtf8());
                    });
            sb.service(Route.builder().methods(HttpMethod.GET)
                    .path("regex:^/v2/projects/(?<project>[^/]+)/locations/(?<location>[^/]+)/functions/(?<function>[^/]+)$")
                    .build(), (ctx, req) ->
                            functionsEmulator.getRestService().getFunction(ctx.pathParam("project"),
                                    ctx.pathParam("location"), ctx.pathParam("function")));
            sb.service(Route.builder().methods(HttpMethod.DELETE)
                    .path("regex:^/v2/projects/(?<project>[^/]+)/locations/(?<location>[^/]+)/functions/(?<function>[^/]+)$")
                    .build(), (ctx, req) ->
                            functionsEmulator.getRestService().deleteFunction(ctx.pathParam("project"),
                                    ctx.pathParam("location"), ctx.pathParam("function")));
            seedService.setCloudFunctionsEmulator(functionsEmulator);
            hasGrpcServices = true;
            logger.info("Cloud Functions facade registered on gateway port {}", config.getGatewayPort());
        }

        // AlloyDB: gRPC + explicit REST (Terraform compatibility)
        if (config.isServiceEnabled("alloydb")) {
            AlloyDBEmulator alloyDBEmulator = new AlloyDBEmulator(dataSource);
            alloyDBEmulator.start();
            grpcBuilder.addService(alloyDBEmulator.getServiceImpl());
            gateway.registerGrpcEmulator(alloyDBEmulator, alloyDBEmulator.getServiceImpl());
            // Explicit REST handler — gRPC transcoding doesn't map Terraform provider v6 paths
            sb.annotatedService("/v1", alloyDBEmulator.getRestService());
            seedService.setAlloyDBEmulator(alloyDBEmulator);
            hasGrpcServices = true;
            logger.info("AlloyDB facade registered on gateway port {}", config.getGatewayPort());
        }

        // Dataproc: gRPC (ClusterController + JobController) + explicit REST
        if (config.isServiceEnabled("dataproc")) {
            DataprocEmulator dataprocEmulator = new DataprocEmulator(dataSource);
            dataprocEmulator.start();
            grpcBuilder.addService(dataprocEmulator.getClusterService());
            grpcBuilder.addService(dataprocEmulator.getJobService());
            gateway.registerGrpcEmulator(dataprocEmulator,
                    dataprocEmulator.getClusterService(), dataprocEmulator.getJobService());
            sb.annotatedService("/v1", dataprocEmulator.getRestService());
            seedService.setDataprocEmulator(dataprocEmulator);
            hasGrpcServices = true;
            logger.info("Dataproc facade registered on gateway port {}", config.getGatewayPort());
        }

        // Cloud IAM: gRPC only
        if (config.isServiceEnabled("cloudiam")) {
            IAMEmulator iamEmulator = new IAMEmulator(dataSource, config.isIamLogWarningsEnabled());
            iamEmulator.start();
            var iamService = io.grpc.ServerInterceptors.intercept(
                    iamEmulator.getServiceImpl(), IAMEmulator.warningInterceptor());
            grpcBuilder.addService(iamService);
            gateway.registerGrpcEmulator(iamEmulator, iamEmulator.getServiceImpl());
            seedService.setIAMEmulator(iamEmulator);
            hasGrpcServices = true;
            logger.info("Cloud IAM facade registered on gateway port {}", config.getGatewayPort());
        }

        // Service Usage API endpoints are co-located in SecretManagerRestService (shared /v1 prefix)
        // to avoid Armeria prefix conflicts with multiple annotated services on the same path.
        logger.info("Service Usage API endpoints registered via SecretManagerRestService");

        // Cloud Resource Manager: REST only (Terraform google_project support)
        // Registered at /v1 and /v3 — Terraform google_project uses v3,
        // but GCS/BigQuery/PubSub client libraries validate projects via v1.
        if (config.isServiceEnabled("cloudresourcemanager")) {
            var crmV1Service = new CloudResourceManagerRestService(projectService, config, "v1");
            var crmV3Service = new CloudResourceManagerRestService(projectService, config, "v3");
            sb.annotatedService("/v1", crmV1Service);
            sb.annotatedService("/v3", crmV3Service);
            logger.info("Cloud Resource Manager facade registered at /v1 and /v3 on gateway port {}", config.getGatewayPort());
        }

        // Cloud Logging: gRPC only (with transcoding) + REST stubs for sink CRUD
        if (config.isServiceEnabled("logging")) {
            LoggingEmulator loggingEmulator = new LoggingEmulator(dataSource);
            loggingEmulator.start();
            grpcBuilder.addService(loggingEmulator.getLoggingService());
            gateway.registerGrpcEmulator(loggingEmulator, loggingEmulator.getLoggingService());
            hasGrpcServices = true;

            // Logging sink CRUD REST stubs — Terraform google_logging_project_sink uses these.
            sb.service(Route.builder().methods(HttpMethod.POST)
                    .path("regex:^/v2/projects/(?<project>[^/]+)/sinks$")
                    .build(), (ctx, req) -> {
                        String project = ctx.pathParam("project");
                        return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                            "{\"name\":\"projects/" + project + "/sinks/" +
                            java.util.UUID.randomUUID().toString().substring(0, 8) +
                            "\",\"destination\":\"bigquery.googleapis.com\",\"writerIdentity\":\"serviceAccount:cloud-logs@localcloud.iam.gserviceaccount.com\"}");
                    });
            sb.service(Route.builder().methods(HttpMethod.GET)
                    .path("regex:^/v2/projects/(?<project>[^/]+)/sinks/(?<sink>[^/]+)$")
                    .build(), (ctx, req) -> {
                        String project = ctx.pathParam("project");
                        String sink = ctx.pathParam("sink");
                        return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                            "{\"name\":\"projects/" + project + "/sinks/" + sink +
                            "\",\"destination\":\"bigquery.googleapis.com\",\"writerIdentity\":\"serviceAccount:cloud-logs@localcloud.iam.gserviceaccount.com\"}");
                    });
            sb.service(Route.builder().methods(HttpMethod.DELETE)
                    .path("regex:^/v2/projects/(?<project>[^/]+)/sinks/(?<sink>[^/]+)$")
                    .build(), (ctx, req) -> HttpResponse.of(HttpStatus.OK, MediaType.JSON, "{}"));
            logger.info("Cloud Logging sink REST stubs registered");
            logger.info("Cloud Logging facade registered on gateway port {}", config.getGatewayPort());
        }

        // Cloud Monitoring: gRPC only (with transcoding) + REST stubs for alert policies
        if (config.isServiceEnabled("monitoring")) {
            MonitoringEmulator monitoringEmulator = new MonitoringEmulator(dataSource);
            monitoringEmulator.start();
            grpcBuilder.addService(monitoringEmulator.getMonitoringService());
            gateway.registerGrpcEmulator(monitoringEmulator, monitoringEmulator.getMonitoringService());
            hasGrpcServices = true;

            // Monitoring alert policy CRUD REST stubs — Terraform google_monitoring_alert_policy uses these.
            sb.service(Route.builder().methods(HttpMethod.POST)
                    .path("regex:^/v3/projects/(?<project>[^/]+)/alertPolicies$")
                    .build(), (ctx, req) -> {
                        String project = ctx.pathParam("project");
                        return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                            "{\"name\":\"projects/" + project + "/alertPolicies/" +
                            java.util.UUID.randomUUID().toString().substring(0, 8) +
                            "\",\"displayName\":\"localcloud-alert\",\"enabled\":true}");
                    });
            sb.service(Route.builder().methods(HttpMethod.GET)
                    .path("regex:^/v3/projects/(?<project>[^/]+)/alertPolicies/(?<policy>[^/]+)$")
                    .build(), (ctx, req) -> {
                        String project = ctx.pathParam("project");
                        String policy = ctx.pathParam("policy");
                        return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                            "{\"name\":\"projects/" + project + "/alertPolicies/" + policy +
                            "\",\"displayName\":\"localcloud-alert\",\"enabled\":true}");
                    });
            sb.service(Route.builder().methods(HttpMethod.DELETE)
                    .path("regex:^/v3/projects/(?<project>[^/]+)/alertPolicies/(?<policy>[^/]+)$")
                    .build(), (ctx, req) -> HttpResponse.of(HttpStatus.OK, MediaType.JSON, "{}"));
            logger.info("Cloud Monitoring alert policy REST stubs registered");
            logger.info("Cloud Monitoring facade registered on gateway port {}", config.getGatewayPort());
        }

        // Compute Engine: REST only
        if (config.isServiceEnabled("compute")) {
            ContainerManager cm = containerManager != null ? containerManager : new ContainerManager(null);
            ComputeEmulator computeEmulator = new ComputeEmulator(dataSource, cm, credentialBroker, iamPolicyRestHandler);
            computeEmulator.start();
            sb.annotatedService("/compute/v1", computeEmulator.getRestService());
            gateway.registerRestEmulator("/compute/v1", computeEmulator, null);
            logger.info("Compute Engine facade registered on gateway port {}", config.getGatewayPort());
        }

        // Cloud Run: gRPC (Services + Revisions)
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

        // GKE: gRPC only
        if (config.isServiceEnabled("gke")) {
            K3dManager k3dManager = new K3dManager(credentialBroker);
            GkeEmulator gkeEmulator = new GkeEmulator(dataSource, k3dManager);
            gkeEmulator.start();
            grpcBuilder.addService(gkeEmulator.getClusterManagerService());
            gateway.registerGrpcEmulator(gkeEmulator, gkeEmulator.getClusterManagerService());
            hasGrpcServices = true;
            logger.info("GKE facade registered on gateway port {}", config.getGatewayPort());
        }

        // Cloud Workflows: gRPC + explicit REST + env vars + connector + callback
        if (config.isServiceEnabled("workflows")) {
            WorkflowsEmulator workflowsEmulator = new WorkflowsEmulator(dataSource);
            workflowsEmulator.start();
            var workflowsGrpc = new WorkflowsGrpcServiceImpl(workflowsEmulator.getWorkflowsService());
            var executionsGrpc = new ExecutionsGrpcServiceImpl(workflowsEmulator.getWorkflowsService());
            grpcBuilder.addService(workflowsGrpc);
            grpcBuilder.addService(executionsGrpc);
            gateway.registerGrpcEmulator(workflowsEmulator, workflowsGrpc, executionsGrpc);

            // Workflow env vars and connector services (register before callbacks to avoid path conflicts)
            var envVarsRepo = new WorkflowEnvVarsRepository(dataSource);
            workflowsEmulator.getWorkflowsService().setEnvVarsRepository(envVarsRepo);
            var envVarsService = new WorkflowEnvVarsService(config, envVarsRepo);
            sb.annotatedService("/workflow-env", envVarsService);
            var connectorService = new WorkflowConnectorService(config, envVarsRepo,
                    workflowsEmulator.getWorkflowsService().getStore());
            sb.annotatedService("/workflow", connectorService);
            sb.annotatedService("/v1", new WorkflowsRestService(
                    workflowsEmulator.getWorkflowsService(), workflowsEmulator, iamPolicyRestHandler));

            // Register callback HTTP endpoint so external systems can wake waiting executions
            WorkflowsCallbackService callbackService = new WorkflowsCallbackService(
                    workflowsEmulator.getWorkflowsService().getCallbackManager());
            sb.annotatedService("/workflows", callbackService);

            // Wire WorkflowsServiceImpl into MutateService so console executions
            // route through the full execution path (connectors, callbacks, env vars)
            mutateService.setWorkflowsService(workflowsEmulator.getWorkflowsService());

            hasGrpcServices = true;
            logger.info("Cloud Workflows facade registered on gateway port {}", config.getGatewayPort());
        }

        // Vertex AI: REST only
        if (config.isServiceEnabled("vertexai")) {
            VertexAiEmulator vertexAiEmulator = new VertexAiEmulator(dataSource, config.getGatewayPort(), iamPolicyRestHandler);
            vertexAiEmulator.start();
            sb.annotatedService("/v1", vertexAiEmulator.getRestService());
            gateway.registerRestEmulator("/v1/projects/*/locations/*/publishers/*/models", vertexAiEmulator, null);

            // Manual regex routes for Vertex AI :verb custom methods.
            // Armeria's annotation parser treats ':' as a regex delimiter inside
            // path parameters, so @Post("/.../{model}:generateContent") fails to match.
            var vaiService = vertexAiEmulator.getRestService();
            sb.service(Route.builder().methods(HttpMethod.POST)
                    .path("regex:^/v1/projects/(?<project>[^/]+)/locations/(?<location>[^/]+)/publishers/(?<publisher>[^/]+)/models/(?<model>[^:]+):generateContent$")
                    .build(), (ctx, req) -> {
                        var agg = req.aggregate().join();
                        return vaiService.generateContent(ctx.pathParam("project"), ctx.pathParam("location"),
                                ctx.pathParam("publisher"), ctx.pathParam("model"), agg.contentUtf8());
                    });
            sb.service(Route.builder().methods(HttpMethod.POST)
                    .path("regex:^/v1/projects/(?<project>[^/]+)/locations/(?<location>[^/]+)/publishers/(?<publisher>[^/]+)/models/(?<model>[^:]+):streamGenerateContent$")
                    .build(), (ctx, req) -> {
                        var agg = req.aggregate().join();
                        return vaiService.streamGenerateContent(ctx.pathParam("project"), ctx.pathParam("location"),
                                ctx.pathParam("publisher"), ctx.pathParam("model"), agg.contentUtf8());
                    });
            sb.service(Route.builder().methods(HttpMethod.POST)
                    .path("regex:^/v1/projects/(?<project>[^/]+)/locations/(?<location>[^/]+)/publishers/(?<publisher>[^/]+)/models/(?<model>[^:]+):embedContent$")
                    .build(), (ctx, req) -> {
                        var agg = req.aggregate().join();
                        return vaiService.embedContent(ctx.pathParam("project"), ctx.pathParam("location"),
                                ctx.pathParam("publisher"), ctx.pathParam("model"), agg.contentUtf8());
                    });
            sb.service(Route.builder().methods(HttpMethod.POST)
                    .path("regex:^/v1/projects/(?<project>[^/]+)/locations/(?<location>[^/]+)/publishers/(?<publisher>[^/]+)/models/(?<model>[^:]+):countTokens$")
                    .build(), (ctx, req) -> {
                        var agg = req.aggregate().join();
                        return vaiService.countTokens(ctx.pathParam("project"), ctx.pathParam("location"),
                                ctx.pathParam("publisher"), ctx.pathParam("model"), agg.contentUtf8());
                    });
            sb.service(Route.builder().methods(HttpMethod.POST)
                    .path("regex:^/v1/projects/(?<project>[^/]+)/locations/(?<location>[^/]+)/publishers/(?<publisher>[^/]+)/models/(?<model>[^:]+):computeTokens$")
                    .build(), (ctx, req) -> {
                        var agg = req.aggregate().join();
                        return vaiService.computeTokens(ctx.pathParam("project"), ctx.pathParam("location"),
                                ctx.pathParam("publisher"), ctx.pathParam("model"), agg.contentUtf8());
                    });
            logger.info("Vertex AI :verb custom method routes registered");
            logger.info("Vertex AI facade registered on gateway port {}", config.getGatewayPort());
        }

        // Cloud KMS: REST only
        if (config.isServiceEnabled("kms")) {
            KmsEmulator kmsEmulator = new KmsEmulator(dataSource, config.getGatewayPort(), iamPolicyRestHandler);
            kmsEmulator.start();
            sb.annotatedService("/v1", kmsEmulator.getRestService());
            gateway.registerRestEmulator("/v1/projects/*/locations/*/keyRings", kmsEmulator, null);

            // Manual regex routes for KMS :verb custom methods.
            // Armeria's annotation parser treats ':' as a regex delimiter inside
            // path parameters, so @Post("/.../{cryptoKey}:encrypt") fails to match.
            var kmsService = kmsEmulator.getRestService();
            sb.service(Route.builder().methods(HttpMethod.POST)
                    .path("regex:^/v1/projects/(?<project>[^/]+)/locations/(?<location>[^/]+)/keyRings/(?<keyRing>[^/]+)/cryptoKeys/(?<cryptoKey>[^:]+):encrypt$")
                    .build(), (ctx, req) -> {
                        var agg = req.aggregate().join();
                        return kmsService.encrypt(ctx.pathParam("project"), ctx.pathParam("location"),
                                ctx.pathParam("keyRing"), ctx.pathParam("cryptoKey"), agg.contentUtf8());
                    });
            sb.service(Route.builder().methods(HttpMethod.POST)
                    .path("regex:^/v1/projects/(?<project>[^/]+)/locations/(?<location>[^/]+)/keyRings/(?<keyRing>[^/]+)/cryptoKeys/(?<cryptoKey>[^:]+):decrypt$")
                    .build(), (ctx, req) -> {
                        var agg = req.aggregate().join();
                        return kmsService.decrypt(ctx.pathParam("project"), ctx.pathParam("location"),
                                ctx.pathParam("keyRing"), ctx.pathParam("cryptoKey"), agg.contentUtf8());
                    });
            sb.service(Route.builder().methods(HttpMethod.POST)
                    .path("regex:^/v1/projects/(?<project>[^/]+)/locations/(?<location>[^/]+)/keyRings/(?<keyRing>[^/]+)/cryptoKeys/(?<cryptoKey>[^:]+):updateCryptoKeyPrimaryVersion$")
                    .build(), (ctx, req) -> {
                        var agg = req.aggregate().join();
                        return kmsService.updatePrimaryVersion(ctx.pathParam("project"), ctx.pathParam("location"),
                                ctx.pathParam("keyRing"), ctx.pathParam("cryptoKey"), agg.contentUtf8());
                    });
            sb.service(Route.builder().methods(HttpMethod.POST)
                    .path("regex:^/v1/projects/(?<project>[^/]+)/locations/(?<location>[^/]+)/keyRings/(?<keyRing>[^/]+)/cryptoKeys/(?<cryptoKey>[^/]+)/cryptoKeyVersions/(?<version>[^:]+):destroy$")
                    .build(), (ctx, req) -> {
                        String p = ctx.pathParam("project");
                        String l = ctx.pathParam("location");
                        String kr = ctx.pathParam("keyRing");
                        String ck = ctx.pathParam("cryptoKey");
                        String v = ctx.pathParam("version");
                        return HttpResponse.from(req.aggregate().thenApply(agg ->
                                kmsService.destroyVersion(p, l, kr, ck, v)));
                    });
            sb.service(Route.builder().methods(HttpMethod.POST)
                    .path("regex:^/v1/projects/(?<project>[^/]+)/locations/(?<location>[^/]+)/keyRings/(?<keyRing>[^/]+)/cryptoKeys/(?<cryptoKey>[^/]+)/cryptoKeyVersions/(?<version>[^:]+):restore$")
                    .build(), (ctx, req) -> {
                        String p = ctx.pathParam("project");
                        String l = ctx.pathParam("location");
                        String kr = ctx.pathParam("keyRing");
                        String ck = ctx.pathParam("cryptoKey");
                        String v = ctx.pathParam("version");
                        return HttpResponse.from(req.aggregate().thenApply(agg ->
                                kmsService.restoreVersion(p, l, kr, ck, v)));
                    });
            logger.info("Cloud KMS :verb custom method routes registered");
            logger.info("Cloud KMS facade registered on gateway port {}", config.getGatewayPort());
        }

        if (hasGrpcServices) {
            sb.service(grpcBuilder.build());
        }

        // GraphQL API gateway — exposes a unified GraphQL endpoint at /graphql
        // that stitches together Spanner, BigQuery, Logging, Monitoring, and query history.
        var graphQLGateway = new GraphQLGateway(config.getServiceRegistry(), dataSource, config, queryHistoryRepo);
        sb.service("/graphql", graphQLGateway.getService());
        logger.info("GraphQL gateway registered at /graphql");

        // Data Mirror sync service
        var registry = config.getServiceRegistry();
        SyncManifestRepository syncManifestRepo = new SyncManifestRepository(dataSource.getDataSource());

        // Encryption key for sync credentials — use env var, or auto-generate ephemeral key
        String encKeyValue = System.getProperty("localcloud.encryption.key",
            System.getenv().getOrDefault("LOCALCLOUD_ENCRYPTION_KEY", ""));
        CredentialEncryption credEncryption = null;
        if (!encKeyValue.isBlank()) {
            credEncryption = new CredentialEncryption(encKeyValue);
        } else {
            // Generate ephemeral key for this session (credentials won't survive restart without config)
            try {
                String key = CredentialEncryption.generateKey();
                credEncryption = new CredentialEncryption(key);
                logger.info("Generated ephemeral encryption key for sync credentials (configure LOCALCLOUD_ENCRYPTION_KEY to persist)");
            } catch (Exception e) {
                logger.warn("Failed to initialize credential encryption: {}", e.getMessage());
            }
        }
        SyncCredentialRepository syncCredentialRepo = new SyncCredentialRepository(
            dataSource.getDataSource(), credEncryption);
        SyncService syncService = new SyncService(syncManifestRepo, syncCredentialRepo, 1.0);

        // Register sync adapters for services that have emulators
        com.fasterxml.jackson.databind.ObjectMapper syncMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        if (registry.getService("bigquery") != null) {
            syncService.registerAdapter("bigquery", new com.localcloud.sync.adapters.BigQuerySyncAdapter(
                    "http://localhost:" + registry.getService("bigquery").port(), syncMapper));
        }
        if (registry.getService("firestore") != null) {
            syncService.registerAdapter("firestore", new com.localcloud.sync.adapters.FirestoreSyncAdapter(
                    "localhost", registry.getService("firestore").port(), syncMapper));
        }
        if (registry.getService("gcs") != null) {
            syncService.registerAdapter("gcs", new com.localcloud.sync.adapters.GcsSyncAdapter(
                    "http://localhost:" + registry.getService("gcs").port(), syncMapper));
        }
        if (registry.getService("spanner") != null) {
            syncService.registerAdapter("spanner", new com.localcloud.sync.adapters.SpannerSyncAdapter(
                    "localhost", registry.getService("spanner").port(), syncMapper));
        }
        if (registry.getService("bigtable") != null) {
            syncService.registerAdapter("bigtable", new com.localcloud.sync.adapters.BigtableSyncAdapter(
                    "localhost", registry.getService("bigtable").port(), syncMapper));
        }

        SyncApiService syncApiService = new SyncApiService(syncService, syncCredentialRepo, config);
        sb.annotatedService("/sync", syncApiService);

        // Spanner IAM stubs — intercept SetIamPolicy/GetIamPolicy/TestIamPermissions
        // before they fall through to the NOT_IMPLEMENTED handler or reach the C++ emulator.
        sb.service(Route.builder()
                .methods(com.linecorp.armeria.common.HttpMethod.POST, com.linecorp.armeria.common.HttpMethod.GET)
                .path("regex:/v1/projects/(?<project>[^/]+)/instances/(?<instance>[^/]+)(?:/databases/(?<database>[^/]+))?:(?:setIamPolicy|getIamPolicy|testIamPermissions)")
                .build(),
                new SpannerIamService());
        logger.info("Spanner IAM stubs registered (permissive mode)");

        // Spanner REST proxy — forwards non-IAM Spanner API requests from the gateway
        // to the external C++ Spanner emulator (port 9020). This enables Terraform's
        // GOOGLE_SPANNER_CUSTOM_ENDPOINT to point at the gateway (port 8080) so that
        // SpannerIamService can intercept IAM calls while data-plane calls proxy through.
        if (config.isServiceEnabled("spanner")) {
            var spannerPort = config.getServiceRegistry().getService("spanner");
            if (spannerPort != null && spannerPort.additionalPorts() != null) {
                Integer restPort = spannerPort.additionalPorts().get("rest");
                if (restPort != null) {
                    int spannerRestPort = restPort;
                    // Shared WebClient with connection pooling — reused across all proxy requests
                    var spannerClient = com.linecorp.armeria.client.WebClient.builder("http://localhost:" + spannerRestPort)
                            .responseTimeoutMillis(30000)
                            .writeTimeoutMillis(10000)
                            .build();
                    sb.service(Route.builder()
                            .methods(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)
                            .path("regex:^/v1/projects/(?<project>[^/]+)/instances(/.*)?$")
                            .build(), (ctx, req) -> {
                                // This proxy handles non-IAM Spanner REST paths.
                                // IAM paths (:setIamPolicy etc.) are intercepted above.
                                String path = ctx.path();
                                if (path.contains(":setIamPolicy") || path.contains(":getIamPolicy")
                                        || path.contains(":testIamPermissions")) {
                                    return HttpResponse.of(HttpStatus.NOT_FOUND);
                                }
                                if (req.method() == HttpMethod.GET) {
                                    return HttpResponse.from(spannerClient.get(path).aggregate()
                                            .thenApply(agg -> HttpResponse.of(agg.status(), agg.headers().contentType(), agg.content())));
                                }
                                return HttpResponse.from(req.aggregate().thenCompose(agg ->
                                        spannerClient.execute(
                                                com.linecorp.armeria.common.HttpRequest.of(
                                                        com.linecorp.armeria.common.RequestHeaders.of(req.method(), path,
                                                                com.linecorp.armeria.common.HttpHeaderNames.CONTENT_TYPE,
                                                                agg.headers().contentType() != null
                                                                        ? agg.headers().contentType().toString()
                                                                        : "application/json"),
                                                        agg.content()))
                                                .aggregate()
                                                .thenApply(respAgg -> HttpResponse.of(respAgg.status(),
                                                        respAgg.headers().contentType(), respAgg.content()))));
                            });
                    logger.info("Spanner REST proxy registered — forwarding to port {}", spannerRestPort);
                }
            }
        }

        // Generic IAM catch-all for services without explicit REST IAM support.
        // Handles patterns like /v1/.../{resource}:setIamPolicy and
        // /compute/v1/.../{resource}:setIamPolicy for any service.
        sb.service(Route.builder()
                .methods(HttpMethod.POST, HttpMethod.GET)
                .path("regex:^/(?:compute/)?v[0-9]+/.*:(setIamPolicy|getIamPolicy|testIamPermissions)$")
                .build(),
                (ctx, req) -> {
                    String path = ctx.path();
                    String method = req.method().name();
                    String action = path.substring(path.lastIndexOf(':') + 1);
                    String resource = path.substring(0, path.lastIndexOf(':'));
                    // Strip /v1/, /v2/, or /compute/v1/ prefix to get the resource path
                    resource = resource.replaceFirst("^/(?:compute/)?v[0-9]+/", "");
                    try {
                        var aggregated = req.aggregate().join();
                        String body = aggregated.contentUtf8();
                        String result = switch (action) {
                            case "getIamPolicy" -> iamPolicyRestHandler.getIamPolicy(resource);
                            case "setIamPolicy" -> iamPolicyRestHandler.setIamPolicy(resource, body);
                            case "testIamPermissions" -> iamPolicyRestHandler.testIamPermissions(resource, body);
                            default -> throw new IllegalArgumentException("Unknown IAM action: " + action);
                        };
                        return HttpResponse.of(HttpStatus.OK, MediaType.JSON, result);
                    } catch (Exception e) {
                        logger.error("IAM request failed for {} {}", method, path, e);
                        return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.JSON,
                            "{\"error\":{\"code\":500,\"message\":\"" + e.getMessage() + "\"}}");
                    }
                });
        logger.info("Generic IAM catch-all registered for all services");

        // Legacy dashboard route - redirect to console root
        sb.service("/dashboard/", (ctx, req) -> {
            ResponseHeaders headers = ResponseHeaders.builder(HttpStatus.MOVED_PERMANENTLY)
                    .add("Location", "/")
                    .add("Cache-Control", "no-cache")
                    .build();
            return HttpResponse.of(headers);
        });

        // OAuth2 token endpoint — Google client libraries call oauth2.googleapis.com/token
        // before making REST API calls. Emulate this locally so auth stays within LocalCloud.
        var oauth2Service = new OAuth2RestService();
        sb.service("/token", (ctx, req) -> {
            if (req.method() != HttpMethod.POST) return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
            return HttpResponse.from(req.aggregate().thenApply(
                agg -> oauth2Service.token(agg.contentUtf8())));
        });
        sb.service("/tokeninfo", (ctx, req) -> {
            if (req.method() != HttpMethod.GET) return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
            return oauth2Service.tokenInfo();
        });
        sb.service("/oauth2/v3/certs", (ctx, req) -> {
            if (req.method() != HttpMethod.GET) return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
            return oauth2Service.certs();
        });
        // Userinfo endpoint — Terraform provider calls userinfo to validate credentials.
        // Both /oauth2/v1/userinfo and /v1/userinfo paths are used by different SDK versions.
        sb.service(Route.builder().methods(HttpMethod.GET)
                .path("regex:^/(?:oauth2/v1|v1|oauth2/v2)/userinfo$")
                .build(), (ctx, req) -> oauth2Service.userInfo());
        logger.info("OAuth2 token, userinfo, and certs endpoints registered on gateway port {}", config.getGatewayPort());

        // Global catch-all: SPA routing when console is available, 501 fallback otherwise
        // spaIndex and consoleAvailable were loaded earlier before the SPA decorator.
        if (consoleAvailable) {
            FileService fileService = FileService.of(consoleDist);
            final String spaHtml = spaIndex;

            // Explicit SPA route for /dashboard to avoid auto-redirect
            sb.service("/dashboard", (ctx, req) -> {
                if (req.method() != HttpMethod.GET) {
                    return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
                }
                ResponseHeaders h = ResponseHeaders.builder(HttpStatus.OK)
                        .add("Content-Type", "text/html; charset=utf-8")
                        .add("Cache-Control", "no-cache")
                        .build();
                return HttpResponse.of(h, HttpData.ofUtf8(spaHtml));
            });

            sb.service("prefix:/", (ctx, req) -> {
                String path = ctx.path();
                Path filePath = consoleDist.resolve(path.startsWith("/") ? path.substring(1) : path).normalize();
                if (!filePath.startsWith(consoleDist)) {
                    return HttpResponse.of(HttpStatus.NOT_FOUND);
                }
                if (Files.isRegularFile(filePath) && !Files.isHidden(filePath)) {
                    return fileService.serve(ctx, req);
                }
                Path indexPath = filePath.resolve("index.html");
                if (Files.isRegularFile(indexPath)) {
                    return fileService.serve(ctx, req);
                }
                if (req.method() != HttpMethod.GET || looksLikeApiOrAssetPath(path)) {
                    String method = req.method().toString().replace("\\", "\\\\").replace("\"", "\\\"");
                    String unsupportedPath = ctx.path().replace("\\", "\\\\").replace("\"", "\\\"");
                    return HttpResponse.of(HttpStatus.NOT_IMPLEMENTED, MediaType.JSON,
                        """
                        {
                          "error": {
                            "code": 501,
                            "message": "LocalCloud does not emulate this API endpoint yet",
                            "method": "%s",
                            "path": "%s"
                          }
                        }
                        """.formatted(method, unsupportedPath));
                }
                ResponseHeaders spaHeaders = ResponseHeaders.builder(HttpStatus.OK)
                        .add("Content-Type", "text/html; charset=utf-8")
                        .add("Cache-Control", "no-cache")
                        .build();
                return HttpResponse.of(spaHeaders, HttpData.ofUtf8(spaHtml));
            });
            logger.info("Console UI with SPA routing served from {}", consoleDist);
        } else {
            sb.service("prefix:/", (ctx, req) -> {
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
                              "suggestion": "Check /services for available emulated services, or see contracts/emulated-services.md for supported operations."
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
        logger.info("  Health:      http://localhost:{}/health", port);
        logger.info("  Console:     http://localhost:{}", port);
        logger.info("  Dashboard:   http://localhost:{}/dashboard/", port);
        logger.info("  Persistence: {}", config.isPersistenceEnabled() ? "enabled (" + config.getDataDir() + ")" : "disabled");
        logger.info("  Services:    {}", config.getEnabledServices());
        logger.info("=================================================");

        // Terraform readiness pre-flight check (non-blocking, logged at startup)
        try {
            java.net.InetAddress addr = java.net.InetAddress.getByName("serviceusage.googleapis.com");
            if (!"127.0.0.1".equals(addr.getHostAddress())) {
                logger.warn("=================================================");
                logger.warn("  ⚠ TERRAFORM DNS NOT CONFIGURED");
                logger.warn("  serviceusage.googleapis.com resolves to {} (expected 127.0.0.1)",
                    addr.getHostAddress());
                logger.warn("  Fix (recommended):");
                logger.warn("    echo 'nameserver 127.0.0.1' | sudo tee /etc/resolver/googleapis.com");
                logger.warn("  Or per-host:");
                logger.warn("    echo '127.0.0.1 serviceusage.googleapis.com' | sudo tee -a /etc/hosts");
                logger.warn("  Without this, Terraform resources will fail with SERVICE_DISABLED");
                logger.warn("=================================================");
            } else {
                logger.info("  Terraform DNS: ✓ serviceusage.googleapis.com → 127.0.0.1");
            }
        } catch (java.net.UnknownHostException e) {
            logger.warn("=================================================");
            logger.warn("  ⚠ TERRAFORM DNS NOT CONFIGURED");
            logger.warn("  Cannot resolve serviceusage.googleapis.com");
            logger.warn("  Fix (recommended):");
            logger.warn("    echo 'nameserver 127.0.0.1' | sudo tee /etc/resolver/googleapis.com");
            logger.warn("  Or per-host:");
            logger.warn("    echo '127.0.0.1 serviceusage.googleapis.com' | sudo tee -a /etc/hosts");
            logger.warn("  Without this, Terraform resources will fail with SERVICE_DISABLED");
            logger.warn("=================================================");
        }

        // Start anonymous telemetry (opt-out: LOCALCLOUD_TELEMETRY=false)
        telemetryService.start();

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stop();
        }, "localcloud-shutdown"));
    }

    private static boolean looksLikeApiOrAssetPath(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) return false;
        String normalized = path.startsWith("/") ? path : "/" + path;
        if (normalized.startsWith("/browse/")
                || normalized.startsWith("/mutate/")
                || normalized.startsWith("/query")
                || normalized.startsWith("/schema/")
                || normalized.startsWith("/reset")
                || normalized.startsWith("/services")
                || normalized.startsWith("/health")
                || normalized.startsWith("/requests")
                || normalized.startsWith("/env")
                || normalized.startsWith("/usage")
                || normalized.startsWith("/projects")
                || normalized.startsWith("/routing")
                || normalized.startsWith("/credentials")
                || normalized.startsWith("/sync/")
                || normalized.startsWith("/workflow")
                || normalized.startsWith("/graphql")) {
            return true;
        }
        String lastSegment = normalized.substring(normalized.lastIndexOf('/') + 1);
        return lastSegment.contains(".");
    }

    private static final String GREEN = "\033[32m";
    private static final String RED = "\033[31m";
    private static final String RESET = "\033[0m";

    private static String padRight(String s, int n) {
        return String.format("%-" + n + "s", s);
    }

    private static String stopped() {
        return " " + GREEN + "[STOPPED]" + RESET;
    }

    private static String failure() {
        return " " + RED + "[FAILURE]" + RESET;
    }

    /**
     * Gracefully stop the server and release all resources.
     */
    public void stop() {
        logger.info("");
        logger.info("═══════════════════════════════════════════════════════════════");
        logger.info("  ✕ Shutting Down LocalCloud...");
        logger.info("═══════════════════════════════════════════════════════════════");
        logger.info("");

        // Stop Armeria server first (drain in-flight requests)
        if (server != null) {
            server.stop().join();
            logger.info("  {}{}", padRight("Gateway", 28), stopped());
        }

        // Stop telemetry scheduler & drain queued events
        telemetryService.stop();
        logger.info("  {}{}", padRight("Telemetry", 28), stopped());

        // Flush usage metrics from memory to PostgreSQL
        healthCheckService.shutdown();
        logger.info("  {}{}", padRight("Usage metrics", 28), stopped());

        // Stop all registered facade emulators (in-process services)
        int stopped = 0;
        for (var emulator : gateway.getEmulators()) {
            try {
                emulator.stop();
                stopped++;
            } catch (Exception e) {
                logger.warn("  {}{}", padRight(emulator.getDisplayName(), 28), failure());
            }
        }
        logger.info("  {}{}", padRight(stopped + " emulator(s)", 28), stopped());

        // Close HTTP client connections
        processHealthChecker.close();
        if (iamMiddleware != null) {
            iamMiddleware.close();
        }
        seedService.close();
        logger.info("  {}{}", padRight("Client connections", 28), stopped());

        // Close database connection pool last
        dataSource.close();
        logger.info("  {}{}", padRight("Database pool", 28), stopped());

        logger.info("");
        logger.info("═══════════════════════════════════════════════════════════════");
        logger.info("  ✓ LocalCloud shutdown complete");
        logger.info("═══════════════════════════════════════════════════════════════");
        logger.info("");
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
