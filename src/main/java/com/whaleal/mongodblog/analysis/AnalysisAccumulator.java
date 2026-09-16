package com.whaleal.mongodblog.analysis;

import com.whaleal.mongodblog.parser.ParseOutcome;
import com.whaleal.mongodblog.parser.ParseStatus;
import com.whaleal.mongodblog.parser.ParsedLogEntry;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class AnalysisAccumulator {
    private static final Pattern CONNECTION_COUNT = Pattern.compile("\\((\\d+) connections now open\\)");
    private static final Comparator<MutableAggregate> BY_COUNT = Comparator.comparingLong((MutableAggregate stat) -> stat.count).reversed();
    private static final Comparator<MutableAggregate> BY_RESPONSE = Comparator.comparingLong((MutableAggregate stat) -> stat.totalResponseBytes)
            .reversed().thenComparing(BY_COUNT);
    private final TopSlowQueryCollector topSlowQueries;
    private final DurationDistribution durationDistribution = new DurationDistribution();
    private final Map<String, MutableAggregate> operations = new LinkedHashMap<>();
    private final Map<String, MutableAggregate> namespaces = new LinkedHashMap<>();
    private final Map<String, MutableAggregate> patterns = new LinkedHashMap<>();
    private final Map<PatternKey, MutablePattern> patternStats = new LinkedHashMap<>();
    private final Map<String, MutableAggregate> plans = new LinkedHashMap<>();
    private final Map<String, MutableAggregate> remotes = new LinkedHashMap<>();
    private final Map<String, MutableAggregate> cpuByOperationNamespace = new LinkedHashMap<>();
    private final Map<String, Long> parseErrors = new LinkedHashMap<>();
    private final Map<String, Map<Long, Long>> cpuByOperationBuckets = new LinkedHashMap<>();
    private final Map<Long, MutableConnectionAverage> connections = new TreeMap<>();
    private Long logStartEpochMillis;
    private Long logEndEpochMillis;
    private long totalLines;
    private long successLines;
    private long partialLines;
    private long skippedLines;
    private long failedLines;
    private long slowQueryCount;
    private long totalSlowDurationMillis;
    private long heartbeatFailures;
    private boolean cpuAvailable;

    public AnalysisAccumulator(int topCapacity) {
        this.topSlowQueries = new TopSlowQueryCollector(topCapacity);
        List.of("insert", "update", "delete", "find").forEach(operation -> cpuByOperationBuckets.put(operation, new TreeMap<>()));
    }

    public void accept(ParseOutcome outcome) {
        totalLines++;
        incrementStatus(outcome.status());
        if (outcome.errorCode() != null && !outcome.errorCode().isBlank()) {
            parseErrors.merge(outcome.errorCode(), 1L, Long::sum);
        }
        outcome.entry().ifPresent(entry -> {
            logStartEpochMillis = logStartEpochMillis == null ? entry.timestampEpochMillis()
                    : Math.min(logStartEpochMillis, entry.timestampEpochMillis());
            logEndEpochMillis = logEndEpochMillis == null ? entry.timestampEpochMillis()
                    : Math.max(logEndEpochMillis, entry.timestampEpochMillis());
            acceptConnectionSample(entry);
            if (entry.heartbeatFailure()) {
                heartbeatFailures++;
            }
            if (entry.slowQuery() && entry.durationMillis() != null) {
                acceptSlowQuery(entry);
            }
        });
    }

    public AnalysisSummary finish() {
        return new AnalysisSummary(
                totalLines, successLines, partialLines, skippedLines, failedLines,
                slowQueryCount, totalSlowDurationMillis, heartbeatFailures, cpuAvailable,
                Collections.unmodifiableMap(new LinkedHashMap<>(parseErrors)),
                durationDistribution.snapshot(),
                snapshot(operations, Integer.MAX_VALUE, BY_COUNT),
                snapshot(namespaces, 20, BY_RESPONSE),
                snapshot(patterns, 50, BY_COUNT),
                snapshot(plans, Integer.MAX_VALUE, BY_COUNT),
                snapshot(remotes, 20, BY_RESPONSE),
                snapshot(cpuByOperationNamespace, Integer.MAX_VALUE, BY_COUNT),
                patternSnapshot(),
                namespaceResponseSnapshot(),
                cpuBucketSnapshot(),
                connections.entrySet().stream().map(entry -> new ConnectionAverage(entry.getKey(),
                        entry.getValue().count, entry.getValue().total / entry.getValue().count)).toList(),
                logStartEpochMillis,
                logEndEpochMillis
        );
    }

    public List<SlowQueryRecord> topSlowQueries() {
        return topSlowQueries.sorted();
    }

    private void incrementStatus(ParseStatus status) {
        switch (status) {
            case SUCCESS -> successLines++;
            case PARTIAL -> partialLines++;
            case SKIPPED -> skippedLines++;
            case FAILED -> failedLines++;
        }
    }

    private void acceptSlowQuery(ParsedLogEntry entry) {
        long duration = entry.durationMillis();
        slowQueryCount++;
        totalSlowDurationMillis += duration;
        durationDistribution.add(duration);
        SlowQueryRecord record = new SlowQueryRecord(
                entry.fileIndex() + "-" + entry.lineNumber(),
                entry.fileIndex(), entry.lineNumber(), entry.timestampEpochMillis(),
                entry.operation(), entry.namespace(), duration, entry.cpuNanos(), entry.responseLength(),
                entry.planSummary(), entry.remote(), entry.queryPattern(), entry.rawLine(), entry.attributes()
        );

        add(operations, valueOrUnknown(entry.operation()), entry);
        add(namespaces, valueOrUnknown(entry.namespace()), entry);
        add(patterns, valueOrUnknown(entry.queryPattern()), entry);
        PatternKey patternKey = new PatternKey(valueOrUnknown(entry.namespace()), valueOrUnknown(entry.operation()),
                valueOrUnknown(entry.queryPattern()));
        patternStats.computeIfAbsent(patternKey, ignored -> new MutablePattern()).add(entry, record);
        add(plans, valueOrUnknown(entry.planSummary()), entry);
        add(remotes, valueOrUnknown(entry.remote()), entry);
        if (entry.cpuNanos() != null) {
            cpuAvailable = true;
            add(cpuByOperationNamespace,
                    valueOrUnknown(entry.operation()) + "|" + valueOrUnknown(entry.namespace()), entry);
            String cpuOperation = "remove".equals(entry.operation()) ? "delete" : entry.operation();
            if (duration > 0 && entry.cpuNanos() >= 0 && cpuByOperationBuckets.containsKey(cpuOperation)) {
                double percent = entry.cpuNanos() / 1_000_000.0 / duration * 100;
                long bucket = (long) (Math.ceil(Math.floor(percent) / 10) * 10);
                cpuByOperationBuckets.get(cpuOperation).merge(bucket, 1L, Long::sum);
            }
        }

        topSlowQueries.offer(record);
    }

    private void add(Map<String, MutableAggregate> target, String key, ParsedLogEntry entry) {
        target.computeIfAbsent(key, ignored -> new MutableAggregate()).add(entry);
    }

    private Map<String, AggregateStat> snapshot(Map<String, MutableAggregate> source, int limit,
                                               Comparator<MutableAggregate> comparator) {
        Map<String, AggregateStat> result = new LinkedHashMap<>();
        source.entrySet().stream()
                .sorted(Map.Entry.<String, MutableAggregate>comparingByValue(comparator)
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .forEach(entry -> result.put(entry.getKey(), entry.getValue().snapshot()));
        return Collections.unmodifiableMap(result);
    }

    private String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private List<PatternStat> patternSnapshot() {
        Set<String> retainedIds = topSlowQueries.sorted().stream().map(SlowQueryRecord::queryId).collect(Collectors.toSet());
        return patternStats.entrySet().stream()
                .sorted(Comparator.<Map.Entry<PatternKey, MutablePattern>>comparingLong(entry -> entry.getValue().aggregate.count)
                        .reversed().thenComparing(entry -> entry.getKey().namespace)
                        .thenComparing(entry -> entry.getKey().operation).thenComparing(entry -> entry.getKey().pattern))
                .limit(50)
                .map(entry -> {
                    PatternKey key = entry.getKey();
                    MutablePattern pattern = entry.getValue();
                    AggregateStat stat = pattern.aggregate.snapshot();
                    String queryId = pattern.slowestQuery.queryId();
                    return new PatternStat(key.namespace, key.operation, key.pattern, String.join("; ", pattern.plans),
                            stat.count(), stat.totalDurationMillis(), stat.averageDurationMillis(), stat.minDurationMillis(),
                            stat.maxDurationMillis(), stat.totalCpuNanos(), pattern.cpuAvailable,
                            retainedIds.contains(queryId) ? queryId : null, pattern.slowestQuery);
                }).toList();
    }

    private Map<String, Long> namespaceResponseSnapshot() {
        Map<String, Long> result = new LinkedHashMap<>();
        snapshot(namespaces, Integer.MAX_VALUE, BY_RESPONSE).forEach((key, stat) -> result.put(key, stat.totalResponseBytes()));
        return Collections.unmodifiableMap(result);
    }

    private Map<String, Map<String, Long>> cpuBucketSnapshot() {
        Map<String, Map<String, Long>> result = new LinkedHashMap<>();
        cpuByOperationBuckets.forEach((operation, buckets) -> {
            Map<String, Long> values = new LinkedHashMap<>();
            buckets.forEach((bucket, count) -> values.put(Long.toString(bucket), count));
            result.put(operation, Collections.unmodifiableMap(values));
        });
        return Collections.unmodifiableMap(result);
    }

    private void acceptConnectionSample(ParsedLogEntry entry) {
        Long count = null;
        Object attribute = entry.attributes().get("connectionCount");
        if (attribute instanceof Number number) {
            count = number.longValue();
        } else if ("NETWORK".equalsIgnoreCase(entry.component()) && entry.message() != null) {
            Matcher matcher = CONNECTION_COUNT.matcher(entry.message());
            if (matcher.find()) {
                try {
                    count = Long.parseLong(matcher.group(1));
                } catch (NumberFormatException ignored) {
                    return;
                }
            }
        }
        if (count != null && count >= 0) {
            long hour = Math.floorDiv(entry.timestampEpochMillis(), 3_600_000) * 3_600_000;
            MutableConnectionAverage average = connections.computeIfAbsent(hour, ignored -> new MutableConnectionAverage());
            average.count++;
            average.total += count;
        }
    }

    private record PatternKey(String namespace, String operation, String pattern) {
    }

    private static final class MutableConnectionAverage {
        private long count;
        private double total;
    }

    private static final class MutablePattern {
        private final MutableAggregate aggregate = new MutableAggregate();
        private final Set<String> plans = new TreeSet<>();
        private boolean cpuAvailable;
        private SlowQueryRecord slowestQuery;

        private void add(ParsedLogEntry entry, SlowQueryRecord record) {
            if (slowestQuery == null || TopSlowQueryCollector.BEST_FIRST.compare(record, slowestQuery) < 0) {
                slowestQuery = record;
            }
            aggregate.add(entry);
            if (entry.planSummary() != null && !entry.planSummary().isBlank()) {
                plans.add(entry.planSummary());
            }
            cpuAvailable |= entry.cpuNanos() != null;
        }
    }

    private static final class MutableAggregate {
        private long count;
        private long totalDurationMillis;
        private long minDurationMillis = Long.MAX_VALUE;
        private long maxDurationMillis;
        private long totalResponseBytes;
        private long totalCpuNanos;

        private void add(ParsedLogEntry entry) {
            long duration = entry.durationMillis();
            count++;
            totalDurationMillis += duration;
            minDurationMillis = Math.min(minDurationMillis, duration);
            maxDurationMillis = Math.max(maxDurationMillis, duration);
            totalResponseBytes += entry.responseLength() == null ? 0 : entry.responseLength();
            totalCpuNanos += entry.cpuNanos() == null ? 0 : entry.cpuNanos();
        }

        private AggregateStat snapshot() {
            return new AggregateStat(
                    count,
                    totalDurationMillis,
                    count == 0 ? 0 : totalDurationMillis * 1.0 / count,
                    count == 0 ? 0 : minDurationMillis,
                    maxDurationMillis,
                    totalResponseBytes,
                    totalCpuNanos
            );
        }
    }
}
