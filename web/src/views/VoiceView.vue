<template>
  <div class="voice-page">
    <div v-if="loading" class="card">
      <el-skeleton :rows="6" animated />
    </div>

    <template v-else-if="!error">
      <div class="card form-card">
        <h3 class="section-title">语音配置</h3>
        <el-form :model="form" label-width="100px" class="voice-form">
          <el-form-item label="音色">
            <el-select v-model="form.voice" style="width: 220px" filterable>
              <el-option v-for="v in voices" :key="v" :label="v" :value="v" />
            </el-select>
          </el-form-item>
          <el-form-item label="语速">
            <el-slider v-model="form.speed" :min="0.5" :max="1.5" :step="0.1" show-stops style="max-width: 320px" />
          </el-form-item>
          <el-form-item label="启用语音">
            <el-switch v-model="form.enabled" />
          </el-form-item>
          <el-form-item label="语音概率">
            <div class="prob-box">
              <div class="prob-head">
                <span class="prob-value">{{ chatProbability }}%</span>
                <span class="prob-caption">{{ probCaption }}</span>
              </div>
              <el-slider
                v-model="chatProbability"
                :min="0"
                :max="100"
                :step="5"
                :marks="{ 0: '从不', 50: '一半', 100: '总是' }"
                :format-tooltip="(v: number) => v + '%'"
                class="prob-slider"
              />
              <div class="prob-presets">
                <el-button size="small" round @click="setProb(0)">从不</el-button>
                <el-button size="small" round @click="setProb(10)">10%</el-button>
                <el-button size="small" round @click="setProb(30)">30%</el-button>
                <el-button size="small" round @click="setProb(50)">50%</el-button>
                <el-button size="small" round @click="setProb(100)">总是</el-button>
              </div>
              <div class="field-hint">
                每次回复都按这个概率决定用语音还是文字；不会因为你要求就发。
              </div>
            </div>
          </el-form-item>
          <el-form-item label="AI 语气指令">
            <el-switch v-model="form.styleLlm" />
            <span class="form-hint">按上下文让模型生成语气（更有感情），关闭则用规则判断</span>
          </el-form-item>
          <el-form-item label="每日语音上限">
            <el-input-number v-model="form.dailyLimit" :min="0" :max="50" :step="1" />
            <span class="field-hint inline">条/天（0 表示当天不发语音）</span>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="saving" @click="save">保存配置</el-button>
            <span v-if="isDirty" class="unsaved-hint">有未保存的修改</span>
          </el-form-item>
        </el-form>
      </div>

      <div class="card clone-card">
        <div class="card-head">
          <div>
            <h3 class="section-title">音色克隆</h3>
            <p class="desc">
              上传一段你自己（或已获授权）的录音：5–15 秒、安静环境、只有一个人在说话。上传后会先质检，再用这个音色念一句给你听。
            </p>
          </div>
          <el-switch
            v-if="clone.profiles.length"
            v-model="useClone"
            active-text="使用克隆音色"
            @change="toggleClone"
          />
        </div>

        <div v-if="clone.profiles.length" class="profile-list">
          <div
            v-for="p in clone.profiles"
            :key="p.id"
            class="profile-item"
            :class="{ active: p.id === clone.activeId }"
          >
            <div class="profile-main">
              <div class="profile-name">
                {{ p.name }}
                <el-tag size="small" :type="verdictType(p.verdict)" effect="plain" round>
                  {{ verdictText(p.verdict) }}
                </el-tag>
                <el-tag v-if="p.id === clone.activeId" size="small" type="success" round>当前</el-tag>
              </div>
              <div class="profile-meta">
                {{ p.seconds }}s · {{ (p.sampleRate / 1000).toFixed(1) }}kHz · {{ p.createdAt }}
                <template v-if="p.transcript">· “{{ p.transcript }}”</template>
              </div>
              <div v-if="p.issues.length" class="profile-issues">{{ p.issues.join('；') }}</div>
            </div>
            <div class="profile-actions">
              <el-button
                size="small"
                text
                :loading="previewingId === p.id"
                @click="preview(p)"
              >试听</el-button>
              <el-button v-if="p.id !== clone.activeId" size="small" text type="primary" @click="activate(p.id)">
                设为当前
              </el-button>
              <el-button size="small" text type="danger" @click="removeProfile(p)">删除</el-button>
            </div>
          </div>
        </div>

        <div class="clone-upload">
          <el-upload
            drag
            :auto-upload="false"
            :limit="1"
            accept="audio/*"
            :on-change="onFileChange"
            :on-remove="() => (file = null)"
            :file-list="fileList"
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">拖入音频文件，或 <em>点击选择</em></div>
            <template #tip>
              <div class="el-upload__tip">支持 wav / mp3 / m4a，≤ 10MB；越接近 10 秒效果越稳</div>
            </template>
          </el-upload>
          <div class="clone-actions">
            <el-input v-model="cloneName" placeholder="给这个音色起个名字" style="max-width: 220px" />
            <el-button type="primary" :loading="cloning" :disabled="!file" @click="uploadClone">
              学习音色
            </el-button>
          </div>
        </div>

        <div v-if="lastAnalysis" class="analysis-box" :class="lastAnalysis.verdict">
          <div class="analysis-head">
            <el-tag :type="verdictType(lastAnalysis.verdict)" effect="dark" round>
              {{ verdictText(lastAnalysis.verdict) }}
            </el-tag>
            <span class="analysis-metrics">
              {{ lastAnalysis.seconds.toFixed(1) }}s ·
              {{ (lastAnalysis.sampleRate / 1000).toFixed(1) }}kHz ·
              {{ lastAnalysis.channels }} 声道
              <template v-if="lastAnalysis.meanDb > -90"> · 平均 {{ lastAnalysis.meanDb.toFixed(0) }}dB</template>
              <template v-if="lastAnalysis.silenceRatio > 0"> · 静音 {{ Math.round(lastAnalysis.silenceRatio * 100) }}%</template>
            </span>
          </div>
          <div v-if="lastAnalysis.transcript" class="analysis-transcript">
            识别到：{{ lastAnalysis.transcript }}
          </div>
          <ul v-if="lastAnalysis.issues.length" class="analysis-issues">
            <li v-for="(issue, i) in lastAnalysis.issues" :key="i">{{ issue }}</li>
          </ul>
        </div>

        <div v-if="previewUrl" class="analysis-preview">
          <span class="preview-label">用这个音色试听：</span>
          <audio :src="previewUrl" controls autoplay style="height: 34px" />
        </div>
        <div v-else-if="previewError" class="analysis-preview error">{{ previewError }}</div>
      </div>

      <div class="card test-card">
        <h3 class="section-title">试听</h3>
        <div class="test-row">
          <el-input v-model="testText" placeholder="输入要测试的文字…" style="max-width: 400px" />
          <el-button type="primary" :loading="testing" :disabled="!testText.trim()" @click="test">
            <el-icon><Microphone /></el-icon>生成语音
          </el-button>
        </div>
        <div v-if="testUrl" class="test-result">
          <el-tag type="success" effect="plain" round>生成成功</el-tag>
          <audio :src="testUrl" controls style="height: 34px" />
        </div>
        <div v-else-if="testError" class="test-placeholder error">
          <span>生成失败：{{ testError }}</span>
          <el-button text size="small" type="primary" @click="test">重试</el-button>
        </div>
        <div v-else class="test-placeholder">
          还没有试听结果，输入一句话生成语音试试。
        </div>
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
defineOptions({ name: 'VoiceView' })
import { ref, computed, watch, onMounted } from 'vue'
import { Microphone, WarningFilled, UploadFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api/client'
import type { AudioAnalysis, VoiceConfig, VoiceProfileView } from '@/api/types'
import { useUnsavedGuard } from '@/composables/useUnsavedGuard'

const { isDirty, markDirty, markClean } = useUnsavedGuard()

const form = ref({ provider: 'mimo', voice: '茉莉', speed: 1.0, enabled: true, chatProbability: 0.2, dailyLimit: 3, styleLlm: true })
const chatProbability = ref(20)
const voices = ref<string[]>([])
const clone = ref<{
  enabled: boolean
  hasRef: boolean
  name: string
  activeId: string | null
  profiles: VoiceProfileView[]
}>({ enabled: false, hasRef: false, name: '', activeId: null, profiles: [] })
const useClone = ref(false)
const cloneName = ref('我的音色')
const file = ref<File | null>(null)
const fileList = ref<any[]>([])
const cloning = ref(false)
const lastAnalysis = ref<AudioAnalysis | null>(null)
const previewUrl = ref('')
const previewError = ref('')
const previewingId = ref('')

const loading = ref(true)
const saving = ref(false)
const error = ref('')
const testing = ref(false)
const testText = ref('')
const testUrl = ref('')
const testError = ref('')

watch(form, () => markDirty(), { deep: true })

watch(chatProbability, (v) => {
  form.value.chatProbability = v / 100
  markDirty()
})

const probCaption = computed(() => {
  if (chatProbability.value === 0) {
    return '不会主动发语音'
  }
  if (chatProbability.value >= 100) {
    return '每条回复都用语音'
  }
  const every = Math.max(1, Math.round(100 / chatProbability.value))
  return '大约每 ' + every + ' 条回复会有 1 条语音'
})

function setProb(value: number) {
  chatProbability.value = value
}

async function loadData() {
  loading.value = true
  error.value = ''
  try {
    const cfg = await api<VoiceConfig>('/voice/config')
    form.value = {
      provider: cfg.provider ?? 'mimo',
      voice: cfg.voice ?? '茉莉',
      speed: cfg.speed ?? 1.0,
      enabled: cfg.enabled ?? true,
      chatProbability: cfg.chatProbability ?? 0.2,
      dailyLimit: cfg.dailyLimit ?? 3,
      styleLlm: cfg.styleLlm ?? true
    }
    chatProbability.value = Math.round((cfg.chatProbability ?? 0.2) * 100)
    voices.value = cfg.voices?.length ? cfg.voices : [form.value.voice]
    clone.value = {
      enabled: cfg.clone?.enabled ?? false,
      hasRef: cfg.clone?.hasRef ?? false,
      name: cfg.clone?.name ?? '',
      activeId: cfg.clone?.activeId ?? null,
      profiles: cfg.clone?.profiles ?? []
    }
    useClone.value = clone.value.enabled && clone.value.hasRef
    cloneName.value = clone.value.name || '我的音色'
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
    await api('/voice/config', { method: 'PUT', body: form.value })
    markClean()
    ElMessage.success('配置已保存')
  } catch (e: any) {
    ElMessage.error(e?.message ?? '保存失败')
  } finally {
    saving.value = false
  }
}

function onFileChange(f: any) {
  file.value = f.raw as File
  fileList.value = [f]
}

async function uploadClone() {
  if (!file.value) return
  cloning.value = true
  previewUrl.value = ''
  previewError.value = ''
  lastAnalysis.value = null
  try {
    const fd = new FormData()
    fd.append('file', file.value)
    fd.append('name', cloneName.value || '我的音色')
    const res = await api<{
      ok: boolean
      code?: string
      message?: string
      profile?: VoiceProfileView
      analysis?: AudioAnalysis
      previewUrl?: string | null
      previewError?: string | null
    }>('/voice/clone', { method: 'POST', formData: fd })

    lastAnalysis.value = res.analysis ?? null
    previewUrl.value = res.previewUrl ?? ''
    previewError.value = res.previewError ?? ''

    if (!res.ok) {
      ElMessage.error(res.message ?? '音频不合格，请换一段再试')
      return
    }
    ElMessage.success(`学习完成：${res.profile?.name ?? cloneName.value}（${res.analysis?.seconds.toFixed(1) ?? '?'} 秒）`)
    file.value = null
    fileList.value = []
    await loadData()
  } catch (e: any) {
    ElMessage.error(e?.message ?? '学习失败')
  } finally {
    cloning.value = false
  }
}

function verdictType(verdict: string) {
  if (verdict === 'ok') return 'success'
  if (verdict === 'warn') return 'warning'
  return 'danger'
}

function verdictText(verdict: string) {
  if (verdict === 'ok') return '质检通过'
  if (verdict === 'warn') return '有提醒'
  return '未通过'
}

async function activate(id: string) {
  try {
    await api(`/voice/clones/${id}/activate`, { method: 'PUT' })
    ElMessage.success('已切换当前音色')
    await loadData()
  } catch (e: any) {
    ElMessage.error(e?.message ?? '切换失败')
  }
}

async function preview(profile: VoiceProfileView) {
  previewingId.value = profile.id
  previewError.value = ''
  try {
    const res = await api<{ ok: boolean; fileUrl: string }>(`/voice/clones/${profile.id}/preview`, {
      method: 'POST'
    })
    previewUrl.value = res.fileUrl
  } catch (e: any) {
    previewError.value = e?.message ?? '试听失败'
    ElMessage.error(previewError.value)
  } finally {
    previewingId.value = ''
  }
}

async function toggleClone(val: string | number | boolean) {
  try {
    await api('/voice/config', { method: 'PUT', body: { cloneEnabled: Boolean(val) } })
    ElMessage.success(val ? '已切换到克隆音色' : '已切回内置音色')
  } catch (e: any) {
    ElMessage.error(e?.message ?? '切换失败')
  }
}

async function removeProfile(profile: VoiceProfileView) {
  try {
    await ElMessageBox.confirm(
      `删除「${profile.name}」后需要重新上传样本，确定吗？`,
      '删除音色',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await api(`/voice/clones/${profile.id}`, { method: 'DELETE' })
    ElMessage.success('已删除')
    previewUrl.value = ''
    previewError.value = ''
    await loadData()
  } catch (e: any) {
    ElMessage.error(e?.message ?? '删除失败')
  }
}

async function test() {
  if (!testText.value.trim()) return
  testing.value = true
  testUrl.value = ''
  testError.value = ''
  try {
    const res = await api<{ fileUrl: string }>('/voice/test', {
      method: 'POST',
      body: { text: testText.value }
    })
    testUrl.value = res.fileUrl
    ElMessage.success('语音已生成')
  } catch (e: any) {
    testError.value = e?.message ?? '生成失败'
    ElMessage.error(testError.value)
  } finally {
    testing.value = false
  }
}

onMounted(() => loadData())
</script>

<style scoped>
.voice-page { display: flex; flex-direction: column; gap: 16px; }
.form-card, .clone-card, .test-card { max-width: 640px; }
.section-title { margin: 0 0 16px; font-size: 15px; font-weight: 600; }
.voice-form { display: flex; flex-direction: column; gap: 4px; }
.desc { margin: -6px 0 14px; color: var(--c-text-2); font-size: 12.5px; line-height: 1.6; }
.card-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.clone-current { display: flex; align-items: center; gap: 14px; margin-bottom: 14px; flex-wrap: wrap; }
.clone-actions { display: flex; align-items: center; gap: 10px; margin-top: 12px; flex-wrap: wrap; }
.profile-list { display: flex; flex-direction: column; gap: 8px; margin-bottom: 16px; }
.profile-item {
  display: flex; align-items: flex-start; justify-content: space-between; gap: 12px;
  padding: 10px 12px; border: 1px solid var(--c-border); border-radius: 10px;
  transition: border-color 0.15s, background 0.15s;
}
.profile-item.active { border-color: var(--c-primary); background: color-mix(in srgb, var(--c-primary) 6%, transparent); }
.profile-main { min-width: 0; flex: 1; }
.profile-name { display: flex; align-items: center; gap: 8px; font-size: 13.5px; font-weight: 600; flex-wrap: wrap; }
.profile-meta {
  margin-top: 4px; color: var(--c-text-2); font-size: 12px;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.profile-issues { margin-top: 4px; color: var(--c-warn); font-size: 12px; line-height: 1.5; }
.profile-actions { display: flex; align-items: center; gap: 2px; flex-shrink: 0; }
.analysis-box {
  margin-top: 14px; padding: 12px; border-radius: 10px;
  border: 1px solid var(--c-border); background: var(--c-bg-2, rgba(0, 0, 0, 0.02));
  font-size: 12.5px;
}
.analysis-box.reject { border-color: var(--c-danger); }
.analysis-box.warn { border-color: var(--c-warn); }
.analysis-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.analysis-metrics { color: var(--c-text-2); font-variant-numeric: tabular-nums; }
.analysis-transcript { margin-top: 8px; color: var(--c-text-1); }
.analysis-issues { margin: 8px 0 0; padding-left: 18px; color: var(--c-warn); line-height: 1.6; }
.analysis-preview { display: flex; align-items: center; gap: 10px; margin-top: 14px; flex-wrap: wrap; font-size: 12.5px; }
.analysis-preview.error { color: var(--c-danger); }
.preview-label { color: var(--c-text-2); }
.test-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.test-result { display: flex; align-items: center; gap: 10px; margin-top: 12px; flex-wrap: wrap; }
.test-placeholder {
  display: flex; align-items: center; justify-content: space-between; gap: 10px;
  margin-top: 12px; padding: 11px 12px; border-radius: 10px;
  border: 1px dashed var(--c-border); color: var(--c-text-2);
  font-size: 12.5px; line-height: 1.6;
}
.test-placeholder.error { border-color: var(--c-danger); }
.prob-box { width: 100%; max-width: 560px; }
.prob-head { display: flex; align-items: baseline; gap: 10px; margin-bottom: 2px; }
.prob-value { font-size: 22px; font-weight: 600; font-variant-numeric: tabular-nums; line-height: 1; }
.prob-caption { color: var(--c-text-2); font-size: 12.5px; }
.prob-slider { padding: 0 6px 10px; }
.prob-presets { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 18px; }
.field-hint { color: var(--c-text-2); font-size: 12px; line-height: 1.5; margin-top: 10px; }
.unsaved-hint { margin-left: 12px; color: var(--c-warn); font-size: 12px; }
.error-card { display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 48px; color: var(--c-text-2); }

@media (max-width: 768px) {
  .form-card, .clone-card, .test-card { max-width: 100%; }
  .test-row { flex-direction: column; align-items: stretch; }
  .test-row :deep(.el-input), .test-row :deep(.el-button) { width: 100%; }
}
</style>
