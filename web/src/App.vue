<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { downloadReport, fetchDiagnostics, fetchSummary, fetchTask, fetchTasks } from './api/tasks.js'
import UploadPanel from './components/UploadPanel.vue'
import TaskList from './components/TaskList.vue'
import StatisticsTables from './components/StatisticsTables.vue'
import DurationHistogram from './components/DurationHistogram.vue'
import BreakdownCharts from './components/BreakdownCharts.vue'
import PatternTable from './components/PatternTable.vue'
import SlowQueryScatter from './components/SlowQueryScatter.vue'
import SlowQueryDrawer from './components/SlowQueryDrawer.vue'
import { formatBytes, formatTimeRange } from './utils/format.js'
import FtdcWorkspace from './components/ftdc/FtdcWorkspace.vue'
import MemoryOrb from './components/MemoryOrb.vue'
import DiagnosticWorkspace from './components/DiagnosticWorkspace.vue'
import UsageGuideDialog from './components/UsageGuideDialog.vue'

const workspaceModeStorageKey = 'mongodb-log:workspace-mode:v1'

function readWorkspaceMode() {
  try {
    return localStorage.getItem(workspaceModeStorageKey) === 'ftdc' ? 'ftdc' : 'logs'
  } catch {
    return 'logs'
  }
}

const tasks = ref([])
const selectedTask = ref(null)
const summary = ref(null)
const detailSelection = ref(null)
const summaryLoading = ref(false)
const summaryError = ref('')
const diagnostics = ref(null)
const diagnosticsLoading = ref(false)
const diagnosticsError = ref('')
const reportLoading = ref(false)
const resultView = ref('slow-queries')
const progressError = ref('')
const workspaceMode = ref(readWorkspaceMode())
const workspaceRevision = ref(0)
let timer
let pollingVersion = 0
let listVersion = 0
let summaryVersion = 0
let diagnosticsVersion = 0

const progress = computed(() => {
  const task = selectedTask.value
  if (!task) return 0
  if (task.status === 'COMPLETED') return 100
  return task.totalBytes ? Math.min(99, Math.round(task.processedBytes * 100 / task.totalBytes)) : 0
})

async function loadTasks() {
  const request = ++listVersion
  try {
    const result = await fetchTasks()
    if (request === listVersion) tasks.value = result
  } catch (error) {
    if (request === listVersion) ElMessage.error(error.message)
  }
}

function taskDeleted(id) {
  ++listVersion
  tasks.value = tasks.value.filter(task => task.id !== id)
}

async function selectTask(task) {
  stopPolling()
  ++summaryVersion
  summaryLoading.value = false
  progressError.value = ''
  detailSelection.value = null
  selectedTask.value = task
  summary.value = null
  summaryError.value = ''
  diagnostics.value = null
  diagnosticsError.value = ''
  diagnosticsLoading.value = false
  resultView.value = 'slow-queries'
  if (task.status === 'COMPLETED') {
    await loadSummary(task.id)
  } else if (task.status === 'QUEUED' || task.status === 'RUNNING') {
    startPolling(task.id)
  }
}

async function showDiagnostics() {
  resultView.value = 'diagnostics'
  detailSelection.value = null
  const taskId = selectedTask.value?.id
  if (!taskId || diagnostics.value || diagnosticsLoading.value) return
  const request = ++diagnosticsVersion
  diagnosticsLoading.value = true
  diagnosticsError.value = ''
  try {
    const result = await fetchDiagnostics(taskId)
    if (request === diagnosticsVersion && selectedTask.value?.id === taskId) diagnostics.value = result
  } catch (error) {
    if (request === diagnosticsVersion && selectedTask.value?.id === taskId) diagnosticsError.value = error.message
  } finally {
    if (request === diagnosticsVersion && selectedTask.value?.id === taskId) diagnosticsLoading.value = false
  }
}

async function exportReport() {
  if (!selectedTask.value || reportLoading.value) return
  reportLoading.value = true
  try {
    await downloadReport(selectedTask.value.id)
    ElMessage.success('Markdown 报告已导出')
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    reportLoading.value = false
  }
}

async function loadSummary(taskId) {
  const request = ++summaryVersion
  summaryLoading.value = true
  summaryError.value = ''
  diagnostics.value = null
  diagnosticsLoading.value = false
  diagnosticsError.value = ''
  resultView.value = 'slow-queries'
  try {
    const result = await fetchSummary(taskId)
    if (request === summaryVersion && selectedTask.value?.id === taskId) summary.value = result
  } catch (error) {
    if (request === summaryVersion && selectedTask.value?.id === taskId) summaryError.value = error.message
  } finally {
    if (request === summaryVersion && selectedTask.value?.id === taskId) summaryLoading.value = false
  }
}

function startPolling(taskId) {
  stopPolling()
  const version = pollingVersion
  let pending = false
  progressError.value = ''
  timer = window.setInterval(async () => {
    if (pending) return
    pending = true
    try {
      const task = await fetchTask(taskId)
      if (version !== pollingVersion || selectedTask.value?.id !== taskId) return
      selectedTask.value = task
      const index = tasks.value.findIndex((item) => item.id === task.id)
      if (index >= 0) tasks.value.splice(index, 1, task)
      if (task.status === 'COMPLETED' || task.status === 'FAILED') {
        stopPolling()
        if (task.status === 'COMPLETED') await loadSummary(task.id)
        await loadTasks()
      }
    } catch (error) {
      if (version === pollingVersion && selectedTask.value?.id === taskId) {
        stopPolling()
        progressError.value = error.message
      }
    } finally {
      pending = false
    }
  }, 1000)
}

function stopPolling() {
  ++pollingVersion
  if (timer) window.clearInterval(timer)
  timer = undefined
}

function resetLayout() {
  try {
    localStorage.removeItem('mongodb-log:metric-layout:v1')
    window.dispatchEvent(new Event('mongodb-log:reset-layout'))
    ElMessage.success('已恢复默认布局')
  } catch { ElMessage.error('浏览器未允许修改本地布局') }
}

function selectWorkspaceMode(mode) {
  workspaceMode.value = mode
  try { localStorage.setItem(workspaceModeStorageKey, mode) } catch { /* 使用当前会话选择 */ }
}

async function dataCleared() {
  stopPolling()
  ++summaryVersion
  ++diagnosticsVersion
  ++listVersion
  tasks.value = []
  selectedTask.value = null
  summary.value = null
  diagnostics.value = null
  diagnosticsLoading.value = false
  diagnosticsError.value = ''
  resultView.value = 'slow-queries'
  detailSelection.value = null
  summaryLoading.value = false
  summaryError.value = ''
  progressError.value = ''
  workspaceRevision.value++
  await loadTasks()
}

async function taskCreated(task) {
  ++listVersion
  tasks.value.unshift(task)
  await selectTask(task)
}

async function backToTasks() {
  stopPolling()
  ++summaryVersion
  summaryLoading.value = false
  detailSelection.value = null
  selectedTask.value = null
  summary.value = null
  await loadTasks()
}

onMounted(loadTasks)
onBeforeUnmount(() => { stopPolling(); ++listVersion; ++summaryVersion; ++diagnosticsVersion })
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <div class="brand">
        <strong>{{ workspaceMode === 'logs' ? 'MongoDB Log' : 'MongoDB Metric' }}</strong><span>离线分析</span>
      </div>
      <nav class="workspace-switch" aria-label="分析类型">
        <button type="button" :class="{ active: workspaceMode === 'logs' }" @click="selectWorkspaceMode('logs')">MongoDB Log</button>
        <button type="button" :class="{ active: workspaceMode === 'ftdc' }" @click="selectWorkspaceMode('ftdc')">MongoDB Metric</button>
      </nav>
      <div class="header-actions"><UsageGuideDialog /><div class="offline-state">本地工作区</div><MemoryOrb @cleared="dataCleared" /></div>
    </header>

    <main class="page-container">
      <el-card class="workspace-card" shadow="never">
        <FtdcWorkspace v-if="workspaceMode === 'ftdc'" :key="workspaceRevision" />
        <template v-else>
        <template v-if="!selectedTask">
          <div class="page-title">
            <div>
              <h1>日志任务</h1>
              <p>上传 MongoDB 日志，查看统计与慢查询明细</p>
            </div>
          </div>
          <el-divider />
          <UploadPanel @created="taskCreated" />
          <TaskList :tasks="tasks" @select="selectTask" @deleted="taskDeleted" />
        </template>

        <template v-else>
          <div class="detail-header">
            <button class="back-button" type="button" @click="backToTasks">← 返回任务列表</button>
            <div class="detail-title">
              <div>
                <h1>日志分析报告</h1>
                <p>{{ selectedTask.name }}</p>
              </div>
              <el-tag v-if="selectedTask.status === 'COMPLETED'" type="success">分析完成</el-tag>
              <el-tag v-else-if="selectedTask.status === 'FAILED'" type="danger">分析失败</el-tag>
              <el-tag v-else type="warning">分析中</el-tag>
            </div>
          </div>
          <el-divider />

          <section class="task-overview">
            <div><span>日志文件</span><strong :title="selectedTask.files.map((file) => file.originalName).join('、')">{{ selectedTask.files.map((file) => file.originalName).join('、') }}</strong></div>
            <div><span>文件大小</span><strong>{{ formatBytes(selectedTask.totalBytes) }}</strong></div>
            <div class="log-range"><span>日志时间范围</span><strong>{{ formatTimeRange(selectedTask.logStartEpochMillis, selectedTask.logEndEpochMillis) }}</strong></div>
          </section>
          <div v-if="['QUEUED', 'RUNNING'].includes(selectedTask.status)" class="task-progress">
            <el-progress :percentage="progress" :stroke-width="10" />
          </div>
          <el-alert v-if="selectedTask.status === 'FAILED'" :title="selectedTask.errorMessage" type="error" :closable="false" show-icon />
          <div v-if="progressError" class="request-error" role="alert"><span>进度读取失败：{{ progressError }}</span><el-button size="small" @click="startPolling(selectedTask.id)">重试进度</el-button></div>

          <p v-if="summaryLoading" role="status">正在加载分析结果…</p>
          <div v-if="summaryError" role="alert"><p>{{ summaryError }}</p><el-button @click="loadSummary(selectedTask.id)">重试分析结果</el-button></div>
          <section v-if="summary" :key="selectedTask.id" class="results">
            <details v-if="summary.failedLines || summary.partialLines || summary.skippedLines" class="parse-notice">
              <summary>解析提示：失败 {{ summary.failedLines || 0 }} 行，部分解析 {{ summary.partialLines || 0 }} 行，跳过 {{ summary.skippedLines || 0 }} 行</summary>
              <p>统计仅包含成功提取的字段。失败行未计入分析，部分解析行可能缺少查询模式等字段；跳过行不属于可识别的 MongoDB 日志或为空行。</p>
              <ul v-if="Object.keys(summary.parseErrors || {}).length"><li v-for="(count, reason) in summary.parseErrors" :key="reason"><code>{{ reason }}</code>：{{ count }} 行</li></ul>
            </details>
            <div class="result-navigation">
              <div class="result-tabs" role="tablist" aria-label="日志分析视图">
                <button type="button" role="tab" :aria-selected="resultView === 'slow-queries'" :class="{ active: resultView === 'slow-queries' }" @click="resultView = 'slow-queries'">慢查询分析</button>
                <button type="button" role="tab" :aria-selected="resultView === 'diagnostics'" :class="{ active: resultView === 'diagnostics' }" @click="showDiagnostics">运行诊断</button>
              </div>
              <button type="button" class="report-button" :disabled="reportLoading" @click="exportReport">{{ reportLoading ? '正在导出…' : '导出 AI 分析报告' }}</button>
            </div>
            <template v-if="resultView === 'slow-queries'">
              <div class="layout-toolbar"><span>{{ summary.totalLines == null ? '分析结果' : summary.totalLines.toLocaleString() + ' 行日志' }} · {{ (summary.slowQueryCount || 0).toLocaleString() }} 条慢查询</span><button type="button" class="text-button" title="窗口大小已自动保存在此浏览器，可拖动右下角调整" @click="resetLayout">恢复默认布局</button></div>
              <StatisticsTables :summary="summary" />
              <DurationHistogram :buckets="summary.durationDistribution" />
              <PatternTable :patterns="summary.patternStats" @select="sample => detailSelection = { sample }" />
              <BreakdownCharts :summary="summary" />
              <SlowQueryScatter :task-id="selectedTask.id" @select="queryId => detailSelection = { queryId }" />
              <SlowQueryDrawer :task-id="selectedTask.id" :selection="detailSelection" @close="detailSelection = null" />
            </template>
            <template v-else>
              <p v-if="diagnosticsLoading" role="status">正在加载运行诊断…</p>
              <div v-else-if="diagnosticsError" class="request-error" role="alert"><span>{{ diagnosticsError }}</span><button type="button" class="text-button" @click="diagnostics = null; showDiagnostics()">重试诊断</button></div>
              <DiagnosticWorkspace v-else-if="diagnostics" :diagnostics="diagnostics" />
            </template>
          </section>
        </template>
        </template>
      </el-card>
    </main>
  </div>
</template>

<style>
:root {
  --el-color-primary: #347961;
  --el-color-primary-light-3: #70a18e;
  --el-color-primary-light-5: #aac8bb;
  --el-color-primary-light-7: #d0e3da;
  --el-color-primary-light-9: #eff6f2;
  --el-color-success: #4f9477;
  --el-border-radius-base: 7px;
  --text: #344455;
  --muted: #64748b;
  --line: #e1e6eb;
  --surface-soft: #f6f8fa;
}
* { box-sizing: border-box; }
html { background: #f4f6f8; }
body { margin: 0; color: var(--text); background: #f4f6f8; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif; -webkit-font-smoothing: antialiased; }
button, input { font: inherit; }
.app-shell { min-height: 100vh; }
.app-header { display: flex; align-items: center; justify-content: space-between; height: 54px; padding: 0 28px; color: #263c36; background: #fff; border-bottom: 1px solid #dce3e8; }
.brand { display: flex; align-items: center; gap: 12px; }
.brand strong { font-size: 15px; font-weight: 650; }
.brand span { padding-left: 12px; border-left: 1px solid #dce3e8; color: #64748b; font-size: 12px; }
.offline-state { color: #64748b; font-size: 12px; }
.header-actions { display: flex; align-items: center; gap: 12px; }
.workspace-switch { display: flex; align-items: center; gap: 4px; padding: 3px; border: 1px solid #dce3e8; border-radius: 6px; background: #f7f9fa; }
.workspace-switch button { padding: 5px 12px; border: 0; border-radius: 4px; color: #64748b; background: transparent; font-size: 12px; cursor: pointer; }
.workspace-switch button.active { color: #215c44; background: #fff; box-shadow: 0 1px 3px #dbe3e0; }
.page-container { max-width: 1600px; margin: 0 auto; padding: 24px 28px 40px; }
.workspace-card { min-width: 0; border: 0; border-radius: 0; background: transparent; overflow: visible; }
.workspace-card > .el-card__body { padding: 0; }
.workspace-card > .el-card__body > .el-divider { display: none; }
.page-title, .detail-title { display: flex; align-items: center; justify-content: space-between; gap: 20px; }
.page-title { margin: 4px 0 26px; }
.page-title h1, .detail-title h1 { margin: 0; color: #253344; font-size: 22px; line-height: 1.35; font-weight: 600; }
.page-title p, .detail-title p { margin: 7px 0 0; color: #64748b; font-size: 12px; }
.detail-header { margin-bottom: 18px; }
.back-button { display: inline-block; padding: 0; margin-bottom: 15px; border: 0; color: #7b8d8a; background: transparent; font-size: 12px; cursor: pointer; }
.back-button:hover { color: #347961; }
.task-overview { display: grid; grid-template-columns: 1.4fr .65fr 1.6fr; gap: 16px; margin-bottom: 16px; padding: 15px 18px; border: 1px solid #dce3e8; border-radius: 5px; background: #fff; }
.task-overview > div { min-width: 0; }
.task-overview span, .task-overview strong { display: block; }
.task-overview span { margin-bottom: 6px; color: #64748b; font-size: 11px; }
.task-overview strong { color: #4d5e6b; font-size: 12px; font-weight: 500; overflow-wrap: anywhere; }
.task-progress { margin: 0 0 20px; }
.panel { min-width: 0; padding: 18px; border: 1px solid #dce3e8; border-radius: 5px; background: #fff; }
.results { display: flex; flex-direction: column; gap: 16px; min-width: 0; }
.layout-toolbar { display: flex; justify-content: space-between; gap: 12px; color: #64748b; font-size: 12px; }
.result-navigation { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 2px; border-bottom: 1px solid #dce3e8; }
.result-tabs { display: flex; align-items: center; gap: 4px; }
.result-tabs button { margin-bottom: -3px; padding: 9px 12px; border: 0; border-bottom: 2px solid transparent; color: #64748b; background: transparent; font-size: 12px; cursor: pointer; }
.result-tabs button.active { color: #215c44; border-bottom-color: #347961; font-weight: 600; }
.report-button { padding: 7px 12px; border: 1px solid #aac8bb; border-radius: 5px; color: #215c44; background: #eff6f2; font-size: 12px; cursor: pointer; }
.report-button:hover { background: #e2f0e9; }.report-button:disabled { cursor: wait; opacity: .65; }
.request-error { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px; color: #9c472c; background: #fff7ed; }
.parse-notice { padding: 12px 16px; border: 1px solid #e8d9bd; border-radius: 4px; background: #fffbf2; color: #785a28; font-size: 12px; line-height: 1.8; }
.parse-notice summary { cursor: pointer; font-weight: 500; }
.parse-notice p, .parse-notice ul { margin: 8px 0 0; }
.chart-grid, .statistics-grid { display: flex; flex-wrap: wrap; align-items: flex-start; gap: 16px; min-width: 0; }
.chart-grid > .panel, .statistics-grid > .panel { flex: 0 0 auto; width: calc((100% - 16px) / 2); }
.statistics-grid > .panel:not(.is-sized) { height: 254px; }
.results > .panel { width: 100%; }
.chart-grid > * { min-width: 0; }
.section-note { color: #64748b; font-size: 11px; line-height: 1.7; }
.empty-state { display: grid; place-items: center; min-height: 140px; margin: 0; padding: 24px; color: #91a0aa; text-align: center; background: #fafbfc; border: 1px dashed #e6ecf0; border-radius: 8px; font-size: 12px; line-height: 1.8; }
.data-table-scroll { max-height: 320px; overflow: auto; scrollbar-width: thin; scrollbar-color: #d9e2e8 transparent; }
.chart-table { height: 280px; }
.data-table { border-collapse: separate; border-spacing: 0; width: 100%; font-size: 12px; font-variant-numeric: tabular-nums; }
.data-table th, .data-table td { text-align: left; padding: 9px 10px; border-bottom: 1px solid #eaf0f4; overflow-wrap: anywhere; }
.data-table th { position: sticky; top: 0; z-index: 1; color: #596b7d; background: #f2f5f8; font-weight: 500; font-size: 11px; }
.data-table tbody tr:last-child td { border-bottom: 0; }
.data-table tbody tr:hover td { background: #f8fafb; }
.numeric-table th:nth-child(n+2), .numeric-table td:nth-child(n+2) { text-align: right; }
.text-button { padding: 0; border: 0; color: #3e8066; background: transparent; font-size: 12px; cursor: pointer; }
.text-button:hover { color: #215c44; }
.metric-help-tooltip { max-width: 340px; padding: 12px 14px !important; font-size: 12px !important; line-height: 1.8 !important; }
.el-button { font-weight: 450; }
.el-tag { border-radius: 5px; font-size: 11px; }
@media (max-width: 900px) {
  .page-container { padding: 22px 18px 36px; }
  .task-overview { grid-template-columns: 1fr 1fr; }
  .task-overview .log-range { grid-column: 1 / -1; }
}
@media (max-width: 760px) {
  .chart-grid > .panel, .statistics-grid > .panel { width: 100%; }
  .page-title h1, .detail-title h1 { font-size: 22px; }
  .app-header { padding: 0 18px; }
}
@media (max-width: 480px) {
  .page-container { padding: 18px 12px 30px; }
  .panel { padding: 16px; }
  .task-overview { padding: 16px; gap: 14px; }
  .app-header { gap: 8px; padding: 0 10px; }
  .brand span, .offline-state { display: none; }
  .workspace-switch button { padding: 5px 7px; }
  .header-actions { gap: 0; }
}
</style>
