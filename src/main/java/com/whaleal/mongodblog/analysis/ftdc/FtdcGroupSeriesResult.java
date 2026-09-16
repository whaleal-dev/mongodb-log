package com.whaleal.mongodblog.analysis.ftdc;

import java.util.List;

public record FtdcGroupSeriesResult(String groupId, String name, String view, List<FtdcSeriesResult> series) {
    public FtdcGroupSeriesResult {
        series = List.copyOf(series);
    }
}
