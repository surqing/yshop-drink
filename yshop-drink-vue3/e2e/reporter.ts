import type { Reporter, FullConfig, Suite, TestCase, TestResult, FullResult } from '@playwright/test/reporter'
import { mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
export default class SafeReporter implements Reporter {
  private cases: Array<Record<string, unknown>> = []
  private planned: string[] = []
  onBegin(_config: FullConfig, suite: Suite) {
    this.planned = suite.allTests().map(t => t.title)
  }
  onTestEnd(test: TestCase, result: TestResult) {
    this.cases.push({ name: test.title, status: result.status, expectedStatus: test.expectedStatus,
      retry: result.retry, durationMs: result.duration })
  }
  onEnd(result: FullResult) {
    const root = resolve(process.env.YSHOP_BROWSER_OUTPUT || '.quality/browser')
    mkdirSync(root, { recursive: true, mode: 0o700 })
    writeFileSync(`${root}/receipt.json`, JSON.stringify({
      runId: process.env.YSHOP_QUALITY_RUN_ID, sourceSha: process.env.YSHOP_QUALITY_SOURCE_SHA,
      sourceDigest: process.env.YSHOP_QUALITY_SOURCE_DIGEST, result: result.status,
      scope: process.env.YSHOP_BROWSER_SCOPE || 'actual Vue pages; synthetic API responses',
      planned: this.planned, cases: this.cases, startedAt: result.startTime.getTime() / 1000,
      endedAt: Date.now() / 1000
    }, null, 2), { mode: 0o600 })
  }
}
