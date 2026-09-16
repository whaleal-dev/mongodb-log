async function request(url, options = {}) {
  const response = await fetch(url, options)
  const text = await response.text()
  const body = text ? JSON.parse(text) : null
  if (!response.ok) throw new Error(body?.message?.replaceAll('FTDC', 'Metric') || `请求失败（HTTP ${response.status}）`)
  return body
}

function queryString(params) {
  const query = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') query.set(key, value)
  })
  return query.toString()
}

export function createFtdcTask(name, files) {
  const form = new FormData()
  if (name?.trim()) form.append('name', name.trim())
  files.forEach(file => form.append('files', file))
  return request('/api/ftdc-tasks', { method: 'POST', body: form })
}

export const fetchFtdcTasks = () => request('/api/ftdc-tasks')
export const fetchFtdcTask = id => request(`/api/ftdc-tasks/${encodeURIComponent(id)}`)
export const deleteFtdcTask = id => request(`/api/ftdc-tasks/${encodeURIComponent(id)}`, { method: 'DELETE' })

export function fetchFtdcGroups(id, signal) {
  return request(`/api/ftdc-tasks/${encodeURIComponent(id)}/groups`, { signal })
}

export function fetchFtdcGroupSeries(id, groupId, params = {}, signal) {
  return request(`/api/ftdc-tasks/${encodeURIComponent(id)}/groups/${encodeURIComponent(groupId)}/series?${queryString(params)}`, { signal })
}
