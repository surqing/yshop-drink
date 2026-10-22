import test from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
const { locateConfirmation, confirmationPath } = createRequire(import.meta.url)('./mini/native-confirmation.cjs')
const runId = '0123456789abcdef0123456789abcdef', project = `/private/tmp/${runId}/mini/project/unpackage/dist/dev/mp-weixin`
function fixture() { return [{ name: `E2E-${runId} - 微信开发者工具`, tree: { role: 'AXWindow', children: [
  { role: 'AXWebArea', url: `file:///official/index.html?projectpath=${encodeURIComponent(project)}`, children: [
    { role: 'AXGroup', children: [{ role: 'AXStaticText', text: '取消订单' }, { role: 'AXStaticText', text: '取消后会恢复库存和预占优惠券' },
      { role: 'AXLink', text: '取消', id: 'cancel' }, { role: 'AXLink', text: '确定', id: 'owned-confirm' }] }
  ] }] } }] }
test('selects the exact owned confirmation link and reports its ownership diagnostics', () => {
  const result = locateConfirmation(fixture(), { runId, project, pid: 4242 })
  assert.equal(result.selected.id, 'owned-confirm')
  assert.deepEqual(result.diagnostics, {
    pid: 4242,
    runId,
    project,
    axWindowsStatusCode: null,
    windowTotal: 1,
    windows: [{ index: 0, title: `E2E-${runId} - 微信开发者工具`, owned: true }],
    ownedWindowCount: 1,
    webAreaCount: 1,
    webAreaUrls: [`file:///official/index.html?projectpath=${encodeURIComponent(project)}`],
    projectPathMatchCount: 1,
    dialogScopeCount: 1,
    confirmationNodeMatchCount: 1,
    result: 'SELECTED',
    failureCode: null
  })
})
test('records whether confirmation came from the official callback or accessibility fallback', () => {
  assert.equal(confirmationPath({ nativeCallbackObserved: true }), 'OFFICIAL_NATIVE_CALLBACK')
  assert.equal(confirmationPath({ accessibilityAttempted: true, accessibilityActionCompleted: true, callbackObserved: false }), 'ACCESSIBILITY_AXPRESS_WITHOUT_CALLBACK')
  assert.equal(confirmationPath({ accessibilityAttempted: true, accessibilityActionCompleted: true, callbackObserved: true }), 'ACCESSIBILITY_AXPRESS_WITH_REAL_CALLBACK')
  assert.equal(confirmationPath({ accessibilityAttempted: true, accessibilityActionCompleted: false, callbackObserved: false }), 'ACCESSIBILITY_AXPRESS_FAILED')
  assert.equal(confirmationPath({}), 'NO_CONFIRMED_PATH')
})
test('reports zero-window ownership failure without attempting confirmation', () => {
  assert.throws(() => locateConfirmation([], { runId, project, pid: 4242 }), error => {
    assert.equal(error.code, 'OWNED_WINDOW_NOT_UNIQUE')
    assert.equal(error.diagnostics.pid, 4242)
    assert.equal(error.diagnostics.windowTotal, 0)
    assert.deepEqual(error.diagnostics.windows, [])
    assert.equal(error.diagnostics.ownedWindowCount, 0)
    assert.equal(error.diagnostics.confirmationNodeMatchCount, 0)
    assert.equal(error.diagnostics.result, 'BLOCKED')
    return true
  })
})
test('reports all candidate window titles when more than one run-owned window exists', () => {
  const windows = fixture()
  windows.push({ ...fixture()[0], name: `E2E-${runId} - 微信开发者工具 副窗口` })
  assert.throws(() => locateConfirmation(windows, { runId, project }), error => {
    assert.equal(error.code, 'OWNED_WINDOW_NOT_UNIQUE')
    assert.equal(error.diagnostics.windowTotal, 2)
    assert.deepEqual(error.diagnostics.windows.map(window => window.title), [
      `E2E-${runId} - 微信开发者工具`,
      `E2E-${runId} - 微信开发者工具 副窗口`
    ])
    assert.equal(error.diagnostics.ownedWindowCount, 2)
    assert.equal(error.diagnostics.confirmationNodeMatchCount, 0)
    return true
  })
})
test('reports foreign windows but never treats them as owned candidates', () => {
  assert.throws(() => locateConfirmation([{ name: 'Other developer project', tree: { role: 'AXWindow', children: [] } }], { runId, project }), error => {
    assert.equal(error.code, 'OWNED_WINDOW_NOT_UNIQUE')
    assert.equal(error.diagnostics.windowTotal, 1)
    assert.deepEqual(error.diagnostics.windows, [{ index: 0, title: 'Other developer project', owned: false }])
    assert.equal(error.diagnostics.ownedWindowCount, 0)
    return true
  })
})
test('preserves AXWindows permission or availability status instead of misreporting zero windows', () => {
  assert.throws(() => locateConfirmation([], { runId, project, pid: 4242, axWindowsStatusCode: -25204 }), error => {
    assert.equal(error.code, 'AX_WINDOWS_READ_FAILED_-25204')
    assert.equal(error.diagnostics.pid, 4242)
    assert.equal(error.diagnostics.axWindowsStatusCode, -25204)
    assert.equal(error.diagnostics.windowTotal, null)
    assert.equal(error.diagnostics.ownedWindowCount, 0)
    return true
  })
})
test('rejects foreign or duplicate named windows', () => {
  const wrong = fixture(); wrong[0].name = 'Developer project'; assert.throws(() => locateConfirmation(wrong, { runId, project }))
  assert.throws(() => locateConfirmation([...fixture(), ...fixture()], { runId, project }))
})
test('rejects a same-named window with foreign project URL', () => {
  const wrong = fixture(); wrong[0].tree.children[0].url = 'file:///official/index.html?projectpath=%2Fdeveloper-project'
  assert.throws(() => locateConfirmation(wrong, { runId, project }), error => {
    assert.equal(error.code, 'OWNED_PROJECT_PATH_MISMATCH')
    assert.equal(error.diagnostics.ownedWindowCount, 1)
    assert.equal(error.diagnostics.webAreaCount, 1)
    assert.equal(error.diagnostics.projectPathMatchCount, 0)
    assert.equal(error.diagnostics.confirmationNodeMatchCount, 0)
    return true
  })
})
test('rejects unrelated or ambiguous modal contents', () => {
  const wrong = fixture(); wrong[0].tree.children[0].children[0].children[1].text = 'Delete developer data'
  assert.throws(() => locateConfirmation(wrong, { runId, project }))
  const duplicate = fixture(); const group = duplicate[0].tree.children[0].children[0]; group.children.push({ role: 'AXLink', text: '确定' })
  assert.throws(() => locateConfirmation(duplicate, { runId, project }), error => {
    assert.equal(error.code, 'OWNED_CONFIRMATION_NOT_UNIQUE')
    assert.equal(error.diagnostics.confirmationNodeMatchCount, 2)
    return true
  })
})
test('rejects invalid run ownership or a project path outside the run', () => {
  assert.throws(() => locateConfirmation(fixture(), { runId: 'native', project }))
  assert.throws(() => locateConfirmation(fixture(), { runId, project: '/private/tmp/developer' }))
})
test('does not select confirmation text elsewhere in the page', () => {
  const wrong = fixture(); wrong[0].tree.children[0].children[0].children.pop()
  wrong[0].tree.children[0].children.push({ role: 'AXLink', text: '确定', id: 'unrelated' })
  assert.throws(() => locateConfirmation(wrong, { runId, project }))
})
test('rejects a foreign top WebArea even when an owned nested WebArea matches', () => {
  const wrong = fixture(), original = wrong[0].tree.children[0]
  wrong[0].tree.children = [{ role: 'AXWebArea', url: 'file:///official/index.html?projectpath=%2Fforeign', children: [original] }]
  assert.throws(() => locateConfirmation(wrong, { runId, project }))
})
test('rejects a missing top project URL even when a nested WebArea matches', () => {
  const wrong = fixture(), original = wrong[0].tree.children[0]
  wrong[0].tree.children = [{ role: 'AXWebArea', children: [original] }]
  assert.throws(() => locateConfirmation(wrong, { runId, project }))
})
