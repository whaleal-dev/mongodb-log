# MongoDB Log 运行诊断与 Markdown 报告设计

## 目标

在不改变现有慢查询分析行为的前提下，为 MongoDB 4.4 及以上结构化日志增加运行诊断，并允许用户从 Web 一键导出适合交给 AI 二次分析的脱敏 Markdown 报告。

## 不变约束

- `summary.json`、Top 5000、Top 50、八档耗时分布及现有 REST 响应保持原口径。
- 旧版文本日志继续支持；新诊断字段不足时明确披露，不伪造结果。
- 普通日志仍不保存原文；现有慢查询原文保留规则不扩大。
- 全部输入继续逐行流式解析，新增统计不得随日志行数线性增长内存。
- 不连接 MongoDB，不查询 IndexStats，不把时间相关性描述成故障因果。

## 数据隔离

每个新任务额外原子保存 `diagnostics.json`。现有 `summary.json` 不增加必填字段，历史任务缺少诊断文件时：

- `/summary` 保持原响应；
- `/diagnostics` 返回明确的“历史任务未生成运行诊断”状态；
- `/report.md` 仍可导出现有分析，并注明诊断部分缺失。

诊断文件只包含聚合、最多 2,000 个时间桶、有限 Top 项和每类最多 3 条脱敏事件样本。

## MongoDB 4.4＋兼容

基础层读取 `t`、`s`、`c`、`id`、`ctx`、`msg`、`attr`，并可选识别 `svc`、`tags`、`truncated`、`size`。分析采用字段存在性而非版本分支：

- 4.4＋：基础事件、连接、认证、复制集、慢查询通用字段；
- 5.0＋：`authorization`、`remoteOpWaitMillis`；
- 6.1＋：Catalog Cache 等待；
- 6.2＋：`queryFramework`；
- 6.3＋：`cpuNanos`、Session Workflow；
- 7.x：`totalOplogSlotDurationMicros`；
- 8.0＋：`planCacheShapeHash`、`queryShapeHash`、`queues`、`workingMillis`；
- 8.1＋：各执行阶段 `Spills`、`SpilledBytes`、`SpilledRecords`。

查询模式主键优先使用 `planCacheShapeHash`，其次使用 `queryHash`，最后回退现有规范化 Pattern。`queryShapeHash` 单独展示，不与计划缓存形状 Hash 混用。

## 诊断模型

### 异常事件

- 全部严重级别和组件计数；
- Fatal／Error／Warning 的稳定事件键、次数、首次和最后时间；
- 事件键优先使用 `component + messageId`，没有 ID 时回退 `component + message`；
- 事件小时趋势和每类最多 3 条脱敏样本。

### 连接与客户端

- 建立、结束连接总数及动态时间趋势；
- `connectionCount` 最小、最大、平均和样本数；
- 客户端应用、Driver 名称及版本 Top 20；
- 认证成功、未认证连接、重新认证警告计数；
- 不保存完整 remote、用户名或认证命令。

### 复制集与网络

识别心跳失败、主机失联、拓扑变化、成员状态变化、选举、同步源变化、慢连接和 Socket 检测失败。展示时间、类型、次数和脱敏属性；只描述为时间线索。

### 慢查询效率

在现有统计之外按查询模式聚合：扫描／返回、COLLSCAN、额外排序、磁盘读取、规划耗时、写关注等待、Flow Control、锁等待、写冲突、Query Framework、查询形状与计划缓存键。所有字段同时记录覆盖样本数。

### 数据可信度

记录结构化／旧版日志数量、字段覆盖、截断数量、时间乱序、文件间重复或重叠线索和服务器 Build Info。缺失值与零值严格区分。

## Web

结果页新增“慢查询分析／运行诊断”页签：

- 慢查询分析沿用现有组件、加载和布局；
- 运行诊断首次打开时按需请求新接口；
- 历史任务显示重新上传提示；
- 稀疏数据使用表格或时间线，连续趋势使用折线图；
- 所有数字可在数据表中核对。

## Markdown 报告

`GET /api/tasks/{taskId}/report.md` 返回 `text/markdown; charset=UTF-8`，并设置安全的下载文件名。报告包含：

1. 任务和时间范围；
2. 数据质量与字段覆盖；
3. 异常事件；
4. 连接与客户端；
5. 复制集与网络；
6. 慢查询概览、效率线索和 Top 查询模式；
7. 分析边界和给 AI 的说明。

报告不包含完整原始日志、命令字面值、用户名和完整 IP。Markdown 顺序固定、数值使用原始精度，缺失字段明确标注。

## 验收

- 新旧日志任务的现有 summary 与改动前一致；
- 4.4～8.x 兼容夹具逐字段通过；
- 两份真实日志完成全量流式回归，诊断聚合可手工复算；
- 历史任务可查看旧页面并导出降级报告；
- Markdown 不含测试夹具中的敏感字面值；
- `mvn test`、前端测试、生产构建、Maven 打包和浏览器验收全部通过。
