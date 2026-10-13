import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import * as vue from 'vue'

async function execute(text, mocks) {
  const module = new vm.SourceTextModule(
    ts.transpileModule(text, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } })
      .outputText,
    { context: vm.createContext({ AbortController, Error }) },
  )
  await module.link((name) => {
    const values = mocks[name]
    if (!values) throw new Error('Unconfigured import ' + name)
    return new vm.SyntheticModule(
      Object.keys(values),
      function () {
        for (const [key, value] of Object.entries(values)) this.setExport(key, value)
      },
      { context: module.context },
    )
  })
  await module.evaluate()
  return module.namespace
}
const source = await readFile(new URL('../src/composables/public-author-posts.ts', import.meta.url), 'utf8')
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
  for (let i = 0; i < 6; i++) await Promise.resolve()
}
const post = (id = '30', ownerId = '12', publishedAt = '2026-10-13T22:00:00.123456') => ({
  id,
  ownerId,
  slug: 'post-' + id,
  title: '作品' + id,
  excerpt: '摘要',
  kind: 'ARTICLE',
  publishedAt,
  likeCount: 0,
  commentCount: 0,
  favoriteCount: 0,
})
const slice = (items = [post()], nextCursor = null, ownerId = '12') => ({
  ownerId,
  items,
  size: 12,
  nextCursor,
  timeZone: 'UTC',
})
async function fixture(network, initial = {}) {
  const hooks = [],
    calls = [],
    ctx = vue.reactive({ ownerId: '12', userId: undefined, sessionRevision: 1, ...initial })
  const dates = await execute(
    await readFile(new URL('../src/services/notification-time.ts', import.meta.url), 'utf8'),
    {},
  )
  const api = {
    authorPosts: (...args) => {
      calls.push(args)
      return network(...args)
    },
  }
  const { usePublicAuthorPosts } = await execute(source, {
    vue: { ...vue, onBeforeUnmount: (fn) => hooks.push(fn) },
    '../api': { api },
    '../services/notification-time': dates,
  })
  return {
    state: usePublicAuthorPosts(() => ({ ...ctx }), api),
    ctx,
    calls,
    dispose: () => hooks.forEach((fn) => fn()),
  }
}
test('实际作者API首次只读取12条，明确加载/空态且不伪造总数', async () => {
  const wait = deferred(),
    f = await fixture(() => wait.promise)
  assert.equal(f.state.loading.value, true)
  assert.equal(f.state.loaded.value, false)
  assert.equal(f.calls[0][0], '12')
  assert.equal(f.calls[0][1], undefined)
  wait.resolve(slice([]))
  await tick()
  assert.equal(f.state.loaded.value, true)
  assert.equal(f.state.items.value.length, 0)
  assert.equal(f.state.nextCursor.value, null)
  f.dispose()
})
test('后页失败保留列表和原边界，重试不变第一页，末页不再联网', async () => {
  let fails = true
  const f = await fixture(async (_owner, after) => {
    if (!after) return slice([post('30')], 'cursor_one')
    if (fails) throw new Error('page failed')
    return slice([post('29')])
  })
  await tick()
  await f.state.load(false)
  assert.equal(f.state.items.value.length, 1)
  assert.equal(f.state.nextCursor.value, 'cursor_one')
  fails = false
  await f.state.retry()
  assert.deepEqual(
    f.calls.map((c) => c[1]),
    [undefined, 'cursor_one', 'cursor_one'],
  )
  assert.equal(f.state.items.value.length, 2)
  await f.state.load(false)
  assert.equal(f.calls.length, 3)
  f.dispose()
})
test('错误作者、正文、草稿、错误游标、顺序及无效字段均拒绝，不追加污染', async () => {
  const mutations = [
    slice([post('29', '99')]),
    slice([{ ...post('29'), body: 'private' }]),
    slice([{ ...post('29'), status: 'DRAFT' }]),
    slice([post('29')], 'cursor_one'),
    slice([post('31')]),
    slice([post('29'), post('29')]),
    slice([{ ...post('29'), slug: undefined }]),
    slice([{ ...post('29'), publishedAt: '2026-02-30T22:00:00' }]),
    slice([post('29')], undefined),
  ]
  mutations.at(-1).nextCursor = undefined
  for (const invalid of mutations) {
    const f = await fixture(async (_owner, after) => (after ? invalid : slice([post('30')], 'cursor_one')))
    await tick()
    await f.state.load(false)
    assert.equal(f.state.items.value.length, 1)
    assert.match(f.state.error.value, /未确认/)
    f.dispose()
  }
})
test('同一毫秒保留微秒顺序，相同发布时间用Long ID而非字符串比较', async () => {
  const f = await fixture(async () =>
    slice([
      post('9', '12', '2026-10-13T22:00:00.123456'),
      post('100', '12', '2026-10-13T22:00:00.123455'),
      post('99', '12', '2026-10-13T22:00:00.123455'),
    ]),
  )
  await tick()
  assert.equal(f.state.items.value.length, 3)
  assert.equal(f.state.error.value, '')
  f.dispose()
})
test('读取中重复操作不产生并发页，刷新失败保留已确认列表', async () => {
  const wait = deferred()
  let first = true
  const f = await fixture(async () => {
    if (first) {
      first = false
      return slice()
    }
    return wait.promise
  })
  await tick()
  const pending = f.state.load()
  await f.state.load()
  await f.state.load(false)
  assert.equal(f.calls.length, 2)
  wait.reject(new Error('refresh failed'))
  await pending
  assert.equal(f.state.items.value.length, 1)
  f.dispose()
})
test('切作者中止旧GET，迟到回应和finally不能污染新列表或解锁新读', async () => {
  const old = deferred(),
    fresh = deferred(),
    f = await fixture((owner) => (owner === '12' ? old.promise : fresh.promise))
  const signal = f.calls[0][2]
  f.ctx.ownerId = '13'
  await tick()
  assert.equal(signal.aborted, true)
  old.resolve(slice())
  await tick()
  assert.equal(f.state.loading.value, true)
  assert.equal(f.state.items.value.length, 0)
  fresh.resolve(slice([post('40', '13')], null, '13'))
  await tick()
  assert.equal(f.state.items.value[0].ownerId, '13')
  f.dispose()
})
test('同账号新登录和卸载隔离晚到读取；匿名公开读取无需身份伪造', async () => {
  const wait = deferred()
  let n = 0
  const f = await fixture(() => (++n === 1 ? wait.promise : Promise.resolve(slice())), { userId: '20' })
  const oldSignal = f.calls[0][2]
  f.ctx.sessionRevision++
  await tick()
  wait.resolve(slice([post('99')]))
  await tick()
  assert.equal(oldSignal.aborted, true)
  assert.equal(f.state.items.value[0].id, '30')
  f.dispose()
  await f.state.load()
  assert.equal(f.calls.length, 2)
})
test('路径/游标编码、固定size=12、取消和no-store随真实API传递', async () => {
  const calls = []
  const { api } = await execute(await readFile(new URL('../src/api.ts', import.meta.url), 'utf8'), {
    './services/http': {
      apiBaseUrl: '/koko-api',
      ApiRequestError: Error,
      request: async (...args) => calls.push(args),
    },
  })
  const signal = new AbortController().signal
  await api.authorPosts('12', 'cursor/+=', signal)
  assert.equal(calls[0][0], '/discovery/posts/by-author/12?size=12&after=cursor%2F%2B%3D')
  assert.equal(calls[0][1].signal, signal)
  assert.equal(calls[0][1].cache, 'no-store')
})

test('接口存储时区明确后转换为北京时间，不依赖浏览器本地时区', async () => {
  const dates = await execute(
    await readFile(new URL('../src/services/notification-time.ts', import.meta.url), 'utf8'),
    {},
  )
  const { formatAuthorPostTime } = await execute(
    await readFile(new URL('../src/services/author-post-time.ts', import.meta.url), 'utf8'),
    { './notification-time': dates },
  )
  assert.ok(formatAuthorPostTime('2026-10-13T14:00:00.123456', 'UTC').includes('22:00:00'))
  assert.ok(formatAuthorPostTime('2026-10-13T14:00:00', 'Asia/Shanghai').includes('14:00:00'))
  assert.equal(formatAuthorPostTime('2026-02-30T14:00:00', 'UTC'), '时间未确认')
  assert.equal(formatAuthorPostTime('2026-10-13T14:00:00', null), '时间未确认')
})

test('缺失存储时区或分页时切换时区不被接纳，原列表保持', async () => {
  for (const zone of [undefined, 'Asia/Shanghai']) {
    const f = await fixture(async (_owner, after) =>
      after ? { ...slice([post('29')]), timeZone: zone } : slice([post()], 'cursor_one'),
    )
    await tick()
    await f.state.load(false)
    assert.equal(f.state.items.value.length, 1)
    assert.match(f.state.error.value, /未确认/)
    f.dispose()
  }
})
