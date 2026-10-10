import { defineConfig, devices } from '@playwright/test'
const port = Number(process.env.YSHOP_BROWSER_PORT || 4173)
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('INVALID_BROWSER_PORT')
const output = process.env.YSHOP_BROWSER_OUTPUT || '.quality/browser'
export default defineConfig({
  testDir: './e2e', forbidOnly: true, fullyParallel: false, workers: 1, retries: 0,
  timeout: 60000, expect: { timeout: 15000 },
  outputDir: `${output}/artifacts`,
  reporter: [['./e2e/reporter.ts'], ['line']],
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  // Screenshots/trace can contain credentials. Only safe reporter summaries are uploaded by CI.
  use: { baseURL: `http://127.0.0.1:${port}`, trace: 'retain-on-failure',
    screenshot: 'only-on-failure', video: 'off', serviceWorkers: 'block' },
  webServer: { command: `pnpm exec vite --config vite.e2e.config.ts --port ${port}`,
    url: `http://127.0.0.1:${port}`, reuseExistingServer: false, timeout: 120000 }
})
