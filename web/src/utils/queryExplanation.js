import { formatDecimal } from './format.js'

const missing = '日志未提供'
const number = (value) => typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : null
const measured = (value, unit = '') => number(value) == null ? missing : `${unit === ' ms' ? formatDecimal(value) : value}${unit}`
const normalizedLine = (query) => (query.rawLine || '').replace(/^\uFEFF/, '').trim()

export function logMetadata(query) {
  let original = {}
  try {
    const raw = JSON.parse(normalizedLine(query))
    original = { id: raw.id, severity: raw.s, component: raw.c, context: raw.ctx, message: raw.msg }
  } catch {
    const header = normalizedLine(query).match(/^\S+\s+(\S+)\s+(\S+)\s+\[([^\]]+)]\s+(.*)$/s)
    if (header) original = { severity: header[1], component: header[2], context: header[3], message: header[4] }
  }
  return { ...original, ...Object.fromEntries(Object.entries(query).filter(([, value]) => value != null)) }
}

// Field semantics: https://www.mongodb.com/docs/manual/reference/database-profiler/
export function explainQuery(query) {
  const legacy = /^\d{4}-\d{2}-\d{2}T/.test(normalizedLine(query))
  const attr = legacy ? {} : query.attributes || {}
  const duration = number(query.durationMillis)
  const cpu = number(query.cpuNanos)
  const docs = number(attr.docsExamined)
  const keys = number(attr.keysExamined)
  const returned = number(attr.nreturned)
  const plan = query.planSummary || attr.planSummary || missing
  const operation = query.operation || missing
  const operationMeaning = { find: '读取符合条件的文档', aggregate: '执行聚合管道', insert: '插入文档',
    update: '更新匹配文档', delete: '删除匹配文档', remove: '删除匹配文档', getMore: '读取游标的后续一批结果',
    findAndModify: '查找并修改或删除文档', createIndexes: '创建索引' }[operation] || '日志记录的数据库操作'
  const rows = [
    { label: '操作耗时', field: 'durationMillis', value: measured(duration, ' ms'), meaning: '本条操作的服务端耗时，不等同于客户端端到端延迟。' },
    { label: 'CPU 耗时与比例', field: 'cpuNanos', value: cpu == null ? missing : `${formatDecimal(cpu / 1e6)} ms${duration > 0 ? `（${formatDecimal(cpu / 1e6 / duration * 100)}%）` : '（总耗时为零，比例不可计算）'}`,
      meaning: 'CPU 纳秒换算为毫秒，再除以操作耗时。它不是服务器 CPU 利用率，也不能用两者差值直接认定等待时间。' },
    { label: '执行计划', field: 'planSummary', value: plan, meaning: /COLLSCAN/.test(plan) ? '包含集合扫描。不能仅凭此项断言索引缺失或确定慢查询根因。'
      : /IXSCAN/.test(plan) ? '包含索引扫描；使用索引仍可能扫描很多条目，需结合扫描量判断。' : '执行计划摘要；未记录完整执行树时无法还原所有执行步骤。' },
    { label: '扫描文档数', field: 'docsExamined', value: measured(docs), meaning: '本次操作检查的文档数量。' },
    { label: '扫描索引键数', field: 'keysExamined', value: measured(keys), meaning: '本次操作检查的索引条目数量，零值与字段缺失含义不同。' },
    { label: '返回文档数', field: 'nreturned', value: measured(returned), meaning: '本次操作返回的文档数；getMore 只代表当前批次，写操作应结合匹配／修改数量。' },
    { label: '响应数据量', field: 'reslen', value: measured(query.responseLength ?? attr.reslen, ' B'), meaning: '返回结果的字节数，不等同于扫描数据量或磁盘读取量。' },
  ]
  const observations = []
  if (returned > 0 && ['find', 'getMore'].includes(operation)) {
    for (const [label, count] of [['文档', docs], ['索引键', keys]]) {
      if (count != null) observations.push({ title: `${label}扫描／返回`, text: `平均每返回一条文档，扫描 ${formatDecimal(count / returned)} 个${label}。可结合过滤条件、排序及索引前缀评估扫描开销，不能仅凭比例确定原因。` })
    }
  } else if (returned === 0) {
    observations.push({ title: '本次未返回文档', text: '返回数为零，不计算扫描／返回比；这可能是无匹配结果或写操作，需结合命令与匹配数量判断。' })
  }
  if (/COLLSCAN/.test(plan)) observations.push({ title: '集合扫描线索', text: '可核对过滤和排序字段对应的索引，以及集合规模。离线日志无法提供完整索引清单，不能直接生成确定的建索引结论。' })
  if (attr.hasSortStage === true) observations.push({ title: '存在额外排序', text: '日志记录 hasSortStage=true，结果需要额外排序。可检查排序与过滤能否共同利用索引；此字段不代表已发生磁盘排序。' })
  if (attr.usedDisk === true) observations.push({ title: '使用了临时磁盘文件', text: '日志记录 usedDisk=true。可结合聚合管道、数据量及内存限制排查落盘开销。' })
  const optionalMetrics = [
    ['匹配文档数', 'nMatched', attr.nMatched, '', '更新条件匹配的文档数量。'],
    ['修改文档数', 'nModified', attr.nModified, '', '实际修改的文档数量；匹配但内容未变化时可小于匹配数。'],
    ['插入文档数', 'ninserted', attr.ninserted, '', '本条操作插入的文档数量。'],
    ['删除文档数', 'ndeleted', attr.ndeleted, '', '本条操作删除的文档数量。'],
    ['让出执行次数', 'numYields', attr.numYields, '', '操作让出执行机会的次数，不是等待时长，也不是错误次数。'],
    ['写冲突次数', 'writeConflicts', attr.writeConflicts, '', '记录到的写冲突数量；需结合并发写入情况分析。'],
    ['磁盘读取量', 'storage.data.bytesRead', attr.storage?.data?.bytesRead, ' B', '存储层读取的数据量，可能包含索引和整页读取。'],
    ['磁盘读取耗时', 'storage.data.timeReadingMicros', number(attr.storage?.data?.timeReadingMicros) == null ? null : attr.storage.data.timeReadingMicros / 1000, ' ms', '该操作记录的磁盘读取时间，微秒已换算为毫秒。'],
    ['查询规划耗时', 'planningTimeMicros', number(attr.planningTimeMicros) == null ? null : attr.planningTimeMicros / 1000, ' ms', '选择执行计划所用时间，微秒已换算为毫秒。'],
  ]
  for (const [label, field, value, unit, meaning] of optionalMetrics) {
    if (number(value) != null) rows.push({ label, field, value: measured(value, unit), meaning })
  }
  for (const [lock, details] of Object.entries(attr.locks || {})) {
    for (const [mode, micros] of Object.entries(details?.timeAcquiringMicros || {})) {
      if (number(micros) != null) rows.push({ label: `锁等待 · ${lock} / ${mode}`, field: `locks.${lock}.timeAcquiringMicros.${mode}`,
        value: `${formatDecimal(micros / 1000)} ms`, meaning: '该锁模式累计等待获取锁的时间；不同锁项不直接合并成总耗时。r／w 为意向读／写锁，R／W 为共享／排他锁。' })
    }
  }
  const command = legacy ? (Object.keys(query.attributes || {}).length ? query.attributes : null) : attr.command || null
  return { overview: `${query.namespace || '未知集合'} · ${operation}：${operationMeaning}。`, rows, observations, command,
    originatingCommand: attr.originatingCommand || null }
}
