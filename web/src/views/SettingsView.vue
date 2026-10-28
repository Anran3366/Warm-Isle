<template>
  <div class="settings-page">
    <div v-if="loading" class="card">
      <el-skeleton :rows="6" animated />
    </div>

    <template v-else-if="!error">
      <div class="card form-card access-card" :class="{ active: accessOpen }">
        <div class="access-head">
          <div>
            <h3 class="section-title">试运行 · 一键放通</h3>
            <p class="access-hint">
              打开后：自动通过好友申请，任何好友都能直接聊天（不再看白名单）。
              关闭后恢复白名单限制。
            </p>
          </div>
          <el-switch v-model="accessOpen" size="large" @change="(v: any) => saveAccess(!!v)" />
        </div>
        <p v-if="accessOpen" class="access-warn">当前处于放通状态，所有好友都能和机器人聊天</p>
      </div>

      <div class="card form-card">
        <h3 class="section-title">外观设置</h3>
        <el-form label-width="120px" class="settings-form">
          <el-form-item label="主题模式">
            <el-radio-group v-model="themeMode" @change="(v: any) => setThemeMode(v)">
              <el-radio-button value="system">跟随系统</el-radio-button>
              <el-radio-button value="light">浅色</el-radio-button>
              <el-radio-button value="dark">深色</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="主题色">
            <el-radio-group v-model="colorTheme" @change="(v: any) => setColorTheme(v)">
              <el-radio-button value="warm">暖色</el-radio-button>
              <el-radio-button value="cool">冷色</el-radio-button>
            </el-radio-group>
          </el-form-item>
        </el-form>
      </div>

      <div class="card form-card">
        <h3 class="section-title">模型设置</h3>
        <el-form :model="form" label-width="120px" class="settings-form">
          <el-form-item label="模型">
            <el-select v-model="form.model" style="width: 240px">
              <el-option label="DeepSeek Chat" value="deepseek-chat" />
              <el-option label="DeepSeek Flash" value="deepseek-flash" />
              <el-option label="DeepSeek V3" value="deepseek-v3" />
              <el-option label="Qwen Max" value="qwen-max" />
            </el-select>
          </el-form-item>
          <el-form-item label="温度">
            <el-slider v-model="form.temperature" :min="0" :max="1.5" :step="0.1" show-stops style="max-width: 320px" />
            <span class="form-hint">越高越有创意，越低越稳定</span>
          </el-form-item>
          <el-form-item label="每日预算">
            <el-input-number v-model="form.dailyBudget" :min="0.1" :max="10" :step="0.1" :precision="2" disabled />
            <span class="form-suffix">元/天（后端暂未支持，只读）</span>
          </el-form-item>
          <el-form-item label="上下文轮数">
            <el-input-number v-model="form.contextRounds" :min="5" :max="50" :step="5" />
            <span class="form-suffix">轮</span>
          </el-form-item>
          <el-form-item label="最大回复 tokens">
            <el-input-number v-model="form.maxTokens" :min="50" :max="2000" :step="50" />
            <span class="form-suffix">tokens</span>
          </el-form-item>
        </el-form>
      </div>

      <div class="card form-card">
        <h3 class="section-title">图片理解</h3>
        <el-form :model="form.vision" label-width="140px" class="settings-form">
          <el-form-item label="启用图片理解">
            <el-switch v-model="form.vision.enabled" />
            <span class="form-hint">开启后机器人可识别聊天中的图片</span>
          </el-form-item>
          <el-form-item label="每条消息最多图片">
            <el-input-number v-model="form.vision.maxPerMessage" :min="1" :max="9" />
            <span class="form-suffix">张</span>
          </el-form-item>
          <el-form-item label="图片最大边长">
            <el-input-number v-model="form.vision.maxSize" :min="512" :max="2048" :step="128" disabled />
            <span class="form-suffix">px（后端暂未支持，只读）</span>
          </el-form-item>
          <el-form-item label="每日图片上限">
            <el-input-number v-model="form.vision.dailyLimit" :min="0" :max="200" :step="10" />
            <span class="form-suffix">张（0 表示不限制）</span>
          </el-form-item>
          <el-form-item label="聊天表情自动入库">
            <el-switch v-model="form.vision.emojiAutoImport" />
            <span class="form-hint">聊天中收到的表情包自动收进素材库，状态为「待审核」，通过后才会被使用</span>
          </el-form-item>
          <el-form-item label=" ">
            <span class="privacy-hint">
              图片会先在本地做敏感检测：命中证件 / 银行卡 / 私密内容时，不解析也不上传。
            </span>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="saving" @click="save">保存设置</el-button>
            <span v-if="isDirty" class="unsaved-hint">有未保存的修改</span>
          </el-form-item>
        </el-form>
      </div>
      <div class="card form-card">
        <h3 class="section-title">回复形态（像人发微信）</h3>
        <el-form :model="form.reply" label-width="140px" class="settings-form">
          <el-form-item label="单条最大字数">
            <el-input-number v-model="form.reply.maxChars" :min="20" :max="200" :step="10" />
            <span class="form-suffix">字（超过就拆成几条发）</span>
          </el-form-item>
          <el-form-item label="拆条间隔">
            <el-input-number v-model="form.reply.splitDelayMs" :min="0" :max="5000" :step="200" />
            <span class="form-suffix">毫秒</span>
          </el-form-item>
          <el-form-item label="打字延迟">
            <el-input-number v-model="form.reply.typingMs" :min="0" :max="5000" :step="200" />
            <span class="form-suffix">毫秒（每条发送前的基础等待）</span>
          </el-form-item>
          <el-form-item label="自适应长度">
            <el-switch v-model="form.reply.adaptive" />
            <span class="form-hint">对方说得少就回得更短</span>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="saving" @click="save">保存设置</el-button>
            <span v-if="isDirty" class="unsaved-hint">有未保存的修改</span>
          </el-form-item>
        </el-form>
      </div>

      <div class="card form-card">
        <h3 class="section-title">登录密码</h3>
        <p class="form-hint">修改后台登录用的授权码，至少 6 位；改完其他设备的登录会失效。</p>
        <el-form label-width="120px" class="settings-form" @submit.prevent>
          <el-form-item label="当前密码">
            <el-input v-model="pwd.current" type="password" show-password autocomplete="current-password" style="max-width: 260px" />
          </el-form-item>
          <el-form-item label="新密码">
            <el-input v-model="pwd.next" type="password" show-password autocomplete="new-password" style="max-width: 260px" />
          </el-form-item>
          <el-form-item label="确认新密码">
            <el-input v-model="pwd.confirm" type="password" show-password autocomplete="new-password" style="max-width: 260px" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="pwd.saving" @click="changePassword">修改密码</el-button>
          </el-form-item>
        </el-form>
      </div>
    </template>

    <div v-else class="card error-card">
      <el-icon :size="40" color="var(--c-danger)"><WarningFilled /></el-icon>
      <p>加载失败：{{ error }}</p>
      <el-button type="primary" @click="loadData">重试</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'SettingsView' })
import { nextTick, ref, watch, onMounted } from 'vue'
import { WarningFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api/client'
import { DEFAULT_SETTINGS, normalizeSettings } from '@/api/adapters'
import type { Settings } from '@/api/types'
import { useUnsavedGuard } from '@/composables/useUnsavedGuard'
import { useTheme } from '@/composables/useTheme'

const { isDirty, markDirty, markClean } = useUnsavedGuard()
const { themeMode, colorTheme, setThemeMode, setColorTheme } = useTheme()

const form = ref<Settings>(normalizeSettings(DEFAULT_SETTINGS))
const loading = ref(true)
const accessOpen = ref(false)
const saving = ref(false)
const error = ref('')
let suppressDirty = false

watch(form, () => {
  if (!suppressDirty) markDirty()
}, { deep: true })

async function loadAccess() {
  try {
    const r = await api<{ open: boolean }>('/access')
    accessOpen.value = !!r.open
  } catch {
    accessOpen.value = false
  }
}

async function saveAccess(val: boolean) {
  try {
    await api('/access', { method: 'PUT', body: { open: val } })
    ElMessage.success(val ? '已开启放通：自动加好友 + 不限白名单' : '已关闭放通，恢复白名单限制')
  } catch (e: any) {
    accessOpen.value = !val
    ElMessage.error(e?.message ?? '保存失败')
  }
}

async function loadData() {
  loading.value = true
  error.value = ''
  try {
    const cfg = normalizeSettings(await api<unknown>('/settings'))
    suppressDirty = true
    form.value = cfg
    await nextTick()
    suppressDirty = false
    markClean()
  } catch (e: any) {
    error.value = e?.message ?? '未知错误'
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    const before = JSON.parse(JSON.stringify(form.value)) as Settings
    const saved = normalizeSettings(await api<unknown>('/settings', { method: 'PUT', body: form.value }))

    suppressDirty = true
    form.value = saved
    await nextTick()
    suppressDirty = false
    markClean()

    // 后端第 2 批只支持部分字段；没被接受的字段会从服务端值恢复，明确告知用户
    const ignored: string[] = []
    if (before.dailyBudget !== saved.dailyBudget) ignored.push('每日预算')
    if (before.vision.maxSize !== saved.vision.maxSize) ignored.push('图片最大边长')

    if (ignored.length > 0) {
      ElMessage.warning(`已保存；${ignored.join('、')} 后端暂未支持，已恢复服务端值`)
    } else {
      ElMessage.success('设置已保存')
    }
  } catch (e: any) {
    ElMessage.error(e?.message ?? '保存失败')
  } finally {
    saving.value = false
  }
}

const pwd = ref({ current: '', next: '', confirm: '', saving: false })

async function changePassword() {
  if (!pwd.value.current) {
    ElMessage.warning('请先输入当前密码')
    return
  }
  if (pwd.value.next.length < 6) {
    ElMessage.warning('新密码至少 6 位')
    return
  }
  if (pwd.value.next !== pwd.value.confirm) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  pwd.value.saving = true
  try {
    await api('/auth/password', {
      method: 'POST',
      body: { currentPassword: pwd.value.current, newPassword: pwd.value.next }
    })
    ElMessage.success('密码已修改，下次请用新密码登录')
    pwd.value = { current: '', next: '', confirm: '', saving: false }
  } catch (e: any) {
    ElMessage.error(e?.message ?? '修改失败')
  } finally {
    pwd.value.saving = false
  }
}

onMounted(() => {
  loadData()
  loadAccess()
})
</script>

<style scoped>
.access-card { border: 1px solid var(--c-border); transition: border-color .2s; }
.access-card.active { border-color: #e6a23c; box-shadow: 0 0 0 3px rgba(230,162,60,.12); }
.access-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; }
.access-hint { margin: 6px 0 0; font-size: 12.5px; color: var(--c-text-2); line-height: 1.7; max-width: 620px; }
.access-warn { margin: 12px 0 0; font-size: 12.5px; color: #e6a23c; }
.settings-page { display: flex; flex-direction: column; gap: 16px; }
'.form-card { max-width: 600px; }
.wl-tags { display: flex; flex-wrap: wrap; gap: 8px; margin: 12px 0; min-height: 24px; align-items: center; }
.wl-tag { font-variant-numeric: tabular-nums; }
.wl-add { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.muted { color: var(--c-text-2); font-size: 12.5px; }'
.section-title { margin: 0 0 16px; font-size: 15px; font-weight: 600; }
.settings-form { display: flex; flex-direction: column; gap: 4px; }
.form-suffix { margin-left: 8px; color: var(--c-text-2); font-size: 12px; }
.form-hint { margin-left: 12px; color: var(--c-text-2); font-size: 12px; }
.unsaved-hint { margin-left: 12px; color: var(--c-warn); font-size: 12px; }
.error-card { display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 48px; color: var(--c-text-2); }

@media (max-width: 768px) {
  .form-card { max-width: 100%; }
  .settings-form :deep(.el-form-item) { flex-direction: column; align-items: flex-start; }
  .settings-form :deep(.el-form-item__label) { width: auto !important; margin-bottom: 4px; }
  .settings-form :deep(.el-select), .settings-form :deep(.el-input-number) { width: 100% !important; }
  .settings-form :deep(.el-slider) { max-width: 100%; }
  .settings-form :deep(.el-button) { width: 100%; }
  .form-hint { margin-left: 0; margin-top: 4px; display: block; }
}
.privacy-hint {
  font-size: 12.5px;
  color: var(--c-text-2);
  line-height: 1.7;
}
</style>
