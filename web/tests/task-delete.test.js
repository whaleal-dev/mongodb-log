import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import TaskList from '../src/components/TaskList.vue'
const task = { id: 'synthetic-task', name: '合成测试', status: 'COMPLETED', files: [], totalBytes: 0 }
afterEach(() => vi.restoreAllMocks())
describe('task deletion', () => {
  it('asks before deleting and reports success only after the API succeeds', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, status: 204, text: async () => '' })))
    const wrapper = mount(TaskList, { props: { tasks: [task] }, global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === '删除').trigger('click'); await flushPromises()
    expect(ElMessageBox.confirm).toHaveBeenCalled()
    expect(fetch).toHaveBeenCalledWith('/api/tasks/synthetic-task', { method: 'DELETE' })
    expect(wrapper.emitted('deleted')[0]).toEqual(['synthetic-task'])
    wrapper.unmount(); vi.unstubAllGlobals()
  })

  it('cancelling preserves the task and active tasks cannot be deleted', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    vi.stubGlobal('fetch', vi.fn())
    const wrapper = mount(TaskList, { props: { tasks: [task, { ...task, id: 'running', status: 'RUNNING' }] }, global: { plugins: [ElementPlus] } })
    await flushPromises()
    const buttons = wrapper.findAll('button').filter(b => b.text() === '删除')
    await buttons[0].trigger('click'); await flushPromises()
    expect(fetch).not.toHaveBeenCalled()
    expect(buttons[1].attributes('disabled')).toBeDefined()
    expect(wrapper.emitted('deleted')).toBeUndefined()
    wrapper.unmount(); vi.unstubAllGlobals()
  })

  it('retains the task and surfaces a cleanup error from the server', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    const error = vi.spyOn(ElMessage, 'error').mockImplementation(() => {})
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 500, text: async () => '{"message":"任务清理失败，请重试"}' })))
    const wrapper = mount(TaskList, { props: { tasks: [task] }, global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === '删除').trigger('click'); await flushPromises()
    expect(wrapper.emitted('deleted')).toBeUndefined()
    expect(error).toHaveBeenCalledWith('任务清理失败，请重试')
    wrapper.unmount(); vi.unstubAllGlobals()
  })
})
