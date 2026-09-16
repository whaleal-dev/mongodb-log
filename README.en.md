# MongoDB Log & Metric Analyzer

[简体中文](README.md) | [English](README.en.md)

A local, offline analyzer for MongoDB logs and FTDC metrics. It is designed for operations engineers who need to inspect diagnostic files on their own computer without connecting to a MongoDB deployment or sending data to an external service.

After starting the application, use the top navigation to switch between the `MongoDB Log` and `MongoDB Metric` workspaces. For complete operating instructions, data-retention details, and known limitations, read the [English User Guide](docs/user-guide.en.md). The [Chinese User Guide](docs/user-guide.md) and an in-app Chinese help dialog are also available.

> The current web interface and exported Markdown reports are in Simplified Chinese. This documentation includes the relevant Chinese button labels together with English explanations.

## Features

### MongoDB Log analysis

- Parses MongoDB structured Extended JSON logs by available fields rather than server version, as well as legacy single-line text logs and GZip-compressed logs.
- Accepts multiple files in one task and processes them as a single analysis dataset.
- Streams input instead of loading an entire log into memory.
- Calculates eight latency buckets from every detected slow query, not from a sample.
- Retains the globally slowest 5,000 query records and a separate slowest sample for each of the Top 50 query patterns.
- Summarizes failed operations, operation types, namespaces, normalized query patterns, execution plans, client IPs, CPU time, response size, and hourly connections when those fields are present.
- Provides a separate runtime-diagnostics view for severity and component trends, connection and authentication activity, client applications and drivers, replica-set and network events, slow-query efficiency signals, and MongoDB 4.4+ field coverage.
- Exports a sanitized Markdown report for human review or optional secondary analysis with an AI tool.

### MongoDB Metric analysis

- Accepts up to 20 MongoDB `diagnostic.data/metrics.*` FTDC files per task and identifies them by content rather than file extension.
- Preserves compressed source files and builds a compact binary index without storing a fully expanded time-series copy.
- Lets you select one or more metric groups, or select the core metric groups detected in the current task.
- Queries selected groups sequentially and renders each group as soon as it is ready; a failure in one group does not stop the remaining groups.
- Supports raw values or adjacent deltas, combined or per-metric charts, collapsible groups, and one-click hiding of all-zero metrics.
- Breaks chart lines across significant sampling gaps and downsamples each metric to a bounded number of points while calculating `min`, `max`, and `avg` from all valid source points.

### Local-first behavior

- Listens on `127.0.0.1:18080` by default.
- Does not require MongoDB, Node.js, Nacos, S3, an AI service, or an Internet connection at runtime.
- Keeps task data under the local `data` directory.
- Never modifies the source files selected by the user.

## Requirements

- Java 17 or newer.
- Enough memory for the workload. The default launcher allows the Java heap to grow to 2 GB and the process also uses a small amount of native and metaspace memory.
- Enough local disk space for analysis results, temporary files, and retained FTDC source copies.

Node.js and Maven are required only when building from source.

## Quick Start

A release directory must contain the executable JAR and the launcher scripts:

```text
mongodb-log-analyzer/
├── mongodb-log-analyzer.jar
└── scripts/
    ├── start.command
    ├── start.sh
    └── start.bat
```

### macOS

Make the launchers executable the first time:

```bash
chmod +x scripts/start.command scripts/start.sh
```

Then double-click `scripts/start.command`, or run:

```bash
./scripts/start.sh
```

### Linux

```bash
./scripts/start.sh
```

### Windows

Double-click `scripts\start.bat`.

The launcher starts Java with `-Xms128m -Xmx2g` and normally opens `http://127.0.0.1:18080` automatically. Open that address manually if the browser does not start. Close the launcher window or press `Ctrl+C` in its terminal to stop the application.

## Basic Usage

### Analyze MongoDB logs

1. Select `MongoDB Log` at the top of the page.
2. Optionally enter a task name.
3. Click `选择日志文件` (Select log files) and choose up to 20 text, structured JSON, legacy, or `.gz` log files.
4. Click `开始分析` (Start analysis).
5. Wait for the queued or running task to finish, then click `查看分析结果` (View analysis results).
6. Switch between `慢查询分析` (Slow Query Analysis) and `运行诊断` (Runtime Diagnostics) as needed.

Log analyses and Metric indexing tasks share one background worker thread. A later Log or indexing task remains queued while that worker is busy.

### Analyze MongoDB FTDC metrics

1. Select `MongoDB Metric` at the top of the page.
2. Choose 1 to 20 `diagnostic.data/metrics.*` files.
3. Click `开始建立索引` (Start indexing) and wait for the task to complete.
4. Open the task, select one or more metric groups, or click `一键选择核心指标组` (Select core metric groups).
5. Click `查询所选指标组` (Query selected metric groups).
6. Use raw/delta mode, combined/split charts, group collapse, and all-zero metric controls as needed.

Indexing and metric-group queries share a fair sequential execution gate. A queued operation continues automatically after the current heavy operation finishes.

For detailed instructions and troubleshooting, see the [English User Guide](docs/user-guide.en.md).

## Data and Privacy

By default, application-managed data is stored relative to the directory from which the application starts:

```text
data/
├── tasks-index.json
├── tasks/<task-id>/metadata.json
├── tasks/<task-id>/summary.json
├── tasks/<task-id>/diagnostics.json
├── tasks/<task-id>/top-slow-queries.jsonl
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

For Log tasks, temporary uploaded copies are removed after analysis completes or fails. The application retains aggregate results, the globally slowest 5,000 query records, and one separate slowest sample for each selected Top 50 query pattern; it does not retain every ordinary log line.

For Metric tasks, the application retains uploaded FTDC copies, the metric catalog, and a compact binary index so that metrics can be queried later. It does not persist a second fully expanded time-series dataset.

Deleting a task removes only application-managed copies and results. It does not delete the user's original files. All parsing and explanations run locally, and exported reports are not automatically sent to an AI service.

## Important Limits

- The server-side limit for a single upload request is 12 GB, including multipart overhead.
- A Log or Metric task accepts at most 20 files through the current web interface.
- Only the globally slowest 5,000 query records remain available as scatter points and detailed global records.
- A Metric group may contain at most 200 metrics.
- A Metric chart returns at most 1,200 points per metric; aggregate statistics still use all valid points.
- The product does not expose single-metric queries, raw-value pagination, or complete metric export.
- Historical tasks are not recalculated after an application upgrade. Re-upload the source files to use new parsing or diagnostic rules.
- The tool provides evidence and investigation leads, not an automatic root-cause diagnosis. Validate conclusions against workload, indexes, query plans, configuration, hardware, and monitoring from the same period.

## Build from Source

Requirements: Java 17+, Maven, Node.js, and npm.

```bash
mvn clean test
cd web
npm test -- --run
cd ..
mvn package
```

`mvn package` installs the locked frontend dependencies, builds the Vue application, and generates `target/mongodb-log-analyzer.jar`. The scripts in the repository can run this JAR directly. To prepare a release directory, copy the JAR to the directory root and keep the `scripts` directory beside it.

## Technology Stack

- Backend: Java 17, Spring Boot, Maven, and the MongoDB BSON library.
- Frontend: Vue 3, Vite, Element Plus, and ECharts.
- Persistence: local JSON, JSONL, original compressed FTDC files, and compact binary indexes.

## Documentation

- [English User Guide](docs/user-guide.en.md)
- [中文使用指南](docs/user-guide.md)
- [Project roadmap](ROADMAP.md) (Chinese)
- [Design specifications](docs/superpowers/specs) (Chinese)
- [Implementation plans](docs/superpowers/plans) (Chinese)

## License

Licensed under the [Apache License 2.0](LICENSE).
