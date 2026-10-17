const fs = require('node:fs'), path = require('node:path')
const { execFile } = require('node:child_process')
const { promisify } = require('node:util')
const exec = promisify(execFile)

// This pure selector is shared verbatim with the macOS script; fail closed before AXPress.
function locateConfirmation(windows, identity) {
  function requireValue(value) { if (!value) throw Error('OWNED_NATIVE_DIALOG_NOT_UNIQUE') }
  var runId = identity.runId, project = identity.project
  requireValue(/^[a-f0-9]{32}$/.test(runId) && project.charAt(0) === '/' && project.split('/').indexOf(runId) >= 0)
  var title = 'E2E-' + runId
  var owned = windows.filter(function (w) { return w.name === title || w.name.indexOf(title + ' - ') === 0 })
  requireValue(owned.length === 1)
  function flatten(node) { return [node].concat.apply([node], (node.children || []).map(flatten)) }
  function topAreas(node) {
    if (node.role === 'AXWebArea') return [node]
    return [].concat.apply([], (node.children || []).map(topAreas))
  }
  var areas = topAreas(owned[0].tree)
  requireValue(areas.length === 1 && typeof areas[0].url === 'string')
  var match = areas[0].url.match(/[?&#]projectpath=([^&#]*)/i)
  requireValue(match)
  var decoded
  try { decoded = decodeURIComponent(match[1]) } catch (_) { requireValue(false) }
  requireValue(decoded === project)
  var candidates = flatten(areas[0]).filter(function (node) {
    if (node.role !== 'AXGroup') return false
    var children = flatten(node)
    return children.length <= 30 && children.some(function (c) { return c.text === '取消订单' }) &&
      children.some(function (c) { return c.text === '取消后会恢复库存和预占优惠券' })
  })
  // Select the smallest complete dialog scope, never an unrelated page-level link.
  candidates = candidates.filter(function (node) {
    return !candidates.some(function (other) { return other !== node && flatten(node).indexOf(other) >= 0 })
  })
  requireValue(candidates.length === 1)
  var links = flatten(candidates[0]).filter(function (node) { return node.role === 'AXLink' && node.text === '确定' })
  requireValue(links.length === 1)
  return links[0]
}

async function confirmWithAccessibility(context, output) {
  if (process.platform !== 'darwin' || context.nativeAdapter !== 'applescript') throw Error('NATIVE_ADAPTER_NOT_ENABLED')
  // Derive the official main executable from the selected CLI bundle; bind System Events to its exact PID.
  const macOS = path.dirname(fs.realpathSync(context.cli))
  const { stdout: executable } = await exec('/usr/libexec/PlistBuddy', ['-c', 'Print CFBundleExecutable', path.join(macOS, '../Info.plist')], { timeout: 5000 })
  const target = path.join(macOS, executable.trim())
  const { stdout: processes } = await exec('/bin/ps', ['-axo', 'pid=,comm='], { timeout: 5000 })
  const matches = processes.split('\n').map(line => line.trim().match(/^(\d+)\s+(.+)$/)).filter(match => match && match[2] === target)
  if (matches.length !== 1) throw Error('OFFICIAL_PROCESS_NOT_UNIQUE')
  const script = `${locateConfirmation.toString()}
function run(argv) {
  var application = Application('System Events');
  var processes = application.processes.whose({unixId: Number(argv[0])})();
  if (processes.length !== 1) throw Error('OFFICIAL_PROCESS_NOT_UNIQUE');
  var title = 'E2E-' + argv[1], count = 0;
  var windows = processes[0].windows().filter(function(w) { var name = w.name(); return name === title || name.indexOf(title + ' - ') === 0; });
  if (windows.length !== 1) throw Error('OWNED_WINDOW_NOT_UNIQUE');
  function attribute(element, name) { try { var value = element.attributes.byName(name).value(); return typeof value === 'string' ? value : ''; } catch (_) { return ''; } }
  function read(element, depth) {
    if (++count > 2000 || depth > 40) throw Error('OWNED_TREE_LIMIT');
    var node = {role: attribute(element, 'AXRole'), text: attribute(element, 'AXTitle') || attribute(element, 'AXValue') || attribute(element, 'AXDescription'), url: attribute(element, 'AXURL'), ref: element};
    node.children = element.uiElements().map(function(child) { return read(child, depth + 1); });
    return node;
  }
  var tree = read(windows[0], 0);
  var selected = locateConfirmation([{name: windows[0].name(), tree: tree}], {runId: argv[1], project: argv[2]});
  selected.ref.actions.byName('AXPress').perform();
  return 'OWNED_AXPRESS_COMPLETED';
}
`
  const scriptPath = path.join(output, 'native-confirmation-private.js')
  fs.writeFileSync(scriptPath, script, { mode: 0o600 })
  try {
    const result = await exec('/usr/bin/osascript', ['-l', 'JavaScript', scriptPath, matches[0][1], context.runId, context.project], { timeout: 25000, maxBuffer: 1024 * 1024 })
    if (result.stdout.trim() !== 'OWNED_AXPRESS_COMPLETED') throw Error('OWNED_AXPRESS_INCOMPLETE')
  } catch (error) {
    fs.writeFileSync(path.join(output, 'native-confirmation-error-private.log'), String(error.stderr || error.message), { mode: 0o600 })
    throw Error('MINI_NATIVE_CONFIRMATION_UNAVAILABLE')
  }
}
module.exports = { locateConfirmation, confirmWithAccessibility }
