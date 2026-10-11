import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import { compileScript, parse } from '@vue/compiler-sfc'
import ts from 'typescript'
import * as vue from 'vue'

// 执行真实SFC的授权/取消逻辑；网络与SDK是明确桩，不证明RTP或SFU权限。
const sid = '00000000-0000-0000-0000-000000000042'
const deferred = () => {
  let resolve
  const promise = new Promise((yes) => {
    resolve = yes
  })
  return { promise, resolve }
}
async function setup(network = {}) {
  const props = vue.reactive({
    roomId: '9',
    userId: '42',
    sessionRevision: 1,
    fresh: true,
    snapshot: {
      roomId: '9',
      version: '3',
      mySessionId: sid,
      seats: Array.from({ length: 8 }, (_, i) => ({ seatNo: i + 1, state: 'EMPTY', userId: null, muted: true })),
    },
  })
  const hooks = [],
    calls = []
  let credentialApi, policy
  const voice = {
    phase: vue.ref('idle'),
    voiceError: vue.ref(''),
    microphoneEnabled: vue.ref(false),
    microphoneBusy: vue.ref(false),
    participantCount: vue.ref(0),
    audioPlaybackBlocked: vue.ref(false),
    audioPlaybackBusy: vue.ref(false),
    audioPlaybackError: vue.ref(''),
    async leaveVoiceRoom() {
      calls.push('disconnect')
      voice.phase.value = 'idle'
    },
    async joinVoiceRoom(target) {
      calls.push('connect')
      return credentialApi(target.id, new AbortController().signal)
    },
    toggleMicrophone() {
      calls.push('device')
    },
    resumeVoiceAudio() {
      calls.push('playback')
    },
  }
  const { descriptor } = parse(
    await readFile(new URL('../src/components/ControlledVoiceMediaPanel.vue', import.meta.url), 'utf8'),
  )
  const source = compileScript(descriptor, { id: 'controlled-media-sfc-test' }).content
  const context = vm.createContext({ AbortController, Error, console })
  const module = new vm.SourceTextModule(
    ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } })
      .outputText,
    { context },
  )
  const mocks = {
    '../services/voice-media-preparation': { waitForVoiceMediaRetirement: network.prepare ?? (async () => {}) },
    './VoicePlaybackControls.vue': { default: {} },
    vue: { ...vue, onBeforeUnmount: (callback) => hooks.push(callback) },
    '../composables/voice-connection': {
      useVoiceConnection(_session, api, _root, _sdk, p) {
        credentialApi = api
        policy = p
        return voice
      },
    },
    '../services/voice-media-credentials': {
      voiceMediaCredentialsApi: {
        capability: network.capability ?? (async () => ({ enabled: true })),
        issue:
          network.issue ??
          (async (...args) => {
            calls.push(args)
            return {
              url: 'wss://app.example.invalid/api/media/livekit',
              token: 'synthetic-token',
              roomName: 'koko-voice-9',
              sessionId: sid,
              generation: '9007199254741001',
              seatNo: null,
              canPublish: false,
              expiresInSeconds: 120,
            }
          }),
      },
    },
  }
  await module.link((specifier) => {
    const values = mocks[specifier]
    if (!values) throw new Error('Unconfigured ' + specifier)
    return new vm.SyntheticModule(
      Object.keys(values),
      function () {
        for (const [key, value] of Object.entries(values)) this.setExport(key, value)
      },
      { context },
    )
  })
  await module.evaluate()
  const scope = vue.effectScope()
  const state = scope.run(() => module.namespace.default.setup(props, { expose() {} }))
  await new Promise((resolve) => setImmediate(resolve))
  return {
    props,
    state,
    calls,
    voice,
    policy,
    api: credentialApi,
    dispose() {
      hooks.forEach((h) => h())
      scope.stop()
    },
  }
}
test('候选关闭不发凭据，能力故障不假装成功或请求设备', async () => {
  const h = await setup({ capability: async () => ({ enabled: false }) })
  h.state.connect()
  assert.equal(h.calls.includes('connect'), false)
  h.dispose()
  const failed = await setup({
    capability: async () => {
      throw new Error('能力读取故障')
    },
  })
  assert.equal(failed.state.enabled.value, false)
  assert.match(failed.state.capabilityError.value, /读取故障/)
  failed.dispose()
})
test('精确高位轮次/会话/听众授权，快照失效同步撤销凭据并断开', async () => {
  const h = await setup()
  const grant = await h.api('9', new AbortController().signal)
  assert.equal(grant.generation, '9007199254741001')
  assert.equal(h.policy.canPublish(), false)
  assert.equal(h.state.credential.value, grant)
  assert.equal(h.calls[0][0], '9')
  assert.equal(h.calls[0][1], sid)
  assert.equal(h.calls[0][2], '3')
  h.voice.phase.value = 'connected'
  h.props.fresh = false
  assert.equal(h.state.credential.value, null)
  assert.ok(h.calls.includes('disconnect'))
  h.dispose()
})
test('旧媒体请求晚返回、数值化轮次或席位不一致均不能进入SDK', async () => {
  const late = deferred()
  const h = await setup({ issue: () => late.promise })
  const pending = h.api('9', new AbortController().signal)
  await new Promise((resolve) => setImmediate(resolve)) // 先越过准备读取，保持验证“凭据在途晚到”边界。
  h.props.snapshot.version = '4'
  late.resolve({ sessionId: sid, generation: '1', seatNo: null, canPublish: false, expiresInSeconds: 120 })
  await assert.rejects(pending, /快照已变化/)
  assert.equal(h.state.credential.value, null)
  h.dispose()
  for (const override of [{ generation: 9007199254741001 }, { seatNo: 1, canPublish: true }]) {
    const bad = await setup({
      issue: async () => ({
        sessionId: sid,
        generation: '1',
        seatNo: null,
        canPublish: false,
        expiresInSeconds: 120,
        ...override,
      }),
    })
    await assert.rejects(bad.api('9', new AbortController().signal), /不一致/)
    assert.equal(bad.state.credential.value, null)
    bad.dispose()
  }
})
test('准备期间换权限不能继续申请凭据，设备与JWT都不隐式重试', async () => {
  const ready = deferred()
  let issues = 0
  const h = await setup({
    prepare: () => ready.promise,
    issue: async () => {
      issues++
      throw new Error('不应签发')
    },
  })
  const pending = h.api('9', new AbortController().signal)
  h.props.fresh = false
  ready.resolve()
  await assert.rejects(pending, /准备期间变化/)
  assert.equal(issues, 0)
  assert.equal(h.calls.includes('device'), false)
  h.dispose()
})
test('同账号新登录轮次废弃旧能力请求，不回填旧enabled状态', async () => {
  const old = deferred()
  let first = true
  const h = await setup({
    capability: () => (first ? ((first = false), old.promise) : Promise.resolve({ enabled: false })),
  })
  h.props.sessionRevision++
  await vue.nextTick()
  await new Promise((resolve) => setImmediate(resolve))
  old.resolve({ enabled: true })
  await new Promise((resolve) => setImmediate(resolve))
  assert.equal(h.state.enabled.value, false)
  h.dispose()
})
