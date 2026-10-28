<template>
  <div class="topic-page">
    <div v-if="loading" class="skeleton-grid">
      <el-skeleton class="card" :rows="10" animated />
      <el-skeleton class="card" :rows="8" animated />
    </div>

    <template v-else-if="!error">
      <section class="card hero" :class="{ 'hero-on': form.enabled }">
        <div class="hero-main">
          <div class="hero-icon" aria-hidden="true">
            <el-icon :size="24"><CircleClose /></el-icon>
          </div>
          <div class="hero-text">
            <h2 class="hero-title">给聊天划一条舒服的边界</h2>
            <p class="hero-desc">
              碰到色情、政治这些话题时，让小满自己绕开，不用你每次都盯着。
              配置只影响机器人，不会删除已有聊天记录。
            </p>
          </div>
        </div>
        <div class="hero-control">
          <el-switch
            v-model="form.enabled"
            size="large"
            inline-prompt
            active-text="开"
            inactive-text="关"
            aria-label="启用话题屏蔽"
          />
          <span class="hero-state">{{ form.enabled ? '已启用' : '已关闭' }}</span>
        </div>
        <div class="hero-stats">
          <div class="stat">
            <b class="num">{{ enabledPresetCount }}</b>
            <span>个预设话题生效</span>
          </div>
          <div class="stat">
            <b class="num">{{ form.customTopics.length }}</b>
            <span>个自定义话题</span>
          </div>
          <div class="stat">
            <b>{{ currentActionLabel }}</b>
            <span>命中后怎么回应</span>
          </div>
        </div>
      </section>

      <div class="config-grid">
        <div class="config-col">
          <div class="card">
            <div class="section-head">
              <div>
                <h3 class="section-title">预设话题</h3>
                <p class="section-desc">打开需要避开的话题；每个话题的关键词都可以自己增删。</p>
              </div>
              <el-tag v-if="!form.enabled" type="info" effect="plain" size="small" round>总开关关闭中</el-tag>
            </div>

            <div v-if="form.presets.length === 0" class="preset-empty">
              <el-icon :size="18"><InfoFilled /></el-icon>
              <span>没有可用的预设话题，可以通过「导入」恢复一份配置。</span>
            </div>
            <div v-else class="preset-grid">
              <div
                v-for="(preset, presetIndex) in form.presets"
                :key="preset.id"
                class="preset-card"
                :class="{ on: preset.enabled && form.enabled, dim: !form.enabled }"
              >
                <div class="preset-top">
                  <span class="preset-icon" aria-hidden="true">
                    <el-icon :size="16"><component :is="presetIcon(preset.id)" /></el-icon>
                  </span>
                  <el-switch v-model="preset.enabled" size="small" :aria-label="'启用' + preset.label" />
                </div>
                <h4 class="preset-name">{{ preset.label }}</h4>
                <p class="preset-desc">{{ preset.desc }}</p>
                <button
                  type="button"
                  class="preset-more"
                  :aria-expanded="expandedPreset === preset.id"
                  :aria-controls="'preset-kw-' + presetIndex"
                  @click="togglePreset(preset.id)"
                >
                  {{ expandedPreset === preset.id ? '收起关键词' : `${preset.keywords.length} 个关键词` }}
                  <el-icon :size="12">
                    <ArrowUp v-if="expandedPreset === preset.id" />
                    <ArrowDown v-else />
                  </el-icon>
                </button>

                <div
                  v-if="expandedPreset === preset.id"
                  :id="'preset-kw-' + presetIndex"
                  class="keywords"
                >
                  <el-tag
                    v-for="(keyword, index) in preset.keywords"
                    :key="keyword + index"
                    size="small"
                    effect="plain"
                    closable
                    @close="removeKeyword(preset, index)"
                  >{{ keyword }}</el-tag>
                  <el-input
                    v-model="keywordDraft[preset.id]"
                    class="kw-input"
                    size="small"
                    maxlength="20"
                    placeholder="+ 添加关键词"
                    :aria-label="'给' + preset.label + '添加关键词'"
                    @keyup.enter="addKeyword(preset)"
                  />
                </div>
              </div>
            </div>
          </div>

          <div class="card">
            <div class="section-head">
              <div>
                <h3 class="section-title">自定义话题</h3>
                <p class="section-desc">写你个人不想聊的具体话题，消息里出现这个词就会命中。</p>
              </div>
            </div>

            <div class="custom-add">
              <el-input
                v-model="customDraft"
                maxlength="20"
                show-word-limit
                placeholder="例如：前任、体重、某个明星"
                aria-label="自定义不想聊的话题"
                @keyup.enter="addCustomTopic"
              />
              <el-button type="primary" plain :disabled="!customDraft.trim()" @click="addCustomTopic">
                <el-icon><Plus /></el-icon>添加
              </el-button>
            </div>

            <div v-if="form.customTopics.length" class="custom-tags">
              <el-tag
                v-for="(topic, index) in form.customTopics"
                :key="topic + index"
                closable
                effect="plain"
                round
                @close="removeCustomTopic(index)"
              >{{ topic }}</el-tag>
            </div>
            <p v-else class="mini-empty">还没有自定义话题，预设话题已经够用也可以留空。</p>
            <p class="hint">匹配方式是「消息包含该词」，建议写短词，不要写整句话。</p>
          </div>

          <div class="card">
            <div class="section-head">
              <div>
                <h3 class="section-title">命中后怎么回应</h3>
                <p class="section-desc">机器人会按这里的方式处理被屏蔽的话题。</p>
              </div>
            </div>

            <div class="action-grid" role="radiogroup" aria-label="命中后的处理方式">
              <button
                v-for="option in topicActions"
                :key="option.value"
                type="button"
                class="action-card"
                :class="{ active: form.action === option.value }"
                role="radio"
                :aria-checked="form.action === option.value"
                @click="selectAction(option.value)"
              >
                <span class="action-icon" aria-hidden="true">
                  <el-icon :size="17"><component :is="option.icon" /></el-icon>
                </span>
                <b>{{ option.label }}</b>
                <span class="action-desc">{{ option.desc }}</span>
              </button>
            </div>

            <div class="crisis-hint">
              <el-icon :size="14"><WarningFilled /></el-icon>
              <span>自伤/自杀等危机话题不建议用「不作回复」；遇到时更需要先关心对方并引导求助。</span>
            </div>

            <div v-if="form.action !== 'silent'" class="reply-box">
              <div class="reply-head">
                <span class="reply-label">参考话术</span>
                <el-button text size="small" @click="resetReply">恢复默认</el-button>
              </div>
              <el-input
                v-model="form.replyTemplate"
                type="textarea"
                :rows="3"
                maxlength="160"
                show-word-limit
                placeholder="被问到时可以这样说…"
                aria-label="命中后的参考话术"
              />
              <p class="hint">
                这句话只是给模型的参考，不会逐字回复；<code>{topic}</code> 会替换成命中的话题名。
              </p>
            </div>
            <div v-else class="silent-note">
              <el-icon><MuteNotification /></el-icon>
              <span>命中时这条消息不接，等对方换话题。建议只在确实不想被打扰时使用。</span>
            </div>
          </div>

          <div class="card save-bar">
            <div class="save-info">
              <el-icon :size="15">
                <WarningFilled v-if="isDirty" />
                <CircleCheck v-else />
              </el-icon>
              <span v-if="isDirty" class="unsaved">有未保存的修改</span>
              <span v-else class="saved">配置已同步</span>
            </div>
            <div class="save-actions">
              <el-button text size="small" :disabled="!isDirty" @click="discardChanges">放弃修改</el-button>
              <el-button text size="small" @click="exportConfig">导出</el-button>
              <el-button text size="small" @click="triggerImport">导入</el-button>
              <el-button type="primary" :loading="saving" @click="save">保存配置</el-button>
            </div>
            <input
              ref="importInput"
              type="file"
              accept=".json,application/json"
              class="visually-hidden"
              tabindex="-1"
              aria-hidden="true"
              @change="onImportFile"
            />
          </div>
        </div>

        <div class="aside-col">
          <div class="card preview-card">
            <div class="section-head row">
              <div>
                <h3 class="section-title">拼进 Prompt 的样子</h3>
                <p class="section-desc">保存后由后端注入 system prompt，模型据此回避。</p>
              </div>
              <el-button text size="small" :disabled="!promptActive" aria-label="复制 Prompt 片段" @click="copyPrompt">
                <el-icon><CopyDocument /></el-icon>复制
              </el-button>
            </div>
            <pre class="prompt-preview" :class="{ empty: !promptActive }">{{ promptPreview }}</pre>
          </div>

          <div class="card test-card">
            <div class="section-head">
              <div>
                <h3 class="section-title">试一句话</h3>
                <p class="section-desc">只按当前配置做本地匹配，不会发给模型。</p>
              </div>
            </div>

            <el-input
              v-model="testText"
              type="textarea"
              :rows="2"
              maxlength="200"
              placeholder="粘贴一句聊天内容，看看会不会被拦…"
              aria-label="用于检测的聊天内容"
              @keyup.ctrl.enter="runTest"
            />

            <div class="example-row">
              <button
                v-for="example in examples"
                :key="example"
                type="button"
                class="example-chip"
                @click="useExample(example)"
              >{{ example }}</button>
            </div>

            <div class="test-actions">
              <span class="hint">Ctrl + Enter 快速检测</span>
              <el-button type="primary" :disabled="!testText.trim()" @click="runTest">检测</el-button>
            </div>

            <div v-if="testResult" class="test-result" :class="'tone-' + testTone" aria-live="polite">
              <div class="result-head">
                <el-icon :size="16">
                  <WarningFilled v-if="testTone === 'blocked' || testTone === 'crisis'" />
                  <CircleCheck v-else-if="testTone === 'safe'" />
                  <InfoFilled v-else />
                </el-icon>
                <b>{{ testTitle }}</b>
              </div>
              <p class="test-quote">
                <span
                  v-for="(segment, index) in highlightedTest"
                  :key="index"
                  :class="{ hit: segment.hit }"
                >{{ segment.text }}</span>
              </p>
              <p class="result-desc">{{ testDesc }}</p>
              <div v-if="testResult.matches.length" class="match-list">
                <span
                  v-for="match in testResult.matches"
                  :key="match.source + match.topic + match.keyword"
                  class="match-chip"
                >
                  {{ match.topic }}<em>{{ match.keyword }}</em>
                </span>
              </div>
            </div>
          </div>
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
defineOptions({ name: 'TopicBlockView' })
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import {
  ArrowDown, ArrowUp, CircleCheck, CircleClose, CopyDocument, InfoFilled,
  MuteNotification, Plus, WarningFilled
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api/client'
import type { TopicAction, TopicBlockConfig, TopicPreset } from '@/api/types'
import { useUnsavedGuard } from '@/composables/useUnsavedGuard'
import { copyToClipboard } from '@/utils/format'
import {
  CRISIS_ADVICE, DEFAULT_REPLY_TEMPLATES, TOPIC_ACTIONS, actionLabel,
  buildTopicBlockPrompt, cloneTopicBlockConfig, defaultReplyTemplate, detectCrisis,
  highlightMatches, matchTopicBlock, parseTopicBlockConfig, presetIcon,
  serializeTopicBlockConfig, type TopicMatch
} from '@/utils/topicBlock'

interface TestResult {
  text: string
  matches: TopicMatch[]
}

const { isDirty, markDirty, markClean } = useUnsavedGuard()

const topicActions = TOPIC_ACTIONS
const examples = ['发张裸照看看', '你觉得现在的政治局势怎么样', '今天股市怎么样', '加个微信，有兼职介绍']

const form = ref<TopicBlockConfig>({
  enabled: true,
  presets: [],
  customTopics: [],
  action: 'deflect',
  replyTemplate: DEFAULT_REPLY_TEMPLATES.deflect
})
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const expandedPreset = ref('')
const keywordDraft = ref<Record<string, string>>({})
const customDraft = ref('')
const testText = ref('')
const testResult = ref<TestResult | null>(null)
const initialConfig = ref<TopicBlockConfig | null>(null)
const importInput = ref<HTMLInputElement | null>(null)

let suppressDirty = false

const enabledPresetCount = computed(() => form.value.presets.filter(p => p.enabled).length)
const currentActionLabel = computed(() => actionLabel(form.value.action))
const promptActive = computed(() => buildTopicBlockPrompt(form.value).length > 0)
const promptPreview = computed(() => {
  if (promptActive.value) return buildTopicBlockPrompt(form.value)
  if (!form.value.enabled) return '（话题屏蔽已关闭，不会注入这段提示）'
  return '（还没有启用任何话题，保存后不会注入这段提示）'
})

watch(form, () => {
  if (suppressDirty) return
  markDirty()
  testResult.value = null
}, { deep: true })

const crisisKeyword = computed(() => (testResult.value ? detectCrisis(testResult.value.text) : null))
const highlightedTest = computed(() => (
  testResult.value ? highlightMatches(testResult.value.text, testResult.value.matches) : []
))

const testTone = computed(() => {
  if (!testResult.value) return 'muted'
  if (crisisKeyword.value) return 'crisis'
  if (!form.value.enabled) return 'muted'
  return testResult.value.matches.length > 0 ? 'blocked' : 'safe'
})

const testTitle = computed(() => {
  if (!testResult.value) return ''
  if (crisisKeyword.value) return '安全提醒'
  if (!form.value.enabled) return '屏蔽未启用'
  return testResult.value.matches.length > 0 ? '会被拦截' : '不会拦截'
})

const testDesc = computed(() => {
  const result = testResult.value
  if (!result) return ''
  if (crisisKeyword.value) return CRISIS_ADVICE
  const count = result.matches.length
  if (!form.value.enabled) {
    return count > 0
      ? `这句话命中了 ${count} 个规则，但总开关关着，不会触发拦截。`
      : '总开关关着，这句话不会被拦截。'
  }
  if (count === 0) return '没有命中任何已启用的话题词，会正常聊天。'
  return `命中 ${count} 个规则，机器人会「${currentActionLabel.value}」。`
})

async function loadData() {
  loading.value = true
  error.value = ''
  try {
    const config = await api<TopicBlockConfig>('/topic-block')
    suppressDirty = true
    form.value = config
    initialConfig.value = cloneTopicBlockConfig(config)
    keywordDraft.value = Object.fromEntries(config.presets.map(p => [p.id, '']))
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
  if (form.value.enabled) {
    if (enabledPresetCount.value === 0 && form.value.customTopics.length === 0) {
      ElMessage.warning('请至少开启一个预设话题，或添加一个自定义话题')
      return
    }
    if (form.value.action !== 'silent' && !form.value.replyTemplate.trim()) {
      ElMessage.warning('请填写参考话术，或把处理方式改成「不作回复」')
      return
    }
  }

  saving.value = true
  try {
    const saved = await api<TopicBlockConfig>('/topic-block', { method: 'PUT', body: form.value })
    suppressDirty = true
    form.value = saved
    initialConfig.value = cloneTopicBlockConfig(saved)
    keywordDraft.value = Object.fromEntries(saved.presets.map(p => [p.id, '']))
    await nextTick()
    suppressDirty = false
    markClean()
    testResult.value = null
    ElMessage.success('话题屏蔽已保存')
  } catch (e: any) {
    ElMessage.error(e?.message ?? '保存失败')
  } finally {
    saving.value = false
  }
}

async function discardChanges() {
  if (!initialConfig.value) return
  suppressDirty = true
  form.value = cloneTopicBlockConfig(initialConfig.value)
  keywordDraft.value = Object.fromEntries(form.value.presets.map(p => [p.id, '']))
  expandedPreset.value = ''
  testResult.value = null
  await nextTick()
  suppressDirty = false
  markClean()
  ElMessage.success('已恢复为上次保存的配置')
}

function exportConfig() {
  const blob = new Blob([serializeTopicBlockConfig(form.value)], { type: 'application/json;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  const stamp = new Date().toISOString().slice(0, 10)
  link.href = url
  link.download = `topic-block-${stamp}.json`
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
  ElMessage.success('配置已导出')
}

function triggerImport() {
  importInput.value?.click()
}

async function onImportFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (file.size > 512 * 1024) {
    ElMessage.error('配置文件不能超过 512KB')
    return
  }
  try {
    const imported = parseTopicBlockConfig(await file.text())
    form.value = imported
    keywordDraft.value = Object.fromEntries(imported.presets.map(p => [p.id, '']))
    expandedPreset.value = ''
    testResult.value = null
    ElMessage.success('配置已导入，记得保存')
  } catch (e: any) {
    ElMessage.error(e?.message ?? '导入失败')
  }
}

function togglePreset(id: string) {
  expandedPreset.value = expandedPreset.value === id ? '' : id
}

function addKeyword(preset: TopicPreset) {
  const value = (keywordDraft.value[preset.id] ?? '').trim()
  if (!value) return
  if (preset.keywords.some(k => k.toLowerCase() === value.toLowerCase())) {
    ElMessage.warning('这个关键词已经有了')
    return
  }
  if (preset.keywords.length >= 50) {
    ElMessage.warning('单个话题最多 50 个关键词')
    return
  }
  preset.keywords.push(value)
  keywordDraft.value[preset.id] = ''
}

function removeKeyword(preset: TopicPreset, index: number) {
  preset.keywords.splice(index, 1)
}

function addCustomTopic() {
  const value = customDraft.value.trim()
  if (!value) return
  if (form.value.customTopics.some(t => t.toLowerCase() === value.toLowerCase())) {
    ElMessage.warning('这个自定义话题已经有了')
    return
  }
  if (form.value.customTopics.length >= 30) {
    ElMessage.warning('最多添加 30 个自定义话题')
    return
  }
  form.value.customTopics.push(value)
  customDraft.value = ''
}

function removeCustomTopic(index: number) {
  form.value.customTopics.splice(index, 1)
}

function selectAction(action: TopicAction) {
  const previousDefault = defaultReplyTemplate(form.value.action)
  const current = form.value.replyTemplate.trim()
  form.value.action = action
  if (action !== 'silent' && (!current || current === previousDefault)) {
    form.value.replyTemplate = defaultReplyTemplate(action)
  }
}

function resetReply() {
  form.value.replyTemplate = defaultReplyTemplate(form.value.action)
}

function runTest() {
  const text = testText.value.trim()
  if (!text) return
  testResult.value = { text, matches: matchTopicBlock(text, form.value) }
}

function useExample(example: string) {
  testText.value = example
  runTest()
}

async function copyPrompt() {
  const ok = await copyToClipboard(promptPreview.value)
  ElMessage[ok ? 'success' : 'error'](ok ? '已复制 Prompt 片段' : '复制失败')
}

onMounted(() => loadData())
</script>

<style scoped>
.topic-page { display: flex; flex-direction: column; gap: 16px; }
.skeleton-grid { display: grid; grid-template-columns: minmax(0, 1fr) 380px; gap: 16px; }

.hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 14px 24px;
  background: linear-gradient(135deg, var(--c-primary-weak), var(--c-surface) 70%);
  border-color: var(--c-border);
  transition: border-color 0.2s ease;
}
.hero-on { border-color: var(--c-primary); }
.hero-main { display: flex; align-items: flex-start; gap: 14px; min-width: 0; }
.hero-icon {
  width: 44px; height: 44px; border-radius: 13px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  background: var(--c-surface); color: var(--c-primary-dark);
  border: 1px solid var(--c-border);
}
.hero-title { margin: 0 0 6px; font-size: 18px; font-weight: 650; letter-spacing: 0.2px; }
.hero-desc { margin: 0; max-width: 620px; color: var(--c-text-2); font-size: 13px; line-height: 1.7; }
.hero-control { display: flex; align-items: center; gap: 10px; align-self: flex-start; }
.hero-state { font-size: 13px; color: var(--c-text-2); min-width: 42px; }
.hero-stats {
  grid-column: 1 / -1;
  display: flex; flex-wrap: wrap; gap: 12px 40px;
  padding-top: 14px; border-top: 1px dashed var(--c-border);
}
.stat { display: flex; align-items: baseline; gap: 8px; }
.stat b { font-size: 17px; font-weight: 650; color: var(--c-text); }
.stat span { font-size: 12px; color: var(--c-text-2); }

.config-grid { display: grid; grid-template-columns: minmax(0, 1fr) 380px; gap: 16px; align-items: start; }
.config-col { display: flex; flex-direction: column; gap: 16px; min-width: 0; }
.aside-col { display: flex; flex-direction: column; gap: 16px; position: sticky; top: 0; align-self: start; }

.section-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.section-head.row { align-items: flex-start; }
.section-title { margin: 0 0 5px; font-size: 15px; font-weight: 600; }
.section-desc { margin: 0; color: var(--c-text-2); font-size: 12.5px; line-height: 1.6; }

.preset-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.preset-empty {
  display: flex; align-items: center; gap: 8px;
  padding: 18px; border: 1px dashed var(--c-border); border-radius: 12px;
  color: var(--c-text-2); font-size: 13px;
}
.preset-card {
  display: flex; flex-direction: column;
  padding: 14px; border: 1px solid var(--c-border);
  border-radius: 12px; background: var(--c-bg);
  transition: border-color 0.18s ease, background 0.18s ease, opacity 0.18s ease;
}
.preset-card.on { border-color: var(--c-primary); background: var(--c-surface); }
.preset-card.dim { opacity: 0.72; }
.preset-top { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.preset-icon {
  width: 30px; height: 30px; border-radius: 9px;
  display: flex; align-items: center; justify-content: center;
  background: var(--c-primary-weak); color: var(--c-primary-dark);
}
.preset-name { margin: 0 0 4px; font-size: 14px; font-weight: 600; }
.preset-desc { margin: 0 0 10px; color: var(--c-text-2); font-size: 12px; line-height: 1.55; min-height: 34px; }
.preset-more {
  display: inline-flex; align-items: center; gap: 4px; align-self: flex-start;
  padding: 2px 0; border: none; background: none; cursor: pointer;
  color: var(--c-primary-dark); font-size: 12px; font-family: inherit;
}
.preset-more:hover { text-decoration: underline; }

.keywords { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 12px; padding-top: 12px; border-top: 1px dashed var(--c-border); }
.kw-input { width: 112px; }
.kw-input :deep(.el-input__inner) { font-size: 12px; }

.custom-add { display: flex; gap: 8px; }
.custom-add .el-input { flex: 1; }
.custom-tags { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 14px; }
.mini-empty { margin: 14px 0 0; color: var(--c-text-2); font-size: 12.5px; }
.hint { margin: 10px 0 0; color: var(--c-text-2); font-size: 12px; line-height: 1.65; }
.hint code {
  padding: 1px 5px; border-radius: 4px;
  background: var(--c-bg); border: 1px solid var(--c-border);
  font-family: 'SF Mono', 'Consolas', monospace; font-size: 11.5px;
}

.action-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
.action-card {
  display: flex; flex-direction: column; align-items: flex-start; gap: 4px;
  padding: 12px; text-align: left; cursor: pointer;
  border: 1px solid var(--c-border); border-radius: 12px;
  background: var(--c-bg); color: var(--c-text); font-family: inherit;
  transition: border-color 0.18s ease, background 0.18s ease;
}
.action-card:hover { border-color: var(--c-primary); }
.action-card.active { border-color: var(--c-primary); background: var(--c-primary-weak); }
.action-icon { color: var(--c-primary-dark); display: flex; }
.action-card b { font-size: 13px; font-weight: 600; }
.action-desc { font-size: 11.5px; line-height: 1.5; color: var(--c-text-2); }
.reply-box { margin-top: 16px; padding-top: 14px; border-top: 1px dashed var(--c-border); }
.reply-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.reply-label { font-size: 13px; font-weight: 600; }
.silent-note {
  display: flex; align-items: flex-start; gap: 8px;
  margin-top: 14px; padding: 11px 12px; border-radius: 10px;
  background: var(--c-bg); color: var(--c-text-2); font-size: 12.5px; line-height: 1.6;
}
.crisis-hint {
  display: flex; align-items: flex-start; gap: 8px;
  margin-top: 14px; padding: 10px 12px; border-radius: 10px;
  border: 1px dashed var(--c-danger); background: var(--c-bg);
  color: var(--c-text-2); font-size: 12.5px; line-height: 1.6;
}
.crisis-hint :deep(.el-icon) { color: var(--c-danger); flex-shrink: 0; margin-top: 2px; }

.save-bar {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  position: sticky; bottom: 12px; z-index: 20;
  box-shadow: 0 8px 24px rgba(47, 42, 37, 0.12);
}
.save-actions { display: flex; align-items: center; gap: 2px; flex-wrap: wrap; }
.save-info { display: flex; align-items: center; gap: 7px; font-size: 12.5px; }
.save-info .unsaved { color: var(--c-warn); }
.save-info .saved { color: var(--c-text-2); }
.save-info :deep(.el-icon) { color: currentColor; }
.visually-hidden {
  position: absolute !important; width: 1px; height: 1px;
  padding: 0; margin: -1px; overflow: hidden;
  clip: rect(0 0 0 0); white-space: nowrap; border: 0;
}

.prompt-preview {
  margin: 0; padding: 14px; max-height: 380px; overflow: auto;
  border: 1px solid var(--c-border); border-radius: 10px;
  background: var(--c-bg); color: var(--c-text);
  font-family: 'SF Mono', 'Fira Code', 'Consolas', monospace;
  font-size: 12px; line-height: 1.75; white-space: pre-wrap; word-break: break-word;
}
.prompt-preview.empty { color: var(--c-text-2); font-family: inherit; font-style: italic; }

.test-card :deep(.el-textarea__inner) { font-size: 13px; }
.example-row { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.example-chip {
  padding: 4px 9px; border-radius: 999px; cursor: pointer;
  border: 1px dashed var(--c-border); background: transparent;
  color: var(--c-text-2); font-size: 11.5px; font-family: inherit;
  transition: border-color 0.15s ease, color 0.15s ease;
}
.example-chip:hover { border-color: var(--c-primary); color: var(--c-primary-dark); }
.test-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 12px; }
.test-actions .hint { margin: 0; }

.test-result {
  margin-top: 14px; padding: 12px 13px; border-radius: 10px;
  border: 1px solid var(--c-border); border-left-width: 3px;
  background: var(--c-bg);
}
.test-result.tone-blocked { border-left-color: var(--c-danger); }
.test-result.tone-safe { border-left-color: var(--c-success); }
.test-result.tone-muted { border-left-color: var(--c-text-2); }
.test-result.tone-crisis { border-left-color: var(--c-danger); background: var(--c-surface); }
.result-head { display: flex; align-items: center; gap: 6px; font-size: 13px; }
.tone-blocked .result-head { color: var(--c-danger); }
.tone-safe .result-head { color: var(--c-success); }
.tone-muted .result-head { color: var(--c-text-2); }
.tone-crisis .result-head { color: var(--c-danger); }
.test-quote {
  margin: 9px 0 0; padding: 8px 10px; border-radius: 8px;
  background: var(--c-bg); border: 1px solid var(--c-border);
  color: var(--c-text); font-size: 0; line-height: 1.7;
  word-break: break-word; white-space: pre-wrap;
}
.test-quote span { font-size: 12.5px; }
.test-quote .hit {
  padding: 0 1px; border-radius: 3px;
  background: var(--c-primary-weak); color: var(--c-danger); font-weight: 600;
}
.result-desc { margin: 6px 0 0; font-size: 12.5px; line-height: 1.6; color: var(--c-text-2); }
.match-list { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.match-chip {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 3px 8px; border-radius: 999px;
  background: var(--c-surface); border: 1px solid var(--c-border);
  font-size: 11.5px; color: var(--c-text);
}
.match-chip em { font-style: normal; color: var(--c-danger); font-weight: 600; }

.error-card { display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 48px; color: var(--c-text-2); }

@media (max-width: 1180px) {
  .config-grid, .skeleton-grid { grid-template-columns: 1fr; }
  .aside-col { position: static; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 768px) {
  .hero { grid-template-columns: 1fr; }
  .hero-control { justify-self: flex-start; }
  .hero-stats { gap: 10px 24px; }
  .preset-grid { grid-template-columns: 1fr; }
  .action-grid { grid-template-columns: 1fr; }
  .action-card { flex-direction: row; align-items: center; flex-wrap: wrap; }
  .action-desc { width: 100%; }
  .aside-col { grid-template-columns: 1fr; }
  .save-bar { flex-direction: column; align-items: stretch; bottom: 8px; }
  .save-bar .el-button { width: 100%; }
  .save-actions { display: grid; grid-template-columns: 1fr 1fr; gap: 6px; width: 100%; }
  .save-actions .el-button { margin: 0; }
  .custom-add { flex-direction: column; }
  .custom-add .el-button { width: 100%; }
}
</style>
