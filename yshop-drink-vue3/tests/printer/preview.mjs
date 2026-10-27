/** Start a loopback-only browser fixture for the real Vue page; external APIs are never used. */
import { createServer } from 'vite'
import { createServer as createPortProbe } from 'node:net'
import { readFileSync, realpathSync, unwatchFile, watchFile } from 'node:fs'
import { isAbsolute, join, resolve } from 'node:path'
import { createHash } from 'node:crypto'

const requestedPort = process.env.YSHOP_PRINTER_BROWSER_PORT
const port = requestedPort
  ? Number(requestedPort)
  : await new Promise((resolve, reject) => {
      const probe = createPortProbe()
      probe.once('error', reject)
      probe.listen(0, '127.0.0.1', () => {
        const selected = probe.address().port
        probe.close((error) => (error ? reject(error) : resolve(selected)))
      })
    })
if (!Number.isInteger(port) || port < 1 || port > 65535)
  throw new Error('INVALID_PRINTER_BROWSER_PORT')
process.env.VITE_BASE_URL = `http://127.0.0.1:${port}`
process.env.VITE_API_URL = '/admin-api'
process.argv[3] = 'dev'
let configured = false
let devices = []
let jobs = []
let nextId = 1
let submitted = 0
const provider = () => ({
  code: 'YILIAN_YUN',
  name: '易联云',
  connectionTypes: ['CLOUD'],
  configFields: [{ key: 'terminalKey', label: '终端密钥', secret: true, required: false }],
  capabilities: { authorize: true, deauthorize: true, status: true, textPrint: true },
  configured,
  requiresAuthorization: true
})
const shops = [
  { id: 1, name: '测试门店一' },
  { id: 2, name: '测试门店二' }
]
let markOptimizerReady
let optimizerReadyResolved = false
const optimizerReady = new Promise((resolve) => { markOptimizerReady = resolve })
const resolveOptimizerReady = () => {
  if (optimizerReadyResolved) return
  optimizerReadyResolved = true
  markOptimizerReady()
}
const bypassOptimizerGate = process.env.YSHOP_PRINTER_BROWSER_TEST_BYPASS_OPTIMIZER_READY === 'test-only'
const awaitOptimizerReady = () => bypassOptimizerGate ? Promise.resolve() : optimizerReady
const requestedCacheDir = process.env.YSHOP_PRINTER_BROWSER_CACHE_DIR
if (!requestedCacheDir || !isAbsolute(requestedCacheDir))
  throw new Error('PRINTER_PREVIEW_CACHE_DIR_REQUIRED')
const expectedCacheDir = realpathSync(resolve(requestedCacheDir))
const cacheOwnerId = process.env.YSHOP_PRINTER_BROWSER_CACHE_OWNER_ID
const ownerMarkerPath = join(expectedCacheDir, '.r4-printer-cache-owner.json')
const ownerMarker = JSON.parse(readFileSync(ownerMarkerPath, 'utf8'))
if (!/^[a-f0-9]{32}$/.test(cacheOwnerId || '') || ownerMarker.cacheOwnerId !== cacheOwnerId ||
    ownerMarker.cacheDir !== expectedCacheDir ||
    ownerMarker.sourceSha !== process.env.YSHOP_PRINTER_BROWSER_SOURCE_SHA ||
    ownerMarker.sourceDigest !== process.env.YSHOP_PRINTER_BROWSER_SOURCE_DIGEST)
  throw new Error('PRINTER_PREVIEW_CACHE_OWNER_MISMATCH')
const validMetadata = (metadata) => metadata &&
  ['hash', 'configHash', 'lockfileHash', 'browserHash'].every((key) => typeof metadata[key] === 'string' && metadata[key].length > 0)
const readValidMetadata = (metadataPath) => {
  try {
    const bytes = readFileSync(metadataPath)
    const metadata = JSON.parse(bytes.toString('utf8'))
    return validMetadata(metadata) ? { bytes, metadata } : null
  } catch {
    return null
  }
}
let cacheHandshake = null
const waitForOptimizerMetadata = (metadataPath) => new Promise((resolve, reject) => {
  if (readValidMetadata(metadataPath)) return resolve()
  const onChange = (current) => {
    if (!current.isFile() || !readValidMetadata(metadataPath)) return
    clearTimeout(deadline)
    unwatchFile(metadataPath, onChange)
    resolve()
  }
  const deadline = setTimeout(() => {
    unwatchFile(metadataPath, onChange)
    reject(new Error('PRINTER_PREVIEW_OPTIMIZER_METADATA_TIMEOUT'))
  }, 60_000)
  watchFile(metadataPath, { interval: 100, persistent: false }, onChange)
})

const server = await createServer({
  cacheDir: expectedCacheDir,
  optimizeDeps: {
    entries: ['tests/printer/preview.html'],
    holdUntilCrawlEnd: false,
    include: [
      'element-plus/es/components/base/style/css',
      'element-plus/es/components/loading/style/css',
      'element-plus/es/components/tabs/style/css',
      'element-plus/es/components/tab-pane/style/css',
      'element-plus/es/components/scrollbar/style/css',
      'element-plus/es/components/message/style/css',
      'element-plus/es/components/message-box/style/css',
      'element-plus/es/components/notification/style/css',
      'element-plus/es/components/icon/style/css',
      'vue-ueditor-wrap',
      'vue-dompurify-html',
      'element-plus/es/components/aside/style/css',
      'element-plus/es/components/container/style/css',
      'element-plus/es/components/divider/style/css',
      'element-plus/es/components/header/style/css',
      'element-plus/es/components/main/style/css',
      'element-plus/es/components/popconfirm/style/css',
      'element-plus/es/components/transfer/style/css',
      '@form-create/designer',
      '@form-create/element-ui/auto-import',
      'element-plus/es/components/config-provider/style/css',
      'element-plus/es/components/popover/style/css'
    ]
  },
  server: { host: '127.0.0.1', port, strictPort: true, open: false },
  plugins: [
    {
      name: 'printer-browser-acceptance',
      configureServer(server) {
        server.middlewares.use(async (request, response, next) => {
          const url = new URL(request.url, `http://127.0.0.1:${port}`)
          if (url.pathname === '/__printer-preview') {
            await awaitOptimizerReady()
            const html = await server.transformIndexHtml(
              url.pathname,
              readFileSync(new URL('./preview.html', import.meta.url), 'utf8')
            )
            response.setHeader('Content-Type', 'text/html; charset=utf-8')
            response.end(html)
            return
          }
          if (url.pathname === '/__printer-fixture') {
            await awaitOptimizerReady()
            if (url.searchParams.has('configured'))
              configured = url.searchParams.get('configured') === '1'
            response.setHeader('Content-Type', 'application/json')
            response.end(
              JSON.stringify({
                configured,
                owner: process.env.YSHOP_PRINTER_BROWSER_OWNER || null,
                optimizerReady: optimizerReadyResolved,
                cacheHandshake,
                deviceCount: devices.length,
                submitted,
                jobCount: jobs.length
              })
            )
            return
          }
          if (!url.pathname.startsWith('/admin-api/store/printer/')) return next()
          let raw = ''
          for await (const chunk of request) raw += chunk
          const body = raw ? JSON.parse(raw) : {}
          const path = url.pathname.substring('/admin-api/store/printer/'.length)
          const id = Number(url.searchParams.get('id') || body.id)
          const device = devices.find((item) => item.id === id)
          let data
          switch (path) {
            case 'providers':
              data = [provider()]
              break
            case 'shops':
              data = shops
              break
            case 'page': {
              const filtered = devices.filter(
                (d) =>
                  d.deleted === (url.searchParams.get('deleted') === 'true') &&
                  (!url.searchParams.get('name') ||
                    d.name.includes(url.searchParams.get('name'))) &&
                  (!url.searchParams.get('shopId') ||
                    d.shopId === Number(url.searchParams.get('shopId')))
              )
              data = { list: filtered, total: filtered.length }
              break
            }
            case 'get':
              data = device
              break
            case 'create': {
              const { configuration, ...publicFields } = body
              const row = {
                ...publicFields,
                id: nextId++,
                deleted: false,
                authorization: 'UNAUTHORIZED',
                deviceState: 'UNKNOWN',
                configuredFields: Object.keys(configuration).filter((key) => configuration[key]),
                createTime: new Date().toISOString()
              }
              devices.push(row)
              data = row.id
              break
            }
            case 'update': {
              const { configuration, ...publicFields } = body
              Object.assign(device, publicFields)
              device.configuredFields = [
                ...new Set([
                  ...device.configuredFields,
                  ...Object.keys(configuration).filter((key) => configuration[key])
                ])
              ]
              data = device.id
              break
            }
            case 'binding': {
              if (request.method === 'DELETE') {
                device.shopId = null
                device.shopName = null
              } else {
                device.shopId = body.shopId
                device.shopName = shops.find((s) => s.id === body.shopId).name
              }
              data = true
              break
            }
            case 'reconnect':
            case 'authorize':
              device.authorization = 'AUTHORIZED'
              data = { authorization: device.authorization }
              break
            case 'deauthorize':
              device.authorization = 'UNAUTHORIZED'
              data = { authorization: device.authorization }
              break
            case 'status':
              data = { state: device.deviceState, checkedAt: device.stateCheckedAt }
              break
            case 'status/refresh':
              device.deviceState = 'PAPER_OUT'
              device.stateCheckedAt = new Date().toISOString()
              data = { state: device.deviceState }
              break
            case 'test-print': {
              let job = jobs.find((j) => j.requestId === body.requestId)
              if (!job) {
                job = {
                  requestId: body.requestId,
                  printerId: id,
                  printerName: device.name,
                  state: 'UNKNOWN',
                  errorCode: 'PROVIDER_TIMEOUT',
                  createTime: new Date().toISOString()
                }
                jobs.push(job)
                submitted++
              }
              data = job
              break
            }
            case 'test-print/get':
              data =
                jobs.find(
                  (j) => j.printerId === id && j.requestId === url.searchParams.get('requestId')
                ) || null
              break
            case 'test-print/page':
              data = {
                list: jobs.filter((j) => j.printerId === id),
                total: jobs.filter((j) => j.printerId === id).length
              }
              break
            case 'delete':
              device.deleted = true
              device.enabled = false
              data = true
              break
            default:
              response.statusCode = 404
              response.end()
              return
          }
          response.setHeader('Content-Type', 'application/json')
          response.end(JSON.stringify({ code: 200, data, msg: '' }))
        })
      }
    }
  ]
})
const configuredCacheDir = realpathSync(server.config.cacheDir)
if (configuredCacheDir !== expectedCacheDir)
  throw new Error('PRINTER_PREVIEW_RESOLVED_CACHE_DIR_MISMATCH')
await server.listen()
const metadataPath = join(configuredCacheDir, 'deps', '_metadata.json')
if (!bypassOptimizerGate) await waitForOptimizerMetadata(metadataPath)
const metadataRead = readValidMetadata(metadataPath)
if (metadataRead) {
  cacheHandshake = {
    optimizerReady: true,
    cacheOwnerId,
    pid: process.pid,
    resolvedCacheDir: realpathSync(server.config.cacheDir),
    configCacheDir: configuredCacheDir,
    metadataPath: realpathSync(metadataPath),
    metadataSha256: createHash('sha256').update(metadataRead.bytes).digest('hex'),
    metadata: metadataRead.metadata,
  }
  resolveOptimizerReady()
}
console.log(`Printer browser acceptance: http://127.0.0.1:${port}/__printer-preview`)
