<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchFtdcGroups, fetchFtdcGroupSeries, fetchFtdcTask, fetchFtdcTasks } from '../../api/ftdcTasks.js'
import { formatBytes, formatTimeRange } from '../../utils/format.js'
import FtdcUploadPanel from './FtdcUploadPanel.vue'
import FtdcTaskList from './FtdcTaskList.vue'
import FtdcMetricGroupChart from './FtdcMetricGroupChart.vue'

const tasks = ref([]), selectedTask = ref(null)
const groups = ref([]), selectedGroupIds = ref([]), groupResults = ref([]), groupKeyword = ref('')
const view = ref('raw'), groupLoading = ref(false), error = ref(''), groupFailureSummary = ref('')
const collapseAllCommand = ref(null), hideZeroAllCommand = ref(null)
const statusLabels = { QUEUED: '排队中', RUNNING: '建立索引中', COMPLETED: '分析完成', FAILED: '分析失败' }
const coreGroupNames = new Set([
  'connections', 'network', 'opcounters', 'mem',
  'globalLock/currentQueue', 'globalLock/activeClients',
  'metrics/document', 'metrics/queryExecutor', 'metrics/cursor', 'metrics/operation',
  'wiredTiger/cache/bytes', 'wiredTiger/cache/pages', 'wiredTiger/cache/eviction',
  'wiredTiger/transaction/rollback',
])
const coreGroupPrefixes = ['systemMetrics/cpu', 'systemMetrics/memory', 'systemMetrics/disks/']
let pollTimer, groupVersion = 0
const pendingPollTasks = new Set()
let chartCommandVersion = 0
let groupController
const progress = computed(() => selectedTask.value?.totalBytes ? Math.min(99, Math.round(selectedTask.value.processedBytes * 100 / selectedTask.value.totalBytes)) : 0)
const filteredGroups = computed(() => {
  const keyword = groupKeyword.value.trim().toLowerCase()
  return keyword ? groups.value.filter(group => group.name.toLowerCase().includes(keyword)) : groups.value
})
const selectedGroups = computed(() => {
  const selected = new Set(selectedGroupIds.value)
  return groups.value.filter(group => selected.has(group.groupId))
})
const selectedMetricCount = computed(() => selectedGroups.value.reduce((sum, group) => sum + (group.metricCount || 0), 0))
const coreGroups = computed(() => groups.value.filter(group => coreGroupNames.has(group.name)
  || coreGroupPrefixes.some(prefix => group.name.startsWith(prefix))))

async function loadTasks() { try { tasks.value = await fetchFtdcTasks() } catch (e) { ElMessage.error(e.message) } }
async function selectTask(task) {
  cancelRequests(); stopPolling(); selectedTask.value = task; error.value = ''; groupFailureSummary.value = ''
  groupKeyword.value = ''; groups.value = []; selectedGroupIds.value = []; groupResults.value = []
  if (task.status === 'COMPLETED') await loadGroups()
  else if (['QUEUED','RUNNING'].includes(task.status)) startPolling(task.id)
}
async function loadGroups() {
  const version = ++groupVersion; groupController?.abort(); groupController = new AbortController(); groupLoading.value = true
  try { const result = await fetchFtdcGroups(selectedTask.value.id, groupController.signal); if (version === groupVersion) groups.value = result }
  catch (e) { if (e.name !== 'AbortError' && version === groupVersion) error.value = e.message }
  finally { if (version === groupVersion) groupLoading.value = false }
}
async function querySelectedGroups() {
  if (!selectedGroupIds.value.length) return
  const groupIds = [...selectedGroupIds.value]
  groupController?.abort(); const version = ++groupVersion; groupController = new AbortController(); groupLoading.value = true; groupResults.value = []; error.value = ''; groupFailureSummary.value = ''
  try {
    const results = []
    let failed = 0
    for (const groupId of groupIds) {
      try {
        const result = await fetchFtdcGroupSeries(selectedTask.value.id, groupId, { maxPoints: 1200, view: view.value }, groupController.signal)
        if (version !== groupVersion) return
        results.push(result)
      } catch (e) {
        if (e.name === 'AbortError' || version !== groupVersion) return
        const selected = groups.value.find(group => group.groupId === groupId)
        results.push({ groupId, name: selected?.name || groupId, error: e.message })
        failed++
      }
      groupResults.value = [...results]
    }
    if (failed) {
      groupFailureSummary.value = `${failed} 个指标组加载失败，其他指标组已继续加载`
      ElMessage.warning(groupFailureSummary.value)
    }
  } catch (e) { if (e.name !== 'AbortError' && version === groupVersion) error.value = e.message }
  finally { if (version === groupVersion) groupLoading.value = false }
}
async function changeView(next) {
  view.value = next
  if (selectedGroupIds.value.length) await querySelectedGroups()
}
function selectionChanged() { groupResults.value = []; groupFailureSummary.value = '' }
function selectCoreGroups() { selectedGroupIds.value = coreGroups.value.map(group => group.groupId); selectionChanged() }
function clearGroupSelection() { selectedGroupIds.value = []; selectionChanged() }
function setAllCollapsed(value) { collapseAllCommand.value = { value, version: ++chartCommandVersion } }
function setAllHideZero(value) { hideZeroAllCommand.value = { value, version: ++chartCommandVersion } }
function startPolling(id) { pollTimer = window.setInterval(async () => { if (pendingPollTasks.has(id)) return; pendingPollTasks.add(id); try { const task = await fetchFtdcTask(id); if (selectedTask.value?.id !== id) return; selectedTask.value = task; if (['COMPLETED','FAILED'].includes(task.status)) { stopPolling(); await loadTasks(); if (task.status === 'COMPLETED') await loadGroups() } } catch (e) { stopPolling(); error.value = e.message } finally { pendingPollTasks.delete(id) } }, 1000) }
function stopPolling() { if (pollTimer) clearInterval(pollTimer); pollTimer = null }
function cancelRequests() { ++groupVersion; groupController?.abort() }
function created(task) { tasks.value.unshift(task); selectTask(task) }
function deleted(id) { tasks.value = tasks.value.filter(task => task.id !== id) }
function back() { cancelRequests(); stopPolling(); selectedTask.value = null; groupResults.value = []; loadTasks() }
onMounted(loadTasks); onBeforeUnmount(() => { cancelRequests(); stopPolling() })
</script>

<template>
  <div class="ftdc-dashboard">
    <template v-if="!selectedTask">
      <div class="page-title"><div><h1>Metric 任务</h1><p>上传 MongoDB Metric 文件，按指标组查看时间序列</p></div></div>
      <FtdcUploadPanel @created="created" />
      <FtdcTaskList :tasks="tasks" @select="selectTask" @deleted="deleted" />
    </template>
    <template v-else>
      <button class="back-button" type="button" @click="back">← 返回 Metric 任务列表</button>
      <section class="card ftdc-status-card">
        <div class="detail-title"><div><h1>MongoDB Metric 分析</h1><p>{{ selectedTask.name }}</p></div><el-tag :type="selectedTask.status === 'COMPLETED' ? 'success' : selectedTask.status === 'FAILED' ? 'danger' : 'warning'">{{ statusLabels[selectedTask.status] }}</el-tag></div>
        <section class="task-overview ftdc-overview"><div><span>Metric 文件</span><strong>{{ selectedTask.files.length }} 个／{{ formatBytes(selectedTask.totalBytes) }}</strong></div><div><span>Block</span><strong>{{ selectedTask.blockCount }}</strong></div><div><span>指标／样本点</span><strong>{{ selectedTask.metricCount }}／{{ (selectedTask.sampleCount || 0).toLocaleString() }}</strong></div><div><span>时间范围</span><strong>{{ formatTimeRange(selectedTask.startEpochMillis, selectedTask.endEpochMillis) }}</strong></div></section>
        <el-progress v-if="['QUEUED','RUNNING'].includes(selectedTask.status)" :percentage="progress" />
        <el-alert v-if="selectedTask.status === 'FAILED'" type="error" :title="selectedTask.errorMessage" :closable="false" />
        <el-alert v-if="error" type="error" :title="error" :closable="false" />
      </section>

      <section v-if="selectedTask.status === 'COMPLETED'" class="card query-card">
        <div class="card-header"><div><h2>查询条件</h2><p>可选择多个指标组，系统按选择顺序逐组查询</p></div></div>
        <div class="query-form">
          <el-input v-model="groupKeyword" class="group-filter-input" clearable placeholder="筛选指标组" />
          <el-select v-model="selectedGroupIds" multiple collapse-tags collapse-tags-tooltip filterable clearable placeholder="选择一个或多个指标组" class="group-select" :loading="groupLoading" :disabled="groupLoading" @change="selectionChanged">
            <el-option v-for="group in filteredGroups" :key="group.groupId" :label="`${group.name}（${group.metricCount}）`" :value="group.groupId" />
          </el-select>
          <div class="group-shortcuts">
            <el-button class="core-groups-button" plain :disabled="groupLoading || !coreGroups.length" @click="selectCoreGroups">一键选择核心指标组（{{ coreGroups.length }}）</el-button>
            <el-button :disabled="groupLoading || !selectedGroupIds.length" @click="clearGroupSelection">清空</el-button>
          </div>
          <div class="query-actions"><span>{{ selectedGroupIds.length ? `已选择 ${selectedGroupIds.length} 个指标组，共 ${selectedMetricCount} 个指标` : `共 ${groups.length} 个指标组` }}</span><el-button class="query-groups-button" type="primary" :loading="groupLoading" :disabled="!selectedGroupIds.length" @click="querySelectedGroups">查询所选指标组</el-button></div>
        </div>
      </section>

      <section v-if="selectedTask.status === 'COMPLETED'" class="charts-section">
        <div class="charts-header"><div><h2>指标图表</h2><p>每个组纵向排列，可切换合并图或拆分图，并隐藏全零指标</p></div></div>
        <div v-if="selectedGroupIds.length" class="chart-toolbar">
          <el-radio-group :model-value="view" @change="changeView"><el-radio-button value="raw">原始值</el-radio-button><el-radio-button value="delta">相邻差值</el-radio-button></el-radio-group>
          <div class="batch-chart-actions">
            <el-button class="expand-all-button" :disabled="!groupResults.length" @click="setAllCollapsed(false)">一键展开</el-button>
            <el-button class="collapse-all-button" :disabled="!groupResults.length" @click="setAllCollapsed(true)">一键关闭</el-button>
            <el-button class="hide-zero-all-button" :disabled="!groupResults.length" @click="setAllHideZero(true)">一键隐藏全零</el-button>
            <el-button class="show-zero-all-button" :disabled="!groupResults.length" @click="setAllHideZero(false)">一键显示全零</el-button>
          </div>
          <span class="toolbar-note">同组 Block 只解压一次；时间断裂处不连接折线</span>
        </div>
        <p v-if="groupLoading && !groupResults.length" class="empty-state">正在按顺序读取所选指标组…</p>
        <p v-else-if="!groupResults.length" class="empty-state">请先在上方选择指标组并查询</p>
        <p v-if="groupLoading && groupResults.length" class="loading-note">已加载 {{ groupResults.length }}／{{ selectedGroupIds.length }} 个指标组，正在继续读取其余指标组…</p>
        <el-alert v-if="groupFailureSummary" class="group-failure-summary" type="warning" :title="groupFailureSummary" :closable="false" />
        <div v-if="groupResults.length" class="chart-grid">
          <template v-for="group in groupResults" :key="group.groupId">
            <section v-if="group.error" class="group-error-card"><strong>{{ group.name }}</strong><span>{{ group.error }}</span></section>
            <FtdcMetricGroupChart v-else :group="group" :collapse-command="collapseAllCommand" :hide-zero-command="hideZeroAllCommand" />
          </template>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.ftdc-dashboard{display:flex;flex-direction:column;gap:18px}.ftdc-dashboard>.page-title,.ftdc-dashboard>.back-button{margin-bottom:0}.card,.charts-section{min-width:0;padding:18px 20px;border:1px solid #d6e2f2;border-radius:16px;background:#fff;box-shadow:0 6px 20px rgba(43,73,93,.05)}.ftdc-status-card{display:flex;flex-direction:column;gap:16px}.ftdc-overview{grid-template-columns:1.1fr .45fr .7fr 1.45fr;margin:0;border:0;padding:15px 0 0;border-top:1px solid #edf1f5;border-radius:0}.card-header,.charts-header{display:flex;justify-content:space-between;align-items:flex-start;gap:16px;margin-bottom:14px}.card-header h2,.charts-header h2{margin:0;color:#2f4050;font-size:16px}.card-header p,.charts-header p{margin:5px 0 0;color:#7a8996;font-size:12px}.query-form{display:grid;grid-template-columns:minmax(0,1fr);gap:12px}.group-filter-input{width:100%}.group-select{width:100%}.group-shortcuts,.batch-chart-actions{display:flex;flex-wrap:wrap;gap:8px}.query-actions{display:flex;align-items:center;justify-content:space-between;gap:12px;color:#7b8995;font-size:11px}.charts-section{display:flex;flex-direction:column}.chart-toolbar{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:14px;padding:12px;border-radius:10px;background:#f7f9fb}.batch-chart-actions :deep(.el-button+.el-button){margin-left:0}.toolbar-note{margin-left:auto;color:#7b8995;font-size:11px}.loading-note{margin:0 0 12px;color:#71808d;font-size:12px}.group-failure-summary{margin-bottom:12px}.chart-grid{display:flex;flex-direction:column;gap:14px}.group-error-card{display:flex;flex-direction:column;gap:6px;padding:14px;border:1px solid #f2c6c6;border-radius:12px;background:#fff6f6}.group-error-card strong{color:#8c3535;font-size:14px}.group-error-card span{color:#a85b5b;font-size:12px}.empty-state{margin:0}@media(max-width:900px){.ftdc-overview{grid-template-columns:1fr 1fr}}@media(max-width:620px){.card,.charts-section{padding:16px}.card-header{flex-direction:column}.chart-toolbar{align-items:flex-start}.toolbar-note{width:100%;margin-left:0}.query-actions{align-items:flex-start;flex-direction:column}}
</style>
