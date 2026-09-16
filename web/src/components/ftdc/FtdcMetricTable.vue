<script setup>
import { formatDate } from '../../utils/format.js'
defineProps({ page: { type: Object, default: () => ({ offset: 0, limit: 100, total: 0, timestamps: [], values: [] }) }, loading: Boolean })
const emit = defineEmits(['page'])
</script>
<template>
  <div v-loading="loading">
    <div class="data-table-scroll"><table class="data-table numeric-table"><thead><tr><th>时间</th><th>Epoch Milliseconds</th><th>原始值</th></tr></thead><tbody>
      <tr v-for="(timestamp,index) in page.timestamps" :key="`${timestamp}-${index}`"><td>{{ formatDate(timestamp) }}</td><td>{{ timestamp }}</td><td>{{ page.values[index] }}</td></tr>
    </tbody></table></div>
    <el-pagination v-if="page.total > page.limit" background layout="prev, pager, next" :page-size="page.limit" :total="page.total" :current-page="Math.floor(page.offset / page.limit) + 1" @current-change="value => $emit('page', value)" />
  </div>
</template>
