<script setup>
import MetricLabel from './MetricLabel.vue'
defineProps({ title: String, description: String, view: String, subtitle: String })
defineEmits(['update:view'])
</script>

<template>
  <header class="metric-header">
    <div class="metric-heading">
      <h2><MetricLabel :label="title" :description="description" /></h2>
      <p v-if="subtitle">{{ subtitle }}</p>
    </div>
    <div v-if="view" class="view-toggle" role="group" :aria-label="`${title}展示方式`">
      <button v-for="item in [{ key: 'chart', label: '图表' }, { key: 'table', label: '数据表' }]" :key="item.key"
        type="button" :aria-pressed="view === item.key" :class="{ active: view === item.key }" @click="$emit('update:view', item.key)">{{ item.label }}</button>
    </div>
    <slot />
  </header>
</template>

<style scoped>
.metric-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.metric-heading { min-width: 0; }
h2 { margin: 0; color: #253344; font-size: 14px; line-height: 1.5; font-weight: 600; }
p { margin: 4px 0 0; color: #64748b; font-size: 11px; line-height: 1.5; }
.view-toggle { display: flex; flex-shrink: 0; gap: 2px; padding: 3px; border-radius: 7px; background: #f0f3f6; }
.view-toggle button { border: 0; border-radius: 5px; padding: 5px 10px; color: #7a8798; background: transparent; font-size: 11px; cursor: pointer; white-space: nowrap; }
.view-toggle .active { color: #285d4d; background: #fff; box-shadow: 0 1px 3px #233b4520; }
.view-toggle button:focus-visible { outline: 2px solid #84b7a3; outline-offset: 1px; }
@media (max-width: 600px) { .metric-header { flex-wrap: wrap; } }
</style>
