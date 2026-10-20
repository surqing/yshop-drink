/** Browser acceptance entry: real printer page, synthetic API, no production login. */
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '@/plugins/unocss'
import '@/styles/index.scss'
import PrinterPage from '@/views/mall/store/printer/index.vue'
import { setupStore } from '@/store'
import { setupI18n } from '@/plugins/vueI18n'
import { hasPermi } from '@/directives/permission/hasPermi'
import { CACHE_KEY, useCache } from '@/hooks/web/useCache'

const { wsCache } = useCache()
wsCache.set(CACHE_KEY.USER, { user: { id: 999999, shopId: 0 }, permissions: ['*:*:*'] })
wsCache.set(CACHE_KEY.TenantId, 999999)
const app = createApp(PrinterPage)
setupStore(app)
await setupI18n(app)
app.use(ElementPlus)
hasPermi(app)
app.mount('#app')
