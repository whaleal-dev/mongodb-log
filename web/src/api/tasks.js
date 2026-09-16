async function request(url, options) {
  const response = await fetch(url, options)
  const text = await response.text()
  const body = text ? JSON.parse(text) : null
  if (!response.ok) {
    throw new Error(body?.message || `请求失败（HTTP ${response.status}）`)
  }
  return body
}

export function createTask(name, files) {
  const form = new FormData()
  if (name?.trim()) form.append('name', name.trim())
  files.forEach((file) => form.append('files', file))
  return request('/api/tasks', { method: 'POST', body: form })
}

export function fetchTasks() {
  return request('/api/tasks')
}

export function deleteTask(taskId) {
  return request(`/api/tasks/${encodeURIComponent(taskId)}`, { method: 'DELETE' })
}

export function fetchTask(taskId) {
  return request(`/api/tasks/${encodeURIComponent(taskId)}`)
}

export function fetchSummary(taskId) {
  return request(`/api/tasks/${encodeURIComponent(taskId)}/summary`)
}

export function fetchDiagnostics(taskId) {
  return request(`/api/tasks/${encodeURIComponent(taskId)}/diagnostics`)
}

export async function downloadReport(taskId) {
  const response = await fetch(`/api/tasks/${encodeURIComponent(taskId)}/report.md`)
  if (!response.ok) {
    let message = `报告导出失败（HTTP ${response.status}）`
    try {
      const body = JSON.parse(await response.text())
      if (body?.message) message = body.message
    } catch { /* 使用 HTTP 状态提示 */ }
    throw new Error(message)
  }
  const disposition = response.headers.get('Content-Disposition') || ''
  const utf8Name = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  const plainName = disposition.match(/filename="([^"]+)"/i)?.[1]
  let fileName = plainName || 'mongodb-analysis.md'
  if (utf8Name) {
    try { fileName = decodeURIComponent(utf8Name) } catch { /* 使用安全回退名称 */ }
  }
  const objectUrl = URL.createObjectURL(await response.blob())
  try {
    const link = document.createElement('a')
    link.href = objectUrl
    link.download = fileName
    link.click()
  } finally {
    URL.revokeObjectURL(objectUrl)
  }
}

export function fetchSlowQueries(taskId, params = {}) {
  const query = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') query.set(key, value)
  })
  return request(`/api/tasks/${encodeURIComponent(taskId)}/slow-queries?${query}`)
}

export function fetchSlowQuery(taskId, queryId) {
  return request(`/api/tasks/${encodeURIComponent(taskId)}/slow-queries/${encodeURIComponent(queryId)}`)
}

export function fetchSlowQueryPoints(taskId) {
  return request(`/api/tasks/${encodeURIComponent(taskId)}/slow-query-points`)
}
