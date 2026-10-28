<template>
  <div>
    <p class="page-desc">每个账号是一个独立的 NapCat 实例，互不影响；扫码登录、下线都在这里</p>

    <div class="toolbar">
      <el-button type="primary" :loading="creating" @click="createAccount">添加账号</el-button>
      <el-button @click="load" :loading="loading">刷新</el-button>
      <span class="hint">已添加 {{ items.length }} 个</span>
    </div>

    <div class="list">
      <div v-for="a in items" :key="a.id" class="card account-item">
        <div class="avatar">{{ a.online ? '🟢' : '⚪' }}</div>
        <div class="info">
          <div class="row">
            <span class="name">{{ a.name || ('账号 ' + a.id) }}</span>
            <el-tag size="small" :type="a.online ? 'success' : 'info'">{{ a.online ? '在线' : '离线' }}</el-tag>
            <el-tag v-if="a.qqId" size="small" type="primary">QQ {{ a.qqId }}</el-tag>
            <el-tag v-if="a.backendName" size="small" type="warning" effect="plain">{{ a.backendName }}</el-tag>
          </div>
          <div class="meta">
            <span>{{ a.container }}</span>
            <span>WS {{ a.wsPort }}</span>
          </div>
          <div class="wl">
            <span class="wl-label">可聊天的 QQ：</span>
            <el-tag v-for="q in (whitelists[a.id] ?? [])" :key="q" size="small" closable
                    @close="removeQq(a.id, q)">{{ q }}</el-tag>
            <span v-if="!(whitelists[a.id] ?? []).length" class="wl-empty">未限制（任何人都能聊）</span>
            <el-button link type="primary" size="small" @click="openWl(a)">配置</el-button>
          </div>
          <div class="wl owner-row">
            <span class="wl-label">主人 QQ：</span>
            <el-input
              v-model="ownerDrafts[a.id]"
              size="small"
              placeholder="只有这个号能改头像/昵称"
              style="width: 200px"
            />
            <el-button size="small" @click="saveOwner(a)">保存</el-button>
          </div>
        </div>
        <div class="actions">
          <el-button size="small" @click="openQr(a)">{{ a.online ? '查看' : '扫码登录' }}</el-button>
          <el-dropdown v-if="otherBackends(a).length" trigger="click"
                       @command="(b: string) => switchBackend(a, b)">
            <el-button size="small">切换接入</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-for="b in otherBackends(a)" :key="b.key" :command="b.key">
                  切到 {{ b.name }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-button size="small" type="danger" plain :disabled="!a.online" @click="logout(a)">下线</el-button>
          <el-button size="small" type="danger" link @click="removeAccount(a)">删除</el-button>
        </div>
      </div>
      <el-empty v-if="!loading && !items.length" description="还没有账号" :image-size="80" />
    </div>

    <div class="card friend-requests">
      <div class="section-head">
        <div>
          <h3 class="section-title">好友申请</h3>
          <p class="section-desc">机器人收到的好友申请会出现在这里，可直接同意或拒绝</p>
        </div>
        <el-button size="small" :loading="requestsLoading" @click="loadRequests">刷新</el-button>
      </div>
      <el-table :data="requests" v-loading="requestsLoading" style="width: 100%">
        <el-table-column label="机器人账号" min-width="160">
          <template #default="{ row }">
            <span>{{ row.botName || ('账号 ' + row.botId) }}</span>
            <el-tag v-if="row.botQq" size="small" type="primary" style="margin-left: 6px">QQ {{ row.botQq }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="userId" label="好友账号" width="160" />
        <el-table-column label="好友名称" min-width="180">
          <template #default="{ row }">
            <span>{{ row.nickname || '（未获取到昵称）' }}</span>
            <span v-if="row.comment" class="req-comment">留言：{{ row.comment }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="success" :loading="row._loading === 'approve'"
                       @click="handleRequest(row as FriendRequestRow, true)">同意</el-button>
            <el-button size="small" type="danger" plain :loading="row._loading === 'reject'"
                       @click="handleRequest(row as FriendRequestRow, false)">拒绝</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!requestsLoading && !requests.length" description="暂无待审核的好友申请" :image-size="70" />
    </div>

    <el-dialog v-model="deleteVisible" title="删除账号" width="440px">
      <p class="danger-title">确定删除「{{ deleteTarget?.name || deleteTarget?.id }}」？</p>
      <el-radio-group v-model="deleteKeepData">
        <el-radio :value="true">保留数据（推荐）</el-radio>
        <el-radio :value="false">同时删除数据</el-radio>
      </el-radio-group>
      <p class="delete-hint" v-if="deleteKeepData">
        只删除账号和容器，聊天记录、记忆、人设都保留。
        以后用同一个 QQ 登录时，会自动接回这份数据。
      </p>
      <p class="delete-hint danger" v-else>
        账号和数据库都会删除，聊天记录和记忆不可恢复。
      </p>
      <template #footer>
        <el-button @click="deleteVisible = false">取消</el-button>
        <el-button type="danger" :loading="deleting !== null" @click="doRemove">确认删除</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="createVisible" title="添加机器人账号" width="440px">
      <el-form label-width="96px">
        <el-form-item label="账号名称">
          <el-input v-model="createName" placeholder="例如：小满" maxlength="20" />
        </el-form-item>
        <el-form-item label="接入方式">
          <el-radio-group v-model="createBackend">
            <el-radio v-for="b in backends" :key="b.key" :value="b.key">{{ b.name }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-alert
          v-if="createBackend !== 'napcat'"
          type="info"
          :closable="false"
          show-icon
          title="SnowLuma 的二维码在它自己的远程桌面里"
          description="创建后点「扫码登录」会给出远程桌面地址和密码；NapCat 是直接在这里显示二维码。"
        />
        <el-form-item label="数据">
          <el-radio-group v-model="createMode">
            <el-radio value="new">全新账号</el-radio>
            <el-radio value="copy">复制旧账号数据</el-radio>
            <el-radio value="move">迁移旧账号数据</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="createMode !== 'new'" label="旧账号">
          <el-select v-model="migrateFrom" placeholder="选择旧账号" style="width: 100%">
            <el-option
              v-for="a in items"
              :key="a.id"
              :value="a.id"
              :label="(a.name || ('账号 ' + a.id)) + (a.qqId ? ' · ' + a.qqId : '')"
            />
          </el-select>
        </el-form-item>
        <el-alert
          v-if="createMode === 'copy' && migrateFrom"
          type="info"
          :closable="false"
          show-icon
          title="旧账号保留，数据复制一份"
          description="新账号从这份数据开始独立运行；之后两边各聊各的，互不覆盖。"
        />
        <el-alert
          v-if="createMode === 'move' && migrateFrom"
          type="warning"
          :closable="false"
          show-icon
          title="旧账号会被删除"
          description="聊天记录、记忆、人设、配置会搬到新账号；旧账号的容器与数据库随后删除，不可恢复。"
        />
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="doCreate">创建并扫码</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="wlVisible" :title="`可聊天的 QQ · ${wlAccount?.name ?? ''}`" width="420px">
      <div class="wl-dialog">
        <div class="wl-tags">
          <el-tag v-for="q in wlDraft" :key="q" closable @close="wlDraft = wlDraft.filter(x => x !== q)">{{ q }}</el-tag>
          <span v-if="!wlDraft.length" class="wl-empty">未限制（任何人都能聊）</span>
        </div>
        <div class="wl-add">
          <el-select
            v-model="newQq"
            filterable
            clearable
            allow-create
            default-first-option
            :loading="friendsLoading"
            placeholder="搜索昵称或输入 QQ"
            style="width: 240px"
          >
            <el-option
              v-for="f in friendOptions"
              :key="f.qqId"
              :value="String(f.qqId)"
              :label="(f.remark || f.nickname || '好友') + ' (' + f.qqId + ')'"
            />
          </el-select>
          <el-button @click="addDraft">添加</el-button>
        </div>
        <p class="wl-tip">从好友列表里搜昵称或 QQ 选择；也可以直接输入 QQ 号。留空表示不限制。</p>
      </div>
      <template #footer>
        <el-button @click="wlVisible = false">取消</el-button>
        <el-button type="primary" :loading="wlSaving" @click="saveWl()">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="qrVisible" :title="`扫码登录 · ${current?.name ?? ''}`" width="360px" @closed="stopPoll">
      <div class="qr-wrap">
        <img v-if="qrSrc" :src="qrSrc" class="qr-img" alt="二维码" @error="onQrError" />
        <div v-else-if="qrHint" class="qr-empty">{{ qrHint }}</div>
        <div v-else class="qr-empty">二维码生成中…（新实例启动约 10–20 秒）</div>
        <p class="qr-hint">{{ qrHint }}</p>
        <p v-if="qrSrc && qrRemain > 0" class="qr-countdown">剩余约 {{ qrRemain }} 秒</p>
        <el-button size="small" :loading="refreshingQr" @click="refreshQr">刷新二维码</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api/client'

interface Account {
  id: number
  qqId?: number
  name?: string
  container: string
  wsPort: number
  online: boolean
  qrcode?: boolean
  backend?: string
  backendName?: string
  scanHint?: string
}

interface BackendOption {
  key: string
  name: string
}

const items = ref<Account[]>([])
const loading = ref(false)
const creating = ref(false)
const createVisible = ref(false)
const createName = ref('')
const createMode = ref<'new' | 'copy' | 'move'>('new')
const backends = ref<BackendOption[]>([])
const createBackend = ref('napcat')
const migrateFrom = ref<number | null>(null)
const deleting = ref<number | null>(null)
const deleteVisible = ref(false)
const deleteTarget = ref<Account | null>(null)
const deleteKeepData = ref(true)

const qrVisible = ref(false)
const qrSrc = ref('')
const qrHint = ref('')
const refreshingQr = ref(false)
/** 二维码生成时间（本地），用于倒计时；NapCat 约每 2 分钟换一次 */
const qrGeneratedAt = ref(0)
const qrRemain = ref(0)
let qrTicker: number | undefined
const current = ref<Account | null>(null)
const whitelists = ref<Record<number, number[]>>({})
const ownerDrafts = ref<Record<number, string>>({})
const wlVisible = ref(false)
const wlAccount = ref<Account | null>(null)
const wlDraft = ref<number[]>([])
const friendOptions = ref<Array<{ qqId: number; nickname: string; remark: string }>>([])
const friendsLoading = ref(false)

interface FriendRequestRow {
  id: number
  botId: number
  botName?: string
  botQq?: number
  userId: number
  nickname?: string
  comment?: string
  _loading?: string
}
const requests = ref<FriendRequestRow[]>([])
const requestsLoading = ref(false)
const newQq = ref('')
const wlSaving = ref(false)
let timer: number | undefined
let successShown = false

async function load() {
  loading.value = true
  try {
    const r = await api<{ items: Account[] }>('/account/list')
    items.value = r.items ?? []
    for (const a of items.value) {
      try {
        const w = await api<{ ids: number[] }>(`/account/${a.id}/whitelist`)
        whitelists.value[a.id] = (w.ids ?? []).map(Number)
      } catch { whitelists.value[a.id] = [] }
      try {
        const o = await api<{ qqId: number }>(`/account/${a.id}/owner`)
        ownerDrafts.value[a.id] = o.qqId ? String(o.qqId) : ''
      } catch { ownerDrafts.value[a.id] = '' }
    }
    if (current.value) {
      const fresh = items.value.find(x => x.id === current.value!.id)
      if (fresh?.online) {
        stopPoll()
        qrVisible.value = false
      }
    }
  } catch (e: any) {
    ElMessage.error(e?.message ?? '加载失败')
  } finally {
    loading.value = false
  }
}

function createAccount() {
  createName.value = `账号 ${items.value.length + 1}`
  createMode.value = 'new'
  createBackend.value = 'napcat'
  migrateFrom.value = null
  createVisible.value = true
}

function otherBackends(a: Account) {
  return backends.value.filter(b => b.key !== a.backend)
}

async function switchBackend(a: Account, backendKey: string) {
  const name = backends.value.find(b => b.key === backendKey)?.name ?? backendKey
  try {
    await ElMessageBox.confirm(
      `会停掉当前的协议端容器，按 ${name} 重建一个新的，账号的聊天记录和记忆不受影响。` +
      `切换后需要重新扫码登录。`,
      '切换接入方式',
      { type: 'warning', confirmButtonText: '切换', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const r = await api<{ ok: boolean; message?: string }>(`/account/${a.id}/backend`, {
      method: 'POST',
      body: { backend: backendKey }
    })
    if (!r.ok) {
      ElMessage.error(r.message ?? '切换失败')
      return
    }
    ElMessage.success(`已切换到 ${name}，请扫码登录`)
    await load()
    const fresh = items.value.find(x => x.id === a.id)
    if (fresh) openQr(fresh)
  } catch (e) {
    ElMessage.error('切换失败：' + (e instanceof Error ? e.message : String(e)))
  }
}

async function loadBackends() {
  try {
    const r = await api<{ items: BackendOption[] }>('/account/backends')
    if (r.items && r.items.length) {
      backends.value = r.items
      if (!r.items.some(b => b.key === createBackend.value)) {
        createBackend.value = r.items[0].key
      }
    }
  } catch {
    /* 拉不到就只用默认 */
  }
}

async function doCreate() {
  if (createMode.value !== 'new' && !migrateFrom.value) {
    ElMessage.warning('请选择要复用数据的旧账号')
    return
  }
  creating.value = true
  try {
    const r = await api<{
      ok: boolean
      id: number
      message?: string
      migrated?: boolean
      copied?: boolean
      migrateMessage?: string
    }>('/account/create', {
      method: 'POST',
      body: {
        name: createName.value.trim() || `账号 ${items.value.length + 1}`,
        backend: createBackend.value,
        migrateFrom: createMode.value === 'new' ? null : migrateFrom.value,
        copyOnly: createMode.value === 'copy'
      }
    })
    if (!r.ok) {
      ElMessage.error(r.message ?? '创建失败')
      return
    }
    createVisible.value = false
    if (createMode.value === 'copy' && r.copied) {
      ElMessage.success('已创建新账号，旧账号数据已复制（旧账号保留）')
    } else if (createMode.value === 'move' && r.migrated) {
      ElMessage.success('已创建新账号，旧账号数据已迁移并删除旧账号')
    } else if (createMode.value !== 'new' && r.migrateMessage) {
      ElMessage.warning('新账号已创建，但数据复用失败：' + r.migrateMessage)
    } else {
      ElMessage.success('已创建新账号')
    }
    await load()
    const a = items.value.find(x => x.id === r.id)
    if (a) openQr(a)
  } finally {
    creating.value = false
  }
}

function onQrError() {
  qrSrc.value = ''
  qrHint.value = current.value?.scanHint || '还没截到二维码，等几秒后点「刷新二维码」'
}

function openQr(a: Account) {
  current.value = a
  successShown = false
  qrSrc.value = ''
  qrHint.value = a.qrcode === false && a.scanHint ? a.scanHint : '等待协议端生成二维码…'
  qrVisible.value = true
  poll()
}

function poll() {
  stopPoll()
  timer = window.setInterval(async () => {
    if (!current.value) return
    try {
      const a = await api<Account>(`/account/${current.value.id}`)
      if (a.online) {
        stopPoll()
        qrVisible.value = false
        if (!successShown) {
          successShown = true
          ElMessage.success(`已上线：${a.qqId ?? ''}`)
        }
        await load()
        return
      }
      if (a.qrcode) {
        qrSrc.value = `/api/account/${a.id}/qrcode?ts=${Date.now()}`
        qrHint.value = '用手机 QQ 扫码登录'
      }
    } catch { /* 忽略 */ }
  }, 2000)
}

async function refreshQr() {
  if (!current.value) return
  refreshingQr.value = true
  qrHint.value = '正在重新生成二维码…'
  try {
    const r = await api<{ ok: boolean; online?: boolean; message?: string }>(
      `/account/${current.value.id}/qrcode/refresh`,
      { method: 'POST' }
    )
    if (r.online) {
      ElMessage.success('该账号已在线')
      qrVisible.value = false
      await load()
      return
    }
    if (!r.ok) {
      ElMessage.warning(r.message ?? '刷新失败，请稍后重试')
      qrHint.value = '刷新失败，可以再点一次'
      return
    }
    qrSrc.value = `/api/account/${current.value.id}/qrcode?ts=${Date.now()}`
    qrHint.value = '二维码已刷新，请重新扫码'
    ElMessage.success('二维码已刷新')
  } catch (e: any) {
    ElMessage.error(e?.message ?? '刷新失败')
    qrHint.value = '刷新失败，可以再点一次'
  } finally {
    refreshingQr.value = false
  }
}

function stopPoll() {
  if (timer) window.clearInterval(timer)
  timer = undefined}

async function openWl(a: Account) {
  wlAccount.value = a
  wlDraft.value = [...(whitelists.value[a.id] ?? [])]
  newQq.value = ''
  wlVisible.value = true
  friendOptions.value = []
  friendsLoading.value = true
  try {
    const r = await api<{ items: Array<{ qqId: number; nickname: string; remark: string }> }>(
      `/account/${a.id}/friends`
    )
    friendOptions.value = r.items ?? []
  } catch {
    friendOptions.value = []
  } finally {
    friendsLoading.value = false
  }
}

async function addDraft() {
  const n = Number(newQq.value.trim())
  if (!n || Number.isNaN(n)) {
    ElMessage.warning('请输入正确的 QQ 号')
    return
  }
  if (wlDraft.value.includes(n)) {
    ElMessage.warning('这个 QQ 已经在列表里了')
    return
  }
  wlDraft.value.push(n)
  newQq.value = ''
  await saveWl(true)
}

async function saveOwner(a: Account) {
  const raw = (ownerDrafts.value[a.id] ?? '').trim()
  const qq = raw ? Number(raw) : 0
  if (raw && (!qq || Number.isNaN(qq))) {
    ElMessage.warning('请输入正确的 QQ 号')
    return
  }
  try {
    await api(`/account/${a.id}/owner`, { method: 'PUT', body: { qqId: qq } })
    ElMessage.success(qq ? '已设置主人 QQ' : '已清空主人 QQ（谁都不能改形象）')
    await load()
  } catch (e: any) {
    ElMessage.error(e?.message ?? '保存失败')
  }
}

async function saveWl(silent = false) {
  if (!wlAccount.value) return
  // 用户可能只输入没点「添加」，保存时把输入框里的号一并收进来
  const typed = Number(newQq.value.trim())
  if (typed && !Number.isNaN(typed) && !wlDraft.value.includes(typed)) {
    wlDraft.value.push(typed)
    newQq.value = ''
  }
  wlSaving.value = true
  const botId = wlAccount.value.id
  try {
    const r = await api<{ ids: number[] }>(`/account/${botId}/whitelist`, {
      method: 'PUT',
      body: { ids: wlDraft.value }
    })
    const saved = (r.ids ?? []).map(Number)
    whitelists.value[botId] = saved
    wlDraft.value = [...saved]
    if (silent) {
      ElMessage.success(`已添加，当前 ${saved.length} 个`)
    } else {
      wlVisible.value = false
      ElMessage.success(`已保存，共 ${saved.length} 个`)
    }
    ElMessage.success('已保存')
  } catch (e: any) {
    ElMessage.error(e?.message ?? '保存失败')
  } finally {
    wlSaving.value = false
  }
}

async function removeQq(id: number, qq: number) {
  const next = (whitelists.value[id] ?? []).filter(x => x !== qq)
  const r = await api<{ ids: number[] }>(`/account/${id}/whitelist`, { method: 'PUT', body: { ids: next } })
  whitelists.value[id] = (r.ids ?? []).map(Number)
}

function removeAccount(a: Account) {
  if (deleting.value !== null) return
  deleteTarget.value = a
  deleteKeepData.value = true
  deleteVisible.value = true
}

async function doRemove() {
  const a = deleteTarget.value
  if (!a) return
  deleting.value = a.id
  try {
    const r = await api<{ ok: boolean, message?: string }>(
      `/account/${a.id}?keepData=${deleteKeepData.value}`,
      { method: 'DELETE' }
    )
    if (!r.ok) {
      ElMessage.error(r.message ?? '删除失败')
      return
    }
    ElMessage.success(deleteKeepData.value ? '账号已删除，数据已保留' : '账号和数据已删除')
    deleteVisible.value = false
    await load()
  } finally {
    deleting.value = null
  }
}

async function logout(a: Account) {
  await ElMessageBox.confirm(`下线「${a.name || a.id}」？该号将停止回复`, '确认', { type: 'warning' })
  await api(`/account/${a.id}/logout`, { method: 'POST' })
  ElMessage.success('已下线')
  await load()
}

async function loadRequests() {
  requestsLoading.value = true
  try {
    const r = await api<{ items: FriendRequestRow[] }>('/friend-requests')
    requests.value = (r.items ?? []).map(x => ({ ...x }))
  } catch (e: any) {
    ElMessage.error(e?.message ?? '好友申请加载失败')
  } finally {
    requestsLoading.value = false
  }
}

async function handleRequest(row: FriendRequestRow, approve: boolean) {
  row._loading = approve ? 'approve' : 'reject'
  try {
    await api(`/friend-requests/${row.id}/${approve ? 'approve' : 'reject'}?botId=${row.botId}`, {
      method: 'POST'
    })
    ElMessage.success(approve ? `已同意 ${row.nickname || row.userId}` : '已拒绝')
    requests.value = requests.value.filter(x => x.id !== row.id)
    await load()
  } catch (e: any) {
    ElMessage.error(e?.message ?? '操作失败')
  } finally {
    row._loading = ''
  }
}

let statusTimer: number | undefined
onMounted(() => {
  load()
  loadBackends()
  loadRequests()
  statusTimer = window.setInterval(load, 5000)
})
onBeforeUnmount(() => {
  stopPoll()
  if (statusTimer) window.clearInterval(statusTimer)
})
</script>

'<style scoped>
.friend-requests { margin-top: 18px; }
.section-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.section-title { margin: 0 0 4px; font-size: 15px; font-weight: 600; }
.section-desc { margin: 0; font-size: 12.5px; color: var(--c-text-2); }
.req-comment { display: block; font-size: 12px; color: var(--c-text-2); margin-top: 2px; }
.toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; }
.hint { font-size: 12px; color: var(--c-text-2); }
.list { display: flex; flex-direction: column; gap: 12px; }
.account-item { display: flex; align-items: center; gap: 16px; padding: 18px; }
.avatar { width: 52px; height: 52px; border-radius: 16px; display: grid; place-items: center; font-size: 22px; background: var(--c-bg-soft, #f3f5fa); }
.info { flex: 1; display: flex; flex-direction: column; gap: 6px; }
.row { display: flex; align-items: center; gap: 8px; }
.name { font-weight: 600; }
.meta { display: flex; gap: 14px; font-size: 12px; color: var(--c-text-2); }
.wl { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; font-size: 12px; }
.wl-label { color: var(--c-text-2); }
.wl-empty { color: var(--c-text-2); }
.wl-dialog { display: flex; flex-direction: column; gap: 12px; }
.wl-tags { display: flex; gap: 6px; flex-wrap: wrap; min-height: 28px; }
.wl-add { display: flex; gap: 8px; }
'.wl-tip { margin: 0; font-size: 12px; color: var(--c-text-2); }
.owner-row { margin-top: 6px; }
.form-hint { font-size: 12px; color: var(--c-text-2); margin-left: 8px; }
.danger-title { margin: 0 0 14px; font-size: 14px; font-weight: 600; }
.delete-hint { margin: 10px 0 0; font-size: 12.5px; line-height: 1.6; color: var(--c-text-2); }
.delete-hint.danger { color: #f56c6c; }'
.qr-wrap { display: flex; flex-direction: column; align-items: center; gap: 12px; }
.qr-img { width: 240px; height: 240px; border-radius: 12px; background: #fff; padding: 8px; }
.qr-empty { width: 240px; height: 240px; display: grid; place-items: center; text-align: center; border-radius: 12px; background: var(--c-bg-soft, #f3f5fa); font-size: 13px; color: var(--c-text-2); }
.qr-hint { margin: 0; font-size: 13px; color: var(--c-text-2); text-align: center; }
.qr-countdown { margin: 0; font-size: 12px; color: var(--c-text-2); font-variant-numeric: tabular-nums; }
</style>
