<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { clearAllData, fetchMemoryUsage } from '../api/system.js'
import { formatBytes } from '../utils/format.js'

const emit = defineEmits(['cleared'])
const usage = ref(null)
const clearing = ref(false)
let timer
let loading = false

const percent = computed(() => usage.value?.usagePercent ?? 0)
const color = computed(() => percent.value >= 85 ? '#c45656' : percent.value >= 70 ? '#d68b36' : '#3f8b6d')
const orbStyle = computed(() => ({
  background: `conic-gradient(${color.value} ${percent.value}%, #e6ece9 ${percent.value}%)`,
}))
const title = computed(() => usage.value
  ? `JVM 堆内存 ${formatBytes(usage.value.usedBytes)}／${formatBytes(usage.value.maxBytes)}（${percent.value}%），点击清空全部任务数据`
  : '内存信息暂不可用，点击清空全部任务数据')

async function loadMemory() {
  if (loading) return
  loading = true
  try { usage.value = await fetchMemoryUsage() }
  catch { usage.value = null }
  finally { loading = false }
}

async function clearData() {
  try {
    await ElMessageBox.confirm(
      '将永久删除全部 MongoDB Log 和 MongoDB Metric 任务及应用托管数据。用户原始源文件不会被删除。',
      '清空全部任务和数据',
      { type: 'warning', confirmButtonText: '确认清空', cancelButtonText: '取消' },
    )
  } catch { return }
  clearing.value = true
  try {
    await clearAllData()
    emit('cleared')
    ElMessage.success('全部 Log 和 Metric 任务数据已清空')
    await loadMemory()
  } catch (error) { ElMessage.error(error.message) }
  finally { clearing.value = false }
}

onMounted(() => {
  loadMemory()
  timer = window.setInterval(loadMemory, 5000)
})
onBeforeUnmount(() => window.clearInterval(timer))
</script>

<template>
  <button class="memory-orb" type="button" :style="orbStyle" :title="title" :aria-label="title" :disabled="clearing" @click="clearData">
    <span>{{ usage ? `${percent}%` : '--' }}</span>
  </button>
</template>

<style scoped>
.memory-orb{display:grid;place-items:center;width:38px;height:38px;padding:4px;border:0;border-radius:50%;cursor:pointer;transition:transform .15s ease,opacity .15s ease}.memory-orb:hover{transform:scale(1.06)}.memory-orb:disabled{cursor:wait;opacity:.6}.memory-orb span{display:grid;place-items:center;width:30px;height:30px;border-radius:50%;color:#52645e;background:#fff;font-size:10px;font-weight:650;font-variant-numeric:tabular-nums;box-shadow:0 1px 4px #b8c7c133}
</style>
