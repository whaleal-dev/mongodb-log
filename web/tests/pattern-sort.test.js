import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import PatternTable from '../src/components/PatternTable.vue'

const row = (namespace, count, averageDurationMillis, cpuAvailable = true) => ({ namespace, count, averageDurationMillis,
  operation: 'find', pattern: '{}', planSummary: 'IXSCAN', totalDurationMillis: 100, minDurationMillis: 1,
  maxDurationMillis: 100, totalCpuNanos: 1000, cpuAvailable })
const order = wrapper => wrapper.findAll('tbody tr').map(tr => tr.find('td').text())
const click = (wrapper, label) => wrapper.get(`button[aria-label="按${label}排序"]`).trigger('click')

describe('pattern table sorting', () => {
  it('defaults to count descending and toggles numeric sorting without mutating the input', async () => {
    const patterns = [row('db.b', 3, 100), row('db.a', 9, 2), row('db.c', 5, 10)]
    const wrapper = mount(PatternTable, { props: { patterns } })
    expect(order(wrapper)).toEqual(['db.a', 'db.c', 'db.b'])
    await click(wrapper, '平均耗时')
    expect(order(wrapper)).toEqual(['db.a', 'db.c', 'db.b'])
    expect(wrapper.get('th[aria-sort="ascending"]').text()).toContain('平均耗时')
    await click(wrapper, '平均耗时')
    expect(order(wrapper)).toEqual(['db.b', 'db.c', 'db.a'])
    expect(wrapper.get('th[aria-sort="descending"]').text()).toContain('平均耗时')
    expect(patterns.map(p => p.namespace)).toEqual(['db.b', 'db.a', 'db.c'])
    wrapper.unmount()
  })

  it('sorts text, keeps missing numeric values last in either direction and retains the Top 50 selection', async () => {
    const patterns = [row('db.10', 60, 2), row('db.2', 59, 1, false), ...Array.from({ length: 49 }, (_, i) => row(`other.${i}`, 58-i, 10))]
    const wrapper = mount(PatternTable, { props: { patterns } })
    await click(wrapper, '集合')
    expect(order(wrapper).slice(0, 2)).toEqual(['db.2', 'db.10'])
    await click(wrapper, 'CPU 耗时')
    expect(order(wrapper).at(-1)).toBe('db.2')
    await click(wrapper, 'CPU 耗时')
    expect(order(wrapper).at(-1)).toBe('db.2')
    expect(order(wrapper)).toHaveLength(50)
    expect(order(wrapper)).not.toContain('other.48')
    expect(wrapper.find('button[aria-label="按最慢语句排序"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
