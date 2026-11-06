import { ref } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { ElMessageBox } from 'element-plus'

/**
 * 表单未保存变更守卫
 * - isDirty: 是否有未保存的变更
 * - markClean(): 标记为已保存（保存成功后调用）
 * - markDirty(): 标记为有变更
 * - guard(): 返回是否可以离开（弹出确认框）
 *
 * 说明：页面加载时给表单赋值，watch 会在之后异步触发一次，
 * 容易误判成「用户改了」。所以 markClean() 后有一个静默窗口，
 * 窗口内忽略 markDirty。
 */
const QUIET_AFTER_CLEAN_MS = 800

export function useUnsavedGuard() {
  const isDirty = ref(false)
  let quietUntil = 0

  function markDirty() {
    if (Date.now() < quietUntil) return
    isDirty.value = true
  }

  function markClean() {
    isDirty.value = false
    quietUntil = Date.now() + QUIET_AFTER_CLEAN_MS
  }

  async function guard(): Promise<boolean> {
    if (!isDirty.value) return true
    try {
      await ElMessageBox.confirm(
        '有未保存的修改，确定要离开吗？',
        '未保存提示',
        { confirmButtonText: '离开', cancelButtonText: '留下', type: 'warning' }
      )
      isDirty.value = false
      return true
    } catch {
      return false
    }
  }

  onBeforeRouteLeave(async () => {
    return await guard()
  })

  onBeforeRouteUpdate(async () => {
    return await guard()
  })

  // Browser close/refresh warning
  if (typeof window !== 'undefined') {
    window.addEventListener('beforeunload', (e) => {
      if (isDirty.value) {
        e.preventDefault()
        e.returnValue = ''
      }
    })
  }

  return { isDirty, markDirty, markClean, guard }
}
