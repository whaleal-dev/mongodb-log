package com.whaleal.mongodblog.analysis.ftdc;

import java.util.List;

public record FtdcSeriesResult(String metricId, String path, String view,
                               List<Long> timestamps, List<Long> values,
                               Long min, Long max, Double average, boolean allZero) {
    public FtdcSeriesResult {
        timestamps = List.copyOf(timestamps);
        values = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(values));
    }
}
