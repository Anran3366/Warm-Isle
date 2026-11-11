<template>
  <div class="persona-page">
    <div v-if="loading" class="skeleton-layout">
      <el-skeleton class="card sidebar-skel" :rows="6" animated />
      <el-skeleton class="card editor-skel" :rows="12" animated />
    </div>

    <template v-else-if="!error">
      <div class="card account-bar">
        <span class="bar-label">配置账号</span>
        <el-select v-model="selectedBot" size="small" style="width: 220px" @change="loadData">
          <el-option
            v-for="b in bots"
            :key="b.id"
            :value="b.id"
            :label="(b.name || ('账号 ' + b.id)) + (b.qqId ? ' · ' + b.qqId : '')"
          />
        </el-select>
        <span class="bar-hint">人设的「启用」只对当前选择的账号生效</span>
        <div class="bar-spacer" />
        <el-button size="small" @click="loadBots">刷新账号</el-button>
        <el-button size="small" type="primary" plain @click="openSystemPrompt">查看系统提示词</el-button>
      </div>

      <el-dialog v-model="systemVisible" title="系统提示词（只读）" width="720px">
        <p class="bar-hint">下面是拼进模型的固定规则 + 当前账号生效的人设，不能直接编辑。</p>
        <el-input v-model="systemText" type="textarea" :rows="20" readonly />
        <template #footer>
          <el-button @click="copySystemPrompt">复制</el-button>
          <el-button type="primary" @click="systemVisible = false">关闭</el-button>
        </template>
      </el-dialog>
      <div class="persona-layout">
        <div class="card sidebar">
          <div class="sidebar-head">
            <h3 class="sidebar-title">人设列表</h3>
            <el-button type="primary" size="small" @click="showNewDialog = true">
              <el-icon><Plus /></el-icon>新建
            </el-button>
          </div>
          <div v-if="personas.length === 0" class="empty-state">
            <el-icon :size="36" color="var(--c-text-2)"><User /></el-icon>
            <p>还没有人设，点击上方新建一个吧</p>
          </div>
          <div
            v-for="p in personas"
            :key="p.id"
            class="persona-item"
            :class="{ active: selectedId === p.id }"
            @click="selectPersona(p)"
          >
            <div class="persona-name">
              {{ p.name }}
              <el-tag v-if="p.active" type="success" size="small" effect="plain" round>当前</el-tag>
            </div>
            <div class="persona-actions">
              <el-button v-if="!p.active" text size="small" type="primary" @click.stop="activate(p.id)">启用</el-button>
              <el-button text size="small" type="danger" @click.stop="confirmDelete(p)">删除</el-button>
            </div>
          </div>

          <el-divider />
          <div class="sidebar-section">
            <h4 class="sidebar-subtitle">Prompt 用量</h4>
            <el-button text size="small" :loading="previewLoading" @click="loadPreview">
              <el-icon><Refresh /></el-icon>刷新预览
            </el-button>
          </div>
          <div v-if="preview && !previewEmpty" class="token-breakdown">
            <div ref="pieRef" class="token-pie" />
            <div v-for="seg in tokenSegments" :key="seg.label" class="token-row">
              <span class="token-label">
                {{ seg.label }}
                <el-button
                  text size="small" class="copy-btn"
                  :aria-label="'复制' + seg.label + '内容'"
                  @click="doCopy(seg.text)"
                ><el-icon :size="12"><CopyDocument /></el-icon></el-button>
              </span>
              <div class="token-bar-bg">
                <div class="token-bar" :style="{ width: seg.pct + '%', background: seg.color }" />
              </div>
              <span class="token-pct num">{{ seg.pct.toFixed(0) }}%</span>
            </div>
            <div class="token-total">
              共 <b class="num">{{ preview.totalTokens.toLocaleString() }}</b> tokens
            </div>
          </div>
          <div v-else-if="previewError" class="token-empty preview-error">
            <span>{{ previewError }}</span>
            <el-button text size="small" type="primary" @click="loadPreview">重试</el-button>
          </div>
          <div v-else-if="previewEmpty" class="token-empty preview-empty">
            <span>服务刚重启，还没有真实回复的 Prompt 预览。先在 QQ 里聊一句，再点「刷新预览」。</span>
            <el-button text size="small" type="primary" @click="loadPreview">刷新预览</el-button>
          </div>
          <div v-else class="token-empty">点击「刷新预览」查看 token 分布</div>
        </div>

        <div class="card editor-area">
          <template v-if="selected">
            <div class="editor-head">
              <el-input v-model="editName" placeholder="人设名称" class="name-input" />
              <div class="editor-tabs">
                <el-radio-group v-model="editorTab" size="small">
                  <el-radio-button value="edit">编辑</el-radio-button>
                  <el-radio-button value="preview">预览</el-radio-button>
                  <el-radio-button value="split">分屏</el-radio-button>
                </el-radio-group>
                <el-button type="primary" size="small" :loading="saving" @click="save">保存</el-button>
                <span v-if="isDirty" class="unsaved-dot" title="有未保存的修改" />
              </div>
            </div>
            <div class="editor-body" :class="'tab-' + editorTab">
              <div v-if="editorTab !== 'preview'" class="editor-pane">
                <el-input
                  v-model="editContent"
                  type="textarea"
                  :autosize="{ minRows: 16, maxRows: 30 }"
                  placeholder="用 Markdown 写人设描述…"
                  class="md-input"
                />
              </div>
              <div v-if="editorTab !== 'edit'" class="preview-pane">
                <div class="md-preview" v-html="renderMarkdown(editContent)" />
              </div>
            </div>
          </template>
          <div v-else class="empty-state large">
            <el-icon :size="48" color="var(--c-text-2)"><EditPen /></el-icon>
            <p>选择左侧人设进行编辑，或新建一个</p>
          </div>
        </div>
      </div>
    </template>

    <div v-else class="card error-card">
      <el-icon :size="40" color="var(--c-danger)"><WarningFilled /></el-icon>
      <p>加载失败：{{ error }}</p>
      <el-button type="primary" @click="loadData">重试</el-button>
    </div>

    <el-dialog v-model="showNewDialog" title="新建人设" width="440px">
      <el-form label-width="80px">
        <el-form-item label="名称">
          <el-input v-model="newName" placeholder="例如：小满" />
        </el-form-item>
        <el-form-item label="初始内容">
          <el-input
            v-model="newContent"
            type="textarea"
            :rows="5"
            placeholder="留空会自动生成「# 名称」占位，创建后可继续编辑"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showNewDialog = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="create">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'PersonaView' })
import { ref, computed, onMounted, onBeforeUnmount, onActivated, nextTick, watch } from 'vue'
import * as echarts from 'echarts/core'
import { PieChart } from 'echarts/charts'
import { TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { Plus, User, Refresh, EditPen, WarningFilled, CopyDocument } from '@element-plus/icons-vue'

echarts.use([PieChart, TooltipComponent, LegendComponent, CanvasRenderer])
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api/client'
import type { Persona, PromptPreview } from '@/api/types'
import { copyToClipboard } from '@/utils/format'
import { useUnsavedGuard } from '@/composables/useUnsavedGuard'

const { isDirty, markDirty, markClean } = useUnsavedGuard()

const personas = ref<Persona[]>([])
const bots = ref<Array<{ id: number; name?: string; qqId?: number }>>([])
const selectedBot = ref<number>(Number(localStorage.getItem('activeBotId')) || 0)

const systemVisible = ref(false)
const systemText = ref('')

async function openSystemPrompt() {
  systemVisible.value = true
  systemText.value = '加载中…'
  try {
    const r = await api<{
      personaName?: string
      persona?: string
      rules?: string
      time?: string
    }>('/prompt/system')
    systemText.value =
      '【当前时间】' + (r.time ?? '') + '\n\n' +
      '【人设：' + (r.personaName ?? '') + '】\n' + (r.persona ?? '') + '\n\n' +
      '【固定规则】\n' + (r.rules ?? '')
  } catch (e: any) {
    systemText.value = '加载失败：' + (e?.message ?? '未知错误')
  }
}

async function copySystemPrompt() {
  const ok = await copyToClipboard(systemText.value)
  ElMessage[ok ? 'success' : 'error'](ok ? '已复制' : '复制失败')
}

async function loadBots() {
  try {
    const r = await api<{ items: Array<{ id: number; name?: string; qqId?: number }> }>('/account/list')
    bots.value = r.items ?? []
    if (!bots.value.some(b => b.id === selectedBot.value) && bots.value.length) {
      selectedBot.value = bots.value[0].id
    }
  } catch {
    bots.value = []
  }
}
const selected = ref<Persona | null>(null)
const selectedId = ref('')
const editName = ref('')
const editContent = ref('')
const editorTab = ref<'edit' | 'preview' | 'split'>('split')
const loading = ref(true)
const saving = ref(false)
const creating = ref(false)
const error = ref('')
const showNewDialog = ref(false)
const newName = ref('')
const newContent = ref('')
const preview = ref<PromptPreview | null>(null)
const previewLoading = ref(false)
const previewError = ref('')
const pieRef = ref<HTMLElement | null>(null)
let pieChart: echarts.ECharts | null = null
let themeObserver: MutationObserver | null = null

const selectedPersona = computed(() => personas.value.find(p => p.id === selectedId.value))

const previewEmpty = computed(() => {
  const p = preview.value
  if (!p) return false
  return !p.persona && !p.facts && !p.memories && !p.history && !p.user && p.totalTokens === 0
})

const tokenSegments = computed(() => {
  if (!preview.value) return []
  const total = preview.value.totalTokens || 1
  const segments = [
    { label: '人设', text: preview.value.persona, color: '#e8927c' },
    { label: '事实', text: preview.value.facts, color: '#7aa874' },
    { label: '记忆', text: preview.value.memories, color: '#d9a441' },
    { label: '历史', text: preview.value.history, color: '#6b9ac4' },
  ]
  const totalLen = segments.reduce((s, seg) => s + seg.text.length, 0) || 1
  return segments.map(seg => ({
    ...seg,
    pct: (seg.text.length / totalLen) * 100
  }))
})

function selectPersona(p: Persona) {
  selected.value = p
  selectedId.value = p.id
  editName.value = p.name
  editContent.value = p.content
  markClean()
}

function renderMarkdown(md: string): string {
  if (!md) return '<p class="md-empty">暂无内容</p>'
  let html = md
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
  html = html.replace(/^### (.+)$/gm, '<h3>$1</h3>')
  html = html.replace(/^## (.+)$/gm, '<h2>$1</h2>')
  html = html.replace(/^# (.+)$/gm, '<h1>$1</h1>')
  html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
  html = html.replace(/\*(.+?)\*/g, '<em>$1</em>')
  html = html.replace(/^- (.+)$/gm, '<li>$1</li>')
  html = html.replace(/(<li>.*<\/li>\n?)+/g, (m) => `<ul>${m}</ul>`)
  const lines = html.split('\n')
  const result: string[] = []
  let inUl = false
  for (const line of lines) {
    if (line.startsWith('<ul>')) { inUl = true; result.push(line); continue }
    if (line === '</ul>') { inUl = false; result.push(line); continue }
    if (inUl) { result.push(line); continue }
    if (/^<(h[1-3]|ul|ol|li)/.test(line) || line.trim() === '') {
      result.push(line)
    } else {
      result.push(`<p>${line}</p>`)
    }
  }
  return result.join('\n')
}

async function loadData() {
  loading.value = true
  error.value = ''
  try {
    const list = await api<Persona[]>('/personas?botId=' + selectedBot.value)
    personas.value = list
    const active = list.find(p => p.active)
    if (active) selectPersona(active)
    else if (list.length) selectPersona(list[0])
  } catch (e: any) {
    error.value = e?.message ?? '未知错误'
  } finally {
    loading.value = false
  }
}

async function loadPreview() {
  previewLoading.value = true
  previewError.value = ''
  try {
    preview.value = await api<PromptPreview>('/prompt/preview')
    await nextTick()
    renderPieChart()
  } catch (e: any) {
    preview.value = null
    previewError.value = e?.message ? `预览加载失败：${e.message}` : '预览加载失败'
  } finally {
    previewLoading.value = false
  }
}

function renderPieChart() {
  if (!pieRef.value || !preview.value) return
  if (!pieChart) {
    pieChart = echarts.init(pieRef.value)
  }
  const isDark = document.documentElement.getAttribute('data-theme') === 'dark'
  const data = tokenSegments.value.map(s => ({
    name: s.label, value: s.text.length, itemStyle: { color: s.color }
  }))
  pieChart.setOption({
    animationDuration: 600,
    tooltip: {
      trigger: 'item',
      backgroundColor: isDark ? 'rgba(37,34,32,0.96)' : 'rgba(255,255,255,0.96)',
      borderColor: isDark ? '#3a3530' : '#e8e1d9',
      textStyle: { color: isDark ? '#e8e1d9' : '#2f2a25', fontSize: 12 },
      formatter(p: any) {
        return `${p.name}：<b>${p.percent.toFixed(0)}%</b>`
      }
    },
    series: [{
      type: 'pie',
      radius: ['45%', '72%'],
      center: ['50%', '50%'],
      data,
      label: { show: false },
      emphasis: {
        scaleSize: 4
      }
    }]
  })
}

watch(preview, () => {
  nextTick(() => renderPieChart())
})

watch([editName, editContent], () => markDirty())

async function save() {
  if (!selected.value) return
  saving.value = true
  try {
    await api(`/personas/${selected.value.id}`, {
      method: 'PUT',
      body: { name: editName.value, content: editContent.value }
    })
    selected.value.name = editName.value
    selected.value.content = editContent.value
    const idx = personas.value.findIndex(p => p.id === selected.value!.id)
    if (idx >= 0) personas.value[idx] = { ...selected.value }
    markClean()
    ElMessage.success('已保存')
  } catch (e: any) {
    ElMessage.error(e?.message ?? '保存失败')
  } finally {
    saving.value = false
  }
}

async function activate(id: string) {
  try {
    await api(`/personas/${id}/activate`, { method: 'POST' })
    personas.value.forEach(p => { p.active = p.id === id })
    ElMessage.success('已切换人设')
  } catch (e: any) {
    ElMessage.error(e?.message ?? '切换失败')
  }
}

async function confirmDelete(p: Persona) {
  try {
    await ElMessageBox.confirm(`确定删除人设「${p.name}」？`, '删除确认', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
    const wasActive = p.active
    await api(`/personas/${p.id}`, { method: 'DELETE' })
    personas.value = personas.value.filter(x => x.id !== p.id)
    if (selectedId.value === p.id) {
      selected.value = null
      selectedId.value = ''
    }
    if (wasActive) {
      ElMessage.warning(
        personas.value.length > 0
          ? '已删除当前人设，请激活另一个人设'
          : '已删除最后一个人设，机器人会回退到配置文件里的人设'
      )
    } else {
      ElMessage.success('已删除')
    }
  } catch { /* cancelled */ }
}

async function create() {
  const name = newName.value.trim()
  if (!name) { ElMessage.warning('请输入名称'); return }
  creating.value = true
  try {
    const content = newContent.value.trim() || `# ${name}\n\n`
    const created = await api<Persona>('/personas', {
      method: 'POST',
      body: { name, content }
    })
    if (!created?.id) {
      ElMessage.error('创建失败：后端没有返回人设')
      return
    }
    personas.value.push(created)
    selectPersona(created)
    showNewDialog.value = false
    newName.value = ''
    newContent.value = ''
    ElMessage.success('已创建')
  } catch (e: any) {
    ElMessage.error(e?.message ?? '创建失败')
  } finally {
    creating.value = false
  }
}

async function doCopy(text: string) {
  const ok = await copyToClipboard(text)
  ElMessage[ok ? 'success' : 'error'](ok ? '已复制' : '复制失败')
}

onMounted(() => {
  loadBots()
  loadData()
  loadPreview()
  window.addEventListener('resize', handleResize)
  themeObserver = new MutationObserver(() => renderPieChart())
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
})

function handleResize() {
  pieChart?.resize()
}

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  themeObserver?.disconnect()
  pieChart?.dispose()
  pieChart = null
})

onActivated(() => {
  if (tokenSegments.value.length > 0) {
    nextTick(() => renderPieChart())
  }
})
</script>

<style scoped>
.account-bar { display: flex; align-items: center; gap: 10px; padding: 12px 16px; margin-bottom: 14px; }
.bar-label { font-size: 13px; color: var(--c-text-2); }
.bar-hint { font-size: 12px; color: var(--c-text-2); }
.bar-spacer { flex: 1; }
.persona-page { display: flex; flex-direction: column; gap: 16px; }
.skeleton-layout { display: grid; grid-template-columns: 280px 1fr; gap: 16px; }
.persona-layout { display: grid; grid-template-columns: 280px 1fr; gap: 16px; min-height: 560px; }
.sidebar { display: flex; flex-direction: column; overflow: auto; }
.sidebar-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.sidebar-title { margin: 0; font-size: 15px; font-weight: 600; }
.sidebar-section { display: flex; align-items: center; justify-content: space-between; }
.sidebar-subtitle { margin: 0; font-size: 13px; font-weight: 600; }
.persona-item {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 12px; border-radius: 10px; cursor: pointer;
  transition: background 0.15s;
}
.persona-item:hover { background: var(--c-primary-weak); }
.persona-item.active { background: var(--c-primary-weak); }
.persona-name { font-size: 14px; display: flex; align-items: center; gap: 6px; }
.persona-actions { display: flex; gap: 2px; }
.empty-state { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 32px 16px; color: var(--c-text-2); font-size: 13px; }
.empty-state.large { padding: 64px 16px; }
.editor-area { display: flex; flex-direction: column; }
.editor-head { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.name-input { max-width: 200px; }
.editor-tabs { display: flex; align-items: center; gap: 8px; margin-left: auto; }
.unsaved-dot {
  width: 8px; height: 8px; border-radius: 50%;
  background: var(--c-warn); flex-shrink: 0;
}
.editor-body { display: grid; gap: 12px; flex: 1; }
.editor-body.tab-edit { grid-template-columns: 1fr; }
.editor-body.tab-preview { grid-template-columns: 1fr; }
.editor-body.tab-split { grid-template-columns: 1fr 1fr; }
.editor-pane, .preview-pane { min-height: 360px; }
.md-input :deep(textarea) {
  font-family: 'SF Mono', 'Fira Code', 'Consolas', monospace;
  font-size: 13px; line-height: 1.7;
}
.preview-pane {
  overflow: auto; padding: 16px; background: var(--c-bg);
  border-radius: 10px; border: 1px solid var(--c-border);
}
.md-preview { font-size: 14px; line-height: 1.8; }
.md-preview :deep(h1) { font-size: 20px; margin: 0 0 12px; border-bottom: 1px solid var(--c-border); padding-bottom: 8px; }
.md-preview :deep(h2) { font-size: 17px; margin: 16px 0 8px; }
.md-preview :deep(h3) { font-size: 15px; margin: 12px 0 6px; }
.md-preview :deep(p) { margin: 6px 0; }
.md-preview :deep(ul) { padding-left: 20px; margin: 6px 0; }
.md-preview :deep(li) { margin: 2px 0; }
.md-preview :deep(.md-empty) { color: var(--c-text-2); font-style: italic; }
.token-breakdown { display: flex; flex-direction: column; gap: 8px; margin-top: 8px; }
.token-pie { width: 100%; height: 160px; }
.token-row { display: flex; align-items: center; gap: 8px; }
.token-label { font-size: 12px; color: var(--c-text-2); width: 56px; flex-shrink: 0; display: flex; align-items: center; gap: 2px; }
.token-label .copy-btn { padding: 2px; opacity: 0.4; transition: opacity 0.15s; min-height: 0; }
.token-label .copy-btn:hover { opacity: 1; }
.token-bar-bg { flex: 1; height: 8px; background: var(--c-bg); border-radius: 4px; overflow: hidden; }
.token-bar { height: 100%; border-radius: 4px; transition: width 0.3s; }
.token-pct { font-size: 12px; color: var(--c-text-2); width: 36px; text-align: right; }
.token-total { font-size: 12px; color: var(--c-text-2); text-align: right; margin-top: 4px; }
.token-empty { font-size: 12px; color: var(--c-text-2); padding: 12px 0; }
.preview-empty { display: flex; flex-direction: column; align-items: flex-start; gap: 6px; line-height: 1.6; }
.preview-error { display: flex; align-items: center; justify-content: space-between; gap: 8px; color: var(--c-danger); }
.error-card { display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 48px; color: var(--c-text-2); }

@media (max-width: 768px) {
  .persona-layout { grid-template-columns: 1fr; }
  .sidebar { max-height: 300px; }
  .editor-body.tab-split { grid-template-columns: 1fr; }
  .editor-head { flex-direction: column; align-items: stretch; }
  .name-input { max-width: 100%; }
  .editor-tabs { margin-left: 0; justify-content: space-between; }
}
</style>
