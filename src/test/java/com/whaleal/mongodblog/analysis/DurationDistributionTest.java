package com.whaleal.mongodblog.analysis;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DurationDistributionTest {

    @Test
    void assignsAllBoundaryValuesToLeftClosedRightOpenBuckets() {
        DurationDistribution distribution = new DurationDistribution();
        long[] values = {99, 100, 499, 500, 999, 1_000, 2_999, 3_000, 9_999, 10_000, 29_999, 30_000, 59_999, 60_000};
        for (long value : values) {
            distribution.add(value);
        }

        Map<String, DurationBucketStat> stats = distribution.snapshot().stream()
                .collect(Collectors.toMap(DurationBucketStat::key, Function.identity()));

        assertThat(stats.get("lt_100ms").count()).isEqualTo(1);
        assertThat(stats.get("100ms_500ms").count()).isEqualTo(2);
        assertThat(stats.get("500ms_1s").count()).isEqualTo(2);
        assertThat(stats.get("1s_3s").count()).isEqualTo(2);
        assertThat(stats.get("3s_10s").count()).isEqualTo(2);
        assertThat(stats.get("10s_30s").count()).isEqualTo(2);
        assertThat(stats.get("30s_60s").count()).isEqualTo(2);
        assertThat(stats.get("gte_60s").count()).isEqualTo(1);
        assertThat(stats.get("100ms_500ms").totalDurationMillis()).isEqualTo(599);
        assertThat(stats.get("100ms_500ms").averageDurationMillis()).isEqualTo(299.5);
        assertThat(stats.get("100ms_500ms").maxDurationMillis()).isEqualTo(499);
        assertThat(stats.get("lt_100ms").percentage()).isCloseTo(100.0 / 14, within(0.0001));
    }

    @Test
    void returnsZeroAveragesAndPercentagesWhenEmpty() {
        assertThat(new DurationDistribution().snapshot())
                .allSatisfy(stat -> {
                    assertThat(stat.count()).isZero();
                    assertThat(stat.averageDurationMillis()).isZero();
                    assertThat(stat.percentage()).isZero();
                });
    }
}
