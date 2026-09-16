import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../src/App.vue'
import FtdcMetricGroupChart from '../src/components/ftdc/FtdcMetricGroupChart.vue'
import FtdcWorkspace from '../src/components/ftdc/FtdcWorkspace.vue'

const reply = body => ({ ok: true, text: async () => JSON.stringify(body) })

afterEach(() => {
  localStorage.clear()
  vi.unstubAllGlobals()
})

describe('FTDC workspace', () => {
  it('restores the last selected workspace when the app is opened again', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(reply([]))))
    let wrapper = mount(App, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('日志任务')
    await wrapper.findAll('.workspace-switch button').find(button => button.text() === 'MongoDB Metric').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Metric 任务')
    expect(wrapper.text()).toContain('选择 Metric 文件')
    expect(localStorage.getItem('mongodb-log:workspace-mode:v1')).toBe('ftdc')

    wrapper.unmount()
    wrapper = mount(App, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('Metric 任务')

    await wrapper.findAll('.workspace-switch button').find(button => button.text() === 'MongoDB Log').trigger('click')
    expect(wrapper.text()).toContain('选择日志文件')
    expect(localStorage.getItem('mongodb-log:workspace-mode:v1')).toBe('logs')

    wrapper.unmount()
    wrapper = mount(App, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('日志任务')
    wrapper.unmount()
  })

  it('uses the original vertical dashboard with grouped and split metric charts', async () => {
    const task = { id: 'task', name: 'FTDC', status: 'COMPLETED', files: [], totalBytes: 1, blockCount: 1, metricCount: 1, sampleCount: 2 }
    vi.stubGlobal('fetch', vi.fn(url => {
      if (url === '/api/ftdc-tasks') return Promise.resolve(reply([task]))
      if (url.endsWith('/groups')) return Promise.resolve(reply([{ groupId: 'g', name: 'server', metricCount: 2 }]))
      if (url.includes('/groups/g/series')) return Promise.resolve(reply({ groupId: 'g', name: 'server', view: 'raw', series: [
        { metricId: 'a', path: 'server/a', timestamps: [1000, 2000, 10000], values: [0, 0, 0] },
        { metricId: 'b', path: 'server/b', timestamps: [1000, 2000, 10000], values: [30, 40, 50] },
      ] }))
      throw new Error(`unexpected ${url}`)
    }))
    const wrapper = mount(FtdcWorkspace, { global: { plugins: [ElementPlus], stubs: { AnalysisChart: { template: '<div class="chart-stub" />' } } } })
    await flushPromises()
    await wrapper.findAll('.ftdc-task-list button').find(button => button.text() === '查看指标').trigger('click')
    await flushPromises()
    wrapper.vm.selectedGroupIds = ['g']
    await wrapper.vm.querySelectedGroups()
    await flushPromises()

    expect(wrapper.find('.ftdc-dashboard').exists()).toBe(true)
    expect(wrapper.find('.query-card').exists()).toBe(true)
    expect(wrapper.find('.charts-section .chart-grid .group-chart-card').exists()).toBe(true)
    expect(wrapper.find('.chart-body > .metric-info-list').exists()).toBe(true)
    expect(wrapper.find('.chart-body > .chart-render-area .chart-stub').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('单指标')
    expect(wrapper.text()).not.toContain('下载 CSV.gz')

    await wrapper.findAll('.group-actions button').find(button => button.text() === '分开展示').trigger('click')
    expect(wrapper.findAll('.split-chart-item')).toHaveLength(2)
    await wrapper.findAll('.group-actions button').find(button => button.text().startsWith('隐藏全零')).trigger('click')
    expect(wrapper.findAll('.split-chart-item')).toHaveLength(1)
    expect(wrapper.text()).not.toContain('server/a')
    await wrapper.findAll('.group-actions button').find(button => button.text() === '折叠').trigger('click')
    expect(wrapper.find('.chart-body').exists()).toBe(false)
    expect(wrapper.find('.group-chart-card').exists()).toBe(true)
    wrapper.unmount()
  })

  it('selects the available core groups with one click and queries multiple groups', async () => {
    const task = { id: 'task', name: 'FTDC', status: 'COMPLETED', files: [], totalBytes: 1, blockCount: 1, metricCount: 3, sampleCount: 2 }
    const requestedGroups = []
    vi.stubGlobal('fetch', vi.fn(url => {
      if (url === '/api/ftdc-tasks') return Promise.resolve(reply([task]))
      if (url.endsWith('/groups')) return Promise.resolve(reply([
        { groupId: 'connections', name: 'connections', metricCount: 1 },
        { groupId: 'network', name: 'network', metricCount: 1 },
        { groupId: 'other', name: 'other', metricCount: 1 },
      ]))
      const match = url.match(/\/groups\/([^/]+)\/series/)
      if (match) {
        requestedGroups.push(match[1])
        return Promise.resolve(reply({ groupId: match[1], name: match[1], view: 'raw', series: [
          { metricId: `${match[1]}-zero`, path: `${match[1]}/zero`, timestamps: [1000, 2000], values: [0, 0] },
          { metricId: `${match[1]}-value`, path: `${match[1]}/value`, timestamps: [1000, 2000], values: [1, 2] },
        ] }))
      }
      throw new Error(`unexpected ${url}`)
    }))
    const wrapper = mount(FtdcWorkspace, { global: { plugins: [ElementPlus], stubs: { AnalysisChart: { template: '<div class="chart-stub" />' } } } })
    await flushPromises()
    await wrapper.findAll('.ftdc-task-list button').find(button => button.text() === '查看指标').trigger('click')
    await flushPromises()

    await wrapper.find('.core-groups-button').trigger('click')
    expect(wrapper.vm.selectedGroupIds).toEqual(['connections', 'network'])
    await wrapper.find('.query-groups-button').trigger('click')
    await flushPromises()

    expect(requestedGroups).toEqual(['connections', 'network'])
    expect(wrapper.findAll('.group-header h3').map(item => item.text())).toEqual(['connections', 'network'])
    expect(wrapper.findAll('.chart-body')).toHaveLength(2)
    expect(wrapper.findAll('.metric-info-item')).toHaveLength(4)

    await wrapper.find('.collapse-all-button').trigger('click')
    expect(wrapper.findAll('.chart-body')).toHaveLength(0)
    await wrapper.find('.expand-all-button').trigger('click')
    expect(wrapper.findAll('.chart-body')).toHaveLength(2)

    await wrapper.find('.hide-zero-all-button').trigger('click')
    expect(wrapper.findAll('.metric-info-item')).toHaveLength(2)
    expect(wrapper.text()).not.toContain('connections/zero')
    expect(wrapper.text()).not.toContain('network/zero')
    await wrapper.find('.show-zero-all-button').trigger('click')
    expect(wrapper.findAll('.metric-info-item')).toHaveLength(4)
    wrapper.unmount()
  })

  it('renders each group as soon as it returns while later groups keep loading', async () => {
    const task = { id: 'task', name: 'FTDC', status: 'COMPLETED', files: [], totalBytes: 1, blockCount: 1, metricCount: 2, sampleCount: 2 }
    let resolveSecond
    vi.stubGlobal('fetch', vi.fn(url => {
      if (url === '/api/ftdc-tasks') return Promise.resolve(reply([task]))
      if (url.endsWith('/groups')) return Promise.resolve(reply([
        { groupId: 'first', name: 'first', metricCount: 1 },
        { groupId: 'second', name: 'second', metricCount: 1 },
      ]))
      if (url.includes('/groups/first/series')) return Promise.resolve(reply({ groupId: 'first', name: 'first', series: [{ values: [1] }] }))
      if (url.includes('/groups/second/series')) return new Promise(resolve => { resolveSecond = () => resolve(reply({ groupId: 'second', name: 'second', series: [{ values: [2] }] })) })
      throw new Error(`unexpected ${url}`)
    }))
    const groupChart = { props: ['group'], template: '<div class="group-result">{{ group.name }}</div>' }
    const wrapper = mount(FtdcWorkspace, { global: { plugins: [ElementPlus], stubs: { FtdcMetricGroupChart: groupChart } } })
    await flushPromises()
    await wrapper.findAll('.ftdc-task-list button').find(button => button.text() === '查看指标').trigger('click')
    await flushPromises()
    wrapper.vm.selectedGroupIds = ['first', 'second']

    const query = wrapper.vm.querySelectedGroups()
    await flushPromises()
    expect(wrapper.findAll('.group-result').map(item => item.text())).toEqual(['first'])
    expect(wrapper.text()).toContain('正在继续读取其余指标组')

    resolveSecond()
    await query
    await flushPromises()
    expect(wrapper.findAll('.group-result').map(item => item.text())).toEqual(['first', 'second'])
    wrapper.unmount()
  })

  it('shows a failed group in place and continues with the remaining groups', async () => {
    const task = { id: 'task', name: 'FTDC', status: 'COMPLETED', files: [], totalBytes: 1, blockCount: 1, metricCount: 3, sampleCount: 2 }
    const requestedGroups = []
    vi.stubGlobal('fetch', vi.fn(url => {
      if (url === '/api/ftdc-tasks') return Promise.resolve(reply([task]))
      if (url.endsWith('/groups')) return Promise.resolve(reply([
        { groupId: 'first', name: 'first', metricCount: 1 },
        { groupId: 'broken', name: 'broken', metricCount: 1 },
        { groupId: 'last', name: 'last', metricCount: 1 },
      ]))
      const match = url.match(/\/groups\/([^/]+)\/series/)
      if (match) requestedGroups.push(match[1])
      if (url.includes('/groups/broken/series')) {
        return Promise.resolve({ ok: false, status: 422, text: async () => JSON.stringify({ message: 'FTDC block 损坏' }) })
      }
      if (match) return Promise.resolve(reply({ groupId: match[1], name: match[1], series: [{ metricId: match[1], path: match[1], timestamps: [1000], values: [1] }] }))
      throw new Error(`unexpected ${url}`)
    }))
    const groupChart = { props: ['group'], template: '<div class="group-result">{{ group.name }}</div>' }
    const wrapper = mount(FtdcWorkspace, { global: { plugins: [ElementPlus], stubs: { FtdcMetricGroupChart: groupChart } } })
    await flushPromises()
    await wrapper.findAll('.ftdc-task-list button').find(button => button.text() === '查看指标').trigger('click')
    await flushPromises()
    wrapper.vm.selectedGroupIds = ['first', 'broken', 'last']

    await wrapper.vm.querySelectedGroups()
    await flushPromises()

    expect(requestedGroups).toEqual(['first', 'broken', 'last'])
    expect(wrapper.findAll('.group-result').map(item => item.text())).toEqual(['first', 'last'])
    expect(wrapper.find('.group-error-card').text()).toContain('broken')
    expect(wrapper.find('.group-error-card').text()).toContain('Metric block 损坏')
    expect(wrapper.text()).toContain('1 个指标组加载失败')
    wrapper.unmount()
  })

  it('renders a backend time-gap marker as a dash', () => {
    const chartStub = { name: 'AnalysisChart', props: ['option'], template: '<div class="chart-stub" />' }
    const wrapper = mount(FtdcMetricGroupChart, {
      props: { group: { groupId: 'g', name: 'server', series: [
        { metricId: 'a', path: 'server/a', timestamps: [1000, 3000, 10000], values: [1, null, 3] },
      ] } },
      global: { stubs: { AnalysisChart: chartStub } },
    })

    const option = wrapper.findComponent({ name: 'AnalysisChart' }).props('option')
    expect(option.series[0].data).toEqual([[1000, 1], [3000, null], [10000, 3]])
    expect(option.series[0].connectNulls).toBe(false)
    expect(option.tooltip.valueFormatter([3000, null])).toBe('-')
    wrapper.unmount()
  })

  it('does not infer false gaps from irregular extrema timestamps after downsampling', () => {
    const chartStub = { name: 'AnalysisChart', props: ['option'], template: '<div class="chart-stub" />' }
    const wrapper = mount(FtdcMetricGroupChart, {
      props: { group: { groupId: 'g', name: 'server', series: [
        { metricId: 'a', path: 'server/a', timestamps: [1000, 1950, 3000, 12000, 13020, 30000], values: [1, 2, 3, 4, 5, 6] },
      ] } },
      global: { stubs: { AnalysisChart: chartStub } },
    })

    const points = wrapper.findComponent({ name: 'AnalysisChart' }).props('option').series[0].data
    expect(points).toEqual([[1000, 1], [1950, 2], [3000, 3], [12000, 4], [13020, 5], [30000, 6]])
    wrapper.unmount()
  })
})
