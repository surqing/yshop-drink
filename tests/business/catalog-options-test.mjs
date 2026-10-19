import test from 'node:test'
import assert from 'node:assert/strict'
const file = new URL('../../yshop-drink-uniapp-vue3/utils/catalog-options.js', import.meta.url)
const { preview, defaults, pruneSelections, selectionKey, activeGroups } = await import(file)
const option = (id, surcharge = 0, defaultQuantity = 0) => ({ id, name: id, surcharge, defaultQuantity, enabled: true })
const group = (id, options, extra = {}) => ({ id, name: id, enabled: true, multiple: false, min: 1, max: 1, maxPerOption: 1, options, ...extra })
const configuration = { groups: [group('temperature', [option('hot', 0, 1), option('cold')]), group('ice', [option('normal', 0, 1)], { when: { groupId: 'temperature', optionId: 'cold' } }), group('topping', [option('pearl', 2), option('cream', 3)], { multiple: true, min: 0, max: 3, maxPerOption: 2 })] }
test('default hot does not select conditional ice', () => assert.deepEqual(defaults(configuration), [{ groupId: 'temperature', optionId: 'hot', quantity: 1 }]))
test('integer cents calculate toppings and quantity exactly', () => { const selected = [...defaults(configuration), { groupId: 'topping', optionId: 'pearl', quantity: 2 }, { groupId: 'topping', optionId: 'cream', quantity: 1 }]; const quote = preview(configuration, selected, 15.01); assert.equal(quote.price, 22.01); assert.equal(Math.round(quote.price * 100) * 3, 6603); assert.match(quote.label, /pearl×2/) })
test('hot hides ice and prunes previous cold selection', () => { const selected = [...defaults(configuration), { groupId: 'ice', optionId: 'normal', quantity: 1 }]; assert.equal(activeGroups(configuration, selected).length, 2); assert.deepEqual(pruneSelections(configuration, selected), defaults(configuration)); assert.throws(() => preview(configuration, selected, 15)) })
test('cold requires ice', () => assert.throws(() => preview(configuration, [{ groupId: 'temperature', optionId: 'cold', quantity: 1 }], 15)))
test('cold and ice are legal', () => assert.equal(preview(configuration, [{ groupId: 'temperature', optionId: 'cold', quantity: 1 }, { groupId: 'ice', optionId: 'normal', quantity: 1 }], 15).price, 15))
test('single choice cannot select hot and cold', () => assert.throws(() => preview(configuration, [...defaults(configuration), { groupId: 'temperature', optionId: 'cold', quantity: 1 }], 15)))
test('unknown/disabled group and option rejected', () => { for (const selection of [{ groupId: 'other', optionId: 'pearl', quantity: 1 }, { groupId: 'topping', optionId: 'other', quantity: 1 }]) assert.throws(() => preview(configuration, [...defaults(configuration), selection], 15)) })
test('repeat quantity bounded and integral', () => { for (const quantity of [-1, 0, 1.5, 3]) assert.throws(() => preview(configuration, [...defaults(configuration), { groupId: 'topping', optionId: 'pearl', quantity }], 15)) })
test('duplicate option not counted twice', () => { const s = { groupId: 'topping', optionId: 'pearl', quantity: 1 }; assert.throws(() => preview(configuration, [...defaults(configuration), s, s], 15)) })
test('cart identity canonicalizes option order', () => { const s = [...defaults(configuration), { groupId: 'topping', optionId: 'cream', quantity: 1 }]; assert.equal(selectionKey(s), selectionKey([...s].reverse())); assert.notEqual(selectionKey(s), selectionKey(defaults(configuration))) })
test('legacy no custom groups stays compatible', () => assert.equal(preview(undefined, [], 1.23).price, 1.23))
