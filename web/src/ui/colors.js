export const DEFAULT_THEME_COLOR = '#347961'
export const themePresets = [
  { name: 'MongoDB 绿', color: DEFAULT_THEME_COLOR },
  { name: '经典蓝', color: '#0071E3' },
  { name: '雾岚紫', color: '#7659B8' },
  { name: '石墨灰', color: '#526071' },
]

export function normalizeThemeColor(value) {
  return typeof value === 'string' && /^#[\da-f]{6}$/i.test(value.trim()) ? value.trim().toUpperCase() : null
}

const rgb = color => [1, 3, 5].map(index => parseInt(color.slice(index, index + 2), 16))
function mix(color, target, weight) {
  const other = rgb(target)
  return '#' + rgb(color).map((channel, index) => Math.round(channel * (1 - weight) + other[index] * weight)
    .toString(16).padStart(2, '0')).join('').toUpperCase()
}
function luminance(color) {
  return rgb(color).map(channel => {
    const value = channel / 255
    return value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4
  }).reduce((sum, value, index) => sum + value * [.2126, .7152, .0722][index], 0)
}
function contrast(first, second) {
  const a = luminance(first), b = luminance(second)
  return (Math.max(a, b) + .05) / (Math.min(a, b) + .05)
}
function readable(color, backgrounds, minimum) {
  for (let step = 0; step <= 100; step++) {
    const candidate = mix(color, '#1D2738', step / 100)
    if (backgrounds.every(background => contrast(candidate, background) >= minimum)) return candidate
  }
  return '#1D2738'
}

export function createThemeTokens(value) {
  const color = normalizeThemeColor(value)
  if (!color) throw new TypeError('Invalid theme color')
  const soft = mix(color, '#FFFFFF', .93)
  const softHover = mix(color, '#FFFFFF', .86)
  const backgrounds = ['#FFFFFF', '#F4F6F8', '#E9E9EB', '#FFF7ED', soft, softHover]
  const text = readable(color, backgrounds, 4.5)
  const mark = readable(color, backgrounds, 3)
  const disabled = mix(color, '#FFFFFF', .65)
  const foreground = contrast(color, '#FFFFFF') >= 4.5 ? '#FFFFFF' : '#000000'
  const hoverTarget = foreground === '#FFFFFF' ? '#000000' : '#FFFFFF'
  const tokens = {
    '--primary': color,
    '--primary-text': text,
    '--primary-contrast': foreground,
    '--primary-hover': mix(color, hoverTarget, .12),
    '--primary-active': mix(color, hoverTarget, .22),
    '--primary-disabled': disabled,
    '--primary-disabled-contrast': readable(color, [disabled], 4.5),
    '--primary-soft': soft,
    '--primary-soft-hover': softHover,
    '--primary-line': mix(mark, '#FFFFFF', .55),
    '--primary-mark': mark,
    '--focus-color': mark,
    '--el-color-primary': text,
    '--el-color-primary-rgb': rgb(text).join(', '),
    '--el-color-primary-dark-2': readable(mix(text, '#000000', .2), backgrounds, 4.5),
  }
  for (const level of [3, 5, 7, 8, 9]) tokens[`--el-color-primary-light-${level}`] = mix(text, '#FFFFFF', level / 10)
  return tokens
}
