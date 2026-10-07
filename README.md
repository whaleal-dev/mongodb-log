# MongoDB Log & Metric Analyzer

[简体中文](README.md) ｜ [English](README.en.md)

面向运维人员的 MongoDB 本地离线分析工具。应用提供 `MongoDB Log` 与 `MongoDB Metric` 两个工作区，可分析日志、FTDC 指标及运行异常，不连接 MongoDB，也不会自动上传数据。

本项目全程使用 AI 完成设计、编码、测试与文档编写。后续如需修改或扩展，建议继续使用 AI，并遵循项目规范与现有验证流程。项目虽由 AI 编程实现，但核心分析逻辑、统计口径与输出结果均已通过自动化测试、实际样本及人工复核验证。

完整操作步骤见 [中文使用指南](docs/user-guide.md) 。运行后也可点击页面右上角的「使用说明」。

## 核心能力

### MongoDB Log

- 流式解析 MongoDB 结构化 Extended JSON、旧版单行文本和 `.gz` 压缩日志。
- 一个任务可合并多个文件；Web 页面一次最多选择 20 个文件。
- 基于全部慢查询精确统计八个耗时区间，不使用 Top 5000 抽样。
- 永久保存全局最慢 Top 5000 明细，以及 Top 50 查询模式各自最慢的一条独立样本；普通日志原文不持久化，运行诊断每类最多保留 3 条脱敏消息样本。
- 汇总失败操作、操作类型、Namespace、查询模式、执行计划、客户端、CPU、响应量和小时连接数。
- Top 50 查询模式支持集合／模式关键字、操作类型和执行计划组合筛选，可继续排序并查看独立最慢样本。
- 提供独立运行诊断，覆盖异常时间线、连接与认证、客户端应用与 Driver、复制集与网络事件、慢查询效率线索和结构化字段覆盖。
- 支持导出脱敏 Markdown 报告，用于人工检查或交给 AI 做二次分析。
- 图表可切换到数据表；慢查询散点支持缩放、框选和右侧详情查看。

### MongoDB Metric

- 每个任务接受 1～20 个 `diagnostic.data/metrics.*` FTDC 文件，按内容验证，不依赖扩展名。
- 逐文档扫描外层 BSON，一次只解压当前 Block；保留源文件副本并建立紧凑二进制索引。
- 按指标组查询，可一次选择多组并按选择顺序逐组返回；单组失败不影响后续组。
- 支持原始值／相邻差值、合并／拆分图表、组折叠以及全零指标隐藏。
- 识别采样时间断裂并断开折线；每指标最多返回 1,200 点，但 `min`、`max`、`avg` 和全零判断仍基于全部有效点。
- 同一时间戳跨文件重叠时，优先采用上传顺序更靠前的文件。

### 本地优先

- 默认只监听 `127.0.0.1:18080`。
- 运行时不需要 MongoDB、Node.js、Nacos、S3、外部 AI 服务或网络连接。
- 任务结果保存在启动目录下的 `data`，不会修改用户选择的原文件。
- Log 分析和 Metric 建索引共用单工作线程；FTDC 建索引与指标查询另有公平串行许可，避免重型操作争抢内存。

## 快速启动

### 使用 Docker（推荐）

公开镜像位于 [Docker Hub](https://hub.docker.com/r/whaleal/mongodb-log-analyzer) ，支持 `linux/amd64` 与 `linux/arm64`。运行时不需要安装 Java、Node.js 或 MongoDB：

```bash
docker pull whaleal/mongodb-log-analyzer:0.1.0

docker run -d \
  --name mongodb-log-analyzer \
  --restart unless-stopped \
  --memory=3g \
  -p 127.0.0.1:18080:18080 \
  -v mongodb-log-analyzer-data:/app/data \
  whaleal/mongodb-log-analyzer:0.1.0
```

启动后访问 `http://127.0.0.1:18080`。容器以非 root 用户运行，JVM 堆上限为 2 GB，命名卷 `mongodb-log-analyzer-data` 用于持久化任务数据。应用没有账号和授权机制，因此示例只向本机开放端口；不要把端口直接暴露到公网。升级或重建容器时保留该命名卷，也不要执行会删除它的 `docker compose down -v`。

同时提供 `latest` 标签，但固定部署建议使用明确版本号 `0.1.0`。

### 使用 JAR

运行要求为 Java 17 或更高版本。发布目录需要同时包含 JAR 和启动脚本：

```text
mongodb-log-analyzer/
├── mongodb-log-analyzer.jar
└── scripts/
    ├── start.command
    ├── start.sh
    └── start.bat
```

- macOS：首次执行 `chmod +x scripts/start.command scripts/start.sh`，之后双击 `scripts/start.command`。
- Linux：执行 `./scripts/start.sh`。
- Windows：双击 `scripts\start.bat`。

启动脚本使用 `-Xms128m -Xmx2g`。启动成功后通常会自动打开 `http://127.0.0.1:18080`；未自动打开时可手动访问。停止程序时关闭启动窗口，或在终端按 `Ctrl+C`。

## 工作流程

```text
浏览器中的 Vue 页面
        │ REST API
        ▼
Spring Boot 本地服务
        ├── Log：上传副本 → 流式解析 → 有界聚合 → JSON／JSONL 结果
        └── Metric：上传副本 → FTDC 扫描 → blocks.idx → 按组流式查询
```

Log 上传副本只在分析期间位于 `data/work`，完成或失败后删除。Metric 必须支持后续查询，因此会保留上传副本、指标目录和 Block 索引，但不会保存全量展开后的时间序列。

应用重启时，先前处于排队或运行状态的任务会标记为失败，不会使用不完整结果继续分析。

## 数据目录

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

删除任务只会清理应用托管的数据，不会删除用户原文件。排队或运行中的任务不能删除。右上角内存指示器提供全量清理入口；存在活动任务时会在删除任何内容前整体拒绝。

## 报告与隐私边界

点击「导出 AI 分析报告」会生成当前 Log 任务的 Markdown 报告。报告包含聚合统计、规范化查询模式、Top 结果和脱敏诊断信息，但不导出完整命令、完整原始日志或完整 attributes。

导出时会遮盖 MongoDB URI、IPv4／IPv6、邮箱，以及带 `user`、`username`、`principal`、`password`、`passwd`、`token`、`secret` 标签的值。任务名、文件名、Namespace、查询模式、执行计划和其他聚合字段仍可能出现，因此对外分享前仍需按所在组织的数据安全要求复核。

## 主要限制

- 单次 multipart 上传请求的服务端上限为 12 GB，包含表单开销；实际文件总大小应略低于该值。
- Web 页面一次最多选择 20 个 Log 文件；FTDC 后端同时强制每个任务最多 20 个非空文件。
- 散点和全局慢查询明细只保留最慢 Top 5000；Top 50 查询模式每组只额外保存一条最慢样本。
- 单个 Metric 指标组最多 200 个指标，每指标最多返回 1,200 个图表点。
- 产品不提供 FTDC 单指标查询入口、原始值分页或完整指标导出。
- 升级程序不会重新计算历史任务；应用新版解析规则需要重新上传原文件。
- 工具提供证据和排查线索，不自动判断根因。最终结论仍需结合业务负载、索引、执行计划、配置、硬件和同期监控。

## 技术实现

- 后端：Java 17、Spring Boot 3.4.5、Maven、MongoDB BSON 5.2.1。
- 前端：Vue 3、Vite、Element Plus、ECharts、Vitest。
- 持久化：本地 JSON／JSONL、FTDC 原始压缩文件和二进制 Block 索引。
- 构建：Maven 在 `prepare-package` 阶段执行 `npm ci` 和 `npm run build`，再把静态资源打入可执行 JAR。

主要目录：

```text
src/main/java/com/whaleal/mongodblog/
├── parser/      # Log 与 FTDC 流式解析
├── analysis/    # 聚合、诊断与 Metric 序列查询
├── report/      # 脱敏 Markdown 报告
├── task/        # 任务生命周期与串行执行
├── storage/     # 本地文件持久化与索引
└── web/         # REST API
web/src/         # Vue 页面
web/tests/       # 前端测试
docs/            # 设计、AI 改写与中英文用户指南
scripts/         # 三个平台的启动脚本
```

## REST API 概览

| 范围 | 主要接口 | 用途 |
|---|---|---|
| Log 任务 | `/api/tasks` | 创建、列表和查询任务 |
| Log 结果 | `/api/tasks/{id}/summary` | 获取聚合结果 |
| 运行诊断 | `/api/tasks/{id}/diagnostics` | 获取独立诊断结果 |
| 慢查询 | `/api/tasks/{id}/slow-queries` | 分页筛选已保留明细 |
| 报告 | `/api/tasks/{id}/report.md` | 下载脱敏 Markdown 报告 |
| Metric 任务 | `/api/ftdc-tasks` | 创建、列表和查询 FTDC 任务 |
| Metric 指标组 | `/api/ftdc-tasks/{id}/groups` | 获取可查询指标组 |
| Metric 序列 | `/api/ftdc-tasks/{id}/groups/{groupId}/series` | 查询一个指标组 |
| 系统状态 | `/api/system/memory` | 获取 JVM 堆使用情况 |
| 清理数据 | `/api/system/data` | 清理全部已结束任务 |

这些接口面向本机单用户页面，没有账号、授权、多用户隔离或公网部署设计。

## 从源码构建

需要 Java 17、Maven、Node.js 和 npm：

```bash
mvn test
cd web
npm test -- --run
npm run build
cd ..
mvn clean package
```

`mvn clean package` 会先清理历史构建产物，再按锁文件安装前端依赖、构建 Vue 页面，并生成 `target/mongodb-log-analyzer.jar`。项目启动脚本也能直接找到该文件。

也可以使用项目根目录的 `Dockerfile` 构建本地镜像。多阶段构建会运行前后端测试，并且最终镜像不包含 Maven、Node.js 或前端构建依赖：

```bash
docker build -t mongodb-log-analyzer:local .
```

## 文档

- [中文使用指南](docs/user-guide.md)
- [项目设计详解](docs/design.md)
- [AI 二次改写指南](docs/ai-rewrite-guide.md)
- [English README](README.en.md)
- [English User Guide](docs/user-guide.en.md)
- [项目路线图](ROADMAP.md)

## 开源许可

本项目使用 [Apache License 2.0](LICENSE) 开源。
