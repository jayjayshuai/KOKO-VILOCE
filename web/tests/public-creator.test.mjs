import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import * as vue from 'vue'

const source = await readFile(new URL('../src/composables/public-creator.ts', import.meta.url), 'utf8')
async function execute(text, mocks) {
  const context = vm.createContext({ AbortController, Error })
  const module = new vm.SourceTextModule(
    ts.transpileModule(text, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } })
      .outputText,
    { context },
  )
  await module.link((name) => {
    const values = mocks[name]
    if (!values) throw new Error('Unconfigured import ' + name)
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
const profile = (slug = 'author-one', userId = '10') => ({
  slug,
  userId,
  displayName: '作者',
  headline: '',
  bio: '真实简介\n第二行',
  status: 'ACTIVE',
  version: 1,
  followerCount: 0,
})
const relation = (followedByMe = false, creatorId = '10') => ({
  creatorId,
  followerCount: followedByMe ? 1 : 0,
  followedByMe,
})
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
const tick = async () => {
  await vue.nextTick()
  for (let i = 0; i < 5; i++) await Promise.resolve()
}
async function fixture(network, initial = {}) {
  const hooks = [],
    calls = [],
    ctx = vue.reactive({ slug: 'author-one', userId: '20', sessionRevision: 1, ...initial })
  const api = new Proxy(network, {
    get(target, name) {
      if (typeof target[name] !== 'function') throw new Error('Unconfigured API ' + String(name))
      return (...args) => {
        calls.push({ name, args })
        return target[name](...args)
      }
    },
  })
  const { usePublicCreator } = await execute(source, {
    vue: { ...vue, onBeforeUnmount: (callback) => hooks.push(callback) },
    '../api': { api },
  })
  const state = usePublicCreator(() => ({ ...ctx }), api)
  return { state, calls, ctx, dispose: () => hooks.forEach((fn) => fn()) }
}
test('公开主页匿名和本人只读ACTIVE事实，不请求私人关系或自动关注', async () => {
  for (const userId of [undefined, '10']) {
    const f = await fixture({ publicCreator: async () => profile() }, { userId })
    await tick()
    assert.equal(f.state.profile.value.bio, '真实简介\n第二行')
    assert.equal(f.state.relation.value, null)
    await f.state.toggleFollow()
    assert.deepEqual(
      f.calls.map((c) => c.name),
      ['publicCreator'],
    )
    f.dispose()
  }
})
test('非法slug不联网，错地址、私人投影和错误ID/计数不能当公开主页', async () => {
  const bad = await fixture({}, { slug: '../../me' })
  await tick()
  assert.equal(bad.calls.length, 0)
  assert.match(bad.state.error.value, /地址无效/)
  bad.dispose()
  for (const change of [
    { status: 'DRAFT' },
    { slug: 'another-author' },
    { userId: '9223372036854775808' },
    { followerCount: -1 },
    { version: '1' },
    { bio: null },
  ]) {
    const f = await fixture({ publicCreator: async () => ({ ...profile(), ...change }) })
    await tick()
    assert.equal(f.state.profile.value, null)
    assert.match(f.state.error.value, /未核实/)
    assert.equal(f.calls.length, 1)
    f.dispose()
  }
})
test('关注事实GET失败不默认未关注，单独重读后才可提交', async () => {
  let fails = true
  const f = await fixture({
    publicCreator: async () => profile(),
    followState: async () => {
      if (fails) throw new Error('GET unavailable')
      return relation()
    },
    followCreator: async () => relation(true),
  })
  await tick()
  assert.ok(f.state.profile.value)
  assert.equal(f.state.relation.value, null)
  await f.state.toggleFollow()
  assert.equal(
    f.calls.some((c) => c.name === 'followCreator'),
    false,
  )
  fails = false
  await f.state.readRelation()
  await f.state.toggleFollow()
  assert.equal(f.state.relation.value.followedByMe, true)
  assert.equal(f.state.success.value, '关注已确认。')
  f.dispose()
})
test('原关注回应丢失锁住重复提交，错误GET不解锁，有效时点事实才恢复', async () => {
  let getMode = 'initial'
  const f = await fixture({
    publicCreator: async () => profile(),
    followState: async () => (getMode === 'wrong' ? relation(true, '99') : relation(getMode === 'confirmed')),
    followCreator: async () => {
      throw new Error('reply lost')
    },
  })
  await tick()
  await f.state.toggleFollow()
  assert.equal(f.state.uncertain.value, true)
  await f.state.toggleFollow()
  await f.state.toggleFollow()
  assert.equal(f.calls.filter((c) => c.name === 'followCreator').length, 1)
  getMode = 'wrong'
  await f.state.readRelation()
  assert.equal(f.state.uncertain.value, true)
  getMode = 'confirmed'
  await f.state.readRelation()
  assert.equal(f.state.uncertain.value, false)
  assert.equal(f.state.relation.value.followedByMe, true)
  assert.equal(f.calls.filter((c) => c.name === 'followCreator').length, 1)
  f.dispose()
})
test('取消关注使用DELETE对应方法，错目标/错误回应不伪造成功', async () => {
  let target = '10'
  const f = await fixture({
    publicCreator: async () => profile(),
    followState: async () => relation(true),
    unfollowCreator: async () => relation(false, target),
  })
  await tick()
  target = '99'
  await f.state.toggleFollow()
  assert.equal(f.state.uncertain.value, true)
  assert.equal(f.state.relation.value.followedByMe, true)
  await f.state.readRelation()
  target = '10'
  await f.state.toggleFollow()
  assert.equal(f.state.success.value, '取消关注已确认。')
  assert.equal(f.state.relation.value.followedByMe, false)
  f.dispose()
})
test('切slug取消原GET，旧回应不覆盖新作者', async () => {
  const pending = deferred()
  const f = await fixture({
    publicCreator: async (slug) => (slug === 'author-one' ? pending.promise : profile(slug, '30')),
    followState: async (id) => relation(false, id),
  })
  const originalSignal = f.calls[0].args[1]
  f.ctx.slug = 'author-two'
  await tick()
  assert.equal(originalSignal.aborted, true)
  assert.equal(f.state.profile.value.userId, '30')
  pending.resolve(profile())
  await tick()
  assert.equal(f.state.profile.value.userId, '30')
  f.dispose()
})
test('同账号新登录隔离旧关注GET，卸载后晚到回包不改变新事实', async () => {
  const pending = deferred()
  let reads = 0
  const f = await fixture({
    publicCreator: async () => profile(),
    followState: async () => (++reads === 1 ? pending.promise : relation(true)),
  })
  await tick()
  const oldSignal = f.calls.find((c) => c.name === 'followState').args[1]
  f.ctx.sessionRevision++
  await tick()
  assert.equal(oldSignal.aborted, true)
  assert.equal(f.state.relation.value.followedByMe, true)
  pending.resolve(relation(false))
  await tick()
  assert.equal(f.state.relation.value.followedByMe, true)
  f.dispose()
  await f.state.load()
  assert.equal(f.calls.filter((c) => c.name === 'publicCreator').length, 2)
})
test('路由变化时旧写回应不得解锁新流程或向新作者写入', async () => {
  const pending = deferred()
  const f = await fixture({
    publicCreator: async (slug) => profile(slug, slug === 'author-one' ? '10' : '30'),
    followState: async (id) => relation(false, id),
    followCreator: async () => pending.promise,
  })
  await tick()
  const writing = f.state.toggleFollow()
  await f.state.toggleFollow()
  f.ctx.slug = 'author-two'
  await tick()
  pending.resolve(relation(true))
  await writing
  assert.equal(f.state.profile.value.userId, '30')
  assert.equal(f.state.relation.value.followedByMe, false)
  assert.equal(f.state.success.value, '')
  assert.deepEqual(
    f.calls.filter((c) => c.name === 'followCreator').map((c) => c.args[0]),
    ['10'],
  )
  f.dispose()
})
test('公开主页和本人关系GET均带取消/no-store，slug做路径编码', async () => {
  const calls = []
  const { api } = await execute(await readFile(new URL('../src/api.ts', import.meta.url), 'utf8'), {
    './services/http': {
      apiBaseUrl: '/koko-api',
      ApiRequestError: Error,
      request: async (...args) => calls.push(args),
    },
  })
  const signal = new AbortController().signal
  await api.publicCreator('author/one', signal)
  await api.followState('10', signal)
  assert.equal(calls[0][0], '/discovery/creators/author%2Fone')
  assert.equal(calls[1][0], '/creators/10/follow')
  for (const [, options] of calls) {
    assert.equal(options.signal, signal)
    assert.equal(options.cache, 'no-store')
  }
})
