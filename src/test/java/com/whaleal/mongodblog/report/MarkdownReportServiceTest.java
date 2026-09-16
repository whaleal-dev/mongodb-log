package com.whaleal.mongodblog.report;

import com.whaleal.mongodblog.analysis.AggregateStat;
import com.whaleal.mongodblog.analysis.AnalysisSummary;
import com.whaleal.mongodblog.analysis.PatternStat;
import com.whaleal.mongodblog.analysis.SlowQueryRecord;
import com.whaleal.mongodblog.analysis.diagnostics.LogDiagnostics;
import com.whaleal.mongodblog.storage.TaskRepository;
import com.whaleal.mongodblog.task.AnalysisTask;
import com.whaleal.mongodblog.task.TaskInputFile;
import com.whaleal.mongodblog.task.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarkdownReportServiceTest {

    @Test
    void exportsSafeDataBehindBothResultTabsAndRedactsSensitiveValues() {
        TaskRepository repository = mock(TaskRepository.class);
        SlowQueryRecord patternSample = new SlowQueryRecord(
                "pattern-slowest", 1, 77, 1_725_000_000_000L, "find", "sales.orders", 900,
                2_000_000L, 4_096L, "IXSCAN", "[2001:db8::2]:27017", "{status:?}",
                "RAW-COMMAND password=raw-secret", Map.of("command", "PRIVATE-LITERAL"));
        PatternStat pattern = new PatternStat(
                "sales.orders", "find", "{status:?}", "IXSCAN", 2, 1_200, 600,
                300, 900, 2_000_000, true, null, patternSample);
        AnalysisSummary summary = summary(
                List.of(pattern),
                Map.of("2001:db8::2", stat()),
                Map.of("sales.orders", stat()),
                Map.of("IXSCAN", stat()));
        LogDiagnostics diagnostics = new LogDiagnostics(
                1,
                2,
                Map.of("W", 1L),
                Map.of("NETWORK", 1L),
                List.of(new LogDiagnostics.EventStat(
                        "NETWORK|1", "W", "NETWORK", 1,
                        "Failure from 2001:db8::3 user=alice token=visible-token", 1,
                        1_725_000_000_000L, 1_725_000_000_000L,
                        List.of(new LogDiagnostics.EventSample(
                                1_725_000_000_000L,
                                "principal=bob source=2001:db8::4 secret=visible-secret")))),
                List.of(),
                3_600_000,
                new LogDiagnostics.ConnectionDiagnostics(
                        0, 0, 0, 0, 0, 0, null, null, null,
                        Map.of("client user=carol", 1L), Map.of(), false),
                List.of(new LogDiagnostics.ReplicationEventStat(
                        "HEARTBEAT_FAILURE", "心跳失败", "REPL", 2,
                        "Heartbeat failed for 2001:db8::5", 1,
                        1_725_000_000_000L, 1_725_000_000_000L)),
                emptySlowDiagnostics(),
                new LogDiagnostics.DataQualityDiagnostics(2, 0, 0, 0, 0, Map.of(), Map.of()));
        AnalysisTask task = new AnalysisTask(
                "task-1", "prod 2001:db8::1 user=owner", TaskStatus.COMPLETED,
                1_000, 1_100L, 1_200L,
                List.of(new TaskInputFile("mongo-2001:db8::1-user=owner.log", "0000.log", 512)),
                512, 512, 2, null, 1_725_000_000_000L, 1_725_000_001_000L);
        stub(repository, task, summary, diagnostics, List.of());

        MarkdownReportService.Report report = new MarkdownReportService(repository).generate(task.id());

        assertThat(report.content())
                .contains(
                        "### 日志文件",
                        "mongo-[IP]-user=[REDACTED]",
                        "任务状态：COMPLETED",
                        "### 查询模式最慢样本 · Top 50",
                        "pattern-slowest",
                        "| 操作类型\\|Namespace |",
                        "### 异常事件脱敏样本",
                        "异常日志 W／E／F：1",
                        "复制集事件：1 次，1 种",
                        "打开连接数：日志未提供（样本 0）",
                        "HEARTBEAT_FAILURE")
                .doesNotContain(
                        "2001:db8::1", "2001:db8::2", "2001:db8::3", "2001:db8::4", "2001:db8::5",
                        "alice", "bob", "carol", "owner", "visible-token", "visible-secret",
                        "RAW-COMMAND", "raw-secret", "PRIVATE-LITERAL");
        assertThat(report.fileName()).doesNotContain("2001:db8::1", "owner");
        assertThat(count(report.content(), "pattern-slowest")).isEqualTo(2);
    }

    @Test
    void doesNotRenderAnEmptyCpuBucketTableWhenCpuDataIsUnavailable() {
        TaskRepository repository = mock(TaskRepository.class);
        AnalysisSummary summary = new AnalysisSummary(
                1, 1, 0, 0, 0, 1, 500, 0, false,
                Map.of(), List.of(), Map.of("find", stat()), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), List.of(), Map.of(), Map.of(
                        "insert", Map.of(), "update", Map.of(), "delete", Map.of(), "find", Map.of()),
                List.of(), null, null);
        AnalysisTask task = new AnalysisTask(
                "no-cpu", "no-cpu", TaskStatus.COMPLETED, 1_000, 1_100L, 1_200L,
                List.of(new TaskInputFile("mongo.log", "0000.log", 20)),
                20, 20, 1, null, null, null);
        stub(repository, task, summary, null, List.of());

        String report = new MarkdownReportService(repository).generate(task.id()).content();

        assertThat(report)
                .contains("日志未提供 cpuNanos。", "### CPU 耗时比例分布\n\n日志未提供 cpuNanos。")
                .doesNotContain("| 操作类型 | CPU 比例区间 | 次数 |");
    }

    @Test
    void labelsHistoricalPartialAggregatesInsteadOfPresentingThemAsCurrentFullData() {
        TaskRepository repository = mock(TaskRepository.class);
        AnalysisSummary summary = summary(
                null,
                Map.of("legacy-client", stat()),
                Map.of("legacy.ns", stat()),
                Map.of("LEGACY_PLAN", stat()));
        AnalysisTask task = new AnalysisTask(
                "legacy", "legacy", TaskStatus.COMPLETED, 1_000, 1_100L, 1_200L,
                List.of(new TaskInputFile("legacy.log", "0000.log", 20)),
                20, 20, 1, null, null, null);
        stub(repository, task, summary, null, List.of());

        String report = new MarkdownReportService(repository).generate(task.id()).content();

        assertThat(report)
                .contains(
                        "客户端统计 · Top 20",
                        "集合统计 · Top 20",
                        "执行计划分布",
                        "查询模式 Top 50",
                        "此历史任务未保存新版统计，请重新上传分析。",
                        "legacy-op")
                .doesNotContain("legacy-client", "legacy.ns", "LEGACY_PLAN");
    }

    private AnalysisSummary summary(List<PatternStat> patterns,
                                    Map<String, AggregateStat> remotes,
                                    Map<String, AggregateStat> namespaces,
                                    Map<String, AggregateStat> plans) {
        return new AnalysisSummary(
                2, 2, 0, 0, 0, 2, 1_200, 0, true,
                Map.of(), List.of(), Map.of("legacy-op", stat()), namespaces, Map.of(), plans, remotes,
                Map.of("find|sales.orders", stat()), patterns, Map.of(), Map.of(), List.of(), null, null);
    }

    private AggregateStat stat() {
        return new AggregateStat(2, 1_200, 600, 300, 900, 4_096, 2_000_000);
    }

    private LogDiagnostics.SlowQueryDiagnostics emptySlowDiagnostics() {
        return new LogDiagnostics.SlowQueryDiagnostics(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                Map.of(), Map.of(), List.of());
    }

    private void stub(TaskRepository repository, AnalysisTask task, AnalysisSummary summary,
                      LogDiagnostics diagnostics, List<SlowQueryRecord> slowQueries) {
        when(repository.findTask(task.id())).thenReturn(Optional.of(task));
        when(repository.readSummary(task.id())).thenReturn(summary);
        when(repository.readDiagnostics(task.id())).thenReturn(Optional.ofNullable(diagnostics));
        when(repository.readSlowQueries(task.id())).thenReturn(slowQueries);
    }

    private int count(String value, String part) {
        return (value.length() - value.replace(part, "").length()) / part.length();
    }
}
