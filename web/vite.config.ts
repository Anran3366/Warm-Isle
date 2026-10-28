import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { VitePWA } from 'vite-plugin-pwa'
import pkg from './package.json'

const buildTime = new Date().toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' })

// base 用 '/'：前端是 history 路由（/m/:token、/account），相对路径会在深层路由下 404
export default defineConfig({
  base: '/',
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version),
    __BUILD_TIME__: JSON.stringify(buildTime)
  },
  plugins: [
    vue(),
    AutoImport({
      resolvers: [ElementPlusResolver()],
      imports: ['vue', 'vue-router'],
      dts: 'src/auto-imports.d.ts'
    }),
    Components({
      resolvers: [ElementPlusResolver()],
      dts: 'src/components.d.ts'
    }),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['favicon.svg', 'pwa-192.png', 'pwa-512.png'],
      manifest: {
        name: '暖屿 · 小满 管理后台',
        short_name: '暖屿小满',
        description: 'QQ 陪聊机器人管理后台',
        lang: 'zh-CN',
        theme_color: '#e8927c',
        background_color: '#f7f4f0',
        display: 'standalone',
        start_url: './',
        scope: './',
        icons: [
          { src: './pwa-192.png', sizes: '192x192', type: 'image/png' },
          { src: './pwa-512.png', sizes: '512x512', type: 'image/png' }
        ]
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,svg,png,ico,woff2}'],
        navigateFallback: undefined,
        runtimeCaching: [
          {
            urlPattern: /^https?:\/\/.*\/(api|files)\/.*/i,
            handler: 'NetworkOnly'
          }
        ]
      }
    })
  ],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) }
  },
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
    sourcemap: false,
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        manualChunks: {
          'echarts': ['echarts/core', 'echarts/charts', 'echarts/components', 'echarts/renderers']
        }
      }
    }
  },
  server: {
    port: 5173,
    proxy: {
      // 本地联调：前端 5173 → 后端 8080
      '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true },
      '/files': { target: 'http://127.0.0.1:8080', changeOrigin: true }
    }
  }
})
