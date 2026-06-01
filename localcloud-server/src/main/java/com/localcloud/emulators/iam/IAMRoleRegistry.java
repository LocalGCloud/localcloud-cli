package com.localcloud.emulators.iam;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Registry of known GCP predefined roles and their permissions.
 * Used for role name validation and permission resolution.
 */
public class IAMRoleRegistry {

    private static final Map<String, Set<String>> ROLE_PERMISSIONS = new HashMap<>();

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

    public static boolean isKnownRole(String role) {
        return ROLE_PERMISSIONS.containsKey(role);
    }

    public static Set<String> getPermissions(String role) {
        return ROLE_PERMISSIONS.getOrDefault(role, Collections.emptySet());
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
