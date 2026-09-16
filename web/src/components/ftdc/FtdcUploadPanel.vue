<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { createFtdcTask } from '../../api/ftdcTasks.js'

const emit = defineEmits(['created'])
const name = ref('')
const fileList = ref([])
const submitting = ref(false)

async function submit() {
  const files = fileList.value.map(item => item.raw).filter(Boolean)
  if (!files.length) return ElMessage.warning('请先选择 Metric 文件')
  if (files.length > 20) return ElMessage.warning('一个任务最多选择 20 个 Metric 文件')
  submitting.value = true
  try {
    const task = await createFtdcTask(name.value, files)
    fileList.value = []
    name.value = ''
    ElMessage.success('Metric 任务已创建，正在本机建立索引')
    emit('created', task)
  } catch (error) { ElMessage.error(error.message) }
  finally { submitting.value = false }
}
</script>

<template>
  <section class="card ftdc-upload-card">
    <div class="card-header"><div><h2>新建 Metric 分析</h2><p>最多 20 个 diagnostic.data/metrics.* 文件</p></div></div>
    <div class="upload-actions">
      <label class="task-name-field">任务名称<el-input v-model="name" maxlength="60" placeholder="可选，默认使用文件名" /></label>
      <div class="file-picker">
        <el-upload v-model:file-list="fileList" multiple :auto-upload="false" :limit="20">
          <el-button type="primary">选择 Metric 文件</el-button>
          <template #tip><span class="section-note">按内容识别，不要求固定扩展名</span></template>
        </el-upload>
      </div>
      <el-button type="success" :loading="submitting" @click="submit">开始建立索引</el-button>
    </div>
  </section>
</template>

<style scoped>
.card-header{margin-bottom:16px}.card-header h2{margin:0;color:#2f4050;font-size:16px}.card-header p{margin:5px 0 0;color:#7a8996;font-size:12px}.upload-actions{display:flex;align-items:flex-start;gap:12px;flex-wrap:wrap}.task-name-field{flex:0 1 320px;color:#606266;font-size:12px}.task-name-field>.el-input{display:block;margin-top:7px}.file-picker{flex:1 1 360px;min-height:66px;padding:10px 12px;border:1px dashed #b9cad7;border-radius:10px;background:#f9fbfc}.file-picker :deep(.el-upload){display:block}.file-picker :deep(.el-upload-list){margin-bottom:0}.upload-actions>.el-button{margin-top:27px}@media(max-width:720px){.task-name-field,.file-picker{flex-basis:100%}.upload-actions>.el-button{margin-top:0}}
</style>
