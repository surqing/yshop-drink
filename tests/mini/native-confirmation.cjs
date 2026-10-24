const fs = require('node:fs'), path = require('node:path')
const { execFile } = require('node:child_process')
const { promisify } = require('node:util')
const exec = promisify(execFile)

function locateConfirmation(windows, identity) {
  function flatten(node) { return [node].concat.apply([node], (node.children || []).map(flatten)) }
  function topAreas(node) {
    if (node.role === 'AXWebArea') return [node]
    return [].concat.apply([], (node.children || []).map(topAreas))
  }
  function fail(code, diagnostics) {
    diagnostics.result = 'BLOCKED'
    diagnostics.failureCode = code
    const error = Error(code)
    error.code = code
    error.diagnostics = diagnostics
    throw error
  }

  windows = Array.isArray(windows) ? windows : []
  identity = identity || {}
  const runId = identity.runId, project = identity.project
  const title = typeof runId === 'string' ? 'E2E-' + runId : ''
  const inventory = windows.map(function (window, index) {
    const name = typeof window.name === 'string' ? window.name : ''
    return { index: index, title: name, owned: !!title && (name === title || name.indexOf(title + ' - ') === 0) }
  })
  const diagnostics = {
    pid: Number.isInteger(identity.pid) ? identity.pid : null,
    runId: typeof runId === 'string' ? runId : null,
    project: typeof project === 'string' ? project : null,
    axWindowsStatusCode: Number.isInteger(identity.axWindowsStatusCode) ? identity.axWindowsStatusCode : null,
    windowTotal: Number.isInteger(identity.axWindowsStatusCode) && identity.axWindowsStatusCode !== 0 ? null : windows.length,
    windows: inventory,
    ownedWindowCount: inventory.filter(function (window) { return window.owned }).length,
    webAreaCount: 0,
    webAreaUrls: [],
    projectPathMatchCount: 0,
    dialogScopeCount: 0,
    confirmationNodeMatchCount: 0,
    result: 'INSPECTING',
    failureCode: null
  }

  if (!/^[a-f0-9]{32}$/.test(runId || '') || typeof project !== 'string' || project.charAt(0) !== '/' || project.split('/').indexOf(runId) < 0) {
    fail('INVALID_OWNER_IDENTITY', diagnostics)
  }
  if (Number.isInteger(identity.axWindowsStatusCode) && identity.axWindowsStatusCode !== 0) {
    fail('AX_WINDOWS_READ_FAILED_' + identity.axWindowsStatusCode, diagnostics)
  }
  if (diagnostics.ownedWindowCount !== 1) fail('OWNED_WINDOW_NOT_UNIQUE', diagnostics)

  const ownedIndex = inventory.filter(function (window) { return window.owned })[0].index
  const areas = topAreas(windows[ownedIndex].tree || {})
  diagnostics.webAreaCount = areas.length
  diagnostics.webAreaUrls = areas.map(function (area) { return typeof area.url === 'string' ? area.url : '' })
  if (areas.length !== 1) fail('OWNED_WEB_AREA_NOT_UNIQUE', diagnostics)
  if (typeof areas[0].url !== 'string' || !areas[0].url) fail('OWNED_PROJECT_URL_MISSING', diagnostics)

  const match = areas[0].url.match(/[?&#]projectpath=([^&#]*)/i)
  if (match) {
    try {
      if (decodeURIComponent(match[1]) === project) diagnostics.projectPathMatchCount = 1
    } catch (_) { /* A malformed URL is an ownership mismatch and fails closed below. */ }
  }
  if (diagnostics.projectPathMatchCount !== 1) fail('OWNED_PROJECT_PATH_MISMATCH', diagnostics)

  const candidates = flatten(areas[0]).filter(function (node) {
    if (node.role !== 'AXGroup') return false
    const children = flatten(node)
    return children.length <= 30 && children.some(function (child) { return child.text === '取消订单' }) &&
      children.some(function (child) { return child.text === '取消后会恢复库存和预占优惠券' })
  })
  // Select the smallest complete dialog scope, never an unrelated page-level link.
  const scopes = candidates.filter(function (node) {
    return !candidates.some(function (other) { return other !== node && flatten(node).indexOf(other) >= 0 })
  })
  diagnostics.dialogScopeCount = scopes.length
  const links = []
  scopes.forEach(function (scope) {
    flatten(scope).forEach(function (node) {
      if (node.role === 'AXLink' && node.text === '确定') links.push(node)
    })
  })
  diagnostics.confirmationNodeMatchCount = links.length
  if (scopes.length !== 1) fail('OWNED_DIALOG_NOT_UNIQUE', diagnostics)
  if (links.length !== 1) fail('OWNED_CONFIRMATION_NOT_UNIQUE', diagnostics)

  diagnostics.result = 'SELECTED'
  return { selected: links[0], diagnostics: diagnostics }
}

function confirmationPath({ nativeCallbackObserved, accessibilityAttempted, accessibilityActionCompleted, callbackObserved }) {
  if (nativeCallbackObserved) return 'OFFICIAL_NATIVE_CALLBACK'
  if (accessibilityAttempted && !accessibilityActionCompleted) return 'ACCESSIBILITY_AXPRESS_FAILED'
  if (accessibilityAttempted && accessibilityActionCompleted && callbackObserved) return 'ACCESSIBILITY_AXPRESS_WITH_REAL_CALLBACK'
  if (accessibilityAttempted && accessibilityActionCompleted) return 'ACCESSIBILITY_AXPRESS_WITHOUT_CALLBACK'
  if (callbackObserved) return 'CALLBACK_WITHOUT_RECORDED_ACTION'
  return 'NO_CONFIRMED_PATH'
}

function writePrivate(output, name, value) {
  const destination = path.join(output, name)
  fs.writeFileSync(destination, JSON.stringify(value, null, 2) + '\n', { mode: 0o600 })
  try { fs.chmodSync(destination, 0o600) } catch (_) { /* Creation mode remains the fallback. */ }
  return destination
}

async function confirmWithAccessibility(context, output) {
  const evidence = {
    adapter: 'macos-applescript-accessibility',
    pid: null,
    processMatchCount: 0,
    processPids: [],
    runId: context.runId,
    project: context.project,
    axWindowsStatusCode: null,
    windowTotal: null,
    windows: [],
    ownedWindowCount: 0,
    webAreaCount: 0,
    webAreaUrls: [],
    projectPathMatchCount: 0,
    dialogScopeCount: 0,
    confirmationNodeMatchCount: 0,
    action: 'AXPress',
    result: 'BLOCKED',
    failureCode: null
  }
  const receiptPath = path.join(output, 'native-confirmation-accessibility-private.json')
  const persist = () => writePrivate(output, path.basename(receiptPath), evidence)
  function reject(code) {
    evidence.failureCode = code
    evidence.result = 'BLOCKED'
    persist()
    const error = Error('MINI_NATIVE_CONFIRMATION_UNAVAILABLE')
    error.code = code
    error.diagnostics = { ...evidence }
    throw error
  }

  if (process.platform !== 'darwin' || context.nativeAdapter !== 'applescript') reject('NATIVE_ADAPTER_NOT_ENABLED')
  try {
    // Derive the official main executable from the selected CLI bundle; bind AX to its exact PID.
    const macOS = path.dirname(fs.realpathSync(context.cli))
    const { stdout: executable } = await exec('/usr/libexec/PlistBuddy', ['-c', 'Print CFBundleExecutable', path.join(macOS, '../Info.plist')], { timeout: 5000 })
    const target = path.join(macOS, executable.trim())
    const { stdout: processes } = await exec('/bin/ps', ['-axo', 'pid=,comm='], { timeout: 5000 })
    const rows = processes.split('\n').map(line => line.trim().match(/^(\d+)\s+(.+)$/)).filter(Boolean)
    const matches = rows.filter(match => match[2] === target)
    evidence.processMatchCount = matches.length
    evidence.processPids = matches.map(match => Number(match[1]))
    if (matches.length !== 1) reject('OFFICIAL_PROCESS_NOT_UNIQUE')
    evidence.pid = Number(matches[0][1])

    const script = `${locateConfirmation.toString()}
ObjC.import('ApplicationServices')
ObjC.bindFunction('AXUIElementCreateApplication',['id',['int']])
ObjC.bindFunction('AXUIElementCopyAttributeValue',['int',['id','id','id*']])
ObjC.bindFunction('AXUIElementPerformAction',['int',['id','id']])
function run(argv){
var pid=Number(argv[0]),runId=argv[1],project=argv[2],count=0;
var diag={pid:pid,runId:runId,project:project,axWindowsStatusCode:null,windowTotal:0,windows:[],ownedWindowCount:0,webAreaCount:0,webAreaUrls:[],projectPathMatchCount:0,dialogScopeCount:0,confirmationNodeMatchCount:0,action:'AXPress',result:'BLOCKED',failureCode:null};
function copy(e,n){var out=Ref(),status=$.AXUIElementCopyAttributeValue(e,$(n),out);return {status:status,value:status===0?out[0]:null};}
function attr(e,n){return copy(e,n).value;}
function text(e,n){var v=attr(e,n);if(!v)return '';var u=ObjC.unwrap(v);return typeof u==='string'?u:'';}
function children(e){var v=attr(e,'AXChildren');return v?ObjC.unwrap(v):[];}
function read(e,depth){if(++count>2000||depth>96)throw Error('OWNED_TREE_LIMIT');var role=text(e,'AXRole'),u=role==='AXWebArea'?attr(e,'AXURL'):null;return {role:role,text:text(e,'AXTitle')||text(e,'AXValue')||text(e,'AXDescription'),url:u?ObjC.unwrap(u.absoluteString):'',ref:e,children:children(e).map(function(c){return read(c,depth+1)})};}
try{
var app=$.AXUIElementCreateApplication(pid),windowsRead=copy(app,'AXWindows'),winValue=windowsRead.value;
diag.axWindowsStatusCode=windowsRead.status;
if(windowsRead.status!==0){try{locateConfirmation([],{pid:pid,runId:runId,project:project,axWindowsStatusCode:windowsRead.status});}catch(error){diag=error.diagnostics||diag;}diag.pid=pid;diag.axWindowsStatusCode=windowsRead.status;diag.action='AXPress';diag.result='BLOCKED';return JSON.stringify(diag);}
var nativeWindows=winValue?ObjC.unwrap(winValue):[];diag.windowTotal=nativeWindows.length;
var windows=nativeWindows.map(function(w,i){var name=text(w,'AXTitle');diag.windows.push({index:i,title:name,owned:false});return {name:name,tree:{},ref:w,index:i};});
var title='E2E-'+runId;
diag.windows.forEach(function(w){w.owned=w.title===title||w.title.indexOf(title+' - ')===0;if(w.owned)diag.ownedWindowCount++;});
if(diag.ownedWindowCount===1){var index=diag.windows.filter(function(w){return w.owned;})[0].index;windows[index].tree=read(windows[index].ref,0);}
var result=locateConfirmation(windows,{pid:pid,runId:runId,project:project,axWindowsStatusCode:windowsRead.status});diag=result.diagnostics;diag.action='AXPress';
var action=$.AXUIElementPerformAction(result.selected.ref,$('AXPress'));
if(action!==0){diag.result='BLOCKED';diag.failureCode='OWNED_AXPRESS_FAILED_'+action;return JSON.stringify(diag);}
diag.result='AXPRESS_COMPLETED';diag.failureCode=null;return JSON.stringify(diag);
}catch(error){
if(error&&error.diagnostics)diag=error.diagnostics;
diag.pid=pid;diag.action='AXPress';diag.result='BLOCKED';diag.failureCode=(error&&error.code)||String(error&&error.message||'OWNED_ACCESSIBILITY_INSPECTION_FAILED');
return JSON.stringify(diag);
}
}
`
    const scriptPath = path.join(output, 'native-confirmation-private.js')
    fs.writeFileSync(scriptPath, script, { mode: 0o600 })
    fs.chmodSync(scriptPath, 0o600)
    let result
    try {
      const execution = await exec('/usr/bin/osascript', ['-l', 'JavaScript', scriptPath, String(evidence.pid), context.runId, context.project], { timeout: 25000, maxBuffer: 1024 * 1024 })
      result = JSON.parse(execution.stdout.trim())
    } catch (error) {
      evidence.failureCode = 'ACCESSIBILITY_SCRIPT_EXECUTION_FAILED'
      evidence.failureDetail = String(error.stderr || error.message || 'osascript failed')
      reject('ACCESSIBILITY_SCRIPT_EXECUTION_FAILED')
    }
    Object.assign(evidence, result)
    persist()
    if (result.result !== 'AXPRESS_COMPLETED') reject(result.failureCode || 'OWNED_ACCESSIBILITY_INSPECTION_FAILED')
    return evidence
  } catch (error) {
    if (error && error.code === 'ACCESSIBILITY_SCRIPT_EXECUTION_FAILED') throw error
    if (evidence.result !== 'BLOCKED' || !evidence.failureCode) {
      evidence.failureCode = 'ACCESSIBILITY_DIAGNOSTIC_COLLECTION_FAILED'
      evidence.failureDetail = String(error && error.message || error)
      persist()
    }
    if (error && error.message === 'MINI_NATIVE_CONFIRMATION_UNAVAILABLE') throw error
    reject('ACCESSIBILITY_DIAGNOSTIC_COLLECTION_FAILED')
  }
}

module.exports = { locateConfirmation, confirmationPath, confirmWithAccessibility }
