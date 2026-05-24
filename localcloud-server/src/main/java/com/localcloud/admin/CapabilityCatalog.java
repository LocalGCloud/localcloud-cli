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
 * <p>This catalog is intentionally conservative: a service can be available
 * locally while still marked partial until SDK, Terraform, state, and CI paths
 * are verified against the same backing state.</p>
 */
final class CapabilityCatalog {

    private static final List<String> SDKS = List.of("python", "java", "go", "nodejs");

    private static final Map<String, List<String>> PROFILES = profileMap();
    private static final Map<String, List<String>> TERRAFORM_SUPPORT = terraformMap();
    private static final Map<String, String> TERRAFORM_STATUS = terraformStatusMap();

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
                        List.of("service registry", "env export", "service enablement", "coverage endpoint", "readiness endpoint"),
                        List.of("full lifecycle CLI", "SDK smoke suite automation", "unsupported-operation enforcement everywhere")),
                phase("phase-1-deterministic-state", "partial",
                        List.of("seed", "reset", "service-scoped reset", "state export", "state import", "named snapshots"),
                        List.of("single state-source audit per service", "full-service export coverage")),
                phase("phase-2-ci-viability", "partial",
                        List.of("Terraform endpoint env export", "core Terraform compatibility matrix", "readiness JSON",
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
        List<Map<String, Object>> services = new ArrayList<>();
        Map<String, Integer> byStatus = new LinkedHashMap<>();

        for (Map.Entry<String, ServiceDefinition> entry : registry.getAllServices().entrySet()) {
            Map<String, Object> service = serviceCoverage(config, entry.getKey());
            services.add(service);
            String status = String.valueOf(service.get("coverage_status"));
            byStatus.put(status, byStatus.getOrDefault(status, 0) + 1);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_services", services.size());
        summary.put("by_coverage_status", byStatus);
        summary.put("fully_ci_ready", 0);
        summary.put("contract", "Services remain partial until SDK, provisioning, state, reset, export, and diagnostics paths share one verified state source.");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("generated_at", Instant.now().toString());
        response.put("project_id", config.getProjectId());
        response.put("summary", summary);
        response.put("services", services);
        return response;
    }

    static Map<String, Object> serviceCoverage(LocalCloudConfig config, String serviceId) {
        ServiceDefinition def = config.getServiceRegistry().getService(serviceId);
        if (def == null) {
            return null;
        }

        Map<String, Object> service = new LinkedHashMap<>();
        service.put("service_id", serviceId);
        service.put("display_name", def.displayName());
        service.put("enabled", config.isServiceEnabled(serviceId));
        service.put("coverage_status", coverageStatus(serviceId));
        service.put("runtime_type", def.type());
        service.put("protocol", def.protocol());
        service.put("endpoint", def.envValue("localhost"));
        service.put("env_var", def.envVar());
        service.put("gcloud_endpoint_env_var", def.gcloudEnvVar());
        service.put("terraform_endpoint_env_var", def.terraformEnvVar());
        service.put("sdk_smoke_status", sdkSmokeStatus());
        service.put("operations", operations(serviceId));
        service.put("terraform_resources", terraformResources(serviceId));
        service.put("state", stateContract(serviceId, def));
        service.put("limitations", limitations(serviceId));
        service.put("ci_recommendation", ciRecommendation(serviceId));
        return service;
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

    private static String coverageStatus(String serviceId) {
        return switch (serviceId) {
            case "gcs", "pubsub", "bigquery", "secretmanager", "cloudtasks",
                    "logging", "monitoring", "memorystore", "workflows" -> "partial";
            case "firestore", "bigtable", "spanner", "compute", "cloudrun",
                    "gke", "vertexai", "kms", "cloudsql" -> "early-partial";
            default -> "unverified";
        };
    }

    private static Map<String, String> sdkSmokeStatus() {
        Map<String, String> status = new LinkedHashMap<>();
        for (String sdk : SDKS) {
            status.put(sdk, "unverified");
        }
        return status;
    }

    private static List<Map<String, Object>> operations(String serviceId) {
        return switch (serviceId) {
            case "gcs" -> List.of(
                    op("buckets.create/list/delete", "supported", "Core bucket lifecycle through the local HTTP emulator."),
                    op("objects.upload/download/list/delete", "supported", "Core object lifecycle through the local HTTP emulator."),
                    op("iam, lifecycle policies, notifications", "unsupported", "Advanced control-plane behavior is not emulated."));
            case "pubsub" -> List.of(
                    op("topics.create/list/delete", "supported", "Topic lifecycle through the Pub/Sub emulator."),
                    op("subscriptions.create/list/delete", "supported", "Subscription lifecycle through the Pub/Sub emulator."),
                    op("publish/pull/ack", "supported", "Core message workflow is available."),
                    op("schemas, snapshots, seek, dead-letter policy", "partial", "Needs explicit parity and CI verification."));
            case "firestore" -> List.of(
                    op("documents.create/read/update/delete", "partial", "External emulator is available; seed and browser parity needs hardening."),
                    op("queries/index behavior", "partial", "Complex query/index parity is unverified."));
            case "bigquery" -> List.of(
                    op("datasets.create/list/delete", "supported", "Local BigQuery emulator supports core dataset lifecycle."),
                    op("tables.create/list/delete", "supported", "Table schema lifecycle is available."),
                    op("insert rows and query SQL", "partial", "DuckDB-backed SQL differs from BigQuery in edge cases."));
            case "spanner" -> List.of(
                    op("instances/databases", "supported", "External emulator supports core admin lifecycle."),
                    op("DDL and DML", "partial", "Persistence and metadata behavior need compatibility hardening."));
            case "bigtable" -> List.of(
                    op("instances/tables/families", "partial", "External emulator and local browse/query paths exist."),
                    op("row mutations and reads", "partial", "Persistence and SDK/browser state alignment need verification."));
            case "secretmanager" -> List.of(
                    op("secrets.create/list/get/delete", "partial", "Facade-backed lifecycle exists for core workflows."),
                    op("versions.add/access/list", "partial", "Local version storage exists; REST/Terraform parity remains incomplete."));
            case "cloudtasks" -> List.of(
                    op("queues.create/list/delete", "partial", "Facade-backed queue lifecycle exists."),
                    op("tasks.create/list/delete", "partial", "Dispatch and scheduling parity remain limited."));
            case "logging" -> List.of(
                    op("write log entries", "partial", "Local log ingestion exists."),
                    op("list/filter log entries", "partial", "Filtering and structured exploration need expansion."));
            case "monitoring" -> List.of(
                    op("create time series", "partial", "Local metric storage exists."),
                    op("list/query metrics", "partial", "Query parity is limited."));
            case "compute" -> List.of(
                    op("instances", "partial", "Local facade exists for developer-visible inventory."),
                    op("networking, disks, instance templates", "unsupported", "Infrastructure behavior is not yet emulated."));
            case "cloudrun" -> List.of(
                    op("services/revisions", "partial", "Local facade exists for service metadata."),
                    op("container execution and routing", "unsupported", "Runtime invocation parity remains future work."));
            case "gke" -> List.of(
                    op("clusters", "partial", "Local facade exists for cluster metadata."),
                    op("Kubernetes API/runtime", "unsupported", "k3d integration remains a roadmap item."));
            case "memorystore" -> List.of(
                    op("RESP commands", "partial", "Redis/Valkey-compatible data path is available."),
                    op("Cloud Redis admin API", "unsupported", "Admin resource lifecycle is not yet Google-shaped."));
            case "workflows" -> List.of(
                    op("workflow deploy/list/get/delete", "partial", "Local facade and seed paths exist."),
                    op("executions", "partial", "Execution engine exists but connector/runtime parity needs expansion."));
            case "vertexai" -> List.of(
                    op("generateContent", "partial", "Local facade exists for selected GenAI flows."),
                    op("models, tuning, batch prediction", "unsupported", "Broader Vertex AI is out of current scope."));
            case "kms" -> List.of(
                    op("key rings/crypto keys", "partial", "Local REST facade exists."),
                    op("encrypt/decrypt/sign/verify", "partial", "Crypto operation parity needs SDK and IAM tests."));
            case "cloudsql" -> List.of(
                    op("instances/databases/users", "partial", "Local REST facade exists."),
                    op("managed SQL engine lifecycle", "unsupported", "Postgres/MySQL runtime wiring is roadmap work."));
            default -> List.of(op("inventory", "unverified", "No operation inventory has been registered yet."));
        };
    }

    private static Map<String, Object> op(String operation, String status, String notes) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("operation", operation);
        row.put("status", status);
        row.put("sdk_python", "unverified");
        row.put("sdk_java", "unverified");
        row.put("sdk_go", "unverified");
        row.put("sdk_node", "unverified");
        row.put("notes", notes);
        return row;
    }

    private static Map<String, Object> terraformResources(String serviceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", TERRAFORM_STATUS.getOrDefault(serviceId, "unverified"));
        result.put("resources", TERRAFORM_SUPPORT.getOrDefault(serviceId, List.of()));
        return result;
    }

    private static Map<String, Object> stateContract(String serviceId, ServiceDefinition def) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("source", stateSource(serviceId, def));
        state.put("seed", seedSupport(serviceId));
        state.put("reset", resetSupport(serviceId));
        state.put("export", exportSupport(serviceId));
        state.put("snapshot", "planned");
        return state;
    }

    private static String stateSource(String serviceId, ServiceDefinition def) {
        return switch (serviceId) {
            case "gcs" -> "filesystem-backed external emulator";
            case "pubsub", "firestore", "bigquery", "spanner", "bigtable", "memorystore" -> "external emulator";
            case "secretmanager", "cloudtasks", "logging", "monitoring", "compute", "cloudrun", "gke",
                    "workflows", "vertexai", "kms", "cloudsql" -> "PostgreSQL-backed facade";
            default -> def.type();
        };
    }

    private static String seedSupport(String serviceId) {
        return switch (serviceId) {
            case "gcs", "pubsub", "bigquery", "secretmanager", "cloudtasks", "spanner",
                    "bigtable", "memorystore", "workflows" -> "available";
            case "firestore" -> "partial";
            default -> "planned";
        };
    }

    private static String resetSupport(String serviceId) {
        return switch (serviceId) {
            case "gcs", "pubsub", "firestore", "bigquery", "spanner", "secretmanager",
                    "cloudtasks", "logging", "monitoring", "memorystore", "bigtable",
                    "compute", "cloudrun", "gke", "workflows" -> "available";
            default -> "planned";
        };
    }

    private static String exportSupport(String serviceId) {
        return switch (serviceId) {
            case "gcs", "pubsub", "bigquery", "secretmanager", "spanner",
                    "memorystore", "cloudtasks" -> "available";
            default -> "planned";
        };
    }

    private static List<String> limitations(String serviceId) {
        return switch (serviceId) {
            case "gcs" -> List.of("No cloud IAM enforcement", "No project-level bucket isolation in the upstream emulator without LocalCloud metadata.");
            case "pubsub" -> List.of("Advanced delivery controls need parity tests.", "Schema and snapshot workflows are not complete.");
            case "firestore" -> List.of("Seed and browser parity is not fully hardened.", "Index/query behavior is unverified.");
            case "bigquery" -> List.of("SQL is DuckDB-backed, so BigQuery dialect parity is partial.", "Export currently focuses on metadata/schema.");
            case "spanner" -> List.of("Persistence behavior needs restart verification.", "REST/gRPC metadata parity remains partial.");
            case "bigtable" -> List.of("Persistence and browse/mutate/export alignment need hardening.");
            case "secretmanager", "cloudtasks" -> List.of("REST/Terraform compatibility is partial.", "IAM behavior is permissive unless strict mode is expanded.");
            case "logging", "monitoring" -> List.of("Local ingestion exists; query/filter parity is limited.");
            case "memorystore" -> List.of("Data plane is Redis/Valkey-compatible; Google Cloud Redis admin API is not complete.");
            case "compute", "cloudrun", "gke", "vertexai", "kms", "cloudsql" -> List.of("Facade coverage is early and should be treated as developer workflow scaffolding.");
            default -> List.of("Coverage has not been verified.");
        };
    }

    private static String ciRecommendation(String serviceId) {
        String status = coverageStatus(serviceId);
        if ("partial".equals(status)) {
            return "Use for local and CI smoke tests that stay within listed supported operations; add a coverage assertion for this service.";
        }
        if ("early-partial".equals(status)) {
            return "Use only for targeted local workflows until SDK and provisioning tests are added.";
        }
        return "Do not gate CI on this service until coverage is inventoried.";
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

    private static Map<String, List<String>> terraformMap() {
        Map<String, List<String>> resources = new LinkedHashMap<>();
        resources.put("gcs", List.of("google_storage_bucket", "google_storage_bucket_object"));
        resources.put("pubsub", List.of("google_pubsub_topic", "google_pubsub_subscription"));
        resources.put("bigquery", List.of("google_bigquery_dataset", "google_bigquery_table"));
        resources.put("spanner", List.of("google_spanner_instance", "google_spanner_database"));
        resources.put("secretmanager", List.of("google_secret_manager_secret", "google_secret_manager_secret_version"));
        resources.put("cloudtasks", List.of("google_cloud_tasks_queue"));
        resources.put("compute", List.of("google_compute_instance"));
        resources.put("cloudrun", List.of("google_cloud_run_v2_service"));
        resources.put("gke", List.of("google_container_cluster"));
        resources.put("memorystore", List.of("google_redis_instance"));
        resources.put("kms", List.of("google_kms_key_ring", "google_kms_crypto_key"));
        resources.put("cloudsql", List.of("google_sql_database_instance", "google_sql_database", "google_sql_user"));
        resources.put("vertexai", List.of("google_vertex_ai_*"));
        resources.put("workflows", List.of("google_workflows_workflow"));
        return resources;
    }

    private static Map<String, String> terraformStatusMap() {
        Map<String, String> status = new LinkedHashMap<>();
        for (String service : List.of("gcs", "pubsub", "bigquery", "spanner")) {
            status.put(service, "supported");
        }
        for (String service : List.of("secretmanager", "cloudtasks")) {
            status.put(service, "partial");
        }
        for (String service : List.of("compute", "cloudrun", "gke", "memorystore", "kms", "cloudsql", "vertexai", "workflows")) {
            status.put(service, "planned");
        }
        return status;
    }
}
