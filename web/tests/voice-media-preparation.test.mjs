import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
const source = await readFile(new URL('../src/services/voice-media-preparation.ts', import.meta.url), 'utf8')
async function setup(get) {
  const context = vm.createContext({ Error, DOMException, setTimeout, clearTimeout })
  const module = new vm.SourceTextModule(
    ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } })
      .outputText,
    { context },
  )
  await module.link((name) => {
    assert.equal(name, './voice-media-plan')
    return new vm.SyntheticModule(
      ['voiceMediaPlanApi'],
      function () {
        this.setExport('voiceMediaPlanApi', { get })
      },
      { context },
    )
  })
  await module.evaluate()
  return module.namespace.waitForVoiceMediaRetirement
}
test('媒体准备只做有界事实读取，队列清空才返回且不签发凭据或打开设备', async () => {
  let calls = 0
  const wait = await setup(async (room, signal) => {
    assert.equal(room, '9')
    assert.equal(signal.aborted, false)
    return { tracked: true, active: true, pendingRetirements: ++calls === 1 ? 1 : 0, deadRetirements: 0 }
  })
  await wait('9', new AbortController().signal, { rounds: 3, intervalMs: 0 })
  assert.equal(calls, 2)
})
test('清退预算耗尽或读失败不自动重试，超时不声称媒体已准备', async () => {
  let calls = 0
  const wait = await setup(async () => {
    calls++
    return { tracked: true, active: true, pendingRetirements: 1, deadRetirements: 0 }
  })
  await assert.rejects(wait('9', undefined, { rounds: 3, intervalMs: 0 }), /仍在准备/)
  assert.equal(calls, 3)
  const dead = await setup(async () => ({ tracked: true, active: true, pendingRetirements: 0, deadRetirements: 1 }))
  await assert.rejects(dead('9'), /清退尚未完成/)
  calls = 0
  const failed = await setup(async () => {
    calls++
    throw new Error('事实读取失败')
  })
  await assert.rejects(failed('9'), /事实读取失败/)
  assert.equal(calls, 1)
})
test('取消在途准备立即隔离晚到回复，不等待剩余轮次', async () => {
  let resolve
  const late = new Promise((yes) => {
    resolve = yes
  })
  const wait = await setup(() => late),
    controller = new AbortController()
  const result = wait('9', controller.signal)
  controller.abort()
  resolve({ tracked: true, active: true, pendingRetirements: 0, deadRetirements: 0 })
  await assert.rejects(result, { name: 'AbortError' })
})
