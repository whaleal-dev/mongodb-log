async function request(url, options = {}) {
  const response = await fetch(url, options)
  const text = await response.text()
  const body = text ? JSON.parse(text) : null
  if (!response.ok) throw new Error(body?.message || `请求失败（HTTP ${response.status}）`)
  return body
}

export const fetchMemoryUsage = () => request('/api/system/memory', { cache: 'no-store' })
export const clearAllData = () => request('/api/system/data', { method: 'DELETE' })
