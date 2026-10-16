const fs = require('node:fs'), path = require('node:path')
const { spawn } = require('node:child_process')
const WebSocket = require('ws')
const automator = require('miniprogram-automator')
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

// CLI socket availability precedes SDK initialization. Never bypass automator's version check.
async function toolReady(endpoint) {
  return new Promise(resolve => {
    const socket = new WebSocket(endpoint)
    let done = false
    const finish = ready => {
      if (done) return
      done = true; clearTimeout(timer)
      socket.terminate(); resolve(ready)
    }
    const timer = setTimeout(() => finish(false), 2000)
    socket.on('error', () => finish(false))
    socket.on('close', () => finish(false))
    socket.on('open', () => socket.send(JSON.stringify({ id: 1, method: 'Tool.getInfo', params: {} })))
    socket.on('message', data => {
      try {
        const response = JSON.parse(data)
        if (response.id === 1) finish(typeof response.result?.SDKVersion === 'string' && !!response.result.SDKVersion)
      } catch { finish(false) }
    })
  })
}

async function launchOfficial(context, output) {
  const log = fs.openSync(path.join(output, 'official-launch-private.log'), 'w', 0o600)
  const child = spawn(context.cli, ['auto', '--project', context.project, '--auto-port', String(context.port)],
    { stdio: ['ignore', log, log] })
  fs.closeSync(log)
  let failure
  child.on('error', error => { failure = error })
  child.on('exit', code => { if (code) failure = Error('OFFICIAL_CLI_FAILED') })
  const endpoint = `ws://127.0.0.1:${context.port}`
  const deadline = Date.now() + 90000
  while (Date.now() < deadline) {
    if (failure) throw failure
    if (await toolReady(endpoint)) return automator.connect({ wsEndpoint: endpoint })
    await sleep(250)
  }
  throw Error('OFFICIAL_SDK_NOT_READY')
}
module.exports = { launchOfficial, toolReady }
