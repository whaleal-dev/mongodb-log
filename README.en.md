# MongoDB Log & Metric Analyzer

[简体中文](README.md) | [English](README.en.md)

A local, offline MongoDB analysis tool for operations engineers. It provides separate `MongoDB Log` and `MongoDB Metric` workspaces for inspecting logs, FTDC metrics, and runtime anomalies without connecting to MongoDB or automatically uploading data.

This project was designed, implemented, tested, and documented entirely with AI. Future changes or extensions are best made with AI while following the project rules and existing validation workflow. Although the project is AI-authored, its core analysis logic, statistical definitions, and outputs have been verified through automated tests, real-world samples, and human review.

See the [English User Guide](docs/user-guide.en.md) for complete operating instructions. The running application also provides a Chinese help dialog through `使用说明` in the upper-right corner.

> The current web interface and exported Markdown reports are in Simplified Chinese. The English guide includes the relevant Chinese labels.

## Core Capabilities

### MongoDB Log

- Streams MongoDB structured Extended JSON, legacy single-line, and `.gz` compressed logs.
- Combines multiple files into one task; the web interface accepts up to 20 files at a time.
- Calculates eight latency buckets from every detected slow query rather than sampling the retained Top 5,000.
- Retains the globally slowest 5,000 records plus one independent slowest sample for each selected Top 50 query pattern. Ordinary raw lines are not persisted; runtime diagnostics keep at most three sanitized message samples per event category.
- Summarizes failed operations, operation types, namespaces, query patterns, plans, clients, CPU time, response sizes, and hourly connections when available.
- Provides separate runtime diagnostics for anomaly timelines, connections and authentication, client applications and drivers, replica-set and network events, slow-query efficiency signals, and structured-field coverage.
- Exports a sanitized Markdown report for human review or optional secondary analysis with an AI tool.
- Supports chart/table switching, scatter zooming and selection, and a detailed side panel for retained slow queries.

### MongoDB Metric

- Accepts 1 to 20 MongoDB `diagnostic.data/metrics.*` FTDC files per task and validates their content rather than relying on file extensions.
- Scans outer BSON documents sequentially, decompresses one block at a time, preserves source copies, and builds a compact binary index.
- Queries one or more metric groups sequentially in the selected order. A failed group does not stop later groups.
- Supports raw values or adjacent deltas, combined or split charts, collapsible groups, and hiding all-zero metrics.
- Breaks lines across significant sampling gaps. Each metric returns at most 1,200 chart points, while `min`, `max`, `avg`, and all-zero detection use every valid point.
- Resolves duplicate timestamps across files by preferring the file that appeared earlier in the upload order.

### Local-First Behavior

- Listens on `127.0.0.1:18080` by default.
- Runtime use does not require MongoDB, Node.js, Nacos, S3, an external AI service, or an Internet connection.
- Stores managed task data under the local `data` directory and never modifies user-selected source files.
- Uses one worker thread for Log analysis and Metric indexing. FTDC indexing and metric queries also share a fair sequential gate to bound heavy concurrent work.

## Quick Start

Java 17 or newer is required. A release directory must contain the executable JAR and launcher scripts:

```text
mongodb-log-analyzer/
├── mongodb-log-analyzer.jar
└── scripts/
    ├── start.command
    ├── start.sh
    └── start.bat
```

- macOS: run `chmod +x scripts/start.command scripts/start.sh` once, then double-click `scripts/start.command`.
- Linux: run `./scripts/start.sh`.
- Windows: double-click `scripts\start.bat`.

The launchers use `-Xms128m -Xmx2g` and normally open `http://127.0.0.1:18080`. Open the address manually if no browser appears. Close the launcher window or press `Ctrl+C` to stop the application.

## Processing Model

```text
Vue page in the browser
        | REST API
        v
Local Spring Boot service
        +-- Log: upload copy -> streaming parser -> bounded aggregation -> JSON/JSONL
        +-- Metric: upload copy -> FTDC scan -> blocks.idx -> streamed group query
```

Temporary Log upload copies stay under `data/work` only during analysis and are removed after completion or failure. Metric tasks retain uploaded copies, the metric catalog, and the block index for later queries, but do not persist a fully expanded time-series copy.

If the application restarts, tasks that were queued or running are marked as failed instead of continuing from incomplete state.

## Data Layout

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

Deleting a task removes only application-managed data, never the user’s original files. Queued or running tasks cannot be deleted. The memory indicator exposes a clear-all action; if any task is active, the operation is rejected before any task data is removed.

## Reports and Privacy Boundary

`导出 AI 分析报告` generates a Markdown report for the selected Log task. It includes aggregates, normalized query patterns, retained Top results, and sanitized diagnostics, but excludes full commands, complete raw logs, and complete attributes.

The export masks MongoDB URIs, IPv4 and IPv6 addresses, email addresses, and values labeled with `user`, `username`, `principal`, `password`, `passwd`, `token`, or `secret`. Task names, file names, namespaces, normalized query patterns, plans, and other aggregates may still appear. Review the exported file against your organization’s data-security requirements before sharing it.

## Important Limits

- The server-side multipart request limit is 12 GB including form overhead, so selected files should total slightly less.
- The web interface accepts up to 20 Log files per task. The FTDC backend also enforces at most 20 non-empty files per task.
- Scatter points and global slow-query details are limited to the slowest 5,000 records. Each Top 50 pattern retains only one additional slowest sample.
- A Metric group contains at most 200 metrics, and each metric returns at most 1,200 chart points.
- The product does not expose FTDC single-metric queries, raw-value pagination, or complete metric export.
- Upgrades do not recalculate historical tasks; re-upload source files to apply new parsing rules.
- The tool provides evidence and investigation leads, not an automatic root-cause diagnosis. Validate conclusions against workload, indexes, plans, configuration, hardware, and monitoring from the same period.

## Implementation

- Backend: Java 17, Spring Boot 3.4.5, Maven, and MongoDB BSON 5.2.1.
- Frontend: Vue 3, Vite, Element Plus, ECharts, and Vitest.
- Persistence: local JSON/JSONL, original FTDC compressed files, and binary block indexes.
- Packaging: Maven runs `npm ci` and `npm run build` during `prepare-package`, then embeds the static frontend in the executable JAR.

Main source layout:

```text
src/main/java/com/whaleal/mongodblog/
├── parser/      # Streaming Log and FTDC parsing
├── analysis/    # Aggregation, diagnostics, and Metric series
├── report/      # Sanitized Markdown reports
├── task/        # Task lifecycle and serialized execution
├── storage/     # Local persistence and binary indexes
└── web/         # REST API
web/src/         # Vue application
web/tests/       # Frontend tests
docs/            # Design, AI rewrite, and user guides
scripts/         # Platform launchers
```

## REST API Overview

| Scope | Main endpoint | Purpose |
|---|---|---|
| Log tasks | `/api/tasks` | Create, list, and inspect tasks |
| Log summary | `/api/tasks/{id}/summary` | Read aggregate results |
| Diagnostics | `/api/tasks/{id}/diagnostics` | Read separate runtime diagnostics |
| Slow queries | `/api/tasks/{id}/slow-queries` | Filter retained records with pagination |
| Report | `/api/tasks/{id}/report.md` | Download a sanitized Markdown report |
| Metric tasks | `/api/ftdc-tasks` | Create, list, and inspect FTDC tasks |
| Metric groups | `/api/ftdc-tasks/{id}/groups` | List queryable metric groups |
| Metric series | `/api/ftdc-tasks/{id}/groups/{groupId}/series` | Query one metric group |
| Memory | `/api/system/memory` | Read JVM heap usage |
| Data cleanup | `/api/system/data` | Delete all terminal tasks |

These endpoints are designed for the local single-user interface. They do not provide accounts, authorization, tenant isolation, or public deployment support.

## Build from Source

Java 17, Maven, Node.js, and npm are required:

```bash
mvn test
cd web
npm test -- --run
npm run build
cd ..
mvn clean package
```

`mvn clean package` removes historical build output, installs locked frontend dependencies, builds the Vue application, and generates `target/mongodb-log-analyzer.jar`. The repository launchers can run that file directly.

## Documentation

- [English User Guide](docs/user-guide.en.md)
- [中文项目介绍](README.md)
- [中文使用指南](docs/user-guide.md)
- [项目设计详解](docs/design.md) (Chinese)
- [AI 二次改写指南](docs/ai-rewrite-guide.md) (Chinese)
- [Project roadmap](ROADMAP.md) (Chinese)

## License

Licensed under the [Apache License 2.0](LICENSE).
