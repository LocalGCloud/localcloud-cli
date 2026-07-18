package com.localcloud.admin;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.localcloud.config.ServiceRegistry;
import com.localcloud.config.ServiceRegistry.ServiceDefinition;

/**
 * Canonical compatibility truth registry loaded from versioned resources.
 */
final class CompatibilityRegistry {

    static final String SCHEMA_VERSION = "2026-06-06";
    private static final String RESOURCE_ROOT = "compatibility";
    private static final Set<String> STATUSES = Set.of(
            "supported", "partial", "unsupported", "unverified", "planned", "prod_only");
    private static final Set<String> EVIDENCE_TYPES = Set.of(
            "unit_test", "integration_test", "terraform_test", "console_build", "manual", "upstream_doc");

    private static final ObjectMapper YAML = new YAMLMapper();

    private final Map<String, CompatibilityService> services;
    private final Map<String, Evidence> evidence;
    private final String generatedAt;

    private CompatibilityRegistry(Map<String, CompatibilityService> services, Map<String, Evidence> evidence) {
        this.services = Collections.unmodifiableMap(new LinkedHashMap<>(services));
        this.evidence = Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
        this.generatedAt = Instant.now().toString();
    }

    static CompatibilityRegistry load(ServiceRegistry serviceRegistry) {
        Map<String, Evidence> evidence = loadEvidence();
        Map<String, CompatibilityService> services = new LinkedHashMap<>();
        for (String serviceId : serviceRegistry.getAllServices().keySet()) {
            String resource = RESOURCE_ROOT + "/services/" + serviceId + ".yaml";
            try (InputStream in = CompatibilityRegistry.class.getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("Missing compatibility registry resource: " + resource);
                }
                CompatibilityService service = YAML.readValue(in, CompatibilityService.class);
                services.put(serviceId, service.withServiceId(serviceId));
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load compatibility registry resource: " + resource, e);
            }
        }
        CompatibilityRegistry registry = new CompatibilityRegistry(services, evidence);
        registry.validate(serviceRegistry);
        return registry;
    }

    static String schemaJson() {
        try (InputStream in = CompatibilityRegistry.class.getClassLoader()
                .getResourceAsStream(RESOURCE_ROOT + "/schema.json")) {
            if (in == null) {
                throw new IllegalStateException("Missing compatibility schema resource");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read compatibility schema", e);
        }
    }

    Map<String, Object> asMap(ServiceRegistry serviceRegistry) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", SCHEMA_VERSION);
        out.put("generated_at", generatedAt);
        List<Map<String, Object>> serviceRows = new ArrayList<>();
        for (Map.Entry<String, CompatibilityService> entry : services.entrySet()) {
            ServiceDefinition def = serviceRegistry.getService(entry.getKey());
            serviceRows.add(entry.getValue().toMap(def));
        }
        out.put("services", serviceRows);
        out.put("evidence", evidenceSummary());
        return out;
    }

    CompatibilityService service(String serviceId) {
        return services.get(serviceId);
    }

    Map<String, Object> evidenceSummary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", SCHEMA_VERSION);
        out.put("generated_at", generatedAt);
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (Evidence item : evidence.values()) {
            rows.add(item.toMap());
            byType.put(item.type(), byType.getOrDefault(item.type(), 0) + 1);
        }
        out.put("by_type", byType);
        out.put("evidence", rows);
        return out;
    }

    List<Map<String, Object>> warnings(String serviceId, String surface) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CompatibilityService service : services.values()) {
            if (serviceId != null && !serviceId.isBlank() && !serviceId.equals(service.serviceId())) {
                continue;
            }
            for (CompatibilityWarning warning : service.warnings()) {
                if (surface != null && !surface.isBlank() && !surface.equals(warning.surface())) {
                    continue;
                }
                Map<String, Object> row = warning.toMap();
                row.put("service_id", service.serviceId());
                rows.add(row);
            }
        }
        return rows;
    }

    void validate(ServiceRegistry serviceRegistry) {
        for (String serviceId : serviceRegistry.getAllServices().keySet()) {
            if (!services.containsKey(serviceId)) {
                throw new IllegalStateException("Missing compatibility registry for service: " + serviceId);
            }
        }
        for (CompatibilityService service : services.values()) {
            requireStatus(service.coverageStatus(), service.serviceId());
            validateEvidenceRefs(service.serviceId(), "service", service.evidence());
            for (CompatibilityOperation op : service.operations()) {
                requireStatus(op.status(), service.serviceId() + ":" + op.id());
                validateEvidenceRefs(service.serviceId(), op.id(), op.evidence());
                if ("supported".equals(op.status()) && op.evidence().isEmpty()) {
                    throw new IllegalStateException("Supported operation lacks evidence: "
                            + service.serviceId() + ":" + op.id());
                }
            }
            validateSurface(service.serviceId(), "terraform_resources", service.terraformResources());
            for (CompatibilityPath path : service.gcloudPaths()) {
                requireStatus(path.status(), service.serviceId() + ":gcloud:" + path.id());
                validateEvidenceRefs(service.serviceId(), path.id(), path.evidence());
            }
            for (CompatibilityPath path : service.consolePaths()) {
                requireStatus(path.status(), service.serviceId() + ":console:" + path.id());
                validateEvidenceRefs(service.serviceId(), path.id(), path.evidence());
            }
            for (CompatibilityWarning warning : service.warnings()) {
                validateEvidenceRefs(service.serviceId(), warning.id(), warning.evidence());
            }
        }
    }

    private void validateSurface(String serviceId, String name, CompatibilitySurface surface) {
        requireStatus(surface.status(), serviceId + ":" + name);
        validateEvidenceRefs(serviceId, name, surface.evidence());
        if ("supported".equals(surface.status()) && surface.evidence().isEmpty()) {
            throw new IllegalStateException("Supported surface lacks evidence: " + serviceId + ":" + name);
        }
    }

    private void validateEvidenceRefs(String serviceId, String objectId, List<String> refs) {
        for (String ref : refs) {
            if (!evidence.containsKey(ref)) {
                throw new IllegalStateException("Unknown evidence reference " + ref + " on " + serviceId + ":" + objectId);
            }
        }
    }

    private static void requireStatus(String status, String scope) {
        if (!STATUSES.contains(status)) {
            throw new IllegalStateException("Invalid compatibility status '" + status + "' on " + scope);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Evidence> loadEvidence() {
        String resource = RESOURCE_ROOT + "/evidence/manual-verifications.yaml";
        try (InputStream in = CompatibilityRegistry.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing compatibility evidence resource: " + resource);
            }
            Map<String, Object> root = YAML.readValue(in, new TypeReference<>() {});
            List<Map<String, Object>> raw = (List<Map<String, Object>>) root.getOrDefault("evidence", List.of());
            Map<String, Evidence> result = new LinkedHashMap<>();
            for (Map<String, Object> item : raw) {
                Evidence evidence = Evidence.fromMap(item);
                if (!EVIDENCE_TYPES.contains(evidence.type())) {
                    throw new IllegalStateException("Invalid evidence type: " + evidence.type());
                }
                result.put(evidence.id(), evidence);
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load compatibility evidence", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CompatibilityService(
            @JsonProperty("service_id") String serviceId,
            @JsonProperty("coverage_status") String coverageStatus,
            List<CompatibilityOperation> operations,
            @JsonProperty("terraform_resources") CompatibilitySurface terraformResources,
            @JsonProperty("gcloud_paths") List<CompatibilityPath> gcloudPaths,
            @JsonProperty("console_paths") List<CompatibilityPath> consolePaths,
            Map<String, Object> state,
            List<String> limitations,
            List<CompatibilityWarning> warnings,
            @JsonProperty("unsupported_operations") List<UnsupportedOperationSpec> unsupportedOperations,
            @JsonProperty("ci_recommendation") String ciRecommendation,
            List<String> evidence
    ) {
        CompatibilityService {
            operations = List.copyOf(operations == null ? List.of() : operations);
            terraformResources = terraformResources == null ? CompatibilitySurface.empty() : terraformResources;
            gcloudPaths = List.copyOf(gcloudPaths == null ? List.of() : gcloudPaths);
            consolePaths = List.copyOf(consolePaths == null ? List.of() : consolePaths);
            state = Collections.unmodifiableMap(new LinkedHashMap<>(state == null ? Map.of() : state));
            limitations = List.copyOf(limitations == null ? List.of() : limitations);
            warnings = List.copyOf(warnings == null ? List.of() : warnings);
            unsupportedOperations = List.copyOf(unsupportedOperations == null ? List.of() : unsupportedOperations);
            evidence = List.copyOf(evidence == null ? List.of() : evidence);
        }


        /**
         * Computes the effective coverage status, upgrading to "supported" when
         * every unsupported operation is classified as prod_only (production-only
         * features that cannot be emulated locally).
         */
        String effectiveCoverageStatus() {
            if (operations.isEmpty()) return coverageStatus;
            boolean hasRealGaps = operations.stream().anyMatch(op ->
                !"supported".equals(op.status()) && !"prod_only".equals(op.status()));
            if (!hasRealGaps) {
                return "supported";
            }
            return coverageStatus;
        }
        CompatibilityService withServiceId(String fallback) {
            if (serviceId != null && !serviceId.isBlank()) {
                return this;
            }
            return new CompatibilityService(fallback, coverageStatus, operations, terraformResources,
                    gcloudPaths, consolePaths, state, limitations, warnings, unsupportedOperations,
                    ciRecommendation, evidence);
        }

        Map<String, Object> toMap(ServiceDefinition def) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("service_id", serviceId);
            if (def != null) {
                out.put("display_name", def.displayName());
                out.put("runtime_type", def.type());
                out.put("protocol", def.protocol());
                out.put("endpoint", def.envValue("localhost"));
                out.put("env_var", def.envVar());
                out.put("gcloud_endpoint_env_var", def.gcloudEnvVar());
                out.put("terraform_endpoint_env_var", def.terraformEnvVar());
            }
            out.put("coverage_status", effectiveCoverageStatus());
            out.put("operations", operations.stream().map(CompatibilityOperation::toMap).toList());
            out.put("terraform_resources", terraformResources.toMap());
            out.put("gcloud_paths", gcloudPaths.stream().map(CompatibilityPath::toMap).toList());
            out.put("console_paths", consolePaths.stream().map(CompatibilityPath::toMap).toList());
            out.put("state", state);
            out.put("limitations", limitations);
            out.put("warnings", warnings.stream().map(CompatibilityWarning::toMap).toList());
            out.put("unsupported_operations", unsupportedOperations.stream().map(UnsupportedOperationSpec::toMap).toList());
            out.put("ci_recommendation", ciRecommendation);
            out.put("evidence", evidence);
            return out;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CompatibilityOperation(
            String id, String operation, String status, Map<String, String> sdk,
            List<String> evidence, String notes
    ) {
        CompatibilityOperation {
            sdk = defaultSdkStatuses(sdk);
            evidence = List.copyOf(evidence == null ? List.of() : evidence);
        }
        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("operation", operation);
            out.put("status", status);
            out.put("sdk_python", sdk.get("python"));
            out.put("sdk_java", sdk.get("java"));
            out.put("sdk_go", sdk.get("go"));
            out.put("sdk_node", sdk.get("nodejs"));
            out.put("sdk", sdk);
            out.put("evidence", evidence);
            out.put("notes", notes);
            return out;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CompatibilitySurface(String status, List<String> resources, List<String> evidence) {
        CompatibilitySurface {
            status = status == null ? "unverified" : status;
            resources = List.copyOf(resources == null ? List.of() : resources);
            evidence = List.copyOf(evidence == null ? List.of() : evidence);
        }
        static CompatibilitySurface empty() {
            return new CompatibilitySurface("unverified", List.of(), List.of());
        }
        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", status);
            out.put("resources", resources);
            out.put("evidence", evidence);
            return out;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CompatibilityPath(String id, String status, List<String> evidence, String notes) {
        CompatibilityPath {
            evidence = List.copyOf(evidence == null ? List.of() : evidence);
        }
        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("status", status);
            out.put("evidence", evidence);
            out.put("notes", notes);
            return out;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CompatibilityWarning(
            String id, String surface, String keyword, String severity, String message,
            String workaround, List<String> evidence
    ) {
        CompatibilityWarning {
            severity = severity == null ? "warning" : severity;
            evidence = List.copyOf(evidence == null ? List.of() : evidence);
        }
        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("surface", surface);
            out.put("keyword", keyword);
            out.put("severity", severity);
            out.put("message", message);
            out.put("workaround", workaround);
            out.put("evidence", evidence);
            return out;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UnsupportedOperationSpec(
            String id, String operation, String surface,
            @JsonProperty("path_pattern") String pathPattern,
            @JsonProperty("http_status") Integer httpStatus,
            String reason, String workaround
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("operation", operation);
            out.put("surface", surface);
            out.put("path_pattern", pathPattern);
            out.put("http_status", httpStatus == null ? 501 : httpStatus);
            out.put("reason", reason);
            out.put("workaround", workaround);
            return out;
        }
    }

    record Evidence(String id, String type, String date, String source, String command, String expected) {
        static Evidence fromMap(Map<String, Object> map) {
            return new Evidence(
                    Objects.toString(map.get("id"), ""),
                    Objects.toString(map.get("type"), ""),
                    Objects.toString(map.get("date"), ""),
                    Objects.toString(map.get("source"), ""),
                    Objects.toString(map.get("command"), ""),
                    Objects.toString(map.get("expected"), ""));
        }
        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("type", type);
            out.put("date", date);
            out.put("source", source);
            out.put("command", command);
            out.put("expected", expected);
            return out;
        }
    }

    private static Map<String, String> defaultSdkStatuses(Map<String, String> input) {
        Map<String, String> sdk = new LinkedHashMap<>();
        sdk.put("python", "unverified");
        sdk.put("java", "unverified");
        sdk.put("go", "unverified");
        sdk.put("nodejs", "unverified");
        if (input != null) {
            sdk.putAll(input);
        }
        return Collections.unmodifiableMap(sdk);
    }
}
