package com.localcloud.sync;

@FunctionalInterface
public interface SyncProgressCallback {
    void onProgress(long rowsTransferred, long bytesTransferred, long estimatedTotalRows);
}
