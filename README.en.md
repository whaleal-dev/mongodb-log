# MongoDB Log & Metric Analyzer

[简体中文](README.md) | [English](README.en.md)

A local, offline MongoDB analysis tool for operations engineers. Its `MongoDB Log` and `MongoDB Metric` workspaces process files on your computer without connecting to MongoDB or automatically sending data to an AI service.

The project was designed, implemented, tested, and documented with AI. See the [project roadmap](ROADMAP.md) for recorded validation results and conditionally skipped tests.

> The current web interface and exported Markdown reports are in Simplified Chinese. The English user guide includes the relevant Chinese labels.

## Core Capabilities

| Workspace | Capabilities |
|---|---|
| MongoDB Log | Streams structured Extended JSON, legacy single-line, and GZip logs; supports multi-file tasks, full-input latency buckets, the globally slowest 5,000 queries, and Top 50 query patterns with independent slowest samples |
| Log diagnostics and reports | Inspects anomalies, connections, authentication, replica-set events, slow-query efficiency signals, and known-field coverage; exports sanitized Markdown reports |
| MongoDB Metric | Indexes 1–20 FTDC files locally; queries groups in selection order, with raw values or adjacent deltas, all-zero hiding, sampling gaps, and bounded downsampling |

Charts provide inspectable values. Log analysis also includes pattern filters, scatter plots, and detailed query records. For operating steps, aggregation definitions, and data retention, see the [English User Guide](docs/user-guide.en.md).

## Quick Start

### Docker

The public image is available on [Docker Hub](https://hub.docker.com/r/whaleal/mongodb-log-analyzer) for `linux/amd64` and `linux/arm64`. Java, Node.js, and MongoDB are not required on the host:

```bash
docker run -d \
  --name mongodb-log-analyzer \
  --restart unless-stopped \
  --memory=3g \
  -p 127.0.0.1:18080:18080 \
  -v mongodb-log-analyzer-data:/app/data \
  whaleal/mongodb-log-analyzer:0.1.2
```

Open `http://127.0.0.1:18080`. The container runs as a non-root user with a 2 GB JVM heap limit; total process memory also includes native overhead. Task data persists in the named volume, which should remain mounted when upgrading.

The example pins the published `0.1.2` version. Source changes do not automatically update published JARs or images. See [GitHub Releases](https://github.com/whaleal-dev/mongodb-log/releases) for downloadable versions.

### JAR release

Download a launcher package from [GitHub Releases](https://github.com/whaleal-dev/mongodb-log/releases), extract it, and preserve this layout. Java 17 or newer is required:

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

The launchers use `-Xms128m -Xmx2g` and attempt to open your browser. See the [user guide](docs/user-guide.en.md) for stopping the application, port troubleshooting, and the author introduction.

## Operating Limits

- The default address is `127.0.0.1:18080`. There are no accounts, authorization, or multi-user isolation; the Docker example also publishes the port locally only.
- One multipart request is limited to 12 GB including form overhead. The UI allows up to 20 Log files; the FTDC backend also enforces at most 20 non-empty files.
- Log latency buckets use every valid slow query. Global details retain only the slowest 5,000 queries, plus one independent sample per selected Top 50 pattern, until task deletion.
- A Metric group contains at most 200 metrics, and each metric returns at most 1,200 chart points. Complete statistics use all valid points.
- Runtime use does not need a network connection or external services. Results and retained query details may contain business information; exported reports also need human review.
- Upgrades do not recalculate historical tasks. Re-upload files to apply new parsing rules. The tool provides investigation leads and does not determine root cause automatically.

The [user guide](docs/user-guide.en.md) centralizes data retention, copies left after interrupted processes, deletion behavior, and report sanitization.

## Build from Source

The backend requires Java 17 and Maven. For the frontend, use Node.js 22 at version `22.12+`, or Node.js 24, with npm. This does not mean every version above 22.12 is supported; check dependency engines in the lockfile.

Run from the repository root:

```bash
mvn test
cd web
npm ci
npm test -- --run
npm run build
cd ..
mvn clean package
```

The output is `target/mongodb-log-analyzer.jar`. During `prepare-package`, Maven runs `npm ci` and the frontend build, then embeds the static assets in the JAR. Maven packaging does not run frontend tests, so use the full sequence above before a release. The first build downloads Maven and npm dependencies; packaged runtime use does not need the build tools.

Alternatively, build a local image from the repository root. The multi-stage Docker build runs frontend and backend tests:

```bash
docker build -t mongodb-log-analyzer:local .
```

The backend uses Spring Boot and MongoDB BSON; the frontend uses Vue 3, Vite, Element Plus, ECharts, and Vitest. See the [project design](docs/design.md) for architecture, storage, public APIs, and internal components.

## Documentation

| Document | Purpose |
|---|---|
| [English User Guide](docs/user-guide.en.md) / [中文使用指南](docs/user-guide.md) | Operation, data retention, privacy, limits, and troubleshooting |
| [Project design](docs/design.md) (Chinese) | Architecture, algorithms, storage, REST APIs, and tradeoffs |
| [AI rewrite guide](docs/ai-rewrite-guide.md) (Chinese) | Change workflow, code navigation, validation matrix, and prompts |
| [Project rules](CLAUDE.md) (Chinese) | Development constraints and documentation ownership |
| [Project roadmap](ROADMAP.md) (Chinese) | Current progress, release status, and recorded validation |

## License

Licensed under the [Apache License 2.0](LICENSE).
