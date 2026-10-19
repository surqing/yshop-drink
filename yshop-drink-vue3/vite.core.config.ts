import { defineConfig, mergeConfig } from 'vite'
import common from './vite.e2e.config'
export default mergeConfig(common, defineConfig({ define: {
  'import.meta.env.VITE_APP_CAPTCHA_ENABLE': JSON.stringify('false'),
  'import.meta.env.VITE_APP_TENANT_ENABLE': JSON.stringify('false')
} }))
