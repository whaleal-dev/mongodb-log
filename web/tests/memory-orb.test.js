import { flushPromises, mount } from '@vue/test-utils'
import { ElMessageBox } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MemoryOrb from '../src/components/MemoryOrb.vue'

const reply = (body, ok = true) => ({ ok, status: ok ? 200 : 409, text: async () => body == null ? '' : JSON.stringify(body) })

describe('MemoryOrb', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('loads immediately and refreshes memory usage every five seconds', async () => {
    const fetch = vi.fn(() => Promise.resolve(reply({ usedBytes: 512, maxBytes: 2048, usagePercent: 25 })))
    vi.stubGlobal('fetch', fetch)
    const wrapper = mount(MemoryOrb)
    await flushPromises()

    expect(wrapper.text()).toContain('25%')
    expect(fetch).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(5000)
    await flushPromises()
    expect(fetch).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('clears all data only after the destructive confirmation', async () => {
    const fetch = vi.fn((url, options) => Promise.resolve(
      options?.method === 'DELETE' ? reply(null) : reply({ usedBytes: 512, maxBytes: 2048, usagePercent: 25 }),
    ))
    vi.stubGlobal('fetch', fetch)
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    const wrapper = mount(MemoryOrb)
    await flushPromises()

    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(ElMessageBox.confirm).toHaveBeenCalledOnce()
    expect(fetch).toHaveBeenCalledWith('/api/system/data', { method: 'DELETE' })
    expect(wrapper.emitted('cleared')).toHaveLength(1)
    wrapper.unmount()
  })
})
