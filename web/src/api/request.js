export async function request(url, options, formatErrorMessage = message => message) {
  const response = await fetch(url, options)
  const text = await response.text()
  let body
  try { body = text ? JSON.parse(text) : null }
  catch {
    if (response.ok) throw new Error(`响应格式错误（HTTP ${response.status}）`)
  }
  if (!response.ok) {
    const message = typeof body?.message === 'string' ? body.message : ''
    throw new Error(formatErrorMessage(message || `请求失败（HTTP ${response.status}）`))
  }
  return body
}
