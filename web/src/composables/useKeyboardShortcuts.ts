import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'

interface ShortcutDef {
  key: string
  description: string
  action: () => void
  /** 是否在 input/textarea 中禁用（默认 true） */
  disableInInput?: boolean
}

const showHelp = ref(false)

export function useKeyboardShortcuts() {
  const router = useRouter()
  let keySequence = ''
  let sequenceTimer: ReturnType<typeof setTimeout> | null = null

  const shortcuts: ShortcutDef[] = [
    {
      key: '/',
      description: '聚焦搜索框',
      action: () => {
        const input = document.querySelector<HTMLInputElement>('.search-input input, .el-input__inner')
        input?.focus()
      }
    },
    {
      key: '?',
      description: '打开快捷键帮助',
      action: () => { showHelp.value = true }
    },
    {
      key: 'g d',
      description: '跳转到仪表盘',
      action: () => { router.push({ name: 'dashboard' }) }
    },
    {
      key: 'g m',
      description: '跳转到记忆',
      action: () => { router.push({ name: 'memory' }) }
    },
    {
      key: 'g p',
      description: '跳转到人设',
      action: () => { router.push({ name: 'persona' }) }
    },
    {
      key: 'g e',
      description: '跳转到表情包',
      action: () => { router.push({ name: 'emoji' }) }
    },
    {
      key: 'g c',
      description: '跳转到聊天记录',
      action: () => { router.push({ name: 'chat' }) }
    }
  ]

  function isInputFocused(): boolean {
    const el = document.activeElement
    if (!el) return false
    const tag = el.tagName.toLowerCase()
    return tag === 'input' || tag === 'textarea' || tag === 'select' || (el as HTMLElement).isContentEditable
  }

  function handleKeydown(e: KeyboardEvent) {
    if (e.key === 'Escape') {
      showHelp.value = false
      return
    }

    if (isInputFocused()) return

    const key = e.key.toLowerCase()

    // Handle two-key sequences (g + x)
    if (sequenceTimer) {
      clearTimeout(sequenceTimer)
      sequenceTimer = null
      const combo = `${keySequence} ${key}`
      keySequence = ''
      const shortcut = shortcuts.find(s => s.key === combo)
      if (shortcut) {
        e.preventDefault()
        shortcut.action()
      }
      return
    }

    if (key === 'g') {
      keySequence = 'g'
      sequenceTimer = setTimeout(() => {
        keySequence = ''
        sequenceTimer = null
      }, 800)
      return
    }

    // Single-key shortcuts
    const shortcut = shortcuts.find(s => s.key === key)
    if (shortcut) {
      e.preventDefault()
      shortcut.action()
    }
  }

  onMounted(() => {
    window.addEventListener('keydown', handleKeydown)
  })

  onBeforeUnmount(() => {
    window.removeEventListener('keydown', handleKeydown)
    if (sequenceTimer) clearTimeout(sequenceTimer)
  })

  return { showHelp, shortcuts }
}
