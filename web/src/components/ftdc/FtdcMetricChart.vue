<script setup>
import { computed } from 'vue'
import AnalysisChart from '../AnalysisChart.vue'
const props = defineProps({ series: { type: Object, default: () => ({ timestamps: [], values: [] }) } })
const option = computed(() => ({
  animation: false,
  grid: { left: 70, right: 24, top: 24, bottom: 55 },
  tooltip: { trigger: 'axis' },
  xAxis: { type: 'time' },
  yAxis: { type: 'value', scale: true },
  dataZoom: [{ type: 'inside' }, { type: 'slider', height: 18 }],
  series: [{ type: 'line', showSymbol: false, connectNulls: false, data: props.series.timestamps.map((time, index) => [time, props.series.values[index]]) }],
}))
</script>
<template><AnalysisChart :option="option" :height="320" /></template>
