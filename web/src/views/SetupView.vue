<template>
  <div class="setup-page">
    <div class="setup-card">
      <div class="brand">
        <div class="brand-icon">伴</div>
        <div>
          <h1>暖屿 · 小满</h1>
          <p>首次部署初始化</p>
        </div>
      </div>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="完成这两步就能进后台"
        description="设置管理员密码，填入 DeepSeek Key。其他配置可在后台「设置」里继续调整。"
      />

      <section>
        <h2>1. 管理员密码</h2>
        <el-input v-model="password" type="password" show-password size="large" placeholder="至少 8 位" />
        <el-input v-model="confirmPassword" type="password" show-password size="large" placeholder="再输一次" />
      </section>

      <section>
        <h2>2. DeepSeek</h2>
        <el-input v-model="apiKey" type="password" show-password size="large" placeholder="sk-...（可以稍后再填）" />
        <el-input v-model="baseUrl" size="large" placeholder="API 地址">
          <template #prepend>Base URL</template>
        </el-input>
        <el-input v-model="model" size="large" placeholder="deepseek-chat">
          <template #prepend>模型</template>
        </el-input>
        <p class="hint">Key 只保存在你自己的 shared.db，不会上传到暖屿商店或第三方。</p>
      </section>

      <section v-if="tokenRequired">
        <h2>3. 部署令牌</h2>
        <el-input v-model="setupToken" size="large" placeholder=".env 里的 SETUP_TOKEN" />
        <p class="hint">部署方设置了 SETUP_TOKEN，防止公网被人抢先认领。</p>
      </section>

      <p v-if="error" class="error">{{ error }}</p>
      <el-button type="primary" size="large" class="submit" :loading="saving" @click="submit">
        完成初始化，进入后台
      </el-button>
      <p class="footnote">初始化完成后，这个页面会自动关闭。</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { resetSetupCheck } from '@/router'

const router = useRouter()
const password = ref('')
const confirmPassword = ref('')
const apiKey = ref('')
const baseUrl = ref('https://api.deepseek.com')
const model = ref('deepseek-chat')
const setupToken = ref('')
const tokenRequired = ref(false)
const saving = ref(false)
const error = ref('')

async function loadStatus() {
  try {
    const res = await fetch('/api/setup/status', { credentials: 'include', cache: 'no-store' })
    const data = await res.json()
    if (!data.required) {
      router.replace('/login')
      return
    }
    tokenRequired.value = !!data.tokenRequired
    if (data.baseUrl) baseUrl.value = data.baseUrl
    if (data.model) model.value = data.model
  } catch {
    error.value = '无法连接后端，请确认服务已启动'
  }
}

async function submit() {
  error.value = ''
  if (password.value.length < 8) {
    error.value = '密码至少 8 位'
    return
  }
  if (password.value !== confirmPassword.value) {
    error.value = '两次输入的密码不一致'
    return
  }
  saving.value = true
  try {
    const res = await fetch('/api/setup', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        password: password.value,
        confirmPassword: confirmPassword.value,
        deepseekApiKey: apiKey.value,
        deepseekBaseUrl: baseUrl.value,
        deepseekModel: model.value,
        setupToken: setupToken.value,
      }),
    })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) throw new Error(data.message || `初始化失败（${res.status}）`)
    sessionStorage.setItem('dsh-admin', '1')
    resetSetupCheck()
    router.replace('/dashboard')
  } catch (e: any) {
    error.value = e.message || '初始化失败'
  } finally {
    saving.value = false
  }
}

onMounted(loadStatus)
</script>

<style scoped>
.setup-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 32px 20px;
  background: radial-gradient(circle at 20% 10%, #f7d9cc 0, transparent 42%),
              radial-gradient(circle at 90% 80%, #f3c3d8 0, transparent 40%),
              #f7f4f0;
}
.setup-card {
  width: min(560px, 100%);
  padding: 30px;
  border: 1px solid #ecdcd3;
  border-radius: 22px;
  background: rgba(255, 253, 251, .94);
  box-shadow: 0 20px 60px rgba(90, 55, 45, .12);
}
.brand { display: flex; align-items: center; gap: 12px; margin-bottom: 18px; }
.brand-icon {
  width: 42px; height: 42px; border-radius: 12px; display: grid; place-items: center;
  background: #e8927c; color: #fff; font-weight: 700;
}
.brand h1 { margin: 0; font-size: 20px; }
.brand p { margin: 2px 0 0; color: #8b7268; font-size: 13px; }
section { margin-top: 20px; }
section h2 { margin: 0 0 10px; font-size: 15px; }
.el-input { margin-bottom: 10px; }
.hint { margin: 6px 0 0; color: #8b7268; font-size: 12px; line-height: 1.6; }
.submit { width: 100%; margin-top: 20px; }
.error { margin: 12px 0 0; color: #c45656; font-size: 13px; }
.footnote { margin: 14px 0 0; text-align: center; color: #a08a80; font-size: 12px; }
</style>
