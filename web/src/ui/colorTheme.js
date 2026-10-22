import { computed, readonly, ref } from 'vue'
import { createThemeTokens, DEFAULT_THEME_COLOR, normalizeThemeColor } from './colors.js'

export const THEME_STORAGE_KEY = 'mongodb-log:theme-color:v1'
const color = ref(DEFAULT_THEME_COLOR)
const saveError = ref('')
const tokens = computed(() => createThemeTokens(color.value))

function apply(value) {
  color.value = value
  for (const [name, token] of Object.entries(tokens.value)) document.documentElement.style.setProperty(name, token)
}

function retrySave() {
  try {
    localStorage.setItem(THEME_STORAGE_KEY, color.value)
    saveError.value = ''
  } catch (error) {
    saveError.value = `主题色未保存：${error.message || '浏览器存储不可用'}。当前预览仍然有效。`
  }
}

function setColor(value) {
  const normalized = normalizeThemeColor(value)
  if (!normalized) return false
  apply(normalized)
  retrySave()
  return true
}

export function initializeColorTheme() {
  saveError.value = ''
  let saved, storage
  try { storage = localStorage; saved = storage.getItem(THEME_STORAGE_KEY) }
  catch (error) { saveError.value = `主题色无法读取：${error.message || '浏览器存储不可用'}。本次可继续设置。` }
  apply(normalizeThemeColor(saved) || DEFAULT_THEME_COLOR)
  function sync(event) {
    if (event.storageArea && event.storageArea !== storage) return
    if (event.key !== THEME_STORAGE_KEY && event.key !== null) return
    const next = event.newValue === null ? DEFAULT_THEME_COLOR : normalizeThemeColor(event.newValue)
    if (next) { apply(next); saveError.value = '' }
  }
  window.addEventListener('storage', sync)
  return () => window.removeEventListener('storage', sync)
}

export function useColorTheme() {
  return { color: readonly(color), tokens, saveError: readonly(saveError), setColor, retrySave }
}
