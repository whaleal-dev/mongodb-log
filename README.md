# MongoDB Log & Metric Analyzer

[简体中文](README.md) ｜ [English](README.en.md)

面向运维人员的 MongoDB 本地离线分析工具，提供 `MongoDB Log` 和 `MongoDB Metric` 两个工作区。数据在本机解析，不连接 MongoDB，也不会自动发送到外部 AI 服务。

本项目使用 AI 完成设计、编码、测试与文档编写。具体版本的验证结果和条件跳过项见 [项目路线图](ROADMAP.md) 。

## 核心能力

| 工作区 | 能力 |
|---|---|
| MongoDB Log | 流式解析结构化 Extended JSON、旧版单行文本和 GZip 日志；支持多文件任务、全量耗时分布、全局最慢 Top 5000、Top 50 查询模式与独立最慢样本 |
| Log 诊断与报告 | 查看异常、连接、认证、复制集事件、慢查询效率线索和字段覆盖率；导出脱敏 Markdown 报告 |
| MongoDB Metric | 为 1～20 个 FTDC 文件建立本地索引；按选择顺序逐组查询，支持原始值／相邻差值、全零隐藏、时间断裂和有界降采样 |

图表支持可核对数值，Log 提供模式组合筛选、慢查询散点和详情抽屉。统计依据、操作步骤和数据保留方式见 [中文使用指南](docs/user-guide.md) 。

## 快速启动

### Docker

公开镜像位于 [Docker Hub](https://hub.docker.com/r/whaleal/mongodb-log-analyzer) ，支持 `linux/amd64` 和 `linux/arm64`。宿主机不需要安装 Java、Node.js 或 MongoDB：

```bash
docker run -d \
  --name mongodb-log-analyzer \
  --restart unless-stopped \
  --memory=3g \
  -p 127.0.0.1:18080:18080 \
  -v mongodb-log-analyzer-data:/app/data \
  whaleal/mongodb-log-analyzer:0.1.3
```

启动后访问 `http://127.0.0.1:18080`。容器以非 root 用户运行，JVM 堆上限为 2 GB，进程总内存还包括堆外开销。任务数据保存在命名卷中，升级时继续挂载该卷。

示例固定使用已发布的 `0.1.3`。源码变化不会自动更新已发布的 JAR 或镜像；可下载版本以 [GitHub Releases](https://github.com/whaleal-dev/mongodb-log/releases) 为准。

### JAR 发布包

从 [GitHub Releases](https://github.com/whaleal-dev/mongodb-log/releases) 下载启动包，解压后保留以下目录结构。运行要求为 Java 17 或更高版本：

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

脚本使用 `-Xms128m -Xmx2g`，启动后会尝试打开浏览器。停止方式、端口问题和作者介绍行为见 [使用指南](docs/user-guide.md) 。

## 使用边界

- 默认只监听 `127.0.0.1:18080`，没有账号、授权和多用户隔离；Docker 示例也仅向本机开放端口。
- 单次 multipart 请求上限为 12 GB，包含表单开销。Web 页面最多选择 20 个 Log 文件；FTDC 后端也限制最多 20 个非空文件。
- Log 耗时分布基于全部有效慢查询；全局明细仅保留最慢 Top 5000，Top 50 模式各额外保留一条最慢样本，保留至任务删除。
- 单个 Metric 指标组最多 200 个指标，每指标最多返回 1,200 点；完整统计基于全部有效点。
- 运行时无需网络或外部服务。结果和保留的慢查询明细可能包含业务信息；导出的报告也需要人工复核。
- 历史任务不会因升级自动重算；应用新规则需要重新上传。工具提供排查线索，不自动确定故障根因。

任务数据、异常退出后的副本、删除行为和报告脱敏范围集中说明在 [使用指南](docs/user-guide.md) 中。

## 从源码构建

后端需要 Java 17 和 Maven。前端推荐 Node.js `22.12+` 的 22 系列或 Node.js 24，并使用 npm；不要将这一要求理解为任意高于 22.12 的版本均受支持，具体约束见锁文件中的依赖 engines。

在项目根目录执行：

```bash
mvn test
cd web
npm ci
npm test -- --run
npm run build
cd ..
mvn clean package
```

产物为 `target/mongodb-log-analyzer.jar`。Maven 在 `prepare-package` 阶段执行 `npm ci` 和前端构建，将静态资源打入 JAR；Maven 打包本身不执行前端测试，因此发布前应执行上面的完整流程。首次构建需要下载 Maven 与 npm 依赖，运行发布包无需这些构建工具。

也可从项目根目录构建镜像，多阶段 Docker 构建会运行前后端测试：

```bash
docker build -t mongodb-log-analyzer:local .
```

后端使用 Spring Boot 和 MongoDB BSON，前端使用 Vue 3、Vite、Element Plus、ECharts 和 Vitest。具体架构、存储布局、公开 API 和内部组件见 [项目设计](docs/design.md) 。

## 文档

| 文档 | 用途 |
|---|---|
| [中文使用指南](docs/user-guide.md) ／ [English User Guide](docs/user-guide.en.md) | 操作、数据保留、隐私、限制和故障排查 |
| [项目设计](docs/design.md) | 架构、算法、存储、REST API 和技术权衡 |
| [AI 二次改写指南](docs/ai-rewrite-guide.md) | 修改流程、代码定位、验证矩阵和提示词 |
| [项目规范](CLAUDE.md) | 开发硬约束与文档职责 |
| [项目路线图](ROADMAP.md) | 当前进度、发布状态和实际验证记录 |

## 作者

HP，公众号自媒体写作者、互联网技术与产品人，一线 AI 实践者，主要实践方向包括自媒体写作、知识管理和 AI 编程工具／产品。

- [作者简介](https://docs.whaleal.com/resume-site/) ：经历、技术背景与实践。
- [Whaleal Platform](https://www.whaleal.io/) ：MongoDB 监控、管理与备份平台。
- [whaleal-dev 公开仓库](https://github.com/whaleal-dev) ：组织开源项目、源码与更新。

## 开源许可

本项目使用 [Apache License 2.0](LICENSE) 开源。
