import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../src/App.vue'

const charts = vi.hoisted(() => [])
vi.mock('echarts', () => ({
  init: (element) => {
    const chart = { element, option: null, handlers: {}, setOption(option) { this.option = option },
      on(event, callback) { this.handlers[event] = callback }, off: vi.fn(), resize: vi.fn(), dispose: vi.fn() }
    charts.push(chart)
    return chart
  },
}))

const task = { id: 'task-1', name: '验收日志', status: 'COMPLETED', createdAtEpochMillis: 1000,
  files: [{ originalName: 'mongo.log' }], totalBytes: 2048, logStartEpochMillis: 1000, logEndEpochMillis: 9000 }
const stat = { count: 3, totalDurationMillis: 600, averageDurationMillis: 200, minDurationMillis: 100,
  maxDurationMillis: 300, totalResponseBytes: 1024, totalCpuNanos: 120000000 }
const summary = { heartbeatFailures: 2, slowQueryCount: 3, cpuAvailable: true,
  operations: { find: stat }, namespaces: { 'db.a': stat }, remotes: { '127.0.0.1': stat },
  plans: { IXSCAN: stat }, namespaceResponseBytes: { 'db.a': 1024, 'db.b': 4096 },
  cpuByOperationNamespace: { 'find|db.a': stat }, cpuByOperationBuckets: { find: { '40': 3 } },
  averageConnections: [{ timestampEpochMillis: 0, sampleCount: 2, averageConnections: 2.5 },
    { timestampEpochMillis: 7200000, sampleCount: 1, averageConnections: 0 }],
  patternStats: [{ ...stat, namespace: 'db.a', operation: 'find', pattern: '{"x":"?"}',
    planSummary: 'IXSCAN', cpuAvailable: true, slowestQueryId: null,
    slowestQuery: { queryId: '0-outside', timestampEpochMillis: 1000, namespace: 'db.a', operation: 'find', durationMillis: 300,
      attributes: { command: { find: 'a' } }, rawLine: 'independent-pattern-raw' } }],
  durationDistribution: [{ key: '100ms_500ms', label: '100–500ms', count: 3, percentage: 100,
    averageDurationMillis: 200, maxDurationMillis: 300 }],
}
const points = { series: [{ name: 'db.a', data: [[1000, 300, '0-3']] }, { name: 'db.b', data: [[2000, 200, '0-2']] }] }
const detail = (queryId) => ({ queryId, id: 51803, timestampEpochMillis: 1000, severity: 'I',
  component: 'COMMAND', context: 'conn42', message: 'Slow query', namespace: 'db.a', durationMillis: 300,
  attributes: { command: { find: 'a' } }, rawLine: `raw-${queryId} <script>unsafe</script>` })
const response = (body, ok = true) => ({ ok, status: ok ? 200 : 500, text: async () => JSON.stringify(body) })
let wrapper

beforeEach(() => {
  charts.length = 0
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
  vi.stubGlobal('fetch', vi.fn(async (url) => {
    if (url === '/api/tasks') return response([task])
    if (url === '/api/tasks/task-1/summary') return response(summary)
    if (url === '/api/tasks/task-1/slow-query-points') return response(points)
    if (url.startsWith('/api/tasks/task-1/slow-queries/')) return response(detail(url.split('/').at(-1)))
    throw new Error(`Unexpected request: ${url}`)
  }))
})
afterEach(() => { wrapper?.unmount(); vi.unstubAllGlobals() })

async function openResult() {
  wrapper = mount(App, { global: { plugins: [ElementPlus], stubs: { teleport: true } } })
  await flushPromises()
  const button = wrapper.findAll('button').find((item) => item.text() === '查看分析结果')
  expect(button, '任务应提供查看分析结果操作').toBeTruthy()
  await button.trigger('click')
  await flushPromises()
}
function scatter() { return charts.find((chart) => chart.option?.series?.[0]?.type === 'scatter') }

describe('LogVis results', () => {
  it('discloses failed, partial and skipped input instead of presenting a clean empty report', async () => {
    const originalFetch = fetch.getMockImplementation()
    fetch.mockImplementation(url => url.endsWith('/summary') ? Promise.resolve(response({ ...summary,
      failedLines: 3, partialLines: 2, skippedLines: 1, parseErrors: { INVALID_STRUCTURED_JSON: 3 } })) : originalFetch(url))
    await openResult()
    expect(wrapper.get('.parse-notice summary').text()).toContain('失败 3 行，部分解析 2 行，跳过 1 行')
    expect(wrapper.get('.parse-notice').text()).toContain('INVALID_STRUCTURED_JSON：3 行')
  })
  it('distinguishes missing CPU and connections from zero values', async () => {
    const originalFetch = fetch.getMockImplementation()
    fetch.mockImplementation((url) => url.endsWith('/summary')
      ? Promise.resolve(response({ ...summary, cpuAvailable: false, averageConnections: [] })) : originalFetch(url))
    await openResult()
    expect(wrapper.text()).toContain('日志未提供 cpuNanos')
    expect(wrapper.text()).toContain('日志未提供可解析的连接数事件')
    expect(wrapper.findAll('h2').map((heading) => heading.text())).not.toContain('CPU Cost by Operation')
    expect(charts.some((chart) => chart.option?.series?.[0]?.type === 'line')).toBe(false)
  })

  it('explains missing data in historical tasks instead of displaying fabricated aggregates', async () => {
    const originalFetch = fetch.getMockImplementation()
    fetch.mockImplementation((url) => url.endsWith('/summary')
      ? Promise.resolve(response({ ...summary, patternStats: null, namespaceResponseBytes: null,
        cpuByOperationBuckets: null, averageConnections: null })) : originalFetch(url))
    await openResult()
    expect(wrapper.text()).toContain('此历史任务未保存新版模式统计，请重新上传分析')
    expect(wrapper.text()).toContain('此历史任务未保存完整 Namespace 响应量，请重新上传分析')
    expect(wrapper.text()).toContain('此历史任务的 Top 20 排序口径不同，请重新上传分析')
    expect(wrapper.text()).toContain('此历史任务未保存完整执行计划统计，请重新上传分析')
    expect(wrapper.text()).toContain('此历史任务未保存 CPU 区间统计，请重新上传分析')
    expect(wrapper.text()).toContain('此历史任务未保存连接数统计，请重新上传分析')
  })

  it('renders ordered result blocks, exact aggregates and all namespace response slices', async () => {
    await openResult()
    const headings = wrapper.findAll('h2').map((heading) => heading.find('.metric-label > span').text())
    expect(headings).toEqual(['失败操作', '客户端统计 · Top 20', '操作类型统计',
      '集合统计 · Top 20', '慢查询耗时分布', '查询模式统计 · Top 50',
      '操作类型占比', '集合响应量占比', '执行计划分布',
      'CPU 耗时占比', 'CPU 耗时比例分布', '每小时平均连接数',
      '最慢查询时间分布 · Top 5000'])
    expect(wrapper.text()).toContain('按出现次数排序')
    expect(wrapper.text()).toContain('mongo.log')
    expect(wrapper.text()).toContain('日志无法提供')
    const responseChart = charts.find((chart) => chart.option?.series?.[0]?.name === '集合响应量占比')
    expect(responseChart.option.series[0].data).toEqual([{ name: 'db.a', value: 1024 }, { name: 'db.b', value: 4096 }])
    const connectionChart = charts.find((chart) => chart.option?.series?.[0]?.type === 'line')
    expect(connectionChart.option.series[0].data).toEqual([[0, 2.5], [3600000, null], [7200000, 0]])
    expect(connectionChart.option.series[0].connectNulls).toBe(false)
  })

  it('switches between charts and exact tables without rendering duplicate views', async () => {
    await openResult()
    const histogram = wrapper.find('[data-metric="duration"]')
    expect(histogram.find('.analysis-chart').exists()).toBe(true)
    expect(histogram.find('table').exists()).toBe(false)
    await histogram.findAll('button').find((button) => button.text() === '数据表').trigger('click')
    expect(histogram.find('.analysis-chart').exists()).toBe(false)
    expect(histogram.find('table').text()).toContain('200.000 ms')
    await histogram.findAll('button').find((button) => button.text() === '图表').trigger('click')
    expect(histogram.find('.analysis-chart').exists()).toBe(true)
    expect(histogram.find('table').exists()).toBe(false)
    const pie = wrapper.find('[data-metric="操作类型占比"]')
    expect(pie.find('table').exists()).toBe(false)
    await pie.findAll('button').find((button) => button.text() === '数据表').trigger('click')
    expect(pie.find('table').text()).toContain('3 次')
    expect(pie.find('.analysis-chart').exists()).toBe(false)
    expect(wrapper.findAll('button[aria-label$="指标说明"]').length).toBeGreaterThanOrEqual(13)
    expect(wrapper.findAll('[aria-label="调整指标窗口大小"]').length).toBe(13)
    expect(wrapper.text()).toContain('HeartBeat Failed')
  })

  it('provides the complete long pattern in a focusable tooltip', async () => {
    const pattern = JSON.stringify({ $and: Array.from({ length: 50 }, (_, index) => ({ ['field' + index]: '?' })) })
    const originalFetch = fetch.getMockImplementation()
    fetch.mockImplementation(url => url.endsWith('/summary') ? Promise.resolve(response({ ...summary,
      patternStats: [{ ...summary.patternStats[0], pattern }] })) : originalFetch(url))
    await openResult()
    const target = wrapper.get('[aria-label="查看完整查询模式"]')
    expect(target.text()).toBe(pattern)
    await target.trigger('focus')
    await new Promise(resolve => setTimeout(resolve, 200))
    await flushPromises()
    expect(wrapper.findAll('[role="tooltip"]').some(tooltip => tooltip.text() === pattern)).toBe(true)
  })

  it('loads only lightweight namespace series then a right drawer with interpreted log on click', async () => {
    await openResult()
    expect(fetch.mock.calls.some(([url]) => url.includes('/slow-queries/'))).toBe(false)
    expect(scatter().option.series.map(({ name, data }) => ({ name, data }))).toEqual(points.series)
    expect(scatter().option.xAxis.type).toBe('time')
    expect(scatter().option.yAxis.name).toContain('ms')
    expect(scatter().option.toolbox.feature).toHaveProperty('dataZoom')
    expect(scatter().option.toolbox.feature).toHaveProperty('restore')
    expect(scatter().option.dataZoom.map((zoom) => zoom.type)).toEqual(['inside', 'slider'])
    scatter().handlers.click({ componentType: 'series', data: [1000, 300, '0-3'] })
    await flushPromises()
    const selected = wrapper.find('[data-testid="slow-query-detail"]')
    expect(selected.text()).toContain('51803')
    expect(selected.text()).toContain('conn42')
    expect(selected.text()).toContain('raw-0-3 <script>unsafe</script>')
    expect(selected.find('script').exists()).toBe(false)
    expect(wrapper.find('.el-drawer.rtl').exists()).toBe(true)
    expect(selected.text()).toContain('日志解读')
    expect(selected.text()).toContain('操作耗时')
    expect(wrapper.find('.scatter-panel [data-testid="slow-query-detail"]').exists()).toBe(false)
  })

  it('opens the independent pattern sample without requesting a Top 5000 detail', async () => {
    await openResult()
    await wrapper.findAll('button').find((button) => button.text() === '查看语句 ↗').trigger('click')
    await flushPromises()
    expect(wrapper.find('.el-drawer').text()).toContain('模式最慢语句')
    expect(wrapper.find('[data-testid="slow-query-detail"]').text()).toContain('independent-pattern-raw')
    expect(fetch.mock.calls.some(([url]) => url.includes('/slow-queries/'))).toBe(false)
  })

  it('explains missing independent samples in historical reports', async () => {
    const originalFetch = fetch.getMockImplementation()
    fetch.mockImplementation((url) => url.endsWith('/summary') ? Promise.resolve(response({ ...summary,
      patternStats: [{ ...summary.patternStats[0], slowestQuery: null, slowestQueryId: '0-3' }] })) : originalFetch(url))
    await openResult()
    expect(wrapper.find('.pattern-panel').text()).toContain('历史任务未保存独立样本，请重新上传')
    expect(wrapper.find('.pattern-panel').text()).not.toContain('查看语句 ↗')
  })

  it('closing the drawer invalidates a pending detail response', async () => {
    await openResult()
    let resolveDetail
    fetch.mockImplementationOnce(() => new Promise((resolve) => { resolveDetail = resolve }))
    scatter().handlers.click({ componentType: 'series', data: [1000, 300, '0-3'] })
    await flushPromises()
    await wrapper.find('.el-drawer__close-btn').trigger('click')
    resolveDetail(response(detail('0-3')))
    await flushPromises()
    expect(wrapper.find('[data-testid="slow-query-detail"]').exists()).toBe(false)
  })

  it('keeps the most recently selected point when requests finish out of order', async () => {
    await openResult()
    let resolveFirst
    fetch.mockImplementationOnce(() => new Promise((resolve) => { resolveFirst = resolve }))
    scatter().handlers.click({ componentType: 'series', data: [1000, 300, '0-3'] })
    await flushPromises()
    expect(fetch).toHaveBeenLastCalledWith('/api/tasks/task-1/slow-queries/0-3', undefined)
    scatter().handlers.click({ componentType: 'series', data: [2000, 200, '0-2'] })
    await flushPromises()
    resolveFirst(response(detail('0-3')))
    await flushPromises()
    expect(wrapper.find('[data-testid="slow-query-detail"]').text()).toContain('raw-0-2')
    expect(wrapper.find('[data-testid="slow-query-detail"]').text()).not.toContain('raw-0-3')
  })

  it('clears old detail on a failed selection and offers retry', async () => {
    await openResult()
    scatter().handlers.click({ componentType: 'series', data: [1000, 300, '0-3'] })
    await flushPromises()
    fetch.mockImplementationOnce(async () => response({ message: '读取详情失败' }, false))
    scatter().handlers.click({ componentType: 'series', data: [2000, 200, '0-2'] })
    await flushPromises()
    expect(wrapper.text()).toContain('读取详情失败')
    expect(wrapper.find('[data-testid="slow-query-detail"]').exists()).toBe(false)
    await wrapper.findAll('button').find((button) => button.text() === '重试详情').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="slow-query-detail"]').text()).toContain('raw-0-2')
  })
})
