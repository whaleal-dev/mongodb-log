import { afterEach, describe, expect, it, vi } from 'vitest'
import { deleteTask, fetchTasks } from '../src/api/tasks.js'
import { deleteFtdcTask, fetchFtdcGroups, fetchFtdcGroupSeries, fetchFtdcTasks } from '../src/api/ftdcTasks.js'
import { clearAllData, fetchMemoryUsage } from '../src/api/system.js'

function respond(status, text) {
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: status >= 200 && status < 300, status, text: async () => text })))
}

afterEach(() => vi.unstubAllGlobals())

describe.each([
  ['Log', fetchTasks],
  ['Metric', fetchFtdcTasks],
  ['System', fetchMemoryUsage],
])('%s API response handling', (_, load) => {
  it.each([
    [413, '<html>Payload Too Large</html>'],
    [500, 'Internal Server Error'],
    [404, ''],
  ])('reports HTTP %s when the error response is not JSON', async (status, text) => {
    respond(status, text)
    await expect(load()).rejects.toThrow(`请求失败（HTTP ${status}）`)
  })

  it('preserves a JSON error message', async () => {
    respond(422, JSON.stringify({ message: '任务文件已损坏' }))
    await expect(load()).rejects.toThrow('任务文件已损坏')
  })

  it('uses the HTTP status when the error message is not text', async () => {
    respond(503, JSON.stringify({ message: { code: 'UNAVAILABLE' } }))
    await expect(load()).rejects.toThrow('请求失败（HTTP 503）')
  })

  it('returns the parsed successful response', async () => {
    const body = { id: 'task', status: 'COMPLETED' }
    respond(200, JSON.stringify(body))
    await expect(load()).resolves.toEqual(body)
  })

  it('reports a readable format error for an invalid successful response', async () => {
    respond(200, '<html>Unexpected page</html>')
    await expect(load()).rejects.toThrow('响应格式错误（HTTP 200）')
  })
})

describe('API request compatibility', () => {
  it.each([
    ['Log', () => deleteTask('task/name'), '/api/tasks/task%2Fname'],
    ['Metric', () => deleteFtdcTask('task/name'), '/api/ftdc-tasks/task%2Fname'],
    ['System', clearAllData, '/api/system/data'],
  ])('accepts the empty %s deletion response', async (_, remove, url) => {
    respond(204, '')
    await expect(remove()).resolves.toBeNull()
    expect(fetch).toHaveBeenCalledWith(url, { method: 'DELETE' })
  })

  it('keeps Metric error terminology', async () => {
    respond(422, JSON.stringify({ message: 'FTDC block 损坏' }))
    await expect(fetchFtdcTasks()).rejects.toThrow('Metric block 损坏')
  })

  it('forwards Metric cancellation signals and group query parameters', async () => {
    respond(200, '[]')
    const { signal } = new AbortController()
    await fetchFtdcGroups('task/name', signal)
    expect(fetch).toHaveBeenLastCalledWith('/api/ftdc-tasks/task%2Fname/groups', { signal })
    await fetchFtdcGroupSeries('task/name', 'group/name', { maxPoints: 1200, view: 'delta' }, signal)
    expect(fetch).toHaveBeenLastCalledWith('/api/ftdc-tasks/task%2Fname/groups/group%2Fname/series?maxPoints=1200&view=delta', { signal })
  })

  it('keeps memory requests uncached', async () => {
    respond(200, '{}')
    await fetchMemoryUsage()
    expect(fetch).toHaveBeenCalledWith('/api/system/memory', { cache: 'no-store' })
  })
})
