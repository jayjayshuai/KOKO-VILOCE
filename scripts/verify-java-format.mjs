import assert from 'node:assert/strict'
import { readdir, readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import * as prettier from 'prettier'
import javaPlugin from 'prettier-plugin-java'
import { javaSyntax } from './java-format-syntax.mjs'

// Java CST 包含重复 namedChildren；直接 JSON 序列化 debug-check 会指数膨胀。
// 只沿 children 比较语法与叶子字面量，忽略注释/位置；仅允许 import 重排及数组可选尾逗号。
// 不豁免括号、运算符、调用顺序、注解内容或字符串改写。
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const mode = process.argv[2]
assert.ok(['--write', '--check', '--debug'].includes(mode), 'Use --write, --check or --debug')
assert.equal(process.argv.length, 3, 'Unexpected arguments')

async function javaFiles(directory) {
  const files = []
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const child = path.join(directory, entry.name)
    if (entry.isDirectory()) files.push(...(await javaFiles(child)))
    else if (entry.isFile() && entry.name.endsWith('.java')) files.push(child)
    else if (entry.isSymbolicLink()) throw new Error(`Source symlink is outside format scope: ${child}`)
  }
  return files
}

const files = []
for (const module of await readdir(path.join(root, 'backend'), { withFileTypes: true })) {
  if (!module.isDirectory() || module.isSymbolicLink()) continue
  for (const kind of ['main', 'test']) {
    const directory = path.join(root, 'backend', module.name, 'src', kind, 'java')
    try {
      files.push(...(await javaFiles(directory)))
    } catch (error) {
      if (error.code !== 'ENOENT') throw error
    }
  }
}
files.sort()
assert.ok(files.length > 0, 'No source Java files found')
let changed = 0
for (const file of files) {
  const source = await readFile(file, 'utf8')
  const options = { ...(await prettier.resolveConfig(file)), filepath: file }
  const formatted = await prettier.format(source, options)
  assert.deepEqual(
    javaSyntax(await javaPlugin.parsers.java.parse(source, options)),
    javaSyntax(await javaPlugin.parsers.java.parse(formatted, options)),
    `Java syntax or literal changed: ${path.relative(root, file)}`,
  )
  assert.equal(await prettier.format(formatted, options), formatted, `Format not idempotent: ${file}`)
  if (source !== formatted) {
    changed++
    if (mode === '--write') await writeFile(file, formatted, 'utf8')
    console.log(`${mode === '--write' ? 'FORMATTED' : 'UNFORMATTED'} ${path.relative(root, file)}`)
  }
}
console.log(`Java syntax/literals and idempotence PASS: files=${files.length}, changed=${changed}, mode=${mode}`)
if (mode === '--check' && changed) process.exitCode = 1
