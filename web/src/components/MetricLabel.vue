<script setup>
import { ElTooltip } from 'element-plus'
defineProps({ label: String, description: String, helpOnly: Boolean })
</script>

<template>
  <span class="metric-label">
    <span v-if="!helpOnly">{{ label }}</span>
    <ElTooltip v-if="description" :content="description" placement="top" :show-after="120" :trigger="['hover', 'focus']" popper-class="metric-help-tooltip">
      <button class="metric-help" type="button" :aria-label="`${label}指标说明`">?</button>
    </ElTooltip>
  </span>
</template>

<style scoped>
.metric-label { display: inline-flex; align-items: center; gap: 7px; max-width: 100%; }
.metric-help { flex: 0 0 auto; display: inline-grid; place-items: center; padding: 0; width: 16px; height: 16px; border: 1px solid #bdc7d3; border-radius: 50%; color: #8391a2; background: transparent; font: 11px/1 Arial, sans-serif; cursor: help; }
.metric-help:hover, .metric-help:focus-visible { color: #246853; border-color: #246853; background: #edf6f2; outline: 2px solid #dceee6; outline-offset: 2px; }
</style>
