import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import UsageGuideDialog from '../src/components/UsageGuideDialog.vue'

describe('UsageGuideDialog', () => {
  it('explains the main workflow and product limits', async () => {
    const wrapper = mount(UsageGuideDialog)

    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    await wrapper.find('.usage-guide-button').trigger('click')

    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('MongoDB Log 使用方法')
    expect(wrapper.text()).toContain('MongoDB Metric 使用方法')
    expect(wrapper.text()).toContain('一次最多选择 20 个文件')
    expect(wrapper.text()).toContain('只监听本机地址')
    expect(wrapper.text()).toContain('不会自动判断根因')
  })
})
