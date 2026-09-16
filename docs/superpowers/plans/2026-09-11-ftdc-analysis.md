# FTDC 指标分析实施计划

**Goal：** 在现有离线 MongoDB Log Analyzer 中增加最多 20 文件的 FTDC 指标组查询能力，使用原始文件和二进制索引持久化，在 Java 最大堆 `-Xmx2g` 的约束下完成真实数据处理而不全量物化时间序列。

**Architecture：** FTDC 任务与日志任务隔离；上传文件保存为任务数据源，流式扫描生成 `blocks.idx` 和小型 catalog。上传解析和指标组查询共用一个公平串行许可。页面一次查询一个指标组，同一 block 一次恢复组内目标列，处理完成后立即释放。

**Tech Stack：** Java 17、Spring Boot 3.4.5、MongoDB BSON 5.2.1、JDK zlib／NIO／GZip、Vue 3、Element Plus、ECharts、Vitest；不新增运行时依赖。

**Spec：** [FTDC 指标分析设计规格](../specs/2026-09-11-ftdc-analysis-design.md) 。

## 全局约束

- 动手前先修改 `CLAUDE.md`，明确 FTDC 原始文件和二进制索引是本地持久化的允许例外。
- 所有生产行为先写失败测试，再写最小实现。
- 禁止复制旧项目的 `Map<String, List<Long>>` 全量物化方式。
- 禁止一次性读取完整 FTDC 文件。
- FTDC 重型操作严格串行。
- 发布启动参数使用 `-Xms128m -Xmx2g`，Java 堆最大允许 2 GB。
- 不依赖 `System.gc()` 保证内存安全。
- 不修改现有日志任务的数据口径、存储结构和接口行为。
- 用户提供的 10 MB 真实文件只用于本机验收，不提交到 Git。
- 完成并验证每个阶段后更新 `ROADMAP.md`，未验证事项不得标为完成。

## Task 1：规范、模型边界与测试夹具

**Files：**

- Modify：`CLAUDE.md`
- Modify：`ROADMAP.md`
- Create：`src/test/java/com/whaleal/mongodblog/parser/ftdc/FtdcFixtureBuilder.java`
- Create：`src/test/resources/fixtures/ftdc-golden.txt`

- [ ] 更新项目目标、目录约定、持久化规则、FTDC 流式处理和指标组有界查询红线。
- [ ] 在 `ROADMAP.md` 标记 FTDC 开发进入进行中。
- [ ] 创建仅用于测试的最小 FTDC 构造器，可生成 baseline、正负 delta、零游程和损坏输入，不引入生产编码器。
- [ ] 固定少量手工可复算的 golden 值，避免单元测试依赖 10 MB 真实文件。
- [ ] 确认只修改规范和测试基础设施，没有改变现有运行行为。

## Task 2：外层 BSON 流式读取

**Files：**

- Create：`src/main/java/com/whaleal/mongodblog/parser/ftdc/FtdcFileReader.java`
- Create：`src/main/java/com/whaleal/mongodblog/parser/ftdc/FtdcDocument.java`
- Create：`src/main/java/com/whaleal/mongodblog/parser/ftdc/FtdcFormatException.java`
- Test：`src/test/java/com/whaleal/mongodblog/parser/ftdc/FtdcFileReaderTest.java`

- [ ] 先写连续 BSON、type=0、type=1、无 type=1、非法长度和截断文档测试。
- [ ] 运行 `mvn -Dtest=FtdcFileReaderTest test`，确认新增用例失败。
- [ ] 使用 `InputStream` 和小端长度读取实现逐文档扫描；每次只分配当前 BSON 文档。
- [ ] 记录文档文件偏移和长度，为后续随机读取 block 使用。
- [ ] 为单 BSON 文档和 block 声明长度增加分配前安全校验。
- [ ] 重新运行测试并确认通过。

## Task 3：baseline 展平、uvarint 与 RLE 解码

**Files：**

- Create：`src/main/java/com/whaleal/mongodblog/parser/ftdc/FtdcSchema.java`
- Create：`src/main/java/com/whaleal/mongodblog/parser/ftdc/FtdcBaselineFlattener.java`
- Create：`src/main/java/com/whaleal/mongodblog/parser/ftdc/FtdcVarIntReader.java`
- Create：`src/main/java/com/whaleal/mongodblog/parser/ftdc/FtdcBlockScanner.java`
- Test：`src/test/java/com/whaleal/mongodblog/parser/ftdc/FtdcBlockScannerTest.java`

- [ ] 先写嵌套文档、数组、Boolean、Int32、Int64、DateTime、Timestamp 和忽略字符串测试。
- [ ] 写正 delta、负 delta、64 位回绕、常量列和跨属性 RLE 测试。
- [ ] 写属性数量不符、varint EOF、varint 溢出、zlib 损坏和解压长度不符测试。
- [ ] 运行 `mvn -Dtest=FtdcBlockScannerTest test`，确认失败。
- [ ] 实现 baseline 展平和严格 uvarint 读取；错误必须抛出带文件偏移和 block 序号的 `FtdcFormatException`。
- [ ] 扫描 delta 流并生成 baseline、delta 偏移和 `zerosAtStart`，确保零游程跨属性保持。
- [ ] 对解码结束位置和序列点数做一致性检查。
- [ ] 重新运行测试并确认通过。

## Task 4：二进制索引格式与原子存储

**Files：**

- Create：`src/main/java/com/whaleal/mongodblog/storage/ftdc/FtdcIndexWriter.java`
- Create：`src/main/java/com/whaleal/mongodblog/storage/ftdc/FtdcIndexReader.java`
- Create：`src/main/java/com/whaleal/mongodblog/storage/ftdc/FtdcCatalog.java`
- Create：`src/main/java/com/whaleal/mongodblog/storage/ftdc/FtdcBlockIndex.java`
- Test：`src/test/java/com/whaleal/mongodblog/storage/ftdc/FtdcIndexRepositoryTest.java`

- [ ] 先写索引往返、magic、版本、多个 schema、多个文件、源文件 SHA-256 不符和截断索引测试。
- [ ] 运行 `mvn -Dtest=FtdcIndexRepositoryTest test`，确认失败。
- [ ] 按规格实现 little-endian `blocks.idx`，使用 `FileChannel` 定位读取，不整体载入索引。
- [ ] 相同 schema 复用路径字典，catalog 使用指标路径并集和稳定 `metricId`。
- [ ] 索引和 catalog 使用临时文件加原子移动发布，失败时不留下可查询的半成品。
- [ ] 重新运行测试并确认通过。

## Task 5：FTDC 任务、持久化与串行许可

**Files：**

- Create：`src/main/java/com/whaleal/mongodblog/task/ftdc/FtdcTask.java`
- Create：`src/main/java/com/whaleal/mongodblog/task/ftdc/FtdcTaskStatus.java`
- Create：`src/main/java/com/whaleal/mongodblog/task/ftdc/FtdcTaskInputFile.java`
- Create：`src/main/java/com/whaleal/mongodblog/task/ftdc/FtdcOperationGate.java`
- Create：`src/main/java/com/whaleal/mongodblog/task/ftdc/FtdcTaskService.java`
- Create：`src/main/java/com/whaleal/mongodblog/task/ftdc/FtdcTaskRunner.java`
- Create：`src/main/java/com/whaleal/mongodblog/storage/ftdc/FtdcTaskRepository.java`
- Create：`src/main/java/com/whaleal/mongodblog/storage/ftdc/FileFtdcTaskRepository.java`
- Test：`src/test/java/com/whaleal/mongodblog/task/ftdc/FtdcTaskRunnerTest.java`
- Test：`src/test/java/com/whaleal/mongodblog/storage/ftdc/FileFtdcTaskRepositoryTest.java`

- [ ] 先写零文件、空文件、超过 20 个文件、内容不是 FTDC 和损坏 FTDC 测试。
- [ ] 写多文件逐个处理、进度、成功发布、失败清理、重启恢复和任务删除隔离测试。
- [ ] 写并发测试证明索引构建、查询和导出最多一个执行，异常及中断后许可必定释放。
- [ ] 运行相关测试，确认失败。
- [ ] 实现 FTDC 独立任务目录、任务索引和公平 `Semaphore(1, true)`。
- [ ] 上传副本先进入工作目录；索引完成后，把源文件和结果原子发布到 FTDC 任务目录。
- [ ] 任务完成后保留 FTDC 源副本；删除操作只清理目标 FTDC 任务。
- [ ] 重新运行相关测试并确认通过。

## Task 6：有界列解码与图表查询基础

**Files：**

- Create：`src/main/java/com/whaleal/mongodblog/analysis/ftdc/FtdcMetricDecoder.java`
- Create：`src/main/java/com/whaleal/mongodblog/analysis/ftdc/FtdcSeriesQuery.java`
- Create：`src/main/java/com/whaleal/mongodblog/analysis/ftdc/FtdcSeriesResult.java`
- Create：`src/main/java/com/whaleal/mongodblog/analysis/ftdc/FtdcSeriesService.java`
- Test：`src/test/java/com/whaleal/mongodblog/analysis/ftdc/FtdcSeriesServiceTest.java`

- [ ] 先写单 block、跨 block、跨文件、不同 schema、指标缺失、时间过滤和重叠时间测试。
- [ ] 写 `raw` 最后值降采样、`delta` 相邻差值、首点 `null` 和最大点数测试。
- [ ] 运行 `mvn -Dtest=FtdcSeriesServiceTest test`，确认失败。
- [ ] 通过索引直接定位目标列，不扫描或保存无关指标值。
- [ ] 每次只解压一个 block；完成聚合后释放 block 引用再处理下一个。
- [ ] 返回有界的图表结果，不创建完整原始序列。
- [ ] 重新运行测试并确认通过。

## Task 7：内部流式能力

**Files：**

- Create：`src/main/java/com/whaleal/mongodblog/analysis/ftdc/FtdcMetricPage.java`
- Create：`src/main/java/com/whaleal/mongodblog/analysis/ftdc/FtdcMetricExportService.java`
- Test：`src/test/java/com/whaleal/mongodblog/analysis/ftdc/FtdcMetricPagingTest.java`
- Test：`src/test/java/com/whaleal/mongodblog/analysis/ftdc/FtdcMetricExportServiceTest.java`

- [ ] 保留内部分页与流式遍历回归测试，不作为产品 API 或页面功能。
- [ ] 运行相关测试，确认失败。
- [ ] 使用 block 累计点偏移定位页面，单页最多保存 1,000 个点。
- [ ] 内部遍历不得创建完整序列、CSV 字符串或临时导出文件。
- [ ] 重新运行测试并确认通过。

## Task 8：REST API

**Files：**

- Create：`src/main/java/com/whaleal/mongodblog/web/FtdcTaskController.java`
- Test：`src/test/java/com/whaleal/mongodblog/web/FtdcTaskControllerIntegrationTest.java`

- [ ] 先写上传、任务列表、进度、指标组目录、组序列和删除闭环测试。
- [ ] 写任务未完成、未知任务、非法指标组、非法时间和超过 20 文件测试。
- [ ] 运行 `mvn -Dtest=FtdcTaskControllerIntegrationTest test`，确认失败。
- [ ] 实现 `/api/ftdc-tasks` 独立接口，复用统一错误响应语义。
- [ ] 只暴露指标组目录和组序列接口；连接断开后取消解码并释放许可。
- [ ] 重新运行集成测试并确认通过。

## Task 9：FTDC 前端工作区

**Files：**

- Modify：`web/src/App.vue`
- Create：`web/src/api/ftdcTasks.js`
- Create：`web/src/components/ftdc/FtdcWorkspace.vue`
- Create：`web/src/components/ftdc/FtdcUploadPanel.vue`
- Create：`web/src/components/ftdc/FtdcTaskList.vue`
- Create：`web/src/components/ftdc/FtdcMetricGroupChart.vue`
- Test：`web/tests/ftdc-workspace.test.js`
- Test：`web/tests/ftdc-races.test.js`

- [ ] 先写日志／FTDC 一级切换、上传限制、任务进度和完成结果测试。
- [ ] 写指标组筛选、单组选择、原始／差值视图和合并／分开展示测试。
- [ ] 写快速切换任务或指标组时取消旧请求、旧响应不得覆盖新选择的竞态测试。
- [ ] 运行 `cd web && npm test -- --run`，确认新增用例失败。
- [ ] 实现 FTDC 工作区，保持现有日志工作区行为和样式不变。
- [ ] 按旧版页面布局说明实现纵向 Dashboard／Card、指标组 Group Card、左侧约 300px 指标信息和右侧自适应图表；窄屏切换为上下布局。
- [ ] 实现有界多指标 Group 查询、Group Collapse 和 Group Split，不迁移异常检测和 JVM 卡片。
- [ ] 页面一次只请求当前指标组，每指标图表最多处理 1,200 点。
- [ ] 重新运行前端测试和 `npm run build`，确认通过。

## Task 10：真实数据、20 文件和内存验收

**Files：**

- Modify：`README.md`
- Modify：`ROADMAP.md`
- Modify：`scripts/start.command`
- Modify：`scripts/start.sh`
- Modify：`scripts/start.bat`

- [ ] 运行 `mvn test`，确认全部后端测试通过。
- [ ] 运行 `cd web && npm test -- --run && npm run build`，确认全部前端测试与构建通过。
- [ ] 运行 `mvn package`，确认生成包含 FTDC 页面资源的可启动 JAR。
- [ ] 使用真实 10 MB 样本核对 96 个 block、2,082 个指标、28,800 点和关键指标逐值结果。
- [ ] 在隔离数据目录构造 20 文件任务，确认文件逐个处理、索引完整、任务可重启读取。
- [ ] 将三个启动脚本的 Java 参数统一为 `-Xms128m -Xmx2g`。
- [ ] 使用 `-Xmx2g` 完成单文件及 20 文件的建索引和指标组图表查询，确认不发生 `OutOfMemoryError`。
- [ ] 记录各阶段堆使用；确认处理完文件后堆不会随文件数量持续单调增长。
- [ ] 确认单文件 `blocks.idx` 不超过 5 MB；若超过，先解释并复核索引布局，不直接放宽标准。
- [ ] 启动 JAR，在浏览器完成 FTDC 上传、进度、指标组查询、合并／分开展示和任务删除。
- [ ] 更新 README 的功能、数据目录、磁盘保留、隐私、内存边界和常见问题。
- [ ] 将已实现且已验证的事项写入 `ROADMAP.md` 已完成；未验证事项保留在进行中或阻塞。

## 最终验收门槛

- 一个 10 MB 真实 FTDC 文件不得产生约 1.5 GB Java 对象。
- 20 文件任务不得并行解压，堆内存不得随已完成文件数量持续增长。
- 指标组图表数值与预先固化的验收向量一致。
- 任意解析错误不得静默吞掉或截断为看似成功的结果。
- FTDC 任务删除不得影响日志任务、其他 FTDC 任务或用户原始文件。
- 现有日志后端和前端测试全部保持通过。
- 最终 JAR 仍只依赖 Java 17 运行，不需要数据库、Node.js 或网络服务。

## Task 11：多指标分组图与拆分图

**Files：**

- Create：`src/main/java/com/whaleal/mongodblog/analysis/ftdc/FtdcMetricGroups.java`
- Create：`src/main/java/com/whaleal/mongodblog/analysis/ftdc/FtdcGroupSeriesResult.java`
- Modify：`src/main/java/com/whaleal/mongodblog/storage/ftdc/FtdcIndexReader.java`
- Modify：`src/main/java/com/whaleal/mongodblog/analysis/ftdc/FtdcMetricDecoder.java`
- Modify：`src/main/java/com/whaleal/mongodblog/analysis/ftdc/FtdcSeriesService.java`
- Modify：`src/main/java/com/whaleal/mongodblog/web/FtdcTaskController.java`
- Create：`web/src/components/ftdc/FtdcMetricGroupChart.vue`
- Modify：`web/src/components/ftdc/FtdcWorkspace.vue`

- [x] 先写特殊路径归组、稳定 groupId、分组序列及 API 测试。
- [x] 同一 Block 一次解压多个目标列，分组查询全程持有一个公平串行许可。
- [x] 限制每组最多 200 个指标、每指标最多 1,200 个返回点。
- [x] 前端实现单组选择、纵向 Group Card、合并图／拆分图和折叠；产品不保留单指标查询入口，不允许一次渲染全部指标组。

## Task 12：收口为纯指标组查询

- [x] 移除页面的单指标模式、指标目录、分页、导出及组内指标跳转。
- [x] 移除对外单指标 catalog／series／values／export 接口，只保留内部有界列解码能力。
- [x] 更新页面与接口测试，确认产品只存在指标组查询入口。
- [x] 跑完整后端、前端和真实样本回归后更新 `ROADMAP.md`。

## Task 13：指标组图表运维辅助

- [x] 先写全零指标隐藏、合并／拆分共用筛选结果和时间断点测试。
- [x] 使用当前返回数据的 `min`、`max`、`avg` 判定全零指标，并提供一键隐藏／恢复按钮。
- [x] 根据主要采样间隔识别明显时间断裂，插入 `null` 空点并在 Tooltip 中显示 `-`，禁止跨断点连线。
- [x] 运行前端测试、构建与浏览器验收，确认三种交互都可用。
