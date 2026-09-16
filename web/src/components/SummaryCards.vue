<script setup>
import { computed } from 'vue'
import { formatDuration } from '../utils/format.js'

const props = defineProps({ summary: { type: Object, required: true } })
const cards = computed(() => [
  { label: '日志总行数', value: props.summary.totalLines?.toLocaleString(), tone: 'neutral' },
  { label: '慢查询', value: props.summary.slowQueryCount?.toLocaleString(), tone: 'green' },
  { label: '慢查询总耗时', value: formatDuration(props.summary.totalSlowDurationMillis), tone: 'amber' },
  { label: '解析成功', value: props.summary.successLines?.toLocaleString(), tone: 'blue' },
  { label: '部分解析', value: props.summary.partialLines?.toLocaleString(), tone: 'amber' },
  { label: '解析失败', value: props.summary.failedLines?.toLocaleString(), tone: 'red' },
])
</script>

<template>
  <section class="summary-section">
    <div class="section-title">分析摘要 <span>完整日志统计</span></div>
    <div class="summary-grid">
    <article v-for="card in cards" :key="card.label" class="summary-card" :class="card.tone">
      <span>{{ card.label }}</span>
      <strong>{{ card.value }}</strong>
    </article>
    </div>
  </section>
</template>

<style scoped>
.summary-section { min-width: 0; padding-top: 4px; }
.section-title { margin-bottom: 10px; font-size: 16px; font-weight: 700; }
.section-title span { margin-left: 8px; color: #909399; font-size: 12px; font-weight: 400; }
.summary-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); min-width: 0; border: 1px solid var(--line); }
.summary-card { min-width: 0; padding: 15px 16px; border-right: 1px solid var(--line); background: #fff; }
.summary-card:last-child { border-right: 0; }
.summary-card span { display: block; color: var(--muted); font-size: 12px; }
.summary-card strong { display: block; margin-top: 8px; overflow: hidden; font-size: 21px; text-overflow: ellipsis; white-space: nowrap; }
.summary-card.green strong { color: #529b2e; }
.summary-card.red strong { color: #f56c6c; }
@media (max-width: 1180px) {
  .summary-grid { grid-template-columns: repeat(3, 1fr); }
  .summary-card { border-bottom: 1px solid var(--line); }
  .summary-card:nth-child(3n) { border-right: 0; }
  .summary-card:nth-last-child(-n+3) { border-bottom: 0; }
}
@media (max-width: 650px) { .summary-grid { grid-template-columns: repeat(2, 1fr); } }
</style>
