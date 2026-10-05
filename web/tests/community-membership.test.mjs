import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import { compileScript, parse } from '@vue/compiler-sfc'
import ts from 'typescript'
import * as vue from 'vue'

// 执行真实 SFC setup；替换网络及生命周期，不假装 DOM/点击验收。
const { descriptor } = parse(
  await readFile(new URL('../src/components/CommunityMembershipPanel.vue', import.meta.url), 'utf8'),
)
const code = ts.transpileModule(compileScript(descriptor, { id: 'membership-test' }).content, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
}).outputText
const member = (id) => ({
  userId: id,
  role: 'MEMBER',
  joinedAt: '2026-10-02T17:00:00',
  handle: 'member',
  displayName: '成员',
})
const status = (id, role = 'MEMBER') => ({ community: { id, name: id, visibility: 'PUBLIC', members: 2 }, role })
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
async function harness(overrides = {}, confirm = () => true) {
  const calls = [],
    unmount = [],
    events = []
  const api = Object.fromEntries(
    ['status', 'joined', 'members', 'join', 'leave', 'remove'].map((name) => [
      name,
      (...args) => {
        calls.push({ name, args })
        if (overrides[name]) return overrides[name](...args)
        return Promise.resolve(name === 'status' ? status(args[0]) : { items: [], nextBefore: null })
      },
    ]),
  )
  const context = vm.createContext({ AbortController, Error, console, confirm })
  const runtime = { ...vue, onMounted() {}, onUnmounted: (callback) => unmount.push(callback) }
  const vueModule = new vm.SyntheticModule(
    Object.keys(runtime),
    function () {
      for (const [key, value] of Object.entries(runtime)) this.setExport(key, value)
    },
    { context },
  )
  const apiModule = new vm.SyntheticModule(
    ['communityMembershipApi'],
    function () {
      this.setExport('communityMembershipApi', api)
    },
    { context },
  )
  const module = new vm.SourceTextModule(code, { context })
  await module.link((specifier) => {
    if (specifier === 'vue') return vueModule
    if (specifier === '../services/community-membership') return apiModule
    throw new Error(`Unexpected runtime import ${specifier}`)
  })
  await module.evaluate()
  const state = module.namespace.default.setup({ userId: '42' }, { expose() {}, emit: (event) => events.push(event) })
  return { state, calls, events, dispose: () => unmount.forEach((callback) => callback()) }
}

test('切换社区取消旧响应，旧身份/成员不能覆盖新视图', async () => {
  const wait = deferred()
  const h = await harness({ status: (id) => (id === 'old' ? wait.promise : Promise.resolve(status(id))) })
  try {
    const old = h.state.select('old')
    const signal = h.calls[0].args[1]
    await h.state.select('new')
    assert.equal(signal.aborted, true)
    wait.resolve(status('old'))
    await old
    assert.equal(h.state.selected.value.community.id, 'new')
  } finally {
    h.dispose()
  }
})

test('未加入仅读取状态，不查询或伪造成员名册', async () => {
  const h = await harness({ status: (id) => Promise.resolve(status(id, null)) })
  try {
    await h.state.select('public')
    assert.equal(h.calls.length, 1)
    assert.equal(h.state.members.value.length, 0)
    assert.equal(h.state.selected.value.role, null)
  } finally {
    h.dispose()
  }
})

test('成员读取拒绝清除旧私密信息，失败不冒充空名册', async () => {
  const h = await harness({ members: () => Promise.reject(new Error('无当前成员权限')) })
  try {
    await h.state.select('private')
    assert.equal(h.state.selected.value, null)
    assert.match(h.state.error.value, /无当前成员权限/)
  } finally {
    h.dispose()
  }
})

test('真实加入失败不发变更事件或显示成功', async () => {
  const h = await harness({
    status: (id) => Promise.resolve(status(id, null)),
    join: () => Promise.reject(new Error('人数已满')),
  })
  try {
    await h.state.select('public')
    await h.state.mutate('join')
    assert.equal(h.state.selected.value.role, null)
    assert.equal(h.events.length, 0)
    assert.equal(h.state.success.value, '')
    assert.equal(h.state.error.value, '人数已满')
  } finally {
    h.dispose()
  }
})

test('加入重复点击受忙碌保护，提交后重新读取而非客户端加人数', async () => {
  const wait = deferred()
  let committed = false
  const h = await harness({
    status: (id) => Promise.resolve(status(id, committed ? 'MEMBER' : null)),
    join: () => wait.promise,
  })
  try {
    await h.state.select('public')
    const running = h.state.mutate('join')
    await h.state.mutate('join')
    assert.equal(h.calls.filter((call) => call.name === 'join').length, 1)
    committed = true
    wait.resolve()
    await running
    assert.equal(h.events.length, 1)
    assert.equal(h.state.selected.value.role, 'MEMBER')
    assert.equal(h.state.selected.value.community.members, 2)
  } finally {
    h.dispose()
  }
})

test('取消确认不退出，成功退出从本人列表读取而不访问旧私密详情', async () => {
  let accepted = false
  const h = await harness({}, () => accepted)
  try {
    await h.state.select('private')
    await h.state.mutate('leave')
    assert.equal(h.calls.filter((call) => call.name === 'leave').length, 0)
    accepted = true
    await h.state.mutate('leave')
    assert.equal(h.state.selected.value, null)
    assert.equal(h.calls.at(-1).name, 'joined')
    assert.match(h.state.success.value, /已退出/)
  } finally {
    h.dispose()
  }
})

test('提交成功但刷新失败区别提示，撤销旧私密视图', async () => {
  let committed = false
  const h = await harness({
    status: (id) => (committed ? Promise.reject(new Error('读取暂不可用')) : Promise.resolve(status(id))),
    remove: () => {
      committed = true
      return Promise.resolve()
    },
  })
  try {
    await h.state.select('public')
    await h.state.mutate('remove', member('43'))
    assert.match(h.state.success.value, /已移除/)
    assert.match(h.state.error.value, /操作已提交/)
    assert.equal(h.state.selected.value, null)
  } finally {
    h.dispose()
  }
})

test('卸载取消在途请求，不发已销毁面板的迟到成功事件', async () => {
  const wait = deferred()
  const h = await harness({ join: () => wait.promise })
  await h.state.select('public')
  const running = h.state.mutate('join')
  const signal = h.calls.find((call) => call.name === 'join').args[1]
  h.dispose()
  assert.equal(signal.aborted, true)
  wait.resolve()
  await running
  assert.equal(h.events.length, 0)
  assert.equal(h.state.success.value, '')
})

test('成员分页使用服务端游标并去重，权限撤销时清除私密详情', async () => {
  let revoked = false
  const h = await harness({
    members: (_id, before) =>
      revoked
        ? Promise.reject(new Error('成员权限已撤销'))
        : Promise.resolve(
            before
              ? { items: [member('43'), member('42')], nextBefore: '42' }
              : { items: [member('44'), member('43')], nextBefore: '43' },
          ),
  })
  try {
    await h.state.select('private')
    await h.state.moreMembers()
    assert.equal(h.calls.at(-1).args[1], '43')
    assert.deepEqual(
      Array.from(h.state.members.value, (item) => item.userId),
      ['44', '43', '42'],
    )
    revoked = true
    await h.state.moreMembers()
    assert.equal(h.calls.at(-1).args[1], '42')
    assert.equal(h.state.selected.value, null)
    assert.equal(h.state.members.value.length, 0)
    assert.match(h.state.error.value, /权限已撤销/)
  } finally {
    h.dispose()
  }
})

test('本人社区分页使用字符串游标并去重，失败保留已读列表且不伪装空态', async () => {
  let offline = false
  const h = await harness({
    joined: (before) =>
      offline
        ? Promise.reject(new Error('列表暂不可用'))
        : Promise.resolve(
            before
              ? { items: [status('43').community, status('42').community], nextBefore: '42' }
              : { items: [status('44').community, status('43').community], nextBefore: '43' },
          ),
  })
  try {
    await h.state.loadJoined()
    await h.state.loadJoined(true)
    assert.equal(h.calls.at(-1).args[0], '43')
    assert.deepEqual(
      Array.from(h.state.joined.value, (item) => item.id),
      ['44', '43', '42'],
    )
    offline = true
    await h.state.loadJoined(true)
    assert.equal(h.calls.at(-1).args[0], '42')
    assert.equal(h.state.joined.value.length, 3)
    assert.match(h.state.error.value, /列表暂不可用/)
    assert.equal(h.state.nextJoined.value, '42')
  } finally {
    h.dispose()
  }
})
