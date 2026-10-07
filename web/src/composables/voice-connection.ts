import { nextTick, onBeforeUnmount, ref, shallowRef, watch, type Ref } from 'vue'
import type { Room as LiveKitRoom } from 'livekit-client'
import { ApiRequestError, type VoiceRoom, type VoiceJoinCredential } from '../api'

/** 本人会话内的真实媒体连接；切换/取消/登出后旧异步结果不能重新入房或开启麦克风。 */
export function useVoiceConnection(
  session: {
    user: { id: string } | null
    sessionRevision: number
    expireSession?: (id: string, revision: number) => void
  },
  credentialApi: (roomId: string, signal?: AbortSignal) => Promise<VoiceJoinCredential>,
  audioRoot: Ref<HTMLElement | null>,
  loadSdk: () => Promise<typeof import('livekit-client')> = () => import('livekit-client'),
  controlledPolicy?: {
    /** 受控调用者使用专属凭据接口，不能回退到原发布JWT。 */ enabled: boolean
    /** 本人会话、麦位及授权快照在各异步边界仍有效。 */ current: () => boolean
    /** 听众不得请求设备发布，即使SDK返回乐观权限也重新核验。 */ canPublish: () => boolean
  },
) {
  /** 当前已成功连接的房间，不把请求受理当媒体入房。 */
  const connectedVoice = ref<VoiceRoom | null>(null)
  /** 当前请求房间，取消时立即清空。 */
  const voiceJoining = ref<string | null>(null)
  /** 用户可见的连接/设备故障，不保存JWT。 */
  const voiceError = ref('')
  /** 本人麦克风实际状态，加入默认关闭，须用户明确点击。 */
  const microphoneEnabled = ref(false)
  /** 防止并行设备切换，失败不盲翻转状态。 */
  const microphoneBusy = ref(false)
  /** 仅客户端当前媒体在线数，不当全站精确人数。 */
  const participantCount = ref(0)
  /** SDK实际连接阶段；恢复中不显示成已稳定连接。 */
  const phase = ref<'idle' | 'joining' | 'connected' | 'reconnecting'>('idle')
  /** SDK引用只在内存中保存，pending也立即可定向断开。 */
  const connection = shallowRef<LiveKitRoom | null>(null)
  /** 本轮附加的音频元素，未挂载时保留到Vue更新后，卸载全部移除。 */
  const audio = new Set<HTMLElement>()
  let epoch = 0,
    disposed = false,
    request = new AbortController()
  const active = (revision: number, userId: string, sessionRevision: number) =>
    !disposed &&
    epoch === revision &&
    session.user?.id === userId &&
    session.sessionRevision === sessionRevision &&
    (!controlledPolicy || controlledPolicy.current())
  const attach = () => {
    for (const element of audio)
      if (audioRoot.value && element.parentElement !== audioRoot.value) audioRoot.value.appendChild(element)
  }
  const clearAudio = () => {
    for (const element of audio) element.remove()
    audio.clear()
  }
  const message = (cause: unknown) => (cause instanceof Error ? cause.message : '语音连接或设备操作失败')

  /** 同步撤销异步轮次，再定向释放本轮SDK；断开失败也不恢复旧UI/令牌。 */
  async function leaveVoiceRoom() {
    epoch++
    request.abort()
    const leavingRevision = epoch
    const old = connection.value
    connection.value = null
    connectedVoice.value = null
    voiceJoining.value = null
    phase.value = 'idle'
    microphoneEnabled.value = false
    microphoneBusy.value = false
    participantCount.value = 0
    clearAudio()
    if (old) {
      old.removeAllListeners()
      try {
        await old.disconnect()
      } catch {
        if (!disposed && epoch === leavingRevision && !connection.value)
          voiceError.value = '媒体断开未确认，请检查浏览器麦克风权限。'
      }
    }
  }

  /** 令牌/SDK/媒体连接各边界重新检查本人轮次，绝不自动开麦。 */
  async function joinVoiceRoom(target: VoiceRoom) {
    if (disposed || !session.user) return
    if (target.controlled && !controlledPolicy?.enabled) {
      voiceError.value = '受控房间媒体授权尚未开放，请使用房间互动面板；不会签发原发布凭据。'
      return
    }
    const expectedUser = session.user.id,
      expectedSession = session.sessionRevision
    const leaving = leaveVoiceRoom(),
      leavingRevision = epoch
    await leaving
    if (!active(leavingRevision, expectedUser, expectedSession)) return
    const revision = ++epoch,
      userId = session.user.id,
      sessionRevision = session.sessionRevision
    const current = () => active(revision, userId, sessionRevision)
    request = new AbortController()
    voiceJoining.value = target.id
    voiceError.value = ''
    phase.value = 'joining'
    let room: LiveKitRoom | null = null
    try {
      // 公网明文页面不签发/传送RTC凭据；浏览器仅环回开发环境允许HTTP。
      const page = globalThis.location
      const loopback = page && ['localhost', '127.0.0.1', '[::1]', '::1'].includes(page.hostname)
      if (page && page.protocol !== 'https:' && !(page.protocol === 'http:' && loopback)) {
        throw new Error('语音连接需要HTTPS安全页面；仅本机环回开发允许HTTP。')
      }
      const credential = await credentialApi(target.id, request.signal)
      if (!current()) return
      const endpoint = new URL(credential.url)
      // 准入代理使用网站Cookie绑定身份；不能把该入口跨源配置成“看起来可用”的SDK连接。
      if (/\/(?:api|koko-api)\/media\/livekit\/?$/.test(endpoint.pathname) && page) {
        const sameProtocol = page.protocol === 'https:' ? 'wss:' : 'ws:'
        const pageUrl = new URL(page.href)
        if (endpoint.protocol !== sameProtocol || endpoint.host !== pageUrl.host)
          throw new Error('媒体准入地址必须与当前网站同源；请检查服务端信令配置。')
      }
      if (
        !['ws:', 'wss:'].includes(endpoint.protocol) ||
        endpoint.username ||
        endpoint.password ||
        endpoint.hash ||
        endpoint.search ||
        (page?.protocol === 'https:' && endpoint.protocol !== 'wss:') ||
        typeof credential.token !== 'string' ||
        !credential.token ||
        credential.token.length > 8192 ||
        typeof credential.roomName !== 'string' ||
        !credential.roomName
      ) {
        throw new Error('媒体凭据或连接地址无效，不建立语音连接。')
      }
      const { Room, RoomEvent, Track } = await loadSdk()
      if (!current()) return
      room = new Room({ adaptiveStream: true, dynacast: true })
      connection.value = room
      const owned = room
      const owns = () => current() && connection.value === owned
      const count = () => {
        if (owns()) participantCount.value = owned.remoteParticipants.size + 1
      }
      const microphone = () => {
        if (owns()) microphoneEnabled.value = owned.localParticipant.isMicrophoneEnabled
      }
      room.on(RoomEvent.TrackSubscribed, (track) => {
        if (!owns() || track.kind !== Track.Kind.Audio) return
        const element = track.attach()
        audio.add(element)
        attach()
      })
      room.on(RoomEvent.TrackUnsubscribed, (track) => {
        if (!owns()) return
        for (const element of track.detach()) {
          audio.delete(element)
          element.remove()
        }
      })
      room.on(RoomEvent.ParticipantConnected, count)
      room.on(RoomEvent.ParticipantDisconnected, count)
      room.on(RoomEvent.LocalTrackPublished, microphone)
      room.on(RoomEvent.LocalTrackUnpublished, microphone)
      room.on(RoomEvent.Reconnecting, () => {
        if (owns()) phase.value = 'reconnecting'
      })
      room.on(RoomEvent.Reconnected, () => {
        if (owns()) {
          phase.value = 'connected'
          count()
          microphone()
        }
      })
      room.on(RoomEvent.Disconnected, () => {
        if (!owns()) return
        void leaveVoiceRoom()
        voiceError.value = '语音连接已断开；登录、房间或媒体凭据可能已变化。请重新核验后加入，不会自动开麦。'
      })
      await room.connect(credential.url, credential.token)
      if (!owns()) {
        room.removeAllListeners()
        await room.disconnect()
        return
      }
      connectedVoice.value = target
      phase.value = 'connected'
      count()
      microphone()
      await nextTick()
      if (owns()) attach()
    } catch (cause) {
      if (!current()) return
      const error = message(cause)
      if (cause instanceof ApiRequestError && cause.status === 401) session.expireSession?.(userId, sessionRevision)
      await leaveVoiceRoom()
      if (!disposed && session.user?.id === userId && session.sessionRevision === sessionRevision)
        voiceError.value = error
    } finally {
      if (current()) voiceJoining.value = null
    }
  }

  /** 明确点击才请求设备权限；同一轮串行，按SDK事实更新而非布尔反转。 */
  async function toggleMicrophone() {
    const room = connection.value
    if (disposed || !room || phase.value !== 'connected' || microphoneBusy.value || !session.user) return
    if (controlledPolicy && !controlledPolicy.current()) {
      await leaveVoiceRoom()
      voiceError.value = '当前会话授权已变化，已断开旧语音连接。'
      return
    }
    if (controlledPolicy && !controlledPolicy.canPublish() && !room.localParticipant.isMicrophoneEnabled) {
      voiceError.value = '当前麦位授权不允许发布，请先重新核验。'
      return
    }
    const revision = epoch,
      userId = session.user.id,
      sessionRevision = session.sessionRevision
    microphoneBusy.value = true
    voiceError.value = ''
    try {
      await room.localParticipant.setMicrophoneEnabled(!room.localParticipant.isMicrophoneEnabled)
      if (active(revision, userId, sessionRevision) && connection.value === room)
        microphoneEnabled.value = room.localParticipant.isMicrophoneEnabled
      else {
        // 设备授权可能晚于离开返回；只关闭旧SDK迟到的轨道，不触碰新房间。
        try {
          await room.localParticipant.setMicrophoneEnabled(false)
          await room.disconnect()
        } catch {
          /* 已离开轮次不覆盖当前反馈。 */
        }
      }
    } catch (cause) {
      if (active(revision, userId, sessionRevision) && connection.value === room) {
        microphoneEnabled.value = room.localParticipant.isMicrophoneEnabled
        voiceError.value = message(cause)
      }
    } finally {
      if (active(revision, userId, sessionRevision)) microphoneBusy.value = false
    }
  }
  watch(
    () => [session.user?.id, session.sessionRevision],
    () => {
      void leaveVoiceRoom()
      voiceError.value = ''
    },
    { flush: 'sync' },
  )
  watch(audioRoot, attach)
  onBeforeUnmount(() => {
    disposed = true
    void leaveVoiceRoom()
  })
  return {
    connectedVoice,
    voiceJoining,
    voiceError,
    microphoneEnabled,
    microphoneBusy,
    participantCount,
    phase,
    joinVoiceRoom,
    leaveVoiceRoom,
    toggleMicrophone,
  }
}
