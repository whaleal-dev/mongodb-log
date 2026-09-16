import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import DurationHistogram from '../src/components/DurationHistogram.vue'

const setOption = vi.fn()

vi.mock('echarts', () => ({
  init: () => ({ setOption, on: vi.fn(), resize: vi.fn(), dispose: vi.fn() }),
}))

const keys = [
  'lt_100ms',
  '100ms_500ms',
  '500ms_1s',
  '1s_3s',
  '3s_10s',
  '10s_30s',
  '30s_60s',
  'gte_60s',
]

describe('DurationHistogram', () => {
  beforeEach(() => {
    setOption.mockClear()
    vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
  })

  it('renders the eight buckets in API order and initializes the chart', async () => {
    const buckets = keys.map((key, index) => ({
      key,
      label: key,
      count: index + 1,
      percentage: 1,
      totalDurationMillis: 100,
      averageDurationMillis: 100,
      maxDurationMillis: 100,
    }))

    const wrapper = mount(DurationHistogram, { props: { buckets } })
    await nextTick()

    expect(wrapper.findAll('[data-bucket-key]')).toHaveLength(0)
    expect(setOption).toHaveBeenCalledOnce()
    await wrapper.findAll('button').find((button) => button.text() === '数据表').trigger('click')
    expect(wrapper.findAll('[data-bucket-key]').map((row) => row.attributes('data-bucket-key'))).toEqual(keys)
  })

  it('explains when no slow queries are available', () => {
    const wrapper = mount(DurationHistogram, { props: { buckets: [] } })

    expect(wrapper.text()).toContain('暂无慢查询耗时数据')
    expect(setOption).not.toHaveBeenCalled()
  })

  it('preserves exact millisecond values in the operational data table', async () => {
    const wrapper = mount(DurationHistogram, { props: { buckets: [{ key: '1s_3s', label: '1–3s', count: 2,
      percentage: 100, totalDurationMillis: 4999, averageDurationMillis: 2499.5, maxDurationMillis: 2999 }] } })
    await wrapper.findAll('button').find((button) => button.text() === '数据表').trigger('click')
    expect(wrapper.text()).toContain('2499.500 ms')
    expect(wrapper.text()).toContain('2999.000 ms')
  })
})
