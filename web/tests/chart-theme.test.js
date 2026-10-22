import { mount } from '@vue/test-utils'
import { nextTick, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DurationHistogram from '../src/components/DurationHistogram.vue'
import ConnectionsChart from '../src/components/ConnectionsChart.vue'
import FtdcMetricChart from '../src/components/ftdc/FtdcMetricChart.vue'
import FtdcMetricGroupChart from '../src/components/ftdc/FtdcMetricGroupChart.vue'
import PieChart from '../src/components/PieChart.vue'

const tokens = ref({ '--primary-mark': '#347961' })
const setOption = vi.fn()

vi.mock('../src/ui/colorTheme.js', () => ({ useColorTheme: () => ({ tokens }) }))
vi.mock('echarts', () => ({
  init: () => ({ setOption, on: vi.fn(), resize: vi.fn(), dispose: vi.fn() }),
}))

let wrapper

beforeEach(() => {
  tokens.value = { '--primary-mark': '#347961' }
  setOption.mockClear()
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
})

afterEach(() => {
  wrapper?.unmount()
  vi.unstubAllGlobals()
})

describe('单系列图表主题', () => {
  it.each([
    ['耗时分布', DurationHistogram, {
      buckets: [{ key: 'lt_100ms', label: '<100ms', count: 3, percentage: 100,
        averageDurationMillis: 50, maxDurationMillis: 90 }],
    }, [3]],
    ['平均连接数', ConnectionsChart, {
      samples: [{ timestampEpochMillis: 10800000, averageConnections: 13 },
        { timestampEpochMillis: 0, averageConnections: 8 }],
    }, [[0, 8], [3600000, null], [10800000, 13]]],
    ['FTDC 指标', FtdcMetricChart, {
      series: { timestamps: [1000, 2000, 5000], values: [5, null, 9] },
    }, [[1000, 5], [2000, null], [5000, 9]]],
  ])('%s 实时采用主题标记色并保留原有数据与缺口', async (_, component, props, data) => {
    wrapper = mount(component, { props })
    await nextTick()

    expect(setOption).toHaveBeenCalledOnce()
    const originalOption = setOption.mock.calls[0][0]
    expect(originalOption.color).toEqual(['#347961'])
    expect(originalOption.series[0].data).toEqual(data)

    tokens.value = { '--primary-mark': '#7659B8' }
    await nextTick()

    expect(setOption).toHaveBeenCalledTimes(2)
    const updatedOption = setOption.mock.lastCall[0]
    expect(updatedOption.color).toEqual(['#7659B8'])
    expect(updatedOption.series[0].data).toEqual(data)
    if (updatedOption.series[0].type === 'line') expect(updatedOption.series[0].connectNulls).toBe(false)
  })

  it('产品指标组中的单系列图随主题变化，保留缺测和统计', async () => {
    wrapper = mount(FtdcMetricGroupChart, { props: { group: { name: 'server', series: [
      { metricId: 'a', path: 'server/a', timestamps: [1000, 2000, 5000], values: [5, null, 9], min: 1, max: 10, average: 6 },
    ] } } })
    await nextTick()
    expect(setOption.mock.lastCall[0].series[0].itemStyle.color).toBe('#347961')

    tokens.value = { '--primary-mark': '#7659B8' }
    await nextTick()

    const option = setOption.mock.lastCall[0]
    expect(option.color).toEqual(['#7659B8'])
    expect(option.series[0].itemStyle.color).toBe('#7659B8')
    expect(option.series[0].data).toEqual([[1000, 5], [2000, null], [5000, 9]])
    expect(option.series[0].connectNulls).toBe(false)
    expect(wrapper.text()).toContain('最小值1')
    expect(wrapper.text()).toContain('最大值10')
  })

  it('多系列指标组和分类饼图在主题变更后保留各自配色', async () => {
    const series = ['a', 'b'].map((metricId, index) => ({ metricId, path: `server/${metricId}`,
      timestamps: [1000, 2000], values: [index, index + 1] }))
    wrapper = mount(FtdcMetricGroupChart, { props: { group: { name: 'server', series } } })
    await nextTick()
    const original = setOption.mock.lastCall[0]
    tokens.value = { '--primary-mark': '#7659B8' }
    await nextTick()
    expect(setOption.mock.lastCall[0].color).toEqual(original.color)
    expect(setOption.mock.lastCall[0].series.map(item => item.itemStyle.color)).toEqual(['#347961', '#3b82a0'])
    wrapper.unmount()

    wrapper = mount(PieChart, { props: { title: '操作类型', values: { find: 3, update: 1 } } })
    await nextTick()
    const palette = setOption.mock.lastCall[0].color
    tokens.value = { '--primary-mark': '#0071E3' }
    await nextTick()
    expect(setOption.mock.lastCall[0].color).toEqual(palette)
  })

  it('多指标组拆分和隐藏全零后仍保留每个指标的分类色', async () => {
    const series = ['a', 'b'].map((metricId, index) => ({ metricId, path: `server/${metricId}`,
      timestamps: [1000, 2000], values: [index, index], allZero: index === 0 }))
    wrapper = mount(FtdcMetricGroupChart, { props: { group: { name: 'server', series } } })
    await nextTick()
    await wrapper.findAll('.group-actions button')[1].trigger('click')
    expect(setOption.mock.calls.slice(-2).map(([option]) => option.series[0].itemStyle.color)).toEqual(['#347961', '#3b82a0'])

    await wrapper.findAll('.group-actions button')[2].trigger('click')
    tokens.value = { '--primary-mark': '#7659B8' }
    await nextTick()
    expect(setOption.mock.lastCall[0].series[0].name).toBe('server/b')
    expect(setOption.mock.lastCall[0].series[0].itemStyle.color).toBe('#3b82a0')
  })
})
