<template>
  <div>
    <p class="page-desc">上传台词/小说/聊天记录，系统会在凌晨 00:00–06:00 清洗入库；聊天时会参考其中的说话风格。删除文件不会删除切片，切片可以在「切片」页单独增删改。</p>

    <div class="stats">
      <div class="card stat"><span class="label">文档</span><span class="value num">{{ stats.docs }}</span></div>
      <div class="card stat"><span class="label">切片</span><span class="value num">{{ stats.chunks }}</span></div>
      <div class="card stat"><span class="label">风格样本</span><span class="value num">{{ stats.styleChunks }}</span></div>
      <div class="card stat"><span class="label">待处理</span><span class="value num accent">{{ stats.pending }}</span></div>
    </div>

    <el-tabs v-model="tab" class="kb-tabs">
      <!-- ========== 文件 ========== -->
      <el-tab-pane label="文件" name="docs">
        <div class="toolbar">
          <el-radio-group v-model="category" size="small" @change="reload">
            <el-radio-button value="all">全部</el-radio-button>
            <el-radio-button v-for="c in categories" :key="c" :value="c">{{ c }}</el-radio-button>
          </el-radio-group>
          <div class="spacer" />
          <el-button size="small" @click="processNow" :loading="processing">立即处理队列</el-button>
          <el-button size="small" type="primary" @click="uploadVisible = true">上传文件</el-button>
        </div>

        <div class="card">
          <el-table :data="docs" v-loading="loading" style="width: 100%">
            <el-table-column prop="filename" label="文件名" min-width="220" show-overflow-tooltip />
            <el-table-column prop="category" label="分类" width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="row.category === 'auto' ? 'info' : 'primary'">{{ row.category }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="100">
              <template #default="{ row }">{{ typeText(row.parseType) }}</template>
            </el-table-column>
            <el-table-column prop="chunkCount" label="切片" width="80" />
            <el-table-column prop="size" label="大小" width="100">
              <template #default="{ row }">{{ (row.size / 1024).toFixed(0) }} KB</template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="statusType(row.status) as any">{{ statusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="uploadedAt" label="上传时间" width="170" />
            <el-table-column label="操作" width="240" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="viewDocChunks(row)">切片</el-button>
                <el-button link type="warning" size="small" @click="reprocess(row)">重处理</el-button>
                <el-button link size="small" @click="toggle(row)">{{ row.enabled ? '禁用' : '启用' }}</el-button>
                <el-button link type="danger" size="small" @click="removeDoc(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!loading && !docs.length" description="还没有文件，点右上角上传" :image-size="80" />
        </div>
      </el-tab-pane>

      <!-- ========== 切片 ========== -->
      <el-tab-pane label="切片" name="chunks">
        <div class="toolbar">
          <el-select v-model="chunkCategory" size="small" style="width: 140px" @change="reloadChunks">
            <el-option label="全部分类" value="all" />
            <el-option v-for="c in categories" :key="c" :value="c" :label="c" />
          </el-select>
          <el-select v-model="chunkDocId" size="small" style="width: 220px" clearable filterable @change="reloadChunks">
            <el-option label="全部来源" :value="0" />
            <el-option v-for="d in docs" :key="d.id" :label="d.filename" :value="d.id" />
          </el-select>
          <el-input
            v-model="chunkQuery"
            size="small"
            placeholder="搜索切片内容…"
            clearable
            style="width: 240px"
            @keyup.enter="reloadChunks"
            @clear="reloadChunks"
          />
          <el-button size="small" @click="reloadChunks">搜索</el-button>
          <div class="spacer" />
          <el-button size="small" type="primary" @click="openCreateChunk">新增切片</el-button>
        </div>

        <div class="card">
          <el-table :data="chunks" v-loading="chunksLoading" style="width: 100%">
            <el-table-column prop="id" label="#" width="70" />
            <el-table-column label="来源" min-width="200">
              <template #default="{ row }">
                <span :class="{ deleted: row.docStatus === 'deleted' }">{{ row.filename }}</span>
                <el-tag v-if="row.docStatus === 'deleted'" size="small" type="info" class="ml">已删除</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="category" label="分类" width="110" />
            <el-table-column label="类型" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="row.kind === 'style' ? 'success' : 'info'">
                  {{ row.kind === 'style' ? '台词' : '内容' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="内容" min-width="360">
              <template #default="{ row }">
                <div class="chunk-text">{{ row.text }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="createdAt" label="时间" width="165" />
            <el-table-column label="操作" width="130" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openEditChunk(row as Chunk)">编辑</el-button>
                <el-button link type="danger" size="small" @click="removeChunk(row as Chunk)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!chunksLoading && !chunks.length" description="没有符合条件的切片" :image-size="80" />
          <div class="pager">
            <el-pagination
              v-model:current-page="chunkPage"
              v-model:page-size="chunkSize"
              :page-sizes="[20, 50, 100]"
              :total="chunkTotal"
              layout="total, sizes, prev, pager, next"
              background
              @current-change="loadChunks"
              @size-change="reloadChunks"
            />
          </div>
        </div>
      </el-tab-pane>

      <!-- ========== 人设 ========== -->
      <el-tab-pane label="人设" name="personas">
        <div class="toolbar">
          <span class="muted">所有人设由所有机器人账号共用；「启用」只对当前账号生效</span>
          <div class="spacer" />
          <el-button size="small" @click="loadPersonas" :loading="personaLoading">刷新</el-button>
        </div>
        <el-table :data="personaList" v-loading="personaLoading" style="width: 100%">
          <el-table-column prop="name" label="名称" width="160" />
          <el-table-column label="字数" width="90">
            <template #default="{ row }">{{ (row.content || '').length }}</template>
          </el-table-column>
          <el-table-column label="当前账号" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.active" type="success" size="small">使用中</el-tag>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="预览" min-width="240" show-overflow-tooltip>
            <template #default="{ row }">{{ (row.content || '').slice(0, 60) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button size="small" text type="primary" @click="openPersona(row as PersonaRow)">查看/编辑</el-button>
              <el-button size="small" text type="success" :disabled="row.active" @click="activatePersona(row as PersonaRow)">启用</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!personaLoading && !personaList.length" description="还没有人设" :image-size="80" />
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="personaVisible" :title="'人设：' + personaForm.name" width="640px">
      <el-form label-width="70px">
        <el-form-item label="名称">
          <el-input v-model="personaForm.name" maxlength="30" />
        </el-form-item>
        <el-form-item label="内容">
          <el-input v-model="personaForm.content" type="textarea" :rows="18" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="personaVisible = false">取消</el-button>
        <el-button type="primary" :loading="personaSaving" @click="savePersona">保存</el-button>
      </template>
    </el-dialog>

    <!-- 上传 -->
    <el-dialog v-model="uploadVisible" title="上传到知识库" width="440px">
      <el-form label-width="70px">
        <el-form-item label="分类">
          <el-select v-model="uploadCategory" style="width: 100%">
            <el-option label="自动判断（推荐）" value="auto" />
            <el-option v-for="c in categories" :key="c" :value="c" :label="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="解析类型">
          <el-select v-model="parseType" style="width: 100%">
            <el-option label="生成人设（从小说抽取角色）" value="persona" />
            <el-option label="台词风格（拆成说话样本）" value="style" />
            <el-option label="仅入库（不处理）" value="raw" />
          </el-select>
          <div class="tip">解析在每天 00:05–06:05 自动执行，上传后先排队</div>
        </el-form-item>
        <el-form-item label="文件">
          <el-upload :auto-upload="false" :limit="1" :on-change="onPick" :file-list="fileList" accept=".md,.txt,.docx">
            <el-button>选择文件</el-button>
            <template #tip><div class="tip">支持 .md / .txt / .docx，单个 ≤30MB</div></template>
          </el-upload>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="doUpload">上传</el-button>
      </template>
    </el-dialog>

    <!-- 某个文件的切片 -->
    <el-dialog v-model="docChunksVisible" :title="`切片预览 · ${currentDoc?.filename ?? ''}`" width="760px">
      <div v-loading="docChunksLoading" class="chunk-list">
        <div v-for="c in docChunks" :key="c.id" class="chunk">
          <el-tag size="small" :type="c.kind === 'style' ? 'success' : 'info'">{{ c.kind === 'style' ? '台词/风格' : '内容' }}</el-tag>
          <span class="chunk-text">{{ c.text }}</span>
        </div>
        <el-empty v-if="!docChunksLoading && !docChunks.length" description="暂无切片" :image-size="70" />
      </div>
    </el-dialog>

    <!-- 新增/编辑切片 -->
    <el-dialog v-model="chunkDialogVisible" :title="editingChunkId ? '编辑切片' : '新增切片'" width="620px">
      <el-form label-width="80px">
        <el-form-item label="来源文档">
          <el-select v-model="chunkForm.docId" style="width: 100%" filterable :disabled="!!editingChunkId">
            <el-option v-for="d in docs" :key="d.id" :label="d.filename" :value="d.id" />
          </el-select>
          <div class="tip">只能选还在列表里的文件；已删除文件的切片可以直接编辑内容</div>
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="chunkForm.category" style="width: 100%" filterable allow-create>
            <el-option label="auto（自动）" value="auto" />
            <el-option v-for="c in categories" :key="c" :value="c" :label="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="chunkForm.kind">
            <el-radio-button value="content">内容</el-radio-button>
            <el-radio-button value="style">台词/风格</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="内容">
          <el-input v-model="chunkForm.text" type="textarea" :rows="8" placeholder="切片正文…" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="chunkDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="chunkSaving" @click="saveChunk">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type UploadFile } from 'element-plus'
import { api } from '@/api/client'

interface Doc {
  id: number
  filename: string
  category: string
  status: string
  error?: string
  size: number
  chunkCount: number
  uploadedAt: string
  enabled: boolean
}
interface Chunk {
  id: number
  docId: number
  filename?: string
  docStatus?: string
  category: string
  kind: string
  text: string
  createdAt?: string
}

const tab = ref('docs')

interface PersonaRow { id: string; name: string; content: string; active: boolean }
const personaList = ref<PersonaRow[]>([])
const personaLoading = ref(false)
const personaVisible = ref(false)
const personaSaving = ref(false)
const personaForm = ref({ id: '', name: '', content: '' })

async function loadPersonas() {
  personaLoading.value = true
  try {
    const list = await api<PersonaRow[]>('/personas')
    personaList.value = list ?? []
  } catch (e: any) {
    ElMessage.error(e?.message ?? '人设加载失败')
  } finally {
    personaLoading.value = false
  }
}

function openPersona(row: PersonaRow) {
  personaForm.value = { id: row.id, name: row.name, content: row.content }
  personaVisible.value = true
}

async function savePersona() {
  if (!personaForm.value.content.trim()) {
    ElMessage.warning('人设内容不能为空')
    return
  }
  personaSaving.value = true
  try {
    await api('/personas/' + personaForm.value.id, {
      method: 'PUT',
      body: { name: personaForm.value.name, content: personaForm.value.content }
    })
    ElMessage.success('已保存')
    personaVisible.value = false
    await loadPersonas()
  } catch (e: any) {
    ElMessage.error(e?.message ?? '保存失败')
  } finally {
    personaSaving.value = false
  }
}

async function activatePersona(row: PersonaRow) {
  try {
    await api('/personas/' + row.id + '/activate', { method: 'POST' })
    ElMessage.success('当前账号已启用「' + row.name + '」')
    await loadPersonas()
  } catch (e: any) {
    ElMessage.error(e?.message ?? '启用失败')
  }
}const stats = ref({ docs: 0, chunks: 0, styleChunks: 0, pending: 0 })
const categories = ref<string[]>([])
const docs = ref<Doc[]>([])
const category = ref('all')
const loading = ref(false)
const processing = ref(false)

const uploadVisible = ref(false)
const uploadCategory = ref('auto')
const parseType = ref('persona')
const uploading = ref(false)
const fileList = ref<UploadFile[]>([])
const picked = ref<File | null>(null)

const docChunksVisible = ref(false)
const docChunksLoading = ref(false)
const docChunks = ref<Chunk[]>([])
const currentDoc = ref<Doc | null>(null)

// 切片页
const chunks = ref<Chunk[]>([])
const chunksLoading = ref(false)
const chunkTotal = ref(0)
const chunkPage = ref(1)
const chunkSize = ref(20)
const chunkQuery = ref('')
const chunkCategory = ref('all')
const chunkKind = ref('all')
const chunkDocId = ref(0)

const chunkDialogVisible = ref(false)
const chunkSaving = ref(false)
const editingChunkId = ref(0)
const chunkForm = ref({ docId: 0, category: 'auto', kind: 'content', text: '' })

onMounted(async () => {
  await loadCategories()
  await reload()
})

async function loadCategories() {
  try {
    const res = await api<{ items: string[] }>('/knowledge/categories')
    categories.value = res.items ?? []
  } catch { /* 分类失败不阻塞 */ }
}

async function reload() {
  loading.value = true
  try {
    const [s, d] = await Promise.all([
      api<typeof stats.value>('/knowledge/stats'),
      api<{ items: Doc[]; total: number }>(`/knowledge/docs?category=${encodeURIComponent(category.value)}&page=1&size=50`)
    ])
    stats.value = s
    docs.value = d.items
  } catch (e: any) {
    ElMessage.error(e?.message ?? '加载失败')
  } finally { loading.value = false }
}

function typeText(t?: string) {
  return ({ persona: '人设', style: '台词', raw: '仅入库' } as Record<string, string>)[t ?? 'style'] ?? '台词'
}

function statusText(s: string) {
  return ({ pending: '待处理', processing: '处理中', done: '已入库', failed: '失败', deleted: '已删除' } as Record<string, string>)[s] ?? s
}
function statusType(s: string) {
  return ({ pending: 'info', processing: 'warning', done: 'success', failed: 'danger', deleted: 'info' } as Record<string, string>)[s] ?? 'info'
}

function onPick(f: UploadFile) {
  picked.value = (f.raw as File) ?? null
  fileList.value = [f]
}

async function doUpload() {
  if (!picked.value) { ElMessage.warning('请选择文件'); return }
  uploading.value = true
  try {
    const form = new FormData()
    form.append('file', picked.value)
    form.append('category', uploadCategory.value)
    form.append('parseType', parseType.value)
    await api('/knowledge/upload', { method: 'POST', formData: form })
    ElMessage.success('已加入队列，可点「立即处理」或等凌晨自动处理')
    uploadVisible.value = false
    fileList.value = []
    picked.value = null
    await reload()
  } catch (e: any) {
    ElMessage.error(e?.message ?? '上传失败')
  } finally { uploading.value = false }
}

async function processNow() {
  processing.value = true
  try {
    const r = await api<{ processed: number; failed: number }>('/knowledge/process', { method: 'POST' })
    ElMessage.success(`处理完成：成功 ${r.processed ?? 0}，失败 ${r.failed ?? 0}`)
    await reload()
  } catch (e: any) {
    ElMessage.error(e?.message ?? '处理失败')
  } finally { processing.value = false }
}

async function viewDocChunks(row: any) {
  currentDoc.value = row
  docChunksVisible.value = true
  docChunksLoading.value = true
  try {
    const r = await api<{ items: Chunk[] }>(`/knowledge/docs/${row.id}/chunks?page=1&size=200`)
    docChunks.value = r.items
  } finally { docChunksLoading.value = false }
}

async function reprocess(row: any) {
  await api(`/knowledge/docs/${row.id}/reprocess`, { method: 'POST' })
  ElMessage.success('已重新入队')
  await reload()
}

async function toggle(row: any) {
  await api(`/knowledge/docs/${row.id}`, { method: 'PATCH', body: { enabled: !row.enabled } })
  await reload()
}

async function removeDoc(row: any) {
  await ElMessageBox.confirm(
    `删除文件「${row.filename}」？\n文件会从列表移除，但已生成的 ${row.chunkCount} 个切片会保留，可在「切片」页查看和管理。`,
    '删除文件（保留切片）',
    { type: 'warning', confirmButtonText: '删除文件' }
  )
  await api(`/knowledge/docs/${row.id}`, { method: 'DELETE' })
  ElMessage.success('文件已删除，切片保留')
  await reload()
}

// ---------- 切片 ----------

async function reloadChunks() {
  chunkPage.value = 1
  await loadChunks()
}

async function loadChunks() {
  chunksLoading.value = true
  try {
    const params = new URLSearchParams({
      page: String(chunkPage.value),
      size: String(chunkSize.value),
      category: chunkCategory.value,
      kind: chunkKind.value
    })
    if (chunkDocId.value) params.set('docId', String(chunkDocId.value))
    if (chunkQuery.value.trim()) params.set('q', chunkQuery.value.trim())
    const r = await api<{ items: Chunk[]; total: number }>('/knowledge/chunks?' + params.toString())
    chunks.value = r.items ?? []
    chunkTotal.value = r.total ?? 0
  } catch (e: any) {
    ElMessage.error(e?.message ?? '切片加载失败')
  } finally { chunksLoading.value = false }
}

function openCreateChunk() {
  editingChunkId.value = 0
  chunkForm.value = { docId: docs.value[0]?.id ?? 0, category: chunkCategory.value === 'all' ? 'auto' : chunkCategory.value, kind: 'content', text: '' }
  chunkDialogVisible.value = true
}

function openEditChunk(row: Chunk) {
  editingChunkId.value = row.id
  chunkForm.value = { docId: row.docId, category: row.category, kind: row.kind, text: row.text }
  chunkDialogVisible.value = true
}

async function saveChunk() {
  if (!chunkForm.value.text.trim()) { ElMessage.warning('内容不能为空'); return }
  if (!editingChunkId.value && !chunkForm.value.docId) { ElMessage.warning('请选择来源文档'); return }
  chunkSaving.value = true
  try {
    if (editingChunkId.value) {
      await api(`/knowledge/chunks/${editingChunkId.value}`, {
        method: 'PUT',
        body: { category: chunkForm.value.category, kind: chunkForm.value.kind, text: chunkForm.value.text }
      })
      ElMessage.success('已保存')
    } else {
      await api('/knowledge/chunks', {
        method: 'POST',
        body: { ...chunkForm.value }
      })
      ElMessage.success('已新增切片')
    }
    chunkDialogVisible.value = false
    await Promise.all([loadChunks(), reload()])
  } catch (e: any) {
    ElMessage.error(e?.message ?? '保存失败')
  } finally { chunkSaving.value = false }
}

async function removeChunk(row: Chunk) {
  await ElMessageBox.confirm('删除这个切片？此操作不可恢复。', '确认删除', { type: 'warning' })
  await api(`/knowledge/chunks/${row.id}`, { method: 'DELETE' })
  ElMessage.success('已删除')
  await Promise.all([loadChunks(), reload()])
}

watch(tab, (v) => {
  if (v === 'chunks') {
    loadChunks()
  }
  if (v === 'personas') {
    loadPersonas()
  }
})
</script>

<style scoped>
.stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 16px; }
.stat { display: flex; flex-direction: column; gap: 4px; }
.label { font-size: 13px; color: var(--c-text-2); }
.value { font-size: 24px; font-weight: 600; }
.accent { color: var(--c-primary); }
.toolbar { display: flex; align-items: center; gap: 10px; margin: 6px 0 14px; flex-wrap: wrap; }
.spacer { flex: 1; }
.tip { font-size: 12px; color: var(--c-text-2); }
.chunk-list { max-height: 60vh; overflow: auto; display: flex; flex-direction: column; gap: 10px; }
.chunk { display: flex; gap: 8px; align-items: flex-start; padding: 8px 10px; border: 1px solid var(--c-border); border-radius: 8px; }
.chunk-text { font-size: 13px; line-height: 1.7; white-space: pre-wrap; word-break: break-word; }
.deleted { color: var(--c-text-2); text-decoration: line-through; }
.ml { margin-left: 6px; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
@media (max-width: 900px) { .stats { grid-template-columns: repeat(2, 1fr); } }
</style>
