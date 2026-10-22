import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createThemeTokens, normalizeThemeColor, themePresets } from '../src/ui/colors.js'
import { initializeColorTheme, THEME_STORAGE_KEY, useColorTheme } from '../src/ui/colorTheme.js'

function contrast(a, b) {
  const light = hex => [1, 3, 5].map(i => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map(v => v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4)
    .reduce((sum, v, i) => sum + v * [.2126, .7152, .0722][i], 0)
  return (Math.max(light(a), light(b)) + .05) / (Math.min(light(a), light(b)) + .05)
}

let stop
beforeEach(() => localStorage.clear())
afterEach(() => { stop?.(); vi.restoreAllMocks() })

describe('theme colors', () => {
  it('accepts only six-digit HEX and normalizes it', () => {
    expect(normalizeThemeColor(' #aabbcc ')).toBe('#AABBCC')
    for (const input of [null, 123, '#fff', '#12345678', 'red', '#GG0000', '123456']) {
      expect(normalizeThemeColor(input)).toBeNull()
      expect(() => createThemeTokens(input)).toThrow(TypeError)
    }
  })

  it.each([...themePresets.map(p => p.color), '#FFFFFF', '#FFFFFE', '#000000', '#010101', '#FFFF00', '#00FF00', '#00FFFF', '#FF00FF', '#777777'])
  ('keeps text, interaction states and marks readable for %s', color => {
    const t = createThemeTokens(color)
    for (const background of ['#FFFFFF', '#F4F6F8', '#E9E9EB', '#FFF7ED', t['--primary-soft'], t['--primary-soft-hover'], t['--el-color-primary-light-9']]) {
      expect(contrast(t['--primary-text'], background)).toBeGreaterThanOrEqual(4.5)
      expect(contrast(t['--focus-color'], background)).toBeGreaterThanOrEqual(3)
    }
    for (const background of [t['--primary'], t['--primary-hover'], t['--primary-active']]) {
      expect(contrast(t['--primary-contrast'], background)).toBeGreaterThanOrEqual(4.5)
    }
    expect(contrast(t['--primary-mark'], '#FFFFFF')).toBeGreaterThanOrEqual(3)
    expect(contrast(t['--el-color-primary'], '#FFFFFF')).toBeGreaterThanOrEqual(4.5)
    expect(t['--el-color-primary-light-8']).toMatch(/^#[\dA-F]{6}$/)
    expect(t['--el-color-primary-dark-2']).toMatch(/^#[\dA-F]{6}$/)
    expect(t['--el-color-success']).toBeUndefined()
  })

  it.each([...themePresets.map(p => p.color), '#FFFFFF', '#000000', '#FFFF00'])
  ('keeps disabled primary button text readable for %s', color => {
    const t = createThemeTokens(color)
    expect(t['--primary-disabled']).toMatch(/^#[\dA-F]{6}$/)
    expect(contrast(t['--primary-disabled-contrast'], t['--primary-disabled'])).toBeGreaterThanOrEqual(4.5)
  })
})

describe('local theme preference', () => {
  it('restores only valid saved colors and applies root tokens', () => {
    localStorage.setItem(THEME_STORAGE_KEY, '#aabbcc')
    stop = initializeColorTheme()
    expect(useColorTheme().color.value).toBe('#AABBCC')
    expect(document.documentElement.style.getPropertyValue('--primary')).toBe('#AABBCC')
    stop()
    localStorage.setItem(THEME_STORAGE_KEY, 'broken')
    stop = initializeColorTheme()
    expect(useColorTheme().color.value).toBe('#347961')
  })

  it('previews valid input and preserves it on save failure, then retries', () => {
    stop = initializeColorTheme()
    const theme = useColorTheme()
    const write = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota') })
    theme.setColor('#FFFF00')
    expect(theme.color.value).toBe('#FFFF00')
    expect(theme.saveError.value).toContain('quota')
    expect(theme.setColor('#FF')).toBe(false)
    expect(theme.color.value).toBe('#FFFF00')
    write.mockRestore()
    theme.retrySave()
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('#FFFF00')
    expect(theme.saveError.value).toBe('')
  })

  it('does not apply or save invalid input after a valid preference', () => {
    stop = initializeColorTheme()
    const theme = useColorTheme()
    theme.setColor(' #aabbcc ')
    const write = vi.spyOn(Storage.prototype, 'setItem')
    for (const invalid of ['', '#', '#FFF', '#12345G', '#12345678', 'rgb(0, 0, 0)', null]) {
      expect(theme.setColor(invalid)).toBe(false)
      expect(theme.color.value).toBe('#AABBCC')
      expect(document.documentElement.style.getPropertyValue('--primary')).toBe('#AABBCC')
      expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('#AABBCC')
    }
    expect(write).not.toHaveBeenCalled()
  })

  it('restores a normalized preference after a successful retry and reinitialization', () => {
    stop = initializeColorTheme()
    const theme = useColorTheme()
    const write = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('Storage full', 'QuotaExceededError') })
    theme.setColor(' #aabbcc ')
    expect(theme.saveError.value).not.toBe('')
    write.mockRestore()
    theme.retrySave()
    stop()
    stop = initializeColorTheme()
    expect(theme.color.value).toBe('#AABBCC')
    expect(theme.saveError.value).toBe('')
    expect(document.documentElement.style.getPropertyValue('--primary')).toBe('#AABBCC')
  })

  it('works when local storage cannot be read', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
    stop = initializeColorTheme()
    expect(useColorTheme().color.value).toBe('#347961')
    expect(useColorTheme().saveError.value).toContain('blocked')
  })

  it('syncs other tabs without writing back and removes its listener on teardown', () => {
    stop = initializeColorTheme()
    const write = vi.spyOn(Storage.prototype, 'setItem')
    window.dispatchEvent(new StorageEvent('storage', { key: THEME_STORAGE_KEY, newValue: '#7659B8', storageArea: localStorage }))
    expect(useColorTheme().color.value).toBe('#7659B8')
    expect(write).not.toHaveBeenCalled()
    window.dispatchEvent(new StorageEvent('storage', { key: THEME_STORAGE_KEY, newValue: 'broken', storageArea: localStorage }))
    expect(useColorTheme().color.value).toBe('#7659B8')
    window.dispatchEvent(new StorageEvent('storage', { key: null, newValue: null, storageArea: localStorage }))
    expect(useColorTheme().color.value).toBe('#347961')
    stop()
    window.dispatchEvent(new StorageEvent('storage', { key: THEME_STORAGE_KEY, newValue: '#7659B8', storageArea: localStorage }))
    expect(useColorTheme().color.value).toBe('#347961')
  })

  it('ignores session storage and unrelated local keys, and restores default on preference removal', () => {
    stop = initializeColorTheme()
    const theme = useColorTheme()
    theme.setColor('#0071E3')
    const write = vi.spyOn(Storage.prototype, 'setItem')
    for (const event of [
      { key: THEME_STORAGE_KEY, newValue: '#FFFFFF', storageArea: sessionStorage },
      { key: 'unrelated-key', newValue: '#FFFFFF', storageArea: localStorage },
    ]) window.dispatchEvent(new StorageEvent('storage', event))
    expect(theme.color.value).toBe('#0071E3')
    window.dispatchEvent(new StorageEvent('storage', { key: THEME_STORAGE_KEY, newValue: null, storageArea: localStorage }))
    expect(theme.color.value).toBe('#347961')
    expect(write).not.toHaveBeenCalled()
  })

  it('handles external changes when storage access becomes restricted after initialization', () => {
    const storage = window.localStorage
    stop = initializeColorTheme()
    const errors = []
    const recordError = event => { errors.push(event.error); event.preventDefault() }
    window.addEventListener('error', recordError)
    try {
      vi.spyOn(window, 'localStorage', 'get').mockImplementation(() => { throw new DOMException('Storage denied', 'SecurityError') })
      window.dispatchEvent(new StorageEvent('storage', { key: THEME_STORAGE_KEY, newValue: '#7659B8', storageArea: storage }))
      expect(errors).toEqual([])
      expect(useColorTheme().color.value).toBe('#7659B8')
    } finally {
      window.removeEventListener('error', recordError)
    }
  })
})
