import { mount } from '@vue/test-utils'
import { readFileSync } from 'node:fs'
import ElementPlus, { ElButton } from 'element-plus'
import { afterEach, describe, expect, it } from 'vitest'
import UploadPanel from '../src/components/UploadPanel.vue'
import FtdcUploadPanel from '../src/components/ftdc/FtdcUploadPanel.vue'
import appSource from '../src/App.vue?raw'
import { createThemeTokens } from '../src/ui/colors.js'

let wrapper
let style
const buttonCss = readFileSync('node_modules/element-plus/theme-chalk/el-button.css', 'utf8')
afterEach(() => {
  wrapper?.unmount()
  style?.remove()
  document.documentElement.removeAttribute('style')
})

function colorContrast(first, second) {
  const luminance = value => {
    const channels = value.startsWith('#') ? [1, 3, 5].map(index => parseInt(value.slice(index, index + 2), 16))
      : value.match(/[\d.]+/g).slice(0, 3).map(Number)
    return channels.map(channel => channel / 255).map(channel => channel <= .04045 ? channel / 12.92 : ((channel + .055) / 1.055) ** 2.4)
      .reduce((sum, channel, index) => sum + channel * [.2126, .7152, .0722][index], 0)
  }
  const values = [luminance(first), luminance(second)].sort((a, b) => a - b)
  return (values[1] + .05) / (values[0] + .05)
}

function computedValue(element, property) {
  const computed = getComputedStyle(element)
  const root = getComputedStyle(document.documentElement)
  const resolve = value => value.replace(/var\((--[\w-]+)\)/g, (_, name) => resolve(computed.getPropertyValue(name) || root.getPropertyValue(name)))
  return resolve(computed.getPropertyValue(property))
}

function button(theme, props, state = '') {
  style = document.createElement('style')
  // JSDOM 没有指针和键盘状态，将真实状态选择器映射到等特异性的测试类。
  style.textContent = (buttonCss + appSource.match(/<style>([\s\S]*?)<\/style>/)[1])
    .replace(/:hover\b/g, '.test-hover').replace(/:active\b/g, '.test-active').replace(/:focus-visible\b/g, '.test-focus')
  document.head.append(style)
  for (const [name, value] of Object.entries({ '--el-color-white': '#FFFFFF', ...createThemeTokens(theme) })) {
    document.documentElement.style.setProperty(name, value)
  }
  wrapper = mount(ElButton, { props: { type: 'primary', ...props }, slots: { default: '主题操作' }, attachTo: document.body })
  if (state) wrapper.element.classList.add(`test-${state}`)
  return wrapper.element
}

describe('upload action themes', () => {
  it.each([
    { component: UploadPanel, label: '开始分析' },
    { component: FtdcUploadPanel, label: '开始建立索引' },
  ])('uses the primary theme for $label', ({ component, label }) => {
    wrapper = mount(component, { global: { plugins: [ElementPlus] } })
    const action = wrapper.findAllComponents(ElButton).find(button => button.text() === label)

    expect(action.props('type')).toBe('primary')
  })

  it.each(['#FFFFFF', '#000000', '#FF0000'])('主按钮及悬停、按下、禁用文字在 %s 下可辨识', theme => {
    for (const state of ['', 'hover', 'active', 'disabled']) {
      const element = button(theme, { disabled: state === 'disabled' }, state === 'disabled' ? '' : state)
      expect(colorContrast(computedValue(element, 'color'), computedValue(element, 'background-color'))).toBeGreaterThanOrEqual(4.5)
      wrapper.unmount()
      style.remove()
    }
  })

  it.each(['hover', 'active', 'disabled'])('主题链接的 %s 文字在白底上保留对比度', state => {
    const element = button('#FFFFFF', { link: true, disabled: state === 'disabled' }, state === 'disabled' ? '' : state)
    expect(colorContrast(computedValue(element, 'color'), '#FFFFFF')).toBeGreaterThanOrEqual(4.5)
  })

  it('Element Plus 的键盘焦点边框与白底达到 3∶1', () => {
    const element = button('#FFFFFF', {}, 'focus')
    expect(colorContrast(computedValue(element, '--el-button-outline-color'), '#FFFFFF')).toBeGreaterThanOrEqual(3)
  })
})
