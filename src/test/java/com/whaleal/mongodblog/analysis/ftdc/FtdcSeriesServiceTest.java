package com.whaleal.mongodblog.analysis.ftdc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whaleal.mongodblog.parser.ftdc.FtdcFixtureBuilder;
import com.whaleal.mongodblog.storage.ftdc.FileFtdcTaskRepository;
import com.whaleal.mongodblog.task.ftdc.FtdcOperationGate;
import com.whaleal.mongodblog.task.ftdc.FtdcTask;
import com.whaleal.mongodblog.task.ftdc.FtdcTaskRunner;
import com.whaleal.mongodblog.task.ftdc.FtdcTaskService;
import com.whaleal.mongodblog.task.ftdc.FtdcTaskStatus;
import org.bson.BsonDateTime;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class FtdcSeriesServiceTest {
    @TempDir
    Path directory;

    FileFtdcTaskRepository repository;
    FtdcOperationGate gate;
    FtdcSeriesService service;
    FtdcTask task;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        repository = new FileFtdcTaskRepository(directory, mapper);
        gate = new FtdcOperationGate();
        FtdcTaskRunner runner = new FtdcTaskRunner(repository, gate, mapper);
        FtdcTaskService tasks = new FtdcTaskService(repository, runner, Runnable::run);
        BsonDocument baseline = new BsonDocument("start", new BsonDateTime(1_700_000_000_000L))
                .append("server", new BsonDocument("counter", new BsonInt64(10))
                        .append("network", new BsonDocument("in", new BsonInt64(100))
                                .append("out", new BsonInt64(200))));
        byte[] content = FtdcFixtureBuilder.file(FtdcFixtureBuilder.metadata(),
                FtdcFixtureBuilder.block(baseline, List.of(
                        new long[]{1_700_000_000_000L, 1_700_000_001_000L, 1_700_000_002_000L, 1_700_000_003_000L},
                        new long[]{10, 12, 12, 9},
                        new long[]{100, 110, 120, 130},
                        new long[]{200, 220, 240, 260}
                )));
        task = tasks.create("fixture", List.of(new MockMultipartFile("files", "metrics.test", null, content)));
        task = tasks.get(task.id());
        assertThat(task.status()).isEqualTo(FtdcTaskStatus.COMPLETED);
        service = new FtdcSeriesService(repository, gate);
    }

    @Test
    void queriesBoundedRawAndDeltaSeries() {
        String metricId = repository.readCatalog(task.id()).metrics().stream()
                .filter(metric -> metric.path().equals("server/counter")).findFirst().orElseThrow().metricId();

        FtdcSeriesResult raw = service.series(task.id(), metricId,
                new FtdcSeriesQuery(null, null, 2, FtdcSeriesQuery.View.RAW));
        assertThat(raw.timestamps()).containsExactly(1_700_000_000_000L, 1_700_000_003_000L);
        assertThat(raw.values()).containsExactly(10L, 9L);
        assertThat(raw.min()).isEqualTo(9L);
        assertThat(raw.max()).isEqualTo(12L);
        assertThat(raw.average()).isEqualTo(10.75);
        assertThat(raw.allZero()).isFalse();

        FtdcSeriesResult delta = service.series(task.id(), metricId,
                new FtdcSeriesQuery(null, null, 2, FtdcSeriesQuery.View.DELTA));
        assertThat(delta.values()).containsExactly(null, -3L);
        assertThat(delta.min()).isEqualTo(-3L);
        assertThat(delta.max()).isEqualTo(2L);
        assertThat(delta.average()).isEqualTo(-1.0 / 3.0);
    }

    @Test
    void queriesEveryMetricInAGroupWithTheSameBoundedWindow() {
        FtdcMetricGroups.Group group = service.groups(task.id()).stream()
                .filter(item -> item.name().equals("server/network"))
                .findFirst().orElseThrow();

        FtdcGroupSeriesResult result = service.groupSeries(task.id(), group.groupId(),
                new FtdcSeriesQuery(null, null, 2, FtdcSeriesQuery.View.RAW));

        assertThat(result.name()).isEqualTo("server/network");
        assertThat(result.series()).extracting(FtdcSeriesResult::path)
                .containsExactly("server/network/in", "server/network/out");
        assertThat(result.series().get(0).values()).containsExactly(100L, 130L);
        assertThat(result.series().get(1).values()).containsExactly(200L, 260L);
    }

    @Test
    void preservesFirstLastSpikeAndValleyAndComputesStatisticsFromEveryPoint() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        FileFtdcTaskRepository extremaRepository = new FileFtdcTaskRepository(directory.resolve("extrema"), mapper);
        FtdcOperationGate extremaGate = new FtdcOperationGate();
        FtdcTaskRunner runner = new FtdcTaskRunner(extremaRepository, extremaGate, mapper);
        FtdcTaskService tasks = new FtdcTaskService(extremaRepository, runner, Runnable::run);
        byte[] content = FtdcFixtureBuilder.file(FtdcFixtureBuilder.metadata(), FtdcFixtureBuilder.block(
                new BsonDocument("start", new BsonDateTime(1_000L)).append("signal", new BsonInt64(10)),
                List.of(
                        new long[]{1_000L, 2_000L, 3_000L, 4_000L, 5_000L},
                        new long[]{10, 11, 999, -500, 12}
                )));
        FtdcTask extremaTask = tasks.create("extrema", List.of(
                new MockMultipartFile("files", "metrics.extrema", null, content)));
        FtdcSeriesService extremaService = new FtdcSeriesService(extremaRepository, extremaGate);
        FtdcMetricGroups.Group group = extremaService.groups(extremaTask.id()).stream()
                .filter(item -> item.name().equals("signal")).findFirst().orElseThrow();

        FtdcSeriesResult result = extremaService.groupSeries(extremaTask.id(), group.groupId(),
                new FtdcSeriesQuery(null, null, 4, FtdcSeriesQuery.View.RAW)).series().get(0);

        assertThat(result.timestamps()).containsExactly(1_000L, 3_000L, 4_000L, 5_000L);
        assertThat(result.values()).containsExactly(10L, 999L, -500L, 12L);
        assertThat(result.min()).isEqualTo(-500L);
        assertThat(result.max()).isEqualTo(999L);
        assertThat(result.average()).isEqualTo(106.4);
        assertThat(result.allZero()).isFalse();
    }

    @Test
    void preservesRealTimeGapAsNullWhileDownsamplingExtrema() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        FileFtdcTaskRepository gapRepository = new FileFtdcTaskRepository(directory.resolve("gap"), mapper);
        FtdcOperationGate gapGate = new FtdcOperationGate();
        FtdcTaskRunner runner = new FtdcTaskRunner(gapRepository, gapGate, mapper);
        FtdcTaskService tasks = new FtdcTaskService(gapRepository, runner, Runnable::run);
        long[] timestamps = {
                1_000L, 1_950L, 3_000L, 4_050L, 12_000L, 13_020L,
                14_000L, 30_000L, 31_050L, 32_000L, 33_020L, 34_000L
        };
        long[] values = {10, 11, 12, 900, 13, 14, 15, -700, 16, 17, 18, 19};
        byte[] content = FtdcFixtureBuilder.file(FtdcFixtureBuilder.metadata(), FtdcFixtureBuilder.block(
                new BsonDocument("start", new BsonDateTime(timestamps[0])).append("signal", new BsonInt64(values[0])),
                List.of(timestamps, values)));
        FtdcTask gapTask = tasks.create("gap", List.of(
                new MockMultipartFile("files", "metrics.gap", null, content)));
        FtdcSeriesService gapService = new FtdcSeriesService(gapRepository, gapGate);
        FtdcMetricGroups.Group group = gapService.groups(gapTask.id()).stream()
                .filter(item -> item.name().equals("signal")).findFirst().orElseThrow();

        FtdcSeriesResult result = gapService.groupSeries(gapTask.id(), group.groupId(),
                new FtdcSeriesQuery(null, null, 6, FtdcSeriesQuery.View.RAW)).series().get(0);

        assertThat(result.timestamps()).hasSameSizeAs(result.values()).hasSizeLessThanOrEqualTo(6);
        assertThat(result.timestamps().get(0)).isEqualTo(1_000L);
        assertThat(result.timestamps().get(result.timestamps().size() - 1)).isEqualTo(34_000L);
        assertThat(result.values()).contains(900L, -700L);
        assertThat(result.values().stream().filter(java.util.Objects::isNull)).hasSize(2);
        assertThat(result.timestamps().get(result.values().indexOf(null))).isBetween(4_051L, 11_999L);
    }

    @Test
    void pagesRawValuesAndStreamsGzipCsv() throws Exception {
        String metricId = repository.readCatalog(task.id()).metrics().stream()
                .filter(metric -> metric.path().equals("server/counter")).findFirst().orElseThrow().metricId();

        FtdcMetricPage page = service.page(task.id(), metricId, 1, 2);
        assertThat(page.total()).isEqualTo(4);
        assertThat(page.timestamps()).containsExactly(1_700_000_001_000L, 1_700_000_002_000L);
        assertThat(page.values()).containsExactly(12L, 12L);

        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        new FtdcMetricExportService(service, gate).export(task.id(), metricId, compressed);
        String csv;
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed.toByteArray()))) {
            csv = new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(csv).startsWith("timestampEpochMillis,value\n")
                .contains("1700000000000,10\n", "1700000003000,9\n");
    }

    @Test
    void mergesOverlappingFilesByTimeAndKeepsEarlierUploadAtDuplicateTimestamps() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        FileFtdcTaskRepository overlapRepository = new FileFtdcTaskRepository(directory.resolve("overlap"), mapper);
        FtdcOperationGate overlapGate = new FtdcOperationGate();
        FtdcTaskRunner runner = new FtdcTaskRunner(overlapRepository, overlapGate, mapper);
        FtdcTaskService tasks = new FtdcTaskService(overlapRepository, runner, Runnable::run);
        byte[] first = FtdcFixtureBuilder.file(FtdcFixtureBuilder.metadata(), FtdcFixtureBuilder.block(
                new BsonDocument("start", new BsonDateTime(1_000L)).append("value", new BsonInt64(10)),
                List.of(new long[]{1_000L, 2_000L, 3_000L}, new long[]{10, 20, 30})));
        byte[] second = FtdcFixtureBuilder.file(FtdcFixtureBuilder.metadata(), FtdcFixtureBuilder.block(
                new BsonDocument("start", new BsonDateTime(2_000L)).append("value", new BsonInt64(200)),
                List.of(new long[]{2_000L, 3_000L, 4_000L}, new long[]{200, 300, 400})));

        FtdcTask overlapTask = tasks.create("overlap", List.of(
                new MockMultipartFile("files", "metrics.first", null, first),
                new MockMultipartFile("files", "metrics.second", null, second)));
        String metricId = overlapRepository.readCatalog(overlapTask.id()).metrics().stream()
                .filter(metric -> metric.path().equals("value")).findFirst().orElseThrow().metricId();
        FtdcMetricPage page = new FtdcSeriesService(overlapRepository, overlapGate)
                .page(overlapTask.id(), metricId, 0, 10);

        assertThat(page.timestamps()).containsExactly(1_000L, 2_000L, 3_000L, 4_000L);
        assertThat(page.values()).containsExactly(10L, 20L, 30L, 400L);
    }
}
