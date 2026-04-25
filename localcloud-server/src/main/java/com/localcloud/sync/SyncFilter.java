package com.localcloud.sync;

public record SyncFilter(String column, String operator, String value, String columnType) {}
