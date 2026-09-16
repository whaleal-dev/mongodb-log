<script setup>
import { computed, ref } from 'vue'
import AnalysisChart from './AnalysisChart.vue'
import MetricHeader from './MetricHeader.vue'
import { formatDecimal, formatPercent } from '../utils/format.js'
import MetricPanel from './MetricPanel.vue'

const props = defineProps({ buckets: { type: Array, default: () => [] } })
const view = ref('chart')
const hasData = computed(() => props.buckets.some((bucket) => bucket.count > 0))
const option = computed(() => ({
  color: ['#4c947d'],
  grid: { left: 55, right: 24, top: 30, bottom: 48 },
  tooltip: { trigger: 'axis', renderMode: 'richText', formatter: (items) => {
    const bucket = props.buckets[items[0].dataIndex]
    return bucket.label + '\n数量：' + bucket.count + '\n占比：' + formatPercent(bucket.percentage) + '\n平均耗时：' + formatDecimal(bucket.averageDurationMillis) + ' ms'
  } },
  xAxis: { type: 'category', data: props.buckets.map((bucket) => bucket.label),
    axisTick: { show: false }, axisLine: { lineStyle: { color: '#dbe2e9' } }, axisLabel: { color: '#7c8998', fontSize: 11 } },
  yAxis: { type: 'value', name: '慢查询数', minInterval: 1,
    splitLine: { lineStyle: { color: '#eff2f5', type: 'dashed' } } },
  series: [{ type: 'bar', data: props.buckets.map((bucket) => bucket.count), barMaxWidth: 44,
    itemStyle: { borderRadius: [5, 5, 0, 0] }, label: { show: true, position: 'top', color: '#6f7e8c', fontSize: 11,
      formatter: (item) => props.buckets[item.dataIndex].count + ' · ' + formatPercent(props.buckets[item.dataIndex].percentage) } }],
}))
</script>

<template>
  <MetricPanel layout-key="duration" :min-height="300" class="duration-panel" data-metric="duration">
    <MetricHeader v-model:view="view" title="慢查询耗时分布" subtitle="全部慢查询 · 8 个耗时区间"
      description="统计全部慢查询落入各耗时区间的数量和占比，不受 Top 5000 保留限制。区间左闭右开；悬停查看数量、占比和平均耗时，数据表提供精确毫秒值。" />
    <AnalysisChart v-if="view === 'chart' && hasData" :option="option" :height="290" />
    <p v-else-if="view === 'chart'" class="empty-state">暂无慢查询耗时数据</p>
    <div v-else class="data-table-scroll">
      <table class="data-table numeric-table">
        <thead><tr><th>耗时区间</th><th>慢查询数</th><th>占全部慢查询</th><th>平均耗时</th><th>最大耗时</th></tr></thead>
        <tbody><tr v-for="bucket in buckets" :key="bucket.key" :data-bucket-key="bucket.key">
          <td>{{ bucket.label }}</td><td>{{ bucket.count.toLocaleString() }}</td><td>{{ formatPercent(bucket.percentage) }}</td>
          <td>{{ formatDecimal(bucket.averageDurationMillis) }} ms</td><td>{{ formatDecimal(bucket.maxDurationMillis) }} ms</td>
        </tr></tbody>
      </table>
      <p v-if="!buckets.length" class="empty-state">暂无慢查询耗时数据</p>
    </div>
  </MetricPanel>
</template>
