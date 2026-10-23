# AI 二次改写指南

## 1．用途与必读顺序

本文用于让 AI 在理解项目后继续开发、修复、局部重构或维护文档。操作说明归 [用户指南](user-guide.md) ，技术事实归 [项目设计](design.md) ，这里集中说明修改流程和验证方法。

开始前按顺序阅读：

1. [项目规范](../CLAUDE.md) ：硬约束、红线与文档职责。
2. [项目路线图](../ROADMAP.md) ：当前阶段、阻塞和实际验证。
3. [项目设计](design.md) ：架构、算法、存储、API 与已知权衡。
4. 与任务相关的源码和测试。
5. [中文 README](../README.md) ／ [English README](../README.en.md) 及中英文用户指南：公开能力与用户行为。

源码和测试说明当前事实，项目规范约束允许的变更。发现冲突先列证据，判断修代码还是修文档，不能把实现缺陷当成降低规范的理由。

## 2．代码定位

后端路径均相对于 `src/main/java/com/whaleal/mongodblog/`：

| 任务 | 优先查看 |
|---|---|
| Log 上传、状态、队列 | `task/TaskService.java`、`task/TaskRunner.java`、`task/AnalysisTask.java` |
| 结构化与旧版日志 | `parser/StructuredLogParser.java`、`parser/LegacyLogParser.java`、`parser/BalancedDocumentExtractor.java` |
| 模式与全量统计 | `parser/QueryPatternNormalizer.java`、`analysis/AnalysisAccumulator.java`、`analysis/DurationDistribution.java`、`analysis/TopSlowQueryCollector.java` |
| 诊断与脱敏 | `analysis/diagnostics/DiagnosticAccumulator.java`、`analysis/diagnostics/LogDiagnostics.java`、`analysis/diagnostics/DiagnosticTextRedactor.java` |
| Log 存储与报告 | `storage/FileTaskRepository.java`、`report/MarkdownReportService.java` |
| Log API | `web/TaskController.java`、`web/GlobalExceptionHandler.java` |
| FTDC 上传与索引 | `task/ftdc/FtdcTaskService.java`、`task/ftdc/FtdcTaskRunner.java`、`parser/ftdc/`、`storage/ftdc/` |
| FTDC 查询与分组 | `analysis/ftdc/FtdcSeriesService.java`、`analysis/ftdc/FtdcMetricDecoder.java`、`analysis/ftdc/FtdcMetricGroups.java` |
| FTDC API | `web/FtdcTaskController.java` |
| 系统维护 | `task/ApplicationMaintenanceService.java`、`web/SystemController.java` |

前端和测试位置：

| 任务 | 优先查看 |
|---|---|
| Log 页面与请求 | `web/src/App.vue`、`web/src/components/`、`web/src/api/tasks.js` |
| Metric 页面与请求 | `web/src/components/ftdc/`、`web/src/api/ftdcTasks.js` |
| 前端竞态 | `web/tests/task-races.test.js`、`web/tests/ftdc-races.test.js` |
| 后端测试 | `src/test/java/com/whaleal/mongodblog/`，包结构与源码对应 |
| 前端测试 | `web/tests/` |

公开能力必须同时核对 Controller 路由和前端入口。FTDC 单指标序列、原始值分页和 GZip CSV 导出仍是内部组件，不能因存在类和测试就写成产品功能，详见设计文档第 7.12 节。

## 3．约束引用

不要在本指南另建一套不变量。修改时检查以下唯一事实来源：

| 主题 | 规范与技术说明 |
|---|---|
| Log 统计、原文保留、缺失值和报告隐私 | [项目规范](../CLAUDE.md) 的「解析与统计红线」；[项目设计](design.md) 第 6 节 |
| FTDC 串行、解码、完整性和输出上限 | [项目规范](../CLAUDE.md) 的「FTDC 解析与查询红线」；[项目设计](design.md) 第 7 节 |
| 存储发布、重启恢复和删除 | [项目设计](design.md) 第 5、8、12 节 |
| 当前权衡与无法保证的边界 | [项目设计](design.md) 第 13 节 |
| 操作、数据保留和对外分享 | [中文用户指南](user-guide.md) ／ [English User Guide](user-guide.en.md) |
| 文档分工 | [文档职责](../CLAUDE.md#文档职责) |

特别区分全量精确聚合、固定容量明细、诊断近似计数，以及图表降采样。诊断和报告的脱敏也不等于本地原始慢查询、FTDC 副本或历史结果已经匿名化。

## 4．标准修改流程

### 4.1 只读调查与目标

先查看 `git status`，保留用户已有改动。读取相关数据流，用现有测试或最小输入复现，再把请求写成可验证目标：

```text
目标：用户最终能观察到什么变化。
范围：允许修改的模块与文档。
不变项：统计、接口、存储、资源与隐私约束。
输入：正常、无效与边界输入。
输出：成功、错误与历史兼容行为。
验证：目标测试、全量验证和浏览器验收。
```

大改动、跨模块或高风险改动应先提出具体方案并获得确认。不要把讨论或只读审查默认为修改授权。

### 4.2 确认最小修改面

沿以下链路核对受影响的层，只修改有证据需要改动的位置：

```text
用户操作 → 前端组件 → API → Controller
→ Service／Runner → Parser／Analysis → Repository
→ 测试 → 对应文档 → ROADMAP
```

不顺手格式化、升级依赖、暴露内部接口或重构相邻代码。若需要调整规范，先修规范，再按新规范实现；涉及删除、凭据、系统配置或发布时遵守用户的授权边界。

### 4.3 失败验证与最小实现

修复行为先补能稳定复现的测试，再做最小修复：

| 类型 | 输入与断言重点 |
|---|---|
| Log 解析 | 最小日志行；BOM、嵌套命令、无效数值、缺失字段、`PARTIAL` 与 `FAILED` |
| 聚合 | 可手工复算的小数据集；区间边界、稳定排序、样本保留与缺失值 |
| 诊断与报告 | 普通事件里的 IP、邮箱、MongoDB URI、带标签身份与凭据、JSON 与带引号值；检查落盘及报告 |
| FTDC 格式／索引 | BSON 长度、zlib 结尾、Schema 顺序、uvarint／RLE、分配前规模校验、索引版本与完整性 |
| FTDC 查询 | 多文件重叠、Schema 缺列、raw／delta、上限前后、尖峰与低谷、断裂、完整统计 |
| API | 参数范围、分页溢出、HTTP 状态、JSON、异步任务失败状态 |
| 前端 | 任务切换、迟到响应、取消、部分失败、重试、损坏本地存储 |
| 文件系统 | 隔离临时目录；符号链接、权限、原子移动回退、失败记录与重试 |

不操作真实 `data` 作为测试目录。只修文档时无需新增业务测试，但应核对源码、链接、路径、常量与中英文口径。

### 4.4 分层验证

先运行目标测试，再运行受影响测试与全量验证。构建环境及完整命令统一见 [README 的源码构建说明](../README.md#从源码构建) 。

首次验证先 `npm ci`；依赖未变化时不必在每次目标测试前重复安装。Maven 打包不代替前端测试。涉及上传、结果或详情交互时，还需启动本次 JAR 完成浏览器验收。

真实 FTDC 样本通过 `ftdc.real.sample` 指定，命令见 [项目设计](design.md) 第 11.4 节。条件跳过应如实报告，不能把历史实样验收或人工复核写成本次已执行。

失败后修根因，不通过注释测试、降低断言或添加跳过标记让结果变绿。外部协议调试先对照官方文档或可运行参考实现；连续两轮未解决时停止同方向猜测。

### 4.5 文档、进度与交付

按职责同步受影响文档及中英文版本，不复制整套操作或算法说明。检查相对链接、代码围栏、接口路径、数值边界和 `git diff --check`。

只有已实现并验证的事项才能进入 `ROADMAP.md` 的已完成或最近验证。源码版本、可下载 Release 和镜像标签是不同状态，不能在上传产物成功前声称版本已发布。

## 5．验证矩阵

| 改动范围 | 最低验证 | 需要追加的情况 |
|---|---|---|
| Markdown 文档 | 源码事实、链接、路径、围栏、`git diff --check` | 用户操作变化时做界面核对 |
| Log 解析 | 对应 Parser 测试 | 影响结果时跑聚合与 Controller |
| 聚合／报告／诊断 | 对应 Analysis／Report 测试 | 影响 API 或落盘时跑集成测试 |
| FTDC 格式／索引 | Parser／Index 测试 | 查询、集成、已配置的真实样本测试 |
| FTDC 查询 | `FtdcSeriesServiceTest` | Controller、多文件和真实样本 |
| 后端 API | Controller 集成测试 | 对应前端请求／组件测试 |
| 前端展示 | 相关 Vitest 与生产构建 | 用户交互变化时做浏览器验收 |
| 前端竞态 | race 测试 | 浏览器快速切换与取消操作 |
| 构建／发布 | 全量前后端测试、干净打包 | JAR 启动、资源一致性、发布附件／镜像核对 |
| 删除／清理 | 隔离目录集成测试 | 权限、符号链接和失败重试 |

全量验证应在最终代码上执行；新改动、失败或未消除风险才需要重复已经通过的检查。

## 6．可复用任务模板

### 6.1 通用上下文

```text
项目：MongoDB Log & Metric Analyzer
目标：<用户可观察的结果>
复现：<输入、步骤、实际与期望结果>
范围：<允许修改的模块和文档>
不变项：遵守 CLAUDE.md 及 docs/design.md 的统计、资源、接口、存储与隐私边界。
必读：CLAUDE.md、ROADMAP.md、docs/design.md、相关源码和测试。
验证：<目标测试、全量验证、浏览器或真实样本验收>
交付：代码、测试、对应文档、已验证的 ROADMAP 更新和简洁汇报。
授权边界：<推送、发布或其他需单独确认的操作>
```

### 6.2 Bug 修复

```text
读取项目规范、路线图和设计后，先用最小测试复现，给出根因与最小修改面。确认属于实施范围后，补失败测试再修复；不要修改无关代码或改变已有统计、FTDC 资源、存储和公开 API 口径。运行目标与全量验证，验证后更新 ROADMAP。报告根因、改动、实际结果、条件跳过及未完成项。
问题：<粘贴问题>
```

### 6.3 新功能

```text
先读取项目规范、路线图、设计和相关测试，将需求转为可验证目标。列出用户流程、无效与边界输入、接口、持久化、内存、兼容和隐私影响。跨模块或高风险改动先给具体方案并确认，再实现最小功能，不增加未要求的依赖、配置或兼容层。完成测试和验收后同步对应文档及 ROADMAP。
需求：<粘贴需求>
```

### 6.4 局部重构

```text
这是行为保持型重构。先建立当前测试基线，只重构指定模块，保持 REST 路由、JSON、排序、错误语义、存储格式、资源上限和 UI 行为。小步修改并验证。必须改变行为时先说明，不混入重构。
范围：<类或组件>
目的：<维护性问题>
```

### 6.5 文档更新

```text
先从 Controller、Service、核心常量、前端入口和测试核对事实。按 CLAUDE.md 的文档职责合并重复说明，保留各类读者需要的入口。明确公开与内部能力、精确与近似统计、源码与已发布版本、实际验证与条件跳过。检查中英文口径、相对链接、路径、接口、数值和围栏，运行 git diff --check，验证后更新 ROADMAP。
文档目标：<粘贴目标>
```

### 6.6 只读代码审查

```text
只审查，不修改。按项目规范与设计寻找统计错误、全量物化、内存无界、竞态、半成品发布、越界删除、敏感信息泄露、历史不兼容和公开能力误判。每项必须给出文件与行、触发输入、实际影响和最小修复方向；未经验证的推断明确标记，不用猜测代替证据。
范围：<提交或模块>
```

## 7．交付自检与汇报

确认每行改动可追溯到请求，保留用户已有改动；统计、缺失值、排序、去重、资源和历史兼容符合规范；诊断与报告满足脱敏边界，未额外扩散原始慢查询或 FTDC 数据；相关验证在最终状态实际运行，文档和路线图只写已证实事实。

```text
结论：<完成程度与用户可观察的结果>
改动：<文件或模块及原因>
验证：<实际命令、关键结果、条件跳过>
未完成：<具体原因、影响和必要下一步>
```

不把「应该通过」写成「已验证」，不把源码提交写成「已发布」。
