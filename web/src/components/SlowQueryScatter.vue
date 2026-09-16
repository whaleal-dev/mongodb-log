<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { fetchSlowQueryPoints } from '../api/tasks.js'
import { formatDate, formatDecimal } from '../utils/format.js'
import MetricPanel from './MetricPanel.vue'
import AnalysisChart from './AnalysisChart.vue'
import MetricHeader from './MetricHeader.vue'

const props = defineProps({ taskId: { type: String, required: true } })
const emit = defineEmits(['select'])
const series = ref([])
const loading = ref(false)
const error = ref('')
let pointsRequest = 0

const pointCount = computed(() => series.value.reduce((count, item) => count + item.data.length, 0))
const option = computed(() => ({
  color: ['#398b73', '#659ec5', '#9d93c4', '#d8ad65', '#77b8b4', '#c98086', '#758aa5'],
  tooltip: { trigger: 'item', renderMode: 'richText', formatter: (item) =>
    `${item.seriesName}\n${formatDate(item.data[0])}\n耗时：${formatDecimal(item.data[1])} ms\nqueryId：${item.data[2]}` },
  legend: { type: 'scroll', top: 0, right: 120 },
  toolbox: { right: 10, feature: { dataZoom: { title: { zoom: '框选缩放', back: '还原上次缩放' } }, restore: { title: '恢复缩放' } } },
  grid: { left: 75, right: 30, top: 65, bottom: 85 },
  xAxis: { type: 'time', name: '日志时间', nameLocation: 'middle', nameGap: 30 },
  yAxis: { type: 'value', name: '耗时（ms）', min: 0 },
  dataZoom: [{ type: 'inside', xAxisIndex: 0, filterMode: 'none' }, { type: 'slider', xAxisIndex: 0, bottom: 10, filterMode: 'none' }],
  series: series.value.map((item) => ({ name: item.name, type: 'scatter', symbolSize: 8, data: item.data })),
}))

async function loadPoints() {
  const request = ++pointsRequest
  series.value = []
  loading.value = true
  error.value = ''
  try {
    const result = await fetchSlowQueryPoints(props.taskId)
    if (request === pointsRequest) series.value = result.series
  } catch (failure) {
    if (request === pointsRequest) error.value = failure.message
  } finally {
    if (request === pointsRequest) loading.value = false
  }
}

function pointClicked(event) {
  if (event.componentType === 'series' && event.data?.[2] != null) emit('select', event.data[2])
}
watch(() => props.taskId, loadPoints, { immediate: true })
onBeforeUnmount(() => { ++pointsRequest })
</script>

<template>
  <MetricPanel layout-key="scatter" :min-height="420" class="scatter-panel">
    <MetricHeader title="最慢查询时间分布 · Top 5000" :subtitle="'已保留 ' + pointCount.toLocaleString() + ' 条 · 点击散点查看日志'"
      description="从全部慢查询按耗时倒序选取最慢的 5000 条。横轴为日志时间，纵轴为耗时（ms），不同颜色对应不同集合。使用底部时间条或框选工具缩放，圆形箭头恢复。点击后仅加载该条日志。" />
    <p v-if="loading" class="empty-state" role="status">正在加载散点…</p>
    <div v-else-if="error" role="alert"><p>{{ error }}</p><el-button @click="loadPoints">重试散点</el-button></div>
    <AnalysisChart v-else-if="pointCount" :option="option" :height="440" @point-click="pointClicked" />
    <p v-else class="empty-state">暂无慢查询散点数据</p>
    <p v-if="pointCount" class="section-note">点击散点，在右侧查看日志详情与解读。</p>
  </MetricPanel>
</template>
