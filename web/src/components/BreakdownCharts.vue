<script setup>
import { computed, ref } from 'vue'
import PieChart from './PieChart.vue'
import MetricHeader from './MetricHeader.vue'
import MetricPanel from './MetricPanel.vue'
import ConnectionsChart from './ConnectionsChart.vue'

const props = defineProps({ summary: { type: Object, required: true } })
const operation = ref('find')
const values = (map, field, divisor = 1) => Object.fromEntries(Object.entries(map || {}).map(([name, stat]) => [name, stat[field] / divisor]))
const cpuBuckets = computed(() => Object.fromEntries(
  Object.entries(props.summary.cpuByOperationBuckets?.[operation.value] || {}).map(([bucket, count]) => {
    const end = Number(bucket)
    return [end === 0 ? '0% ≤ CPU 比例 < 1%' : (end - 9) + '% ≤ CPU 比例 < ' + (end + 1) + '%', count]
  }),
))
</script>

<template>
  <div class="chart-grid">
    <PieChart title="操作类型占比" :values="values(summary.operations, 'count')"
      description="全部慢查询中，各操作类型的数量及占比。用于判断 find、update、insert 等操作的构成；单位为次。" />
    <PieChart title="集合响应量占比" :values="summary.namespaceResponseBytes || {}" unit="B"
      description="按 Namespace（数据库名.集合名）累计全部慢查询的响应数据量 reslen，并计算占比。包含全部集合，不只统计 Top 20；单位为字节，不代表连接数。"
      :empty-text="summary.namespaceResponseBytes == null ? '此历史任务未保存完整 Namespace 响应量，请重新上传分析' : '暂无响应数据量'" />
    <PieChart title="执行计划分布" :values="summary.patternStats == null ? {} : values(summary.plans, 'count')"
      description="全部慢查询中各类执行计划的次数及占比。COLLSCAN 表示集合扫描，IXSCAN 表示索引扫描；unknown 表示日志未提供计划。"
      :empty-text="summary.patternStats == null ? '此历史任务未保存完整执行计划统计，请重新上传分析' : '暂无执行计划数据'" />
    <template v-if="summary.cpuAvailable">
      <PieChart title="CPU 耗时占比" :values="values(summary.cpuByOperationNamespace, 'totalCpuNanos', 1000000)" unit="ms"
        description="按操作类型与 Namespace 汇总 CPU 耗时并计算占比。仅统计明确提供 cpuNanos 的慢查询；纳秒换算为毫秒。CPU 耗时与用户等待的总耗时含义不同。" />
      <PieChart title="CPU 耗时比例分布" :values="cpuBuckets"
        description="按操作类型统计 CPU 耗时占单次查询总耗时的比例，即 cpuNanos÷1000000÷durationMillis。沿用 LogVis：百分比先向下取整，再向上归入十位桶；表中标明实际区间，可超过 100%。扇区数值为查询次数。"
        :empty-text="summary.cpuByOperationBuckets == null ? '此历史任务未保存 CPU 区间统计，请重新上传分析' : '该操作类型暂无有效 CPU 比例样本'">
        <el-tabs v-if="summary.cpuByOperationBuckets != null" v-model="operation" class="cpu-tabs">
          <el-tab-pane v-for="name in ['insert', 'update', 'delete', 'find']" :key="name" :label="name" :name="name" />
        </el-tabs>
      </PieChart>
    </template>
    <MetricPanel v-else layout-key="cpu-empty">
      <MetricHeader title="CPU 分析" description="日志包含 cpuNanos 时展示 CPU 耗时占比及比例分布。缺失不代表 CPU 消耗为零。" />
      <p class="empty-state">日志未提供 cpuNanos</p>
    </MetricPanel>
    <ConnectionsChart :samples="summary.averageConnections" />
  </div>
</template>

<style scoped>
.cpu-tabs :deep(.el-tabs__header) { margin-bottom: 4px; }
.cpu-tabs :deep(.el-tabs__item) { height: 32px; font-size: 12px; }
.cpu-tabs :deep(.el-tabs__nav-wrap::after) { height: 1px; background: #edf1f5; }
</style>
