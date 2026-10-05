// CST 只沿 children 遍历，避免重复 namedChildren 导致指数膨胀。
// 仅忽略位置、注释、import 顺序和数组可选尾逗号，不豁免可执行内容。
export function javaSyntax(node) {
  if (node.type === 'line_comment' || node.type === 'block_comment') return null
  const children = node.children?.map(javaSyntax).filter((value) => value !== null)
  if (node.type === 'program' && children) {
    const imports = children.filter((value) => value.type === 'import_declaration')
    imports.sort((a, b) => JSON.stringify(a).localeCompare(JSON.stringify(b), 'en'))
    return { type: node.type, imports, children: children.filter((value) => value.type !== 'import_declaration') }
  }
  if (['array_initializer', 'element_value_array_initializer'].includes(node.type) && children?.at(-2)?.type === ',') {
    children.splice(-2, 1)
  }
  return children?.length ? { type: node.type, children } : { type: node.type, value: node.value }
}
