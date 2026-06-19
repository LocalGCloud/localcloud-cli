package com.localcloud.emulators.iam;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registry of known GCP predefined roles and their permissions.
 * Used for role name validation and permission resolution.
 */
public class IAMRoleRegistry {

    /** Rich metadata for a role: id, title, category, description, permissions, stage. */
    public record RoleMeta(String id, String title, String category, String description,
                           List<String> permissions, String stage) {}

    private static final Map<String, Set<String>> ROLE_PERMISSIONS = new HashMap<>();
    private static final Map<String, RoleMeta> ROLE_META = new LinkedHashMap<>();

    static {
        // Basic (primitive) roles
        addRole("roles/viewer",
                "resourcemanager.projects.get",
                "resourcemanager.projects.list",
                "storage.objects.get",
                "storage.objects.list",
                "storage.buckets.get",
                "storage.buckets.list",
                "pubsub.topics.get",
                "pubsub.topics.list",
                "pubsub.subscriptions.get",
                "pubsub.subscriptions.list",
                "monitoring.timeSeries.list",
                "logging.logEntries.list",
                "secretmanager.secrets.get",
                "secretmanager.secrets.list",
                "secretmanager.versions.get",
                "secretmanager.versions.list",
                "cloudfunctions.functions.get",
                "cloudfunctions.functions.list",
                "cloudtasks.queues.get",
                "cloudtasks.queues.list",
                "cloudscheduler.jobs.get",
                "cloudscheduler.jobs.list",
                "compute.instances.get",
                "compute.instances.list",
                "run.services.get",
                "run.services.list",
                "iam.roles.get",
                "iam.roles.list");

        addRole("roles/editor",
                "resourcemanager.projects.get",
                "resourcemanager.projects.list",
                "storage.objects.*",
                "storage.buckets.*",
                "pubsub.topics.*",
                "pubsub.subscriptions.*",
                "monitoring.timeSeries.*",
                "logging.logEntries.*",
                "secretmanager.secrets.*",
                "secretmanager.versions.*",
                "cloudfunctions.functions.*",
                "cloudtasks.queues.*",
                "cloudscheduler.jobs.*",
                "compute.instances.*",
                "run.services.*");

        addRole("roles/owner",
                "*"); // wildcard - all permissions

        // Storage roles
        addRole("roles/storage.admin",
                "storage.buckets.create", "storage.buckets.delete",
                "storage.buckets.get", "storage.buckets.list", "storage.buckets.update",
                "storage.objects.create", "storage.objects.delete",
                "storage.objects.get", "storage.objects.list", "storage.objects.update");

        addRole("roles/storage.objectViewer",
                "storage.objects.get", "storage.objects.list");

        addRole("roles/storage.objectCreator",
                "storage.objects.create", "storage.objects.get", "storage.objects.list");

        // Pub/Sub roles
        addRole("roles/pubsub.publisher", "pubsub.topics.publish");
        addRole("roles/pubsub.subscriber", "pubsub.subscriptions.consume");
        addRole("roles/pubsub.viewer",
                "pubsub.topics.get", "pubsub.topics.list",
                "pubsub.subscriptions.get", "pubsub.subscriptions.list");
        addRole("roles/pubsub.editor",
                "pubsub.topics.*", "pubsub.subscriptions.*");

        // Secret Manager roles
        addRole("roles/secretmanager.secretAccessor",
                "secretmanager.secrets.get", "secretmanager.versions.access");
        addRole("roles/secretmanager.secretVersionAdder",
                "secretmanager.versions.add");
        addRole("roles/secretmanager.admin",
                "secretmanager.secrets.*", "secretmanager.versions.*");

        // Cloud Functions roles
        addRole("roles/cloudfunctions.developer",
                "cloudfunctions.functions.create", "cloudfunctions.functions.get",
                "cloudfunctions.functions.list", "cloudfunctions.functions.update",
                "cloudfunctions.functions.delete", "cloudfunctions.functions.call");
        addRole("roles/cloudfunctions.viewer",
                "cloudfunctions.functions.get", "cloudfunctions.functions.list");

        // Cloud Tasks roles
        addRole("roles/cloudtasks.enqueuer", "cloudtasks.tasks.create");
        addRole("roles/cloudtasks.viewer",
                "cloudtasks.queues.get", "cloudtasks.queues.list");
        addRole("roles/cloudtasks.admin",
                "cloudtasks.queues.*", "cloudtasks.tasks.*");

        // Cloud Scheduler roles
        addRole("roles/cloudscheduler.admin",
                "cloudscheduler.jobs.*", "cloudscheduler.locations.*");
        addRole("roles/cloudscheduler.viewer",
                "cloudscheduler.jobs.get", "cloudscheduler.jobs.list");

        // Compute Engine roles
        addRole("roles/compute.admin", "compute.*");
        addRole("roles/compute.viewer",
                "compute.instances.get", "compute.instances.list",
                "compute.disks.get", "compute.disks.list");

        // Cloud Run roles
        addRole("roles/run.admin", "run.services.*", "run.revisions.*");
        addRole("roles/run.viewer", "run.services.get", "run.services.list");
        addRole("roles/run.invoker", "run.services.invoke");

        // Monitoring roles
        addRole("roles/monitoring.viewer", "monitoring.timeSeries.list");
        addRole("roles/monitoring.editor", "monitoring.*");
        addRole("roles/monitoring.admin", "monitoring.*");

        // Logging roles
        addRole("roles/logging.viewer", "logging.logEntries.list");
        addRole("roles/logging.admin", "logging.*");

        // IAM roles
        addRole("roles/iam.securityAdmin", "iam.roles.*", "iam.serviceAccounts.*");
        addRole("roles/iam.roleViewer", "iam.roles.get", "iam.roles.list");
    }

    private static void addRole(String role, String... permissions) {
        Set<String> permSet = new HashSet<>();
        Collections.addAll(permSet, permissions);
        ROLE_PERMISSIONS.put(role, permSet);
    }

    private static void addRoleMeta(String role, String title, String category,
                                    String description, String... permissions) {
        addRole(role, permissions);
        String stage = role.contains("alpha") ? "ALPHA" : role.contains("beta") ? "BETA" : "GA";
        ROLE_META.put(role, new RoleMeta(role, title, category, description,
                List.of(permissions), stage));
    }

    static {
        // Build role metadata after the role-permission mappings are set up
        addRoleMeta("roles/viewer", "Viewer", "Basic",
                "Read-only access to browse and list resources across all services. " +
                "Cannot create, modify, or delete any resources.",
                "resourcemanager.projects.get", "resourcemanager.projects.list");

        addRoleMeta("roles/editor", "Editor", "Basic",
                "Read-write access to most services. Can create, modify, and delete " +
                "resources but cannot manage IAM policies or billing.",
                "resourcemanager.projects.get", "resourcemanager.projects.list");

        addRoleMeta("roles/owner", "Owner", "Basic",
                "Full administrative access to all resources. Can manage IAM policies, " +
                "billing, and all service resources. Equivalent to project owner.");

        // Storage
        addRoleMeta("roles/storage.admin", "Storage Admin", "Storage",
                "Full control over Cloud Storage buckets and objects including creating, " +
                "deleting, and updating buckets and objects.",
                "storage.buckets.*", "storage.objects.*");
        addRoleMeta("roles/storage.objectViewer", "Storage Object Viewer", "Storage",
                "Read-only access to Cloud Storage objects. Can list and download objects " +
                "but cannot create, modify, or delete them.",
                "storage.objects.get", "storage.objects.list");
        addRoleMeta("roles/storage.objectCreator", "Storage Object Creator", "Storage",
                "Can create new objects in Cloud Storage buckets. Also has read access " +
                "to view existing objects.",
                "storage.objects.create", "storage.objects.get", "storage.objects.list");

        // Pub/Sub
        addRoleMeta("roles/pubsub.publisher", "Pub/Sub Publisher", "Pub/Sub",
                "Can publish messages to Pub/Sub topics. Cannot create or manage topics " +
                "or subscriptions.",
                "pubsub.topics.publish");
        addRoleMeta("roles/pubsub.subscriber", "Pub/Sub Subscriber", "Pub/Sub",
                "Can consume messages from Pub/Sub subscriptions. Cannot create or manage " +
                "topics or subscriptions.",
                "pubsub.subscriptions.consume");
        addRoleMeta("roles/pubsub.viewer", "Pub/Sub Viewer", "Pub/Sub",
                "Read-only access to Pub/Sub topics and subscriptions. Can list and view " +
                "topics and subscriptions but cannot publish or consume messages.",
                "pubsub.topics.get", "pubsub.topics.list");
        addRoleMeta("roles/pubsub.editor", "Pub/Sub Editor", "Pub/Sub",
                "Full control over Pub/Sub topics and subscriptions including publishing " +
                "and consuming messages.",
                "pubsub.topics.*", "pubsub.subscriptions.*");

        // Secret Manager
        addRoleMeta("roles/secretmanager.secretAccessor", "Secret Manager Secret Accessor", "Secret Manager",
                "Can access and view secret payloads. Cannot create, update, or delete secrets.",
                "secretmanager.secrets.get", "secretmanager.versions.access");
        addRoleMeta("roles/secretmanager.secretVersionAdder", "Secret Manager Version Adder", "Secret Manager",
                "Can add new versions to existing secrets. Cannot create new secrets or access " +
                "secret payloads.",
                "secretmanager.versions.add");
        addRoleMeta("roles/secretmanager.admin", "Secret Manager Admin", "Secret Manager",
                "Full control over Secret Manager secrets and versions. Can create, update, " +
                "delete, and access secrets and all versions.",
                "secretmanager.secrets.*", "secretmanager.versions.*");

        // Cloud Functions
        addRoleMeta("roles/cloudfunctions.developer", "Cloud Functions Developer", "Cloud Functions",
                "Can create, update, delete, and invoke Cloud Functions. Full development access.",
                "cloudfunctions.functions.create", "cloudfunctions.functions.get",
                "cloudfunctions.functions.list", "cloudfunctions.functions.update",
                "cloudfunctions.functions.delete", "cloudfunctions.functions.call");
        addRoleMeta("roles/cloudfunctions.viewer", "Cloud Functions Viewer", "Cloud Functions",
                "Read-only access to Cloud Functions. Can list and view function configurations " +
                "but cannot create or invoke functions.",
                "cloudfunctions.functions.get", "cloudfunctions.functions.list");

        // Cloud Tasks
        addRoleMeta("roles/cloudtasks.enqueuer", "Cloud Tasks Enqueuer", "Cloud Tasks",
                "Can create (enqueue) tasks in Cloud Tasks queues. Cannot manage queues.",
                "cloudtasks.tasks.create");
        addRoleMeta("roles/cloudtasks.viewer", "Cloud Tasks Viewer", "Cloud Tasks",
                "Read-only access to Cloud Tasks queues. Can list and view queues.",
                "cloudtasks.queues.get", "cloudtasks.queues.list");
        addRoleMeta("roles/cloudtasks.admin", "Cloud Tasks Admin", "Cloud Tasks",
                "Full control over Cloud Tasks queues and tasks. Can create, manage, and " +
                "delete queues and tasks.",
                "cloudtasks.queues.*", "cloudtasks.tasks.*");

        // Cloud Scheduler
        addRoleMeta("roles/cloudscheduler.admin", "Cloud Scheduler Admin", "Cloud Scheduler",
                "Full control over Cloud Scheduler jobs and locations. Can create, pause, " +
                "resume, and delete cron jobs.",
                "cloudscheduler.jobs.*", "cloudscheduler.locations.*");
        addRoleMeta("roles/cloudscheduler.viewer", "Cloud Scheduler Viewer", "Cloud Scheduler",
                "Read-only access to Cloud Scheduler jobs. Can list and view job configurations.",
                "cloudscheduler.jobs.get", "cloudscheduler.jobs.list");

        // Compute Engine
        addRoleMeta("roles/compute.admin", "Compute Admin", "Compute",
                "Full control over Compute Engine instances, disks, and all compute resources.",
                "compute.*");
        addRoleMeta("roles/compute.viewer", "Compute Viewer", "Compute",
                "Read-only access to Compute Engine instances and disks. Can list and view but " +
                "cannot create or modify VMs.",
                "compute.instances.get", "compute.instances.list");

        // Cloud Run
        addRoleMeta("roles/run.admin", "Cloud Run Admin", "Cloud Run",
                "Full control over Cloud Run services and revisions. Can deploy, update, and " +
                "delete services.",
                "run.services.*", "run.revisions.*");
        addRoleMeta("roles/run.viewer", "Cloud Run Viewer", "Cloud Run",
                "Read-only access to Cloud Run services. Can list and view service configurations.",
                "run.services.get", "run.services.list");
        addRoleMeta("roles/run.invoker", "Cloud Run Invoker", "Cloud Run",
                "Can invoke (call) Cloud Run services but cannot manage them. Typically used " +
                "for service-to-service authentication.",
                "run.services.invoke");

        // Monitoring
        addRoleMeta("roles/monitoring.viewer", "Monitoring Viewer", "Operations",
                "Read-only access to Cloud Monitoring metrics and time series data.",
                "monitoring.timeSeries.list");
        addRoleMeta("roles/monitoring.editor", "Monitoring Editor", "Operations",
                "Read-write access to Cloud Monitoring. Can create and manage dashboards and alerts.",
                "monitoring.*");
        addRoleMeta("roles/monitoring.admin", "Monitoring Admin", "Operations",
                "Full control over Cloud Monitoring including alerting policies and notification channels.",
                "monitoring.*");

        // Logging
        addRoleMeta("roles/logging.viewer", "Logs Viewer", "Operations",
                "Read-only access to Cloud Logging log entries. Can search and view logs.",
                "logging.logEntries.list");
        addRoleMeta("roles/logging.admin", "Logs Admin", "Operations",
                "Full control over Cloud Logging including log sinks, exclusions, and log buckets.",
                "logging.*");

        // IAM
        addRoleMeta("roles/iam.securityAdmin", "Security Admin", "Identity & Security",
                "Full control over IAM policies and service accounts. Can grant and revoke access " +
                "to all resources.",
                "iam.roles.*", "iam.serviceAccounts.*");
        addRoleMeta("roles/iam.roleViewer", "Role Viewer", "Identity & Security",
                "Read-only access to IAM roles. Can list and view role metadata and permissions.",
                "iam.roles.get", "iam.roles.list");
    }

    public static boolean isKnownRole(String role) {
        return ROLE_PERMISSIONS.containsKey(role);
    }

    public static Set<String> getPermissions(String role) {
        return ROLE_PERMISSIONS.getOrDefault(role, Collections.emptySet());
    }

    /** Returns all known roles with their metadata for the IAM console UI. */
    public static List<RoleMeta> getAllRoles() {
        return new ArrayList<>(ROLE_META.values());
    }

    /** Returns category names in display order. */
    public static List<String> getCategories() {
        return List.of("Basic", "Storage", "Pub/Sub", "Secret Manager", "Cloud Functions",
                "Cloud Tasks", "Cloud Scheduler", "Compute", "Cloud Run", "Operations",
                "Identity & Security");
    }

    /**
     * Resolve the union of all permissions from a set of roles.
     */
    public static Set<String> resolvePermissions(Iterable<String> roles) {
        Set<String> resolved = new HashSet<>();
        boolean hasWildcard = false;
        for (String role : roles) {
            Set<String> perms = getPermissions(role);
            if (perms.contains("*")) {
                hasWildcard = true;
            }
            resolved.addAll(perms);
        }
        if (hasWildcard) {
            // Owner or wildcard role — grant all
            resolved.add("*");
        }
        return resolved;
    }

    public static boolean hasPermission(Iterable<String> roles, String permission) {
        for (String role : roles) {
            Set<String> perms = getPermissions(role);
            if (perms.contains("*")) return true;
            if (perms.contains(permission)) return true;
            // Check wildcard pattern: storage.objects.* matches storage.objects.get
            for (String p : perms) {
                if (p.endsWith(".*") && permission.startsWith(p.substring(0, p.length() - 2))) {
                    return true;
                }
            }
        }
        return false;
    }
}
