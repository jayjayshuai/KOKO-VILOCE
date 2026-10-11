import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
const source = await readFile(new URL('../src/services/voice-room-entry.ts', import.meta.url), 'utf8')
const module = new vm.SourceTextModule(
  ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } })
    .outputText,
  { context: vm.createContext({}) },
)
await module.link(() => {
  throw new Error('Unexpected import')
})
await module.evaluate()
const { consumeVoiceRoomEntry, arriveInVoiceRoom, validVoiceRoomId } = module.namespace
test('房间ID严格保持64位字符串，一次导航意图消费后不在刷新/返回复活', () => {
  assert.equal(validVoiceRoomId('9223372036854775807'), true)
  for (const value of ['0', '01', '9223372036854775808', '../1', 1]) assert.equal(validVoiceRoomId(value), false)
  const history = {
    state: {
      back: '/voice',
      position: 2,
      kokoVoiceEntry: { roomId: '9', userId: '1', revision: 3, documentOrigin: 100, title: '真实名称' },
    },
    replaceState(value) {
      this.state = value
    },
  }
  assert.equal(consumeVoiceRoomEntry('9', '1', 3, history, 100).enter, true)
  assert.equal(history.state.position, 2)
  assert.equal(history.state.kokoVoiceEntry, null)
  assert.equal(consumeVoiceRoomEntry('9', '1', 3, history, 100), null)
})
test('换房/账号/登录轮次/文档不能消费旧点击，也不从网址自动授予入房', () => {
  for (const args of [
    ['10', '1', 3, 100],
    ['9', '2', 3, 100],
    ['9', '1', 4, 100],
    ['9', '1', 3, 101],
  ]) {
    const history = {
      state: { kokoVoiceEntry: { roomId: '9', userId: '1', revision: 3, documentOrigin: 100 } },
      replaceState(value) {
        this.state = value
      },
    }
    assert.equal(consumeVoiceRoomEntry(args[0], args[1], args[2], history, args[3]), null)
    assert.equal(history.state.kokoVoiceEntry, null)
  }
})
function flow() {
  const calls = [],
    state = { current: true, ready: true, joined: false, pending: false, connected: false }
  const actions = {
    load: async () => calls.push('load'),
    current: () => state.current,
    ready: () => state.ready,
    joined: () => state.joined,
    pending: () => state.pending,
    prepare: async () => {
      calls.push('prepare')
      return true
    },
    join: async () => {
      calls.push('join')
      state.joined = true
    },
    connect: async () => {
      calls.push('connect')
      state.connected = true
    },
    connected: () => state.connected,
  }
  return { calls, state, actions }
}
test('明确点击串行核验/入房/收听，已有会话不重复JOIN且不含设备调用', async () => {
  const f = flow()
  assert.equal(await arriveInVoiceRoom(f.actions), true)
  assert.deepEqual(f.calls, ['load', 'prepare', 'join', 'connect'])
  f.calls.length = 0
  assert.equal(await arriveInVoiceRoom(f.actions), true)
  assert.deepEqual(f.calls, ['load', 'connect'])
})
test('入房未知结果、换身份或读取失败不申请SDK连接，不自动新建重试请求', async () => {
  for (const variant of ['unknown', 'scope', 'read']) {
    const f = flow()
    if (variant === 'unknown')
      f.actions.join = async () => {
        f.calls.push('join')
        f.state.pending = true
      }
    if (variant === 'scope')
      f.actions.join = async () => {
        f.calls.push('join')
        f.state.current = false
      }
    if (variant === 'read')
      f.actions.load = async () => {
        f.calls.push('load')
        f.state.ready = false
      }
    assert.equal(await arriveInVoiceRoom(f.actions), false)
    assert.equal(f.calls.includes('connect'), false)
  }
})
