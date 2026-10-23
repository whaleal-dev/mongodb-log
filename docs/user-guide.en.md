# MongoDB Log & Metric Analyzer: User Guide and Limitations

[简体中文](user-guide.md) | [English](user-guide.en.md) | [Back to project home](../README.en.md)

This guide is for operators using a packaged release. It explains how to start the application, analyze MongoDB logs and FTDC metric files, manage local data, and interpret the results safely.

> The current web interface and exported Markdown reports are in Simplified Chinese. The instructions below show Chinese labels followed by English descriptions.

## 1. What This Tool Does

MongoDB Log & Metric Analyzer is a local, offline file-analysis application with two workspaces:

- `MongoDB Log` parses plain-text, structured JSON, legacy single-line, and `.gz` MongoDB logs. It produces slow-query statistics, runtime diagnostics, and a sanitized Markdown report.
- `MongoDB Metric` parses MongoDB `diagnostic.data/metrics.*` FTDC files. It builds a local index and then retrieves bounded time series by metric group.

The application does not connect to a MongoDB server. Runtime use does not require MongoDB, Node.js, Nacos, S3, an AI service, or Internet access. The server listens only on `127.0.0.1:18080` by default, and all parsing and field interpretation happen on the local computer.

See the [project overview](../README.en.md) for version information, downloads, and source builds, and the [project roadmap](../ROADMAP.md) for recorded tests, conditional skips, and historical acceptance results. Historical validation does not mean that it has been repeated for the current source version.

## 2. Requirements

- Docker: Docker Desktop or Docker Engine, with at least 3 GB of memory available to the container recommended.
- JAR: Java 17 or newer, plus a release directory containing both `mongodb-log-analyzer.jar` and the `scripts` directory.
- Enough free disk space for analysis results, temporary files, and retained Metric file copies.
- The Docker image and launchers use `-Xms128m -Xmx2g`. The 2 GB value limits only the Java heap; the container or process also uses native and metaspace memory.

Expected layout when using the JAR release:

```text
mongodb-log-analyzer/
├── mongodb-log-analyzer.jar
└── scripts/
    ├── start.command
    ├── start.sh
    └── start.bat
```

## 3. Start and Stop the Application

### Docker

The public image supports `linux/amd64` and `linux/arm64`. Run:

```bash
docker run -d \
  --name mongodb-log-analyzer \
  --restart unless-stopped \
  --memory=3g \
  -p 127.0.0.1:18080:18080 \
  -v mongodb-log-analyzer-data:/app/data \
  whaleal/mongodb-log-analyzer:0.1.3
```

Open `http://127.0.0.1:18080` after startup. Use `docker stop mongodb-log-analyzer` to stop the application and `docker start mongodb-log-analyzer` to start it again.

Task data is stored in the `mongodb-log-analyzer-data` named volume. You can recreate the container when upgrading the image, but keep mounting the same volume and avoid commands such as `docker compose down -v` that delete it. The application has no accounts or authorization, so do not expose the container port directly to the public Internet.

### JAR release: macOS

Download and extract a launcher package from [GitHub Releases](https://github.com/whaleal-dev/mongodb-log/releases). The first time, open a terminal in the extracted directory and run:

```bash
chmod +x scripts/start.command scripts/start.sh
```

Then double-click `scripts/start.command`. You can also run `./scripts/start.sh` from a terminal.

### JAR release: Linux

Run:

```bash
./scripts/start.sh
```

### JAR release: Windows

Double-click `scripts\start.bat`.

After a successful start, the launcher normally opens `http://127.0.0.1:18080`. Open that address manually if no browser window appears.

No dialog appears on first use. After 24 elapsed hours, a visible page automatically shows the author introduction only once, linking to the author's profile, the Whaleal MongoDB product, and `whaleal-dev` public repositories. The top-right `关于作者` button opens it at any time. Dismiss it with `继续使用`, the close button, the backdrop, or `Esc`. Manually viewing it, or having seen the previous version, suppresses the automatic reminder. First-use time and viewed status are saved in the browser across page reloads, application restarts, and workspace switches. The delay means elapsed time, not cumulative active use. Links open in a new tab only after a click; the dialog does not automatically load external content or upload analysis data.

Clearing site data, changing browsers, or using a different application address resets the delay. When browser storage is unavailable, first-use time and viewed status can only be maintained within the current page.

To stop the application, close the launcher window or press `Ctrl+C` in its terminal. If the process exits during Log analysis or Metric indexing, the interrupted task is marked as failed after the next startup and is not resumed. Working copies may remain; delete the failed task from its task list, then upload the files again.

### Theme color

Click `主题色` (Theme color) at the top right to choose one of four presets: `MongoDB 绿`, `经典蓝`, `雾岚紫`, or `石墨灰`. You can also use the color picker or enter a six-digit HEX color such as `#347961`. Valid colors apply immediately and are saved in the current browser. Incomplete or invalid input keeps the current theme; leaving the field or pressing Enter shows a format error.

`完成` (Done) and closing the dialog only close the settings; they do not undo the applied color. `恢复默认` (Restore default) immediately restores the default green. If saving fails, the preview remains active and `重试保存` (Retry save) tries again. The preference belongs to the current browser and application address, is separate from task data, and resets when site data is cleared.

The page remains light. Success, error, warning, memory-alert, and multi-series category colors are independent of the theme. The color algorithm targets `4.5:1` for covered theme text and primary-button text, and `3:1` for focus indicators and single-series chart marks. These targets are not a site-wide accessibility audit or certification.

## 4. Use the MongoDB Log Workspace

### 4.1 Create an analysis task

1. Select `MongoDB Log` at the top of the page.
2. Optionally enter a task name. If left empty, the first file name becomes the default task name.
3. Click `选择日志文件` (Select log files).
4. Choose up to 20 non-empty plain-text, structured JSON, legacy single-line, or `.gz` log files. `.zip`, `.bz2`, `.xz`, and `.7z` archives are not supported. The request also remains subject to the 12 GB server-side upload limit.
5. Click `开始分析` (Start analysis).

The files are streamed in the order selected and combined into one task. The page displays queued and running progress. Log analyses and Metric indexing tasks share one background worker thread, so a later Log or indexing task waits instead of competing with an active task for memory.

### 4.2 Review the results

Click `查看分析结果` (View analysis results) in the task list. The result page has two tabs:

- `慢查询分析` (Slow Query Analysis) shows failed operations, clients, operation types, namespaces, latency distribution, query patterns, execution plans, CPU data, connection counts, and slow-query scatter plots.
- `运行诊断` (Runtime Diagnostics) shows abnormal log activity, connections and authentication, client applications and drivers, replica-set and network events, slow-query efficiency signals, and structured-field coverage.

Useful interactions:

- Switch a chart to its data-table view when exact values are needed.
- Use the question-mark icon beside a metric name to see its unit, range, and aggregation rules.
- Click a point in a slow-query scatter plot to inspect parsed fields, locally generated investigation hints, the retained record, and its original log text.
- The Top 50 query patterns initially appear by frequency. Clicking a column header only reorders the 50 already selected patterns; it does not change which patterns belong to the Top 50.
- Above the pattern table, search namespaces or query fields, select an operation type, and search plan text such as `COLLSCAN`. Text searches ignore case and surrounding whitespace; all active conditions must match. The table shows the matching count, and `重置` clears the filters. Filtering only narrows the already selected Top 50, resets when switching tasks, and does not change the task aggregates exported in the report.
- Drag the bottom-right corner of a metric panel to resize it. Layout is stored in the current browser and can be cleared with `恢复默认布局` (Restore default layout).
- If parsing failed for some lines, the result page reports failed, partial, and skipped counts. Statistics include only fields that were extracted successfully.

The fixed latency buckets are `<100ms`, `100ms-500ms`, `500ms-1s`, `1s-3s`, `3s-10s`, `10s-30s`, `30s-60s`, and `>=60s`. Boundaries are left-inclusive and right-exclusive, and the distribution covers all detected slow queries.

### 4.3 Export a Markdown report

Click `导出 AI 分析报告` (Export AI analysis report) to download a Markdown report for the current task. It includes aggregate statistics, normalized query patterns, and sanitized diagnostic information for human review or optional analysis with an AI tool.

The application does not automatically send the report anywhere. It excludes full commands, complete raw logs, and attributes. During export it masks MongoDB URIs, IPv4 and IPv6 addresses, email addresses, and values explicitly labeled with `user`, `username`, `principal`, `password`, `passwd`, `token`, or `secret`. Task names, file names, namespaces, normalized query patterns, execution plans, and other aggregate fields can still appear, so review the exported file against your organization's data-security requirements before sharing it.

Report sanitization does not anonymize the local task directory. Retained slow-query details contain original log text, attributes, and potentially command literals. FTDC copies are also original diagnostic data; handle them with the same care as source files. Since `0.1.2`, diagnostic messages are sanitized before being saved, but upgrading does not rewrite results already saved by older versions. Sanitization covers recognized formats, including JSON key-value pairs and quoted identity or credential values; it does not guarantee anonymity for arbitrary free text.

Diagnostics are saved separately. Missing historical diagnostics or a failure to save diagnostics does not invalidate a completed slow-query analysis. The page reports unavailable diagnostics, and the report falls back to available aggregates. For complete diagnostics, check disk space and permissions, then upload the source files again.

The web interface renders timestamps in the browser’s local timezone. Exported Markdown reports use UTC so that events can be compared consistently across systems.

## 5. Use the MongoDB Metric Workspace

### 5.1 Create an indexing task

1. Select `MongoDB Metric` at the top of the page.
2. Choose 1 to 20 `diagnostic.data/metrics.*` files.
3. Click `开始建立索引` (Start indexing).
4. Wait for the task to finish, then open its Metric analysis page.

The parser identifies FTDC files by content, not by extension. Empty files, truncated files, damaged compressed data, and files without a valid metric block are rejected with an explicit error.

### 5.2 Query metric groups

1. Search for and select one or more metric groups, or click `一键选择核心指标组` (Select core metric groups).
2. Click `查询所选指标组` (Query selected metric groups).
3. The application reads groups sequentially in the selected order. Each completed group appears immediately, and a failure in one group does not stop later groups.
4. Switch between `原始值` (Raw values) and `相邻差值` (Adjacent deltas), and between combined and per-metric charts as needed.
5. Collapse groups or hide all-zero metrics to reduce visual noise.

When the time between adjacent samples is significantly larger than the main sampling interval, the chart inserts a gap rather than drawing a misleading continuous line. Missing values appear as `-` in chart tooltips.

Metric indexing and metric-group queries share a fair sequential execution gate. A long queued state usually means another heavy operation is active; the queued operation continues automatically afterward.

When files contain the same timestamp, values from the file that appeared earlier in the upload order take precedence. Each metric returns at most 1,200 chart points, but minimum, maximum, average, and all-zero detection are calculated from every valid point in the requested range rather than from the downsampled output.

## 6. Tasks and Local Data

The application stores managed task data under the `data` directory relative to its startup directory.

```text
data/
├── tasks-index.json
├── tasks/<task-id>/
│   ├── metadata.json
│   ├── summary.json
│   ├── diagnostics.json
│   └── top-slow-queries.jsonl
├── work/<task-id>/
└── ftdc/
    ├── tasks-index.json
    ├── tasks/<task-id>/
    │   ├── metadata.json
    │   ├── catalog.json
    │   ├── blocks.idx
    │   └── source/<uploaded-file>
    └── work/<task-id>/
```

### Log data

- The source files selected by the user are never modified.
- Normal processing cleans working copies before completing or failing; a cleanup failure marks the task as failed. An interrupted process may leave copies behind. Restart recovery only marks that task as failed and does not delete those copies.
- The application does not persist every log line. It retains aggregate results, the globally slowest 5,000 query records, and one separate slowest sample for each selected Top 50 query pattern.
- These results remain until task deletion. Separate diagnostics retain aggregates, time buckets, and at most three message samples per category.

### Metric data

- The source FTDC files selected by the user are never modified.
- Uploaded copies, a metric catalog, and a binary block index are retained to support later queries.
- A fully expanded copy of every time-series value is not persisted, but the retained FTDC copies still consume disk space.
- Before each query, the retained source copy is checked against the file size and SHA-256 stored in the index. External modification causes the query to fail.

### Delete data

- Clicking `删除` (Delete) in a task list removes only application-managed data for that task. It does not delete the user's source files.
- Queued or active tasks cannot be deleted; wait for them to finish first.
- The clear-all action available from the memory indicator removes all terminal Log and Metric tasks plus their managed data after confirmation. If any task is active, the entire operation is rejected before anything is removed.
- After that active-task check, deletion proceeds task by task. A later permission or disk error does not roll back earlier deletions. The failed task's record remains so that deletion can be retried after fixing the environment.

## 7. Capabilities and Resource Limits

### 7.1 Runtime scope

- The application listens only on the local interface by default. It does not provide accounts, authorization, multi-user collaboration, remote access, or public deployment features.
- It is an offline file analyzer, not a MongoDB real-time monitor, alerting system, backup product, or database-administration client.
- Because it does not connect to MongoDB, it cannot inspect current index definitions, server configuration, live locks, live sessions, or state that was never written to the supplied files.
- The server-side limit for one upload request is 12 GB. Multipart form data adds a small overhead, so the total selected file size should remain slightly below that limit.

### 7.2 Log analysis limits

- Results depend on the fields present in the logs. If `cpuNanos`, connection counts, scan counts, or execution plans are absent, related metrics show no data rather than zero.
- Latency buckets, Top 20 aggregates, and Top 50 patterns are calculated from the full input. Scatter plots and globally retained query details are limited to the slowest 5,000 records.
- Each selected Top 50 query pattern has only one separately retained slowest sample; this is not a complete archive of all records for that pattern.
- Structured logs are parsed according to the fields implemented by the analyzer. Coverage counts include supported fields only. Unknown fields do not contribute to those statistics but may remain in retained slow-query attributes. Unrecognized lines do not contribute to valid statistics.
- Upgrading the application does not recalculate historical tasks. Re-upload source files to apply new parsing or diagnostic rules.

### 7.3 Metric analysis limits

- One task accepts at most 20 non-empty FTDC files.
- One metric group contains at most 200 metrics.
- Each metric chart returns at most 1,200 points. Chart points are bounded and downsampled, while `min`, `max`, `avg`, and all-zero detection are calculated from all valid source points.
- One FTDC block may declare at most 10,000,000 uncompressed bytes and 100,000 samples, with at most 1,000,000 metric-sample cells. Files exceeding these bounds are rejected.
- The product does not expose single-metric queries, raw-value pagination, or complete metric export.
- If a retained FTDC source copy is changed outside the application, integrity verification fails and a new task must be created.

### 7.4 Interpretation limits

The UI provides statistics, field explanations, and investigation leads. It does not determine root cause automatically. High latency, `COLLSCAN`, CPU share, or connection fluctuations are evidence, not conclusions. Confirm findings using workload context, indexes, query plans, MongoDB configuration, hardware data, and monitoring from the same period.

## 8. Troubleshooting

### The page does not open

Confirm that the launcher is still running and that `java -version` reports Java 17 or newer. If port `18080` is already in use, stop the process occupying that port and restart the analyzer.

### A `.gz` log fails to parse

Confirm that the file is actually GZip-compressed. Renaming another file format to use a `.gz` extension does not make it valid GZip data.

### CPU or connection charts have no data

The required fields were not present in the supplied logs. No data does not mean that CPU usage or the connection count was zero.

### A task remains queued for a long time

Log analyses and Metric indexing tasks share one background worker thread. Metric indexing and metric-group queries also share a fair sequential execution gate. A queued operation continues after the operation occupying its execution path completes.

### Metric tasks consume disk space

Metric tasks retain application-managed source copies and indexes. Delete an unneeded task from the Metric task list to reclaim that space; the user's original files are not affected.

### A Metric file is rejected

The parser validates BSON documents, zlib lengths, metric and sample bounds, RLE data, and compressed-stream termination. Empty files, truncation, damaged data, inconsistent declared lengths, and files without a valid metric block fail explicitly. Obtain a complete original MongoDB FTDC file and create a new task.

### A historical task is missing newer data

Historical results are not recalculated after an upgrade. Upload the original files again and create a new task to use the current parser and diagnostic rules.

## 9. Additional Documentation

- [English project overview](../README.en.md)
- [中文项目介绍](../README.md)
- [中文使用指南](user-guide.md)
- [Project design and APIs](design.md) (Chinese)
- [Project roadmap](../ROADMAP.md) (Chinese)
