# FTDC 指标分析设计规格

## 1．目标

在现有 MongoDB Log Analyzer 中增加 FTDC 指标解析能力，同时保持本地离线、单 JAR、轻量和方便运行的产品定位。

一个 FTDC 任务允许上传最多 20 个 `diagnostic.data/metrics.*` 文件。程序把原始 FTDC 文件保存在本地任务目录，建立紧凑二进制索引；产品查询入口只提供有界指标组查询，串行解码组内指标跨全部文件的时间序列。

首要成功标准是内存占用有明确上界。Java 最大堆按 `-Xmx2g` 配置；文件数和总采样点增加时，处理时间和磁盘使用可以线性增加，但运行时不得将全部指标值一次性物化到 Java 堆。

## 2．已确认口径

- FTDC 产品查询只以指标组为单位，不提供单指标查询入口。
- 分组图每指标允许降采样，最多返回 1,200 个点。
- FTDC 上传解析、索引重建和指标组查询共用一个公平的串行许可。
- 查询性能可以让位于内存稳定性。
- 不引入 MongoDB、DuckDB、SQLite、Parquet、Arrow、RocksDB 或其他运行时存储依赖。
- 继续使用项目已有的 MongoDB BSON 库和 JDK 自带的 zlib、NIO、并发及压缩能力。

## 3．非目标

- 不把全部指标与全部时间点一次性加载进内存。
- 不提供一次性返回“全部指标 × 全部时间点”的 HTTP 接口。
- 不把完整时间序列保存为 JSON 或 JSONL。
- 不把每个指标点展开成数据库行。
- 首期不迁移旧项目的异常检测、`CORE_METRICS`、整份 `key.txt` 和指标语义推断器。
- 不实现指标组并行解码或多任务并行 FTDC 解码。
- 不依赖显式调用 `System.gc()` 保证正确性。

## 4．为什么选择原始 FTDC 加二进制索引

10,485,242 字节的真实样本包含 96 个 block、2,082 个指标和 28,800 个采样点，逻辑数值约 5,996 万个。若完整展开为 `Map<String, List<Long>>`，需要约 1.5 GB Java 堆。

FTDC 本身已经使用基线、delta、零游程和 zlib 压缩。保留原始 FTDC 可以避免重复保存完整序列；额外索引只解决按指标定位问题。

对该样本，索引的主要空间预计为：

- baseline：`96 × 2082 × 8`，约 1.6 MB。
- delta 偏移：`96 × 2082 × 4`，约 0.8 MB。
- RLE 起始状态：`96 × 2082 × 4`，约 0.8 MB。
- schema、block 元数据和文件信息：预计小于 1 MB。

因此一个 10 MB FTDC 文件预计产生约 3～4 MB 索引。20 个同等文件预计保存约 200 MB 原始数据和 60～80 MB 索引，不产生数十 GB 的展开值。

## 5．FTDC 格式与解码规则

### 5.1 外层文件

FTDC 文件是一串连续 BSON 文档。每个文档以 little-endian `int32` 总长度开头：

```text
[BSON type=0 metadata]
[BSON type=1 metrics block]
[BSON type=1 metrics block]
...
```

- `type=0`：文件级元数据，本期只记录存在性，不展开进入时间序列。
- `type=1`：指标压缩 block，二进制字段为 `data`。
- `data` 前 4 字节声明解压长度，剩余字节为 zlib 数据。

读取器必须顺序读取单个 BSON 文档，禁止读取整个文件到内存。

### 5.2 解压后 block

```text
[baseline BSON]
[int32 numAttribs]
[int32 numDeltas]
[uvarint delta stream]
```

- baseline BSON 是第一个采样点。
- 每个指标总点数为 `numDeltas + 1`。
- 嵌套文档使用 `/` 拼接路径；数组下标作为路径段。
- Boolean 转为 `0` 或 `1`；Int32、Int64、DateTime 和 Timestamp 按已验证语义转成 `long`。
- String、ObjectId 和不参与 FTDC 数值 delta 的字段不进入指标列表。
- baseline 展平后的属性数必须等于 `numAttribs`。

### 5.3 delta 与 RLE

- delta 使用 unsigned varint。
- 负增量通过 64 位补码形式编码，累加保持 Java `long` 回绕语义。
- 读到 `delta == 0` 时，下一个 uvarint 是连续零增量数量。
- `zerosLeft` 位于属性循环之外，可以跨指标边界延续。
- EOF、varint 溢出、非法 RLE、属性数量不符或 delta 流未按预期结束必须产生明确错误，不得把错误当成零。

## 6．磁盘布局

FTDC 任务与现有日志任务隔离：

```text
data/
├── tasks/                              # 现有日志任务，不变
├── ftdc/
│   ├── tasks-index.json
│   └── tasks/<task-id>/
│       ├── metadata.json
│       ├── catalog.json
│       ├── blocks.idx
│       └── source/
│           ├── 0000-metrics.*
│           └── ...
└── work/
```

- `metadata.json`：FTDC 任务状态、进度、文件、错误和时间信息。
- `catalog.json`：指标 ID、指标路径、schema、block 数、样本数和总时间范围。
- `blocks.idx`：按本规格定义的二进制索引。
- `source/`：应用保存的 FTDC 原始副本，是时间序列的权威数据源。
- JSON 文件不得包含时间序列数组。
- 所有元数据和索引先写同目录临时文件，完成后原子移动。
- 任务删除时只删除应用任务目录，不触碰用户原文件。

当前项目规范在实施前需要调整：业务元数据继续使用 JSON／JSONL；FTDC 任务允许保留原始压缩文件和应用生成的二进制索引，但禁止持久化全量展开值。

## 7．二进制索引

`blocks.idx` 使用 little-endian 编码，包含固定 magic、格式版本和以下逻辑结构：

```text
Header
FileTable
SchemaTable
BlockTable
BlockAttributeIndex
```

### 7.1 文件表

每个源文件记录：

- 上传顺序和保存文件名。
- 文件大小。
- SHA-256。
- type=1 block 数。

### 7.2 Schema 表

- 指标路径按 FTDC baseline 原始顺序保存。
- 相同指标顺序复用同一个 schema。
- schema 变化时创建新 schema，不假定 20 个文件结构完全一致。
- `catalog.json` 保存所有 schema 指标路径的并集，为每个指标生成稳定 `metricId`。

### 7.3 Block 表

每个 block 记录：

- 源文件 ID、顶层 BSON 文件偏移和长度。
- 压缩长度、声明解压长度。
- schema ID、`numAttribs`、`numDeltas`。
- 首尾时间、任务内累计点偏移。
- baseline `long[]`。
- delta 起始偏移 `int[]`。
- 指标起始处尚未消费的零游程 `int[]`。

索引通过 `FileChannel` 定位读取，不在启动时整体加载，也不使用内存映射作为首版实现，避免不可控的驻留页和 Windows 文件删除问题。

## 8．任务与串行控制

FTDC 使用独立任务模型和存储，不扩展现有日志 `AnalysisTask`，避免两种任务字段互相污染。状态沿用：

- `QUEUED`
- `RUNNING`
- `COMPLETED`
- `FAILED`

一个任务最多接收 20 个非空文件。FTDC 文件通常没有固定扩展名，因此按内容验证，不以扩展名作为成功依据。

上传后按文件顺序保存副本，再由单线程依次扫描文件和 block。任务成功后保留 `source`；失败时保留任务错误，清理未完成索引和不完整结果。

所有 FTDC 重型操作使用同一个公平 `Semaphore(1, true)`：

1. 上传后的索引构建。
2. 索引重建。
3. 指标组图表查询。

等待中的 HTTP 请求只保留小型参数。获取许可必须可中断；客户端取消、线程中断或响应写入失败后立即停止解码并释放许可。

## 9．多指标分组图查询

- 本项目默认使用指标路径的父路径分组；`serverStatus/` 前缀只从组名中移除，不改变指标真实路径。
- WiredTiger cache／capacity／transaction 和 systemMetrics netstat 的特殊路径按本项目的固定前缀规则归入更细分组。
- 分组目录不返回时间序列；每个组使用稳定 `groupId`，并返回组名、指标数和指标元信息。
- 单次分组查询最多包含 200 个指标，每指标最多返回 1,200 个点。真实验收样本最大组为 133 个指标，能够完整查询。
- 同一 Block 只解压一次，再按索引恢复该组需要的列；禁止为了分组图对同一 Block 按指标重复解压。
- 一个组内各指标共享时间窗口和降采样宽度，但每个指标分别保留有效点、计算原始值或相邻差值。
- 页面一次只允许选择并查询一个指标组，避免同时创建数百个组卡片和 ECharts 实例；后端仍由公平串行许可保证任何时刻只解压一个 Block。
- 合并图把一个组的多条序列绘制在同一个 ECharts；拆分图只改变前端展示方式，不重复查询后端。
- 每个指标按当前返回值计算 `min`、`max`、`avg`；三者都为 0 时判定为全零指标。页面提供一键隐藏／恢复全零指标，合并图、拆分图和左侧指标信息使用同一筛选结果。
- 图表根据当前返回时间点的主要采样间隔识别时间断裂；相邻时间差明显超过主要间隔时插入 `null` 空点，ECharts 使用 `connectNulls=false` 断开折线，Tooltip 将空值显示为 `-`。

## 10．内存边界

生产实现必须满足：

- 发布启动参数使用 `-Xms128m -Xmx2g`，Java 堆硬上限为 2 GB。
- 禁止 `Files.readAllBytes` 读取完整 FTDC 文件。
- 禁止用 `List<Long>` 保存指标序列。
- 禁止构造全量 `Map<String, List<Long>>`。
- 禁止缓存全部解压 block。
- 一次只打开当前需要的文件和 block。
- block 内数值只使用原始数组。
- 解压前验证声明长度，并设置单 block 安全上限。
- 分组图每指标最多 1,200 点且每组最多 200 个指标。
- 不设置常驻全任务时间序列缓存。
- 不通过频繁 `System.gc()` 掩盖引用生命周期问题。

指标组跨 20 个文件查询时，处理时间随文件数增加，堆内存主要由当前 block、组内列数组和有限响应结果构成，不随总文件数量线性增长。

## 11．HTTP API

```text
POST   /api/ftdc-tasks
GET    /api/ftdc-tasks
GET    /api/ftdc-tasks/{taskId}
DELETE /api/ftdc-tasks/{taskId}

GET    /api/ftdc-tasks/{taskId}/groups
GET    /api/ftdc-tasks/{taskId}/groups/{groupId}/series
```

- 上传接口使用 multipart，参数为可选 `name` 和最多 20 个 `files`。
- `groups` 返回稳定分组目录，不返回时间序列。
- 分组 `series` 支持 `start`、`end`、`maxPoints`、`view=raw|delta`。

## 12．页面结构与旧版布局取舍

FTDC 页面沿用旧版页面的核心 DOM 和布局关系，并服从指标组有界查询边界：

- 页面根节点使用纵向 `.ftdc-dashboard`，上传、任务／数据状态和指标选择分别放入独立圆角 `.card`。
- 图表结果使用独立 `.charts-section`；其下 `.chart-grid` 和 `.group-chart-card` 纵向排列，不使用多列瀑布流。
- 指标组的 `.chart-body` 在桌面端使用 `300px minmax(0, 1fr)` 两列布局：左侧 `.metric-info-list` 展示组内指标及结果统计，右侧展示 ECharts。
- 页面宽度低于约 1,000px 时，`.chart-body` 切换为上下布局；图表默认高度约 320px。
- 指标组支持折叠、合并图／拆分图、隐藏全零指标和原始值／相邻差值切换。
- 多指标 Group 查询和 Group Split 按 9.4 的有界方式实现。异常检测、异常点联动和 JVM 信息卡仍不在本期实现，它们需要独立的数据口径，不能只按旧页面 DOM 直接迁移。
- 只有 `COMPLETED` 任务允许查询。
- 非法参数、损坏 FTDC、资源上限、任务未完成和客户端取消使用明确错误分类。

## 12．前端

应用顶部增加“日志分析／FTDC 分析”一级切换。FTDC 工作区包含：

- FTDC 多文件上传和独立任务列表。
- 任务状态、进度、文件大小、block 数、指标数、样本点数和时间范围。
- 指标组筛选与单选。
- 指标组纵向图表，支持原始值／相邻差值、合并图／拆分图、隐藏全零指标和折叠。
- 删除任务时明确提示会删除应用保存的 FTDC 副本和索引。

前端不得请求或保存全部原始指标矩阵，也不得提供“选择全部指标组”。切换任务或重新查询指标组时取消旧请求，旧响应不能覆盖新选择。

## 13．错误与数据正确性

- 文件中没有合法 type=1 block：任务失败。
- BSON 长度越界、zlib 解压失败、声明长度不符：任务失败并记录文件和 block。
- `numAttribs` 与 baseline 展平结果不一致：任务失败。
- varint EOF、溢出或 delta 流结构错误：任务失败。
- 源文件与索引记录的大小或 SHA-256 不符：拒绝查询并要求重建任务。
- 解析不得静默截断或按最短序列掩盖错误。
- 多 schema 是合法情况；单个 schema 内部结构不一致是错误。
- 所有时间统一为 UTC Epoch Milliseconds，页面按本机时区展示。

## 14．验证标准

### 14.1 自动测试

- 合成 FTDC 覆盖 BSON 类型、嵌套文档、数组和 Timestamp。
- delta 覆盖正增量、负增量、64 位回绕和常量列。
- RLE 覆盖属性内部和跨属性延续。
- 损坏 BSON、错误长度、截断 zlib、截断 varint、错误属性数均得到明确失败。
- 二进制索引往返、版本、原子写入和源文件校验通过。
- 20 文件任务严格串行处理。
- 查询许可公平串行，异常和取消后必定释放。
- 分组规则、每组指标上限和每指标图表最大点数正确。
- 重启恢复、任务删除和文件隔离通过。
- 前端覆盖任务切换、指标组选择、合并／拆分、全零指标隐藏、时间断点、请求取消和错误提示。

### 14.2 真实数据验收

使用上述 10,485,242 字节的真实 FTDC 样本：

- 识别 96 个 block、2,082 个指标和 28,800 个采样点。
- `start` 的主要相邻间隔为 1,000 ms。
- 抽取关键指标与预先固化的验收向量逐值一致。
- 单文件及 20 文件场景均在 `-Xmx2g` 下完成索引和图表查询，不发生 `OutOfMemoryError`。
- 20 文件任务运行时堆内存不随已处理文件数量持续增长。
- 单文件派生索引目标不超过 5 MB；不计算保留的原始 FTDC。
- 后端测试、前端测试、前端构建和 Maven 打包全部通过。
- 启动 JAR 后在浏览器完成上传、进度、指标组查询、合并／拆分和删除验收。
