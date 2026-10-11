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
const { consumeVoiceRoomEntry, arriveInVoiceRoom, validVoiceRoomId, runVoiceSpeakingFlow } = module.namespace
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
test('明确发言模式只消费一次，不接受非法模式或刷新文档的旧设备意图', () => {
  const history = {
    state: { kokoVoiceEntry: { roomId: '9', userId: '1', revision: 1, documentOrigin: 100, mode: 'speak' } },
    replaceState(value) {
      this.state = value
    },
  }
  assert.equal(consumeVoiceRoomEntry('9', '1', 1, history, 100).mode, 'speak')
  assert.equal(consumeVoiceRoomEntry('9', '1', 1, history, 100), null)
  history.state.kokoVoiceEntry = { roomId: '9', userId: '1', revision: 1, documentOrigin: 100, mode: 'invalid' }
  assert.equal(consumeVoiceRoomEntry('9', '1', 1, history, 100), null)
  history.state.kokoVoiceEntry = { roomId: '9', userId: '1', revision: 1, documentOrigin: 100, mode: 'speak' }
  assert.equal(consumeVoiceRoomEntry('9', '1', 1, history, 101), null)
})

function speakingFixture(owner = true) {
  const calls = [],
    state = { current: true, ready: true, seat: false, request: false, owner, muted: true, connected: false, mic: true }
  const actions = {
    current: () => state.current,
    ready: () => state.ready,
    hasSeat: () => state.seat,
    hasRequest: () => state.request,
    owner: () => state.owner,
    muted: () => state.muted,
    connected: () => state.connected,
    join: async () => {
      calls.push('join')
      return true
    },
    takeSeat: async () => {
      calls.push('seat')
      if (state.owner) state.seat = true
      else state.request = true
    },
    permit: async () => {
      calls.push('permit')
      state.muted = false
    },
    connect: async () => {
      calls.push('connect')
      state.connected = true
    },
    consumeConsent: () => calls.push('consume'),
    enableMicrophone: async () => {
      calls.push('device')
      return state.mic
    },
  }
  return { calls, state, actions }
}

test('房主一键入房上麦许可连接设备串行，无中间听众连接；普通成员等待房主而非自批', async () => {
  const owner = speakingFixture()
  assert.equal(await runVoiceSpeakingFlow(owner.actions), 'speaking')
  assert.deepEqual(owner.calls, ['join', 'seat', 'permit', 'consume', 'connect', 'device'])
  const member = speakingFixture(false)
  assert.equal(await runVoiceSpeakingFlow(member.actions), 'waiting')
  assert.deepEqual(member.calls, ['join', 'seat', 'connect'])
  member.calls.length = 0
  member.state.seat = true
  member.state.muted = false
  assert.equal(await runVoiceSpeakingFlow(member.actions), 'speaking')
  assert.deepEqual(member.calls, ['join', 'consume', 'connect', 'device'])
})

test('未知提交、取消、连接失败、失权及设备拒绝不会循环重试或越权开麦', async () => {
  for (const stage of ['join', 'seat', 'permit', 'connect']) {
    const h = speakingFixture(),
      key = stage === 'seat' ? 'takeSeat' : stage
    const original = h.actions[key]
    h.actions[key] = async () => {
      const result = await original()
      h.state.current = false
      return result
    }
    assert.equal(await runVoiceSpeakingFlow(h.actions), 'canceled')
    assert.equal(h.calls.includes('device'), false)
  }
  const unknown = speakingFixture()
  unknown.actions.takeSeat = async () => {
    unknown.calls.push('unknown-write')
    unknown.state.ready = false
  }
  assert.equal(await runVoiceSpeakingFlow(unknown.actions), 'failed')
  assert.equal(unknown.calls.includes('connect'), false)
  const broken = speakingFixture()
  broken.actions.connect = async () => broken.calls.push('connect-failed')
  assert.equal(await runVoiceSpeakingFlow(broken.actions), 'failed')
  assert.equal(broken.calls.filter((name) => name === 'consume').length, 1)
  assert.equal(broken.calls.includes('device'), false)
  const revoked = speakingFixture()
  revoked.actions.connect = async () => {
    revoked.state.connected = true
    revoked.state.muted = true
  }
  assert.equal(await runVoiceSpeakingFlow(revoked.actions), 'failed')
  assert.equal(revoked.calls.includes('device'), false)
  const denied = speakingFixture()
  denied.state.mic = false
  assert.equal(await runVoiceSpeakingFlow(denied.actions), 'failed')
  assert.equal(denied.calls.filter((name) => name === 'device').length, 1)
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
