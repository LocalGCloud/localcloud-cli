package com.localcloud.admin;

import static com.localcloud.admin.AdminApiSupport.*;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.localcloud.config.LocalCloudConfig;
import com.localcloud.gateway.FaultInjectionRegistry;
import com.localcloud.gateway.RequestLogger;
import com.localcloud.gateway.RequestLogger.RequestLogEntry;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Diagnostics, coverage, capabilities, and request logging.
 * Extracted from AdminApiService.
 */
public class DiagnosticsService {

    private static final Logger logger = LoggerFactory.getLogger(DiagnosticsService.class);
    private final LocalCloudConfig config;
    private final RequestLogger requestLogger;
    private final FaultInjectionRegistry faultInjectionRegistry;

    public DiagnosticsService(LocalCloudConfig config, RequestLogger requestLogger,
                              FaultInjectionRegistry faultInjectionRegistry) {
        this.config = config;
        this.requestLogger = requestLogger;
        this.faultInjectionRegistry = faultInjectionRegistry;
    }

    @Get("/capabilities")
    public HttpResponse capabilities() {
        try {
            String json = mapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(CapabilityCatalog.capabilities(config));
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, json);
        } catch (Exception e) {
            logger.error("Error generating capability catalog", e);
            return errorResponse(e);
        }
    }

    @Get("/coverage")
    public HttpResponse coverage() {
        try {
            String json = mapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(CapabilityCatalog.coverage(config));
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, json);
        } catch (Exception e) {
            logger.error("Error generating coverage catalog", e);
            return errorResponse(e);
        }
    }

    @Get("/coverage/{service}")
    public HttpResponse serviceCoverage(@Param("service") String serviceId) {
        try {
            Map<String, Object> coverage = CapabilityCatalog.serviceCoverage(config, serviceId);
            if (coverage == null) {
                return HttpResponse.of(HttpStatus.NOT_FOUND, MediaType.JSON,
                        mapper().writeValueAsString(Map.of("error", true, "message", "Unknown service: " + serviceId)));
            }
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper().writerWithDefaultPrettyPrinter().writeValueAsString(coverage));
        } catch (Exception e) {
            logger.error("Error generating coverage for service '{}'", serviceId, e);
            return errorResponse(e);
        }
    }

    @Get("/diagnostics")
    public HttpResponse diagnostics(ServiceRequestContext ctx) {
        try {
            QueryParams params = ctx.queryParams();
            int limit = Math.min(params.getInt("limit", 100), MAX_REQUEST_LIMIT);
            String json = mapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(diagnosticsBundle(limit));
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, json);
        } catch (Exception e) {
            logger.error("Error generating diagnostics bundle", e);
            return errorResponse(e);
        }
    }

    @Get("/diagnostics/archive")
    public HttpResponse diagnosticsArchive(ServiceRequestContext ctx) {
        try {
            QueryParams params = ctx.queryParams();
            int limit = Math.min(params.getInt("limit", 100), MAX_REQUEST_LIMIT);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                writeJsonEntry(zip, "diagnostics.json", diagnosticsBundle(limit));
                writeJsonEntry(zip, "coverage.json", CapabilityCatalog.coverage(config));
                writeJsonEntry(zip, "capabilities.json", CapabilityCatalog.capabilities(config));
                writeJsonEntry(zip, "requests.json", Map.of("requests", requestSnapshot(limit)));
                writeJsonEntry(zip, "services.json", Map.of("services", serviceConfigSnapshot()));
                writeJsonEntry(zip, "faults.json", Map.of("faults", faultInjectionRegistry.list()));
            }
            return HttpResponse.of(HttpStatus.OK, MediaType.parse("application/zip"), bytes.toByteArray());
        } catch (Exception e) {
            logger.error("Error generating diagnostics archive", e);
            return errorResponse(e);
        }
    }

    @Get("/requests")
    public HttpResponse requests(ServiceRequestContext ctx) {
        try {
            QueryParams params = ctx.queryParams();
            String service = params.get("service");
            int limit = Math.min(params.getInt("limit", DEFAULT_REQUEST_LIMIT), MAX_REQUEST_LIMIT);
            String sinceParam = params.get("since");

            List<RequestLogEntry> entries;
            if (sinceParam != null && !sinceParam.isEmpty()) {
                entries = requestLogger.getEntries(service, Instant.parse(sinceParam), limit);
            } else {
                entries = requestLogger.getEntries(service, limit);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            List<Map<String, Object>> requestList = new ArrayList<>();
            for (RequestLogEntry entry : entries) {
                Map<String, Object> req = new LinkedHashMap<>();
                req.put("id", entry.id());
                req.put("timestamp", entry.timestamp().toString());
                req.put("service", entry.service());
                req.put("method", entry.method());
                req.put("path", entry.path());
                req.put("status_code", entry.statusCode());
                req.put("duration_ms", entry.durationMs());
                req.put("request_size", entry.requestSize());
                req.put("response_size", entry.responseSize());
                requestList.add(req);
            }
            response.put("requests", requestList);
            response.put("total", requestLogger.getSize());
            response.put("has_more", entries.size() == limit && requestLogger.getSize() > limit);

            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper().writerWithDefaultPrettyPrinter().writeValueAsString(response));
        } catch (Exception e) {
            logger.error("Error retrieving request log", e);
            return errorResponse(e);
        }
    }

    private List<Map<String, Object>> requestSnapshot(int limit) {
        List<Map<String, Object>> requests = new ArrayList<>();
        for (RequestLogEntry entry : requestLogger.getEntries(null, limit)) {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("id", entry.id());
            req.put("timestamp", entry.timestamp().toString());
            req.put("trace_id", entry.traceId());
            req.put("service", entry.service());
            req.put("method", entry.method());
            req.put("path", entry.path());
            req.put("status_code", entry.statusCode());
            req.put("duration_ms", entry.durationMs());
            req.put("request_size", entry.requestSize());
            req.put("response_size", entry.responseSize());
            req.put("request_body", entry.requestBody());
            req.put("response_body", entry.responseBody());
            requests.add(req);
        }
        return requests;
    }

    private Map<String, Object> diagnosticsBundle(int limit) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("generated_at", Instant.now().toString());
        response.put("project_id", config.getProjectId());
        Map<String, Object> configSnapshot = new LinkedHashMap<>();
        configSnapshot.put("gateway_port", config.getGatewayPort());
        configSnapshot.put("data_dir", config.getDataDir().toString());
        configSnapshot.put("persistence", config.isPersistenceEnabled());
        configSnapshot.put("iam_mode", config.getIamMode());
        response.put("config", configSnapshot);
        Map<String, Object> requestCapture = new LinkedHashMap<>();
        requestCapture.put("body_capture_enabled", requestLogger.isCaptureBodies());
        requestCapture.put("max_body_size", requestLogger.getMaxBodySize());
        requestCapture.put("stored_entries", requestLogger.getSize());
        requestCapture.put("capacity", requestLogger.getCapacity());
        response.put("request_capture", requestCapture);
        response.put("coverage_summary", CapabilityCatalog.coverage(config).get("summary"));
        response.put("capabilities", CapabilityCatalog.capabilities(config).get("phases"));
        response.put("services", serviceConfigSnapshot());
        response.put("active_faults", faultInjectionRegistry.list());
        response.put("recent_requests", requestSnapshot(limit));
        return response;
    }

    private void writeJsonEntry(ZipOutputStream zip, String name, Object value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        String json = mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value);
        zip.write(json.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private List<Map<String, Object>> serviceConfigSnapshot() {
        List<Map<String, Object>> services = new ArrayList<>();
        for (Map.Entry<String, com.localcloud.config.ServiceRegistry.ServiceDefinition> entry
                : config.getServiceRegistry().getAllServices().entrySet()) {
            String serviceId = entry.getKey();
            var def = entry.getValue();
            Map<String, Object> service = new LinkedHashMap<>();
            service.put("id", serviceId);
            service.put("display_name", def.displayName());
            service.put("enabled", config.isServiceEnabled(serviceId));
            service.put("enabled_source", config.getConfigSource(serviceId));
            service.put("protocol", def.protocol());
            service.put("type", def.type());
            service.put("endpoint", def.envValue("localhost"));
            service.put("env_var", def.envVar());
            service.put("terraform_env_var", def.terraformEnvVar());
            services.add(service);
        }
        return services;
    }
}
