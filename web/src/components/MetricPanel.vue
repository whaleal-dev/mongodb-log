<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps({ layoutKey: { type: String, required: true }, minHeight: { type: Number, default: 220 } })
const storageKey = 'mongodb-log:metric-layout:v1'
const element = ref(null)
const size = ref({})
const saveError = ref(false)
let drag = null
function readLayout() {
  try { return JSON.parse(localStorage.getItem(storageKey) || '{}') || {} } catch { return {} }
}
function restore() {
  const saved = readLayout()[props.layoutKey]
  size.value = saved && Number.isFinite(saved.width) && saved.width > 0 && saved.width <= 100
    && Number.isFinite(saved.height) && saved.height >= props.minHeight && saved.height <= 1600 ? saved : {}
}
function reset() { size.value = {}; saveError.value = false }
function persist() {
  try {
    localStorage.setItem(storageKey, JSON.stringify({ ...readLayout(), [props.layoutKey]: size.value }))
    saveError.value = false
  } catch { saveError.value = true }
}
function start(event) {
  if (event.button !== 0) return
  const rect = element.value.getBoundingClientRect()
  drag = { x: event.clientX, y: event.clientY, width: rect.width, height: rect.height,
    containerWidth: element.value.parentElement.getBoundingClientRect().width }
  event.currentTarget.setPointerCapture?.(event.pointerId)
  event.preventDefault()
}
function move(event) {
  if (!drag || !drag.containerWidth) return
  const width = Math.max(Math.min(280, drag.containerWidth), Math.min(drag.containerWidth, drag.width + event.clientX - drag.x))
  size.value = { width: width / drag.containerWidth * 100,
    height: Math.max(props.minHeight, Math.min(1600, drag.height + event.clientY - drag.y)) }
}
function finish() { if (drag) { drag = null; persist() } }
function keyboard(event) {
  if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) return
  event.preventDefault()
  const rect = element.value.getBoundingClientRect()
  drag = { x: 0, y: 0, width: rect.width, height: rect.height, containerWidth: element.value.parentElement.getBoundingClientRect().width }
  move({ clientX: event.key === 'ArrowLeft' ? -20 : event.key === 'ArrowRight' ? 20 : 0,
    clientY: event.key === 'ArrowUp' ? -20 : event.key === 'ArrowDown' ? 20 : 0 })
  finish()
}
restore()
onMounted(() => window.addEventListener('mongodb-log:reset-layout', reset))
onBeforeUnmount(() => window.removeEventListener('mongodb-log:reset-layout', reset))
</script>

<template>
  <section ref="element" class="panel resizable-panel" :class="{ 'is-sized': size.height }" :data-layout-key="layoutKey"
    :style="{ width: size.width ? size.width + '%' : undefined, height: size.height ? size.height + 'px' : undefined }">
    <slot />
    <span v-if="saveError" class="layout-save-error" role="status">浏览器未允许保存布局</span>
    <button class="panel-resize" type="button" aria-label="调整指标窗口大小" title="拖动调整大小，自动保存；方向键也可调整"
      @pointerdown="start" @pointermove="move" @pointerup="finish" @pointercancel="finish" @lostpointercapture="finish" @keydown="keyboard"></button>
  </section>
</template>

<style>
.resizable-panel { position: relative; display: flex; flex-direction: column; min-width: min(280px, 100%); max-width: 100%; overflow: hidden; }
.resizable-panel > .metric-header { flex-shrink: 0; }
.panel-resize { position: absolute; right: 2px; bottom: 2px; width: 22px; height: 22px; padding: 0; border: 0; color: #aabbb4; background: transparent; cursor: nwse-resize; touch-action: none; font-size: 16px; }
.panel-resize:hover, .panel-resize:focus-visible { color: #347961; background: #eff6f2; outline: 1px solid #9fbdaf; }
.panel-resize::after { content: ''; display: block; width: 7px; height: 7px; margin: 8px; border-right: 2px solid currentColor; border-bottom: 2px solid currentColor; }
.resizable-panel.is-sized > .analysis-chart { flex: 1; height: auto !important; min-height: 80px; }
.resizable-panel.is-sized > .data-table-scroll { flex: 1; min-height: 0; height: auto; max-height: none; }
.resizable-panel.is-sized > .empty-state { flex: 1; min-height: 0; }
.layout-save-error { font-size: 10px; color: #ab6e41; }
</style>
