import { request } from './request.js'

export const fetchMemoryUsage = () => request('/api/system/memory', { cache: 'no-store' })
export const clearAllData = () => request('/api/system/data', { method: 'DELETE' })
