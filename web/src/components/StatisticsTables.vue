<script setup>
import { computed } from 'vue'
import { formatBytes } from '../utils/format.js'
import MetricHeader from './MetricHeader.vue'
import MetricLabel from './MetricLabel.vue'
import MetricPanel from './MetricPanel.vue'

const props = defineProps({ summary: { type: Object, required: true } })
const tables = computed(() => [
  { title: '客户端统计 · Top 20', label: '客户端 IP', values: props.summary.remotes, response: true,
    description: '按客户端 IP 汇总全部慢查询的次数与响应数据量，按响应数据量倒序取前 20。IP 不含端口；unknown 表示日志未提供来源。' },
  { title: '操作类型统计', label: '操作类型', values: props.summary.operations,
    description: '全部慢查询按 find、insert、update 等操作类型汇总的次数。用于识别主要慢操作，不受 Top 5000 保留限制。' },
  { title: '集合统计 · Top 20', label: '集合', values: props.summary.namespaces, response: true,
    description: '按 Namespace（数据库名.集合名）汇总全部慢查询，先按响应数据量、再按次数倒序取前 20。离线日志无法提供实时索引信息 IndexStats。' },
])
</script>

<template>
  <div class="statistics-grid">
    <MetricPanel layout-key="failures" class="compact-panel">
      <MetricHeader title="失败操作" description="记录日志中的心跳失败次数。心跳用于节点间健康检查；次数来自全部已解析日志，与慢查询数量无关。" subtitle="节点通信健康" />
      <table class="data-table"><thead><tr><th>失败类型</th><th>次数</th></tr></thead>
        <tbody><tr><td>HeartBeat Failed</td><td class="count-cell" :class="{ 'has-failures': summary.heartbeatFailures > 0 }">{{ summary.heartbeatFailures ?? 0 }}</td></tr></tbody></table>
    </MetricPanel>
    <MetricPanel v-for="table in tables" :key="table.title" :layout-key="table.title" class="compact-panel">
      <MetricHeader :title="table.title" :description="table.description" :subtitle="table.response ? '按响应数据量排序' : '全部慢查询'" />
      <p v-if="table.response && summary.patternStats == null" class="empty-state">此历史任务的 Top 20 排序口径不同，请重新上传分析</p>
      <div v-else class="data-table-scroll compact-table">
        <table class="data-table"><thead><tr><th>{{ table.label }}</th>
          <th><MetricLabel label="慢查询数" description="该分组中的全部慢查询数量，单位为次。" /></th>
          <th v-if="table.response"><MetricLabel label="响应量" description="累计日志中的 reslen，表示响应数据量。悬停数值可查看精确字节数。" /></th></tr></thead>
          <tbody><tr v-for="(stat, name) in table.values" :key="name"><td>{{ name }}</td><td>{{ stat.count.toLocaleString() }}</td>
            <td v-if="table.response" :title="stat.totalResponseBytes + ' B'">{{ formatBytes(stat.totalResponseBytes) }}</td></tr></tbody>
        </table>
        <p v-if="!Object.keys(table.values || {}).length" class="empty-state">暂无可用数据</p>
      </div>
      <p v-if="table.label === '集合'" class="section-note index-note">索引信息：日志无法提供</p>
    </MetricPanel>
  </div>
</template>

<style scoped>
.compact-table { flex: 1; min-height: 0; max-height: 184px; }
.compact-panel .data-table th:nth-child(n+2), .compact-panel .data-table td:nth-child(n+2) { text-align: right; }
.count-cell { font-variant-numeric: tabular-nums; font-weight: 600; }
.has-failures { color: #bb6d48; }
.index-note { margin: 10px 0 0; }
</style>
