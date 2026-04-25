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
}
