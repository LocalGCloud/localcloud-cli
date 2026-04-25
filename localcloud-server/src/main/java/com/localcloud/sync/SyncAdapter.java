package com.localcloud.sync;

import java.util.List;

public interface SyncAdapter {
    BrowseResult browseRemote(String project, String accessToken);
    PreviewResult previewRemote(String project, String resource, String accessToken, int limit);
    CostEstimate estimate(String project, String resource, List<SyncFilter> filters,
                          int rowLimit, String accessToken);
    SyncResult sync(String project, String resource, List<SyncFilter> filters,
                    int rowLimit, String accessToken, String localProject,
                    SyncProgressCallback progress);

    /**
     * Delete synced data from the local emulator for a given resource.
     * Called when user clicks "Remove from Local" to clean up data that
     * was previously synced into the local emulator.
     *
     * @param localProject the local project identifier
     * @param resource     the resource path (format varies by adapter)
     */
    void deleteLocal(String localProject, String resource);
}
