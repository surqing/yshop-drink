// Convert native V8 ranges for unchanged source files; unexecuted utils stay in the denominator.
const fs = require('node:fs')
const path = require('node:path')
const { fileURLToPath } = require('node:url')
const crypto = require('node:crypto')
const repo = path.resolve(__dirname, '../..')
const dependency = name => require(require.resolve(name, { paths: [path.join(repo, 'yshop-drink-vue3')] }))
const { mergeProcessCovs } = dependency('@bcoe/v8-coverage')
const converter = dependency('v8-to-istanbul')
const { createCoverageMap } = dependency('istanbul-lib-coverage')
async function main() {
  const [input, output] = process.argv.slice(2)
  if (!input || !output || fs.existsSync(output)) throw Error('FRESH_OUTPUT_REQUIRED')
  const files = fs.readdirSync(input).filter(n => n.endsWith('.json'))
  if (!files.length) throw Error('V8_EXECUTION_REQUIRED')
  const merged = mergeProcessCovs(files.map(n => JSON.parse(fs.readFileSync(path.join(input, n), 'utf8'))))
  const folder = path.join(repo, 'yshop-drink-uniapp-vue3/utils')
  const measured = new Map()
  for (const script of merged.result) {
    if (!script.url.startsWith('file:')) continue
    const name = fileURLToPath(script.url)
    if (path.dirname(name) === folder && name.endsWith('.js')) measured.set(name, script.functions)
  }
  const map = createCoverageMap({}), modules = {}
  for (const name of fs.readdirSync(folder).filter(n => n.endsWith('.js')).sort()) {
    const file = path.join(folder, name), source = fs.readFileSync(file, 'utf8')
    const coverage = converter(file, 0, { source }); await coverage.load()
    coverage.applyCoverage(measured.get(file) || [{ functionName: '', ranges: [{ startOffset: 0, endOffset: source.length, count: 0 }], isBlockCoverage: true }])
    const result = coverage.toIstanbul()
    if (!measured.has(file)) {
      const counters = result[file]
      for (const key of Object.keys(counters.s)) counters.s[key] = 0
      for (const key of Object.keys(counters.f)) counters.f[key] = 0
      for (const key of Object.keys(counters.b)) counters.b[key] = counters.b[key].map(() => 0)
    }
    map.merge(result)
    modules[path.relative(repo, file)] = { executed: measured.has(file), sourceHash: crypto.createHash('sha256').update(source).digest('hex'), ...map.fileCoverageFor(file).toSummary().toJSON() }
  }
  const required = ['catalog-options.js', 'ordering-context.js', 'coupon-context.js', 'auth-errors.js', 'sms-errors.js']
  if (required.some(n => !measured.has(path.join(folder, n)))) throw Error('CORE_SOURCE_NOT_EXECUTED')
  fs.mkdirSync(output, { recursive: false, mode: 0o700 })
  const summary = { result: 'MEASURED', engine: 'Node V8 + v8-to-istanbul', scope: 'all first-party UniApp utils/*.js; includes unexecuted modules; no GUI claim', ...map.getCoverageSummary().toJSON(), modules }
  fs.writeFileSync(path.join(output, 'coverage.json'), JSON.stringify(summary, null, 2))
  fs.writeFileSync(path.join(output, 'istanbul.json'), JSON.stringify(map.toJSON()))
  console.log(JSON.stringify({ result: summary.result, modules: Object.keys(modules).length, lines: summary.lines, branches: summary.branches }))
}
main().catch(() => { console.error('FRONTEND_COVERAGE_FAILED'); process.exitCode = 1 })
