import { reactive } from 'vue'
import { ApiRequestError } from '../services/http'
import {
  videoDraftApi,
  videoUploadRequestId,
  type VideoDraftPort,
  type VideoDraft,
  type VideoDraftCapability,
  type VideoDraftUpload,
} from '../services/video-drafts'

/** 所有写意图只留内存，旧账号/旧页面响应不回填；未知结果只显式重试原请求。 */
export function createVideoDraftWorkspace(
  session: { user: { id: string } | null; sessionRevision: number },
  api: VideoDraftPort = videoDraftApi,
  uuid: () => string = videoUploadRequestId,
) {
  const state = reactive({
    /** 当前能力；无效或未开放不展示可成功上传。 */ capability: null as VideoDraftCapability | null,
    /** 本人真实草稿，失败时不替换为空列表。 */ items: [] as VideoDraft[],
    /** 首次/手动读取状态。 */ loading: false,
    /** 当前上传调用。 */ busy: false,
    /** 首次上传的不可变参数，未知结果时保留。 */ pending: null as (VideoDraftUpload & { uncertain: boolean }) | null,
    /** 明确读取或操作错误。 */ error: '',
    /** 安静轮询故障，不闪烁清空整页。 */ syncNote: '',
  })
  let generation = 0,
    mutation = 0,
    failures = 0,
    disposed = false,
    reads = new AbortController()
  const capture = () => ({ generation, user: session.user?.id, revision: session.sessionRevision })
  const valid = (scope: ReturnType<typeof capture>) =>
    !disposed &&
    !!scope.user &&
    scope.generation === generation &&
    scope.user === session.user?.id &&
    scope.revision === session.sessionRevision
  function reset() {
    generation++
    mutation++
    reads.abort()
    reads = new AbortController()
    failures = 0
    Object.assign(state, {
      capability: null,
      items: [],
      loading: false,
      busy: false,
      pending: null,
      error: '',
      syncNote: '',
    })
  }
  async function load(quiet = false) {
    if (disposed || !session.user || state.loading || state.busy) return
    const scope = capture(),
      version = mutation
    state.loading = true
    if (!quiet) state.error = ''
    try {
      if (!state.capability) {
        const capability = await api.capability(reads.signal)
        if (!valid(scope) || version !== mutation) return
        if (
          typeof capability?.enabled !== 'boolean' ||
          capability.maxBytes !== 33554432 ||
          capability.maxSeconds !== 180 ||
          capability.maxDrafts !== 5 ||
          capability.reservedBytesPerDraft !== 100663296
        )
          throw new Error('视频上传能力响应无效。')
        state.capability = capability
      }
      if (!state.capability.enabled) return
      const rows = await api.list(reads.signal)
      if (!valid(scope) || version !== mutation) return
      state.items = rows
      failures = 0
      state.syncNote = ''
    } catch (failure) {
      if (!valid(scope) || version !== mutation) return
      if (failure instanceof ApiRequestError && failure.status === 404 && !state.capability) {
        state.capability = {
          enabled: false,
          maxBytes: 33554432,
          maxSeconds: 180,
          maxDrafts: 5,
          reservedBytesPerDraft: 100663296,
        }
        return
      }
      if (quiet) {
        if (++failures >= 3) state.syncNote = '视频状态暂时无法同步，可手动刷新；不会自动重传文件。'
      } else state.error = failure instanceof Error ? failure.message : '视频草稿读取失败，请重试。'
    } finally {
      if (valid(scope)) state.loading = false
    }
  }
  async function submit() {
    const input = state.pending
    if (!input || state.busy || !session.user || disposed) return
    const scope = capture()
    mutation++
    state.busy = true
    state.error = ''
    try {
      const row = await api.upload(input)
      if (!valid(scope)) return
      if (row.requestId !== input.requestId) throw new Error('原请求结果尚未确认。')
      state.items = [row, ...state.items.filter((item) => item.id !== row.id)].slice(0, 5)
      state.pending = null
    } catch (failure) {
      if (!valid(scope)) return
      if (
        !input.uncertain &&
        failure instanceof ApiRequestError &&
        [400, 409, 413, 429].includes(failure.status) &&
        ['BAD_REQUEST', 'CONFLICT', 'VIDEO_TOO_LARGE', 'ASSET_UPLOAD_BUSY'].includes(failure.code ?? '')
      ) {
        state.pending = null
        state.error = failure.message
      } else {
        input.uncertain = true
        state.error = '上传结果尚未确认。请刷新列表核对或重试原上传，不要更换文件和资料。'
      }
    } finally {
      if (valid(scope)) state.busy = false
    }
  }
  async function upload(file: File | null, title: string, description: string) {
    if (!state.capability?.enabled || !session.user || state.busy || state.pending || disposed) return
    if (
      !file ||
      file.size < 16 ||
      file.size > state.capability.maxBytes ||
      !title.trim() ||
      title.trim().length > 100 ||
      description.trim().length > 1000
    ) {
      state.error = '请选择32MiB以内的MP4，标题1～100字，说明最多1000字。'
      return
    }
    state.pending = { requestId: uuid(), file, title: title.trim(), description: description.trim(), uncertain: false }
    await submit()
  }
  return {
    state,
    load,
    upload,
    retry: submit,
    reset,
    dispose() {
      reset()
      disposed = true
    },
  }
}
