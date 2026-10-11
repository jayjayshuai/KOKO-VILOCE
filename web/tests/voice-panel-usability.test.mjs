import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import * as vue from 'vue'

test('房主默认选本人但不自动写入，换成员/角色不能残留旧目标', async () => {
  const source = await readFile(new URL('../src/components/VoiceInteractionPanel.vue', import.meta.url), 'utf8')
  const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
  const props = vue.reactive({ roomId: '9', userId: '1', sessionRevision: 1 })
  const writes = []
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
      'writeError',
      'pending',
      'success',
      'fresh',
      'lastVerifiedAt',
      'online',
      'actionPreparing',
    ].map((key) => [key, vue.ref(key === 'online')]),
  )
  state.snapshot.value = null
  state.fresh.value = false
  state.busy.value = false
  state.pending.value = null
  state.command = (...args) => writes.push(args)
  let preflight = async () => {}
  state.load = (...args) => preflight(...args)
  state.prepareAction = async (...args) => {
    await preflight(...args)
    return state.fresh.value && !state.busy.value
  }
  const imports = {
    vue,
    '../composables/voice-interaction-workspace': { useVoiceInteractionWorkspace: () => state },
    './VoiceMediaPlanPanel.vue': { default: {} },
    './ControlledVoiceMediaPanel.vue': { default: {} },
  }
  const context = vm.createContext({ defineProps: () => props, defineEmits: () => () => {}, confirm: () => true })
  const module = new vm.SourceTextModule(
    ts.transpileModule(
      script + '\nexport { target, showMediaPlan, intent, mySeat, freeSeat, myApplication, execute };',
      {
        compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
      },
    ).outputText,
    { context },
  )
  await module.link((name) => {
    const values = imports[name]
    assert.ok(values, 'Unexpected component dependency')
    return new vm.SyntheticModule(
      Object.keys(values),
      function () {
        for (const [key, value] of Object.entries(values)) this.setExport(key, value)
      },
      { context },
    )
  })
  await module.evaluate()
  const { target, showMediaPlan, intent, mySeat, freeSeat, myApplication, execute } = module.namespace
  state.snapshot.value = {
    mySessionId: 'synthetic-session',
    myRole: 'OWNER',
    members: [{ userId: '1' }, { userId: '2' }],
    seats: [
      { seatNo: 1, state: 'EMPTY' },
      { seatNo: 2, state: 'ON_MIC', userId: '2', muted: false },
    ],
    requests: [],
  }
  state.fresh.value = true
  assert.equal(target.value, '1')
  assert.equal(intent.value, 'listen')
  assert.equal(mySeat.value, undefined)
  assert.equal(freeSeat.value.seatNo, 1)
  intent.value = 'speak'
  assert.equal(writes.length, 0, '选择发言方式不能隐式授予权限或开启麦克风')
  target.value = '2'
  state.snapshot.value = { ...state.snapshot.value, version: '2' }
  assert.equal(target.value, '2')
  props.userId = '3'
  state.snapshot.value = {
    mySessionId: 'synthetic-next-session',
    myRole: 'LISTENER',
    members: [{ userId: '3' }],
    seats: [{ seatNo: 1, state: 'ON_MIC', userId: '3', muted: true }],
    requests: [{ id: 'synthetic-request', type: 'APPLY', userId: '3', seatNo: 2 }],
  }
  assert.equal(target.value, '')
  assert.equal(showMediaPlan.value, false)
  assert.equal(mySeat.value.muted, true)
  assert.equal(myApplication.value.seatNo, 2)
  assert.equal(writes.length, 0)
  let resolveRead
  preflight = () =>
    new Promise((resolve) => {
      resolveRead = resolve
    })
  const prepared = execute('DOWN', { seatNo: 1 })
  assert.equal(writes.length, 0)
  props.sessionRevision++
  resolveRead()
  await prepared
  assert.equal(writes.length, 0, '换登录轮次必须取消尚未发送的操作')
  preflight = async () => {
    state.fresh.value = false
  }
  await execute('DOWN', { seatNo: 1 })
  assert.equal(writes.length, 0, '核验失败不能发送')
  state.fresh.value = true
  preflight = async () => {
    state.snapshot.value = { ...state.snapshot.value, version: '3' }
  }
  await execute('DOWN', { seatNo: 1 })
  assert.equal(writes.length, 1, '核验成功后只发一次明确操作')
  assert.equal(writes[0][0], 'DOWN')
})

test('用户可见说明区分连接和发声，技术进度默认折叠且不宣称媒体未开放', async () => {
  const panel = await readFile(new URL('../src/components/VoiceInteractionPanel.vue', import.meta.url), 'utf8')
  const plan = await readFile(new URL('../src/components/VoiceMediaPlanPanel.vue', import.meta.url), 'utf8')
  const connection = await readFile(new URL('../src/components/ControlledVoiceMediaPanel.vue', import.meta.url), 'utf8')
  assert.match(panel, /1 · 加入房间/)
  assert.match(panel, /只收听/)
  assert.match(panel, /我要发言/)
  assert.match(panel, /等待房主同意/)
  assert.match(panel, /同意发言/)
  assert.match(panel, /高级：麦位、预约和房间管理/)
  assert.match(panel, /解除闭麦/)
  assert.match(panel, /自己上麦/)
  assert.match(panel, /<details[\s\S]*?<VoiceMediaPlanPanel\s+v-if="showMediaPlan"/)
  assert.doesNotMatch(panel + plan, /媒体仍未开放/)
  assert.match(panel, /:reading="loading"/)
  assert.match(connection, /:disabled="reading \|\| !contextCurrent\(\)/)
  assert.doesNotMatch(
    connection,
    /!props\.reading.*contextCurrent|contextCurrent.*!props\.reading/,
    '读取只影响新连接，不能使定时同步切断已连接通话',
  )
})
