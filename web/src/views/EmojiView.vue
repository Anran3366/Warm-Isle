<template>
  <div class="emoji-page">
    <div v-if="loading" class="skeleton-grid">
      <el-skeleton v-for="i in 8" :key="i" class="card emoji-skel" :rows="1" animated />
    </div>

    <template v-else-if="!error">
      <div class="card upload-card">
        <el-upload
          ref="uploadRef"
          drag
          multiple
          accept="image/png,image/jpeg,image/gif,image/webp"
          :auto-upload="false"
          :on-change="handleFileChange"
          :show-file-list="false"
          class="upload-area"
        >
          <el-icon :size="36" color="var(--c-primary)"><UploadFilled /></el-icon>
          <div class="upload-text">拖拽图片到这里，或 <em>点击上传</em></div>
          <div class="upload-hint">支持 jpg / png / gif / webp，最大 10MB；上传后自动识别描述和标签</div>
        </el-upload>
        <div class="upload-tip">
          <el-icon><InfoFilled /></el-icon>
          {{ mockMode ? '聊天里收到的表情包会自动入库' : '上传后可直接在聊天里使用' }}
        </div>
      </div>

      <div class="card filter-bar">
        <div class="filter-left">
          <el-radio-group v-if="mockMode" v-model="sourceFilter" size="small">
            <el-radio-button value="all">全部 {{ emojis.length }}</el-radio-button>
            <el-radio-button value="upload">手动 {{ uploadCount }}</el-radio-button>
            <el-radio-button value="auto">自动 {{ autoCount }}</el-radio-button>
          </el-radio-group>
          <el-radio-group v-model="statusFilter" size="small" @change="loadData">
            <el-radio-button value="all">全部状态</el-radio-button>
            <el-radio-button value="pending">待审核</el-radio-button>
            <el-radio-button value="approved">已通过</el-radio-button>
          </el-radio-group>
          <el-select
            v-if="tagOptions.length"
            v-model="tagFilter"
            clearable
            size="small"
            placeholder="按标签筛选"
            class="tag-filter"
          >
            <el-option
              v-for="[tag, count] in tagOptions"
              :key="tag"
              :label="`${tag} (${count})`"
              :value="tag"
            />
          </el-select>
          <span v-if="!mockMode" class="muted">共 {{ emojis.length }} 张</span>
        </div>
        <div class="filter-right">
          <el-switch v-model="batchMode" active-text="批量操作" size="small" />
        </div>
      </div>

      <div v-if="batchMode && selectedIds.size > 0" class="card batch-bar">
        <span>已选 <b>{{ selectedIds.size }}</b> 项</span>
        <el-button type="danger" size="small" @click="batchDelete">批量删除</el-button>
        <el-button size="small" @click="openBatchEdit">批量改标签</el-button>
        <el-button size="small" @click="selectedIds.clear()">取消选择</el-button>
      </div>

      <EmptyState
        v-if="filteredEmojis.length === 0"
        title="还没有表情包"
        :desc="mockMode ? '上传一些，或者聊天时收到的会自动入库' : '上传一些图片，聊天时就能用了'"
        :illustration="emptyEmoji"
      >
        <template #action>
          <el-button type="primary" @click="uploadRef?.$el?.querySelector('input')?.click()">上传表情</el-button>
        </template>
      </EmptyState>

      <div v-else class="emoji-grid">
        <div
          v-for="em in filteredEmojis" :key="em.id"
          class="card emoji-card"
          :class="{ disabled: !em.enabled, selected: selectedIds.has(em.id) }"
          draggable="true"
          @dragstart="onDragStart($event, em)"
          @dragover.prevent
          @drop="onDrop($event, em)"
        >
          <div class="emoji-img-wrap">
            <el-image :src="em.url" fit="cover" class="emoji-img" :preview-src-list="[em.url]">
              <template #error>
                <div class="img-error">
                  <el-icon :size="22"><PictureRounded /></el-icon>
                  <span>图片加载失败</span>
                </div>
              </template>
            </el-image>
            <div v-if="batchMode" class="select-check" @click.stop="toggleSelect(em.id)">
              <el-icon v-if="selectedIds.has(em.id)" :size="18" color="var(--c-primary)"><Select /></el-icon>
              <div v-else class="check-empty" />
            </div>
            <div v-else class="emoji-overlay">
              <el-switch v-model="em.enabled" size="small" :aria-label="'切换' + em.emoTags.join('') + '的启用状态'" @change="toggleEnabled(em)" />
            </div>
            <div v-if="em.status === 'pending'" class="pending-badge">待审</div>
            <div v-if="mockMode" class="source-badge" :class="'source-' + em.source">
              {{ em.source === 'upload' ? '手动' : '自动' }}
            </div>
          </div>
          <div class="emoji-info">
            <div class="emoji-tags">
              <el-tag v-for="t in em.emoTags" :key="t" size="small" effect="plain" class="tag-emo">{{ t }}</el-tag>
            </div>
            <div class="emoji-tags">
              <el-tag v-for="t in em.sceneTags" :key="t" size="small" type="info" effect="plain" class="tag-scene">{{ t }}</el-tag>
            </div>
            <div v-if="em.description" class="emoji-desc" :title="em.description">{{ em.description }}</div>
          </div>
          <div class="emoji-actions">
            <el-button v-if="em.status === 'pending'" text size="small" type="success" @click="approve(em)">通过</el-button>
            <el-button v-if="em.status === 'pending'" text size="small" type="warning" @click="reject(em)">删除</el-button>
            <el-button text size="small" type="primary" @click="openEdit(em)">编辑</el-button>
            <el-button text size="small" @click="testSend(em)">试发</el-button>
            <el-button text size="small" type="danger" @click="removeEmoji(em)">删除</el-button>
          </div>
        </div>
      </div>
    </template>

    <div v-else class="card error-card">
      <el-icon :size="40" color="var(--c-danger)"><WarningFilled /></el-icon>
      <p>加载失败：{{ error }}</p>
      <el-button type="primary" @click="loadData">重试</el-button>
    </div>

    <el-dialog v-model="showEdit" title="编辑标签" width="440px">
      <el-form v-if="editForm" label-width="90px">
        <el-form-item label="表情标签">
          <el-select
            v-model="editForm.emoTags"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="输入后回车"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="场景标签">
          <el-select
            v-model="editForm.sceneTags"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="输入后回车"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="editForm.description"
            type="textarea"
            :rows="3"
            placeholder="画面内容 + 情绪/用途，例如：两只小猫贴在一起亲亲，表达亲昵和爱意"
          />
          <div class="hint">发送表情时会参考这句话挑选，写得越具体越准</div>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="editForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showEdit = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="showBatchEdit" title="批量改标签" width="440px">
      <el-form label-width="90px">
        <el-form-item label="追加表情标签">
          <el-select
            v-model="batchEmoTags"
            multiple filterable allow-create default-first-option
            placeholder="输入后回车"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="追加场景标签">
          <el-select
            v-model="batchSceneTags"
            multiple filterable allow-create default-first-option
            placeholder="输入后回车"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showBatchEdit = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveBatchEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'EmojiView' })
import { ref, computed, onMounted } from 'vue'
import { UploadFilled, PictureRounded, WarningFilled, InfoFilled, Select } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadFile } from 'element-plus'
import { api, isMock } from '@/api/client'
import { normalizeEmoji, normalizeEmojis } from '@/api/adapters'
import type { Emoji, Page } from '@/api/types'
import EmptyState from '@/components/EmptyState.vue'
import emptyEmoji from '@/assets/illustrations/empty-emoji.svg'

const emojis = ref<Emoji[]>([])
const statusFilter = ref('all')
const loading = ref(true)
const error = ref('')
const showEdit = ref(false)
const saving = ref(false)
const sourceFilter = ref<'all' | 'upload' | 'auto'>('all')
const mockMode = isMock('/emoji')
const tagStats = ref<Record<string, number>>({})
const tagFilter = ref('')
const editForm = ref<{ id: string; emoTags: string[]; sceneTags: string[]; description: string; enabled: boolean } | null>(null)
const uploadRef = ref()
const batchMode = ref(false)
const selectedIds = ref<Set<string>>(new Set())
const showBatchEdit = ref(false)
const batchEmoTags = ref<string[]>([])
const batchSceneTags = ref<string[]>([])
let dragId: string | null = null

const uploadCount = computed(() => emojis.value.filter(e => e.source === 'upload').length)
const autoCount = computed(() => emojis.value.filter(e => e.source === 'auto').length)
const tagOptions = computed(() => Object.entries(tagStats.value).sort((a, b) => b[1] - a[1]))

const filteredEmojis = computed(() => {
  let list = emojis.value
  if (mockMode && sourceFilter.value !== 'all') {
    list = list.filter(e => e.source === sourceFilter.value)
  }
  if (tagFilter.value) {
    list = list.filter(e => e.emoTags.includes(tagFilter.value))
  }
  return list
})

function recomputeTagStats() {
  const stats: Record<string, number> = {}
  for (const emoji of emojis.value) {
    for (const tag of emoji.emoTags) {
      stats[tag] = (stats[tag] ?? 0) + 1
    }
  }
  tagStats.value = stats
}

async function loadData() {
  loading.value = true
  error.value = ''
  try {
    const page = await api<Page<unknown> & { tags?: Record<string, number> }>('/emoji?page=1&size=100&status=' + statusFilter.value)
    emojis.value = normalizeEmojis(page.items)
    if (page.tags && Object.keys(page.tags).length > 0) {
      tagStats.value = page.tags
    } else {
      recomputeTagStats()
    }
  } catch (e: any) {
    error.value = e?.message ?? '未知错误'
  } finally {
    loading.value = false
  }
}

async function handleFileChange(file: UploadFile) {
  if (!file.raw) return
  if (file.raw.size > 10 * 1024 * 1024) {
    ElMessage.error('文件超过 10MB')
    return
  }
  const allowed = ['image/png', 'image/jpeg', 'image/gif', 'image/webp']
  if (file.raw.type && !allowed.includes(file.raw.type)) {
    ElMessage.error('只支持 png / jpg / gif / webp')
    return
  }
  const formData = new FormData()
  formData.append('file', file.raw)
  formData.append('emoTags', '')
  formData.append('sceneTags', '')
  formData.append('autoAnalyze', 'true')
  try {
    const res = await api<{ id?: string; item?: unknown }>('/emoji', { method: 'POST', formData })
    const created = res.item
      ? normalizeEmoji(res.item)
      : normalizeEmoji({ id: res.id, url: res.id ? `/api/emoji/${res.id}/file` : '' })
    if (!created.id) {
      ElMessage.error('上传失败：后端没有返回表情 id')
      return
    }
    emojis.value.unshift(created)
    recomputeTagStats()
    ElMessage.success('上传成功，已自动识别描述和标签')
  } catch (e: any) {
    ElMessage.error(e?.message ?? '上传失败')
  }
}

function openEdit(em: Emoji) {
  editForm.value = {
    id: em.id,
    emoTags: [...em.emoTags],
    sceneTags: [...em.sceneTags],
    description: em.description ?? '',
    enabled: em.enabled
  }
  showEdit.value = true
}

async function saveEdit() {
  if (!editForm.value) return
  saving.value = true
  try {
    await api(`/emoji/${editForm.value.id}`, {
      method: 'PUT',
      body: {
        emoTags: editForm.value.emoTags.join(','),
        sceneTags: editForm.value.sceneTags.join(','),
        description: editForm.value.description,
        enabled: editForm.value.enabled
      }
    })
    const idx = emojis.value.findIndex(e => e.id === editForm.value!.id)
    if (idx >= 0) {
      emojis.value[idx] = { ...emojis.value[idx], ...editForm.value }
    }
    recomputeTagStats()
    showEdit.value = false
    ElMessage.success('已保存')
  } catch (e: any) {
    ElMessage.error(e?.message ?? '保存失败')
  } finally {
    saving.value = false
  }
}

async function toggleEnabled(em: Emoji) {
  try {
    await api(`/emoji/${em.id}`, { method: 'PUT', body: { enabled: em.enabled } })
  } catch {
    em.enabled = !em.enabled
    ElMessage.error('更新失败')
  }
}

async function testSend(em: Emoji) {
  try {
    const res = await api<{ sentTo?: number }>(`/emoji/${em.id}/test-send`, { method: 'POST' })
    ElMessage.success(res.sentTo ? `已发送到 QQ ${res.sentTo}` : '试发成功')
  } catch (e: any) {
    ElMessage.error(e?.message ?? '试发失败')
  }
}

async function approve(em: Emoji) {
  await api(`/emoji/${em.id}/approve`, { method: 'POST' })
  em.status = 'approved'
  ElMessage.success('已通过，可用作聊天表情')
}

async function reject(em: Emoji) {
  await api(`/emoji/${em.id}/reject`, { method: 'POST' })
  ElMessage.success('已删除这张表情')
}

async function removeEmoji(em: Emoji) {
  try {
    await ElMessageBox.confirm('确定删除这个表情包？', '删除确认', {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning'
    })
    await api(`/emoji/${em.id}`, { method: 'DELETE' })
    emojis.value = emojis.value.filter(x => x.id !== em.id)
    recomputeTagStats()
    ElMessage.success('已删除')
  } catch { /* cancelled */ }
}

function toggleSelect(id: string) {
  const s = new Set(selectedIds.value)
  if (s.has(id)) s.delete(id); else s.add(id)
  selectedIds.value = s
}

async function batchDelete() {
  if (selectedIds.value.size === 0) return
  try {
    await ElMessageBox.confirm(`确定删除选中的 ${selectedIds.value.size} 个表情包？`, '批量删除', {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning'
    })
    const ids = [...selectedIds.value]
    await Promise.all(ids.map(id => api(`/emoji/${id}`, { method: 'DELETE' })))
    emojis.value = emojis.value.filter(e => !ids.includes(e.id))
    selectedIds.value = new Set()
    recomputeTagStats()
    ElMessage.success(`已删除 ${ids.length} 个`)
  } catch { /* cancelled */ }
}

function openBatchEdit() {
  batchEmoTags.value = []
  batchSceneTags.value = []
  showBatchEdit.value = true
}

async function saveBatchEdit() {
  const ids = [...selectedIds.value]
  if (ids.length === 0) return
  saving.value = true
  try {
    for (const id of ids) {
      const em = emojis.value.find(e => e.id === id)
      if (!em) continue
      const newEmoTags = [...new Set([...em.emoTags, ...batchEmoTags.value])]
      const newSceneTags = [...new Set([...em.sceneTags, ...batchSceneTags.value])]
      await api(`/emoji/${id}`, {
        method: 'PUT',
        body: { emoTags: newEmoTags.join(','), sceneTags: newSceneTags.join(',') }
      })
      em.emoTags = newEmoTags
      em.sceneTags = newSceneTags
    }
    recomputeTagStats()
    showBatchEdit.value = false
    ElMessage.success('已更新')
  } catch (e: any) {
    ElMessage.error(e?.message ?? '更新失败')
  } finally {
    saving.value = false
  }
}

function onDragStart(e: DragEvent, em: Emoji) {
  if (batchMode.value) { e.preventDefault(); return }
  dragId = em.id
  e.dataTransfer!.effectAllowed = 'move'
}

function onDrop(e: DragEvent, target: Emoji) {
  if (!dragId || dragId === target.id) return
  const fromIdx = emojis.value.findIndex(x => x.id === dragId)
  const toIdx = emojis.value.findIndex(x => x.id === target.id)
  if (fromIdx < 0 || toIdx < 0) return
  const [moved] = emojis.value.splice(fromIdx, 1)
  emojis.value.splice(toIdx, 0, moved)
  dragId = null
}

onMounted(() => loadData())
</script>

<style scoped>
.hint { font-size: 12px; color: var(--c-text-2); margin-top: 4px; }
.emoji-page { display: flex; flex-direction: column; gap: 16px; }
.skeleton-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 16px; }
.emoji-skel { padding: 16px; }
.upload-card { padding: 16px; }
.upload-area { width: 100%; }
.upload-area :deep(.el-upload) { width: 100%; }
.upload-area :deep(.el-upload-dragger) {
  width: 100%; padding: 28px; display: flex; flex-direction: column; align-items: center; gap: 8px;
  border: 2px dashed var(--c-border); border-radius: var(--radius); background: var(--c-bg);
  transition: border-color 0.2s;
}
.upload-area :deep(.el-upload-dragger:hover) { border-color: var(--c-primary); }
.upload-text { font-size: 14px; color: var(--c-text); }
.upload-text em { color: var(--c-primary); font-style: normal; }
.upload-hint { font-size: 12px; color: var(--c-text-2); }
.upload-tip {
  display: flex; align-items: center; gap: 6px;
  margin-top: 12px; padding: 8px 12px;
  background: var(--c-primary-weak); border-radius: 8px;
  font-size: 12px; color: var(--c-primary);
}
.filter-bar { padding: 12px 16px; display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.filter-left { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.filter-right { display: flex; align-items: center; gap: 8px; }
.tag-filter { width: 180px; }
.muted { color: var(--c-text-2); font-size: 12px; }
.batch-bar { display: flex; align-items: center; gap: 12px; padding: 12px 16px; background: var(--c-primary-weak); }
.empty-state { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 48px; color: var(--c-text-2); }
.emoji-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 16px; }
.emoji-card { padding: 0; overflow: hidden; display: flex; flex-direction: column; transition: opacity 0.2s, box-shadow 0.15s; cursor: grab; }
.emoji-card.disabled { opacity: 0.5; }
.emoji-card.selected { box-shadow: 0 0 0 2px var(--c-primary); }
.select-check {
  position: absolute; top: 8px; left: 8px;
  background: rgba(255,255,255,0.9); border-radius: 50%;
  width: 28px; height: 28px; display: flex; align-items: center; justify-content: center;
  cursor: pointer;
}
.check-empty { width: 16px; height: 16px; border: 2px solid var(--c-border); border-radius: 50%; }
.emoji-img-wrap { position: relative; aspect-ratio: 1; overflow: hidden; background: var(--c-bg); }
.emoji-img { width: 100%; height: 100%; }
.img-error {
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px;
  width: 100%; height: 100%; color: var(--c-text-2); font-size: 11.5px;
  background: var(--c-bg);
}
.emoji-overlay {
  position: absolute; top: 8px; right: 8px;
  background: rgba(255,255,255,0.85); border-radius: 8px; padding: 4px 6px;
}
.pending-badge {
  position: absolute; top: 8px; left: 8px;
  padding: 2px 8px; border-radius: 6px;
  font-size: 11px; font-weight: 600;
  background: rgba(230, 162, 60, 0.9); color: #fff;
}
.source-badge {
  position: absolute; bottom: 8px; left: 8px;
  padding: 2px 8px; border-radius: 6px;
  font-size: 11px; font-weight: 500;
  backdrop-filter: blur(4px);
}
.source-upload { background: rgba(122, 168, 116, 0.85); color: #fff; }
.source-auto { background: rgba(107, 154, 196, 0.85); color: #fff; }
.emoji-info { padding: 10px 12px 6px; display: flex; flex-direction: column; gap: 4px; }
.emoji-tags { display: flex; flex-wrap: wrap; gap: 4px; }
.tag-emo { background: var(--c-primary-weak); border-color: transparent; color: var(--c-primary); }
.tag-scene { border-color: transparent; }
.emoji-desc {
  margin-top: 6px; font-size: 12px; line-height: 1.5; color: var(--c-text-2);
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.emoji-actions { display: flex; gap: 2px; padding: 4px 8px 10px; }
.error-card { display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 48px; color: var(--c-text-2); }

@media (max-width: 768px) {
  .emoji-grid { grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 12px; }
  .filter-bar { flex-wrap: wrap; }
  .filter-left { width: 100%; }
  .tag-filter { flex: 1; min-width: 140px; }
}
@media (max-width: 480px) {
  .emoji-grid { grid-template-columns: 1fr 1fr; }
}
</style>
