package com.localcloud.admin;

import static com.localcloud.admin.AdminApiSupport.*;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.Put;
import com.localcloud.config.LocalCloudConfig;
import com.localcloud.config.ServiceRegistry;
import com.localcloud.config.ServiceRegistry.ServiceDefinition;
import com.localcloud.emulators.iam.IAMEmulator;
import com.localcloud.gateway.FaultInjectionRegistry;
import com.localcloud.licensing.LicenseTier;
import com.localcloud.licensing.LicenseTierProvider;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service enable/disable, routing, credentials, and runtime config management.
 * Extracted from AdminApiService.
 */
public class ServicesConfigService {

    private static final Logger logger = LoggerFactory.getLogger(ServicesConfigService.class);
    private final LocalCloudConfig config;
    private final ProjectService projectService;
    private final ServiceRoutingRepository routingRepository;
    private final CredentialBroker credentialBroker;
    private final ServiceConfigRepository serviceConfigRepository;
    private final LicenseTierProvider tierProvider;
    private final FaultInjectionRegistry faultInjectionRegistry;
    private final SupervisorClient supervisorClient;

    public ServicesConfigService(LocalCloudConfig config, ProjectService projectService,
                                 ServiceRoutingRepository routingRepository,
                                 CredentialBroker credentialBroker,
                                 ServiceConfigRepository serviceConfigRepository,
                                 LicenseTierProvider tierProvider,
                                 FaultInjectionRegistry faultInjectionRegistry) {
        this.config = config;
        this.projectService = projectService;
        this.routingRepository = routingRepository;
        this.credentialBroker = credentialBroker;
        this.serviceConfigRepository = serviceConfigRepository;
        this.tierProvider = tierProvider;
        this.faultInjectionRegistry = faultInjectionRegistry;
        this.supervisorClient = new SupervisorClient();
    }

    @Get("/routing")
    public HttpResponse routing(ServiceRequestContext ctx) {
        try {
            String projectParam = ctx.queryParams().get("project");
            String projectId = (projectParam != null && !projectParam.isBlank())
                    ? projectParam : config.getProjectId();

            Map<String, Map<String, String>> persisted = routingRepository.getAll(projectId);
            ServiceRegistry registry = config.getServiceRegistry();
            Map<String, Object> response = new LinkedHashMap<>();

            for (Map.Entry<String, ServiceDefinition> entry : registry.getAllServices().entrySet()) {
                String serviceId = entry.getKey();
                ServiceDefinition def = entry.getValue();
                boolean enabled = config.isServiceEnabled(serviceId);
                String routing = enabled ? "local" : "cloud";
                boolean emulatorRunning = enabled;
                boolean healthy = enabled;
                Map<String, String> routingConfig = persisted.get(serviceId);
                String mode = routingConfig != null ? routingConfig.get("mode") : "local";

                Map<String, Object> serviceRouting = new LinkedHashMap<>();
                serviceRouting.put("emulatorRunning", emulatorRunning);
                serviceRouting.put("healthy", healthy);
                serviceRouting.put("routing", routing);
                serviceRouting.put("mode", mode);
                serviceRouting.put("port", def.port());
                serviceRouting.put("envVar", def.envVar());
                if ("remote".equals(mode) && routingConfig != null) {
                    serviceRouting.put("remote_project", routingConfig.get("remote_project"));
                    serviceRouting.put("remote_region", routingConfig.get("remote_region"));
                }
                response.put(serviceId, serviceRouting);
            }
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper().writerWithDefaultPrettyPrinter().writeValueAsString(response));
        } catch (Exception e) {
            logger.error("Error getting routing status", e);
            return errorResponse(e);
        }
    }

    @SuppressWarnings("unchecked")
    @Put("/routing/{service}")
    public HttpResponse setRouting(ServiceRequestContext ctx, @Param("service") String serviceId,
                                   AggregatedHttpRequest request) {
        try {
            ServiceRegistry registry = config.getServiceRegistry();
            if (!registry.getAllServices().containsKey(serviceId)) {
                return HttpResponse.of(HttpStatus.NOT_FOUND, MediaType.JSON,
                        mapper().writeValueAsString(Map.of("error", true, "message", "Unknown service: " + serviceId)));
            }
            Map<String, Object> body = mapper().readValue(request.contentUtf8(), Map.class);
            String mode = (String) body.get("mode");
            if (mode == null || (!mode.equals("local") && !mode.equals("remote"))) {
                return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON,
                        mapper().writeValueAsString(Map.of("error", true, "message", "mode must be 'local' or 'remote'")));
            }
            String remoteProject = (String) body.get("remote_project");
            String remoteRegion = (String) body.get("remote_region");
            String projectParam = ctx.queryParams().get("project");
            String projectId = (projectParam != null && !projectParam.isBlank()) ? projectParam : config.getProjectId();
            routingRepository.upsert(projectId, serviceId, mode, remoteProject, remoteRegion);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("service", serviceId);
            result.put("mode", mode);
            result.put("remote_project", remoteProject);
            result.put("remote_region", remoteRegion);
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
        } catch (Exception e) {
            logger.error("Error setting routing for service '{}'", serviceId, e);
            return errorResponse(e);
        }
    }

    @Get("/credentials")
    public HttpResponse credentials() {
        try {
            String json = mapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(credentialBroker.getStatus());
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, json);
        } catch (Exception e) {
            logger.error("Error getting credential status", e);
            return errorResponse(e);
        }
    }

    @Post("/services/{id}/enable")
    public HttpResponse enableService(@Param("id") String serviceId) {
        try {
            ServiceRegistry registry = config.getServiceRegistry();
            ServiceDefinition def = registry.getAllServices().get(serviceId);
            if (def == null) {
                return HttpResponse.of(HttpStatus.NOT_FOUND, MediaType.JSON,
                        mapper().writeValueAsString(Map.of("error", true, "message", "Unknown service: " + serviceId)));
            }
            LicenseTier required = def.minTier() != null ? def.minTier() : LicenseTier.COMMUNITY;
            if (!tierProvider.currentTier().includes(required)) {
                return HttpResponse.of(HttpStatus.FORBIDDEN, MediaType.JSON,
                        mapper().writeValueAsString(Map.of(
                                "error", "Service '" + serviceId + "' requires " + required.name().toLowerCase() + " tier or higher",
                                "current_tier", tierProvider.currentTier().name().toLowerCase(),
                                "required_tier", required.name().toLowerCase(),
                                "upgrade_url", UPGRADE_URL)));
            }
            if (config.isServiceDynamicallyEnabled(serviceId)) {
                return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                        mapper().writeValueAsString(Map.of("service", serviceId, "status", "already_enabled")));
            }
            if ("external".equals(def.type())) {
                String programName = SUPERVISOR_PROGRAM_NAMES.get(serviceId);
                if (programName != null) {
                    boolean started = supervisorClient.startProcess(programName);
                    if (!started) {
                        logger.warn("Supervisor failed to start process '{}' for service '{}'", programName, serviceId);
                    }
                }
            }
            config.setServiceEnabled(serviceId, true);
            try { serviceConfigRepository.upsert(serviceId, true); }
            catch (Exception pe) { logger.warn("Failed to persist enable state for '{}': {}", serviceId, pe.getMessage()); }
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("service", serviceId, "status", "enabled")));
        } catch (Exception e) {
            logger.error("Error enabling service '{}'", serviceId, e);
            return errorResponse(e);
        }
    }

    @Post("/services/{id}/disable")
    public HttpResponse disableService(@Param("id") String serviceId) {
        try {
            ServiceRegistry registry = config.getServiceRegistry();
            ServiceDefinition def = registry.getAllServices().get(serviceId);
            if (def == null) {
                return HttpResponse.of(HttpStatus.NOT_FOUND, MediaType.JSON,
                        mapper().writeValueAsString(Map.of("error", true, "message", "Unknown service: " + serviceId)));
            }
            if (!config.isServiceDynamicallyEnabled(serviceId)) {
                return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                        mapper().writeValueAsString(Map.of("service", serviceId, "status", "already_disabled")));
            }
            if ("external".equals(def.type())) {
                String programName = SUPERVISOR_PROGRAM_NAMES.get(serviceId);
                if (programName != null) {
                    boolean stopped = supervisorClient.stopProcess(programName);
                    if (!stopped) {
                        logger.warn("Supervisor failed to stop process '{}' for service '{}'", programName, serviceId);
                    }
                }
            }
            config.setServiceEnabled(serviceId, false);
            try { serviceConfigRepository.upsert(serviceId, false); }
            catch (Exception pe) { logger.warn("Failed to persist disable state for '{}': {}", serviceId, pe.getMessage()); }
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("service", serviceId, "status", "disabled")));
        } catch (Exception e) {
            logger.error("Error disabling service '{}'", serviceId, e);
            return errorResponse(e);
        }
    }

    @Get("/config/services")
    public HttpResponse getServiceConfig() {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            for (String serviceId : config.getServiceRegistry().getAllServices().keySet()) {
                Map<String, Object> svcConfig = new LinkedHashMap<>();
                svcConfig.put("enabled", config.isServiceDynamicallyEnabled(serviceId));
                svcConfig.put("source", config.getConfigSource(serviceId));
                svcConfig.put("locked", "env".equals(config.getConfigSource(serviceId)));
                result.put(serviceId, svcConfig);
            }
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
        } catch (Exception e) {
            logger.error("Error getting service config", e);
            return errorResponse(e);
        }
    }

    @SuppressWarnings("unchecked")
    @Put("/config/services")
    public HttpResponse updateServiceConfig(String body) {
        try {
            Map<String, Object> updates = mapper().readValue(body, Map.class);
            Map<String, Object> applied = new LinkedHashMap<>();
            Map<String, Object> blocked = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : updates.entrySet()) {
                String serviceId = entry.getKey();
                Object val = entry.getValue();
                boolean enabled = val instanceof Boolean ? (Boolean) val : "true".equalsIgnoreCase(String.valueOf(val));
                if ("env".equals(config.getConfigSource(serviceId))) continue;
                if (enabled) {
                    ServiceDefinition def = config.getServiceRegistry().getAllServices().get(serviceId);
                    if (def != null) {
                        LicenseTier required = def.minTier() != null ? def.minTier() : LicenseTier.COMMUNITY;
                        if (!tierProvider.currentTier().includes(required)) {
                            blocked.put(serviceId, Map.of(
                                    "error", "Service '" + serviceId + "' requires " + required.name().toLowerCase() + " tier or higher",
                                    "current_tier", tierProvider.currentTier().name().toLowerCase(),
                                    "required_tier", required.name().toLowerCase(), "upgrade_url", UPGRADE_URL));
                            continue;
                        }
                    }
                }
                config.setServiceEnabled(serviceId, enabled);
                serviceConfigRepository.upsert(serviceId, enabled);
                applied.put(serviceId, Map.of("enabled", enabled));
                ServiceDefinition def = config.getServiceRegistry().getAllServices().get(serviceId);
                if (def != null && "external".equals(def.type())) {
                    String programName = SUPERVISOR_PROGRAM_NAMES.get(serviceId);
                    if (programName != null) {
                        try {
                            if (enabled) supervisorClient.startProcess(programName);
                            else supervisorClient.stopProcess(programName);
                        } catch (Exception se) {
                            logger.warn("Failed to {} supervisor process '{}': {}", enabled ? "start" : "stop", programName, se.getMessage());
                        }
                    }
                }
            }
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper().writeValueAsString(Map.of("applied", applied, "blocked", blocked)));
        } catch (Exception e) {
            logger.error("Error updating service config", e);
            return errorResponse(e);
        }
    }

    @SuppressWarnings("unchecked")
    @Put("/config/iam")
    public HttpResponse updateIamConfig(String body) {
        try {
            Map<String, Object> updates = mapper().readValue(body, Map.class);
            if (updates.containsKey("logWarnings")) {
                boolean enabled = Boolean.TRUE.equals(updates.get("logWarnings"))
                        || "true".equalsIgnoreCase(String.valueOf(updates.get("logWarnings")));
                var iam = IAMEmulator.getRunningInstance();
                if (iam != null) iam.setLogWarnings(enabled);
                return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                        mapper().writeValueAsString(Map.of("logWarnings", enabled)));
            }
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON,
                    mapper().writeValueAsString(Map.of("error", "Unknown IAM config key")));
        } catch (Exception e) {
            logger.error("Error updating IAM config", e);
            return errorResponse(e);
        }
    }
}
