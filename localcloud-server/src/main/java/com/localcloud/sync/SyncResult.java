package com.localcloud.sync;

public record SyncResult(int manifestId, long rowsSynced, long bytesSynced,
                          double costIncurred, String status, String errorMessage) {}
