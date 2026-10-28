import { ref, watch } from 'vue'

const isDark = ref(false)
const colorTheme = ref<'warm' | 'cool'>('warm')
const themeMode = ref<'system' | 'light' | 'dark'>('system')
let initialized = false

function applyTheme() {
  document.documentElement.setAttribute('data-theme', isDark.value ? 'dark' : 'light')
  document.documentElement.setAttribute('data-color', colorTheme.value)
  if (isDark.value) {
    document.documentElement.classList.add('dark')
  } else {
    document.documentElement.classList.remove('dark')
  }
}

export function useTheme() {
  function init() {
    if (initialized) return
    initialized = true

    const storedMode = localStorage.getItem('theme-mode') as 'system' | 'light' | 'dark' | null
    const storedColor = localStorage.getItem('color-theme') as 'warm' | 'cool' | null

    themeMode.value = storedMode ?? 'system'
    colorTheme.value = storedColor ?? 'warm'

    if (themeMode.value === 'system') {
      isDark.value = window.matchMedia('(prefers-color-scheme: dark)').matches
    } else {
      isDark.value = themeMode.value === 'dark'
    }

    applyTheme()

    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', (e) => {
      if (themeMode.value === 'system') {
        isDark.value = e.matches
        applyTheme()
      }
    })
  }

  function setThemeMode(mode: 'system' | 'light' | 'dark') {
    themeMode.value = mode
    localStorage.setItem('theme-mode', mode)
    if (mode === 'system') {
      isDark.value = window.matchMedia('(prefers-color-scheme: dark)').matches
    } else {
      isDark.value = mode === 'dark'
    }
    applyTheme()
  }

  function setColorTheme(theme: 'warm' | 'cool') {
    colorTheme.value = theme
    localStorage.setItem('color-theme', theme)
    applyTheme()
  }

  function toggleDark() {
    if (themeMode.value === 'system') {
      const current = window.matchMedia('(prefers-color-scheme: dark)').matches
      setThemeMode(current ? 'light' : 'dark')
    } else {
      setThemeMode(isDark.value ? 'light' : 'dark')
    }
  }

  init()

  return {
    isDark,
    colorTheme,
    themeMode,
    setThemeMode,
    setColorTheme,
    toggleDark
  }
}
