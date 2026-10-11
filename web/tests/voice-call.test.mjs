import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import * as vue from 'vue'
import * as pinia from 'pinia'
import { compileScript, parse } from '@vue/compiler-sfc'

// 执行真实Store/SFC，网络和设备仍为测试桩；缓存DOM/真实媒体另行浏览器验收。
async function moduleFor(source, imports) {
  const context = vm.createContext({ console })
  const module = new vm.SourceTextModule(
    ts.transpileModule(source, {
      compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
    }).outputText,
    { context },
  )
  await module.link((name) => {
    const values = imports[name]
    assert.ok(values, 'Unexpected import ' + name)
    return new vm.SyntheticModule(
      Object.keys(values),
      function () {
        for (const [key, value] of Object.entries(values)) this.setExport(key, value)
      },
      { context },
    )
  })
  await module.evaluate()
  return module.namespace
}
async function store() {
  pinia.setActivePinia(pinia.createPinia())
  const module = await moduleFor(await readFile(new URL('../src/stores/voice-call.ts', import.meta.url), 'utf8'), {
    vue,
    pinia,
  })
  return module.useVoiceCallStore()
}
const status = {
  phase: 'connected',
  microphoneEnabled: true,
  microphoneBusy: false,
  canToggleMicrophone: true,
  participantCount: 3,
}
const controls = { async connect() {}, async leave() {}, async disconnect() {}, async toggleMicrophone() {} }

test('常驻投影仅归属当前注册；旧缓存清理/迟到状态不能覆盖新房间且无SDK/凭据', async () => {
  const s = await store()
  const old = s.attach({ roomId: '9', userId: '1', sessionRevision: 1, title: '房间A' }, controls)
  const next = s.attach({ roomId: '10', userId: '1', sessionRevision: 1, title: '房间B' }, controls)
  s.update(old, status)
  s.clear(old)
  assert.equal(s.call.registration, next)
  assert.equal(s.call.status.phase, 'idle')
  s.update(next, { ...status, accidentalInternalField: 'synthetic-redacted' })
  assert.equal(s.call.status.phase, 'connected')
  assert.equal(s.call.status.accidentalInternalField, undefined)
  s.clear(next)
  assert.equal(s.call, null)
})

async function view() {
  const calls = [],
    hooks = [],
    s = await store()
  const auth = vue.reactive({ user: { id: '1' }, sessionRevision: 1 })
  const route = vue.reactive({ name: 'voice-room', params: { roomId: '9' } })
  let intent = true
  const source = compileScript(
    parse(await readFile(new URL('../src/views/VoiceRoomView.vue', import.meta.url), 'utf8')).descriptor,
    { id: 'voice-call-view-test' },
  ).content
  const module = await moduleFor(source, {
    vue: { ...vue, onBeforeUnmount: (fn) => hooks.push(fn) },
    'vue-router': { RouterLink: {}, useRoute: () => route, useRouter: () => ({ push: () => Promise.resolve() }) },
    '../stores/auth': { useAuthStore: () => auth },
    '../stores/voice-call': { useVoiceCallStore: () => s },
    '../components/VoiceInteractionPanel.vue': { default: {} },
    '../services/voice-room-entry': {
      validVoiceRoomId: (id) => /^[1-9][0-9]*$/.test(id),
      consumeVoiceRoomEntry: () => {
        const value = intent ? { enter: true, title: '房间A' } : null
        intent = false
        return value
      },
    },
  })
  const scope = vue.effectScope()
  const state = scope.run(() => module.default.setup({}, { expose() {} }))
  state.panel.value = Object.fromEntries(
    ['connect', 'disconnect', 'leave', 'toggleMicrophone'].map((name) => [name, async () => calls.push(name)]),
  )
  return {
    state,
    s,
    auth,
    route,
    calls,
    dispose() {
      hooks.forEach((fn) => fn())
      scope.stop()
    },
  }
}

test('缓存房间切页/返回保留同一注册与房间，入口不重放且不触发设备或连接', async () => {
  const h = await view()
  try {
    const registration = h.s.call.registration
    h.state.mediaState({ ...status, roomId: '9', userId: '1', sessionRevision: 1 })
    h.route.name = 'messages'
    h.route.params = {}
    assert.equal(h.state.roomId.value, '9')
    assert.equal(h.s.call.registration, registration)
    await h.s.call.controls.toggleMicrophone()
    assert.deepEqual(h.calls, ['toggleMicrophone'])
    h.route.params = { roomId: '9' }
    h.route.name = 'voice-room'
    assert.equal(h.s.call.registration, registration)
    assert.deepEqual(h.calls, ['toggleMicrophone'])
    h.route.params = { roomId: '10' }
    const next = h.s.call.registration
    h.state.mediaState({ ...status, roomId: '9', userId: '1', sessionRevision: 1 })
    assert.equal(h.s.call.registration, next)
    assert.equal(h.s.call.status.phase, 'idle')
  } finally {
    h.dispose()
  }
})

test('缓存房间后台换登录轮次清原意图与通话，不自动加入旧房；确认离房清入口', async () => {
  const h = await view()
  try {
    const old = h.s.call.controls
    h.route.name = 'messages'
    h.route.params = {}
    h.auth.sessionRevision++
    assert.equal(h.s.call, null)
    assert.equal(h.state.roomId.value, '')
    assert.equal(h.state.entry.value, null)
    await old.connect()
    assert.deepEqual(h.calls, [])
    h.route.params = { roomId: '9' }
    h.route.name = 'voice-room'
    assert.equal(h.state.entry.value, null)
    assert.deepEqual(h.calls, [])
    h.state.left()
    assert.equal(h.s.call, null)
    assert.equal(h.state.roomId.value, '')
  } finally {
    h.dispose()
  }
})
