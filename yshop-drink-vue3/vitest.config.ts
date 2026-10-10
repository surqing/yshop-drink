import { defineConfig } from 'vitest/config'
import Vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import { fileURLToPath, URL } from 'node:url'
export default defineConfig({
  plugins: [Vue(), AutoImport({ imports: ['vue', { '@/hooks/web/useMessage': ['useMessage'] }], dts: false })],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: { hmr: false },
  test: {
    environment: 'jsdom', include: ['tests/**/*.test.ts'], testTimeout: 5000,
    coverage: { provider: 'v8', all: true, include: ['src/**/*.{ts,vue}'],
      exclude: ['src/types/**'], reporter: ['json', 'json-summary'],
      reportsDirectory: process.env.YSHOP_VUE_COVERAGE || './.quality/coverage' }
  }
})
