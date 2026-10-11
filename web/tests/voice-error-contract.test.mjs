import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'

// 执行真实请求基础层，验证业务拒绝识别所需字段；不证明服务器事务或网络质量。
async function http(fetch) {
  const source = await readFile(new URL('../src/services/http.ts', import.meta.url), 'utf8')
  const context = vm.createContext({ fetch, AbortController, Error, Headers, FormData, setTimeout, clearTimeout })
  const module = new vm.SourceTextModule(
    ts.transpileModule(source, {
      compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
    }).outputText,
    {
      context,
      initializeImportMeta(meta) {
        meta.env = { PROD: true }
      },
    },
  )
  await module.link(() => {
    throw new Error('Unexpected import')
  })
  await module.evaluate()
  return module.namespace
}

test('真实HTTP层保留本站409业务契约，非契约响应不能虚构明确拒绝', async () => {
  for (const [body, expected] of [
    [JSON.stringify({ code: 'CONFLICT', message: '麦位冲突' }), 'CONFLICT'],
    ['<html>proxy conflict</html>', null],
    [JSON.stringify({ code: 409 }), null],
  ]) {
    const module = await http(async () => new Response(body, { status: 409 }))
    await assert.rejects(
      module.request('/voice/rooms/1/interaction/commands', { method: 'POST' }),
      (error) => error instanceof module.ApiRequestError && error.status === 409 && error.code === expected,
    )
  }
})

test('真正超时没有业务拒绝码，不自动重试写请求', async () => {
  let count = 0
  const module = await http((_url, options) => {
    count++
    return new Promise((_resolve, reject) =>
      options.signal.addEventListener('abort', () => reject(new Error('aborted'))),
    )
  })
  await assert.rejects(
    module.request('/voice/rooms/1/interaction/commands', { method: 'POST', timeoutMs: 10 }),
    (error) => error.status === 0 && error.code === null,
  )
  assert.equal(count, 1)
})
