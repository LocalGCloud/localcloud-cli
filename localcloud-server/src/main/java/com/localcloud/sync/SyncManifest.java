package com.localcloud.sync;

public record SyncManifest(
    String projectId,
    String serviceId,
    String resourcePath,
    String sourceProject,
    String filtersJson,
    long rowCount,
    long bytesSynced,
    double estimatedCost,
    String status,
    String errorMessage
) {}
