import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../src/App.vue'

const tasks = ['a', 'b'].map(id => ({ id, name: id, status: 'RUNNING', files: [], totalBytes: 100, processedBytes: 10 }))
const reply = body => ({ ok: true, text: async () => JSON.stringify(body) })
const listStub = { props: ['tasks'], emits: ['select', 'deleted'], template: '<div><div v-for="task in tasks" :key="task.id"><button @click="$emit(\'select\', task)">{{task.name}}</button><button :aria-label="\'delete-\'+task.id" @click="$emit(\'deleted\',task.id)">删除</button></div></div>' }
let wrapper
beforeEach(() => vi.useFakeTimers())
afterEach(() => { wrapper?.unmount(); vi.useRealTimers(); vi.unstubAllGlobals() })
async function open() {
  wrapper = mount(App, { global: { plugins: [ElementPlus], stubs: { TaskList: listStub, UploadPanel: true } } })
  await flushPromises()
}
async function select(name) { await wrapper.findAll('button').find(b => b.text() === name).trigger('click'); await flushPromises() }
async function back() { await wrapper.find('.back-button').trigger('click'); await flushPromises() }

describe('task request ownership', () => {
  it('does not stop the new task polling when the previous task request fails', async () => {
    let rejectA
    vi.stubGlobal('fetch', vi.fn(url => url === '/api/tasks' ? Promise.resolve(reply(tasks))
      : url === '/api/tasks/a' ? new Promise((resolve, reject) => { rejectA = reject }) : Promise.resolve(reply(tasks[1]))))
    await open(); await select('a'); await vi.advanceTimersByTimeAsync(1000)
    await back(); await select('b')
    rejectA(new Error('old request failure')); await flushPromises()
    await vi.advanceTimersByTimeAsync(2000)
    expect(fetch.mock.calls.filter(([url]) => url === '/api/tasks/b').length).toBeGreaterThanOrEqual(2)
  })

  it('keeps one progress request in flight and finishes once the task completes', async () => {
    let resolveTask
    vi.stubGlobal('fetch', vi.fn(url => url === '/api/tasks' ? Promise.resolve(reply(tasks))
      : url.endsWith('/summary') ? Promise.resolve(reply(null)) : new Promise(resolve => { resolveTask = resolve })))
    await open(); await select('a'); await vi.advanceTimersByTimeAsync(4000)
    expect(fetch.mock.calls.filter(([url]) => url === '/api/tasks/a')).toHaveLength(1)
    resolveTask(reply({ ...tasks[0], status: 'COMPLETED' })); await flushPromises()
    expect(wrapper.text()).toContain('分析完成')
    await vi.advanceTimersByTimeAsync(3000)
    expect(fetch.mock.calls.filter(([url]) => url === '/api/tasks/a')).toHaveLength(1)
  })

  it('does not resurrect a deleted task from a stale list response', async () => {
    let resolveList
    let calls = 0
    vi.stubGlobal('fetch', vi.fn(url => {
      if (url === '/api/system/memory') return Promise.resolve(reply({ usedBytes: 1, maxBytes: 10, usagePercent: 10 }))
      return ++calls === 1 ? Promise.resolve(reply(tasks)) : new Promise(resolve => { resolveList = resolve })
    }))
    await open(); await select('a'); await back()
    await wrapper.get('[aria-label="delete-a"]').trigger('click')
    resolveList(reply(tasks)); await flushPromises()
    expect(wrapper.findAll('button').some(b => b.text() === 'a')).toBe(false)
  })
})
