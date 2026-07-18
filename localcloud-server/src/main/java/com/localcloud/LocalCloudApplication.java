package com.localcloud;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.logging.AccessLogWriter;
import com.localcloud.admin.EnvService;
import com.localcloud.admin.DiagnosticsService;
import com.localcloud.admin.ProjectsApiService;
import com.localcloud.admin.ServicesConfigService;
import com.localcloud.admin.BrowseService;
import com.localcloud.admin.CredentialBroker;
import com.localcloud.admin.ExportService;
import com.localcloud.admin.FaultInjectionService;
import com.localcloud.admin.MutateService;
import com.localcloud.admin.McpService;
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
import com.localcloud.emulators.common.ServiceRegistrar;
import com.localcloud.emulators.common.ServiceRegistrationContext;
import com.localcloud.emulators.iam.IAMPolicyRestHandler;
import com.localcloud.emulators.iam.IAMRepository;
import com.localcloud.emulators.cloudsql.CloudSqlRegistrar;
import com.localcloud.emulators.bigtable.BigtableRegistrar;
import com.localcloud.emulators.memorystore.MemorystoreRegistrar;
import com.localcloud.emulators.pubsub.PubSubRestRegistrar;
import com.localcloud.emulators.secretmanager.SecretManagerRegistrar;
import com.localcloud.emulators.cloudtasks.CloudTasksRegistrar;
import com.localcloud.emulators.scheduler.CloudSchedulerRegistrar;
import com.localcloud.emulators.functions.CloudFunctionsRegistrar;
import com.localcloud.emulators.alloydb.AlloyDBRegistrar;
import com.localcloud.emulators.dataproc.DataprocRegistrar;
import com.localcloud.emulators.iam.IAMRegistrar;
import com.localcloud.emulators.logging.LoggingRegistrar;
import com.localcloud.emulators.monitoring.MonitoringRegistrar;
import com.localcloud.emulators.compute.ComputeRegistrar;
import com.localcloud.emulators.cloudrun.CloudRunRegistrar;
import com.localcloud.emulators.gke.GKERegistrar;
import com.localcloud.emulators.vertexai.VertexAIRegistrar;
import com.localcloud.emulators.kms.KMSRegistrar;
import com.localcloud.emulators.workflows.WorkflowsRegistrar;
import com.localcloud.emulators.cloudresourcemanager.CloudResourceManagerRegistrar;
import com.localcloud.emulators.serviceusage.ServiceUsageRegistrar;
import com.localcloud.emulators.cloudbilling.CloudBillingRegistrar;
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
import com.localcloud.persistence.FlywayMigrationRunner;
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
    private EnvService envService;
    private DiagnosticsService diagnosticsService;
    private ProjectsApiService projectsApiService;
    private ServicesConfigService servicesConfigService;
    private final ServiceConfigRepository serviceConfigRepository;
    private final TelemetryService telemetryService;
    private final BrowseService browseService;
    private final MutateService mutateService;
    private final SeedService seedService;
    private final ExportService exportService;
    private final CredentialBroker credentialBroker;
    private final QueryService queryService;
    private final QueryHistoryRepository queryHistoryRepo;
    private IamMiddleware iamMiddleware;
    private Server server;
    private final IAMPolicyRestHandler iamPolicyRestHandler;
    private final List<ServiceRegistrar> serviceRegistrars;

    // Stored for deferred admin API service construction after license validation
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

        this.serviceRegistrars = List.of(
                new CloudSqlRegistrar(),
                new BigtableRegistrar(),
                new MemorystoreRegistrar(),
                new PubSubRestRegistrar(),
                new SecretManagerRegistrar(),
                new CloudTasksRegistrar(),
                new CloudSchedulerRegistrar(),
                new CloudFunctionsRegistrar(),
                new AlloyDBRegistrar(),
                new DataprocRegistrar(),
                new IAMRegistrar(),
                new LoggingRegistrar(),
                new MonitoringRegistrar(),
                new ComputeRegistrar(),
                new CloudRunRegistrar(),
                new GKERegistrar(),
                new VertexAIRegistrar(),
                new KMSRegistrar(),
                new WorkflowsRegistrar(),
                new CloudResourceManagerRegistrar(),
                new ServiceUsageRegistrar(),
                new CloudBillingRegistrar()
        );
    }

    /**
     * Build and start the Armeria server.
     */
    public void start() throws Exception {
        // Ensure data directory exists
        if (config.isPersistenceEnabled()) {
            Files.createDirectories(config.getDataDir());
        }

        // Run Flyway migrations first (establishes flyway_schema_history, applies new migrations)
        try {
            new FlywayMigrationRunner(dataSource.getDataSource()).migrate();
        } catch (Exception e) {
            logger.warn("Flyway migration skipped: {}", e.getMessage());
        }

        // Initialize database schema (base tables, backward compatible with Flyway)
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

        // Create tier provider and admin API services now that the license tier is known
        LicenseTier currentTier = licenseResult.tier() != null ? licenseResult.tier() : LicenseTier.COMMUNITY;
        LicenseTierProvider tierProvider = new StaticLicenseTierProvider(currentTier);
        this.envService = new EnvService(config);
        this.diagnosticsService = new DiagnosticsService(config, requestLogger, faultInjectionRegistry);
        this.projectsApiService = new ProjectsApiService(config, projectService);
        this.servicesConfigService = new ServicesConfigService(config, projectService,
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

        // Access logging — structured request logs for debugging
        sb.accessLogWriter(AccessLogWriter.combined(), true);

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
        sb.annotatedService("/", envService);
        sb.annotatedService("/", diagnosticsService);
        sb.annotatedService("/", projectsApiService);
        sb.annotatedService("/", servicesConfigService);
        sb.annotatedService("/browse", browseService);
        sb.annotatedService("/mutate", mutateService);
        sb.annotatedService("/", seedService);
        sb.annotatedService("/", exportService);
        sb.annotatedService("/", queryService);
        sb.annotatedService("/", new SnapshotService(config, exportService, seedService));
        var faultInjectionService = new FaultInjectionService(faultInjectionRegistry);
        sb.annotatedService("/", faultInjectionService);
        sb.annotatedService("/", new McpService(config, browseService, diagnosticsService, seedService,
                exportService, queryService, faultInjectionService));
        sb.annotatedService("/computeMetadata/v1", new MetadataServerService(config));

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
        boolean hasGrpcServices = true; // OperationsGrpcService always registered

        // Build registration context with resolved ContainerManager and ApiGateway
        var regCtx = new ServiceRegistrationContext(
                config, dataSource, seedService, mutateService, credentialBroker,
                containerManager, projectService, iamPolicyRestHandler, gateway);

        // Delegate all facade emulator route registration to their registrars
        logger.info("Registering {} facade emulator services...", serviceRegistrars.size());
        for (var registrar : serviceRegistrars) {
            try {
                registrar.registerRoutes(sb, grpcBuilder, regCtx);
            } catch (Exception e) {
                logger.error("Failed to register {}: {}",
                        registrar.getClass().getSimpleName(), e.getMessage(), e);
            }
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
                                    return HttpResponse.of(spannerClient.get(path).aggregate()
                                            .thenApply(agg -> HttpResponse.of(agg.status(), agg.headers().contentType(), agg.content())));
                                }
                                return HttpResponse.of(req.aggregate().thenCompose(agg ->
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
            return HttpResponse.of(req.aggregate().thenApply(
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

        // DocService — interactive debug console for all gRPC + REST operations
        sb.serviceUnder("/docs", new DocService());

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
                || normalized.startsWith("/gcs/")
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
