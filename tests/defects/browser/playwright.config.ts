import { defineConfig } from '../../../yshop-drink-vue3/node_modules/@playwright/test'
import path from 'node:path'

const output = process.env.YSHOP_DEFECT_OUTPUT
if (!output || !path.isAbsolute(output)) throw new Error('PRIVATE_ABSOLUTE_OUTPUT_REQUIRED')
const repo = path.resolve(__dirname, '../../..')
export default defineConfig({
  testDir: '.', testMatch: 'dialog.spec.ts', workers: 1, retries: 0, repeatEach: 2,
  timeout: 60000, expect: { timeout: 15000 },
  outputDir: path.join(output, 'artifacts'),
  reporter: [['json', { outputFile: path.join(output, 'playwright-private.json') }], ['line']],
  use: { baseURL: 'http://127.0.0.1:4197', trace: 'retain-on-failure', screenshot: 'only-on-failure', serviceWorkers: 'block' },
  webServer: {
    cwd: path.join(repo, 'yshop-drink-vue3'),
    command: 'pnpm exec vite --config vite.e2e.config.ts --port 4197',
    url: 'http://127.0.0.1:4197', reuseExistingServer: false, timeout: 120000
  }
})
