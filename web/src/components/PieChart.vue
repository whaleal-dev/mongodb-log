<script setup>
import { computed, ref } from 'vue'
import AnalysisChart from './AnalysisChart.vue'
import MetricHeader from './MetricHeader.vue'
import MetricPanel from './MetricPanel.vue'
import { formatBytes, formatDecimal, formatPercent } from '../utils/format.js'

const props = defineProps({ title: String, description: String, values: { type: Object, default: () => ({}) },
  unit: { type: String, default: '次' }, emptyText: { type: String, default: '暂无可用数据' } })
const view = ref('chart')
const rows = computed(() => Object.entries(props.values).map(([name, value]) => ({ name, value })))
const total = computed(() => rows.value.reduce((sum, row) => sum + row.value, 0))
const displayValue = (value) => props.unit === 'B' ? `${value.toLocaleString()} B（${formatBytes(value)}）`
  : `${props.unit === '次' ? value.toLocaleString() : formatDecimal(value)} ${props.unit}`
const option = computed(() => ({
  color: ['#398b73', '#659ec5', '#9d93c4', '#d8ad65', '#77b8b4', '#c98086', '#758aa5', '#a1b476'],
  tooltip: { trigger: 'item', renderMode: 'richText', formatter: (item) => `${item.name}\n${displayValue(item.value)}\n占比：${formatPercent(total.value ? item.value / total.value * 100 : 0)}` },
  legend: { type: 'scroll', bottom: 0 },
  series: [{ name: props.title, type: 'pie', radius: ['42%', '68%'], center: ['50%', '44%'],
    itemStyle: { borderColor: '#fff', borderWidth: 2 },
    label: { show: rows.value.length <= 8, formatter: item => `${item.name}\n${formatPercent(total.value ? item.value / total.value * 100 : 0)}`, fontSize: 11, color: '#667789', width: 100, overflow: 'truncate' }, data: rows.value }],
}))
</script>

<template>
  <MetricPanel :layout-key="title" :min-height="$slots.default ? 380 : 340" :data-metric="title">
    <MetricHeader v-model:view="view" :title="title" :description="description" />
    <slot />
    <AnalysisChart v-if="view === 'chart' && total > 0" :option="option" :height="280" />
    <p v-else-if="!rows.length || (view === 'chart' && total === 0)" class="empty-state">{{ rows.length ? '已记录数据，合计为零，无可绘制占比' : emptyText }}</p>
    <div v-else class="data-table-scroll chart-table">
      <table class="data-table numeric-table"><thead><tr><th>名称</th><th>数值</th><th>占比</th></tr></thead>
        <tbody><tr v-for="row in rows" :key="row.name"><td>{{ row.name }}</td><td>{{ displayValue(row.value) }}</td>
          <td>{{ formatPercent(total ? row.value * 100 / total : 0) }}</td></tr></tbody>
      </table>
    </div>
  </MetricPanel>
</template>
