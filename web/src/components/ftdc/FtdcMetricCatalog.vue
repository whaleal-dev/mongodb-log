<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
const props = defineProps({ page: { type: Object, default: () => ({ content: [], total: 0 }) }, selectedId: String, loading: Boolean })
const emit = defineEmits(['search', 'page', 'select'])
const keyword = ref('')
let timer
watch(keyword, value => { clearTimeout(timer); timer = setTimeout(() => emit('search', value), 250) })
onBeforeUnmount(() => clearTimeout(timer))
</script>

<template>
  <div class="metric-catalog">
    <div class="catalog-head"><h2>指标目录</h2><span>{{ page.total || 0 }} 个指标</span></div>
    <el-input v-model="keyword" clearable placeholder="搜索指标路径" />
    <div v-loading="loading" class="metric-list">
      <button v-for="metric in page.content" :key="metric.metricId" type="button" :class="{ selected: selectedId === metric.metricId }" @click="$emit('select', metric)">{{ metric.path }}</button>
      <p v-if="!loading && !page.content?.length" class="section-note">没有匹配指标</p>
    </div>
    <el-pagination v-if="page.total > page.size" size="small" background layout="prev, pager, next" :page-size="page.size" :total="page.total" :current-page="page.page" @current-change="value => $emit('page', value)" />
  </div>
</template>

<style scoped>
.metric-catalog{min-width:0}.catalog-head{display:flex;justify-content:space-between;align-items:center;margin-bottom:12px}.catalog-head h2{margin:0;font-size:14px}.catalog-head span{color:#909399;font-size:12px}.metric-list{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:6px;margin:12px 0;max-height:260px;overflow:auto}.metric-list button{display:block;width:100%;padding:9px 10px;border:1px solid #edf1f4;border-radius:7px;background:#fff;color:#52616e;text-align:left;overflow-wrap:anywhere;cursor:pointer}.metric-list button:hover,.metric-list button.selected{border-color:#b9d3c8;background:#eff6f2;color:#215c44}
</style>
