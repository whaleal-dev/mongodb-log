<script setup>
import { ref, watch } from 'vue'
import { ElButton, ElDialog } from 'element-plus'
import { DEFAULT_THEME_COLOR, normalizeThemeColor, themePresets } from '../ui/colors.js'
import { useColorTheme } from '../ui/colorTheme.js'

const { color, saveError, setColor, retrySave } = useColorTheme()
const visible = ref(false)
const draft = ref(color.value)
const draftDirty = ref(false)
const formatError = ref(false)

watch(color, value => { if (!draftDirty.value) draft.value = value })

function open() {
  draft.value = color.value
  draftDirty.value = false
  formatError.value = false
  visible.value = true
}
function choose(value) {
  draftDirty.value = false
  formatError.value = false
  setColor(value)
  draft.value = color.value
}
function inputHex(event) {
  draft.value = event.target.value
  draftDirty.value = true
  formatError.value = false
  if (normalizeThemeColor(draft.value)) setColor(draft.value)
}
function validate() {
  const normalized = normalizeThemeColor(draft.value)
  formatError.value = !normalized
  if (normalized) { draft.value = normalized; draftDirty.value = false }
}
</script>

<template>
  <button type="button" class="theme-settings-button" @click="open">主题色<span v-if="saveError" aria-label="主题色未保存"> · !</span></button>
  <ElDialog v-model="visible" title="主题色" class="theme-settings-dialog" width="min(460px, calc(100vw - 24px))" align-center append-to-body>
    <p class="theme-description">选择常用配色，或输入自定义主色。修改即时生效，保存在当前浏览器。</p>
    <div class="theme-presets" aria-label="预设主题色">
      <button v-for="preset in themePresets" :key="preset.color" type="button" :aria-label="preset.name"
        :aria-pressed="color === preset.color" :class="{ selected: color === preset.color }" @click="choose(preset.color)">
        <span class="theme-swatch" :style="{ background: preset.color }" aria-hidden="true"></span>
        <span>{{ preset.name }}</span><span class="theme-check" aria-hidden="true">{{ color === preset.color ? '✓' : '' }}</span>
      </button>
    </div>
    <label class="theme-input-label" for="theme-hex">自定义主色</label>
    <div class="theme-custom">
      <input type="color" aria-label="选择自定义主色" :value="color" @input="choose($event.target.value)" />
      <input id="theme-hex" type="text" :value="draft" spellcheck="false" autocomplete="off" placeholder="#RRGGBB"
        :aria-invalid="formatError" aria-describedby="theme-hex-hint theme-format-error" @input="inputHex" @blur="validate" @keydown.enter.prevent="validate" />
    </div>
    <p id="theme-hex-hint" class="theme-hint">使用六位 HEX，例如 #347961。页面保持浅色，状态色和多系列图表配色独立。</p>
    <p v-if="formatError" id="theme-format-error" class="theme-error" role="status">请输入完整的 #RRGGBB 六位颜色值。</p>
    <div v-if="saveError" class="theme-save-error" role="alert"><p>{{ saveError }}</p><ElButton size="small" @click="retrySave">重试保存</ElButton></div>
    <template #footer>
      <div class="theme-footer"><ElButton @click="choose(DEFAULT_THEME_COLOR)">恢复默认</ElButton><ElButton type="primary" @click="visible = false">完成</ElButton></div>
    </template>
  </ElDialog>
</template>

<style scoped>
.theme-settings-button { padding: 5px 10px; border: 1px solid #d7e0e5; border-radius: 5px; color: #526b66; background: #fff; font-size: 12px; white-space: nowrap; cursor: pointer; }
.theme-settings-button:hover { border-color: var(--primary-line); color: var(--primary-text); background: var(--primary-soft); }
.theme-settings-button span { color: #BD3541; }
.theme-description { margin: 0 0 18px; color: var(--muted); font-size: 13px; line-height: 1.7; }
.theme-presets { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.theme-presets button { display: flex; align-items: center; gap: 8px; min-width: 0; padding: 10px; border: 1px solid var(--line); border-radius: 6px; color: var(--text); background: #fff; font-size: 12px; cursor: pointer; }
.theme-presets button:hover { background: var(--primary-soft); }
.theme-presets button.selected { border-color: var(--primary-mark); color: var(--primary-text); background: var(--primary-soft); }
.theme-swatch { flex: 0 0 18px; height: 18px; border: 1px solid #0002; border-radius: 50%; }
.theme-check { margin-left: auto; font-weight: 600; }
.theme-input-label { display: block; margin: 20px 0 8px; font-size: 13px; }
.theme-custom { display: flex; align-items: center; gap: 10px; }
.theme-custom input[type="color"] { width: 40px; height: 34px; padding: 2px; border: 1px solid var(--line); border-radius: 5px; background: #fff; cursor: pointer; }
#theme-hex { flex: 1; min-width: 0; height: 34px; padding: 0 10px; border: 1px solid var(--line); border-radius: 5px; color: var(--text); background: #fff; font: 13px ui-monospace, SFMono-Regular, Consolas, monospace; }
#theme-hex[aria-invalid="true"] { border-color: #BD3541; }
.theme-hint { margin: 9px 0 0; color: var(--muted); font-size: 11px; line-height: 1.7; }
.theme-error, .theme-save-error { color: #BD3541; font-size: 12px; line-height: 1.7; }
.theme-save-error { margin-top: 14px; padding: 10px 12px; border: 1px solid #f2c6c6; border-radius: 5px; background: #fff6f6; }
.theme-save-error p { margin: 0 0 8px; }
.theme-footer { display: flex; justify-content: space-between; }
</style>
