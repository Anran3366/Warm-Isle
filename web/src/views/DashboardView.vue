<template>
  <div class="dashboard">
    <div v-if="loading" class="skeleton-grid">
      <el-skeleton v-for="i in 5" :key="i" class="card" :rows="2" animated />
    </div>

    <template v-else-if="!error">
      <div class="stat-grid">
        <div class="card stat-card">
          <div class="stat-label">机器人状态</div>
          <div class="stat-value">
            <el-tag :type="status?.bot.online ? 'success' : 'info'" effect="plain" round>
              {{ status?.bot.online ? '在线' : '离线' }}
            </el-tag>
          </div>
          <div class="stat-sub">运行 {{ formatUptime(status?.bot.uptimeSec ?? 0) }}</div>
        </div>

        <div class="card stat-card">
          <div class="stat-label">当前人设</div>
          <div class="stat-value accent">{{ status?.persona?.name ?? '—' }}</div>
          <div class="stat-sub">{{ status?.persona ? '已激活' : '未设置' }}</div>
        </div>

        <div class="card stat-card">
          <div class="stat-label">今日消息</div>
          <div class="stat-value num">{{ messagesCount.value }}</div>
          <div class="stat-sub">条对话</div>
        </div>

        <div class="card stat-card">
          <div class="stat-label">今日花费</div>
          <div class="stat-value num accent">¥{{ costCount.value }}</div>
          <div class="stat-sub num">{{ tokensCount.value }} tokens</div>
        </div>

        <div class="card stat-card">
          <div class="stat-label">记忆库</div>
          <div class="stat-value num">{{ factsCount.value }}</div>
          <div class="stat-sub">
            {{ status?.memory.pending ? `${status.memory.pending} 条待确认` : '无待确认' }}
          </div>
        </div>
      </div>

      <div class="card chart-card">
        <div class="chart-header">
          <h3 class="chart-title">趋势</h3>
          <div class="chart-controls">
            <el-dropdown trigger="click" @command="handleExport">
              <el-button size="small">
                <el-icon><Download /></el-icon>导出<el-icon class="el-icon--right"><ArrowDown /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="csv">用量 CSV</el-dropdown-item>
                  <el-dropdown-item command="json">用量 JSON</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-radio-group v-model="chartRange" size="small">
              <el-radio-button value="7">7 天</el-radio-button>
              <el-radio-button value="30">30 天</el-radio-button>
            </el-radio-group>
          </div>
        </div>
        <div v-if="chartEmpty" class="chart-empty">
          <el-icon :size="32" color="var(--c-text-2)"><DataLine /></el-icon>
          <span>{{ usageError || '暂无趋势数据' }}</span>
        </div>
        <div v-show="!chartEmpty" ref="chartRef" class="chart-body" />
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
defineOptions({ name: 'DashboardView' })
import { ref, computed, watch, onMounted, onBeforeUnmount, onActivated, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { WarningFilled, DataLine, Download, ArrowDown } from '@element-plus/icons-vue'
import { api } from '@/api/client'
import { normalizeStatus } from '@/api/adapters'
import type { StatusResp, UsagePoint } from '@/api/types'
import { useCountUp } from '@/utils/format'
import { fillUsageSeries } from '@/utils/usage'
import { exportCsv, exportJson } from '@/utils/export'

echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const status = ref<StatusResp | null>(null)
const usage = ref<UsagePoint[]>([])
const loading = ref(true)
const error = ref('')
const usageError = ref('')
const chartRef = ref<HTMLElement | null>(null)
const chartRange = ref<'7' | '30'>('7')
let chart: echarts.ECharts | null = null

const chartEmpty = computed(() => usage.value.every(u => u.cost === 0 && u.tokens === 0))

const messagesCount = useCountUp(0, 600, 0)
const costCount = useCountUp(0, 600, 2)
const tokensCount = useCountUp(0, 600, 0)
const factsCount = useCountUp(0, 600, 0)

function formatUptime(sec: number): string {
  const d = Math.floor(sec / 86400)
  const h = Math.floor((sec % 86400) / 3600)
  if (d > 0) return `${d} 天 ${h} 小时`
  const m = Math.floor((sec % 3600) / 60)
  return `${h} 小时 ${m} 分`
}

function getDateRange(): { from: string; to: string } {
  const days = Number(chartRange.value)
  const to = new Date()
  const from = new Date()
  from.setDate(from.getDate() - days + 1)
  return {
    from: from.toISOString().slice(0, 10),
    to: to.toISOString().slice(0, 10)
  }
}

async function loadData() {
  loading.value = true
  error.value = ''
  usageError.value = ''
  try {
    const { from, to } = getDateRange()
    const s = normalizeStatus(await api<unknown>('/status'))
    status.value = s
    messagesCount.target = s.today.messages
    costCount.target = s.today.cost
    tokensCount.target = s.today.tokens
    factsCount.target = s.memory.facts
    messagesCount.start()
    costCount.start()
    tokensCount.start()
    factsCount.start()

    // 用量接口可能还没交付：失败时保留状态卡片，只让趋势图显示提示
    try {
      usage.value = fillUsageSeries(from, to, await api<UsagePoint[]>(`/usage?from=${from}&to=${to}`))
    } catch (usageErr: any) {
      usage.value = []
      usageError.value = usageErr?.code === 'HTTP_404'
        ? '用量接口暂未就绪，趋势图稍后可用'
        : (usageErr?.message ?? '趋势数据加载失败')
    }
  } catch (e: any) {
    error.value = e?.message ?? '未知错误'
  } finally {
    loading.value = false
  }
}

function getChartColors() {
  const isDark = document.documentElement.getAttribute('data-theme') === 'dark'
  return {
    tooltipBg: isDark ? 'rgba(37,34,32,0.96)' : 'rgba(255,255,255,0.96)',
    tooltipBorder: isDark ? '#3a3530' : '#e8e1d9',
    tooltipText: isDark ? '#e8e1d9' : '#2f2a25',
    axisLine: isDark ? '#3a3530' : '#e8e1d9',
    axisLabel: isDark ? '#9a9189' : '#7a7169',
    splitLine: isDark ? '#2a2520' : '#f0ebe5'
  }
}

function renderChart() {
  if (!chartRef.value || !usage.value.length) return
  // 容器可能因 v-if 切换被重建，旧实例指向已卸载的 DOM，需重建
  if (chart && chart.getDom() !== chartRef.value) {
    chart.dispose()
    chart = null
  }
  if (!chart) {
    chart = echarts.init(chartRef.value)
  }
  const colors = getChartColors()
  const dates = usage.value.map(u => u.date.slice(5))
  chart.setOption({
    animationDuration: 800,
    animationEasing: 'cubicOut',
    tooltip: {
      trigger: 'axis',
      backgroundColor: colors.tooltipBg,
      borderColor: colors.tooltipBorder,
      textStyle: { color: colors.tooltipText, fontSize: 12 },
      formatter(params: any) {
        if (!Array.isArray(params)) return ''
        let html = params[0].name
        for (const p of params) {
          const val = p.seriesName === '花费' ? `¥${p.value.toFixed(2)}` : `${(p.value / 1000).toFixed(1)}k`
          html += `<br/>${p.marker}${p.seriesName}：<b>${val}</b>`
        }
        return html
      }
    },
    legend: {
      data: ['花费', 'Token'],
      bottom: 0,
      textStyle: { color: colors.axisLabel, fontSize: 11 },
      itemWidth: 16, itemHeight: 8
    },
    grid: { left: 52, right: 52, top: 16, bottom: 36 },
    xAxis: {
      type: 'category',
      data: dates,
      axisLine: { lineStyle: { color: colors.axisLine } },
      axisLabel: { color: colors.axisLabel, fontSize: 11 },
      axisTick: { show: false }
    },
    yAxis: [
      {
        type: 'value',
        name: '花费 (¥)',
        nameTextStyle: { color: colors.axisLabel, fontSize: 10, padding: [0, 40, 0, 0] },
        splitLine: { lineStyle: { color: colors.splitLine } },
        axisLabel: {
          color: colors.axisLabel,
          fontSize: 11,
          formatter: (v: number) => `¥${v}`
        }
      },
      {
        type: 'value',
        name: 'Token',
        nameTextStyle: { color: colors.axisLabel, fontSize: 10, padding: [0, 0, 0, 40] },
        splitLine: { show: false },
        axisLabel: {
          color: colors.axisLabel,
          fontSize: 11,
          formatter: (v: number) => v >= 1000 ? `${v / 1000}k` : String(v)
        }
      }
    ],
    series: [
      {
        name: '花费',
        type: 'line',
        yAxisIndex: 0,
        data: usage.value.map(u => u.cost),
        smooth: true,
        symbol: 'circle',
        symbolSize: 5,
        lineStyle: { color: '#e8927c', width: 2.5 },
        itemStyle: { color: '#e8927c' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(232,146,124,0.15)' },
            { offset: 1, color: 'rgba(232,146,124,0.01)' }
          ])
        }
      },
      {
        name: 'Token',
        type: 'line',
        yAxisIndex: 1,
        data: usage.value.map(u => u.tokens),
        smooth: true,
        symbol: 'circle',
        symbolSize: 5,
        lineStyle: { color: '#6b9ac4', width: 2.5 },
        itemStyle: { color: '#6b9ac4' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(107,154,196,0.12)' },
            { offset: 1, color: 'rgba(107,154,196,0.01)' }
          ])
        }
      }
    ]
  })
  // 容器可能刚从 v-show 隐藏变为可见，补一次 resize
  requestAnimationFrame(() => chart?.resize())
}

watch(chartRange, () => { void reloadUsage() })

// 只刷新趋势数据：不切换 loading，容器保持挂载，图表实例可复用
async function reloadUsage() {
  try {
    usageError.value = ''
    const { from, to } = getDateRange()
    usage.value = fillUsageSeries(from, to, await api<UsagePoint[]>(`/usage?from=${from}&to=${to}`))
    await nextTick()
    renderChart()
  } catch (e: any) {
    usage.value = []
    usageError.value = e?.code === 'HTTP_404'
      ? '用量接口暂未就绪，趋势图稍后可用'
      : (e?.message ?? '趋势数据加载失败')
    ElMessage.error(usageError.value)
  }
}

// 首屏 loading 期间模板渲染的是骨架屏，图表容器还不存在；
// 等 loading 结束、容器挂载后再初始化图表。
watch(loading, async (visible) => {
  if (!visible) {
    await nextTick()
    renderChart()
  }
})

function handleExport(cmd: string) {
  const date = new Date().toISOString().slice(0, 10)
  if (cmd === 'csv') {
    exportCsv(`usage-${date}.csv`,
      ['日期', '花费(¥)', 'Token'],
      usage.value.map(u => [u.date, u.cost, u.tokens])
    )
    ElMessage.success('已导出用量 CSV')
  } else if (cmd === 'json') {
    exportJson(`usage-${date}.json`, usage.value)
    ElMessage.success('已导出用量 JSON')
  }
}

function handleResize() {
  chart?.resize()
}

let themeObserver: MutationObserver | null = null

onMounted(() => {
  loadData()
  window.addEventListener('resize', handleResize)
  themeObserver = new MutationObserver(() => renderChart())
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
})

onActivated(() => {
  // keep-alive 缓存激活后重新渲染图表
  if (usage.value.length > 0) {
    nextTick(() => renderChart())
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  themeObserver?.disconnect()
  chart?.dispose()
  chart = null
})
</script>

<style scoped>
.dashboard { display: flex; flex-direction: column; gap: 20px; }
.skeleton-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 16px; }
.stat-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 16px; }
.stat-card { display: flex; flex-direction: column; gap: 6px; }
.stat-label { font-size: 13px; color: var(--c-text-2); }
.stat-value { font-size: 26px; font-weight: 600; }
.stat-value.accent { color: var(--c-primary); }
.stat-sub { font-size: 12px; color: var(--c-text-2); }
.chart-card { padding: 20px; }
.chart-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.chart-title { margin: 0; font-size: 15px; font-weight: 600; }
.chart-controls { display: flex; gap: 8px; }
.chart-body { height: 280px; }
.chart-empty {
  height: 280px; display: flex; flex-direction: column;
  align-items: center; justify-content: center; gap: 8px;
  color: var(--c-text-2); font-size: 13px;
}
.error-card { display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 48px; color: var(--c-text-2); }

@media (max-width: 768px) {
  .stat-grid { grid-template-columns: 1fr 1fr; gap: 12px; }
  .stat-value { font-size: 22px; }
  .chart-body { height: 220px; }
}
@media (max-width: 480px) {
  .stat-grid { grid-template-columns: 1fr; }
}
</style>
