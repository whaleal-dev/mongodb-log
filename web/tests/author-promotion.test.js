import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AuthorPromotionDialog from '../src/components/AuthorPromotionDialog.vue'
import App from '../src/App.vue'

const storageKey = 'mongodb-log:author-promotion:v2'
const legacyKey = 'mongodb-log:author-promotion:last-shown:v1'
const oneDay = 24 * 60 * 60 * 1000
const now = Date.parse('2026-10-22T06:00:00Z')
let wrapper

async function open() {
  wrapper = mount(AuthorPromotionDialog, { attachTo: document.body,
    global: { stubs: { teleport: true } } })
  await flushPromises()
}

function shown() {
  const dialog = wrapper.find('.author-promotion-dialog')
  return dialog.exists() && dialog.isVisible()
}

function save(firstUsedAt, viewed = false) {
  localStorage.setItem(storageKey, JSON.stringify({ firstUsedAt, viewed }))
}

function saved() {
  return JSON.parse(localStorage.getItem(storageKey))
}

async function close() {
  await wrapper.get('[aria-label="关闭作者推广"]').trigger('click')
  await vi.advanceTimersByTimeAsync(500)
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(now)
  localStorage.clear()
})
afterEach(() => {
  wrapper?.unmount()
  vi.restoreAllMocks()
  vi.useRealTimers()
  localStorage.clear()
  document.body.innerHTML = ''
})

describe('author promotion', () => {
  it('records first use without showing a popup immediately', async () => {
    await open()
    expect(shown()).toBe(false)
    expect(wrapper.get('button').text()).toBe('关于作者')
    expect(saved()).toEqual({ firstUsedAt: now, viewed: false })
  })

  it('opens all supplied links manually without making network requests', async () => {
    const fetch = vi.spyOn(window, 'fetch')
    await open()
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(shown()).toBe(true)
    expect(wrapper.text()).toContain('作者推广')
    expect(wrapper.findAll('a').map(link => link.attributes('href'))).toEqual([
      'https://docs.whaleal.com/resume-site/', 'https://www.whaleal.io/', 'https://github.com/whaleal-dev',
    ])
    for (const link of wrapper.findAll('a')) {
      expect(link.attributes('target')).toBe('_blank')
      expect(link.attributes('rel')).toBe('noopener noreferrer')
      expect(link.attributes('referrerpolicy')).toBe('no-referrer')
    }
    expect(saved()).toEqual({ firstUsedAt: now, viewed: true })
    expect(fetch).not.toHaveBeenCalled()
    expect(wrapper.findAll('img, iframe').length).toBe(0)
  })

  it('waits until exactly 24 hours after first use without reloading', async () => {
    save(now - oneDay + 1)
    await open()
    expect(shown()).toBe(false)
    await vi.advanceTimersByTimeAsync(1)
    await flushPromises()
    expect(shown()).toBe(true)
    expect(saved()).toEqual({ firstUsedAt: now - oneDay + 1, viewed: true })
  })

  it('shows immediately when a returning visitor is already due', async () => {
    save(now - oneDay)
    await open()
    expect(shown()).toBe(true)
    expect(saved().viewed).toBe(true)
  })

  it('places the about button in the header for both workspaces and does not reopen on switching', async () => {
    vi.spyOn(window, 'fetch').mockResolvedValue({ ok: true, text: async () => '[]' })
    wrapper = mount(App, { attachTo: document.body, global: { plugins: [ElementPlus], stubs: {
      teleport: true, MemoryOrb: true, FtdcWorkspace: true, UploadPanel: true, TaskList: true,
    } } })
    await flushPromises()
    expect(shown()).toBe(false)
    const about = wrapper.get('.app-header .about-author-button')
    expect(about.text()).toBe('关于作者')
    await about.trigger('click')
    await flushPromises()
    expect(shown()).toBe(true)
    await close()
    await wrapper.findAll('.workspace-switch button')[1].trigger('click')
    expect(shown()).toBe(false)
    await wrapper.findAll('.workspace-switch button')[0].trigger('click')
    expect(shown()).toBe(false)
    await about.trigger('click')
    await flushPromises()
    expect(shown()).toBe(true)
    expect(saved().viewed).toBe(true)
  })

  it('never automatically reopens after the one-time reminder, including after remounting', async () => {
    save(now - oneDay)
    await open()
    expect(shown()).toBe(true)
    await close()
    wrapper.unmount()
    await open()
    expect(shown()).toBe(false)
    await vi.advanceTimersByTimeAsync(30 * oneDay)
    window.dispatchEvent(new Event('focus'))
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(shown()).toBe(false)
  })

  it('cancels the automatic reminder after a manual visit but still allows manual reopening', async () => {
    await open()
    await wrapper.get('button').trigger('click')
    await flushPromises()
    await close()
    await vi.advanceTimersByTimeAsync(2 * oneDay)
    expect(shown()).toBe(false)
    await wrapper.get('.about-author-button').trigger('click')
    await flushPromises()
    expect(shown()).toBe(true)
  })

  it('preserves an earlier version display as already viewed', async () => {
    localStorage.setItem(legacyKey, String(now - 10 * oneDay))
    await open()
    expect(shown()).toBe(false)
    expect(saved()).toEqual({ firstUsedAt: now - 10 * oneDay, viewed: true })
    await vi.advanceTimersByTimeAsync(30 * oneDay)
    expect(shown()).toBe(false)
  })

  it.each(['not-json', 'null', '{}', JSON.stringify({ firstUsedAt: -1, viewed: false }),
    JSON.stringify({ firstUsedAt: now + oneDay, viewed: false }),
    JSON.stringify({ firstUsedAt: now, viewed: 'false' })])(
    'resets invalid state without immediately showing (%s)', async value => {
      localStorage.setItem(storageKey, value)
      await open()
      expect(shown()).toBe(false)
      expect(saved()).toEqual({ firstUsedAt: now, viewed: false })
    })

  it('continues working when storage is unavailable and does not reopen on every focus', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
    await open()
    expect(shown()).toBe(false)
    await vi.advanceTimersByTimeAsync(oneDay)
    expect(shown()).toBe(true)
    await close()
    window.dispatchEvent(new Event('focus'))
    await vi.advanceTimersByTimeAsync(30 * oneDay)
    await flushPromises()
    expect(shown()).toBe(false)
  })

  it('waits for a hidden page to become visible before consuming its reminder', async () => {
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
    save(now - oneDay)
    await open()
    expect(shown()).toBe(false)
    expect(saved().viewed).toBe(false)
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(shown()).toBe(true)
    expect(saved().viewed).toBe(true)
  })

  it('respects another tab that has already shown the dialog before its own timer fires', async () => {
    await open()
    save(now, true)
    await vi.advanceTimersByTimeAsync(oneDay)
    expect(shown()).toBe(false)
  })

  it('checks elapsed time on focus and removes the reminder on unmount', async () => {
    await open()
    vi.setSystemTime(now + oneDay)
    window.dispatchEvent(new Event('focus'))
    await flushPromises()
    expect(shown()).toBe(true)
    wrapper.unmount()
    const stored = localStorage.getItem(storageKey)
    vi.setSystemTime(now + 2 * oneDay)
    window.dispatchEvent(new Event('focus'))
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(oneDay)
    expect(localStorage.getItem(storageKey)).toBe(stored)
  })
})
