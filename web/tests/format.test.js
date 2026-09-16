import { describe, expect, it } from 'vitest'
import { formatBytes, formatDecimal, formatDuration, formatPercent } from '../src/utils/format.js'

describe('format helpers', () => {
  it('formats durations with explicit units', () => {
    expect(formatDuration(99)).toBe('99.000 ms')
    expect(formatDuration(1500)).toBe('1.500 s')
    expect(formatDuration(60_000)).toBe('1.000 min')
  })

  it('formats bytes and percentages', () => {
    expect(formatBytes(1024)).toBe('1.000 KB')
    expect(formatBytes(5 * 1024 * 1024)).toBe('5.000 MB')
    expect(formatPercent(12.3456)).toBe('12.346%')
    expect(formatPercent(null)).toBe('0.000%')
  })

  it('rounds half values to three decimal places without binary floating point truncation', () => {
    expect(formatDecimal(1.2345)).toBe('1.235')
    expect(formatDecimal(0.0005)).toBe('0.001')
    expect(formatDecimal(1.2344)).toBe('1.234')
    expect(formatDecimal(2)).toBe('2.000')
  })
})
