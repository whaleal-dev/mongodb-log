<script setup>
import { formatBytes, formatDate, formatTimeRange } from '../utils/format.js'
import { ref } from 'vue'
import { ElMessageBox, ElMessage } from 'element-plus'
import { deleteTask } from '../api/tasks.js'

defineProps({ tasks: { type: Array, default: () => [] } })
const emit = defineEmits(['select', 'deleted'])
const deleting = ref(null)
async function remove(task) {
  try {
    await ElMessageBox.confirm(`删除任务「${task.name}」及其分析结果、慢查询原文和临时副本？用户选择的原始日志文件不会被删除。`, '删除任务',
      { confirmButtonText: '删除任务和数据', cancelButtonText: '取消', type: 'warning' })
  } catch { return }
  deleting.value = task.id
  try {
    await deleteTask(task.id)
    emit('deleted', task.id)
    ElMessage.success('任务及对应数据已清理')
  } catch (error) { ElMessage.error(error.message) }
  finally { deleting.value = null }
}

const statusText = { QUEUED: '排队中', RUNNING: '分析中', COMPLETED: '已完成', FAILED: '失败' }
const statusType = { QUEUED: 'info', RUNNING: 'warning', COMPLETED: 'success', FAILED: 'danger' }
</script>

<template>
  <section class="task-list">
    <div class="section-title">
      <h2>分析任务</h2>
      <span>共 {{ tasks.length }} 个任务</span>
    </div>
    <el-empty v-if="!tasks.length" description="上传日志后，分析任务会显示在这里" />
    <el-table v-else :data="tasks" :header-cell-style="{ background: '#f7f9fb', color: '#8a98a5', fontWeight: 400, fontSize: '11px' }">
      <el-table-column label="任务名称" min-width="220">
        <template #default="scope">
          <el-button type="primary" link @click="$emit('select', scope.row)">{{ scope.row.name }}</el-button>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="scope">
          <el-tag :type="statusType[scope.row.status]" size="small">{{ statusText[scope.row.status] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="190">
        <template #default="scope">{{ formatDate(scope.row.createdAtEpochMillis) }}</template>
      </el-table-column>
      <el-table-column label="上传文件" min-width="180" show-overflow-tooltip>
        <template #default="scope">{{ scope.row.files.map((file) => file.originalName).join('、') }}</template>
      </el-table-column>
      <el-table-column label="文件大小" width="120">
        <template #default="scope">{{ formatBytes(scope.row.totalBytes) }}</template>
      </el-table-column>
      <el-table-column label="日志时间范围" min-width="320">
        <template #default="scope">{{ formatTimeRange(scope.row.logStartEpochMillis, scope.row.logEndEpochMillis) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="scope">
          <el-button type="primary" size="small" @click="$emit('select', scope.row)">查看分析结果</el-button>
          <el-button type="danger" link :loading="deleting === scope.row.id" :disabled="Boolean(deleting) || !['COMPLETED', 'FAILED'].includes(scope.row.status)"
            :title="['QUEUED', 'RUNNING'].includes(scope.row.status) ? '任务结束后可删除' : '删除任务和对应数据'" @click="remove(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<style scoped>
.task-list { min-width: 0; margin-top: 16px; padding: 18px; border: 1px solid #dce3e8; border-radius: 5px; background: #fff; }
.section-title { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.section-title h2 { margin: 0; font-size: 15px; color: #334b46; }
.section-title span { color: #909399; font-size: 12px; }
</style>
