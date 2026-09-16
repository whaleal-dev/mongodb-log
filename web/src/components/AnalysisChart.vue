<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({ option: { type: Object, required: true }, height: { type: Number, default: 340 } })
const emit = defineEmits(['point-click'])
const element = ref(null)
let chart
let observer
function resize() { chart?.resize() }
onMounted(() => {
  chart = echarts.init(element.value)
  chart.setOption(props.option, true)
  chart.on('click', (event) => emit('point-click', event))
  observer = new ResizeObserver(resize)
  observer.observe(element.value)
  window.addEventListener('resize', resize)
})
watch(() => props.option, (option) => chart?.setOption(option, true), { deep: true })
onBeforeUnmount(() => {
  observer?.disconnect()
  window.removeEventListener('resize', resize)
  chart?.dispose()
})
</script>

<template>
  <div ref="element" class="analysis-chart" :style="{ height: `${height}px` }"></div>
</template>

<style scoped>
.analysis-chart { width: 100%; min-width: 0; }
</style>
