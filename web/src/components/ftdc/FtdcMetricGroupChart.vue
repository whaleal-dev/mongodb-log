<script setup>
import { computed, ref, watch } from 'vue'
import AnalysisChart from '../AnalysisChart.vue'

const props = defineProps({
  group: { type: Object, required: true },
  collapseCommand: { type: Object, default: null },
  hideZeroCommand: { type: Object, default: null },
})
const collapsed = ref(false)
const split = ref(false)
const hideZero = ref(false)
const colors = ['#347961', '#3b82a0', '#c26b3d', '#7c5cb2', '#a05b75', '#718548', '#526b8f', '#aa7d28']

watch(() => props.collapseCommand, command => { if (command) collapsed.value = command.value }, { immediate: true })
watch(() => props.hideZeroCommand, command => { if (command) hideZero.value = command.value }, { immediate: true })

function validValues(series) {
  return (series.values || []).filter(value => value != null && Number.isFinite(value))
}

function stats(series) {
  if (series.min != null && series.max != null && series.average != null) {
    return { min: series.min, max: series.max, average: series.average }
  }
  const values = validValues(series)
  if (!values.length) return null
  return {
    min: Math.min(...values),
    max: Math.max(...values),
    average: values.reduce((sum, value) => sum + value, 0) / values.length,
  }
}

function format(value) {
  if (value == null) return '-'
  return Number.isInteger(value) ? value.toLocaleString() : value.toLocaleString(undefined, { maximumFractionDigits: 2 })
}

function chartPoints(series) {
  return (series.timestamps || []).map((timestamp, index) => [timestamp, series.values[index]])
}

function chartSeries(series, index) {
  return {
    name: series.path,
    type: 'line',
    showSymbol: false,
    connectNulls: false,
    itemStyle: { color: colors[index % colors.length] },
    data: chartPoints(series),
  }
}

function option(seriesList) {
  return {
    animation: false,
    color: colors,
    grid: { left: 70, right: 24, top: seriesList.length > 1 ? 52 : 24, bottom: 55 },
    legend: seriesList.length > 1 ? { type: 'scroll', top: 4, left: 16, right: 16 } : undefined,
    tooltip: {
      trigger: 'axis',
      valueFormatter: value => format(Array.isArray(value) ? value[1] : value),
    },
    xAxis: { type: 'time' },
    yAxis: { type: 'value', scale: true },
    dataZoom: [{ type: 'inside' }, { type: 'slider', height: 18 }],
    series: seriesList.map(chartSeries),
  }
}

const seriesEntries = computed(() => (props.group.series || []).map(series => {
  const summary = stats(series)
  const zero = typeof series.allZero === 'boolean'
    ? series.allZero
    : summary?.min === 0 && summary?.max === 0 && summary?.average === 0
  return { series, summary, zero }
}))
const zeroCount = computed(() => seriesEntries.value.filter(entry => entry.zero).length)
const visibleEntries = computed(() => hideZero.value ? seriesEntries.value.filter(entry => !entry.zero) : seriesEntries.value)
const combinedOption = computed(() => option(visibleEntries.value.map(entry => entry.series)))
</script>

<template>
  <article class="group-chart-card">
    <div class="group-header">
      <div><h3>{{ group.name }}</h3><span>{{ visibleEntries.length }}／{{ group.series.length }} 个指标</span></div>
      <div class="group-actions">
        <button type="button" :class="{ active: !split }" :aria-pressed="!split" @click="split = false">合并展示</button>
        <button type="button" :class="{ active: split }" :aria-pressed="split" @click="split = true">分开展示</button>
        <button type="button" :disabled="!zeroCount" @click="hideZero = !hideZero">{{ hideZero ? '显示全部' : `隐藏全零（${zeroCount}）` }}</button>
        <button type="button" @click="collapsed = !collapsed">{{ collapsed ? '展开' : '折叠' }}</button>
      </div>
    </div>
    <div v-if="!collapsed" class="chart-body">
      <div class="metric-info-list">
        <div v-for="entry in visibleEntries" :key="entry.series.metricId" class="metric-info-item">
          <span class="metric-name">{{ entry.series.path }}</span>
          <span class="metric-stats">
            <span>最小值<strong>{{ format(entry.summary?.min) }}</strong></span>
            <span>最大值<strong>{{ format(entry.summary?.max) }}</strong></span>
            <span>平均值<strong>{{ format(entry.summary?.average) }}</strong></span>
          </span>
        </div>
      </div>
      <div class="chart-render-area">
        <div v-if="split" class="split-chart-list">
          <section v-for="entry in visibleEntries" :key="entry.series.metricId" class="split-chart-item">
            <h4>{{ entry.series.path }}</h4>
            <AnalysisChart :option="option([entry.series])" :height="320" />
          </section>
        </div>
        <AnalysisChart v-else-if="visibleEntries.length" :option="combinedOption" :height="320" />
        <p v-if="!visibleEntries.length" class="empty-chart">该组指标全部为 0，已隐藏</p>
      </div>
    </div>
  </article>
</template>

<style scoped>
.group-chart-card{width:100%;min-width:0;border:1px solid #dce6ef;border-radius:12px;background:#f9fbfc;overflow:hidden}.group-header{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:12px 14px;border-bottom:1px solid #e4ebf1;background:#fff}.group-header h3{margin:0;color:#34495a;font-size:14px;font-weight:600;overflow-wrap:anywhere}.group-header span{display:block;margin-top:4px;color:#8795a1;font-size:11px}.group-actions{display:flex;flex-wrap:wrap;gap:7px}.group-actions button{padding:4px 10px;border:1px solid #d5e1e9;border-radius:6px;color:#4c6d63;background:#fff;font-size:12px;cursor:pointer}.group-actions button.active{border-color:#72a08d;background:#eaf4ef;color:#225f47}.group-actions button:disabled{cursor:not-allowed;opacity:.45}.chart-body{display:grid;grid-template-columns:300px minmax(0,1fr);gap:12px;padding:12px}.metric-info-list{display:flex;flex-direction:column;gap:4px;max-height:320px;overflow:auto}.metric-info-item{display:block;width:100%;padding:9px 10px;border:1px solid #dce6ef;border-radius:8px;background:#fff;text-align:left}.metric-name{display:block;color:#3c5362;font-size:11px;font-weight:600;overflow-wrap:anywhere}.metric-stats{display:grid;grid-template-columns:repeat(3,1fr);gap:6px;margin-top:9px}.metric-stats span{color:#81909c;font-size:9px}.metric-stats strong{display:block;margin-top:2px;color:#405563;font-size:10px;overflow-wrap:anywhere}.chart-render-area{min-width:0;width:100%;min-height:320px;padding:8px;border-radius:8px;background:#fff}.split-chart-list{display:flex;flex-direction:column;gap:14px}.split-chart-item{border-bottom:1px solid #edf1f4}.split-chart-item:last-child{border-bottom:0}.split-chart-item h4{margin:4px 8px;color:#506473;font-size:11px;font-weight:600;overflow-wrap:anywhere}.empty-chart{display:flex;min-height:300px;align-items:center;justify-content:center;margin:0;color:#8a98a4;font-size:12px}@media(max-width:1000px){.chart-body{grid-template-columns:1fr}.metric-info-list{max-height:180px}}@media(max-width:620px){.group-header{align-items:flex-start;flex-direction:column}.metric-stats{grid-template-columns:1fr 1fr}}
</style>
