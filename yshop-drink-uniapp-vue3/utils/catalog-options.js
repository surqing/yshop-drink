// Display preview only. The server validates IDs, version, availability and prices again.
export const cents = value => Math.round(Number(value) * 100)
export const selectionKey = selections => JSON.stringify([...selections].sort((a, b) =>
  a.groupId.localeCompare(b.groupId) || a.optionId.localeCompare(b.optionId)))
export function activeGroups(configuration, selections) {
  return (configuration?.groups || []).filter(g => g.enabled && (!g.when || selections.some(s =>
    s.groupId === g.when.groupId && s.optionId === g.when.optionId && s.quantity > 0)))
}
export function defaults(configuration) {
  const selected = []
  for (const g of configuration?.groups || []) {
    if (!g.enabled || (g.when && !selected.some(s => s.groupId === g.when.groupId && s.optionId === g.when.optionId))) continue
    for (const o of g.options || []) if (o.enabled && o.defaultQuantity > 0)
      selected.push({ groupId: g.id, optionId: o.id, quantity: o.defaultQuantity })
  }
  return selected
}
export function pruneSelections(configuration, selections) {
  const result = []
  for (const g of configuration?.groups || []) {
    if (!g.enabled || (g.when && !result.some(s => s.groupId === g.when.groupId && s.optionId === g.when.optionId))) continue
    result.push(...selections.filter(s => s.groupId === g.id && g.options.some(o => o.id === s.optionId && o.enabled)))
  }
  return result
}
export function preview(configuration, selections, base) {
  if (!Array.isArray(selections)) throw new Error('请重新选择规格')
  const normalized = pruneSelections(configuration, selections)
  if (normalized.length !== selections.length || new Set(selections.map(s => s.groupId + ':' + s.optionId)).size !== selections.length)
    throw new Error('存在已失效的选项，请重新选择')
  let extra = 0
  const labels = []
  for (const g of activeGroups(configuration, selections)) {
    const selected = selections.filter(s => s.groupId === g.id)
    const total = selected.reduce((sum, s) => sum + s.quantity, 0)
    if (total < g.min || total > g.max || (!g.multiple && selected.length > 1)) throw new Error(`请检查${g.name}的选择数量`)
    for (const s of selected) {
      const o = g.options.find(o => o.id === s.optionId && o.enabled)
      if (!o || !Number.isInteger(s.quantity) || s.quantity < 1 || s.quantity > g.maxPerOption) throw new Error('选项不可用')
      extra += cents(o.surcharge) * s.quantity
      labels.push(`${g.name}：${o.name}${s.quantity > 1 ? '×' + s.quantity : ''}`)
    }
  }
  return { price: (cents(base) + extra) / 100, extra: extra / 100, label: labels.join('，') }
}
