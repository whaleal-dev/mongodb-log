package com.whaleal.mongodblog.analysis;

public enum DurationBucket {
    LT_100MS("lt_100ms", "< 100ms", 0, 100),
    FROM_100MS_TO_500MS("100ms_500ms", "100ms - 500ms", 100, 500),
    FROM_500MS_TO_1S("500ms_1s", "500ms - 1s", 500, 1_000),
    FROM_1S_TO_3S("1s_3s", "1s - 3s", 1_000, 3_000),
    FROM_3S_TO_10S("3s_10s", "3s - 10s", 3_000, 10_000),
    FROM_10S_TO_30S("10s_30s", "10s - 30s", 10_000, 30_000),
    FROM_30S_TO_60S("30s_60s", "30s - 60s", 30_000, 60_000),
    GTE_60S("gte_60s", ">= 60s", 60_000, Long.MAX_VALUE);

    private final String key;
    private final String label;
    private final long minimumInclusive;
    private final long maximumExclusive;

    DurationBucket(String key, String label, long minimumInclusive, long maximumExclusive) {
        this.key = key;
        this.label = label;
        this.minimumInclusive = minimumInclusive;
        this.maximumExclusive = maximumExclusive;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public boolean contains(long durationMillis) {
        return durationMillis >= minimumInclusive && durationMillis < maximumExclusive;
    }
}

