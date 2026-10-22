import { flushPromises, mount } from '@vue/test-utils'
import { ElButton, ElDialog } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ThemeSettings from '../src/components/ThemeSettings.vue'
import { initializeColorTheme, THEME_STORAGE_KEY, useColorTheme } from '../src/ui/colorTheme.js'

let wrapper, stop
beforeEach(() => { localStorage.clear(); stop = initializeColorTheme() })
afterEach(() => { wrapper?.unmount(); stop(); vi.restoreAllMocks(); document.body.innerHTML = '' })

async function open() {
  wrapper = mount(ThemeSettings, { attachTo: document.body, global: { components: { ElButton, ElDialog }, stubs: { teleport: true } } })
  await wrapper.get('.theme-settings-button').trigger('click')
  await flushPromises()
}

describe('theme settings', () => {
  it('applies presets immediately and closing does not undo the saved preview', async () => {
    await open()
    const button = wrapper.get('[aria-label="经典蓝"]')
    await button.trigger('click')
    expect(button.attributes('aria-pressed')).toBe('true')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('#0071E3')
    await wrapper.findAll('button').find(b => b.text() === '完成').trigger('click')
    expect(useColorTheme().color.value).toBe('#0071E3')
  })

  it('keeps the applied theme while HEX is incomplete, validates on blur or Enter, and accepts corrected input', async () => {
    await open()
    const input = wrapper.get('#theme-hex')
    await input.setValue('#FF')
    expect(wrapper.find('#theme-format-error').exists()).toBe(false)
    expect(useColorTheme().color.value).toBe('#347961')
    await input.trigger('blur')
    expect(input.attributes('aria-invalid')).toBe('true')
    expect(wrapper.get('#theme-format-error').text()).toContain('#RRGGBB')
    await input.setValue('bad')
    await input.trigger('keydown', { key: 'Enter' })
    expect(wrapper.find('#theme-format-error').exists()).toBe(true)
    await input.setValue('#aabbcc')
    expect(wrapper.find('#theme-format-error').exists()).toBe(false)
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('#AABBCC')
  })

  it('shows save failures with a working retry and preserves the preview', async () => {
    await open()
    const write = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota') })
    await wrapper.get('[aria-label="雾岚紫"]').trigger('click')
    expect(useColorTheme().color.value).toBe('#7659B8')
    expect(wrapper.get('[role="alert"]').text()).toContain('quota')
    write.mockRestore()
    await wrapper.findAll('button').find(b => b.text() === '重试保存').trigger('click')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('#7659B8')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('protects an unfinished HEX draft from cross-tab updates and restores the default', async () => {
    await open()
    const input = wrapper.get('#theme-hex')
    await input.setValue('#12')
    window.dispatchEvent(new StorageEvent('storage', { key: THEME_STORAGE_KEY, newValue: '#0071E3', storageArea: localStorage }))
    await flushPromises()
    expect(input.element.value).toBe('#12')
    expect(useColorTheme().color.value).toBe('#0071E3')
    await wrapper.findAll('button').find(b => b.text() === '恢复默认').trigger('click')
    expect(input.element.value).toBe('#347961')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('#347961')
  })
})
