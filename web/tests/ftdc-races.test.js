import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import FtdcWorkspace from '../src/components/ftdc/FtdcWorkspace.vue'

const task = { id: 'task', name: 'task', status: 'COMPLETED', files: [], totalBytes: 1, processedBytes: 1, blockCount: 1, metricCount: 2 }
const reply = body => Promise.resolve({ ok: true, text: async () => JSON.stringify(body) })
const taskList = { props: ['tasks'], emits: ['select', 'deleted'], template: '<button v-for="task in tasks" :key="task.id" @click="$emit(\'select\',task)">{{task.name}}</button>' }
const groupChart = { props: ['group'], template: '<div class="group-result">{{group.name}}：{{group.series[0].values.join(\',\')}}</div>' }

afterEach(() => vi.unstubAllGlobals())

describe('FTDC request ownership', () => {
  it('aborts a stale group request and keeps the newest group result', async () => {
    const staleSignals = []
    vi.stubGlobal('fetch', vi.fn((url, options = {}) => {
      if (url === '/api/ftdc-tasks') return reply([task])
      if (url.endsWith('/groups')) return reply([{ groupId: 'a', name: 'A' }, { groupId: 'b', name: 'B' }])
      if (url.includes('/groups/a/series')) {
        staleSignals.push(options.signal)
        return new Promise((resolve, reject) => options.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError'))))
      }
      if (url.includes('/groups/b/series')) return reply({ groupId: 'b', name: 'B', series: [{ values: [22] }] })
      throw new Error(`unexpected ${url}`)
    }))
    const wrapper = mount(FtdcWorkspace, { global: { plugins: [ElementPlus], stubs: { FtdcTaskList: taskList, FtdcUploadPanel: true, FtdcMetricGroupChart: groupChart } } })
    await flushPromises()
    await wrapper.find('button').trigger('click')
    await flushPromises()
    wrapper.vm.selectedGroupIds = ['a']
    const stale = wrapper.vm.querySelectedGroups()
    await Promise.resolve()
    wrapper.vm.selectedGroupIds = ['b']
    await wrapper.vm.querySelectedGroups()
    await stale
    await flushPromises()

    expect(staleSignals).toHaveLength(1)
    expect(staleSignals.every(signal => signal.aborted)).toBe(true)
    expect(wrapper.find('.group-result').text()).toBe('B：22')
    wrapper.unmount()
  })

  it('does not overlap status polling requests for the same task', async () => {
    vi.useFakeTimers()
    const running = { ...task, status: 'RUNNING' }
    let resolvePoll
    const fetch = vi.fn(url => {
      if (url === '/api/ftdc-tasks') return reply([running])
      if (url === '/api/ftdc-tasks/task') return new Promise(resolve => { resolvePoll = () => resolve({ ok: true, text: async () => JSON.stringify(running) }) })
      throw new Error(`unexpected ${url}`)
    })
    vi.stubGlobal('fetch', fetch)
    const wrapper = mount(FtdcWorkspace, { global: { plugins: [ElementPlus], stubs: { FtdcTaskList: taskList, FtdcUploadPanel: true, FtdcMetricGroupChart: groupChart } } })
    await flushPromises()
    await wrapper.find('button').trigger('click')

    await vi.advanceTimersByTimeAsync(1_000)
    await vi.advanceTimersByTimeAsync(3_000)
    expect(fetch.mock.calls.filter(([url]) => url === '/api/ftdc-tasks/task')).toHaveLength(1)

    resolvePoll()
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1_000)
    expect(fetch.mock.calls.filter(([url]) => url === '/api/ftdc-tasks/task')).toHaveLength(2)
    wrapper.unmount()
    vi.useRealTimers()
  })

})
