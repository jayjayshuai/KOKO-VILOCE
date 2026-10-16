import assert from 'node:assert/strict'
import test from 'node:test'
import * as vue from 'vue'
import * as pinia from 'pinia'
import { executeTs } from './helpers/execute-ts.mjs'

// 真控制逻辑/响应性，HTTP、设备和时钟是明确夹具；不代替真实RTC验收。
class ApiRequestError extends Error {
  constructor(message, status, code = null) {
    super(message)
    this.status = status
    this.code = code
  }
}
const a = '36f1fb46-91cd-4f32-b174-55b0c5d4bf17',
  b = '46f1fb46-91cd-4f32-b174-55b0c5d4bf17'
const view = (changes = {}) => ({
  id: a,
  conversationId: b,
  initiatorId: '42',
  version: 1,
  state: 'RINGING',
  endReason: null,
  ringExpiresAtUtc: '2026-10-15T14:00:00',
  participants: [
    { userId: '42', displayName: 'Caller', state: 'ACCEPTED' },
    { userId: '43', displayName: 'Peer', state: 'INVITED' },
  ],
  ...changes,
})
const clone = (x) => JSON.parse(JSON.stringify(x))
const active = (changes = {}) =>
  view({
    version: 2,
    state: 'ACTIVE',
    participants: [
      { userId: '42', displayName: 'Caller', state: 'ACCEPTED' },
      { userId: '43', displayName: 'Peer', state: 'ACCEPTED' },
    ],
    ...changes,
  })
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
async function setup(overrides = {}, user = '42') {
  const calls = [],
    stopped = [],
    session = vue.reactive({ user: { id: user }, sessionRevision: 1 })
  let time = 100000,
    sequence = 0,
    busy = false
  const api = {
    capability: async () => ({ enabled: true }),
    current: async () => undefined,
    read: async () => view(),
    invite: async () => view(),
    command: async (input) => ({ requestId: input.requestId, appliedVersion: 2, current: active() }),
    heartbeat: async () => {},
    ...overrides,
  }
  const observed = Object.fromEntries(
    Object.entries(api).map(([key, fn]) => [
      key,
      (...args) => {
        calls.push({ key, args: clone(args) })
        return fn(...args)
      },
    ]),
  )
  const module = await executeTs('composables/native-call-control.ts', {
    vue,
    '../services/http': { ApiRequestError },
    '../services/native-calls': { nativeCallApi: observed, nativeCallRequestId: () => `request-${++sequence}` },
  })
  const control = module.createNativeCallControl(
    session,
    async () => {
      stopped.push(true)
    },
    () => busy,
    observed,
    () => `request-${++sequence}`,
    () => time,
  )
  await control.initialize()
  return {
    control,
    calls,
    stopped,
    session,
    advance(ms) {
      time += ms
    },
    occupied(value) {
      busy = value
    },
  }
}
test('没有能力/旧服务404不显示假通话能力，也不发送呼叫', async () => {
  for (const capability of [
    async () => ({ enabled: false }),
    async () => {
      throw new ApiRequestError('old service', 404)
    },
  ]) {
    const h = await setup({ capability })
    await h.control.invite(b)
    await h.control.refresh()
    assert.equal(h.control.state.enabled, false)
    assert.equal(h.calls.length, 1)
    assert.equal(h.control.state.syncNote, '')
  }
})
test('明确发起才拥有本网页同意，轮询已接听事实不能恢复麦克风许可', async () => {
  const h = await setup({ current: async () => active() })
  await h.control.refresh()
  assert.equal(h.control.state.localConsent, false)
  h.control.resume()
  assert.equal(h.control.state.localConsent, true)
  h.control.stopLocal()
  await h.control.refresh()
  assert.equal(h.control.state.localConsent, false)
  const caller = await setup()
  await caller.control.invite(b)
  assert.equal(caller.control.state.localConsent, true)
  assert.equal(caller.control.state.view.state, 'RINGING')
})
test('另一个公开或私人音频占用时，不发送邀请或接听，不自动结束原通话', async () => {
  const h = await setup({ current: async () => view() }, '43')
  h.occupied(true)
  await h.control.invite(b)
  await h.control.refresh()
  await h.control.command('ACCEPT')
  assert.equal(
    h.calls.some((x) => ['invite', 'command'].includes(x.key)),
    false,
  )
  assert.match(h.control.state.error, /已有语音连接/)
})
test('邀请结果未知保留原UUID和成员，重试不会创建新呼叫或改变目标', async () => {
  let fail = true
  const h = await setup({
    invite: async () => {
      if (fail) throw new ApiRequestError('timeout', 0)
      return view()
    },
  })
  const targets = ['43']
  await h.control.invite(b, targets)
  targets.push('44')
  assert.equal(h.control.state.pending.uncertain, true)
  await h.control.invite(a)
  fail = false
  await h.control.retry()
  const writes = h.calls.filter((x) => x.key === 'invite')
  assert.equal(writes.length, 2)
  assert.deepEqual(writes[0].args, writes[1].args)
  assert.deepEqual(writes[1].args[0].targets, ['43'])
  assert.equal(h.control.state.pending, null)
})
test('接听未确认时不授权媒体；原重试返回结束快照时不能复活声音', async () => {
  let fail = true
  const h = await setup(
    {
      current: async () => view(),
      command: async (input) => {
        if (fail) throw new ApiRequestError('network', 0)
        return { requestId: input.requestId, appliedVersion: 2, current: active({ state: 'ENDED', version: 3 }) }
      },
    },
    '43',
  )
  await h.control.refresh()
  await h.control.command('ACCEPT')
  assert.equal(h.control.state.localConsent, false)
  fail = false
  await h.control.retry()
  assert.equal(h.control.state.localConsent, false)
  assert.equal(h.control.state.view.state, 'ENDED')
  const writes = h.calls.filter((x) => x.key === 'command')
  assert.deepEqual(writes[0].args, writes[1].args)
})
test('用户在接听在途时停止本机声音，迟到接听确认不能重新开启', async () => {
  const wait = deferred(),
    h = await setup({ current: async () => view(), command: () => wait.promise }, '43')
  await h.control.refresh()
  const pending = h.control.command('ACCEPT')
  h.control.stopLocal()
  wait.resolve({ requestId: 'request-1', appliedVersion: 2, current: active() })
  await pending
  assert.equal(h.control.state.view.state, 'ACTIVE')
  assert.equal(h.control.state.localConsent, false)
  assert.equal(h.control.state.stopped, true)
})
test('接听结果未知后停止本机，再重试原命令只确认结果，不把已撤销的开麦意图恢复', async () => {
  let fail = true
  const h = await setup(
    {
      current: async () => view(),
      command: async () => {
        if (fail) throw new ApiRequestError('lost', 0)
        return { requestId: 'request-1', appliedVersion: 2, current: active() }
      },
    },
    '43',
  )
  await h.control.refresh()
  await h.control.command('ACCEPT')
  h.control.stopLocal()
  fail = false
  await h.control.retry()
  assert.equal(h.control.state.pending, null)
  assert.equal(h.control.state.localConsent, false)
})
test('挂断先停止本机音频；未知结果和旧ACTIVE轮询不能自动连接，原命令重试仍用原版本', async () => {
  const h = await setup({
    current: async () => active(),
    command: async () => {
      throw new ApiRequestError('lost reply', 0)
    },
  })
  await h.control.refresh()
  h.control.resume()
  await h.control.command('LEAVE')
  assert.ok(h.stopped.length)
  assert.equal(h.control.state.localConsent, false)
  await h.control.refresh()
  h.control.resume()
  assert.equal(h.control.state.localConsent, false)
  await h.control.retry()
  const writes = h.calls.filter((x) => x.key === 'command')
  assert.deepEqual(writes[0].args, writes[1].args)
  assert.equal(writes[1].args[0].expectedVersion, 2)
})
test('同账号重登录使旧邀请/接听响应失效，不污染新轮次或再次连接', async () => {
  const wait = deferred(),
    h = await setup({ invite: () => wait.promise })
  const writing = h.control.invite(b)
  h.session.sessionRevision++
  h.control.reset()
  wait.resolve(view())
  await writing
  assert.equal(h.control.state.view, null)
  assert.equal(h.control.state.localConsent, false)
  assert.equal(h.control.state.pending, null)
})
test('写入开始前的旧轮询和更低版本不能覆盖已确认接听', async () => {
  const wait = deferred()
  let current = () => wait.promise
  const h = await setup({ current: () => current() }, '43')
  const reading = h.control.refresh()
  current = async () => active()
  wait.resolve(view())
  await reading
  const late = deferred()
  current = () => late.promise
  const older = h.control.refresh()
  await h.control.command('ACCEPT')
  late.resolve(view())
  await older
  assert.equal(h.control.state.view.version, 2)
  assert.equal(h.control.state.localConsent, true)
  current = async () => view()
  await h.control.refresh()
  assert.equal(h.control.state.view.version, 2)
})
test('后台同步失败不反复覆盖操作错误，恢复后只清除同步提示', async () => {
  let fail = true
  const h = await setup({
    current: async () => {
      if (fail) throw new ApiRequestError('503', 503)
      return active()
    },
  })
  h.control.state.error = '明确的操作提示'
  await h.control.refresh()
  await h.control.refresh()
  assert.equal(h.control.state.syncNote, '')
  await h.control.refresh()
  assert.match(h.control.state.syncNote, /后台重试/)
  fail = false
  await h.control.refresh()
  assert.equal(h.control.state.syncNote, '')
  assert.equal(h.control.state.error, '明确的操作提示')
})
test('心跳续租限频、其他网站拒绝和超过本机核验期限均停止音频', async () => {
  let deny = false
  const h = await setup({
    current: async () => active(),
    heartbeat: async () => {
      if (deny) throw new ApiRequestError('other website', 403)
    },
  })
  await h.control.refresh()
  h.control.resume()
  await h.control.heartbeat()
  await h.control.heartbeat()
  assert.equal(h.calls.filter((x) => x.key === 'heartbeat').length, 1)
  h.advance(10000)
  deny = true
  await h.control.heartbeat()
  assert.equal(h.control.state.localConsent, false)
  h.control.resume()
  h.advance(26000)
  h.control.expireUnverified()
  assert.equal(h.control.state.localConsent, false)
})
test('真实服务契约拒绝可修正重试；没有契约的代理409必须保留未知原请求', async () => {
  for (const code of ['CONFLICT', null]) {
    const h = await setup({
      invite: async () => {
        throw new ApiRequestError('busy', 409, code)
      },
    })
    await h.control.invite(b)
    assert.equal(!!h.control.state.pending, code === null)
  }
})
test('真实响应校验拒绝损坏成员、重复参与者和超安全整数版本，HTTP原命令契约保持固定', async () => {
  const calls = []
  const module = await executeTs('services/native-calls.ts', {
    './http': {
      request: async (path, options) => {
        calls.push({ path, options })
        return { requestId: a, appliedVersion: 2, current: active() }
      },
    },
    './chat': { messageUuid: () => a },
  })
  for (const row of [
    view({ version: Number.MAX_SAFE_INTEGER + 1 }),
    view({ participants: [] }),
    view({ participants: [view().participants[0], view().participants[0]] }),
    view({ initiatorId: '0' }),
    view({ ringExpiresAtUtc: 'invalid' }),
  ])
    assert.throws(() => module.nativeCallView(row), /响应无效/)
  const command = { callId: a, requestId: a, expectedVersion: 1, type: 'ACCEPT' }
  await module.nativeCallApi.command(command)
  assert.equal(calls[0].path, `/chat/calls/${a}/commands`)
  assert.deepEqual(JSON.parse(calls[0].options.body), { requestId: a, expectedVersion: 1, type: 'ACCEPT' })
})
test('群选择Store保留ID字符串且旧账号/已有通话不能发起新意图', async () => {
  pinia.setActivePinia(pinia.createPinia())
  const module = await executeTs('stores/native-call.ts', { pinia })
  const s = module.useNativeCallStore()
  s.reset('42', 1)
  s.enabled = true
  const conversation = { id: b, title: 'group', members: [{ userId: '2109124317675118594', displayName: 'Member' }] }
  s.open(conversation, '42', 1)
  conversation.members[0].displayName = 'changed'
  assert.equal(s.draft.members[0].displayName, 'Member')
  assert.equal(s.draft.members[0].userId, '2109124317675118594')
  s.draft = null
  s.occupied = true
  s.open(conversation, '42', 1)
  assert.equal(s.draft, null)
  s.occupied = false
  s.open(conversation, '42', 2)
  assert.equal(s.draft, null)
})
