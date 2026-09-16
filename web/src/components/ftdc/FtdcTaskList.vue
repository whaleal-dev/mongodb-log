<script setup>
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteFtdcTask } from '../../api/ftdcTasks.js'
import { formatBytes, formatDate, formatTimeRange } from '../../utils/format.js'

defineProps({ tasks: { type: Array, default: () => [] } })
const emit = defineEmits(['select', 'deleted'])
const deleting = ref('')
const labels = { QUEUED: '排队中', RUNNING: '建立索引中', COMPLETED: '已完成', FAILED: '失败' }
const types = { QUEUED: 'info', RUNNING: 'warning', COMPLETED: 'success', FAILED: 'danger' }
async function remove(task) {
  try {
    await ElMessageBox.confirm(`删除任务「${task.name}」保存的 Metric 副本和索引？用户原始文件不会被删除。`, '删除 Metric 任务', { type: 'warning' })
  } catch { return }
  deleting.value = task.id
  try { await deleteFtdcTask(task.id); emit('deleted', task.id); ElMessage.success('Metric 任务数据已清理') }
  catch (error) { ElMessage.error(error.message) }
  finally { deleting.value = '' }
}
</script>

<template>
  <section class="card ftdc-task-list">
    <div class="ftdc-section-title"><h2>Metric 任务</h2><span>共 {{ tasks.length }} 个任务</span></div>
    <el-empty v-if="!tasks.length" description="上传 Metric 文件后，任务会显示在这里" />
    <el-table v-else :data="tasks">
      <el-table-column label="任务名称" min-width="180"><template #default="scope"><el-button link type="primary" @click="$emit('select', scope.row)">{{ scope.row.name }}</el-button></template></el-table-column>
      <el-table-column label="状态" width="120"><template #default="scope"><el-tag :type="types[scope.row.status]">{{ labels[scope.row.status] }}</el-tag></template></el-table-column>
      <el-table-column label="创建时间" width="180"><template #default="scope">{{ formatDate(scope.row.createdAtEpochMillis) }}</template></el-table-column>
      <el-table-column label="文件" min-width="160"><template #default="scope">{{ scope.row.files.length }} 个／{{ formatBytes(scope.row.totalBytes) }}</template></el-table-column>
      <el-table-column label="时间范围" min-width="280"><template #default="scope">{{ formatTimeRange(scope.row.startEpochMillis, scope.row.endEpochMillis) }}</template></el-table-column>
      <el-table-column label="操作" width="170"><template #default="scope">
        <el-button size="small" type="primary" @click="$emit('select', scope.row)">查看指标</el-button>
        <el-button link type="danger" :loading="deleting === scope.row.id" :disabled="!['COMPLETED','FAILED'].includes(scope.row.status)" @click="remove(scope.row)">删除</el-button>
      </template></el-table-column>
    </el-table>
  </section>
</template>

<style scoped>
.ftdc-section-title{display:flex;justify-content:space-between;align-items:center;margin-bottom:12px}.ftdc-section-title h2{margin:0;font-size:15px}.ftdc-section-title span{color:#909399;font-size:12px}
</style>
