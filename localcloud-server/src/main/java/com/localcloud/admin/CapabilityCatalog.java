package com.localcloud.admin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.localcloud.config.LocalCloudConfig;
import com.localcloud.config.ServiceRegistry;
import com.localcloud.config.ServiceRegistry.ServiceDefinition;

/**
 * Builds machine-readable LocalCloud capability and compatibility metadata.
 *
 * <p>Compatibility facts come from {@link CompatibilityRegistry}; this class
 * preserves the existing coverage/capabilities API response shape.</p>
 */
final class CapabilityCatalog {

    private static final Map<String, List<String>> PROFILES = profileMap();

    private CapabilityCatalog() {}

    static Map<String, Object> profiles(LocalCloudConfig config) {
        ServiceRegistry registry = config.getServiceRegistry();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("generated_at", Instant.now().toString());

        List<Map<String, Object>> profiles = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : PROFILES.entrySet()) {
            List<String> services = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (String serviceId : entry.getValue()) {
                if (registry.getService(serviceId) == null) {
                    missing.add(serviceId);
                } else {
                    services.add(serviceId);
                }
            }

            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("id", entry.getKey());
            profile.put("services", services);
            profile.put("missing_services", missing);
            profiles.add(profile);
        }

        response.put("profiles", profiles);
        response.put("custom_profile_contract", "Use explicit service ids from services.yaml.");
        return response;
    }

    static Map<String, Object> capabilities(LocalCloudConfig config) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("generated_at", Instant.now().toString());
        response.put("project_id", config.getProjectId());
        response.put("roadmap_scope", "developer and CI/CD usability");
        response.put("phases", List.of(
                phase("phase-0-truth-lifecycle", "partial",
                        List.of("service registry", "env export", "service enablement", "coverage endpoint", "readiness endpoint", "compatibility registry"),
                        List.of("full lifecycle CLI", "full SDK smoke suite automation")),
                phase("phase-1-deterministic-state", "partial",
                        List.of("seed", "reset", "service-scoped reset", "state export", "state import", "named snapshots"),
                        List.of("single state-source audit per service", "full-service export coverage")),
                phase("phase-2-ci-viability", "partial",
                        List.of("Terraform endpoint env export", "Terraform compatibility matrix", "readiness JSON",
                                "CI templates", "coverage assertion helper", "Testcontainers helper template"),
                        List.of("apply/destroy suites for expanded resources")),
                phase("phase-3-runtime-parity", "partial",
                        List.of("project id config", "IAM mode config", "metadata server",
                                "strict IAM denial explanations", "local endpoint routing visibility"),
                        List.of("local service accounts", "region/zone isolation audit")),
                phase("phase-4-diagnostics-observability", "partial",
                        List.of("request log", "usage metrics", "diagnostics endpoint", "diagnostics archive", "fault injection"),
                        List.of("trace correlation", "event replay")),
                phase("phase-5-service-breadth", "partial",
                        List.of("registry entries for current and planned GCP services", "facade/external emulator split"),
                        List.of("Cloud SQL MVP hardening", "KMS/Vertex AI parity suites", "eventing service roadmap")),
                phase("phase-6-ecosystem", "planned",
                        List.of("profile metadata"),
                        List.of("MCP tools", "IDE integration", "Docker Desktop integration", "scenario packs"))));
        response.put("profiles", profiles(config).get("profiles"));
        return response;
    }

    static Map<String, Object> coverage(LocalCloudConfig config) {
        ServiceRegistry registry = config.getServiceRegistry();
        CompatibilityRegistry compatibility = CompatibilityRegistry.load(registry);
        List<Map<String, Object>> services = new ArrayList<>();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        int fullyCiReady = 0;

        for (Map.Entry<String, ServiceDefinition> entry : registry.getAllServices().entrySet()) {
            Map<String, Object> service = serviceCoverage(config, entry.getKey());
            services.add(service);
            String status = String.valueOf(service.get("coverage_status"));
            byStatus.put(status, byStatus.getOrDefault(status, 0) + 1);
            if ("supported".equals(status)) {
                fullyCiReady++;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_services", services.size());
        summary.put("by_coverage_status", byStatus);
        summary.put("fully_ci_ready", fullyCiReady);
        summary.put("schema_version", CompatibilityRegistry.SCHEMA_VERSION);
        summary.put("contract", "Services remain partial until SDK, provisioning, state, reset, export, and diagnostics paths share one verified state source.");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("generated_at", Instant.now().toString());
        response.put("project_id", config.getProjectId());
        response.put("summary", summary);
        response.put("services", services);
        response.put("compatibility", compatibility.asMap(registry));
        return response;
    }

    static Map<String, Object> serviceCoverage(LocalCloudConfig config, String serviceId) {
        ServiceDefinition def = config.getServiceRegistry().getService(serviceId);
        if (def == null) {
            return null;
        }
        CompatibilityRegistry.CompatibilityService service =
                CompatibilityRegistry.load(config.getServiceRegistry()).service(serviceId);
        if (service == null) {
            return null;
        }
        Map<String, Object> out = service.toMap(def);
        out.put("enabled", config.isServiceEnabled(serviceId));
        return out;
    }

    static Map<String, Object> compatibility(LocalCloudConfig config) {
        return CompatibilityRegistry.load(config.getServiceRegistry()).asMap(config.getServiceRegistry());
    }

    static Map<String, Object> evidence(LocalCloudConfig config) {
        return CompatibilityRegistry.load(config.getServiceRegistry()).evidenceSummary();
    }

    static List<Map<String, Object>> warnings(LocalCloudConfig config, String serviceId, String surface) {
        return CompatibilityRegistry.load(config.getServiceRegistry()).warnings(serviceId, surface);
    }

    static String schemaJson() {
        return CompatibilityRegistry.schemaJson();
    }

    private static Map<String, Object> phase(String id, String status,
                                             List<String> available,
                                             List<String> remaining) {
        Map<String, Object> phase = new LinkedHashMap<>();
        phase.put("id", id);
        phase.put("status", status);
        phase.put("available_now", available);
        phase.put("remaining_gaps", remaining);
        return phase;
    }

    private static Map<String, List<String>> profileMap() {
        Map<String, List<String>> profiles = new LinkedHashMap<>();
        profiles.put("core", List.of("gcs", "pubsub", "firestore", "bigquery", "secretmanager", "cloudtasks", "logging", "monitoring", "memorystore", "workflows"));
        profiles.put("data", List.of("gcs", "bigquery", "spanner", "bigtable", "firestore"));
        profiles.put("events", List.of("pubsub", "cloudtasks", "workflows", "logging", "monitoring"));
        profiles.put("serverless", List.of("cloudrun", "cloudtasks", "pubsub", "workflows", "logging", "monitoring"));
        profiles.put("infra", List.of("compute", "gke", "cloudrun", "cloudsql", "kms"));
        profiles.put("ai", List.of("vertexai", "gcs", "bigquery", "kms"));
        return profiles;
    }
}
