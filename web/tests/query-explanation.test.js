import { describe, expect, it } from 'vitest'
import { explainQuery, logMetadata } from '../src/utils/queryExplanation.js'

describe('local log interpretation', () => {
  it('explains actual measurements and conversions without asserting a root cause', () => {
    const result = explainQuery({ namespace: 'shop.orders', operation: 'find', durationMillis: 200,
      cpuNanos: 100000000, responseLength: 2048, planSummary: 'COLLSCAN', attributes: {
        command: { find: 'orders', filter: { status: 'new' } }, docsExamined: 1000, keysExamined: 0,
        nreturned: 10, hasSortStage: true, storage: { data: { bytesRead: 4096, timeReadingMicros: 2500 } },
        locks: { Global: { timeAcquiringMicros: { r: 500 } } }, numYields: 5,
      } })
    const text = JSON.stringify(result)
    expect(text).toContain('100.000 ms（50.000%）')
    expect(text).toContain('100.000')
    expect(text).toContain('2.500 ms')
    expect(text).toContain('0.500 ms')
    expect(text).toContain('集合扫描')
    expect(text).toContain('不能仅凭')
    expect(result.command.filter.status).toBe('new')
  })

  it('preserves zero and missing separately, without invalid ratios or inferred wait time', () => {
    const text = JSON.stringify(explainQuery({ durationMillis: 0, cpuNanos: 0,
      attributes: { docsExamined: 0, nreturned: 0 } }))
    expect(text).toContain('0.000 ms')
    expect(text).toContain('日志未提供')
    expect(text).not.toMatch(/Infinity|NaN/)
    const over = JSON.stringify(explainQuery({ durationMillis: 10, cpuNanos: 20000000, attributes: {} }))
    expect(over).toContain('200.000%')
    expect(over).not.toContain('-10')
  })

  it('does not treat legacy command arguments as measured statistics', () => {
    const text = JSON.stringify(explainQuery({ operation: 'find', attributes: { filter: { docsExamined: 987654 } } }))
    expect(text).not.toContain('987654')
  })

  it('reads original metadata from structured and legacy independent samples', () => {
    expect(logMetadata({ rawLine: '{"s":"I","c":"COMMAND","id":51803,"ctx":"conn1","msg":"Slow query","attr":{}}' }))
      .toMatchObject({ id: 51803, severity: 'I', component: 'COMMAND', context: 'conn1', message: 'Slow query' })
    expect(logMetadata({ rawLine: '2026-09-10T01:00:00.000+0000 I COMMAND [conn2] command db.a 200ms' }))
      .toMatchObject({ severity: 'I', component: 'COMMAND', context: 'conn2', message: 'command db.a 200ms' })
    expect(logMetadata({ rawLine: 'unparsed log' }).id).toBeUndefined()
  })

  it('parses BOM and surrounding whitespace without changing original logs', () => {
    const rawLine = '\ufeff  {"id":51803,"s":"I","c":"COMMAND","ctx":"conn7","msg":"Slow query"}  '
    expect(logMetadata({ rawLine })).toMatchObject({ id: 51803, severity: 'I', component: 'COMMAND', context: 'conn7', message: 'Slow query', rawLine })
    const legacy = { rawLine: '\ufeff  2026-09-10T01:00:00.000+0000 I COMMAND [conn8] command db.a 200ms  ',
      attributes: { docsExamined: 987654 } }
    expect(logMetadata(legacy).context).toBe('conn8')
    expect(explainQuery(legacy).rows.find((row) => row.field === 'docsExamined').value).toBe('日志未提供')
  })
})
