package com.whaleal.mongodblog.analysis.ftdc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whaleal.mongodblog.storage.ftdc.FileFtdcTaskRepository;
import com.whaleal.mongodblog.task.ftdc.FtdcOperationGate;
import com.whaleal.mongodblog.task.ftdc.FtdcTask;
import com.whaleal.mongodblog.task.ftdc.FtdcTaskInputFile;
import com.whaleal.mongodblog.task.ftdc.FtdcTaskRunner;
import com.whaleal.mongodblog.task.ftdc.FtdcTaskStatus;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.io.OutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class FtdcRealSampleAcceptanceTest {
    @TempDir
    Path dataDirectory;

    @Test
    void validatesKnownMongoDbFtdcSampleWhenConfigured() throws Exception {
        String configured = System.getProperty("ftdc.real.sample");
        Assumptions.assumeTrue(configured != null && Files.isRegularFile(Path.of(configured)));
        Path sample = Path.of(configured);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        FileFtdcTaskRepository repository = new FileFtdcTaskRepository(dataDirectory, mapper);
        FtdcOperationGate gate = new FtdcOperationGate();
        String id = "real-sample";
        String storedName = "0000-" + sample.getFileName();
        Path source = Files.createDirectories(repository.workDirectory(id).resolve("source"));
        Files.copy(sample, source.resolve(storedName));
        FtdcTask queued = new FtdcTask(id, "real", FtdcTaskStatus.QUEUED, System.currentTimeMillis(), null, null,
                List.of(new FtdcTaskInputFile(sample.getFileName().toString(), storedName, Files.size(sample))),
                Files.size(sample), 0, 0, 0, 0, null, null, null);
        repository.saveTask(queued);

        new FtdcTaskRunner(repository, gate, mapper).run(id);
        FtdcTask completed = repository.findTask(id).orElseThrow();

        assertThat(completed.status()).withFailMessage(String.valueOf(completed.errorMessage())).isEqualTo(FtdcTaskStatus.COMPLETED);
        assertThat(completed.blockCount()).isEqualTo(96);
        assertThat(completed.metricCount()).isEqualTo(2_082);
        assertThat(completed.sampleCount()).isEqualTo(28_800);
        assertThat(Files.size(repository.taskDirectory(id).resolve("blocks.idx"))).isLessThanOrEqualTo(5L * 1024 * 1024);

        String startMetric = repository.readCatalog(id).metrics().stream()
                .filter(metric -> metric.path().equals("start")).findFirst().orElseThrow().metricId();
        FtdcSeriesService series = new FtdcSeriesService(repository, gate);
        FtdcMetricPage firstPage = series.page(id, startMetric, 0, 1_000);
        // Expected sequences are fixed acceptance vectors for this real-data fixture.
        assertThat(firstPage.values().subList(0, 10)).containsExactly(
                1_768_293_370_000L, 1_768_293_371_000L, 1_768_293_372_000L, 1_768_293_373_000L,
                1_768_293_374_000L, 1_768_293_375_000L, 1_768_293_376_000L, 1_768_293_377_000L,
                1_768_293_378_000L, 1_768_293_379_000L);
        long oneSecondIntervals = 0;
        for (int i = 1; i < firstPage.values().size(); i++) {
            if (firstPage.values().get(i) - firstPage.values().get(i - 1) == 1_000) oneSecondIntervals++;
        }
        assertThat(oneSecondIntervals).isGreaterThan(900);

        String connectionsMetric = repository.readCatalog(id).metrics().stream()
                .filter(metric -> metric.path().equals("serverStatus/connections/current"))
                .findFirst().orElseThrow().metricId();
        assertThat(series.page(id, connectionsMetric, 0, 10).values())
                .containsExactly(836L, 836L, 836L, 836L, 836L, 836L, 836L, 836L, 836L, 836L);

        String bytesInMetric = repository.readCatalog(id).metrics().stream()
                .filter(metric -> metric.path().equals("serverStatus/network/bytesIn"))
                .findFirst().orElseThrow().metricId();
        assertThat(series.page(id, bytesInMetric, 0, 10).values()).containsExactly(
                4_683_828_429_064L, 4_683_828_438_580L, 4_683_828_448_669L, 4_683_828_457_759L,
                4_683_828_469_361L, 4_683_828_484_413L, 4_683_828_495_899L, 4_683_828_506_676L,
                4_683_828_521_646L, 4_683_828_535_663L);

        FtdcMetricGroups.Group network = series.groups(id).stream()
                .filter(group -> group.name().equals("network")).findFirst().orElseThrow();
        FtdcGroupSeriesResult networkSeries = series.groupSeries(id, network.groupId(),
                new FtdcSeriesQuery(null, null, 1_200, FtdcSeriesQuery.View.RAW));
        assertThat(networkSeries.series()).extracting(FtdcSeriesResult::path)
                .contains("serverStatus/network/bytesIn", "serverStatus/network/bytesOut");
        assertThat(networkSeries.series()).allSatisfy(item -> assertThat(item.values()).hasSizeLessThanOrEqualTo(1_200));

        Set<String> coreNames = Set.of(
                "connections", "network", "opcounters", "mem",
                "globalLock/currentQueue", "globalLock/activeClients",
                "metrics/document", "metrics/queryExecutor", "metrics/cursor", "metrics/operation",
                "wiredTiger/cache/bytes", "wiredTiger/cache/pages", "wiredTiger/cache/eviction",
                "wiredTiger/transaction/rollback");
        List<FtdcMetricGroups.Group> coreGroups = series.groups(id).stream()
                .filter(group -> coreNames.contains(group.name())
                        || group.name().startsWith("systemMetrics/cpu")
                        || group.name().startsWith("systemMetrics/memory")
                        || group.name().startsWith("systemMetrics/disks/"))
                .toList();
        assertThat(coreGroups).hasSize(19);
        for (FtdcMetricGroups.Group coreGroup : coreGroups) {
            FtdcGroupSeriesResult result = series.groupSeries(id, coreGroup.groupId(),
                    new FtdcSeriesQuery(null, null, 1_200, FtdcSeriesQuery.View.RAW));
            assertThat(result.series()).allSatisfy(item -> {
                assertThat(item.timestamps()).hasSameSizeAs(item.values());
                assertThat(item.values()).hasSizeLessThanOrEqualTo(1_200);
            });
        }
    }

    @Test
    void completesTwentyFileTaskAndAllQueryModesWhenConfigured() throws Exception {
        String configured = System.getProperty("ftdc.real.sample");
        Assumptions.assumeTrue(configured != null && Files.isRegularFile(Path.of(configured)));
        Path sample = Path.of(configured);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        FileFtdcTaskRepository repository = new FileFtdcTaskRepository(dataDirectory, mapper);
        FtdcOperationGate gate = new FtdcOperationGate();
        String id = "twenty-files";
        Path source = Files.createDirectories(repository.workDirectory(id).resolve("source"));
        List<FtdcTaskInputFile> files = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String storedName = "%04d-%s".formatted(i, sample.getFileName());
            Files.copy(sample, source.resolve(storedName));
            files.add(new FtdcTaskInputFile(sample.getFileName().toString(), storedName, Files.size(sample)));
        }
        long totalBytes = Files.size(sample) * 20;
        repository.saveTask(new FtdcTask(id, "twenty", FtdcTaskStatus.QUEUED, System.currentTimeMillis(), null, null,
                files, totalBytes, 0, 0, 0, 0, null, null, null));

        new FtdcTaskRunner(repository, gate, mapper).run(id);
        FtdcTask completed = repository.findTask(id).orElseThrow();
        assertThat(completed.status()).withFailMessage(String.valueOf(completed.errorMessage())).isEqualTo(FtdcTaskStatus.COMPLETED);
        assertThat(completed.blockCount()).isEqualTo(1_920);
        assertThat(completed.sampleCount()).isEqualTo(576_000);

        String startMetric = repository.readCatalog(id).metrics().stream()
                .filter(metric -> metric.path().equals("start")).findFirst().orElseThrow().metricId();
        FtdcSeriesService series = new FtdcSeriesService(repository, gate);
        assertThat(series.series(id, startMetric, new FtdcSeriesQuery(null, null, 2_000, FtdcSeriesQuery.View.RAW))
                .timestamps()).hasSizeLessThanOrEqualTo(2_000);
        assertThat(series.page(id, startMetric, 0, 1_000).timestamps()).hasSize(1_000);
        new FtdcMetricExportService(series, gate).export(id, startMetric, OutputStream.nullOutputStream());
    }
}
