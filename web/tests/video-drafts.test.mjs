import assert from 'node:assert/strict'
import test from 'node:test'
import * as vue from 'vue'
import { executeTs } from './helpers/execute-ts.mjs'

class ApiRequestError extends Error {
  constructor(message, status, code = null) {
    super(message)
    this.status = status
    this.code = code
  }
}
const id = '36f1fb46-91cd-4f32-b174-55b0c5d4bf17',
  requestId = '46f1fb46-91cd-4f32-b174-55b0c5d4bf17'
const capability = {
  enabled: true,
  deletionEnabled: true,
  maxBytes: 33554432,
  maxSeconds: 180,
  maxDrafts: 5,
  reservedBytesPerDraft: 100663296,
}
const row = (changes = {}) => ({
  id,
  requestId,
  title: '合成视频',
  description: '',
  state: 'QUEUED',
  sourceBytes: 1024,
  outputBytes: null,
  durationMillis: null,
  width: null,
  height: null,
  failureCode: null,
  createdAtUtc: '2026-10-17T00:00:00',
  updatedAtUtc: '2026-10-17T00:00:00',
  ...changes,
})
const file = { size: 1024, name: 'synthetic.mp4' }
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
async function setup(overrides = {}) {
  const session = vue.reactive({ user: { id: '42' }, sessionRevision: 1 }),
    calls = []
  const api = {
    capability: async () => capability,
    list: async () => [],
    upload: async () => row(),
    read: async () => row(),
    delete: async () => row({ state: 'DELETING', deleteRequestId: requestId }),
    ...overrides,
  }
  const observed = Object.fromEntries(
    Object.entries(api).map(([key, fn]) => [
      key,
      (...args) => {
        calls.push({ key, args })
        return fn(...args)
      },
    ]),
  )
  const module = await executeTs('composables/video-drafts.ts', {
    vue,
    '../services/http': { ApiRequestError },
    '../services/video-drafts': { videoDraftApi: observed, videoUploadRequestId: () => requestId },
  })
  const workspace = module.createVideoDraftWorkspace(session, observed, () => requestId)
  await workspace.load()
  return { workspace, session, calls }
}
test('未开放或旧服务404不发上传，不冒充空列表已核验', async () => {
  for (const check of [
    async () => ({ ...capability, enabled: false }),
    async () => {
      throw new ApiRequestError('old', 404)
    },
  ]) {
    const h = await setup({ capability: check })
    await h.workspace.upload(file, 'title', '')
    assert.equal(
      h.calls.some((c) => c.key === 'upload' || c.key === 'list'),
      false,
    )
  }
})
test('能力契约损坏时禁止上传，文件大小和资料上限在提交前校验', async () => {
  const invalid = await setup({ capability: async () => ({ ...capability, maxBytes: 999999999 }) })
  await invalid.workspace.upload(file, 'x', '')
  assert.match(invalid.workspace.state.error, /能力响应无效/)
  assert.equal(invalid.calls.length, 1)
  for (const [input, title, description] of [
    [null, 'x', ''],
    [{ size: 33554433 }, 'x', ''],
    [file, ' ', ''],
    [file, 'x', 'a'.repeat(1001)],
  ]) {
    const h = await setup()
    await h.workspace.upload(input, title, description)
    assert.equal(h.calls.filter((c) => c.key === 'upload').length, 0)
    assert.match(h.workspace.state.error, /请选择/)
  }
})
test('未知上传保留同一UUID、文件和资料，只允许显式原请求重试且不重复列表', async () => {
  let fail = true
  const h = await setup({
    upload: async () => {
      if (fail) throw new ApiRequestError('timeout', 0)
      return row()
    },
  })
  await h.workspace.upload(file, ' 合成视频 ', '')
  assert.equal(h.workspace.state.pending.uncertain, true)
  await h.workspace.upload({ size: 32 }, '其他文件', '')
  fail = false
  await h.workspace.retry()
  const writes = h.calls.filter((c) => c.key === 'upload')
  assert.equal(writes.length, 2)
  assert.equal(writes[0].args[0].requestId, writes[1].args[0].requestId)
  assert.equal(writes[0].args[0].file, writes[1].args[0].file)
  assert.equal(h.workspace.state.items.length, 1)
  assert.equal(h.workspace.state.pending, null)
})
test('未知上传之后代理409不能丢弃原请求，明确未受理的参数错误允许修正', async () => {
  let first = true
  const h = await setup({
    upload: async () => {
      if (first) {
        first = false
        throw new ApiRequestError('lost', 0)
      }
      throw new ApiRequestError('conflict', 409, 'CONFLICT')
    },
  })
  await h.workspace.upload(file, 'x', '')
  await h.workspace.retry()
  assert.equal(h.workspace.state.pending.uncertain, true)
  const rejected = await setup({
    upload: async () => {
      throw new ApiRequestError('invalid', 400, 'BAD_REQUEST')
    },
  })
  await rejected.workspace.upload(file, 'x', '')
  assert.equal(rejected.workspace.state.pending, null)
  assert.equal(rejected.workspace.state.error, 'invalid')
})
test('同账号重登和页面卸载使旧上传无效，不把原账号草稿写给新登录', async () => {
  const wait = deferred(),
    h = await setup({ upload: () => wait.promise })
  const pending = h.workspace.upload(file, 'x', '')
  h.session.sessionRevision++
  h.workspace.reset()
  wait.resolve(row())
  await pending
  assert.equal(h.workspace.state.items.length, 0)
  assert.equal(h.workspace.state.pending, null)
  h.workspace.dispose()
  await h.workspace.load()
  assert.equal(h.calls.filter((c) => c.key === 'list').length, 1)
})
test('上传前开始的列表晚到不能覆盖上传确认结果', async () => {
  const wait = deferred()
  let slow = false
  const h = await setup({ list: () => (slow ? wait.promise : Promise.resolve([])) })
  slow = true
  const read = h.workspace.load()
  await h.workspace.upload(file, 'x', '')
  wait.resolve([])
  await read
  assert.equal(h.workspace.state.items[0].id, id)
})
test('后台失败保留真实列表、操作错误及待核验原请求，不反复闪红字', async () => {
  let fail = false
  const h = await setup({
    list: async () => {
      if (fail) throw new Error('network')
      return [row()]
    },
  })
  h.workspace.state.error = '原操作提示'
  fail = true
  await h.workspace.load(true)
  assert.equal(h.workspace.state.syncNote, '')
  await h.workspace.load(true)
  await h.workspace.load(true)
  assert.match(h.workspace.state.syncNote, /手动刷新/)
  assert.equal(h.workspace.state.items.length, 1)
  assert.equal(h.workspace.state.error, '原操作提示')
})
test('损坏READY产物不能得到预览URL，源文件和对象键不出现在URL', async () => {
  const module = await executeTs('services/video-drafts.ts', {
    './http': { apiBaseUrl: '/koko-api', request: async () => [] },
    './chat': { messageUuid: () => requestId },
  })
  assert.throws(() => module.videoDraft(row({ state: 'READY' })), /产物尚未/)
  assert.throws(() => module.videoDraft(row({ sourceBytes: 33554433 })), /状态响应无效/)
  assert.equal(module.videoPreviewUrl(id), `/koko-api/assets/videos/${id}/content`)
  assert.throws(() => module.videoPreviewUrl('../private'), /ID无效/)
  assert.equal(
    module.videoDraft(row({ state: 'READY', outputBytes: 2048, durationMillis: 2000, width: 1280, height: 720 })).state,
    'READY',
  )
})

test('未知删除保持原UUID，不根据列表缺失或404假报清理；只显式重试原请求', async () => {
  let fail = true,
    missing = false
  const h = await setup({
    list: async () => (missing ? [] : [row()]),
    read: async () => {
      throw new ApiRequestError('not found', 404)
    },
    delete: async () => {
      if (fail) throw new ApiRequestError('lost', 0)
      return row({ state: 'DELETING', deleteRequestId: requestId })
    },
  })
  await h.workspace.remove(h.workspace.state.items[0])
  assert.ok(h.workspace.state.pendingDeletion)
  missing = true
  await h.workspace.load()
  assert.ok(h.workspace.state.pendingDeletion)
  assert.equal(h.workspace.state.notice, '')
  await h.workspace.upload(file, 'another', '')
  assert.equal(
    h.calls.some((c) => c.key === 'upload'),
    false,
  )
  fail = false
  await h.workspace.retryDeletion()
  assert.equal(h.workspace.state.pendingDeletion, null)
  assert.ok(h.workspace.state.trackedDeletion)
  assert.match(h.workspace.state.notice, /完成后才会释放/)
  const writes = h.calls.filter((c) => c.key === 'delete')
  assert.equal(writes.length, 2)
  assert.deepEqual(writes[0].args, writes[1].args)
})

test('删除轮询需要本人原DELETED回执才显示额度回收，受理不等于完成', async () => {
  let receipt = row({ state: 'DELETING', deleteRequestId: requestId }),
    listed = [row()]
  const h = await setup({ list: async () => listed, read: async () => receipt })
  await h.workspace.remove(h.workspace.state.items[0])
  assert.equal(h.workspace.state.items.length, 1)
  assert.equal(h.workspace.state.items[0].state, 'DELETING')
  listed = []
  receipt = row({ state: 'DELETED', deleteRequestId: requestId })
  await h.workspace.load(true)
  assert.equal(h.workspace.state.items.length, 0)
  assert.equal(h.workspace.state.trackedDeletion, null)
  assert.match(h.workspace.state.notice, /额度已回收/)
})

test('账号轮次变化后迟到删除回执不能修改新账号，旧列表不能覆盖删除', async () => {
  const wait = deferred()
  const h = await setup({ list: async () => [row()], delete: () => wait.promise })
  const removing = h.workspace.remove(h.workspace.state.items[0])
  h.session.sessionRevision++
  h.workspace.reset()
  wait.resolve(row({ state: 'DELETED', deleteRequestId: requestId }))
  await removing
  assert.equal(h.workspace.state.items.length, 0)
  assert.equal(h.workspace.state.notice, '')
  assert.equal(h.workspace.state.pendingDeletion, null)
})

test('旧服务没有删除门禁时不提交删除；伪造DELETED或不匹配回执不清理原意图', async () => {
  const old = await setup({
    capability: async () => ({ ...capability, deletionEnabled: undefined }),
    list: async () => [row()],
  })
  await old.workspace.remove(old.workspace.state.items[0])
  assert.equal(
    old.calls.some((c) => c.key === 'delete'),
    false,
  )
  const mismatch = await setup({
    list: async () => [row()],
    delete: async () => row({ state: 'DELETED', deleteRequestId: '56f1fb46-91cd-4f32-b174-55b0c5d4bf17' }),
  })
  await mismatch.workspace.remove(mismatch.workspace.state.items[0])
  assert.ok(mismatch.workspace.state.pendingDeletion)
  assert.equal(mismatch.workspace.state.items.length, 1)
  assert.equal(mismatch.workspace.state.notice, '')
})

test('删除API固定UUID且不携带对象键，损坏墓碑/等待原因被拒绝', async () => {
  const calls = []
  const module = await executeTs('services/video-drafts.ts', {
    './http': {
      apiBaseUrl: '/koko-api',
      request: async (path, options) => {
        calls.push({ path, options })
        return row({ state: 'DELETING', deleteRequestId: requestId })
      },
    },
    './chat': { messageUuid: () => requestId },
  })
  await module.videoDraftApi.delete(id, requestId)
  assert.equal(calls[0].path, `/assets/videos/${id}/deletion`)
  assert.deepEqual(JSON.parse(calls[0].options.body), { requestId })
  assert.throws(() => module.videoDraft(row({ state: 'DELETED' })), /删除状态/)
  assert.throws(() => module.videoDraft(row({ deletionWaitReason: 'vendor-secret' })), /删除状态/)
  assert.equal(module.videoDraft(row()).deleteRequestId, null)
})
