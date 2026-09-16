<script setup>
import { computed } from 'vue'
import AnalysisChart from './AnalysisChart.vue'

const props = defineProps({ diagnostics: { type: Object, required: true } })

const number = value => value == null ? '—' : Number(value).toLocaleString()
const percent = (value, total) => total ? `${(value * 100 / total).toFixed(1)}％` : '—'
const time = value => value == null ? '—' : new Date(value).toLocaleString('zh-CN', { hour12: false })
const counts = value => Object.entries(value || {}).map(([key, count]) => `${key}：${number(count)}`).join('，') || '日志未提供'

const timelineOption = computed(() => ({
  animation: false,
  tooltip: { trigger: 'axis' },
  legend: { bottom: 0 },
  grid: { left: 48, right: 20, top: 20, bottom: 55 },
  xAxis: { type: 'time' },
  yAxis: { type: 'value', minInterval: 1 },
  series: [
    ['警告', 'warnings'], ['错误', 'errors'], ['致命错误', 'fatals'],
    ['建立连接', 'connectionsAccepted'], ['结束连接', 'connectionsEnded'], ['复制集事件', 'replicationEvents'],
  ].map(([name, field]) => ({ name, type: 'line', showSymbol: false,
    data: props.diagnostics.timeline.map(bucket => [bucket.epochMillis, bucket[field]]) })),
}))

const efficiencySignals = computed(() => {
  const slow = props.diagnostics.slowQueries
  return [
    ['COLLSCAN', slow.collscanCount],
    ['文档扫描比高', slow.highDocumentScanRatioCount],
    ['索引扫描比高', slow.highIndexScanRatioCount],
    ['零返回高扫描', slow.zeroReturnHighScanCount],
    ['磁盘读取占主导', slow.storageDominantCount],
    ['查询规划占主导', slow.planningDominantCount],
    ['写关注等待', slow.writeConcernWaitCount],
    ['Flow Control 等待', slow.flowControlWaitCount],
    ['锁等待', slow.lockWaitCount],
    ['分片响应等待', slow.remoteOpWaitCount],
    ['鉴权缓存等待', slow.authorizationWaitCount],
    ['执行队列等待', slow.queueWaitCount],
    ['Oplog 提交等待', slow.oplogSlotWaitCount],
    ['额外排序', slow.hasSortStageCount],
    ['使用临时磁盘', slow.usedDiskCount],
    ['查询执行落盘', slow.spillCount],
  ]
})
</script>

<template>
  <div class="diagnostic-workspace">
    <section class="diagnostic-summary" aria-label="运行诊断概览">
      <div><span>异常日志</span><strong>{{ number((diagnostics.severityCounts.W || 0) + (diagnostics.severityCounts.E || 0) + (diagnostics.severityCounts.F || 0)) }}</strong><small>W／E／F</small></div>
      <div><span>连接建立／结束</span><strong>{{ number(diagnostics.connections.accepted) }}／{{ number(diagnostics.connections.ended) }}</strong><small>用于观察连接抖动</small></div>
      <div><span>复制集事件</span><strong>{{ number(diagnostics.replicationEvents.reduce((sum, event) => sum + event.count, 0)) }}</strong><small>{{ number(diagnostics.replicationEvents.length) }} 种事件</small></div>
      <div><span>慢查询线索</span><strong>{{ number(diagnostics.slowQueries.insights.length) }}</strong><small>从 {{ number(diagnostics.slowQueries.total) }} 条慢查询筛选</small></div>
    </section>

    <section class="diagnostic-panel">
      <header><div><h2>异常事件与时间线</h2><p>把警告、错误、连接变化和复制集事件放到同一时间轴，便于观察是否同期发生。</p></div></header>
      <AnalysisChart v-if="diagnostics.timeline.length" :option="timelineOption" :height="300" />
      <p v-else class="empty-state">日志中没有可绘制的异常或连接时间点。</p>
      <div v-if="diagnostics.abnormalEvents.length" class="data-table-scroll">
        <table class="data-table">
          <thead><tr><th>级别</th><th>组件</th><th>ID</th><th>事件</th><th>次数</th><th>首次</th><th>最后</th></tr></thead>
          <tbody><tr v-for="event in diagnostics.abnormalEvents" :key="event.key">
            <td>{{ event.severity }}</td><td>{{ event.component }}</td><td>{{ event.messageId ?? '—' }}</td><td>{{ event.message }}</td>
            <td>{{ number(event.count) }}</td><td>{{ time(event.firstEpochMillis) }}</td><td>{{ time(event.lastEpochMillis) }}</td>
          </tr></tbody>
        </table>
      </div>
    </section>

    <div class="diagnostic-grid">
      <section class="diagnostic-panel">
        <header><div><h2>连接与客户端</h2><p>识别连接 churn、认证异常、应用与 Driver 构成。</p></div></header>
        <dl class="diagnostic-list">
          <div><dt>认证成功</dt><dd>{{ number(diagnostics.connections.authenticationSucceeded) }}</dd></div>
          <div><dt>未认证连接</dt><dd>{{ number(diagnostics.connections.notAuthenticating) }}</dd></div>
          <div><dt>重新认证警告</dt><dd>{{ number(diagnostics.connections.reauthenticationWarnings) }}</dd></div>
          <div><dt>连接数范围</dt><dd>{{ number(diagnostics.connections.connectionCountMin) }}～{{ number(diagnostics.connections.connectionCountMax) }}</dd></div>
          <div><dt>连接数平均值</dt><dd>{{ diagnostics.connections.connectionCountAverage == null ? '—' : diagnostics.connections.connectionCountAverage.toFixed(2) }}</dd></div>
          <div><dt>应用 Top</dt><dd>{{ counts(diagnostics.connections.applications) }}</dd></div>
          <div><dt>Driver Top</dt><dd>{{ counts(diagnostics.connections.drivers) }}</dd></div>
        </dl>
        <p v-if="diagnostics.connections.topValuesApproximate" class="section-note">应用和 Driver 数量为有界近似统计。</p>
      </section>

      <section class="diagnostic-panel">
        <header><div><h2>复制集与网络</h2><p>汇总选举、成员状态、心跳、同步源和连接异常。</p></div></header>
        <div v-if="diagnostics.replicationEvents.length" class="data-table-scroll compact-table">
          <table class="data-table"><thead><tr><th>类型</th><th>事件</th><th>次数</th><th>最后发生</th></tr></thead>
            <tbody><tr v-for="event in diagnostics.replicationEvents" :key="`${event.type}-${event.messageId}`">
              <td>{{ event.label }}</td><td>{{ event.message }}</td><td>{{ number(event.count) }}</td><td>{{ time(event.lastEpochMillis) }}</td>
            </tr></tbody></table>
        </div>
        <p v-else class="empty-state">未识别到复制集或相关网络事件。</p>
      </section>
    </div>

    <section class="diagnostic-panel">
      <header><div><h2>慢查询效率线索</h2><p>这些是定位方向，不等同于根因；应结合索引、数据分布和 FTDC 继续验证。</p></div></header>
      <div class="signal-grid">
        <div v-for="signal in efficiencySignals" :key="signal[0]"><span>{{ signal[0] }}</span><strong>{{ number(signal[1] || 0) }}</strong></div>
      </div>
      <div v-if="diagnostics.slowQueries.insights.length" class="data-table-scroll">
        <table class="data-table">
          <thead><tr><th>集合／操作</th><th>耗时</th><th>执行计划</th><th>文档／返回</th><th>线索</th><th>规范化查询模式</th></tr></thead>
          <tbody><tr v-for="(item, index) in diagnostics.slowQueries.insights" :key="`${item.timestampEpochMillis}-${index}`">
            <td>{{ item.namespace || '—' }}／{{ item.operation || '—' }}</td><td>{{ number(item.durationMillis) }} ms</td>
            <td>{{ item.planSummary || '—' }}</td><td>{{ item.documentsPerReturned == null ? '—' : item.documentsPerReturned.toFixed(1) }}</td>
            <td>{{ item.reasons.join('、') }}</td><td><code>{{ item.queryPattern || '—' }}</code></td>
          </tr></tbody>
        </table>
      </div>
    </section>

    <section class="diagnostic-panel">
      <header><div><h2>MongoDB 4.4+ 字段覆盖</h2><p>按字段是否实际存在进行识别；升级或混合版本日志不会因为版本号未知而丢弃。</p></div></header>
      <div class="quality-summary">
        <span>结构化日志：{{ number(diagnostics.dataQuality.structuredLines) }}</span>
        <span>旧版文本：{{ number(diagnostics.dataQuality.legacyLines) }}</span>
        <span>截断：{{ number(diagnostics.dataQuality.truncatedLines) }}</span>
        <span>带标签：{{ number(diagnostics.dataQuality.taggedLines) }}</span>
        <span>文件内时间乱序：{{ number(diagnostics.dataQuality.outOfOrderLines) }}</span>
      </div>
      <p class="section-note">服务器版本：{{ counts(diagnostics.dataQuality.serverVersions) }}。服务角色：{{ counts(diagnostics.dataQuality.services) }}。查询框架：{{ counts(diagnostics.slowQueries.queryFrameworks) }}。</p>
      <div v-if="Object.keys(diagnostics.slowQueries.fieldCoverage).length" class="data-table-scroll compact-table">
        <table class="data-table numeric-table"><thead><tr><th>慢查询字段</th><th>有值样本</th><th>覆盖率</th></tr></thead>
          <tbody><tr v-for="(count, field) in diagnostics.slowQueries.fieldCoverage" :key="field">
            <td><code>{{ field }}</code></td><td>{{ number(count) }}</td><td>{{ percent(count, diagnostics.slowQueries.total) }}</td>
          </tr></tbody></table>
      </div>
    </section>
  </div>
</template>

<style scoped>
.diagnostic-workspace { display: flex; flex-direction: column; gap: 16px; }
.diagnostic-summary { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.diagnostic-summary > div, .signal-grid > div { padding: 15px 16px; border: 1px solid #dce3e8; border-radius: 5px; background: #fff; }
.diagnostic-summary span, .diagnostic-summary small, .signal-grid span { display: block; color: #64748b; font-size: 11px; }
.diagnostic-summary strong { display: block; margin: 8px 0 4px; color: #253344; font-size: 22px; font-weight: 600; }
.diagnostic-panel { min-width: 0; padding: 18px; border: 1px solid #dce3e8; border-radius: 5px; background: #fff; }
.diagnostic-panel > header { margin-bottom: 14px; }
.diagnostic-panel h2 { margin: 0; color: #253344; font-size: 14px; font-weight: 600; }
.diagnostic-panel header p { margin: 4px 0 0; color: #64748b; font-size: 11px; line-height: 1.6; }
.diagnostic-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.diagnostic-list { display: grid; grid-template-columns: 1fr 1fr; margin: 0; gap: 0 18px; }
.diagnostic-list > div { display: flex; justify-content: space-between; gap: 12px; padding: 9px 0; border-bottom: 1px solid #eaf0f4; font-size: 12px; }
.diagnostic-list dt { color: #64748b; }.diagnostic-list dd { margin: 0; text-align: right; overflow-wrap: anywhere; }
.signal-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-bottom: 16px; }
.signal-grid strong { display: block; margin-top: 7px; color: #334155; font-size: 17px; }
.quality-summary { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 10px; }
.quality-summary span { padding: 7px 10px; border-radius: 4px; color: #526475; background: #f2f5f8; font-size: 11px; }
.compact-table { max-height: 270px; }
code { color: #43596b; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; }
@media (max-width: 900px) { .diagnostic-summary, .signal-grid { grid-template-columns: repeat(2, 1fr); }.diagnostic-grid { grid-template-columns: 1fr; } }
@media (max-width: 520px) { .diagnostic-summary, .signal-grid, .diagnostic-list { grid-template-columns: 1fr; } }
</style>
