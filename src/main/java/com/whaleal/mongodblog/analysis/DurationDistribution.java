package com.whaleal.mongodblog.analysis;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class DurationDistribution {
    private final Map<DurationBucket, MutableBucket> buckets = new EnumMap<>(DurationBucket.class);
    private long totalCount;

    public DurationDistribution() {
        for (DurationBucket bucket : DurationBucket.values()) {
            buckets.put(bucket, new MutableBucket());
        }
    }

    public void add(long durationMillis) {
        if (durationMillis < 0) {
            return;
        }
        for (DurationBucket bucket : DurationBucket.values()) {
            if (bucket.contains(durationMillis)) {
                buckets.get(bucket).add(durationMillis);
                totalCount++;
                return;
            }
        }
    }

    public List<DurationBucketStat> snapshot() {
        List<DurationBucketStat> result = new ArrayList<>();
        for (DurationBucket bucket : DurationBucket.values()) {
            MutableBucket value = buckets.get(bucket);
            double percentage = totalCount == 0 ? 0 : value.count * 100.0 / totalCount;
            double average = value.count == 0 ? 0 : value.totalDurationMillis * 1.0 / value.count;
            result.add(new DurationBucketStat(
                    bucket.key(), bucket.label(), value.count, percentage,
                    value.totalDurationMillis, average, value.maxDurationMillis
            ));
        }
        return List.copyOf(result);
    }

    private static final class MutableBucket {
        private long count;
        private long totalDurationMillis;
        private long maxDurationMillis;

        private void add(long durationMillis) {
            count++;
            totalDurationMillis += durationMillis;
            maxDurationMillis = Math.max(maxDurationMillis, durationMillis);
        }
    }
}

