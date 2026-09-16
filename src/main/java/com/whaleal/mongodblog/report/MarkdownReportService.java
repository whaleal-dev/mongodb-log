package com.whaleal.mongodblog.report;

import com.whaleal.mongodblog.analysis.AnalysisSummary;
import com.whaleal.mongodblog.analysis.AggregateStat;
import com.whaleal.mongodblog.analysis.DurationBucketStat;
import com.whaleal.mongodblog.analysis.PatternStat;
import com.whaleal.mongodblog.analysis.SlowQueryRecord;
import com.whaleal.mongodblog.analysis.diagnostics.LogDiagnostics;
import com.whaleal.mongodblog.storage.TaskRepository;
import com.whaleal.mongodblog.task.AnalysisTask;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;

@Service
public class MarkdownReportService {
    private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern IPV6 = Pattern.compile(
            "(?i)(?<![0-9a-f:])(?:[0-9a-f]{0,4}:){2,}[0-9a-f:.]{0,15}(?:%[a-z0-9_.-]+)?(?![0-9a-f:])");
    private static final Pattern MONGODB_URI = Pattern.compile("(?i)mongodb(?:\\+srv)?://\\S+");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern SECRET = Pattern.compile("(?i)(password|passwd|token|secret)(\\s*[:=]\\s*)([^\\s,;]+)");
    private static final Pattern USER_IDENTITY = Pattern.compile(
            "(?i)\\b(user(?:name)?|principal)(\\s*[:=]\\s*)([^\\s,;]+)");

    private final TaskRepository repository;

    public MarkdownReportService(TaskRepository repository) {
        this.repository = repository;
    }

    public Report generate(String taskId) {
        AnalysisTask task = repository.findTask(taskId)
                .orElseThrow(() -> new NoSuchElementException("任务不存在：" + taskId));
        AnalysisSummary summary = repository.readSummary(taskId);
        LogDiagnostics diagnostics = repository.readDiagnostics(taskId).orElse(null);
        List<SlowQueryRecord> retainedSlowQueries = repository.readSlowQueries(taskId);
        RemoteAliases remoteAliases = new RemoteAliases();
        StringBuilder report = new StringBuilder(65_536);
        line(report, "# MongoDB 日志分析报告");
        line(report, "");
        line(report, "> 本报告由本地离线工具生成，适合交给 AI 进行二次分析。报告不包含完整原始日志、命令字面值、用户名或完整客户端 IP。");
        line(report, "");
        line(report, "## 任务概况");
        line(report, "");
        item(report, "任务名称", inline(task.name()));
        item(report, "任务状态", task.status().name());
        item(report, "日志文件数", Integer.toString(task.files().size()));
        item(report, "文件总大小", Long.toString(task.totalBytes()) + " B");
        appendFiles(report, task);
        item(report, "日志时间范围", time(summary.logStartEpochMillis()) + " ～ " + time(summary.logEndEpochMillis()));
        item(report, "日志总行数", Long.toString(summary.totalLines()));
        item(report, "慢查询数", Long.toString(summary.slowQueryCount()));
        item(report, "解析结果", "成功 " + summary.successLines() + "，部分解析 " + summary.partialLines()
                + "，失败 " + summary.failedLines() + "，跳过 " + summary.skippedLines());

        appendSlowQueries(report, summary, retainedSlowQueries, remoteAliases);
        appendDiagnostics(report, diagnostics);
        appendLimits(report, diagnostics);
        return new Report(fileName(task.name()), report.toString());
    }

    private void appendFiles(StringBuilder report, AnalysisTask task) {
        line(report, "");
        line(report, "### 日志文件");
        line(report, "");
        line(report, "| 文件名 | 大小 |");
        line(report, "|---|---:|");
        task.files().forEach(file -> line(report,
                "| " + cell(file.originalName()) + " | " + file.sizeBytes() + " B |"));
        line(report, "");
    }

    private void appendQuality(StringBuilder report, LogDiagnostics diagnostics) {
        line(report, "");
        line(report, "### 数据可信度");
        line(report, "");
        if (diagnostics == null) {
            line(report, "此历史任务未生成运行诊断；重新上传原日志后可获得字段覆盖、截断和事件分析。");
            return;
        }
        var quality = diagnostics.dataQuality();
        item(report, "结构化日志", Long.toString(quality.structuredLines()));
        item(report, "旧版文本日志", Long.toString(quality.legacyLines()));
        item(report, "截断日志", Long.toString(quality.truncatedLines()));
        item(report, "带标签日志", Long.toString(quality.taggedLines()));
        item(report, "文件内时间乱序", Long.toString(quality.outOfOrderLines()));
        if (!quality.serverVersions().isEmpty()) item(report, "检测到的服务器版本", joinCounts(quality.serverVersions()));
        if (!quality.services().isEmpty()) item(report, "服务角色", joinCounts(quality.services()));
        if (!diagnostics.slowQueries().fieldCoverage().isEmpty()) {
            line(report, "");
            line(report, "### 慢查询字段覆盖");
            line(report, "");
            line(report, "| 字段 | 有值样本 | 慢查询总数 |");
            line(report, "|---|---:|---:|");
            diagnostics.slowQueries().fieldCoverage().forEach((field, count) ->
                    line(report, "| " + cell(field) + " | " + count + " | " + diagnostics.slowQueries().total() + " |"));
        }
    }

    private void appendEvents(StringBuilder report, LogDiagnostics diagnostics) {
        line(report, "");
        line(report, "### 异常事件汇总");
        line(report, "");
        if (diagnostics == null) {
            line(report, "运行诊断不可用。");
            return;
        }
        item(report, "严重级别分布", joinCounts(diagnostics.severityCounts()));
        item(report, "组件分布", joinCounts(diagnostics.componentCounts()));
        if (diagnostics.abnormalEvents().isEmpty()) {
            line(report, "");
            line(report, "日志中未识别到 Fatal、Error 或 Warning 事件。");
            return;
        }
        line(report, "");
        line(report, "| 级别 | 组件 | ID | 事件 | 次数 | 首次 | 最后 |");
        line(report, "|---|---|---:|---|---:|---|---|");
        diagnostics.abnormalEvents().forEach(event -> line(report,
                "| " + cell(event.severity()) + " | " + cell(event.component()) + " | "
                        + (event.messageId() == null ? "-" : event.messageId()) + " | " + cell(redact(event.message()))
                        + " | " + event.count() + " | " + time(event.firstEpochMillis()) + " | "
                        + time(event.lastEpochMillis()) + " |"));
        if (diagnostics.abnormalEvents().stream().noneMatch(event -> !event.samples().isEmpty())) return;
        line(report, "");
        line(report, "### 异常事件脱敏样本");
        line(report, "");
        line(report, "| 事件 ID | 时间 | 样本 |");
        line(report, "|---:|---|---|");
        diagnostics.abnormalEvents().forEach(event -> event.samples().forEach(sample -> line(report,
                "| " + (event.messageId() == null ? "-" : event.messageId()) + " | "
                        + time(sample.timestampEpochMillis()) + " | " + cell(sample.message()) + " |")));
    }

    private void appendConnections(StringBuilder report, LogDiagnostics diagnostics) {
        line(report, "");
        line(report, "### 连接与客户端");
        line(report, "");
        if (diagnostics == null) {
            line(report, "运行诊断不可用。");
            return;
        }
        var connection = diagnostics.connections();
        item(report, "建立／结束连接", connection.accepted() + "／" + connection.ended());
        item(report, "认证成功／未认证连接／重新认证警告", connection.authenticationSucceeded() + "／"
                + connection.notAuthenticating() + "／" + connection.reauthenticationWarnings());
        item(report, "打开连接数", connection.connectionCountSamples() == 0
                ? "日志未提供（样本 0）"
                : "最小 " + nullable(connection.connectionCountMin()) + "，最大 "
                        + nullable(connection.connectionCountMax()) + "，平均 "
                        + decimal(connection.connectionCountAverage()) + "，样本 "
                        + connection.connectionCountSamples());
        item(report, "客户端应用 Top", connection.applications().isEmpty()
                ? "日志未提供" : joinCounts(connection.applications()));
        item(report, "Driver Top", connection.drivers().isEmpty()
                ? "日志未提供" : joinCounts(connection.drivers()));
        if (connection.topValuesApproximate()) line(report, "\n客户端 Top 统计使用有界重频算法，显示值为近似计数。");
    }

    private void appendReplication(StringBuilder report, LogDiagnostics diagnostics) {
        line(report, "");
        line(report, "### 复制集与网络");
        line(report, "");
        if (diagnostics == null || diagnostics.replicationEvents().isEmpty()) {
            line(report, diagnostics == null ? "运行诊断不可用。" : "未识别到相关事件。");
            return;
        }
        line(report, "| 类型代码 | 类型 | 组件 | ID | 事件 | 次数 | 首次 | 最后 |");
        line(report, "|---|---|---|---:|---|---:|---|---|");
        diagnostics.replicationEvents().forEach(event -> line(report,
                "| " + cell(event.type()) + " | " + cell(event.label()) + " | " + cell(event.component()) + " | "
                        + (event.messageId() == null ? "-" : event.messageId()) + " | " + cell(redact(event.message()))
                        + " | " + event.count() + " | " + time(event.firstEpochMillis()) + " | "
                        + time(event.lastEpochMillis()) + " |"));
    }

    private void appendSlowQueries(StringBuilder report, AnalysisSummary summary,
                                   List<SlowQueryRecord> retainedSlowQueries, RemoteAliases remoteAliases) {
        line(report, "");
        line(report, "## 慢查询分析");
        line(report, "");
        line(report, "### 解析与总体统计");
        line(report, "");
        item(report, "日志总行数", Long.toString(summary.totalLines()));
        item(report, "成功／部分解析／失败／跳过", summary.successLines() + "／" + summary.partialLines()
                + "／" + summary.failedLines() + "／" + summary.skippedLines());
        item(report, "慢查询总数", Long.toString(summary.slowQueryCount()));
        item(report, "慢查询总耗时", summary.totalSlowDurationMillis() + " ms");
        item(report, "HeartBeat Failed", Long.toString(summary.heartbeatFailures()));
        item(report, "CPU 数据", summary.cpuAvailable() ? "日志已提供 cpuNanos" : "日志未提供 cpuNanos");
        if (summary.parseErrors() != null && !summary.parseErrors().isEmpty()) {
            line(report, "");
            line(report, "#### 解析异常分类");
            line(report, "");
            line(report, "| 分类 | 行数 |");
            line(report, "|---|---:|");
            summary.parseErrors().forEach((reason, count) -> line(report, "| " + cell(reason) + " | " + count + " |"));
        }
        if (summary.durationDistribution() != null && !summary.durationDistribution().isEmpty()) {
            line(report, "");
            line(report, "### 耗时分布");
            line(report, "");
            line(report, "| 区间 | 数量 | 占比 | 总耗时 | 平均耗时 | 最大耗时 |");
            line(report, "|---|---:|---:|---:|---:|---:|");
            for (DurationBucketStat bucket : summary.durationDistribution()) {
                line(report, "| " + cell(bucket.label()) + " | " + bucket.count() + " | "
                        + decimal(bucket.percentage()) + "% | " + bucket.totalDurationMillis() + " ms | "
                        + decimal(bucket.averageDurationMillis()) + " ms | " + bucket.maxDurationMillis() + " ms |");
            }
        }

        boolean currentSummary = summary.patternStats() != null;
        if (currentSummary) {
            appendAggregateTable(report, "客户端统计 · Top 20", "客户端", summary.remotes(),
                    remoteAliases, summary.cpuAvailable());
        } else {
            appendHistoricalSection(report, "客户端统计 · Top 20");
        }
        appendAggregateTable(report, "操作类型统计", "操作类型", summary.operations(), null, summary.cpuAvailable());
        if (currentSummary) {
            appendAggregateTable(report, "集合统计 · Top 20", "Namespace", summary.namespaces(),
                    null, summary.cpuAvailable());
        } else {
            appendHistoricalSection(report, "集合统计 · Top 20");
        }
        appendNamespaceResponses(report, summary.namespaceResponseBytes());
        if (currentSummary) {
            appendAggregateTable(report, "执行计划分布", "执行计划", summary.plans(),
                    null, summary.cpuAvailable());
        } else {
            appendHistoricalSection(report, "执行计划分布");
        }
        appendCpu(report, summary);
        appendConnectionAverages(report, summary);
        appendPatterns(report, summary);
        appendPatternSamples(report, summary.patternStats(), remoteAliases);
        appendRetainedSlowQueries(report, retainedSlowQueries, remoteAliases);
    }

    private void appendHistoricalSection(StringBuilder report, String title) {
        line(report, "");
        line(report, "### " + title);
        line(report, "");
        line(report, "此历史任务未保存新版统计，请重新上传分析。");
    }

    private void appendAggregateTable(StringBuilder report, String title, String keyLabel,
                                      Map<String, AggregateStat> values, RemoteAliases remoteAliases,
                                      boolean cpuAvailable) {
        line(report, "");
        line(report, "### " + title);
        line(report, "");
        if (values == null || values.isEmpty()) {
            line(report, "日志未提供相关数据。");
            return;
        }
        line(report, "| " + keyLabel + " | 次数 | 总耗时 | 平均耗时 | 最小耗时 | 最大耗时 | 响应字节 | CPU 纳秒 |");
        line(report, "|---|---:|---:|---:|---:|---:|---:|---:|");
        values.forEach((key, stat) -> line(report, "| "
                + cell(remoteAliases == null ? key : remoteAliases.alias(key)) + " | " + stat.count() + " | "
                + stat.totalDurationMillis() + " | " + decimal(stat.averageDurationMillis()) + " | "
                + stat.minDurationMillis() + " | " + stat.maxDurationMillis() + " | "
                + stat.totalResponseBytes() + " | "
                + (cpuAvailable ? stat.totalCpuNanos() : "日志未提供") + " |"));
    }

    private void appendNamespaceResponses(StringBuilder report, Map<String, Long> responses) {
        line(report, "");
        line(report, "### Namespace 响应量");
        line(report, "");
        if (responses == null) {
            line(report, "此历史任务未保存完整 Namespace 响应量，请重新上传分析。");
            return;
        }
        if (responses.isEmpty()) {
            line(report, "暂无响应数据量。");
            return;
        }
        line(report, "| Namespace | 响应字节 |");
        line(report, "|---|---:|");
        responses.forEach((namespace, bytes) -> line(report, "| " + cell(namespace) + " | " + bytes + " |"));
    }

    private void appendCpu(StringBuilder report, AnalysisSummary summary) {
        line(report, "");
        line(report, "### CPU 耗时");
        line(report, "");
        if (!summary.cpuAvailable()) {
            line(report, "日志未提供 cpuNanos。");
        } else {
            appendAggregateRows(report, "操作类型|Namespace", summary.cpuByOperationNamespace());
        }
        line(report, "");
        line(report, "### CPU 耗时比例分布");
        line(report, "");
        if (!summary.cpuAvailable()) {
            line(report, "日志未提供 cpuNanos。");
            return;
        }
        if (summary.cpuByOperationBuckets() == null) {
            line(report, "此历史任务未保存 CPU 区间统计。");
            return;
        }
        if (summary.cpuByOperationBuckets().values().stream().allMatch(Map::isEmpty)) {
            line(report, "暂无有效 CPU 比例样本。");
            return;
        }
        line(report, "| 操作类型 | CPU 比例区间 | 次数 |");
        line(report, "|---|---|---:|");
        summary.cpuByOperationBuckets().forEach((operation, buckets) -> {
            if (buckets.isEmpty()) {
                line(report, "| " + cell(operation) + " | 无样本 | 0 |");
            } else {
                buckets.forEach((bucket, count) -> line(report,
                        "| " + cell(operation) + " | " + cpuBucket(bucket) + " | " + count + " |"));
            }
        });
    }

    private void appendAggregateRows(StringBuilder report, String keyLabel, Map<String, AggregateStat> values) {
        if (values == null || values.isEmpty()) {
            line(report, "日志未提供相关数据。");
            return;
        }
        line(report, "| " + cell(keyLabel) + " | 次数 | 总耗时 | 平均耗时 | 最小耗时 | 最大耗时 | 响应字节 | CPU 纳秒 |");
        line(report, "|---|---:|---:|---:|---:|---:|---:|---:|");
        values.forEach((key, stat) -> line(report, "| " + cell(key) + " | " + stat.count() + " | "
                + stat.totalDurationMillis() + " | " + decimal(stat.averageDurationMillis()) + " | "
                + stat.minDurationMillis() + " | " + stat.maxDurationMillis() + " | "
                + stat.totalResponseBytes() + " | " + stat.totalCpuNanos() + " |"));
    }

    private void appendConnectionAverages(StringBuilder report, AnalysisSummary summary) {
        line(report, "");
        line(report, "### 每小时平均连接数");
        line(report, "");
        if (summary.averageConnections() == null || summary.averageConnections().isEmpty()) {
            line(report, "日志未提供可解析的连接数事件。");
            return;
        }
        line(report, "| UTC 小时 | 样本数 | 平均连接数 |");
        line(report, "|---|---:|---:|");
        summary.averageConnections().forEach(sample -> line(report, "| " + time(sample.timestampEpochMillis())
                + " | " + sample.sampleCount() + " | " + decimal(sample.averageConnections()) + " |"));
    }

    private void appendPatterns(StringBuilder report, AnalysisSummary summary) {
        line(report, "");
        line(report, "### 查询模式 Top 50");
        line(report, "");
        if (summary.patternStats() == null) {
            line(report, "此历史任务未保存新版统计，请重新上传分析。");
            return;
        }
        if (summary.patternStats().isEmpty()) {
            line(report, "暂无可识别的查询模式。");
            return;
        }
        line(report, "| 集合 | 操作 | 次数 | 总耗时 | 平均耗时 | 最小耗时 | 最大耗时 | CPU 纳秒 | 执行计划 | 最慢查询 ID | 规范化查询模式 |");
        line(report, "|---|---|---:|---:|---:|---:|---:|---:|---|---|---|");
        for (PatternStat pattern : summary.patternStats()) {
            line(report, "| " + cell(pattern.namespace()) + " | " + cell(pattern.operation()) + " | "
                    + pattern.count() + " | " + pattern.totalDurationMillis() + " | "
                    + decimal(pattern.averageDurationMillis()) + " | " + pattern.minDurationMillis() + " | "
                    + pattern.maxDurationMillis() + " | " + (pattern.cpuAvailable() ? pattern.totalCpuNanos() : "-")
                    + " | " + cell(pattern.planSummary()) + " | " + cell(slowestQueryId(pattern)) + " | "
                    + cell(pattern.pattern()) + " |");
        }
    }

    private String slowestQueryId(PatternStat pattern) {
        if (pattern.slowestQueryId() != null) return pattern.slowestQueryId();
        return pattern.slowestQuery() == null ? null : pattern.slowestQuery().queryId();
    }

    private void appendPatternSamples(StringBuilder report, List<PatternStat> patterns,
                                      RemoteAliases remoteAliases) {
        if (patterns == null || patterns.stream().noneMatch(pattern -> pattern.slowestQuery() != null)) return;
        line(report, "");
        line(report, "### 查询模式最慢样本 · Top 50");
        line(report, "");
        line(report, "每个查询模式独立保留的最慢样本，可能不在全局 Top 5000 中；原始日志与 attributes 不导出。");
        line(report, "");
        line(report, "| 查询 ID | 时间 | 文件序号 | 行号 | 客户端 | Namespace | 操作 | 耗时 ms | CPU ns | 响应字节 | 执行计划 | 规范化查询模式 |");
        line(report, "|---|---|---:|---:|---|---|---|---:|---:|---:|---|---|");
        patterns.stream().map(PatternStat::slowestQuery).filter(java.util.Objects::nonNull)
                .forEach(query -> appendSlowQueryRow(report, query, remoteAliases));
    }

    private void appendRetainedSlowQueries(StringBuilder report, List<SlowQueryRecord> slowQueries,
                                           RemoteAliases remoteAliases) {
        line(report, "");
        line(report, "### 最慢查询明细 · Top 5000");
        line(report, "");
        if (slowQueries.isEmpty()) {
            line(report, "没有保留的慢查询明细。");
            return;
        }
        line(report, "| 查询 ID | 时间 | 文件序号 | 行号 | 客户端 | Namespace | 操作 | 耗时 ms | CPU ns | 响应字节 | 执行计划 | 规范化查询模式 |");
        line(report, "|---|---|---:|---:|---|---|---|---:|---:|---:|---|---|");
        slowQueries.forEach(query -> appendSlowQueryRow(report, query, remoteAliases));
    }

    private void appendSlowQueryRow(StringBuilder report, SlowQueryRecord query, RemoteAliases remoteAliases) {
        line(report, "| " + cell(query.queryId()) + " | "
                + time(query.timestampEpochMillis()) + " | " + query.fileIndex() + " | " + query.lineNumber()
                + " | " + cell(remoteAliases.alias(query.remote())) + " | " + cell(query.namespace()) + " | "
                + cell(query.operation()) + " | " + query.durationMillis() + " | "
                + (query.cpuNanos() == null ? "-" : query.cpuNanos()) + " | "
                + (query.responseLength() == null ? "-" : query.responseLength()) + " | "
                + cell(query.planSummary()) + " | " + cell(query.queryPattern()) + " |");
    }

    private void appendDiagnostics(StringBuilder report, LogDiagnostics diagnostics) {
        line(report, "");
        line(report, "## 运行诊断");
        line(report, "");
        if (diagnostics == null) {
            line(report, "此历史任务未生成运行诊断；重新上传原日志后可获得完整数据。");
            return;
        }
        line(report, "### 运行诊断概览");
        line(report, "");
        item(report, "诊断 Schema 版本", Integer.toString(diagnostics.schemaVersion()));
        item(report, "诊断已解析行数", Long.toString(diagnostics.totalParsedLines()));
        item(report, "时间桶宽度", diagnostics.timelineBucketMillis() + " ms");
        item(report, "异常日志 W／E／F", Long.toString(abnormalCount(diagnostics)));
        item(report, "连接建立／结束", diagnostics.connections().accepted() + "／" + diagnostics.connections().ended());
        item(report, "复制集事件", diagnostics.replicationEvents().stream()
                .mapToLong(LogDiagnostics.ReplicationEventStat::count).sum()
                + " 次，" + diagnostics.replicationEvents().size() + " 种");
        item(report, "慢查询线索", diagnostics.slowQueries().insights().size()
                + " 条，从 " + diagnostics.slowQueries().total() + " 条慢查询筛选");
        appendQuality(report, diagnostics);
        appendEvents(report, diagnostics);
        appendDiagnosticTimeline(report, diagnostics);
        appendConnections(report, diagnostics);
        appendReplication(report, diagnostics);
        appendDiagnosticSlowQueries(report, diagnostics);
    }

    private long abnormalCount(LogDiagnostics diagnostics) {
        return diagnostics.severityCounts().getOrDefault("W", 0L)
                + diagnostics.severityCounts().getOrDefault("E", 0L)
                + diagnostics.severityCounts().getOrDefault("F", 0L);
    }

    private void appendDiagnosticTimeline(StringBuilder report, LogDiagnostics diagnostics) {
        line(report, "");
        line(report, "### 异常事件时间线");
        line(report, "");
        if (diagnostics.timeline().isEmpty()) {
            line(report, "没有可用时间桶。");
            return;
        }
        line(report, "| 时间桶 | 警告 | 错误 | 致命错误 | 建立连接 | 结束连接 | 复制集事件 |");
        line(report, "|---|---:|---:|---:|---:|---:|---:|");
        diagnostics.timeline().forEach(bucket -> line(report, "| " + time(bucket.epochMillis()) + " | "
                + bucket.warnings() + " | " + bucket.errors() + " | " + bucket.fatals() + " | "
                + bucket.connectionsAccepted() + " | " + bucket.connectionsEnded() + " | "
                + bucket.replicationEvents() + " |"));
    }

    private void appendDiagnosticSlowQueries(StringBuilder report, LogDiagnostics diagnostics) {
        var slow = diagnostics.slowQueries();
        line(report, "");
        line(report, "### 慢查询效率线索");
        line(report, "");
        item(report, "诊断慢查询总数", Long.toString(slow.total()));
        item(report, "COLLSCAN", Long.toString(slow.collscanCount()));
        item(report, "扫描文档／返回比不低于 100", Long.toString(slow.highDocumentScanRatioCount()));
        item(report, "扫描索引键／返回比不低于 100", Long.toString(slow.highIndexScanRatioCount()));
        item(report, "零返回但扫描量高", Long.toString(slow.zeroReturnHighScanCount()));
        item(report, "磁盘读取耗时占比不低于 50％", Long.toString(slow.storageDominantCount()));
        item(report, "查询规划耗时占比不低于 50％", Long.toString(slow.planningDominantCount()));
        item(report, "写关注／Flow Control／锁等待", slow.writeConcernWaitCount() + "／"
                + slow.flowControlWaitCount() + "／" + slow.lockWaitCount());
        item(report, "分片响应／鉴权缓存／执行队列／Oplog 提交等待", slow.remoteOpWaitCount() + "／"
                + slow.authorizationWaitCount() + "／" + slow.queueWaitCount() + "／" + slow.oplogSlotWaitCount());
        item(report, "额外排序／使用临时磁盘／执行落盘", slow.hasSortStageCount() + "／"
                + slow.usedDiskCount() + "／" + slow.spillCount());
        item(report, "不同查询形状", Long.toString(slow.distinctShapeCount()));
        line(report, "");
        line(report, "### Query Framework 分布");
        line(report, "");
        line(report, slow.queryFrameworks().isEmpty() ? "日志未提供 queryFramework。" : joinCounts(slow.queryFrameworks()));
        if (slow.insights().isEmpty()) return;
        line(report, "");
        line(report, "### 重点慢查询诊断明细");
        line(report, "");
        line(report, "| 时间 | Namespace | 操作 | 耗时 ms | 计划 | Shape Hash | Plan Cache Key | 文档 | 索引键 | 返回 | 文档／返回 | 索引键／返回 | 磁盘读取 ms | 规划 ms | 写关注 ms | Flow Control ms | 锁等待 ms | 线索 | 规范化查询模式 |");
        line(report, "|---|---|---|---:|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|");
        slow.insights().forEach(insight -> line(report, "| " + time(insight.timestampEpochMillis()) + " | "
                + cell(insight.namespace()) + " | " + cell(insight.operation()) + " | " + insight.durationMillis()
                + " | " + cell(insight.planSummary()) + " | " + cell(insight.shapeHash()) + " | "
                + cell(insight.planCacheKey()) + " | " + nullable(insight.docsExamined()) + " | "
                + nullable(insight.keysExamined()) + " | " + nullable(insight.returned()) + " | "
                + decimal(insight.documentsPerReturned()) + " | " + decimal(insight.keysPerReturned()) + " | "
                + decimal(insight.storageReadMillis()) + " | " + decimal(insight.planningMillis()) + " | "
                + nullable(insight.writeConcernWaitMillis()) + " | " + decimal(insight.flowControlWaitMillis())
                + " | " + decimal(insight.lockWaitMillis()) + " | "
                + cell(String.join("、", insight.reasons())) + " | " + cell(insight.queryPattern()) + " |"));
    }

    private void appendLimits(StringBuilder report, LogDiagnostics diagnostics) {
        line(report, "");
        line(report, "## 分析边界与 AI 使用说明");
        line(report, "");
        line(report, "- 本报告只反映所上传日志中的可见事件；慢查询数量受 `slowms`、`slowOpSampleRate`、日志级别和过滤条件影响。");
        line(report, "- COLLSCAN、扫描比、等待时间和时间相关事件是排查线索，不等同于已确定根因。");
        line(report, "- 日志不能提供完整索引清单、实时复制延迟、缓存命中率或磁盘吞吐；这些信息应结合 FTDC 或在线状态核对。");
        if (diagnostics != null && diagnostics.dataQuality().truncatedLines() > 0) {
            line(report, "- 存在截断日志，命令或属性可能不完整。");
        }
        line(report, "- 建议 AI 先按时间关联异常、复制集、连接峰值和慢查询，再给出需要人工或在线命令验证的假设。");
    }

    private String joinCounts(Map<String, Long> counts) {
        return counts.entrySet().stream().map(entry -> inline(entry.getKey()) + "=" + entry.getValue())
                .reduce((left, right) -> left + "，" + right).orElse("无");
    }

    private String cell(Object value) {
        if (value == null) return "-";
        return inline(String.valueOf(value)).replace("|", "\\|");
    }

    private String inline(String value) {
        return redact(value).replace("\r", " ").replace("\n", " ");
    }

    private String redact(String value) {
        if (value == null) return "";
        String redacted = MONGODB_URI.matcher(value).replaceAll("[MongoDB URI]");
        redacted = IPV6.matcher(redacted).replaceAll("[IP]");
        redacted = IPV4.matcher(redacted).replaceAll("[IP]");
        redacted = EMAIL.matcher(redacted).replaceAll("[EMAIL]");
        redacted = USER_IDENTITY.matcher(redacted).replaceAll("$1$2[REDACTED]");
        return SECRET.matcher(redacted).replaceAll("$1$2[REDACTED]");
    }

    private String decimal(Number value) {
        if (value == null) return "-";
        return BigDecimal.valueOf(value.doubleValue()).setScale(3, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private String nullable(Number value) {
        return value == null ? "-" : value.toString();
    }

    private String cpuBucket(String bucket) {
        try {
            long end = Long.parseLong(bucket);
            return end == 0 ? "0％ ≤ CPU 比例 < 1％" : (end - 9) + "％ ≤ CPU 比例 < " + (end + 1) + "％";
        } catch (NumberFormatException ignored) {
            return cell(bucket);
        }
    }

    private String time(Long epochMillis) {
        return epochMillis == null ? "日志未提供" : Instant.ofEpochMilli(epochMillis).toString();
    }

    private void item(StringBuilder report, String label, String value) {
        line(report, "- " + label + "：" + value);
    }

    private void line(StringBuilder report, String line) {
        report.append(line).append('\n');
    }

    private String fileName(String taskName) {
        String safe = taskName == null ? "mongodb-log" : inline(taskName)
                .replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        if (safe.isBlank()) safe = "mongodb-log";
        if (safe.length() > 80) safe = safe.substring(0, 80);
        return safe + "-mongodb-analysis.md";
    }

    public record Report(String fileName, String content) {
    }

    private static final class RemoteAliases {
        private final Map<String, String> aliases = new LinkedHashMap<>();

        private String alias(String remote) {
            if (remote == null || remote.isBlank() || remote.equalsIgnoreCase("unknown")) return "unknown";
            return aliases.computeIfAbsent(remote, ignored -> "客户端 " + (aliases.size() + 1) + "（IP 已脱敏）");
        }
    }
}
