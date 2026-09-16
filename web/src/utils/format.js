const decimalFormatter = new Intl.NumberFormat('en-US', { useGrouping: false, minimumFractionDigits: 3, maximumFractionDigits: 3 })
export function formatDecimal(value) {
  return decimalFormatter.format(Number(value) || 0)
}

export function formatDuration(value) {
  const milliseconds = Number(value) || 0
  if (milliseconds < 1000) return `${formatDecimal(milliseconds)} ms`
  if (milliseconds < 60_000) return `${formatDecimal(milliseconds / 1000)} s`
  return `${formatDecimal(milliseconds / 60_000)} min`
}

export function formatBytes(value) {
  const bytes = Number(value) || 0
  if (bytes < 1024) return `${bytes.toFixed(0)} B`
  if (bytes < 1024 ** 2) return `${formatDecimal(bytes / 1024)} KB`
  if (bytes < 1024 ** 3) return `${formatDecimal(bytes / 1024 ** 2)} MB`
  return `${formatDecimal(bytes / 1024 ** 3)} GB`
}

export function formatPercent(value) {
  return `${formatDecimal(value)}%`
}

export function formatDate(value) {
  if (value === null || value === undefined) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(new Date(value))
}

export function formatTimeRange(start, end) {
  return start == null || end == null ? '暂无可用日志时间范围' : `${formatDate(start)} ～ ${formatDate(end)}`
}
