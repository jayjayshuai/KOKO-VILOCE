import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'

// 真实服务适配代码，request为显式桩；不声称实际HTTP/数据库验证。
const source = await readFile(new URL('../src/services/voice-interaction.ts', import.meta.url), 'utf8')
async function setup() {
  const calls = [],
    context = vm.createContext({ URLSearchParams, encodeURIComponent })
  const module = new vm.SourceTextModule(
    ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } })
      .outputText,
    { context },
  )
  await module.link((name) => {
    const values =
      name === './http'
        ? {
            request: (path, options) => {
              calls.push({ path, options })
              return Promise.resolve({})
            },
          }
        : { messageUuid: () => 'synthetic-uuid' }
    return new vm.SyntheticModule(
      Object.keys(values),
      function () {
        for (const [key, value] of Object.entries(values)) this.setExport(key, value)
      },
      { context },
    )
  })
  await module.evaluate()
  return { calls, api: module.namespace.voiceInteractionApi }
}
test('同步版本保留高精度字符串，首读无游标，核对收据只发GET不携带成员会话或写入体', async () => {
  const { calls, api } = await setup(),
    signal = new AbortController().signal
  await api.sync('9223372036854775807', '9007199254741001', signal)
  await api.sync('1', null, signal)
  await api.receipt('1', '00000000-0000-0000-0000-000000000042', signal)
  assert.equal(calls[0].path, '/voice/rooms/9223372036854775807/interaction/sync?knownVersion=9007199254741001')
  assert.equal(calls[1].path, '/voice/rooms/1/interaction/sync?')
  assert.equal(calls[2].path, '/voice/rooms/1/interaction/receipts/00000000-0000-0000-0000-000000000042')
  for (const call of calls) {
    assert.equal(call.options.method, undefined)
    assert.equal(call.options.body, undefined)
    assert.equal(call.options.signal, signal)
  }
})
