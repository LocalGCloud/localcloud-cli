package com.localcloud.sync;

public record CostEstimate(long estimatedRows, long estimatedBytes, double estimatedCostUsd, String details) {}
