import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import FtdcWorkspace from '../src/components/ftdc/FtdcWorkspace.vue'

const task = { id: 'task', name: 'task', status: 'COMPLETED', files: [], totalBytes: 1, processedBytes: 1, blockCount: 1, metricCount: 2 }
const reply = body => Promise.resolve({ ok: true, text: async () => JSON.stringify(body) })
const taskList = { props: ['tasks'], emits: ['select', 'deleted'], template: '<button v-for="task in tasks" :key="task.id" @click="$emit(\'select\',task)">{{task.name}}</button>' }
const groupChart = { props: ['group'], template: '<div class="group-result">{{group.name}}：{{group.series[0].values.join(\',\')}}</div>' }

afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

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

  it('keeps the new task polling when an earlier task request fails', async () => {
    vi.useFakeTimers()
    const tasks = ['a', 'b'].map(id => ({ ...task, id, name: id, status: 'RUNNING' }))
    let rejectA
    const fetch = vi.fn(url => {
      if (url === '/api/ftdc-tasks') return reply(tasks)
      if (url.endsWith('/a')) return new Promise((resolve, reject) => { rejectA = reject })
      if (url.endsWith('/b')) return reply(tasks[1])
      throw new Error(`unexpected ${url}`)
    })
    vi.stubGlobal('fetch', fetch)
    const wrapper = mount(FtdcWorkspace, { global: { plugins: [ElementPlus], stubs: { FtdcTaskList: taskList, FtdcUploadPanel: true } } })
    try {
      await flushPromises()
      await wrapper.vm.selectTask(tasks[0])
      await vi.advanceTimersByTimeAsync(1000)
      wrapper.vm.back()
      await wrapper.vm.selectTask(tasks[1])
      rejectA(new Error('old task failure'))
      await flushPromises()
      await vi.advanceTimersByTimeAsync(2000)

      expect(fetch.mock.calls.filter(([url]) => url.endsWith('/b'))).toHaveLength(2)
      expect(wrapper.text()).not.toContain('old task failure')
    } finally { wrapper.unmount() }
  })

  it('does not load groups for a new task after an earlier completion refresh finishes', async () => {
    vi.useFakeTimers()
    const tasks = ['a', 'b'].map(id => ({ ...task, id, name: id, status: 'RUNNING' }))
    let resolveOldList, lists = 0
    const fetch = vi.fn(url => {
      if (url === '/api/ftdc-tasks') return ++lists === 2 ? new Promise(resolve => { resolveOldList = resolve }) : reply(tasks)
      if (url.endsWith('/a')) return reply({ ...tasks[0], status: 'COMPLETED' })
      if (url.endsWith('/b')) return reply(tasks[1])
      if (url.endsWith('/groups')) return reply([])
      throw new Error(`unexpected ${url}`)
    })
    vi.stubGlobal('fetch', fetch)
    const wrapper = mount(FtdcWorkspace, { global: { plugins: [ElementPlus], stubs: { FtdcTaskList: taskList, FtdcUploadPanel: true } } })
    try {
      await flushPromises()
      await wrapper.vm.selectTask(tasks[0])
      await vi.advanceTimersByTimeAsync(1000)
      wrapper.vm.back()
      await wrapper.vm.selectTask(tasks[1])
      resolveOldList({ ok: true, text: async () => JSON.stringify(tasks) })
      await flushPromises()

      expect(fetch.mock.calls.some(([url]) => url.endsWith('/groups'))).toBe(false)
      await vi.advanceTimersByTimeAsync(1000)
      expect(fetch.mock.calls.some(([url]) => url.endsWith('/b'))).toBe(true)
    } finally { wrapper.unmount() }
  })

  it('does not restore a deleted task from an older list response', async () => {
    const tasks = ['a', 'b'].map(id => ({ ...task, id, name: id }))
    let resolveOldList, lists = 0
    vi.stubGlobal('fetch', vi.fn(() => ++lists === 1 ? reply(tasks) : new Promise(resolve => { resolveOldList = resolve })))
    const wrapper = mount(FtdcWorkspace, { global: { plugins: [ElementPlus], stubs: { FtdcTaskList: taskList, FtdcUploadPanel: true } } })
    try {
      await flushPromises()
      const pending = wrapper.vm.loadTasks()
      wrapper.vm.deleted('a')
      resolveOldList({ ok: true, text: async () => JSON.stringify(tasks) })
      await pending

      expect(wrapper.vm.tasks.map(item => item.id)).toEqual(['b'])
    } finally { wrapper.unmount() }
  })

})
