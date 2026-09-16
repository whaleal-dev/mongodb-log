<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchSlowQueries } from '../api/tasks.js'
import { formatDuration, formatDate } from '../utils/format.js'

const props = defineProps({ taskId: { type: String, required: true } })
const emit = defineEmits(['select'])
const loading = ref(false)
const rows = ref([])
const total = ref(0)
const filters = reactive({ namespace: '', operation: '', minDurationMillis: '', planSummary: '' })
const page = ref(1)
const size = ref(50)

async function load() {
  loading.value = true
  try {
    const result = await fetchSlowQueries(props.taskId, { page: page.value, size: size.value, ...filters })
    rows.value = result.content
    total.value = result.total
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    loading.value = false
  }
}

function search() { page.value = 1; load() }
function reset() {
  Object.assign(filters, { namespace: '', operation: '', minDurationMillis: '', planSummary: '' })
  search()
}
watch(() => props.taskId, () => { page.value = 1; load() })
onMounted(load)
</script>

<template>
  <section class="panel">
    <div class="section-heading"><h2>慢查询信息（Top 5000）</h2><small>按耗时从高到低，点击行查看原始日志</small></div>
    <div class="filters">
      <el-input v-model="filters.namespace" clearable placeholder="Namespace" @keyup.enter="search" />
      <el-input v-model="filters.operation" clearable placeholder="Operation" @keyup.enter="search" />
      <el-input v-model="filters.planSummary" clearable placeholder="Plan Summary" @keyup.enter="search" />
      <el-input v-model="filters.minDurationMillis" type="number" min="0" placeholder="最低耗时 ms" @keyup.enter="search" />
      <el-button type="primary" @click="search">筛选</el-button>
      <el-button @click="reset">重置</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" stripe @row-click="(row) => emit('select', row)">
      <el-table-column label="时间" width="175"><template #default="scope">{{ formatDate(scope.row.timestampEpochMillis) }}</template></el-table-column>
      <el-table-column prop="operation" label="Operation" width="130" />
      <el-table-column prop="namespace" label="Namespace" min-width="190" show-overflow-tooltip />
      <el-table-column prop="planSummary" label="执行计划" width="130" show-overflow-tooltip />
      <el-table-column label="耗时" width="120" sortable><template #default="scope"><strong class="duration">{{ formatDuration(scope.row.durationMillis) }}</strong></template></el-table-column>
      <el-table-column prop="remote" label="客户端 IP" width="135" />
    </el-table>
    <div class="pagination">
      <span>共 {{ total.toLocaleString() }} 条</span>
      <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total" :page-sizes="[20, 50, 100, 200]" layout="sizes, prev, pager, next" @change="load" />
    </div>
  </section>
</template>

<style scoped>
.section-heading { display: flex; justify-content: space-between; align-items: flex-start; }
.section-heading h2 { margin: 0 0 16px; font-size: 16px; }
.section-heading small { color: var(--muted); }
.filters { display: grid; grid-template-columns: 1.4fr 1fr 1fr 1fr auto auto; gap: 9px; margin-bottom: 16px; }
.duration { color: #b84e38; }
.pagination { display: flex; align-items: center; justify-content: space-between; gap: 18px; margin-top: 16px; color: var(--muted); font-size: 12px; }
@media (max-width: 980px) { .filters { grid-template-columns: repeat(2, 1fr); } }
</style>
