<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import { fetchSlowQuery } from '../api/tasks.js'
import SlowQueryDetail from './SlowQueryDetail.vue'

const props = defineProps({ taskId: String, selection: { type: Object, default: null } })
const emit = defineEmits(['close'])
const query = ref(null)
const loading = ref(false)
const error = ref('')
let requestVersion = 0

async function load() {
  const request = ++requestVersion
  query.value = null
  error.value = ''
  loading.value = false
  if (!props.selection) return
  if (props.selection.sample) {
    query.value = props.selection.sample
    return
  }
  loading.value = true
  try {
    const result = await fetchSlowQuery(props.taskId, props.selection.queryId)
    if (request === requestVersion) query.value = result
  } catch (failure) {
    if (request === requestVersion) error.value = failure.message
  } finally {
    if (request === requestVersion) loading.value = false
  }
}
function close() {
  ++requestVersion
  query.value = null
  emit('close')
}
function beforeClose(done) {
  close()
  done()
}
watch(() => [props.taskId, props.selection], load, { immediate: true })
onBeforeUnmount(() => { ++requestVersion })
</script>

<template>
  <el-drawer :model-value="Boolean(selection)" :title="selection?.sample ? '模式最慢语句' : '慢查询详情'"
    direction="rtl" size="min(780px, 96vw)" class="query-drawer" destroy-on-close :before-close="beforeClose" @update:model-value="value => { if (!value) close() }">
    <p v-if="selection?.sample" class="sample-context">此查询模式中耗时最长的一条日志，独立保留，不受全局 Top 5000 限制。</p>
    <p v-if="loading" role="status">正在加载选中日志…</p>
    <div v-else-if="error" role="alert"><p>{{ error }}</p><el-button @click="load">重试详情</el-button></div>
    <SlowQueryDetail v-else-if="query" :query="query" />
  </el-drawer>
</template>

<style>
.query-drawer .el-drawer__header { margin-bottom: 0; padding: 22px 24px; border-bottom: 1px solid var(--line); color: #2a433b; font-weight: 650; }
.query-drawer .el-drawer__body { padding: 0 24px 28px; }
.sample-context { padding: 12px 14px; border-radius: 7px; color: #597669; background: #eff6f2; font-size: 12px; line-height: 1.7; }
@media (max-width: 480px) { .query-drawer .el-drawer__body { padding: 0 16px 20px; } }
</style>
