<template>
  <div class="approve-page">
    <div class="approve-card">
      <div class="icon">🔐</div>
      <h1>登录确认</h1>
      <p class="sub">请输入管理授权码，确认后电脑端会自动登录</p>

      <template v-if="state !== 'done'">
        <el-input v-model="password" type="password" show-password size="large" placeholder="授权码"
                  @keyup.enter="submit" />
        <el-button type="primary" size="large" class="submit" :loading="loading" @click="submit">
          确认登录
        </el-button>
      </template>
      <div v-else class="done">
        <div class="check">✓</div>
        <p>已确认，请回到电脑继续</p>
      </div>

      <p v-if="error" class="error">{{ error }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '@/api/client'

const route = useRoute()
const token = String(route.params.token ?? '')
const password = ref('')
const loading = ref(false)
const error = ref('')
const state = ref<'idle' | 'done'>('idle')

async function submit() {
  if (!password.value) { error.value = '请输入授权码'; return }
  loading.value = true
  error.value = ''
  try {
    await api(`/auth/qr/${token}/approve`, { method: 'POST', body: { password: password.value } })
    state.value = 'done'
  } catch (e: any) {
    error.value = e?.message ?? '授权码错误'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.approve-page {
  min-height: 100vh; display: grid; place-items: center; padding: 20px;
  background: radial-gradient(900px 500px at 30% 0%, #1d2b64 0%, #0b1022 60%, #060812 100%);
}
.approve-card {
  width: 340px; max-width: 100%; padding: 30px 24px; border-radius: 18px;
  background: rgba(255, 255, 255, 0.07); border: 1px solid rgba(255, 255, 255, 0.14);
  backdrop-filter: blur(16px); color: #eef2ff; text-align: center;
  display: flex; flex-direction: column; gap: 12px;
}
.icon { font-size: 34px; }
h1 { margin: 0; font-size: 20px; }
.sub { margin: 0 0 8px; font-size: 12px; color: rgba(238, 242, 255, 0.6); line-height: 1.6; }
.submit { width: 100%; }
:deep(.el-input__wrapper) { background: rgba(255, 255, 255, 0.08); box-shadow: none; }
:deep(.el-input__inner) { color: #fff; }
.done { padding: 10px 0; }
.check {
  width: 54px; height: 54px; margin: 0 auto 10px; border-radius: 50%;
  display: grid; place-items: center; font-size: 26px; color: #fff;
  background: linear-gradient(135deg, #34d399, #10b981);
}
.done p { margin: 0; font-size: 13px; color: rgba(238, 242, 255, 0.75); }
.error { margin: 0; font-size: 12px; color: #ff8a8a; }
</style>
