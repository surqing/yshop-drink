import { defineConfig, devices } from '@playwright/test'
import { readFileSync } from 'node:fs'
const context = JSON.parse(readFileSync(process.env.YSHOP_E2E_CONTEXT!, 'utf8'))
const port = Number(process.env.YSHOP_BROWSER_PORT)
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw Error('OWNED_BROWSER_PORT_REQUIRED')
export default defineConfig({
  testDir: './e2e/core', testMatch: 'core.spec.ts', forbidOnly: true,
  fullyParallel: false, workers: 1, retries: 0, timeout: 180000,
  expect: { timeout: 15000 }, outputDir: `${context.output}/artifacts`,
  reporter: [['./e2e/core/reporter.ts'], ['line']],
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chromium', timezoneId: 'Asia/Shanghai' } }],
  use: { actionTimeout: 15000, navigationTimeout: 30000, baseURL: `http://127.0.0.1:${port}`, trace: 'retain-on-failure',
    screenshot: 'only-on-failure', video: 'off', serviceWorkers: 'block' },
  webServer: { command: `pnpm exec vite --config vite.core.config.ts --port ${port}`,
    url: `http://127.0.0.1:${port}`, reuseExistingServer: false, timeout: 120000 }
})
