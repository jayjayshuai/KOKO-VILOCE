import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import * as vue from 'vue'

// 执行真实父面板和真实编排函数；设备/HTTP 为明确桩，另有实际 RTC 页面验收。
async function fixture(role = 'OWNER') {
  const props = vue.reactive({ roomId: '9', userId: '1', sessionRevision: 1, presentation: 'room', active: true })
  const calls = [],
    hooks = [],
    mounted = [],
    listeners = new Map()
  const document = {
    visibilityState: 'visible',
    addEventListener(name, callback) {
      listeners.set(name, callback)
    },
    removeEventListener(name, callback) {
      if (listeners.get(name) === callback) listeners.delete(name)
    },
  }
  const state = Object.fromEntries(
    [
      'actions',
      'actionLoading',
      'actionError',
      'capabilities',
      'snapshot',
      'loading',
      'busy',
      'readError',
      'readTransient',
      'writeError',
      'rejectionNotice',
      'renewalError',
      'renewalTransient',
      'pending',
      'success',
      'fresh',
      'lastVerifiedAt',
      'online',
      'actionPreparing',
    ].map((key) => [key, vue.ref(null)]),
  )
  state.capabilities.value = { enabled: true }
  state.fresh.value = state.online.value = true
  state.loading.value = state.busy.value = false
  state.snapshot.value = {
    roomId: '9',
    version: '1',
    mySessionId: null,
    myRole: role,
    members: [{ userId: '1' }],
    seats: [{ seatNo: 1, state: 'EMPTY', userId: null, muted: true }],
    requests: [],
  }
  state.load = async () => {}
  state.prepareAction = async () => true
  state.join = async () => {
    calls.push('JOIN')
    state.snapshot.value.mySessionId = 'synthetic-session'
  }
  state.command = async (type, args) => {
    calls.push(type)
    if (type === 'PULL') state.snapshot.value.seats[0] = { seatNo: 1, state: 'ON_MIC', userId: '1', muted: true }
    if (type === 'APPLY') state.snapshot.value.requests = [{ id: 'synthetic-request', type, userId: '1', seatNo: 1 }]
    if (type === 'MUTE') state.snapshot.value.seats[0].muted = args.value
    if (type === 'CANCEL') state.snapshot.value.requests = []
    return { type }
  }
  let connected = false,
    mic = false,
    device = async () => true,
    release = async () => {}
  const panel = {
    connected: () => connected,
    connect: async () => {
      if (!connected) calls.push('CONNECT')
      connected = true
    },
    enableMicrophone: async () => {
      if (!mic) {
        calls.push('DEVICE')
        mic = await device()
      }
      return mic
    },
    disableMicrophone: async () => {
      calls.push('STOP_DEVICE')
      await release()
      mic = false
    },
  }
  const context = vm.createContext({
    defineProps: () => props,
    defineEmits: () => () => {},
    defineExpose() {},
    document,
    confirm: () => true,
  })
  const transpile = (source) =>
    ts.transpileModule(source, {
      compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
    }).outputText
  const helpers = new vm.SourceTextModule(
    transpile(await readFile(new URL('../src/services/voice-room-entry.ts', import.meta.url), 'utf8')),
    { context },
  )
  await helpers.link(() => {
    throw new Error('Unexpected helper import')
  })
  await helpers.evaluate()
  const component = await readFile(new URL('../src/components/VoiceInteractionPanel.vue', import.meta.url), 'utf8')
  const script = component.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
  const module = new vm.SourceTextModule(
    transpile(
      script +
        '\nexport { startSpeaking, listenOnly, speechConsent, speechBusy, listenBusy, speechNotice, mediaPanel };',
    ),
    { context },
  )
  const imports = {
    vue: {
      ...vue,
      onMounted: (callback) => mounted.push(callback),
      onBeforeUnmount: (callback) => hooks.push(callback),
    },
    '../composables/voice-interaction-workspace': { useVoiceInteractionWorkspace: () => state },
    './VoiceMediaPlanPanel.vue': { default: {} },
    './ControlledVoiceMediaPanel.vue': { default: {} },
  }
  await module.link((name) => {
    if (name === '../services/voice-room-entry') return helpers
    const values = imports[name]
    assert.ok(values, 'Unexpected component import')
    return new vm.SyntheticModule(
      Object.keys(values),
      function () {
        for (const [key, value] of Object.entries(values)) this.setExport(key, value)
      },
      { context },
    )
  })
  await module.evaluate()
  module.namespace.mediaPanel.value = panel
  mounted.forEach((callback) => callback())
  return {
    props,
    state,
    calls,
    document,
    listeners,
    api: module.namespace,
    setDevice: (fn) => {
      device = fn
    },
    setRelease: (fn) => {
      release = fn
    },
    dispose: () => hooks.forEach((callback) => callback()),
  }
}

test('真实父面板一键房主链路串行且不重复采集，不能把只收听作为开麦意图', async () => {
  const h = await fixture()
  try {
    await h.api.startSpeaking()
    assert.deepEqual(h.calls, ['JOIN', 'PULL', 'MUTE', 'CONNECT', 'DEVICE'])
    assert.equal(h.api.speechConsent.value, false)
    await h.api.startSpeaking()
    assert.equal(h.calls.filter((value) => value === 'DEVICE').length, 1)
    await h.api.listenOnly()
    assert.equal(h.api.speechConsent.value, false)
    assert.equal(h.calls.filter((value) => value === 'DEVICE').length, 1)
  } finally {
    h.dispose()
  }
})

test('普通成员后台获得批准不采集，返回可见房间才消费一次先前明确意图', async () => {
  const h = await fixture('LISTENER')
  try {
    await h.api.startSpeaking()
    assert.deepEqual(h.calls, ['JOIN', 'APPLY', 'CONNECT'])
    h.document.visibilityState = 'hidden'
    h.state.snapshot.value.seats[0] = { seatNo: 1, state: 'ON_MIC', userId: '1', muted: false }
    h.state.snapshot.value.requests = []
    await vue.nextTick()
    assert.equal(h.calls.includes('DEVICE'), false)
    h.props.active = false
    h.document.visibilityState = 'visible'
    h.listeners.get('visibilitychange')()
    await vue.nextTick()
    assert.equal(h.calls.includes('DEVICE'), false, '可见文档但站内不在房间仍不能开麦')
    h.props.active = true
    await vue.nextTick()
    await new Promise((resolve) => setImmediate(resolve))
    assert.equal(h.calls.filter((value) => value === 'DEVICE').length, 1)
    h.listeners.get('visibilitychange')()
    for (let count = 0; count < 4; count++) await vue.nextTick()
    assert.equal(h.calls.filter((value) => value === 'DEVICE').length, 1)
  } finally {
    h.dispose()
  }
  assert.equal(h.listeners.size, 0)
})

test('等待批准时取消只收听，不因后来的批准重新开启设备', async () => {
  const h = await fixture('LISTENER')
  try {
    await h.api.startSpeaking()
    await h.api.listenOnly()
    assert.ok(h.calls.includes('CANCEL'))
    h.state.snapshot.value.seats[0] = { seatNo: 1, state: 'ON_MIC', userId: '1', muted: false }
    for (let count = 0; count < 4; count++) await vue.nextTick()
    assert.equal(h.calls.includes('DEVICE'), false)
  } finally {
    h.dispose()
  }
})

test('取消设备操作在途时互斥，新发言意图不会穿过旧设备释放窗口', async () => {
  const h = await fixture()
  let resolveRelease
  try {
    h.setRelease(
      () =>
        new Promise((resolve) => {
          resolveRelease = resolve
        }),
    )
    const stopped = h.api.listenOnly()
    assert.equal(h.api.listenBusy.value, true)
    await h.api.startSpeaking()
    assert.equal(h.calls.includes('DEVICE'), false)
    resolveRelease()
    await stopped
    assert.equal(h.api.listenBusy.value, false)
  } finally {
    h.dispose()
  }
})
