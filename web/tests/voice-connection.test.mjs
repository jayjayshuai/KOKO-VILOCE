import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import * as vue from 'vue'

// 执行实际生产composable和Vue响应性；SDK/网络桩不证明真实RTC或浏览器设备权限。
class ApiRequestError extends Error {
  constructor(message, status) {
    super(message)
    this.status = status
  }
}
const target = (id = '1') => ({
  id,
  title: `房间${id}`,
  slug: `room-${id}`,
  owner: '房主',
  maxParticipants: 10,
  status: 'OPEN',
})
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
async function until(predicate) {
  for (let i = 0; i < 30 && !predicate(); i++) await new Promise((resolve) => setImmediate(resolve))
  assert.equal(predicate(), true)
}

async function setup(network, options = {}) {
  const hooks = [],
    rooms = [],
    calls = []
  const session = vue.reactive({
    user: { id: '42' },
    sessionRevision: 1,
    expireSession(id, revision) {
      if (this.user?.id === id && this.sessionRevision === revision) {
        this.user = null
        this.sessionRevision++
      }
    },
  })
  const root = vue.shallowRef(null)
  const events = Object.fromEntries(
    [
      'TrackSubscribed',
      'TrackUnsubscribed',
      'ParticipantConnected',
      'ParticipantDisconnected',
      'LocalTrackPublished',
      'LocalTrackUnpublished',
      'Reconnecting',
      'Reconnected',
      'Disconnected',
    ].map((name) => [name, name]),
  )
  class Room {
    constructor() {
      this.handlers = new Map()
      this.remoteParticipants = new Map()
      this.disconnects = 0
      this.microphoneCalls = []
      this.localParticipant = {
        isMicrophoneEnabled: false,
        setMicrophoneEnabled: async (value) => {
          this.microphoneCalls.push(value)
          await options.microphone?.(value, this)
          this.localParticipant.isMicrophoneEnabled = value
        },
      }
      rooms.push(this)
    }
    on(event, handler) {
      this.handlers.set(event, handler)
      return this
    }
    removeAllListeners() {
      this.handlers.clear()
    }
    emit(event, ...args) {
      this.handlers.get(event)?.(...args)
    }
    async connect(url, token) {
      this.url = url
      calls.push(['connect', token])
      await options.connect?.(this)
    }
    async disconnect() {
      this.disconnects++
      this.localParticipant.isMicrophoneEnabled = false
      await options.disconnect?.(this)
    }
  }
  const source = await readFile(new URL('../src/composables/voice-connection.ts', import.meta.url), 'utf8')
  const code = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  const context = vm.createContext({ AbortController, Error, Set, URL, location: options.location })
  const module = new vm.SourceTextModule(code, { context })
  await module.link((specifier) => {
    const values = specifier === 'vue' ? { ...vue, onBeforeUnmount: (hook) => hooks.push(hook) } : { ApiRequestError }
    return new vm.SyntheticModule(
      Object.keys(values),
      function () {
        for (const [key, value] of Object.entries(values)) this.setExport(key, value)
      },
      { context },
    )
  })
  await module.evaluate()
  const api = async (id, signal) => {
    calls.push(['credential', id, signal])
    return network ? network(id, signal) : { url: 'ws://127.0.0.1:7880', token: 'synthetic-token', roomName: 'test' }
  }
  const scope = vue.effectScope()
  const state = scope.run(() =>
    module.namespace.useVoiceConnection(session, api, root, async () => ({
      Room,
      RoomEvent: events,
      Track: { Kind: { Audio: 'audio' } },
    })),
  )
  return {
    state,
    session,
    rooms,
    calls,
    root,
    hooks,
    scope,
    dispose: async () => {
      hooks.forEach((hook) => hook())
      scope.stop()
      await vue.nextTick()
    },
  }
}

test('加入成功默认不开麦，设备失败不盲翻转，明确点击才开麦', async () => {
  let deny = true
  const s = await setup(null, {
    microphone: (value) => {
      if (value && deny) throw new Error('麦克风权限拒绝')
    },
  })
  await s.state.joinVoiceRoom(target())
  assert.equal(s.state.connectedVoice.value.id, '1')
  assert.equal(s.state.phase.value, 'connected')
  assert.deepEqual(s.rooms[0].microphoneCalls, [])
  assert.equal(s.state.microphoneEnabled.value, false)
  await s.state.toggleMicrophone()
  assert.equal(s.state.microphoneEnabled.value, false)
  assert.match(s.state.voiceError.value, /权限拒绝/)
  deny = false
  await s.state.toggleMicrophone()
  assert.equal(s.state.microphoneEnabled.value, true)
  await s.state.toggleMicrophone()
  assert.equal(s.state.microphoneEnabled.value, false)
  await s.dispose()
})
test('取消与卸载撤销请求，晚返回凭据不会创建媒体连接', async () => {
  for (const action of ['cancel', 'unmount']) {
    const pending = deferred(),
      s = await setup(() => pending.promise),
      joining = s.state.joinVoiceRoom(target())
    await until(() => s.calls.length === 1)
    if (action === 'cancel') await s.state.leaveVoiceRoom()
    else await s.dispose()
    assert.equal(s.calls[0][2].aborted, true)
    pending.resolve({ url: 'ws://127.0.0.1:7880', token: 'old', roomName: 'test' })
    await joining
    assert.equal(s.rooms.length, 0)
    assert.equal(s.state.connectedVoice.value, null)
    if (action === 'cancel') await s.dispose()
  }
})
test('同账号会话轮次改变或切换账号都隔离旧凭据响应', async () => {
  for (const change of ['revision', 'account']) {
    const pending = deferred(),
      s = await setup(() => pending.promise),
      joining = s.state.joinVoiceRoom(target())
    await until(() => s.calls.length === 1)
    if (change === 'revision') s.session.sessionRevision++
    else s.session.user = { id: '43' }
    pending.resolve({ url: 'ws://127.0.0.1:7880', token: 'old', roomName: 'test' })
    await joining
    assert.equal(s.rooms.length, 0)
    assert.equal(s.state.voiceJoining.value, null)
    await s.dispose()
  }
})
test('连接中取消后SDK晚成功仍断开，旧事件不能清除新房间', async () => {
  const pending = deferred(),
    s = await setup(null, {
      connect: (room) => {
        if (s.rooms.length === 1) return pending.promise
      },
    })
  const first = s.state.joinVoiceRoom(target('1'))
  await until(() => s.rooms.length === 1)
  const old = s.rooms[0],
    oldDisconnected = old.handlers.get('Disconnected')
  await s.state.leaveVoiceRoom()
  await s.state.joinVoiceRoom(target('2'))
  pending.resolve()
  await first
  oldDisconnected()
  assert.equal(s.state.connectedVoice.value.id, '2')
  assert.equal(s.state.participantCount.value, 1)
  assert.ok(old.disconnects >= 1)
  assert.equal(old.handlers.size, 0)
  await s.dispose()
})
test('较早加入等待旧断开时不能覆盖随后开始的新加入', async () => {
  const stop = deferred()
  let delay = false
  const s = await setup(null, {
    disconnect: () => {
      if (delay) return stop.promise
    },
  })
  await s.state.joinVoiceRoom(target('1'))
  delay = true
  const first = s.state.joinVoiceRoom(target('2'))
  await until(() => s.rooms[0].disconnects === 1)
  await s.state.joinVoiceRoom(target('3'))
  stop.resolve()
  await first
  assert.equal(s.state.connectedVoice.value.id, '3')
  assert.deepEqual(
    s.calls.filter((call) => call[0] === 'credential').map((call) => call[1]),
    ['1', '3'],
  )
  await s.dispose()
})
test('设备授权迟到完成只关闭旧轨道，不改变新房间', async () => {
  const permission = deferred()
  const s = await setup(null, {
    microphone: (value, room) => {
      if (value && room === s.rooms[0]) return permission.promise
    },
  })
  await s.state.joinVoiceRoom(target('1'))
  const enabling = s.state.toggleMicrophone()
  assert.equal(s.state.microphoneBusy.value, true)
  await s.state.toggleMicrophone()
  assert.deepEqual(s.rooms[0].microphoneCalls, [true])
  await s.state.joinVoiceRoom(target('2'))
  permission.resolve()
  await enabling
  assert.equal(s.state.connectedVoice.value.id, '2')
  assert.equal(s.state.microphoneEnabled.value, false)
  assert.deepEqual(s.rooms[0].microphoneCalls, [true, false])
  assert.deepEqual(s.rooms[1].microphoneCalls, [])
  await s.dispose()
})
test('SDK恢复与断开如实反馈，提前到达音频挂载后可听且离开移除', async () => {
  const s = await setup(),
    children = new Set()
  const root = {
    appendChild(element) {
      children.add(element)
      element.parentElement = root
    },
  }
  const element = {
    parentElement: null,
    remove() {
      children.delete(element)
      this.parentElement = null
    },
  }
  await s.state.joinVoiceRoom(target())
  const room = s.rooms[0]
  room.emit('TrackSubscribed', { kind: 'audio', attach: () => element })
  assert.equal(children.size, 0)
  s.root.value = root
  await vue.nextTick()
  assert.equal(children.size, 1)
  room.emit('Reconnecting')
  assert.equal(s.state.phase.value, 'reconnecting')
  await s.state.toggleMicrophone()
  assert.deepEqual(room.microphoneCalls, [])
  room.emit('Reconnected')
  assert.equal(s.state.phase.value, 'connected')
  room.emit('Disconnected')
  await vue.nextTick()
  assert.equal(s.state.connectedVoice.value, null)
  assert.equal(children.size, 0)
  assert.equal(s.state.participantCount.value, 0)
  assert.match(s.state.voiceError.value, /断开/)
  await s.dispose()
})
test('入会401只撤销当前本人会话，当前故障不伪装成功', async () => {
  const s = await setup(() => {
    throw new ApiRequestError('已失效', 401)
  })
  await s.state.joinVoiceRoom(target())
  assert.equal(s.session.user, null)
  assert.equal(s.rooms.length, 0)
  await s.dispose()
  const failed = await setup(() => {
    throw new Error('实际依赖不可用')
  })
  await failed.state.joinVoiceRoom(target())
  assert.match(failed.state.voiceError.value, /不可用/)
  assert.equal(failed.state.phase.value, 'idle')
  assert.equal(failed.state.connectedVoice.value, null)
  await failed.dispose()
})

test('公网HTTP不索取凭据，HTTPS拒绝明文媒体地址， malformed凭据不创建SDK', async () => {
  const insecure = await setup(null, { location: { protocol: 'http:', hostname: '198.51.100.10' } })
  await insecure.state.joinVoiceRoom(target())
  assert.equal(insecure.calls.length, 0)
  assert.match(insecure.state.voiceError.value, /HTTPS/)
  await insecure.dispose()
  const mixed = await setup(null, { location: { protocol: 'https:', hostname: 'voice.example.invalid' } })
  await mixed.state.joinVoiceRoom(target())
  assert.equal(mixed.rooms.length, 0)
  assert.match(mixed.state.voiceError.value, /无效/)
  await mixed.dispose()
  for (const invalid of [
    { url: 'https://example.invalid', token: 'fixture', roomName: 'test' },
    { url: 'ws://user:secret@127.0.0.1:7880', token: 'fixture', roomName: 'test' },
    { url: 'ws://127.0.0.1:7880?secret=fixture', token: 'fixture', roomName: 'test' },
    { url: 'ws://127.0.0.1:7880', token: '', roomName: 'test' },
  ]) {
    const s = await setup(() => invalid)
    await s.state.joinVoiceRoom(target())
    assert.equal(s.rooms.length, 0)
    assert.match(s.state.voiceError.value, /无效/)
    await s.dispose()
  }
})

test('受控房间不能复用原发布JWT或实例化媒体SDK', async () => {
  const s = await setup(() => {
    throw new Error('禁止签发')
  })
  await s.state.joinVoiceRoom({ ...target(), controlled: true })
  assert.equal(s.calls.length, 0)
  assert.equal(s.rooms.length, 0)
  assert.match(s.state.voiceError.value, /媒体授权尚未开放/)
  await s.dispose()
})

test('Java媒体准入代理保留SDK基址且默认不开麦，跨源配置在SDK前拒绝', async () => {
  const location = { protocol: 'https:', hostname: 'app.example.invalid', href: 'https://app.example.invalid/koko/' }
  const good = await setup(
    () =>
      Promise.resolve({
        url: 'wss://app.example.invalid/koko-api/media/livekit',
        token: 'synthetic-token',
        roomName: 'test',
      }),
    { location },
  )
  await good.state.joinVoiceRoom(target())
  assert.equal(good.rooms[0].url, 'wss://app.example.invalid/koko-api/media/livekit')
  assert.deepEqual(good.rooms[0].microphoneCalls, [])
  await good.dispose()
  const bad = await setup(
    () =>
      Promise.resolve({
        url: 'wss://other.example.invalid/api/media/livekit',
        token: 'synthetic-token',
        roomName: 'test',
      }),
    { location },
  )
  await bad.state.joinVoiceRoom(target())
  assert.equal(bad.rooms.length, 0)
  assert.match(bad.state.voiceError.value, /必须.*同源/)
  await bad.dispose()
})
