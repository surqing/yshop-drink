import type { Reporter, FullConfig, Suite, TestCase, TestResult, FullResult } from '@playwright/test/reporter'
import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { join } from 'node:path'
export default class E2eReporter implements Reporter {
  private context = JSON.parse(readFileSync(process.env.YSHOP_E2E_CONTEXT!, 'utf8'))
  private planned: Array<{ id: string; variant: string }> = []
  private cases: Array<Record<string, unknown>> = []
  onBegin(_config: FullConfig, suite: Suite) {
    this.planned = suite.allTests().map(test => ({ id: test.title,
      variant: this.context.planned.find((s: any) => s.id === test.title)?.variant || 'UNREGISTERED' }))
  }
  onTestEnd(test: TestCase, result: TestResult) {
    const selected = this.context.planned.find((s: any) => s.id === test.title)
    if (!selected) { this.cases.push({ id: 'UNREGISTERED', status: 'FAILED' }); return }
    const path = join(this.context.output, `case-${test.title}.json`)
    const receipt = existsSync(path) ? JSON.parse(readFileSync(path, 'utf8')) : {}
    const checks = (receipt.checks || []).map((check: any) => ({
      id: selected.expectedCheckIds.includes(check.id) ? check.id : 'UNREGISTERED',
      status: check.status, executed: check.executed === true,
      ...(check.reasonCode ? { reasonCode: check.reasonCode === 'INJECTED_ASSERTION' ? 'INJECTED_ASSERTION' : 'E2E_ASSERTION_FAILED' } : {})
    }))
    this.cases.push({ id: selected.id, variant: selected.variant,
      status: result.status === 'passed' && test.expectedStatus === 'passed' ? 'PASSED' : 'FAILED',
      retry: result.retry, checks, miniEvidence: receipt.miniEvidence, stage: receipt.stage, driversCleanup: receipt.driversCleanup || 'NOT_RUN' })
  }
  onEnd(result: FullResult) {
    if (!this.cases.length) return // Discovery has no executed evidence to publish.
    const receipt = { runId: this.context.runId, owner: this.context.owner,
      sourceSha: this.context.sourceSha, sourceDigest: this.context.sourceDigest,
      planned: this.planned, scenarios: this.cases, complete: true,
      status: result.status === 'passed' ? 'PASSED' : 'FAILED',
      driversCleanup: this.cases.length > 0 && this.cases.every(c => c.driversCleanup === 'PASSED') ? 'PASSED' : 'FAILED',
      startedAt: result.startTime.getTime() / 1000, endedAt: Date.now() / 1000 }
    writeFileSync(join(this.context.output, 'receipt.json'), JSON.stringify(receipt, null, 2), { mode: 0o600 })
  }
}
