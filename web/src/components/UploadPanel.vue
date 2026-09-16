<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { createTask } from '../api/tasks.js'

const emit = defineEmits(['created'])
const name = ref('')
const fileList = ref([])
const submitting = ref(false)

async function submit() {
  const files = fileList.value.map((item) => item.raw).filter(Boolean)
  if (!files.length) {
    ElMessage.warning('请先选择 MongoDB 日志文件')
    return
  }
  submitting.value = true
  try {
    const task = await createTask(name.value, files)
    ElMessage.success('任务已创建，正在本机分析')
    fileList.value = []
    name.value = ''
    emit('created', task)
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="upload-section">
    <div class="section-title">
      <h2>新建分析</h2>
      <span>支持多文件合并分析</span>
    </div>
    <div class="upload-form">
      <div class="form-item task-name">
        <label>任务名称</label>
        <el-input v-model="name" maxlength="60" placeholder="可选，默认使用文件名" />
      </div>
      <div class="form-item file-picker">
        <label>MongoDB 日志</label>
      <el-upload
        v-model:file-list="fileList"
        multiple
        :auto-upload="false"
        :limit="20"
        accept=".log,.txt,.json,.gz"
      >
          <el-button type="primary">选择日志文件</el-button>
          <template #tip>
            <span class="upload-tip">支持普通文本、结构化 JSON、旧版日志和 .gz</span>
          </template>
      </el-upload>
      </div>
      <div class="form-action">
        <el-button type="success" :loading="submitting" @click="submit">开始分析</el-button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.upload-section { padding: 18px; border: 1px solid #dce3e8; border-radius: 5px; background: #fff; }
.section-title { display: flex; align-items: baseline; gap: 12px; margin-bottom: 18px; }
.section-title h2 { margin: 0; font-size: 15px; color: #334b46; }
.section-title span { color: #909399; font-size: 12px; }
.upload-form { display: grid; grid-template-columns: minmax(220px, 320px) minmax(360px, 1fr) auto; align-items: start; gap: 20px; }
.form-item label { display: block; margin-bottom: 8px; color: #606266; font-size: 13px; }
.file-picker :deep(.el-upload-list) { margin: 6px 0 0; }
.upload-tip { display: inline-block; margin-left: 10px; color: #909399; font-size: 12px; }
.form-action { padding-top: 29px; }
@media (max-width: 600px) {
  .section-title { flex-direction: column; gap: 6px; }
  .upload-form { grid-template-columns: 1fr !important; }
  .upload-tip { display: block; margin: 8px 0 0; }
}
@media (max-width: 980px) {
  .upload-form { grid-template-columns: 1fr 1fr; }
  .form-action { grid-column: 1 / -1; padding-top: 0; }
}
</style>
