import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import PatternTable from '../src/components/PatternTable.vue'

const row = (namespace, count, operation = 'find', pattern = '{}', planSummary = 'IXSCAN') => ({
  namespace, count, operation, pattern, planSummary, totalDurationMillis: count * 100,
  averageDurationMillis: 100, minDurationMillis: 100, maxDurationMillis: 100, cpuAvailable: false,
})
const order = wrapper => wrapper.findAll('tbody tr').map(tr => tr.find('td').text())

describe('pattern table filtering', () => {
  it('combines trimmed case-insensitive text searches with an exact operation filter and resets all filters', async () => {
    const patterns = [
      row('shop.orders', 9, 'find', '{"customerId":"?"}', 'COLLSCAN, IXSCAN { customerId: 1 }'),
      row('shop.orders', 8, 'update', '{"customerId":"?"}', 'COLLSCAN'),
      row('shop.customers', 7, 'find', '{"email":"?"}', 'IXSCAN { email: 1 }'),
    ]
    patterns[0].slowestQuery = { queryId: 'orders-sample', rawLine: 'pattern sample' }
    const wrapper = mount(PatternTable, { props: { patterns } })
    expect(wrapper.get('[role="status"]').text()).toContain('显示 3 / 3 组')
    await wrapper.get('[aria-label="搜索集合或查询模式"]').setValue('  CUSTOMERID  ')
    expect(order(wrapper)).toEqual(['shop.orders', 'shop.orders'])
    await wrapper.get('[aria-label="筛选操作类型"]').setValue('find')
    await wrapper.get('[aria-label="搜索执行计划"]').setValue('  ixscan  ')
    expect(order(wrapper)).toEqual(['shop.orders'])
    expect(wrapper.get('[role="status"]').text()).toContain('显示 1 / 3 组')
    await wrapper.get('.pattern-action button').trigger('click')
    expect(wrapper.emitted('select')).toEqual([[patterns[0].slowestQuery]])
    expect(patterns.map(item => item.count)).toEqual([9, 8, 7])
    await wrapper.get('[aria-label="重置查询模式筛选"]').trigger('click')
    expect(order(wrapper)).toEqual(['shop.orders', 'shop.orders', 'shop.customers'])
    expect(wrapper.get('[aria-label="搜索集合或查询模式"]').element.value).toBe('')
    expect(wrapper.get('[aria-label="筛选操作类型"]').element.value).toBe('')
    expect(wrapper.get('[aria-label="搜索执行计划"]').element.value).toBe('')
    wrapper.unmount()
  })

  it('filters only the frequency-selected Top 50, preserves sorting and treats special characters literally', async () => {
    const patterns = [row('db.[orders]', 100, 'find', '{"$or":"?"}'),
      ...Array.from({ length: 49 }, (_, i) => row(`db.other${i}`, 99 - i, 'update')),
      row('db.[outside]', 1, 'delete')]
    const wrapper = mount(PatternTable, { props: { patterns } })
    expect(wrapper.get('[aria-label="筛选操作类型"]').text()).not.toContain('delete')
    await wrapper.get('[aria-label="按集合排序"]').trigger('click')
    await wrapper.get('[aria-label="搜索集合或查询模式"]').setValue('[')
    expect(order(wrapper)).toEqual(['db.[orders]'])
    await wrapper.get('[aria-label="搜索集合或查询模式"]').setValue('$or')
    expect(order(wrapper)).toEqual(['db.[orders]'])
    await wrapper.get('[aria-label="搜索集合或查询模式"]').setValue('outside')
    expect(order(wrapper)).toEqual([])
    expect(wrapper.text()).toContain('已入选 Top 50 中没有匹配的查询模式')
    expect(wrapper.get('[role="status"]').text()).toContain('显示 0 / 50 组')
    await wrapper.get('[aria-label="重置查询模式筛选"]').trigger('click')
    expect(order(wrapper)).toHaveLength(50)
    expect(wrapper.get('th[aria-sort="ascending"]').text()).toContain('集合')
    expect(order(wrapper)).not.toContain('db.[outside]')
    wrapper.unmount()
  })

  it('does not infer missing plans and clears stale filters when the result changes', async () => {
    const wrapper = mount(PatternTable, { props: { patterns: [row('db.old', 3, 'find', '{}', null)] } })
    await wrapper.get('[aria-label="搜索执行计划"]').setValue('IXSCAN')
    expect(order(wrapper)).toEqual([])
    await wrapper.setProps({ patterns: [row('db.new', 2, 'insert')] })
    expect(order(wrapper)).toEqual(['db.new'])
    expect(wrapper.get('[aria-label="搜索执行计划"]').element.value).toBe('')
    expect(wrapper.get('[aria-label="筛选操作类型"]').text()).toContain('insert')
    expect(wrapper.get('[aria-label="筛选操作类型"]').text()).not.toContain('find')
    wrapper.unmount()
  })

  it('preserves the historical and empty result explanations without offering unusable filters', () => {
    for (const patterns of [null, []]) {
      const wrapper = mount(PatternTable, { props: { patterns } })
      expect(wrapper.find('[aria-label="搜索集合或查询模式"]').exists()).toBe(false)
      expect(wrapper.text()).toContain(patterns === null
        ? '此历史任务未保存新版模式统计，请重新上传分析' : '暂无可识别的查询模式')
      wrapper.unmount()
    }
  })
})
