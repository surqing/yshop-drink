/** Start a loopback-only browser fixture for the real Vue page; external APIs are never used. */
import { createServer } from 'vite'

const port = 5175
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

const server = await createServer({
  server: { host: '127.0.0.1', port, strictPort: true, open: false },
  plugins: [
    {
      name: 'printer-browser-acceptance',
      configureServer(server) {
        server.middlewares.use(async (request, response, next) => {
          const url = new URL(request.url, `http://127.0.0.1:${port}`)
          if (url.pathname === '/__printer-preview') {
            const html = await server.transformIndexHtml(
              url.pathname,
              '<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><title>打印机页面验收 · 模拟数据</title></head><body><div style="padding:16px;background:#fff3cd">仅用于页面验收 · 模拟设备，不连接真实打印服务</div><div id="app" style="padding:24px"></div><script type="module" src="/tests/printer/preview.ts"></script></body></html>'
            )
            response.setHeader('Content-Type', 'text/html; charset=utf-8')
            response.end(html)
            return
          }
          if (url.pathname === '/__printer-fixture') {
            if (url.searchParams.has('configured'))
              configured = url.searchParams.get('configured') === '1'
            response.setHeader('Content-Type', 'application/json')
            response.end(
              JSON.stringify({
                configured,
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
              data = jobs.find((j) => j.printerId === id && j.requestId === url.searchParams.get('requestId')) || null
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
await server.listen()
console.log(`Printer browser acceptance: http://127.0.0.1:${port}/__printer-preview`)
