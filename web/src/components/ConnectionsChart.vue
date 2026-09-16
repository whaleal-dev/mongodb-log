<script setup>
import { computed, ref } from 'vue'
import AnalysisChart from './AnalysisChart.vue'
import MetricHeader from './MetricHeader.vue'
import { formatDate, formatDecimal } from '../utils/format.js'
import MetricPanel from './MetricPanel.vue'
const props = defineProps({ samples: { type: Array, default: null } })
const view = ref('chart')
const points = computed(() => {
  const sorted = [...(props.samples || [])].sort((a, b) => a.timestampEpochMillis - b.timestampEpochMillis)
  const result = []
  for (let i = 0; i < sorted.length; i++) {
    if (i && sorted[i].timestampEpochMillis - sorted[i - 1].timestampEpochMillis > 3600000) {
      result.push([sorted[i - 1].timestampEpochMillis + 3600000, null])
    }
    result.push([sorted[i].timestampEpochMillis, sorted[i].averageConnections])
  }
  return result
})
const option = computed(() => ({
  color: ['#649bb8'],
  tooltip: { trigger: 'axis', renderMode: 'richText', formatter: (items) => {
    const point = items[0]?.value
    return point ? formatDate(point[0]) + '\n平均连接数：' + (point[1] == null ? '无样本' : formatDecimal(point[1])) : ''
  } },
  grid: { left: 45, right: 25, top: 35, bottom: 35 },
  xAxis: { type: 'time', axisLine: { lineStyle: { color: '#dbe2e9' } }, axisLabel: { color: '#7c8998', fontSize: 11 } },
  yAxis: { type: 'value', name: '连接数', min: 0, axisLabel: { formatter: value => Number.isInteger(value) ? value : formatDecimal(value) }, splitLine: { lineStyle: { type: 'dashed', color: '#eff2f5' } } },
  series: [{ name: 'Average Connections', type: 'line', connectNulls: false, symbolSize: 6, data: points.value }],
}))
</script>

<template>
  <MetricPanel layout-key="connections" :min-height="320" data-metric="connections">
    <MetricHeader v-model:view="view" title="每小时平均连接数"
      description="将连接事件中明确记录的连接数按 UTC 小时分组，计算样本算术平均，包含零值；不是时间加权平均。页面按本机时区显示，缺少样本的小时留空，不推算连接数。" />
    <p v-if="samples == null" class="empty-state">此历史任务未保存连接数统计，请重新上传分析</p>
    <p v-else-if="!samples.length" class="empty-state">无数据：日志未提供可解析的连接数事件</p>
    <AnalysisChart v-else-if="view === 'chart'" :option="option" :height="316" />
    <div v-else class="data-table-scroll chart-table"><table class="data-table numeric-table">
      <thead><tr><th>小时起点</th><th>样本数</th><th>平均连接数</th></tr></thead>
      <tbody><tr v-for="sample in samples" :key="sample.timestampEpochMillis"><td>{{ formatDate(sample.timestampEpochMillis) }}</td>
        <td>{{ sample.sampleCount }}</td><td>{{ formatDecimal(sample.averageConnections) }}</td></tr></tbody>
    </table></div>
  </MetricPanel>
</template>
