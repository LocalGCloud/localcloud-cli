package com.localcloud.emulators.common;

import java.time.Instant;
import java.util.Locale;

import com.google.longrunning.Operation;
import com.google.protobuf.Any;
import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;

public final class GrpcSupport {
    private GrpcSupport() {}

    public static String[] parseLocationParent(String parent) {
        String[] segments = parent.split("/");
        if (segments.length != 4 || !"projects".equals(segments[0]) || !"locations".equals(segments[2])) {
            throw new IllegalArgumentException("Invalid parent: " + parent);
        }
        return new String[] {segments[1], segments[3]};
    }

    public static String[] parseNamedResource(String name, String collection) {
        String[] segments = name.split("/");
        if (segments.length != 6 || !"projects".equals(segments[0]) || !"locations".equals(segments[2])
                || !collection.equals(segments[4])) {
            throw new IllegalArgumentException("Invalid resource name: " + name);
        }
        return new String[] {segments[1], segments[3], segments[5]};
    }

    public static String[] parseChildResource(String name, String parentCollection, String childCollection) {
        String[] segments = name.split("/");
        if (segments.length != 8 || !"projects".equals(segments[0]) || !"locations".equals(segments[2])
                || !parentCollection.equals(segments[4]) || !childCollection.equals(segments[6])) {
            throw new IllegalArgumentException("Invalid resource name: " + name);
        }
        return new String[] {segments[1], segments[3], segments[5], segments[7]};
    }

    public static String[] parseDataprocCluster(String projectId, String region, String clusterName) {
        if (projectId == null || projectId.isBlank() || region == null || region.isBlank()
                || clusterName == null || clusterName.isBlank()) {
            throw new IllegalArgumentException("project_id, region, and cluster_name are required");
        }
        return new String[] {projectId, region, clusterName};
    }

    public static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    public static Operation doneOperation(String name, Message response) {
        return Operation.newBuilder()
                .setName(name)
                .setDone(true)
                .setResponse(Any.pack(response))
                .build();
    }

    public static String safeDatabaseName(String id) {
        String normalized = id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (normalized.isBlank() || !Character.isLetter(normalized.charAt(0))) {
            normalized = "c_" + normalized;
        }
        return "alloydb_cluster_" + normalized;
    }
}
