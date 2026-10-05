import assert from 'node:assert/strict'
import { readdir, readFile } from 'node:fs/promises'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { parse, compileTemplate } from 'vue/compiler-sfc'

// 原生 formatter 的 AST 校验不能代替 Vue 编译器对多语句事件表达式的校验。
async function sources(directory) {
  const result = []
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const file = new URL(entry.name, directory)
    if (entry.isDirectory()) result.push(...(await sources(new URL(`${entry.name}/`, directory))))
    else if (entry.isFile() && entry.name.endsWith('.vue')) result.push(file)
  }
  return result
}
for (const file of await sources(new URL('../src/', import.meta.url))) {
  test(`Vue 模板实际编译：${file.pathname.split('/src/')[1]}`, async () => {
    const filename = fileURLToPath(file)
    const { descriptor, errors } = parse(await readFile(file, 'utf8'), { filename })
    assert.deepEqual(errors, [], 'SFC 解析不能失败')
    if (descriptor.template) {
      const compiled = compileTemplate({ source: descriptor.template.content, filename, id: 'format-contract' })
      assert.deepEqual(compiled.errors, [], '模板事件/绑定必须通过真实 Vue 编译器')
    }
  })
}
