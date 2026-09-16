import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MetricPanel from '../src/components/MetricPanel.vue'

beforeEach(() => localStorage.clear())
describe('metric panel layout', () => {
  it('rejects a saved height that would make the scatter plot unusable', () => {
    localStorage.setItem('mongodb-log:metric-layout:v1', JSON.stringify({ scatter: { width: 100, height: 220 } }))
    const wrapper = mount(MetricPanel, { props: { layoutKey: 'scatter', minHeight: 420 } })
    expect(wrapper.element.style.height).toBe('')
    wrapper.unmount()
  })
  it('resizes, persists and restores each metric independently', async () => {
    const parent = document.createElement('div')
    document.body.appendChild(parent)
    const wrapper = mount(MetricPanel, { props: { layoutKey: 'duration' }, attachTo: parent, slots: { default: '统计' } })
    vi.spyOn(wrapper.element.parentElement, 'getBoundingClientRect').mockReturnValue({ width: 1000 })
    vi.spyOn(wrapper.element, 'getBoundingClientRect').mockReturnValue({ width: 500, height: 300 })
    const handle = wrapper.get('[aria-label="调整指标窗口大小"]')
    handle.element.dispatchEvent(new MouseEvent('pointerdown', { clientX: 500, clientY: 300, button: 0, bubbles: true }))
    handle.element.dispatchEvent(new MouseEvent('pointermove', { clientX: 750, clientY: 450, bubbles: true }))
    handle.element.dispatchEvent(new MouseEvent('pointerup', { bubbles: true }))
    await wrapper.vm.$nextTick()
    expect(wrapper.element.style.width).toBe('75%')
    expect(wrapper.element.style.height).toBe('450px')
    wrapper.unmount()
    const restored = mount(MetricPanel, { props: { layoutKey: 'duration' } })
    const other = mount(MetricPanel, { props: { layoutKey: 'operations' } })
    expect(restored.element.style.width).toBe('75%')
    expect(other.element.style.width).toBe('')
    window.dispatchEvent(new Event('mongodb-log:reset-layout'))
    await restored.vm.$nextTick()
    expect(restored.element.style.width).toBe('')
    restored.unmount(); other.unmount(); parent.remove()
  })

  it('ignores corrupt saved layouts and remains usable when local storage is blocked', async () => {
    localStorage.setItem('mongodb-log:metric-layout:v1', '{broken')
    const get = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
    const wrapper = mount(MetricPanel, { props: { layoutKey: 'duration' } })
    expect(wrapper.get('[aria-label="调整指标窗口大小"]').exists()).toBe(true)
    expect(wrapper.element.style.width).toBe('')
    wrapper.unmount(); get.mockRestore()
  })
})
