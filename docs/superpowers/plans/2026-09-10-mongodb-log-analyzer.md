# MongoDB Log Analyzer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个双击启动、离线解析 MongoDB 日志、精确统计全部慢查询并只保存最慢 Top 5000 明细的本地 Web 工具。

**Architecture:** 单个 Spring Boot 应用负责文件上传、后台流式解析、聚合和文件持久化；Vue 3 前端在构建时打包进 JAR。任务结果以 JSON／JSONL 原子写入本地目录，上传原始副本只在分析期间存在。

**Tech Stack:** Java 17、Spring Boot 3.4.5、MongoDB BSON 5.2.1、Maven、Vue 3、Vite、Element Plus、ECharts、Vitest。

**Spec:** `docs/superpowers/specs/2026-09-10-mongodb-log-analyzer-design.md`

> 2026-09-10 更新：Web 开发以 [Web 页面需求](../specs/2026-09-10-web-page-requirements.md) 和 [页面重做计划](2026-09-10-web-page-rebuild.md) 为准。本计划保留为初版实施记录；最新要求为散点＋右侧详情解读、图表／表格切换，以及 Top 50 独立最慢原文样本。下方初版保留策略与布局由上述文档覆盖，真实进度见 ROADMAP.md。

## Global Constraints

- 应用默认只监听 `127.0.0.1:18080`。
- 运行时不得依赖 MongoDB、Node.js、Nacos、S3 或其他外部服务。
- 所有日志必须流式处理，禁止一次性读入整个上传文件。
- Top 5000 必须基于全部慢查询，通过固定容量最小堆精确选取。
- 耗时区间分布必须基于全部慢查询，使用规格中的八个固定区间。
- 普通日志和 Top 5000 之外的慢查询不得永久保存原文。
- 解析异常必须计数并分类，不得静默吞掉。
- 每个生产行为先写失败测试并确认失败，再写最小实现。

---

### Task 1：项目骨架与构建闭环

**Files:**
- Create: `.gitignore`
- Create: `pom.xml`
- Create: `src/main/java/com/whaleal/mongodblog/MongoDbLogAnalyzerApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `src/test/java/com/whaleal/mongodblog/ApplicationContextTest.java`
- Create: `web/package.json`
- Create: `web/vite.config.js`
- Create: `web/index.html`
- Create: `web/src/main.js`
- Create: `web/src/App.vue`

**Interfaces:**
- Produces: 可测试的 Spring Boot 上下文、可构建的 Vue 应用和由 Maven 打包的静态资源。

- [ ] **Step 1: 写上下文加载失败测试**

  创建 `ApplicationContextTest`，使用 `@SpringBootTest(properties = "mongodblog.data-dir=${java.io.tmpdir}/mongodb-log-context-test")`，声明空的 `contextLoads()`。此时因应用类不存在而编译失败。

- [ ] **Step 2: 运行红灯**

  Run: `mvn -Dtest=ApplicationContextTest test`
  Expected: FAIL，提示找不到 `MongoDbLogAnalyzerApplication`。

- [ ] **Step 3: 创建最小 Spring Boot 应用和配置**

  `MongoDbLogAnalyzerApplication.main` 调用 `SpringApplication.run`。`application.yml` 固定 `server.address: 127.0.0.1`、`server.port: 18080`，数据目录默认 `${user.dir}/data`，上传单文件与请求上限均为 `12GB`。

- [ ] **Step 4: 创建 Vue 最小页面与 Maven 前端构建**

  `web/package.json` 提供 `dev`、`build`、`test` 脚本。Vite 输出到 `../target/generated-resources/static`。Maven 在 `prepare-package` 阶段运行 `npm ci` 和 `npm run build`，并把输出复制到 `target/classes/static`，避免普通后端单测重复安装前端依赖。

- [ ] **Step 5: 运行绿灯和构建**

  Run: `mvn test && cd web && npm test -- --run && npm run build`
  Expected: PASS，且 `web/dist` 或配置的静态输出目录存在。

- [ ] **Step 6: 提交**

  ```bash
  git add CLAUDE.md ROADMAP.md .gitignore pom.xml src web docs
  git commit -m "init: 建立离线 MongoDB 日志分析工具骨架"
  ```

### Task 2：统一解析模型与结构化日志解析

**Files:**
- Create: `src/main/java/com/whaleal/mongodblog/parser/ParseStatus.java`
- Create: `src/main/java/com/whaleal/mongodblog/parser/ParseOutcome.java`
- Create: `src/main/java/com/whaleal/mongodblog/parser/ParsedLogEntry.java`
- Create: `src/main/java/com/whaleal/mongodblog/parser/LogParser.java`
- Create: `src/main/java/com/whaleal/mongodblog/parser/StructuredLogParser.java`
- Create: `src/main/java/com/whaleal/mongodblog/parser/QueryPatternNormalizer.java`
- Test: `src/test/java/com/whaleal/mongodblog/parser/StructuredLogParserTest.java`
- Test fixture: `src/test/resources/fixtures/structured.log`

**Interfaces:**
- Produces: `ParseOutcome LogParser.parse(String line, long lineNumber, int fileIndex)`。
- Produces: `String QueryPatternNormalizer.normalize(Object value)`。

- [ ] **Step 1: 写结构化解析失败测试**

  使用手工固定的 MongoDB Extended JSON 行，断言时间、严重级别、组件、上下文、操作、namespace、耗时、CPU、响应长度、计划、IP、查询模式和原始行。再加入 BOM、可选字段缺失和损坏 JSON 三个独立测试。

- [ ] **Step 2: 运行红灯**

  Run: `mvn -Dtest=StructuredLogParserTest test`
  Expected: FAIL，提示解析类型不存在。

- [ ] **Step 3: 实现最小结构化解析**

  使用 `org.bson.Document.parse` 解析 Extended JSON；将 `$date` 转为 `Instant`；按 command 键确定 operation；规范化 filter、pipeline、query、deletes 和 indexes。异常映射为 `PARTIAL` 或 `FAILED`，不得空 catch。

- [ ] **Step 4: 运行绿灯**

  Run: `mvn -Dtest=StructuredLogParserTest test`
  Expected: PASS。

- [ ] **Step 5: 提交**

  ```bash
  git add src/main/java/com/whaleal/mongodblog/parser src/test
  git commit -m "feat: 解析 MongoDB 结构化日志并规范化慢查询字段"
  ```

### Task 3：旧版文本日志解析

**Files:**
- Create: `src/main/java/com/whaleal/mongodblog/parser/LegacyLogParser.java`
- Create: `src/main/java/com/whaleal/mongodblog/parser/BalancedDocumentExtractor.java`
- Create: `src/main/java/com/whaleal/mongodblog/parser/CompositeLogParser.java`
- Test: `src/test/java/com/whaleal/mongodblog/parser/LegacyLogParserTest.java`
- Test: `src/test/java/com/whaleal/mongodblog/parser/BalancedDocumentExtractorTest.java`
- Test fixture: `src/test/resources/fixtures/legacy.log`

**Interfaces:**
- Consumes: `LogParser`、`ParseOutcome`、`ParsedLogEntry`。
- Produces: `Optional<String> BalancedDocumentExtractor.extractAfter(String text, String marker)`。
- Produces: `CompositeLogParser`，按内容选择结构化或旧版解析器。

- [ ] **Step 1: 写括号扫描器失败测试**

  测试嵌套文档、字符串内大括号、转义引号、缺少闭括号和 marker 不存在。期望值使用手工字符串字面量。

- [ ] **Step 2: 运行红灯并实现括号扫描器**

  Run: `mvn -Dtest=BalancedDocumentExtractorTest test`
  Expected: FAIL。实现逐字符状态机后重新运行并确认 PASS。

- [ ] **Step 3: 写八类旧版操作失败测试**

  分别断言 `find`、`aggregate`、`insert`、`update`、`remove`、`getMore`、`findAndModify`、`createIndexes` 的 operation、namespace、durationMillis、planSummary 和 pattern。增加 NETWORK、心跳失败、空行与异常行测试。

- [ ] **Step 4: 运行红灯并实现旧版解析**

  Run: `mvn -Dtest=LegacyLogParserTest test`
  Expected: FAIL。实现五段基础切分、命令提取、尾部指标解析和降级 `PARTIAL` 后确认 PASS。

- [ ] **Step 5: 运行全部解析测试并提交**

  Run: `mvn -Dtest='*ParserTest,*ExtractorTest' test`
  Expected: PASS。

  ```bash
  git add src/main/java/com/whaleal/mongodblog/parser src/test
  git commit -m "feat: 兼容 MongoDB 旧版文本日志和异常行"
  ```

### Task 4：Top 5000 与全量统计

**Files:**
- Create: `src/main/java/com/whaleal/mongodblog/analysis/SlowQueryRecord.java`
- Create: `src/main/java/com/whaleal/mongodblog/analysis/TopSlowQueryCollector.java`
- Create: `src/main/java/com/whaleal/mongodblog/analysis/DurationBucket.java`
- Create: `src/main/java/com/whaleal/mongodblog/analysis/DurationDistribution.java`
- Create: `src/main/java/com/whaleal/mongodblog/analysis/AnalysisAccumulator.java`
- Create: `src/main/java/com/whaleal/mongodblog/analysis/AnalysisSummary.java`
- Test: `src/test/java/com/whaleal/mongodblog/analysis/TopSlowQueryCollectorTest.java`
- Test: `src/test/java/com/whaleal/mongodblog/analysis/DurationDistributionTest.java`
- Test: `src/test/java/com/whaleal/mongodblog/analysis/AnalysisAccumulatorTest.java`

**Interfaces:**
- Produces: `void TopSlowQueryCollector.offer(SlowQueryRecord record)` 和 `List<SlowQueryRecord> sorted()`。
- Produces: `void AnalysisAccumulator.accept(ParseOutcome outcome)` 和 `AnalysisSummary finish()`。

- [ ] **Step 1: 写 Top K 边界失败测试**

  容量设为 3，输入耗时 `100、500、200、500、300`，手工断言最终为 `500、500、300`；增加相同耗时按时间和行号稳定排序的测试。

- [ ] **Step 2: 红灯、最小堆实现、绿灯**

  Run: `mvn -Dtest=TopSlowQueryCollectorTest test`
  Expected: 先 FAIL，使用 `PriorityQueue` 实现后 PASS。

- [ ] **Step 3: 写八区间边界失败测试**

  输入 `99、100、499、500、999、1000、2999、3000、9999、10000、29999、30000、59999、60000`，逐桶断言计数、总耗时、平均值和最大值。

- [ ] **Step 4: 红灯、区间实现、绿灯**

  Run: `mvn -Dtest=DurationDistributionTest test`
  Expected: 先 FAIL，实现规格中的左闭右开边界后 PASS。

- [ ] **Step 5: 写并实现全量聚合测试**

  使用 6 条手工日志结果，断言解析质量、operation、namespace、pattern、plan、IP、CPU 和心跳统计。确认聚合来自全部记录，而 Top K 只限制明细。

- [ ] **Step 6: 运行分析测试并提交**

  Run: `mvn -Dtest='*CollectorTest,*DistributionTest,*AccumulatorTest' test`
  Expected: PASS。

  ```bash
  git add src/main/java/com/whaleal/mongodblog/analysis src/test
  git commit -m "feat: 精确统计全部慢查询并保留最慢 Top 5000"
  ```

### Task 5：任务执行与文件持久化

**Files:**
- Create: `src/main/java/com/whaleal/mongodblog/task/AnalysisTask.java`
- Create: `src/main/java/com/whaleal/mongodblog/task/TaskStatus.java`
- Create: `src/main/java/com/whaleal/mongodblog/task/TaskService.java`
- Create: `src/main/java/com/whaleal/mongodblog/task/TaskRunner.java`
- Create: `src/main/java/com/whaleal/mongodblog/storage/TaskRepository.java`
- Create: `src/main/java/com/whaleal/mongodblog/storage/FileTaskRepository.java`
- Create: `src/main/java/com/whaleal/mongodblog/config/TaskExecutorConfig.java`
- Test: `src/test/java/com/whaleal/mongodblog/storage/FileTaskRepositoryTest.java`
- Test: `src/test/java/com/whaleal/mongodblog/task/TaskRunnerTest.java`

**Interfaces:**
- Produces: `AnalysisTask TaskService.create(String name, List<MultipartFile> files)`。
- Produces: `Optional<AnalysisTask> TaskService.get(String id)`、`List<AnalysisTask> TaskService.list()`。
- Produces: `AnalysisSummary TaskRepository.readSummary(String id)` 和 Top K 分页读取。

- [ ] **Step 1: 写文件仓库失败测试**

  使用 `@TempDir` 验证任务元数据、summary 和 JSONL 往返一致；验证写入后不存在残留 `.tmp`；验证启动时把遗留 `RUNNING` 标记为 `FAILED`。

- [ ] **Step 2: 红灯、原子文件仓库实现、绿灯**

  Run: `mvn -Dtest=FileTaskRepositoryTest test`
  Expected: 先 FAIL，实现后 PASS。

- [ ] **Step 3: 写流式任务失败测试**

  输入一个普通样例和一个 GZip 样例，断言两者全部行被处理、Top K 和分布正确、状态最终为 `COMPLETED`，并且工作目录被清理。损坏 GZip 必须得到 `FAILED` 和明确错误。

- [ ] **Step 4: 红灯、任务执行实现、绿灯**

  Run: `mvn -Dtest=TaskRunnerTest test`
  Expected: 先 FAIL，实现 CountingInputStream、GZIPInputStream、BufferedReader 流式处理后 PASS。

- [ ] **Step 5: 提交**

  ```bash
  git add src/main/java/com/whaleal/mongodblog/task src/main/java/com/whaleal/mongodblog/storage src/main/java/com/whaleal/mongodblog/config src/test
  git commit -m "feat: 异步执行日志任务并原子保存分析结果"
  ```

### Task 6：REST API 闭环

**Files:**
- Create: `src/main/java/com/whaleal/mongodblog/web/TaskController.java`
- Create: `src/main/java/com/whaleal/mongodblog/web/ApiError.java`
- Create: `src/main/java/com/whaleal/mongodblog/web/GlobalExceptionHandler.java`
- Create: `src/main/java/com/whaleal/mongodblog/web/SlowQueryPage.java`
- Test: `src/test/java/com/whaleal/mongodblog/web/TaskControllerIntegrationTest.java`

**Interfaces:**
- Consumes: `TaskService` 和 `TaskRepository`。
- Produces: 规格第 10 节的六个 `/api/tasks` 接口。

- [ ] **Step 1: 写上传到结果查询的失败集成测试**

  用 MockMvc 上传 `structured.log`，轮询任务直到终态，断言 summary 的慢查询总数、耗时桶和 Top 查询；验证空文件返回 HTTP 400、未知任务返回 HTTP 404。

- [ ] **Step 2: 运行红灯**

  Run: `mvn -Dtest=TaskControllerIntegrationTest test`
  Expected: FAIL，接口不存在。

- [ ] **Step 3: 实现控制器和统一错误**

  上传返回 HTTP 202 和任务对象；分页参数限制 `page >= 1`、`1 <= size <= 200`；原始日志始终作为 JSON 字符串返回。

- [ ] **Step 4: 运行绿灯并提交**

  Run: `mvn -Dtest=TaskControllerIntegrationTest test`
  Expected: PASS。

  ```bash
  git add src/main/java/com/whaleal/mongodblog/web src/test
  git commit -m "feat: 提供日志任务上传和分析结果接口"
  ```

### Task 7：Vue 页面与图表

**Files:**
- Create: `web/src/api/tasks.js`
- Create: `web/src/components/UploadPanel.vue`
- Create: `web/src/components/TaskList.vue`
- Create: `web/src/components/SummaryCards.vue`
- Create: `web/src/components/DurationHistogram.vue`
- Create: `web/src/components/BreakdownCharts.vue`
- Create: `web/src/components/PatternTable.vue`
- Create: `web/src/components/SlowQueryTable.vue`
- Create: `web/src/components/SlowQueryDetail.vue`
- Create: `web/src/utils/format.js`
- Test: `web/tests/format.test.js`
- Test: `web/tests/duration-histogram.test.js`

**Interfaces:**
- Consumes: `/api/tasks` 接口。
- Produces: 上传、进度、汇总、耗时区间、Top 50 pattern、Top 5000 和详情交互。

- [ ] **Step 1: 写格式化与耗时分布失败测试**

  断言毫秒、字节和百分比格式；挂载 `DurationHistogram`，断言八个桶按规格顺序显示，零数据时显示明确空状态。

- [ ] **Step 2: 运行红灯**

  Run: `cd web && npm test -- --run`
  Expected: FAIL，组件与工具不存在。

- [ ] **Step 3: 实现 API、上传和任务轮询**

  使用 `fetch` 和 `FormData`，每秒轮询当前任务；终态后停止定时器并加载 summary。组件销毁时清理定时器。

- [ ] **Step 4: 实现统计图表和表格**

  ECharts 柱状图展示八个耗时桶；Operation、namespace、plan、IP 和 CPU 使用饼图或条形图；每张图提供 tooltip 与精确表格。Top 5000 使用服务端分页和过滤，详情以纯文本展示。

- [ ] **Step 5: 运行测试和生产构建**

  Run: `cd web && npm test -- --run && npm run build`
  Expected: PASS，生产构建无错误。

- [ ] **Step 6: 提交**

  ```bash
  git add web
  git commit -m "feat: 展示慢查询耗时分布和 Top 5000 明细"
  ```

### Task 8：一键启动、端到端验证与文档

**Files:**
- Create: `src/main/java/com/whaleal/mongodblog/config/BrowserLauncher.java`
- Create: `scripts/start.sh`
- Create: `scripts/start.command`
- Create: `scripts/start.bat`
- Create: `README.md`
- Modify: `ROADMAP.md`
- Test: `src/test/java/com/whaleal/mongodblog/config/BrowserLauncherTest.java`

**Interfaces:**
- Produces: 三个平台的一键启动入口和完整使用说明。

- [ ] **Step 1: 写浏览器启动地址失败测试**

  把外部打开动作注入为函数，断言应用就绪时只请求打开 `http://127.0.0.1:18080`，测试不真实启动浏览器。

- [ ] **Step 2: 红灯、最小实现、绿灯**

  Run: `mvn -Dtest=BrowserLauncherTest test`
  Expected: 先 FAIL，实现后 PASS。

- [ ] **Step 3: 创建启动脚本**

  三个脚本均从自身目录定位 JAR，验证 Java 主版本不低于 17，使用 `-Xms128m -Xmx512m` 启动。脚本失败时保留窗口并显示中文错误，不修改系统设置。

- [ ] **Step 4: 编写 README**

  说明支持格式、数据保存范围、隐私、运行、构建、测试、数据目录和常见错误。明确只永久保存 Top 5000，耗时分布基于全部慢查询。

- [ ] **Step 5: 全量验证**

  Run: `mvn clean test`
  Expected: 所有 Java 测试 PASS。

  Run: `cd web && npm test -- --run && npm run build`
  Expected: 所有前端测试和构建 PASS。

  Run: `mvn package`
  Expected: 生成可执行 JAR，JAR 内包含 `static/index.html`。

  启动 JAR，使用结构化和旧版样例完成一次真实上传，核对任务完成、八个耗时桶、Top 5000 排序和详情；记录结果到 `ROADMAP.md`。

- [ ] **Step 6: 最终提交**

  ```bash
  git add README.md ROADMAP.md scripts src web pom.xml
  git commit -m "docs: 补充一键启动说明和最终验证记录"
  ```
