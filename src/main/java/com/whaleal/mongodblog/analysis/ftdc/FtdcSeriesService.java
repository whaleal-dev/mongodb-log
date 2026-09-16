package com.whaleal.mongodblog.analysis.ftdc;

import com.whaleal.mongodblog.storage.ftdc.FtdcCatalog;
import com.whaleal.mongodblog.storage.ftdc.FtdcIndexReader;
import com.whaleal.mongodblog.storage.ftdc.FtdcIndexWriter;
import com.whaleal.mongodblog.storage.ftdc.FtdcTaskRepository;
import com.whaleal.mongodblog.task.ftdc.FtdcOperationGate;
import com.whaleal.mongodblog.task.ftdc.FtdcTask;
import com.whaleal.mongodblog.task.ftdc.FtdcTaskStatus;
import com.whaleal.mongodblog.web.TaskNotReadyException;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.stream.Collectors;

@Service
public class FtdcSeriesService {
    private final FtdcTaskRepository repository;
    private final FtdcOperationGate gate;
    private final FtdcMetricDecoder decoder = new FtdcMetricDecoder();

    public FtdcSeriesService(FtdcTaskRepository repository, FtdcOperationGate gate) {
        this.repository = repository;
        this.gate = gate;
    }

    public FtdcSeriesResult series(String taskId, String metricId, FtdcSeriesQuery query) {
        try {
            return gate.call(() -> querySeries(taskId, metricId, query));
        } catch (RuntimeException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FTDC 查询被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("FTDC 查询失败：" + e.getMessage(), e);
        }
    }

    public List<FtdcMetricGroups.Group> groups(String taskId) {
        requireCompleted(taskId);
        return FtdcMetricGroups.from(repository.readCatalog(taskId));
    }

    public FtdcGroupSeriesResult groupSeries(String taskId, String groupId, FtdcSeriesQuery query) {
        if (query.maxPoints() > 1_200) throw new IllegalArgumentException("分组查询 maxPoints 必须在 1 到 1200 之间");
        try {
            return gate.call(() -> queryGroupSeries(taskId, groupId, query));
        } catch (RuntimeException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FTDC 分组查询被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("FTDC 分组查询失败：" + e.getMessage(), e);
        }
    }

    public FtdcMetricPage page(String taskId, String metricId, long offset, int limit) {
        if (offset < 0) throw new IllegalArgumentException("offset 不能小于 0");
        if (limit < 1 || limit > 1_000) throw new IllegalArgumentException("limit 必须在 1 到 1000 之间");
        try {
            return gate.call(() -> queryPage(taskId, metricId, offset, limit));
        } catch (RuntimeException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FTDC 分页查询被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("FTDC 分页查询失败：" + e.getMessage(), e);
        }
    }

    public MetricContext context(String taskId, String metricId) {
        FtdcTask task = requireCompleted(taskId);
        FtdcCatalog catalog = repository.readCatalog(taskId);
        FtdcCatalog.Metric metric = catalog.requireMetric(metricId);
        Path taskDirectory = repository.taskDirectory(taskId);
        FtdcIndexReader index = new FtdcIndexReader(taskDirectory.resolve("blocks.idx"));
        List<FtdcIndexWriter.SourceFile> files = index.files();
        index.validateSources(taskDirectory.resolve("source"), files);
        List<FtdcIndexReader.MetricBlockIndex> blocks = new ArrayList<>(index.metricBlocks(metric.path()));
        blocks.sort(Comparator.comparingLong(FtdcIndexReader.MetricBlockIndex::startEpochMillis)
                .thenComparingInt(FtdcIndexReader.MetricBlockIndex::fileId)
                .thenComparingInt(FtdcIndexReader.MetricBlockIndex::blockOrdinal));
        return new MetricContext(task, catalog, metric, taskDirectory.resolve("source"), files, List.copyOf(blocks));
    }

    public void forEachPoint(MetricContext context, Long start, Long end, PointConsumer consumer) throws Exception {
        Map<Integer, List<FtdcIndexReader.MetricBlockIndex>> blocksByFile = context.blocks().stream()
                .filter(block -> start == null || block.endEpochMillis() >= start)
                .filter(block -> end == null || block.startEpochMillis() <= end)
                .collect(Collectors.groupingBy(FtdcIndexReader.MetricBlockIndex::fileId));
        try (FtdcMetricDecoder.SourceChannels sources = decoder.openSources(context.sourceDirectory(), context.files())) {
            PriorityQueue<FilePointIterator> queue = new PriorityQueue<>(Comparator
                    .comparingLong(FilePointIterator::timestamp)
                    .thenComparingInt(FilePointIterator::fileId));
            for (Map.Entry<Integer, List<FtdcIndexReader.MetricBlockIndex>> entry : blocksByFile.entrySet()) {
                List<FtdcIndexReader.MetricBlockIndex> blocks = new ArrayList<>(entry.getValue());
                blocks.sort(Comparator.comparingLong(FtdcIndexReader.MetricBlockIndex::startEpochMillis)
                        .thenComparingInt(FtdcIndexReader.MetricBlockIndex::blockOrdinal));
                FilePointIterator iterator = new FilePointIterator(entry.getKey(), blocks, sources, start, end);
                if (iterator.advance()) queue.add(iterator);
            }

            long lastTimestamp = Long.MIN_VALUE;
            while (!queue.isEmpty()) {
                long timestamp = queue.peek().timestamp();
                int selectedFileId = Integer.MAX_VALUE;
                long selectedValue = 0;
                while (!queue.isEmpty() && queue.peek().timestamp() == timestamp) {
                    FilePointIterator iterator = queue.poll();
                    if (iterator.fileId() < selectedFileId) {
                        selectedFileId = iterator.fileId();
                        selectedValue = iterator.value();
                    }
                    if (iterator.advance()) queue.add(iterator);
                }
                if (timestamp <= lastTimestamp) continue;
                consumer.accept(timestamp, selectedValue);
                lastTimestamp = timestamp;
            }
        }
    }

    private FtdcSeriesResult querySeries(String taskId, String metricId, FtdcSeriesQuery query) throws Exception {
        MetricContext context = context(taskId, metricId);
        long start = query.start() == null ? context.catalog().startEpochMillis() : query.start();
        long end = query.end() == null ? context.catalog().endEpochMillis() : query.end();
        if (start > end) throw new IllegalArgumentException("查询时间范围无数据");
        SeriesAccumulator accumulator = new SeriesAccumulator(query.maxPoints(), start, end);
        TimeGapTracker gaps = new TimeGapTracker(query.maxPoints());
        long[] previous = new long[1];
        boolean[] hasPrevious = new boolean[1];
        forEachPoint(context, start, end, (timestamp, rawValue) -> {
            gaps.accept(timestamp);
            Long value = rawValue;
            if (query.view() == FtdcSeriesQuery.View.DELTA) {
                value = hasPrevious[0] ? rawValue - previous[0] : null;
                previous[0] = rawValue;
                hasPrevious[0] = true;
            }
            accumulator.accept(timestamp, value);
        });
        return accumulator.result(metricId, context.metric().path(), query.view(), gaps.summary());
    }

    private FtdcGroupSeriesResult queryGroupSeries(String taskId, String groupId, FtdcSeriesQuery query) throws Exception {
        requireCompleted(taskId);
        FtdcCatalog catalog = repository.readCatalog(taskId);
        FtdcMetricGroups.Group group = FtdcMetricGroups.require(catalog, groupId);
        if (group.metricCount() > FtdcMetricGroups.MAX_METRICS_PER_GROUP) {
            throw new IllegalArgumentException("FTDC 指标组超过 200 个指标：" + group.name());
        }
        Path taskDirectory = repository.taskDirectory(taskId);
        FtdcIndexReader index = new FtdcIndexReader(taskDirectory.resolve("blocks.idx"));
        Path sourceDirectory = taskDirectory.resolve("source");
        FtdcIndexReader.GroupQueryIndex selected = index.groupQuery(
                group.metrics().stream().map(FtdcCatalog.Metric::path).toList());
        index.validateSources(sourceDirectory, selected.files());
        List<FtdcIndexReader.GroupMetricBlockIndex> blocks = new ArrayList<>(selected.blocks());
        blocks.sort(Comparator.comparingLong(FtdcIndexReader.GroupMetricBlockIndex::startEpochMillis)
                .thenComparingInt(FtdcIndexReader.GroupMetricBlockIndex::fileId)
                .thenComparingInt(FtdcIndexReader.GroupMetricBlockIndex::blockOrdinal));
        long start = query.start() == null ? catalog.startEpochMillis() : query.start();
        long end = query.end() == null ? catalog.endEpochMillis() : query.end();
        if (start > end) throw new IllegalArgumentException("查询时间范围无数据");
        int metricCount = group.metricCount();
        SeriesAccumulator[] accumulators = new SeriesAccumulator[metricCount];
        for (int i = 0; i < metricCount; i++) {
            accumulators[i] = new SeriesAccumulator(query.maxPoints(), start, end);
        }
        long[] previous = new long[metricCount];
        boolean[] hasPrevious = new boolean[metricCount];
        TimeGapTracker gaps = new TimeGapTracker(query.maxPoints());
        GroupContext context = new GroupContext(sourceDirectory, selected.files(), List.copyOf(blocks), metricCount);
        forEachGroupPoint(context, start, end, (timestamp, values, present) -> {
            gaps.accept(timestamp);
            for (int metricIndex = 0; metricIndex < metricCount; metricIndex++) {
                if (!present[metricIndex]) continue;
                long rawValue = values[metricIndex];
                Long value = rawValue;
                if (query.view() == FtdcSeriesQuery.View.DELTA) {
                    value = hasPrevious[metricIndex] ? rawValue - previous[metricIndex] : null;
                    previous[metricIndex] = rawValue;
                    hasPrevious[metricIndex] = true;
                }
                accumulators[metricIndex].accept(timestamp, value);
            }
        });
        TimeGapSummary gapSummary = gaps.summary();
        List<FtdcSeriesResult> output = new ArrayList<>(metricCount);
        for (int metricIndex = 0; metricIndex < metricCount; metricIndex++) {
            FtdcCatalog.Metric metric = group.metrics().get(metricIndex);
            output.add(accumulators[metricIndex].result(
                    metric.metricId(), metric.path(), query.view(), gapSummary));
        }
        return new FtdcGroupSeriesResult(group.groupId(), group.name(), query.view().name().toLowerCase(), output);
    }

    private void forEachGroupPoint(GroupContext context, Long start, Long end, GroupPointConsumer consumer) throws Exception {
        Map<Integer, List<FtdcIndexReader.GroupMetricBlockIndex>> blocksByFile = context.blocks().stream()
                .filter(block -> start == null || block.endEpochMillis() >= start)
                .filter(block -> end == null || block.startEpochMillis() <= end)
                .collect(Collectors.groupingBy(FtdcIndexReader.GroupMetricBlockIndex::fileId));
        try (FtdcMetricDecoder.SourceChannels sources = decoder.openSources(context.sourceDirectory(), context.files())) {
            PriorityQueue<GroupFilePointIterator> queue = new PriorityQueue<>(Comparator
                    .comparingLong(GroupFilePointIterator::timestamp)
                    .thenComparingInt(GroupFilePointIterator::fileId));
            for (Map.Entry<Integer, List<FtdcIndexReader.GroupMetricBlockIndex>> entry : blocksByFile.entrySet()) {
                List<FtdcIndexReader.GroupMetricBlockIndex> blocks = new ArrayList<>(entry.getValue());
                blocks.sort(Comparator.comparingLong(FtdcIndexReader.GroupMetricBlockIndex::startEpochMillis)
                        .thenComparingInt(FtdcIndexReader.GroupMetricBlockIndex::blockOrdinal));
                GroupFilePointIterator iterator = new GroupFilePointIterator(entry.getKey(), blocks, sources, start, end);
                if (iterator.advance()) queue.add(iterator);
            }
            long[] values = new long[context.metricCount()];
            boolean[] present = new boolean[context.metricCount()];
            int[] selectedFiles = new int[context.metricCount()];
            long lastTimestamp = Long.MIN_VALUE;
            while (!queue.isEmpty()) {
                long timestamp = queue.peek().timestamp();
                Arrays.fill(present, false);
                Arrays.fill(selectedFiles, Integer.MAX_VALUE);
                while (!queue.isEmpty() && queue.peek().timestamp() == timestamp) {
                    GroupFilePointIterator iterator = queue.poll();
                    for (int column = 0; column < iterator.metricIndexes().length; column++) {
                        int metricIndex = iterator.metricIndexes()[column];
                        if (iterator.fileId() < selectedFiles[metricIndex]) {
                            selectedFiles[metricIndex] = iterator.fileId();
                            values[metricIndex] = iterator.value(column);
                            present[metricIndex] = true;
                        }
                    }
                    if (iterator.advance()) queue.add(iterator);
                }
                if (timestamp <= lastTimestamp) continue;
                consumer.accept(timestamp, values, present);
                lastTimestamp = timestamp;
            }
        }
    }

    private FtdcMetricPage queryPage(String taskId, String metricId, long offset, int limit) throws Exception {
        MetricContext context = context(taskId, metricId);
        List<Long> timestamps = new ArrayList<>(limit);
        List<Long> values = new ArrayList<>(limit);
        long[] index = {0};
        forEachPoint(context, null, null, (timestamp, value) -> {
            if (index[0] >= offset && timestamps.size() < limit) {
                timestamps.add(timestamp);
                values.add(value);
            }
            index[0]++;
        });
        return new FtdcMetricPage(offset, limit, index[0], timestamps, values);
    }

    private FtdcTask requireCompleted(String id) {
        FtdcTask task = repository.findTask(id).orElseThrow(() -> new java.util.NoSuchElementException("FTDC 任务不存在：" + id));
        if (task.status() != FtdcTaskStatus.COMPLETED) throw new TaskNotReadyException("FTDC 任务尚未完成：" + id);
        return task;
    }

    private static long safeSpan(long start, long end) {
        long difference = end - start;
        return difference < 0 || difference == Long.MAX_VALUE ? Long.MAX_VALUE : difference + 1;
    }

    private static long ceilDiv(long value, long divisor) {
        return value / divisor + (value % divisor == 0 ? 0 : 1);
    }

    @FunctionalInterface
    public interface PointConsumer {
        void accept(long timestamp, long value) throws Exception;
    }

    @FunctionalInterface
    private interface GroupPointConsumer {
        void accept(long timestamp, long[] values, boolean[] present) throws Exception;
    }

    public record MetricContext(FtdcTask task, FtdcCatalog catalog, FtdcCatalog.Metric metric,
                                Path sourceDirectory, List<FtdcIndexWriter.SourceFile> files,
                                List<FtdcIndexReader.MetricBlockIndex> blocks) {
    }

    private record GroupContext(Path sourceDirectory, List<FtdcIndexWriter.SourceFile> files,
                                List<FtdcIndexReader.GroupMetricBlockIndex> blocks, int metricCount) {
    }

    private final class FilePointIterator {
        private final int fileId;
        private final List<FtdcIndexReader.MetricBlockIndex> blocks;
        private final FtdcMetricDecoder.SourceChannels sources;
        private final Long start;
        private final Long end;
        private int blockIndex;
        private FtdcMetricDecoder.DecodedBlock decoded;
        private long timestamp;
        private long value;

        private FilePointIterator(int fileId, List<FtdcIndexReader.MetricBlockIndex> blocks,
                                  FtdcMetricDecoder.SourceChannels sources, Long start, Long end) {
            this.fileId = fileId;
            this.blocks = blocks;
            this.sources = sources;
            this.start = start;
            this.end = end;
        }

        private boolean advance() throws Exception {
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (decoded != null && decoded.advance()) {
                    timestamp = decoded.timestamp();
                    value = decoded.value();
                    if (start != null && timestamp < start) continue;
                    if (end != null && timestamp > end) continue;
                    return true;
                }
                if (blockIndex >= blocks.size()) return false;
                decoded = decoder.decode(sources, blocks.get(blockIndex++));
            }
        }

        private int fileId() { return fileId; }
        private long timestamp() { return timestamp; }
        private long value() { return value; }
    }

    private final class GroupFilePointIterator {
        private final int fileId;
        private final List<FtdcIndexReader.GroupMetricBlockIndex> blocks;
        private final FtdcMetricDecoder.SourceChannels sources;
        private final Long start;
        private final Long end;
        private int blockIndex;
        private FtdcMetricDecoder.GroupDecodedBlock decoded;
        private long timestamp;

        private GroupFilePointIterator(int fileId, List<FtdcIndexReader.GroupMetricBlockIndex> blocks,
                                       FtdcMetricDecoder.SourceChannels sources, Long start, Long end) {
            this.fileId = fileId;
            this.blocks = blocks;
            this.sources = sources;
            this.start = start;
            this.end = end;
        }

        private boolean advance() throws Exception {
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (decoded != null && decoded.advance()) {
                    timestamp = decoded.timestamp();
                    if (start != null && timestamp < start) continue;
                    if (end != null && timestamp > end) continue;
                    return true;
                }
                if (blockIndex >= blocks.size()) return false;
                decoded = decoder.decodeGroup(sources, blocks.get(blockIndex++));
            }
        }

        private int[] metricIndexes() { return decoded.metricIndexes(); }
        private long value(int column) { return decoded.value(column); }
        private int fileId() { return fileId; }
        private long timestamp() { return timestamp; }
    }

    private static final class TimeGapTracker {
        private static final int SAMPLE_CAPACITY = 4_096;

        private final long[] intervalSamples = new long[SAMPLE_CAPACITY];
        private final PriorityQueue<TimeGap> largestIntervals;
        private final int retainedIntervalCount;
        private int sampleCount;
        private long intervalsSeen;
        private boolean hasPrevious;
        private long previousTimestamp;

        private TimeGapTracker(int maxPoints) {
            retainedIntervalCount = Math.max(1, maxPoints);
            largestIntervals = new PriorityQueue<>(Comparator.comparingLong(TimeGap::interval));
        }

        private void accept(long timestamp) {
            if (hasPrevious && timestamp > previousTimestamp) {
                long interval = timestamp - previousTimestamp;
                if (interval <= 0) interval = Long.MAX_VALUE;
                sample(interval);
                if (largestIntervals.size() < retainedIntervalCount) {
                    largestIntervals.add(new TimeGap(previousTimestamp, timestamp, interval));
                } else if (interval > largestIntervals.peek().interval()) {
                    largestIntervals.poll();
                    largestIntervals.add(new TimeGap(previousTimestamp, timestamp, interval));
                }
            }
            previousTimestamp = timestamp;
            hasPrevious = true;
        }

        private void sample(long interval) {
            intervalsSeen++;
            if (sampleCount < intervalSamples.length) {
                intervalSamples[sampleCount++] = interval;
                return;
            }
            long slot = Long.remainderUnsigned(mix64(intervalsSeen), intervalsSeen);
            if (slot < intervalSamples.length) intervalSamples[(int) slot] = interval;
        }

        private TimeGapSummary summary() {
            if (sampleCount == 0) return new TimeGapSummary(0, List.of());
            long[] sorted = Arrays.copyOf(intervalSamples, sampleCount);
            Arrays.sort(sorted);
            long preliminary = sorted[(sorted.length - 1) / 2];
            long outlierLimit = multiplySaturated(preliminary, 10);
            int filteredCount = 0;
            while (filteredCount < sorted.length && sorted[filteredCount] <= outlierLimit) filteredCount++;
            long typical = sorted[(Math.max(1, filteredCount) - 1) / 2];
            long gapThreshold = multiplySaturated(typical, 3);
            List<TimeGap> gaps = largestIntervals.stream()
                    .filter(gap -> gap.interval() > gapThreshold)
                    .sorted(Comparator.comparingLong(TimeGap::start))
                    .toList();
            return new TimeGapSummary(typical, gaps);
        }

        private static long multiplySaturated(long value, long multiplier) {
            return value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
        }

        private static long mix64(long value) {
            long mixed = value;
            mixed = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L;
            mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL;
            return mixed ^ (mixed >>> 31);
        }
    }

    private record TimeGap(long start, long end, long interval) {
        private long marker(long typicalInterval) {
            long step = Math.min(interval - 1, Math.max(1, typicalInterval));
            return start + step;
        }
    }

    private record TimeGapSummary(long typicalInterval, List<TimeGap> gaps) {
    }

    private static final class SeriesAccumulator {
        private final int maxPoints;
        private final long rangeStart;
        private final long bucketWidth;
        private final int bucketCount;
        private final long[] initialTimes;
        private final long[] initialValues;
        private final boolean[] initialValid;
        private int initialCount;
        private boolean downsampled;
        private final boolean[] bucketPresent;
        private final long[] minTimes;
        private final long[] minValues;
        private final long[] maxTimes;
        private final long[] maxValues;
        private boolean bucketsInitialized;
        private boolean hasFirst;
        private long firstTime;
        private Long firstValue;
        private long lastTime;
        private Long lastValue;
        private long validCount;
        private long minimum;
        private long maximum;
        private double sum;

        private SeriesAccumulator(int maxPoints, long start, long end) {
            this.maxPoints = maxPoints;
            this.rangeStart = start;
            this.bucketCount = Math.max(0, (maxPoints - 2) / 2);
            this.bucketWidth = bucketCount == 0 ? 1 : Math.max(1, ceilDiv(safeSpan(start, end), bucketCount));
            this.initialTimes = new long[maxPoints + 1];
            this.initialValues = new long[maxPoints + 1];
            this.initialValid = new boolean[maxPoints + 1];
            this.bucketPresent = new boolean[bucketCount];
            this.minTimes = new long[bucketCount];
            this.minValues = new long[bucketCount];
            this.maxTimes = new long[bucketCount];
            this.maxValues = new long[bucketCount];
        }

        private void accept(long timestamp, Long value) {
            if (!hasFirst) {
                hasFirst = true;
                firstTime = timestamp;
                firstValue = value;
            }
            lastTime = timestamp;
            lastValue = value;
            if (value != null) {
                if (validCount == 0) {
                    minimum = value;
                    maximum = value;
                } else {
                    minimum = Math.min(minimum, value);
                    maximum = Math.max(maximum, value);
                }
                validCount++;
                sum += value;
            }
            if (!downsampled) {
                initialTimes[initialCount] = timestamp;
                initialValid[initialCount] = value != null;
                if (value != null) initialValues[initialCount] = value;
                initialCount++;
                if (initialCount > maxPoints) {
                    downsampled = true;
                    initializeBuckets();
                }
            } else if (value != null) {
                addToBucket(timestamp, value);
            }
        }

        private void addToBucket(long timestamp, long value) {
            if (bucketCount == 0) return;
            long rawBucket = (timestamp - rangeStart) / bucketWidth;
            int bucket = (int) Math.min(bucketCount - 1L, Math.max(0, rawBucket));
            if (!bucketPresent[bucket]) {
                bucketPresent[bucket] = true;
                minTimes[bucket] = maxTimes[bucket] = timestamp;
                minValues[bucket] = maxValues[bucket] = value;
                return;
            }
            if (value < minValues[bucket]) {
                minValues[bucket] = value;
                minTimes[bucket] = timestamp;
            }
            if (value > maxValues[bucket]) {
                maxValues[bucket] = value;
                maxTimes[bucket] = timestamp;
            }
        }

        private FtdcSeriesResult result(String metricId, String path, FtdcSeriesQuery.View view,
                                        TimeGapSummary gapSummary) {
            List<Long> times = new ArrayList<>(Math.min(initialCount, maxPoints));
            List<Long> values = new ArrayList<>(Math.min(initialCount, maxPoints));
            List<TimeGap> gaps = relevantGaps(gapSummary);
            if (!downsampled && initialCount + gaps.size() <= maxPoints) {
                List<SamplePoint> points = new ArrayList<>(initialCount + gaps.size());
                for (int i = 0; i < initialCount; i++) {
                    points.add(new SamplePoint(initialTimes[i], initialValid[i] ? initialValues[i] : null));
                }
                addGapMarkers(points, gaps, gapSummary.typicalInterval());
                writePoints(points, times, values);
            } else if (hasFirst) {
                initializeBuckets();
                int boundaryPoints = firstTime == lastTime ? 1 : Math.min(2, maxPoints);
                int targetBucketCount = Math.max(0, (maxPoints - boundaryPoints - gaps.size()) / 2);
                List<SamplePoint> points = new ArrayList<>(maxPoints);
                points.add(new SamplePoint(firstTime, firstValue));
                addBucketExtrema(points, targetBucketCount);
                addGapMarkers(points, gaps, gapSummary.typicalInterval());
                if (firstTime != lastTime) points.add(new SamplePoint(lastTime, lastValue));
                writePoints(points, times, values);
            }
            Long min = validCount == 0 ? null : minimum;
            Long max = validCount == 0 ? null : maximum;
            Double average = validCount == 0 ? null : sum / validCount;
            boolean allZero = validCount > 0 && minimum == 0 && maximum == 0;
            return new FtdcSeriesResult(metricId, path, view.name().toLowerCase(), times, values,
                    min, max, average, allZero);
        }

        private void initializeBuckets() {
            if (bucketsInitialized) return;
            for (int i = 0; i < initialCount; i++) {
                if (initialValid[i]) addToBucket(initialTimes[i], initialValues[i]);
            }
            bucketsInitialized = true;
        }

        private List<TimeGap> relevantGaps(TimeGapSummary summary) {
            if (!hasFirst || summary.gaps().isEmpty()) return List.of();
            List<TimeGap> relevant = summary.gaps().stream()
                    .filter(gap -> gap.start() >= firstTime && gap.end() <= lastTime)
                    .collect(Collectors.toCollection(ArrayList::new));
            int boundaryPoints = firstTime == lastTime ? 1 : Math.min(2, maxPoints);
            int limit = Math.max(0, maxPoints - boundaryPoints);
            if (relevant.size() > limit) {
                relevant.sort(Comparator.comparingLong(TimeGap::interval).reversed());
                relevant = new ArrayList<>(relevant.subList(0, limit));
                relevant.sort(Comparator.comparingLong(TimeGap::start));
            }
            return relevant;
        }

        private void addBucketExtrema(List<SamplePoint> points, int targetBucketCount) {
            if (targetBucketCount == 0 || bucketCount == 0) return;
            ExtremaBucket[] merged = new ExtremaBucket[targetBucketCount];
            for (int bucket = 0; bucket < bucketCount; bucket++) {
                if (!bucketPresent[bucket]) continue;
                int target = (int) Math.min(targetBucketCount - 1L,
                        (long) bucket * targetBucketCount / bucketCount);
                if (merged[target] == null) merged[target] = new ExtremaBucket();
                merged[target].accept(minTimes[bucket], minValues[bucket]);
                merged[target].accept(maxTimes[bucket], maxValues[bucket]);
            }
            for (ExtremaBucket bucket : merged) {
                if (bucket == null) continue;
                if (bucket.minTime <= bucket.maxTime) {
                    points.add(new SamplePoint(bucket.minTime, bucket.minValue));
                    points.add(new SamplePoint(bucket.maxTime, bucket.maxValue));
                } else {
                    points.add(new SamplePoint(bucket.maxTime, bucket.maxValue));
                    points.add(new SamplePoint(bucket.minTime, bucket.minValue));
                }
            }
        }

        private void addGapMarkers(List<SamplePoint> points, List<TimeGap> gaps, long typicalInterval) {
            for (TimeGap gap : gaps) {
                points.add(new SamplePoint(gap.marker(typicalInterval), null));
            }
        }

        private void writePoints(List<SamplePoint> points, List<Long> times, List<Long> values) {
            points.sort(Comparator.comparingLong(SamplePoint::timestamp)
                    .thenComparing(point -> point.value() == null));
            for (SamplePoint point : points) {
                int size = times.size();
                if (size > 0 && times.get(size - 1) == point.timestamp()) continue;
                if (size >= maxPoints) break;
                times.add(point.timestamp());
                values.add(point.value());
            }
        }

        private record SamplePoint(long timestamp, Long value) {
        }

        private static final class ExtremaBucket {
            private boolean present;
            private long minTime;
            private long minValue;
            private long maxTime;
            private long maxValue;

            private void accept(long timestamp, long value) {
                if (!present) {
                    present = true;
                    minTime = maxTime = timestamp;
                    minValue = maxValue = value;
                    return;
                }
                if (value < minValue) {
                    minValue = value;
                    minTime = timestamp;
                }
                if (value > maxValue) {
                    maxValue = value;
                    maxTime = timestamp;
                }
            }
        }
    }
}
