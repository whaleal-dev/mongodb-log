package com.whaleal.mongodblog.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

public final class TopSlowQueryCollector {
    public static final Comparator<SlowQueryRecord> BEST_FIRST = Comparator
            .comparingLong(SlowQueryRecord::durationMillis).reversed()
            .thenComparingLong(SlowQueryRecord::timestampEpochMillis)
            .thenComparingInt(SlowQueryRecord::fileIndex)
            .thenComparingLong(SlowQueryRecord::lineNumber);

    private final int capacity;
    private final PriorityQueue<SlowQueryRecord> worstFirst;

    public TopSlowQueryCollector(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Top K 容量必须大于零");
        }
        this.capacity = capacity;
        this.worstFirst = new PriorityQueue<>(BEST_FIRST.reversed());
    }

    public void offer(SlowQueryRecord record) {
        if (worstFirst.size() < capacity) {
            worstFirst.offer(record);
            return;
        }
        SlowQueryRecord worst = worstFirst.peek();
        if (BEST_FIRST.compare(record, worst) < 0) {
            worstFirst.poll();
            worstFirst.offer(record);
        }
    }

    public List<SlowQueryRecord> sorted() {
        List<SlowQueryRecord> result = new ArrayList<>(worstFirst);
        result.sort(BEST_FIRST);
        return List.copyOf(result);
    }
}

