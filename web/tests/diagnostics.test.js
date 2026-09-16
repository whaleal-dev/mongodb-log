import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../src/App.vue'

vi.mock('echarts', () => ({
  init: () => ({ setOption: vi.fn(), on: vi.fn(), off: vi.fn(), resize: vi.fn(), dispose: vi.fn() }),
}))

const task = { id: 'task-1', name: 'MongoDB 兼容性日志', status: 'COMPLETED', createdAtEpochMillis: 1000,
  files: [{ originalName: 'mongodb.log' }], totalBytes: 2048, logStartEpochMillis: 1000, logEndEpochMillis: 9000 }
const summary = { slowQueryCount: 2, totalLines: 12, operations: {}, namespaces: {}, remotes: {}, plans: {},
  patternStats: [], durationDistribution: [], namespaceResponseBytes: {}, cpuAvailable: false,
  cpuByOperationNamespace: {}, cpuByOperationBuckets: {}, averageConnections: [] }
const diagnostics = {
  schemaVersion: 1,
  totalParsedLines: 12,
  severityCounts: { I: 7, W: 3, E: 2 },
  componentCounts: { COMMAND: 5, NETWORK: 4 },
  abnormalEvents: [{ key: 'NETWORK|22989', severity: 'W', component: 'NETWORK', messageId: 22989,
    message: 'Host failed in replica set', count: 3, firstEpochMillis: 1000, lastEpochMillis: 3000, samples: [] }],
  timeline: [{ epochMillis: 1000, warnings: 2, errors: 1, fatals: 0, connectionsAccepted: 4,
    connectionsEnded: 3, replicationEvents: 1 }],
  timelineBucketMillis: 3600000,
  connections: { accepted: 4, ended: 3, authenticationSucceeded: 2, notAuthenticating: 1,
    reauthenticationWarnings: 5, connectionCountSamples: 2, connectionCountMin: 3, connectionCountMax: 8,
    connectionCountAverage: 5.5, applications: { mongodb_exporter: 3 }, drivers: { 'mongo-go-driver 1.17': 3 },
    topValuesApproximate: false },
  replicationEvents: [{ type: 'HOST_UNAVAILABLE', label: '成员不可用', component: 'NETWORK', messageId: 22989,
    message: 'Host failed in replica set', count: 3, firstEpochMillis: 1000, lastEpochMillis: 3000 }],
  slowQueries: { total: 2, collscanCount: 1, highDocumentScanRatioCount: 1, highIndexScanRatioCount: 0,
    zeroReturnHighScanCount: 0, storageDominantCount: 1, planningDominantCount: 0,
    writeConcernWaitCount: 0, flowControlWaitCount: 0, lockWaitCount: 0, remoteOpWaitCount: 1,
    authorizationWaitCount: 1, queueWaitCount: 1, oplogSlotWaitCount: 0, hasSortStageCount: 0,
    usedDiskCount: 1, spillCount: 1, distinctShapeCount: 2,
    fieldCoverage: { queryHash: 1, planCacheShapeHash: 1, queryFramework: 1 }, queryFrameworks: { sbe: 1 },
    insights: [{ timestampEpochMillis: 2000, namespace: 'db.items', operation: 'find',
      queryPattern: '{"state":"?"}', shapeHash: 'HASH', planCacheKey: 'KEY', planSummary: 'COLLSCAN',
      durationMillis: 220, docsExamined: 1000, keysExamined: 0, returned: 1,
      documentsPerReturned: 1000, keysPerReturned: 0, storageReadMillis: 150,
      planningMillis: null, writeConcernWaitMillis: null, flowControlWaitMillis: null,
      lockWaitMillis: null, reasons: ['COLLSCAN', '等待分片响应'] }] },
  dataQuality: { structuredLines: 10, legacyLines: 2, truncatedLines: 1, taggedLines: 1,
    outOfOrderLines: 0, services: { R: 10 }, serverVersions: { '8.0.1': 1 } },
}

function json(body, ok = true) {
  return { ok, status: ok ? 200 : 500, text: async () => JSON.stringify(body) }
}

let wrapper
let anchorClick

beforeEach(() => {
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
  vi.stubGlobal('fetch', vi.fn(async (url) => {
    if (url === '/api/tasks') return json([task])
    if (url === '/api/tasks/task-1/summary') return json(summary)
    if (url === '/api/tasks/task-1/slow-query-points') return json({ series: [] })
    if (url === '/api/tasks/task-1/diagnostics') return json(diagnostics)
    if (url === '/api/tasks/task-1/report.md') return {
      ok: true,
      status: 200,
      headers: { get: () => 'attachment; filename="mongodb-analysis.md"' },
      blob: async () => new Blob(['# MongoDB 日志分析报告'], { type: 'text/markdown' }),
    }
    throw new Error(`Unexpected request: ${url}`)
  }))
  vi.stubGlobal('URL', { createObjectURL: vi.fn(() => 'blob:report'), revokeObjectURL: vi.fn() })
  anchorClick = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
})

afterEach(() => {
  wrapper?.unmount()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

async function openResult() {
  wrapper = mount(App, { global: { plugins: [ElementPlus], stubs: { teleport: true } } })
  await flushPromises()
  await wrapper.findAll('button').find(button => button.text() === '查看分析结果').trigger('click')
  await flushPromises()
}

describe('运行诊断与报告导出', () => {
  it('保留原慢查询页为默认页，并按需加载运行诊断', async () => {
    await openResult()
    expect(wrapper.text()).toContain('查询模式统计')
    expect(fetch.mock.calls.some(([url]) => url.endsWith('/diagnostics'))).toBe(false)

    await wrapper.findAll('button').find(button => button.text() === '运行诊断').trigger('click')
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('/api/tasks/task-1/diagnostics', undefined)
    expect(wrapper.text()).toContain('异常事件与时间线')
    expect(wrapper.text()).toContain('连接与客户端')
    expect(wrapper.text()).toContain('复制集与网络')
    expect(wrapper.text()).toContain('慢查询效率线索')
    expect(wrapper.text()).toContain('MongoDB 4.4+ 字段覆盖')
    expect(wrapper.text()).toContain('等待分片响应')
    expect(wrapper.text()).toContain('8.0.1')
  })

  it('一键下载适合 AI 二次分析的 Markdown 报告', async () => {
    await openResult()
    await wrapper.findAll('button').find(button => button.text() === '导出 AI 分析报告').trigger('click')
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('/api/tasks/task-1/report.md')
    expect(URL.createObjectURL).toHaveBeenCalledOnce()
    expect(anchorClick).toHaveBeenCalledOnce()
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:report')
  })
})
