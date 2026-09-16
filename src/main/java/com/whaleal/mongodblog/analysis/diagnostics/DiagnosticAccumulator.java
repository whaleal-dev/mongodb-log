package com.whaleal.mongodblog.analysis.diagnostics;

import com.whaleal.mongodblog.parser.ParseOutcome;
import com.whaleal.mongodblog.parser.ParsedLogEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class DiagnosticAccumulator {
    private static final long HOUR_MILLIS = 3_600_000L;
    private static final int MAX_EVENT_TYPES = 500;
    private static final int MAX_REPLICATION_TYPES = 100;
    private static final int MAX_INSIGHTS = 50;
    private static final int MAX_SHAPES = 10_000;

    private final int maxTimelinePoints;
    private final Map<String, Long> severityCounts = new LinkedHashMap<>();
    private final Map<String, Long> componentCounts = new LinkedHashMap<>();
    private final Map<String, MutableEvent> abnormalEvents = new LinkedHashMap<>();
    private final Map<String, MutableReplicationEvent> replicationEvents = new LinkedHashMap<>();
    private final TreeMap<Long, MutableTimeBucket> timeline = new TreeMap<>();
    private final BoundedCounter applications = new BoundedCounter(100);
    private final BoundedCounter drivers = new BoundedCounter(100);
    private final BoundedCounter queryFrameworks = new BoundedCounter(50);
    private final Map<String, Long> slowFieldCoverage = new LinkedHashMap<>();
    private final List<LogDiagnostics.SlowQueryInsight> slowInsights = new ArrayList<>();
    private final Set<String> shapeHashes = new LinkedHashSet<>();
    private final Map<Integer, Long> lastTimestampByFile = new LinkedHashMap<>();
    private final BoundedCounter services = new BoundedCounter(20);
    private final BoundedCounter serverVersions = new BoundedCounter(20);

    private long timelineBucketMillis = HOUR_MILLIS;
    private long totalParsedLines;
    private long structuredLines;
    private long legacyLines;
    private long truncatedLines;
    private long taggedLines;
    private long outOfOrderLines;
    private long accepted;
    private long ended;
    private long authenticationSucceeded;
    private long notAuthenticating;
    private long reauthenticationWarnings;
    private long connectionCountSamples;
    private long connectionCountSum;
    private Long connectionCountMin;
    private Long connectionCountMax;
    private long slowTotal;
    private long collscanCount;
    private long highDocumentScanRatioCount;
    private long highIndexScanRatioCount;
    private long zeroReturnHighScanCount;
    private long storageDominantCount;
    private long planningDominantCount;
    private long writeConcernWaitCount;
    private long flowControlWaitCount;
    private long lockWaitCount;
    private long remoteOpWaitCount;
    private long authorizationWaitCount;
    private long queueWaitCount;
    private long oplogSlotWaitCount;
    private long hasSortStageCount;
    private long usedDiskCount;
    private long spillCount;

    public DiagnosticAccumulator() {
        this(2_000);
    }

    DiagnosticAccumulator(int maxTimelinePoints) {
        if (maxTimelinePoints < 1) {
            throw new IllegalArgumentException("maxTimelinePoints 必须大于 0");
        }
        this.maxTimelinePoints = maxTimelinePoints;
    }

    public void accept(ParseOutcome outcome) {
        outcome.entry().ifPresent(this::acceptEntry);
    }

    public LogDiagnostics finish() {
        List<LogDiagnostics.EventStat> events = abnormalEvents.values().stream()
                .map(MutableEvent::snapshot)
                .sorted(Comparator.comparingLong(LogDiagnostics.EventStat::count).reversed()
                        .thenComparing(LogDiagnostics.EventStat::key))
                .toList();
        List<LogDiagnostics.ReplicationEventStat> replication = replicationEvents.values().stream()
                .map(MutableReplicationEvent::snapshot)
                .sorted(Comparator.comparingLong(LogDiagnostics.ReplicationEventStat::count).reversed()
                        .thenComparing(LogDiagnostics.ReplicationEventStat::type))
                .toList();
        List<LogDiagnostics.DiagnosticTimeBucket> buckets = timeline.entrySet().stream()
                .map(entry -> entry.getValue().snapshot(entry.getKey()))
                .toList();
        List<LogDiagnostics.SlowQueryInsight> insights = slowInsights.stream()
                .sorted(INSIGHT_ORDER.reversed())
                .toList();
        return new LogDiagnostics(
                1,
                totalParsedLines,
                sortedCounts(severityCounts, Integer.MAX_VALUE),
                sortedCounts(componentCounts, Integer.MAX_VALUE),
                events,
                buckets,
                timelineBucketMillis,
                new LogDiagnostics.ConnectionDiagnostics(
                        accepted, ended, authenticationSucceeded, notAuthenticating, reauthenticationWarnings,
                        connectionCountSamples, connectionCountMin, connectionCountMax,
                        connectionCountSamples == 0 ? null : connectionCountSum * 1.0 / connectionCountSamples,
                        applications.snapshot(20), drivers.snapshot(20),
                        applications.approximate() || drivers.approximate()),
                replication,
                new LogDiagnostics.SlowQueryDiagnostics(
                        slowTotal, collscanCount, highDocumentScanRatioCount, highIndexScanRatioCount,
                        zeroReturnHighScanCount, storageDominantCount, planningDominantCount,
                        writeConcernWaitCount, flowControlWaitCount, lockWaitCount, remoteOpWaitCount,
                        authorizationWaitCount, queueWaitCount, oplogSlotWaitCount, hasSortStageCount,
                        usedDiskCount, spillCount, shapeHashes.size(), sortedCounts(slowFieldCoverage, Integer.MAX_VALUE),
                        queryFrameworks.snapshot(20), insights),
                new LogDiagnostics.DataQualityDiagnostics(
                        structuredLines, legacyLines, truncatedLines, taggedLines, outOfOrderLines,
                        services.snapshot(20), serverVersions.snapshot(20))
        );
    }

    private void acceptEntry(ParsedLogEntry entry) {
        totalParsedLines++;
        String severity = valueOrUnknown(entry.severity());
        String component = valueOrUnknown(entry.component());
        severityCounts.merge(severity, 1L, Long::sum);
        componentCounts.merge(component, 1L, Long::sum);
        acceptQuality(entry);

        boolean warning = severity.startsWith("W");
        boolean error = severity.startsWith("E");
        boolean fatal = severity.startsWith("F");
        if (warning || error || fatal) {
            acceptAbnormalEvent(entry);
        }

        String message = valueOrEmpty(entry.message());
        boolean connectionAccepted = message.equalsIgnoreCase("Connection accepted")
                || message.toLowerCase().startsWith("connection accepted from");
        boolean connectionEnded = message.equalsIgnoreCase("Connection ended")
                || message.toLowerCase().startsWith("end connection");
        acceptConnection(entry, connectionAccepted, connectionEnded);

        ReplicationType replicationType = replicationType(entry);
        if (replicationType != null) {
            acceptReplication(entry, replicationType);
        }
        acceptTimeline(entry.timestampEpochMillis(), warning, error, fatal,
                connectionAccepted, connectionEnded, replicationType != null);

        if (entry.slowQuery() && entry.durationMillis() != null) {
            acceptSlowQuery(entry);
        }
    }

    private void acceptQuality(ParsedLogEntry entry) {
        LogEnvelopeMetadata metadata = entry.envelopeMetadata();
        if (metadata == null) {
            legacyLines++;
        } else {
            structuredLines++;
            if (metadata.truncated()) truncatedLines++;
            if (!metadata.tags().isEmpty()) taggedLines++;
            if (metadata.service() != null && !metadata.service().isBlank()) services.add(metadata.service());
        }
        Long previous = lastTimestampByFile.put(entry.fileIndex(), entry.timestampEpochMillis());
        if (previous != null && entry.timestampEpochMillis() < previous) outOfOrderLines++;
        Object version = valueAt(entry.attributes(), "buildInfo", "version");
        if (version != null && !String.valueOf(version).isBlank()) serverVersions.add(String.valueOf(version));
    }

    private void acceptAbnormalEvent(ParsedLogEntry entry) {
        String key = valueOrUnknown(entry.component()) + "|"
                + (entry.messageId() == null ? valueOrUnknown(entry.message()) : entry.messageId());
        MutableEvent event = abnormalEvents.get(key);
        if (event == null) {
            if (abnormalEvents.size() >= MAX_EVENT_TYPES) return;
            event = new MutableEvent(key, entry);
            abnormalEvents.put(key, event);
        }
        event.add(entry);
    }

    private void acceptConnection(ParsedLogEntry entry, boolean connectionAccepted, boolean connectionEnded) {
        if (connectionAccepted) accepted++;
        if (connectionEnded) ended++;
        String message = valueOrEmpty(entry.message());
        if (message.equalsIgnoreCase("Successfully authenticated")) authenticationSucceeded++;
        if (message.equalsIgnoreCase("Connection not authenticating")) notAuthenticating++;
        if (message.toLowerCase().contains("attempted to reauthenticate")) reauthenticationWarnings++;

        Long count = nonNegativeLong(entry.attributes().get("connectionCount"));
        if (count != null) {
            connectionCountSamples++;
            connectionCountSum += count;
            connectionCountMin = connectionCountMin == null ? count : Math.min(connectionCountMin, count);
            connectionCountMax = connectionCountMax == null ? count : Math.max(connectionCountMax, count);
        }
        if (message.equalsIgnoreCase("client metadata")) {
            String application = stringAt(entry.attributes(), "doc", "application", "name");
            String driverName = stringAt(entry.attributes(), "doc", "driver", "name");
            String driverVersion = stringAt(entry.attributes(), "doc", "driver", "version");
            if (application != null) applications.add(application);
            if (driverName != null) drivers.add(driverVersion == null ? driverName : driverName + " " + driverVersion);
        }
    }

    private void acceptReplication(ParsedLogEntry entry, ReplicationType type) {
        String key = type.name() + "|" + (entry.messageId() == null ? valueOrUnknown(entry.message()) : entry.messageId());
        MutableReplicationEvent event = replicationEvents.get(key);
        if (event == null) {
            if (replicationEvents.size() >= MAX_REPLICATION_TYPES) return;
            event = new MutableReplicationEvent(type, entry);
            replicationEvents.put(key, event);
        }
        event.add(entry);
    }

    private ReplicationType replicationType(ParsedLogEntry entry) {
        String message = valueOrEmpty(entry.message()).toLowerCase();
        String component = valueOrEmpty(entry.component()).toUpperCase();
        if (message.contains("heartbeat failed")) return ReplicationType.HEARTBEAT_FAILURE;
        if (message.contains("host failed in replica set")) return ReplicationType.HOST_UNAVAILABLE;
        if (message.contains("topology change") || message.contains("primary server change")) return ReplicationType.TOPOLOGY_CHANGE;
        if (message.contains("member is in new state") || message.contains("state transition")) return ReplicationType.MEMBER_STATE_CHANGE;
        if (message.contains("election") || message.contains("takeover") || component.equals("ELECTION")) return ReplicationType.ELECTION;
        if (message.contains("sync source")) return ReplicationType.SYNC_SOURCE;
        if (message.contains("slow connection establishment")) return ReplicationType.SLOW_CONNECTION;
        if (message.contains("socket connectivity")) return ReplicationType.SOCKET_FAILURE;
        return null;
    }

    private void acceptTimeline(long timestamp, boolean warning, boolean error, boolean fatal,
                                boolean connectionAccepted, boolean connectionEnded, boolean replication) {
        if (!warning && !error && !fatal && !connectionAccepted && !connectionEnded && !replication) return;
        long bucketStart = Math.floorDiv(timestamp, timelineBucketMillis) * timelineBucketMillis;
        MutableTimeBucket bucket = timeline.computeIfAbsent(bucketStart, ignored -> new MutableTimeBucket());
        if (warning) bucket.warnings++;
        if (error) bucket.errors++;
        if (fatal) bucket.fatals++;
        if (connectionAccepted) bucket.connectionsAccepted++;
        if (connectionEnded) bucket.connectionsEnded++;
        if (replication) bucket.replicationEvents++;
        compactTimeline();
    }

    private void compactTimeline() {
        while (timeline.size() > maxTimelinePoints) {
            timelineBucketMillis *= 2;
            TreeMap<Long, MutableTimeBucket> compacted = new TreeMap<>();
            timeline.forEach((timestamp, bucket) -> {
                long target = Math.floorDiv(timestamp, timelineBucketMillis) * timelineBucketMillis;
                compacted.computeIfAbsent(target, ignored -> new MutableTimeBucket()).merge(bucket);
            });
            timeline.clear();
            timeline.putAll(compacted);
        }
    }

    private void acceptSlowQuery(ParsedLogEntry entry) {
        slowTotal++;
        Map<String, Object> attr = entry.attributes();
        Long docs = nonNegativeLong(attr.get("docsExamined"));
        Long keys = nonNegativeLong(attr.get("keysExamined"));
        Long returned = nonNegativeLong(attr.get("nreturned"));
        Long storageReadMicros = nonNegativeLong(valueAt(attr, "storage", "data", "timeReadingMicros"));
        Long planningMicros = nonNegativeLong(attr.get("planningTimeMicros"));
        Long writeConcernWait = nonNegativeLong(attr.get("waitForWriteConcernDurationMillis"));
        Long flowControlMicros = nonNegativeLong(valueAt(attr, "flowControl", "timeAcquiringMicros"));
        Long remoteOpWaitMillis = nonNegativeLong(attr.get("remoteOpWaitMillis"));
        Long authorizationWaitMicros = nonNegativeLong(valueAt(attr, "authorization", "userCacheWaitTimeMicros"));
        long queueWaitMicros = queueWaitMicros(attr.get("queues"));
        Long oplogSlotWaitMicros = nonNegativeLong(attr.get("totalOplogSlotDurationMicros"));
        SpillMetrics spillMetrics = spillMetrics(attr);
        long lockMicros = lockWaitMicros(attr.get("locks"));
        String plan = entry.planSummary() == null ? stringValue(attr.get("planSummary")) : entry.planSummary();
        String shapeHash = firstNonBlank(stringValue(attr.get("planCacheShapeHash")), stringValue(attr.get("queryHash")));
        String shapeIdentity = firstNonBlank(shapeHash, entry.queryPattern());
        String planCacheKey = stringValue(attr.get("planCacheKey"));
        String framework = stringValue(attr.get("queryFramework"));
        boolean collscan = plan != null && plan.contains("COLLSCAN");
        boolean highDocs = returned != null && returned > 0 && docs != null && docs * 1.0 / returned >= 100;
        boolean highKeys = returned != null && returned > 0 && keys != null && keys * 1.0 / returned >= 100;
        boolean zeroHighScan = returned != null && returned == 0 && docs != null && docs >= 1_000;
        boolean storageDominant = storageReadMicros != null && storageReadMicros >= entry.durationMillis() * 1_000.0 * 0.5;
        boolean planningDominant = planningMicros != null && planningMicros >= entry.durationMillis() * 1_000.0 * 0.5;
        boolean writeWait = writeConcernWait != null && writeConcernWait > 0;
        boolean flowWait = flowControlMicros != null && flowControlMicros > 0;
        boolean lockWait = lockMicros > 0;
        boolean remoteOpWait = remoteOpWaitMillis != null && remoteOpWaitMillis > 0;
        boolean authorizationWait = authorizationWaitMicros != null && authorizationWaitMicros > 0;
        boolean queueWait = queueWaitMicros > 0;
        boolean oplogSlotWait = oplogSlotWaitMicros != null && oplogSlotWaitMicros > 0;
        boolean hasSort = Boolean.TRUE.equals(attr.get("hasSortStage"));
        boolean usedDisk = Boolean.TRUE.equals(attr.get("usedDisk"));
        boolean spilled = spillMetrics.hasSpill();

        if (collscan) collscanCount++;
        if (highDocs) highDocumentScanRatioCount++;
        if (highKeys) highIndexScanRatioCount++;
        if (zeroHighScan) zeroReturnHighScanCount++;
        if (storageDominant) storageDominantCount++;
        if (planningDominant) planningDominantCount++;
        if (writeWait) writeConcernWaitCount++;
        if (flowWait) flowControlWaitCount++;
        if (lockWait) lockWaitCount++;
        if (remoteOpWait) remoteOpWaitCount++;
        if (authorizationWait) authorizationWaitCount++;
        if (queueWait) queueWaitCount++;
        if (oplogSlotWait) oplogSlotWaitCount++;
        if (hasSort) hasSortStageCount++;
        if (usedDisk) usedDiskCount++;
        if (spilled) spillCount++;
        if (shapeIdentity != null && shapeHashes.size() < MAX_SHAPES) shapeHashes.add(shapeIdentity);
        if (framework != null) queryFrameworks.add(framework);

        cover("planSummary", plan);
        cover("docsExamined", docs);
        cover("keysExamined", keys);
        cover("nreturned", returned);
        cover("queryHash", attr.get("queryHash"));
        cover("planCacheKey", planCacheKey);
        cover("planCacheShapeHash", attr.get("planCacheShapeHash"));
        cover("queryShapeHash", attr.get("queryShapeHash"));
        cover("planningTimeMicros", planningMicros);
        cover("storage.data.bytesRead", valueAt(attr, "storage", "data", "bytesRead"));
        cover("storage.data.timeReadingMicros", storageReadMicros);
        cover("flowControl", attr.get("flowControl"));
        cover("waitForWriteConcernDurationMillis", writeConcernWait);
        cover("locks", attr.get("locks"));
        cover("queryFramework", framework);
        cover("queues", attr.get("queues"));
        cover("workingMillis", attr.get("workingMillis"));
        cover("remoteOpWaitMillis", remoteOpWaitMillis);
        cover("authorization.userCacheWaitTimeMicros", authorizationWaitMicros);
        cover("cpuNanos", attr.get("cpuNanos"));
        cover("totalOplogSlotDurationMicros", oplogSlotWaitMicros);
        cover("spilledBytes", spillMetrics.spilledBytes() > 0 ? spillMetrics.spilledBytes() : null);
        cover("spilledRecords", spillMetrics.spilledRecords() > 0 ? spillMetrics.spilledRecords() : null);

        List<String> reasons = new ArrayList<>();
        if (collscan) reasons.add("COLLSCAN");
        if (highDocs) reasons.add("扫描文档／返回比高");
        if (highKeys) reasons.add("扫描索引键／返回比高");
        if (zeroHighScan) reasons.add("零返回但扫描量高");
        if (storageDominant) reasons.add("磁盘读取耗时占比高");
        if (planningDominant) reasons.add("查询规划耗时占比高");
        if (writeWait) reasons.add("写关注等待");
        if (flowWait) reasons.add("Flow Control 等待");
        if (lockWait) reasons.add("锁等待");
        if (remoteOpWait) reasons.add("等待分片响应");
        if (authorizationWait) reasons.add("鉴权缓存等待");
        if (queueWait) reasons.add("执行队列等待");
        if (oplogSlotWait) reasons.add("Oplog 提交排队");
        if (hasSort) reasons.add("额外排序");
        if (usedDisk) reasons.add("使用临时磁盘");
        if (spilled) reasons.add("查询执行落盘");
        if (reasons.isEmpty()) return;

        LogDiagnostics.SlowQueryInsight insight = new LogDiagnostics.SlowQueryInsight(
                entry.timestampEpochMillis(), entry.namespace(), entry.operation(), entry.queryPattern(),
                shapeHash, planCacheKey, plan, entry.durationMillis(), docs, keys, returned,
                ratio(docs, returned), ratio(keys, returned), millis(storageReadMicros), millis(planningMicros),
                writeConcernWait, millis(flowControlMicros), millis(lockMicros), reasons);
        retainInsight(insight);
    }

    private void cover(String field, Object value) {
        if (value != null) slowFieldCoverage.merge(field, 1L, Long::sum);
    }

    private void retainInsight(LogDiagnostics.SlowQueryInsight insight) {
        if (slowInsights.size() < MAX_INSIGHTS) {
            slowInsights.add(insight);
            return;
        }
        int minimumIndex = 0;
        for (int index = 1; index < slowInsights.size(); index++) {
            if (INSIGHT_ORDER.compare(slowInsights.get(index), slowInsights.get(minimumIndex)) < 0) minimumIndex = index;
        }
        if (INSIGHT_ORDER.compare(insight, slowInsights.get(minimumIndex)) > 0) slowInsights.set(minimumIndex, insight);
    }

    private long lockWaitMicros(Object locksValue) {
        Map<?, ?> locks = asMap(locksValue);
        if (locks == null) return 0;
        long total = 0;
        for (Object lockValue : locks.values()) {
            Map<?, ?> lock = asMap(lockValue);
            Map<?, ?> waits = lock == null ? null : asMap(lock.get("timeAcquiringMicros"));
            if (waits == null) continue;
            for (Object wait : waits.values()) {
                Long value = nonNegativeLong(wait);
                if (value != null) total += value;
            }
        }
        return total;
    }

    private long queueWaitMicros(Object queuesValue) {
        Map<?, ?> queues = asMap(queuesValue);
        if (queues == null) return 0;
        long total = 0;
        for (Object queueValue : queues.values()) {
            Map<?, ?> queue = asMap(queueValue);
            if (queue == null) continue;
            Long wait = nonNegativeLong(queue.get("totalTimeQueuedMicros"));
            if (wait != null) total += wait;
        }
        return total;
    }

    private SpillMetrics spillMetrics(Map<String, Object> attributes) {
        long spilledBytes = 0;
        long spilledRecords = 0;
        boolean hasSpill = false;
        for (Map.Entry<String, Object> field : attributes.entrySet()) {
            Long value = nonNegativeLong(field.getValue());
            if (value == null || value == 0) continue;
            if (field.getKey().endsWith("SpilledBytes")) spilledBytes += value;
            if (field.getKey().endsWith("SpilledRecords")) spilledRecords += value;
            if (field.getKey().endsWith("Spills")) hasSpill = true;
        }
        return new SpillMetrics(spilledBytes, spilledRecords, hasSpill || spilledBytes > 0 || spilledRecords > 0);
    }

    private Object valueAt(Map<String, Object> source, String... path) {
        Object current = source;
        for (String segment : path) {
            Map<?, ?> map = asMap(current);
            if (map == null) return null;
            current = map.get(segment);
        }
        return current;
    }

    private String stringAt(Map<String, Object> source, String... path) {
        return stringValue(valueAt(source, path));
    }

    private Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : null;
    }

    private Long nonNegativeLong(Object value) {
        if (!(value instanceof Number number)) return null;
        long result = number.longValue();
        return result < 0 ? null : result;
    }

    private Double ratio(Long value, Long returned) {
        return value == null || returned == null || returned <= 0 ? null : value * 1.0 / returned;
    }

    private Double millis(Long micros) {
        return micros == null ? null : micros / 1_000.0;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second != null && !second.isBlank() ? second : null;
    }

    private String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private record SpillMetrics(long spilledBytes, long spilledRecords, boolean hasSpill) {
    }

    private static Map<String, Long> sortedCounts(Map<String, Long> source, int limit) {
        Map<String, Long> result = new LinkedHashMap<>();
        source.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private static final Comparator<LogDiagnostics.SlowQueryInsight> INSIGHT_ORDER =
            Comparator.comparingInt((LogDiagnostics.SlowQueryInsight insight) -> insight.reasons().size())
                    .thenComparingLong(LogDiagnostics.SlowQueryInsight::durationMillis)
                    .thenComparingLong(LogDiagnostics.SlowQueryInsight::timestampEpochMillis);

    private enum ReplicationType {
        HEARTBEAT_FAILURE("心跳失败"),
        HOST_UNAVAILABLE("复制集主机失联"),
        TOPOLOGY_CHANGE("拓扑变化"),
        MEMBER_STATE_CHANGE("成员状态变化"),
        ELECTION("选举／接管"),
        SYNC_SOURCE("同步源变化"),
        SLOW_CONNECTION("慢连接建立"),
        SOCKET_FAILURE("Socket 检测失败");

        private final String label;

        ReplicationType(String label) {
            this.label = label;
        }
    }

    private static final class MutableEvent {
        private final String key;
        private final String severity;
        private final String component;
        private final Integer messageId;
        private final String message;
        private final List<LogDiagnostics.EventSample> samples = new ArrayList<>();
        private long count;
        private long first;
        private long last;

        private MutableEvent(String key, ParsedLogEntry entry) {
            this.key = key;
            this.severity = entry.severity();
            this.component = entry.component();
            this.messageId = entry.messageId();
            this.message = entry.message();
            this.first = entry.timestampEpochMillis();
            this.last = entry.timestampEpochMillis();
        }

        private void add(ParsedLogEntry entry) {
            count++;
            first = Math.min(first, entry.timestampEpochMillis());
            last = Math.max(last, entry.timestampEpochMillis());
            if (samples.size() < 3) samples.add(new LogDiagnostics.EventSample(entry.timestampEpochMillis(), entry.message()));
        }

        private LogDiagnostics.EventStat snapshot() {
            return new LogDiagnostics.EventStat(key, severity, component, messageId, message, count, first, last, samples);
        }
    }

    private static final class MutableReplicationEvent {
        private final ReplicationType type;
        private final String component;
        private final Integer messageId;
        private final String message;
        private long count;
        private long first;
        private long last;

        private MutableReplicationEvent(ReplicationType type, ParsedLogEntry entry) {
            this.type = type;
            this.component = entry.component();
            this.messageId = entry.messageId();
            this.message = entry.message();
            this.first = entry.timestampEpochMillis();
            this.last = entry.timestampEpochMillis();
        }

        private void add(ParsedLogEntry entry) {
            count++;
            first = Math.min(first, entry.timestampEpochMillis());
            last = Math.max(last, entry.timestampEpochMillis());
        }

        private LogDiagnostics.ReplicationEventStat snapshot() {
            return new LogDiagnostics.ReplicationEventStat(type.name(), type.label, component, messageId,
                    message, count, first, last);
        }
    }

    private static final class MutableTimeBucket {
        private long warnings;
        private long errors;
        private long fatals;
        private long connectionsAccepted;
        private long connectionsEnded;
        private long replicationEvents;

        private void merge(MutableTimeBucket other) {
            warnings += other.warnings;
            errors += other.errors;
            fatals += other.fatals;
            connectionsAccepted += other.connectionsAccepted;
            connectionsEnded += other.connectionsEnded;
            replicationEvents += other.replicationEvents;
        }

        private LogDiagnostics.DiagnosticTimeBucket snapshot(long epochMillis) {
            return new LogDiagnostics.DiagnosticTimeBucket(epochMillis, warnings, errors, fatals,
                    connectionsAccepted, connectionsEnded, replicationEvents);
        }
    }

    private static final class BoundedCounter {
        private final int capacity;
        private final Map<String, Long> counts = new LinkedHashMap<>();
        private boolean approximate;

        private BoundedCounter(int capacity) {
            this.capacity = capacity;
        }

        private void add(String value) {
            if (value == null || value.isBlank()) return;
            Long current = counts.get(value);
            if (current != null) {
                counts.put(value, current + 1);
                return;
            }
            if (counts.size() < capacity) {
                counts.put(value, 1L);
                return;
            }
            approximate = true;
            List<String> removed = new ArrayList<>();
            counts.replaceAll((key, count) -> {
                long next = count - 1;
                if (next == 0) removed.add(key);
                return next;
            });
            removed.forEach(counts::remove);
        }

        private Map<String, Long> snapshot(int limit) {
            return sortedCounts(counts, limit);
        }

        private boolean approximate() {
            return approximate;
        }
    }
}
