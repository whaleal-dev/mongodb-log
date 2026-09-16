package com.whaleal.mongodblog.analysis;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TopSlowQueryCollectorTest {

    @Test
    void keepsOnlyTheSlowestRecords() {
        TopSlowQueryCollector collector = new TopSlowQueryCollector(3);

        collector.offer(record("a", 100, 5_000, 1, 1));
        collector.offer(record("b", 500, 6_000, 1, 2));
        collector.offer(record("c", 200, 7_000, 1, 3));
        collector.offer(record("d", 500, 8_000, 1, 4));
        collector.offer(record("e", 300, 9_000, 1, 5));

        assertThat(collector.sorted()).extracting(SlowQueryRecord::queryId)
                .containsExactly("b", "d", "e");
        assertThat(collector.sorted()).extracting(SlowQueryRecord::durationMillis)
                .containsExactly(500L, 500L, 300L);
    }

    @Test
    void ordersEqualDurationsByEarlierTimeThenFileAndLine() {
        TopSlowQueryCollector collector = new TopSlowQueryCollector(4);

        collector.offer(record("late", 500, 2_000, 0, 1));
        collector.offer(record("file-two", 500, 1_000, 2, 1));
        collector.offer(record("line-two", 500, 1_000, 1, 2));
        collector.offer(record("first", 500, 1_000, 1, 1));

        assertThat(collector.sorted()).extracting(SlowQueryRecord::queryId)
                .containsExactly("first", "line-two", "file-two", "late");
    }

    @Test
    void selectsExactTopFiveThousandFromMoreThanFiveThousandRecords() {
        TopSlowQueryCollector collector = new TopSlowQueryCollector(5_000);
        for (int duration = 0; duration < 6_000; duration++) {
            collector.offer(record(String.valueOf(duration), duration, duration, 0, duration));
        }

        assertThat(collector.sorted()).hasSize(5_000);
        assertThat(collector.sorted().get(0).durationMillis()).isEqualTo(5_999);
        assertThat(collector.sorted().get(4_999).durationMillis()).isEqualTo(1_000);
    }

    private SlowQueryRecord record(String id, long duration, long timestamp, int fileIndex, long lineNumber) {
        return new SlowQueryRecord(
                id, fileIndex, lineNumber, timestamp, "find", "db.items", duration,
                null, null, "IXSCAN", "127.0.0.1", "{}", "raw", Map.of()
        );
    }
}
