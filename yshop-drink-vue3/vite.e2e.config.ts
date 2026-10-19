import { defineConfig } from 'vite'
import { resolve } from 'node:path'
import { createVitePlugins } from './build/vite'

// Same application, compiler and component resolution. Evidence runs must not rewrite source.
export default defineConfig({
  base: '/', plugins: createVitePlugins(false),
  resolve: { alias: { '@': resolve(__dirname, 'src'), 'vue-i18n': 'vue-i18n/dist/vue-i18n.cjs.js' } },
  css: { preprocessorOptions: { scss: { additionalData: '@import "./src/styles/variables.scss";' } } },
  server: { host: '127.0.0.1', strictPort: true },
  define: {
    'import.meta.env.VITE_BASE_URL': JSON.stringify(''),
    'import.meta.env.VITE_API_URL': JSON.stringify('/admin-api'),
    'import.meta.env.VITE_APP_DOCALERT_ENABLE': JSON.stringify('false')
  }
})
