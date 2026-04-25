package com.localcloud.sync;

import java.util.List;
import java.util.Map;

public record PreviewResult(List<String> columns, List<Map<String, Object>> rows,
                             long totalRows, long totalBytes) {}
