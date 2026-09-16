package com.whaleal.mongodblog.analysis.ftdc;

import java.util.List;

public record FtdcMetricPage(long offset, int limit, long total,
                             List<Long> timestamps, List<Long> values) {
    public FtdcMetricPage {
        timestamps = List.copyOf(timestamps);
        values = List.copyOf(values);
    }
}
