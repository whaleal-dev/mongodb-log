<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElDialog } from 'element-plus'

const storageKey = 'mongodb-log:author-promotion:v2'
const legacyKey = 'mongodb-log:author-promotion:last-shown:v1'
const reminderDelay = 24 * 60 * 60 * 1000
const visible = ref(false)
let state = { firstUsedAt: Date.now(), viewed: false }
let timer

const links = [
  { title: '作者简介', description: '了解作者的经历、技术背景与实践。',
    url: 'https://docs.whaleal.com/resume-site/', label: 'docs.whaleal.com/resume-site/' },
  { title: 'MongoDB 核心作品', description: 'Whaleal Platform · MongoDB 监控、管理与备份平台。',
    url: 'https://www.whaleal.io/', label: 'www.whaleal.io' },
  { title: '组织公开仓库', description: '浏览 whaleal-dev 的公开项目、源码与更新。',
    url: 'https://github.com/whaleal-dev', label: 'github.com/whaleal-dev' },
]

function readState(now) {
  try {
    const raw = localStorage.getItem(storageKey)
    const saved = JSON.parse(raw)
    if (saved && Number.isFinite(saved.firstUsedAt) && saved.firstUsedAt > 0
      && saved.firstUsedAt <= now && typeof saved.viewed === 'boolean') {
      state = { firstUsedAt: saved.firstUsedAt, viewed: state.viewed || saved.viewed }
    } else if (raw === null) {
      const previous = Number(localStorage.getItem(legacyKey))
      if (Number.isFinite(previous) && previous > 0 && previous <= now) {
        state = { firstUsedAt: previous, viewed: true }
      }
    }
  } catch { /* 存储不可用或内容损坏时，保留当前页面状态。 */ }
  if (state.firstUsedAt > now) state.firstUsedAt = now
}

function saveState() {
  try { localStorage.setItem(storageKey, JSON.stringify(state)) } catch { /* 当前页面仍只自动提醒一次。 */ }
}

function open() {
  readState(Date.now())
  state.viewed = true
  saveState()
  window.clearTimeout(timer)
  visible.value = true
}

function checkReminder() {
  window.clearTimeout(timer)
  const now = Date.now()
  readState(now)
  saveState()
  if (state.viewed) return
  const remaining = reminderDelay - (now - state.firstUsedAt)
  if (remaining > 0) {
    timer = window.setTimeout(checkReminder, remaining)
  } else if (document.visibilityState === 'visible') {
    open()
  }
}

function pageVisible() {
  if (document.visibilityState === 'visible') checkReminder()
}

onMounted(() => {
  checkReminder()
  window.addEventListener('focus', checkReminder)
  document.addEventListener('visibilitychange', pageVisible)
})
onBeforeUnmount(() => {
  window.clearTimeout(timer)
  window.removeEventListener('focus', checkReminder)
  document.removeEventListener('visibilitychange', pageVisible)
})
</script>

<template>
  <button type="button" class="about-author-button" @click="open">关于作者</button>
  <ElDialog v-model="visible" title="认识作者与 Whaleal" class="author-promotion-dialog"
    width="min(640px, calc(100vw - 32px))" align-center append-to-body :show-close="false">
    <template #header>
      <div class="promotion-header">
        <div><span class="promotion-badge">作者推广</span><span class="promotion-title" role="heading" aria-level="2">认识作者与 Whaleal</span></div>
        <button type="button" class="promotion-close" aria-label="关闭作者推广" @click="visible = false">×</button>
      </div>
    </template>
    <p class="promotion-intro">了解项目作者、MongoDB 核心作品与组织开源项目。</p>
    <div class="promotion-links">
      <a v-for="(link, index) in links" :key="link.url" :href="link.url" target="_blank"
        rel="noopener noreferrer" referrerpolicy="no-referrer" :aria-label="`${link.title}（在新标签页打开）`">
        <span class="promotion-number" aria-hidden="true">0{{ index + 1 }}</span>
        <div class="promotion-link-text"><strong>{{ link.title }}</strong><p>{{ link.description }}</p><span>{{ link.label }}</span></div>
        <span class="promotion-arrow" aria-hidden="true">↗</span>
      </a>
    </div>
    <template #footer>
      <div class="promotion-footer"><span>感谢使用，随时可从「关于作者」再次查看。</span><button type="button" @click="visible = false">继续使用</button></div>
    </template>
  </ElDialog>
</template>

<style scoped>
.about-author-button { padding: 5px 10px; border: 1px solid #d7e0e5; border-radius: 5px; color: #526b66; background: #fff; font-size: 12px; white-space: nowrap; cursor: pointer; }
.about-author-button:hover { border-color: var(--primary-line); color: var(--primary-text); background: var(--primary-soft); }
.promotion-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
.promotion-badge { display: block; margin-bottom: 8px; color: var(--primary-text); font-size: 11px; font-weight: 600; letter-spacing: 2px; }
.promotion-title { color: #253b37; font-size: 22px; font-weight: 650; line-height: 1.4; }
.promotion-close { flex-shrink: 0; width: 30px; height: 30px; border: 0; border-radius: 5px; color: #6f7f89; background: transparent; font-size: 24px; line-height: 1; cursor: pointer; }
.promotion-close:hover { color: var(--primary-text); background: var(--primary-soft); }
.promotion-intro { margin: 10px 0 20px; color: #657580; font-size: 13px; line-height: 1.7; }
.promotion-links { display: flex; flex-direction: column; gap: 12px; }
.promotion-links a { display: flex; align-items: flex-start; gap: 14px; padding: 16px; border: 1px solid var(--primary-line); border-radius: 8px; color: inherit; background: var(--primary-soft); text-decoration: none; }
.promotion-links a:hover { border-color: var(--primary-mark); background: var(--primary-soft-hover); }
.promotion-number { flex-shrink: 0; display: grid; place-items: center; width: 34px; height: 34px; border-radius: 7px; color: var(--primary-text); background: var(--primary-soft-hover); font: 12px ui-monospace, SFMono-Regular, Consolas, monospace; }
.promotion-link-text { flex: 1; min-width: 0; overflow-wrap: anywhere; }
.promotion-link-text strong { color: #2d4e40; font-size: 14px; }
.promotion-link-text p { margin: 6px 0; color: #657580; font-size: 12px; line-height: 1.6; }
.promotion-link-text > span { color: var(--primary-text); font: 11px ui-monospace, SFMono-Regular, Consolas, monospace; }
.promotion-arrow { color: var(--primary-text); font-size: 18px; }
.promotion-footer { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 20px; }
.promotion-footer > span { color: #7b8b96; font-size: 11px; }
.promotion-footer button { padding: 9px 20px; border: 1px solid var(--primary); border-radius: 6px; color: var(--primary-contrast); background: var(--primary); font-size: 13px; cursor: pointer; white-space: nowrap; }
.about-author-button:focus-visible, .promotion-links a:focus-visible, .promotion-close:focus-visible, .promotion-footer button:focus-visible { outline: 2px solid var(--focus-color); outline-offset: 3px; }
@media (max-width: 480px) {
  .promotion-title { font-size: 20px; }
  .promotion-links a { gap: 10px; padding: 12px; }
  .promotion-number { width: 28px; height: 28px; }
  .promotion-footer { gap: 10px; }
}
</style>

<style>
.author-promotion-dialog.el-dialog { max-height: calc(100dvh - 32px); overflow-y: auto; padding: 24px; border-radius: 12px; }
.author-promotion-dialog .el-dialog__header, .author-promotion-dialog .el-dialog__body, .author-promotion-dialog .el-dialog__footer { padding: 0; margin: 0; }
@media (max-width: 480px) { .author-promotion-dialog.el-dialog { padding: 20px; } }
</style>
