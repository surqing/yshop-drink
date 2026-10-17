import test from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
const { locateConfirmation } = createRequire(import.meta.url)('./mini/native-confirmation.cjs')
const runId = '0123456789abcdef0123456789abcdef', project = `/private/tmp/${runId}/mini/project/unpackage/dist/dev/mp-weixin`
function fixture() { return [{ name: `E2E-${runId} - 微信开发者工具`, tree: { role: 'AXWindow', children: [
  { role: 'AXWebArea', url: `file:///official/index.html?projectpath=${encodeURIComponent(project)}`, children: [
    { role: 'AXGroup', children: [{ role: 'AXStaticText', text: '取消订单' }, { role: 'AXStaticText', text: '取消后会恢复库存和预占优惠券' },
      { role: 'AXLink', text: '取消', id: 'cancel' }, { role: 'AXLink', text: '确定', id: 'owned-confirm' }] }
  ] }] } }] }
test('selects only the exact owned real dialog confirmation link', () => assert.equal(locateConfirmation(fixture(), { runId, project }).id, 'owned-confirm'))
test('rejects foreign or duplicate named windows', () => {
  const wrong = fixture(); wrong[0].name = 'Developer project'; assert.throws(() => locateConfirmation(wrong, { runId, project }))
  assert.throws(() => locateConfirmation([...fixture(), ...fixture()], { runId, project }))
})
test('rejects a same-named window with foreign project URL', () => {
  const wrong = fixture(); wrong[0].tree.children[0].url = 'file:///official/index.html?projectpath=%2Fdeveloper-project'
  assert.throws(() => locateConfirmation(wrong, { runId, project }))
})
test('rejects unrelated or ambiguous modal contents', () => {
  const wrong = fixture(); wrong[0].tree.children[0].children[0].children[1].text = 'Delete developer data'
  assert.throws(() => locateConfirmation(wrong, { runId, project }))
  const duplicate = fixture(); const group = duplicate[0].tree.children[0].children[0]; group.children.push({ role: 'AXLink', text: '确定' })
  assert.throws(() => locateConfirmation(duplicate, { runId, project }))
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
