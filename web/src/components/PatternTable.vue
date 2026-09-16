<script setup>
import { computed, ref } from 'vue'
import MetricHeader from './MetricHeader.vue'
import MetricLabel from './MetricLabel.vue'
import MetricPanel from './MetricPanel.vue'
import { ElTooltip } from 'element-plus'
import { formatDecimal } from '../utils/format.js'

const props = defineProps({ patterns: { type: Array, default: null } })
defineEmits(['select'])
const sortKey = ref('count')
const ascending = ref(false)
const collator = new Intl.Collator('zh-CN', { numeric: true, sensitivity: 'base' })
const keys = ['namespace', 'operation', 'pattern', 'planSummary', 'totalCpuNanos', 'count',
  'totalDurationMillis', 'averageDurationMillis', 'maxDurationMillis', 'minDurationMillis']
const rows = computed(() => [...(props.patterns || [])].sort((a, b) => b.count - a.count).slice(0, 50).sort((a, b) => {
  const key = sortKey.value
  const left = key === 'totalCpuNanos' && !a.cpuAvailable ? null : a[key]
  const right = key === 'totalCpuNanos' && !b.cpuAvailable ? null : b[key]
  const absent = value => value == null || value === '' || (typeof value === 'number' && !Number.isFinite(value))
  if (absent(left)) return absent(right) ? 0 : 1
  if (absent(right)) return -1
  const result = typeof left === 'number' && typeof right === 'number' ? left - right : collator.compare(String(left), String(right))
  return ascending.value ? result : -result
}))
function sort(key) {
  ascending.value = sortKey.value === key ? !ascending.value : true
  sortKey.value = key
}
const columns = [
  { label: '集合', description: 'Namespace，格式为数据库名.集合名。相同查询模式在不同集合中分别统计。' },
  { label: '操作类型', description: '日志解析出的操作，例如 find、update、insert。相同模式的不同操作分别统计。' },
  { label: '查询模式', description: '将查询中的具体值归一化后得到的 Pattern，用于识别重复出现的查询结构。' },
  { label: '执行计划', description: '该模式下实际出现的执行计划，合并展示；COLLSCAN 为集合扫描，IXSCAN 为索引扫描。' },
  { label: 'CPU 耗时', description: '该模式的 CPU 总耗时，单位为毫秒。仅累计明确提供 cpuNanos 的日志，缺失不代表零。' },
  { label: '次数', description: '该模式在全部慢查询中的出现次数。此表按次数倒序取前 50。' },
  { label: '总耗时', description: '该模式下全部慢查询耗时之和，单位为毫秒。' },
  { label: '平均耗时', description: '总耗时除以出现次数，单位为毫秒。' },
  { label: '最大耗时', description: '该模式中最慢的一次查询耗时，单位为毫秒。' },
  { label: '最小耗时', description: '该模式中最快的一次慢查询耗时，单位为毫秒。' },
  { label: '最慢语句', description: '该模式下耗时最长的一条原始日志，独立保留，不受全局 Top 5000 限制。点击后在右侧查看。' },
]
const sortLabel = computed(() => columns[keys.indexOf(sortKey.value)].label)
</script>

<template>
  <MetricPanel layout-key="patterns" class="pattern-panel">
    <MetricHeader title="查询模式统计 · Top 50" :subtitle="`${sortKey === 'count' ? '按出现次数排序' : '按' + sortLabel + '排序'} · ${ascending ? '升序' : '降序'} · 耗时 ms`"
      description="按集合、操作类型、查询模式分组，按出现次数选取前 50 组。点击表头仅调整这 50 组的显示顺序，缺失值始终置后。每组独立保留最慢的一条原始日志。" />
    <p v-if="!rows.length" class="empty-state">{{ patterns == null ? '此历史任务未保存新版模式统计，请重新上传分析' : '暂无可识别的查询模式' }}</p>
    <div v-else class="data-table-scroll pattern-scroll">
      <table class="data-table pattern-table"><thead><tr>
        <th v-for="(column, index) in columns" :key="column.label" :class="{ 'numeric-column': index >= 4 && index <= 9 }"
          :aria-sort="keys[index] ? (sortKey === keys[index] ? (ascending ? 'ascending' : 'descending') : 'none') : undefined">
          <div class="column-heading"><button v-if="keys[index]" type="button" class="sort-button" :aria-label="`按${column.label}排序`" @click="sort(keys[index])">
            {{ column.label }}<span class="sort-direction" :class="{ active: sortKey === keys[index] }" aria-hidden="true">{{ sortKey === keys[index] ? (ascending ? '↑' : '↓') : '↕' }}</span>
          </button><span v-else>{{ column.label }}</span><MetricLabel :label="column.label" :description="column.description" help-only /></div>
        </th>
      </tr></thead><tbody>
        <tr v-for="row in rows" :key="JSON.stringify([row.namespace, row.operation, row.pattern])">
          <td>{{ row.namespace }}</td><td><span class="operation-tag">{{ row.operation }}</span></td>
          <td><ElTooltip placement="top" :trigger="['hover', 'focus']" popper-class="pattern-tooltip" :show-after="150">
            <template #content><pre>{{ row.pattern }}</pre></template>
            <code class="pattern-text" tabindex="0" aria-label="查看完整查询模式">{{ row.pattern }}</code>
          </ElTooltip></td><td>{{ row.planSummary || '日志未提供' }}</td>
          <td>{{ row.cpuAvailable ? formatDecimal(row.totalCpuNanos / 1000000) : '日志未提供' }}</td><td class="number-cell">{{ row.count }}</td>
          <td>{{ formatDecimal(row.totalDurationMillis) }}</td><td>{{ formatDecimal(row.averageDurationMillis) }}</td><td>{{ formatDecimal(row.maxDurationMillis) }}</td><td>{{ formatDecimal(row.minDurationMillis) }}</td>
          <td class="pattern-action"><button v-if="row.slowestQuery" class="text-button" type="button" @click="$emit('select', row.slowestQuery)">查看语句 ↗</button>
            <span v-else class="section-note">历史任务未保存独立样本，请重新上传</span></td>
        </tr>
      </tbody></table>
    </div>
  </MetricPanel>
</template>

<style scoped>
.pattern-scroll { max-height: 360px; }
.pattern-table { min-width: 1360px; }
.column-heading { display: flex; align-items: center; gap: 5px; }
.sort-button { display: inline-flex; align-items: center; gap: 5px; padding: 0; border: 0; color: inherit; background: transparent; cursor: pointer; font-size: inherit; white-space: nowrap; }
.sort-button:hover, .sort-button:focus-visible { color: #236c52; }
.sort-button:focus-visible { outline: 2px solid #91b5a3; outline-offset: 3px; }
.sort-direction { color: #9ca8b4; font-size: 12px; }
.sort-direction.active { color: #236c52; }
.numeric-column .column-heading { justify-content: flex-end; }
.pattern-table td:nth-child(n+5):nth-child(-n+10) { text-align: right; white-space: nowrap; }
.pattern-table th { white-space: nowrap; }
.pattern-table td { font-size: 12px; font-variant-numeric: tabular-nums; }
.pattern-table td:first-child { min-width: 170px; }
.pattern-table td:nth-child(3) { min-width: 220px; max-width: 300px; }
.pattern-text { display: block; max-width: 280px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font: 11px/1.6 ui-monospace, SFMono-Regular, Consolas, monospace; color: #5d7282; }
.operation-tag { font: 12px ui-monospace, SFMono-Regular, Consolas, monospace; color: #42576a; }
.pattern-action { position: sticky; right: 0; background: #fff; border-left: 1px solid #edf1f5; white-space: nowrap; }
.pattern-table th:last-child { position: sticky; right: 0; z-index: 2; border-left: 1px solid #edf1f5; }
.number-cell { font-weight: 600; color: #334b5e; }
</style>

<style>
.pattern-tooltip { max-width: min(640px, 90vw); }
.pattern-tooltip pre { max-height: 360px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; margin: 0; padding: 4px; font: 12px/1.8 ui-monospace, monospace; }
</style>
