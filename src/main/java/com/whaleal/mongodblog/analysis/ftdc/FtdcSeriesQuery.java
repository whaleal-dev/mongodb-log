package com.whaleal.mongodblog.analysis.ftdc;

public record FtdcSeriesQuery(Long start, Long end, int maxPoints, View view) {
    public enum View { RAW, DELTA }

    public FtdcSeriesQuery {
        if (maxPoints < 1 || maxPoints > 2_000) throw new IllegalArgumentException("maxPoints 必须在 1 到 2000 之间");
        if (start != null && end != null && start > end) throw new IllegalArgumentException("start 不能晚于 end");
        if (view == null) view = View.RAW;
    }
}
