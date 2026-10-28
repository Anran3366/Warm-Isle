<template>
  <div class="review-page">
    <div class="card toolbar">
      <el-date-picker
        v-model="day"
        type="date"
        value-format="YYYY-MM-DD"
        placeholder="选择日期"
        style="width: 170px"
      />
      <el-button type="primary" :loading="running" @click="runReview">运行复盘</el-button>
      <el-button :loading="loading" @click="load">刷新</el-button>
      <span class="hint">每天 00:10 自动复盘前一天</span>
    </div>

    <div v-if="!loading && !items.length" class="card empty">
      这一天还没有复盘记录，点「运行复盘」试一下
    </div>

    <div v-for="r in items" :key="r.id" class="card review-card">
      <div class="review-head">
        <div class="head-left">
          <span class="qq">QQ {{ r.qqId }}</span>
          <el-tag :type="scoreType(r.score)" size="small">聊天 {{ r.score }} 分</el-tag>
          <el-tag :type="scoreType(r.humanScore)" size="small" effect="plain">
            像真人 {{ r.humanScore }} 分
          </el-tag>
          <span class="muted">{{ r.msgCount }} 条消息</span>
        </div>
        <el-button size="small" type="primary" plain @click="apply(r)">追加建议到人设</el-button>
      </div>
      <p class="summary">{{ r.summary }}</p>

      <div v-if="r.issues?.length" class="block">
        <div class="block-title">问题</div>
        <ul>
          <li v-for="(x, i) in r.issues" :key="i">{{ x }}</li>
        </ul>
      </div>
      <div v-if="r.suggestions?.length" class="block">
        <div class="block-title">建议</div>
        <ul>
          <li v-for="(x, i) in r.suggestions" :key="i">{{ x }}</li>
        </ul>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api/client'

interface Review {
  id: number
  day: string
  qqId: number
  score: number
  humanScore: number
  summary: string
  issues: string[]
  suggestions: string[]
  msgCount: number
}

const yesterday = new Date(Date.now() - 86400000).toISOString().slice(0, 10)
const day = ref(yesterday)
const items = ref<Review[]>([])
const loading = ref(false)
const running = ref(false)

function scoreType(score: number) {
  if (score >= 4) return 'success'
  if (score >= 3) return 'warning'
  return 'danger'
}

async function load() {
  loading.value = true
  try {
    const r = await api<{ items: Review[] }>('/reviews?day=' + day.value)
    items.value = r.items ?? []
  } catch (e: any) {
    ElMessage.error(e?.message ?? '加载失败')
  } finally {
    loading.value = false
  }
}

async function runReview() {
  running.value = true
  try {
    const r = await api<{ ok: boolean; count?: number; message?: string }>(
      '/reviews/run?day=' + day.value,
      { method: 'POST' }
    )
    if (!r.ok) {
      ElMessage.error(r.message ?? '复盘失败')
      return
    }
    ElMessage.success(`复盘完成，生成 ${r.count ?? 0} 条`)
    await load()
  } catch (e: any) {
    ElMessage.error(e?.message ?? '复盘失败')
  } finally {
    running.value = false
  }
}

async function apply(r: Review) {
  if (!r.suggestions?.length) {
    ElMessage.warning('这条复盘没有建议')
    return
  }
  await ElMessageBox.confirm(
    '会把这些建议以「【复盘补充】」的形式追加到当前账号的人设末尾，原有内容不变。确定吗？',
    '追加到人设',
    { type: 'warning', confirmButtonText: '确定追加' }
  )
  try {
    const res = await api<{ ok: boolean; appended?: number; message?: string }>(
      `/reviews/${r.id}/apply`,
      { method: 'POST' }
    )
    if (!res.ok) {
      ElMessage.error(res.message ?? '追加失败')
      return
    }
    ElMessage.success(`已追加 ${res.appended ?? 0} 条建议到人设`)
  } catch (e: any) {
    ElMessage.error(e?.message ?? '追加失败')
  }
}

onMounted(load)
</script>

<style scoped>
.review-page { display: flex; flex-direction: column; gap: 14px; }
.toolbar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 12px 16px; }
.hint { font-size: 12px; color: var(--c-text-2); }
.empty { padding: 40px; text-align: center; color: var(--c-text-2); }
.review-card { padding: 16px; }
.review-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.head-left { display: flex; align-items: center; gap: 8px; }
.qq { font-weight: 600; }
.muted { font-size: 12px; color: var(--c-text-2); }
.summary { margin: 10px 0 6px; font-size: 14px; line-height: 1.7; }
.block { margin-top: 10px; }
.block-title { font-size: 12.5px; color: var(--c-text-2); margin-bottom: 4px; }
.block ul { margin: 0; padding-left: 18px; }
.block li { font-size: 13.5px; line-height: 1.7; }
</style>
