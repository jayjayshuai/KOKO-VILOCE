import { nextTick, onBeforeUnmount, ref, shallowRef, watch, type Ref } from 'vue'
import type { Room as LiveKitRoom, RemoteTrack } from 'livekit-client'
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
  /** SDK确认的浏览器播放门禁；不是房间内确有音轨或已收到RTP的证明。 */
  const audioPlaybackBlocked = ref(false)
  /** 本人明确点击后的单次播放恢复，不能重复触发或请求发布设备。 */
  const audioPlaybackBusy = ref(false)
  /** 播放恢复故障独立于连接/麦克风错误，不暴露SDK凭据或地址。 */
  const audioPlaybackError = ref('')
  /** 仅客户端当前媒体在线数，不当全站精确人数。 */
  const participantCount = ref(0)
  /** 本连接已订阅的远端音频轨数，不表示有声能量、已播放或已经听见。 */
  const receivedAudioTracks = ref(0)
  /** SDK实际连接阶段；恢复中不显示成已稳定连接。 */
  const phase = ref<'idle' | 'joining' | 'connected' | 'reconnecting'>('idle')
  /** SDK引用只在内存中保存，pending也立即可定向断开。 */
  const connection = shallowRef<LiveKitRoom | null>(null)
  /** 本轮附加的音频元素，未挂载时保留到Vue更新后，卸载全部移除。 */
  const audio = new Set<HTMLMediaElement>()
  /** SDK可能在取消订阅事件前已detach，保留本人元素映射以可靠释放DOM。 */
  const trackAudio = new Map<RemoteTrack, Set<HTMLMediaElement>>()
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
  /** 移除DOM不会保证停止声音；先暂停并断开MediaStream，再释放元素引用。 */
  const removeAudio = (element: HTMLMediaElement) => {
    let released = true
    // 单个浏览器元素异常不能阻断其余元素回收或随后SDK断开。
    for (const release of [() => element.pause(), () => (element.srcObject = null), () => element.remove()]) {
      try {
        release()
      } catch {
        released = false
      }
    }
    return released
  }
  const clearAudio = () => {
    let released = true
    for (const element of audio) if (!removeAudio(element)) released = false
    audio.clear()
    trackAudio.clear()
    receivedAudioTracks.value = 0
    return released
  }
  const message = (cause: unknown) => (cause instanceof Error ? cause.message : '语音连接或设备操作失败')
  /** 设备错误仅按标准名称映射，SDK的原始message可能包含媒体地址或凭据。 */
  const microphoneMessage = (cause: unknown) => {
    const name = cause instanceof Error ? cause.name : ''
    if (['NotAllowedError', 'PermissionDeniedError', 'SecurityError'].includes(name))
      return '麦克风权限被拒绝，请在浏览器设置中允许当前网站使用麦克风。'
    if (['NotFoundError', 'DevicesNotFoundError'].includes(name)) return '未找到可用麦克风，请连接设备后重试。'
    if (['NotReadableError', 'TrackStartError'].includes(name))
      return '麦克风暂不可读取，请检查系统权限或其他应用占用。'
    return '麦克风状态未确认，请检查设备和浏览器权限后重试。'
  }

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
    audioPlaybackBlocked.value = false
    audioPlaybackBusy.value = false
    audioPlaybackError.value = ''
    participantCount.value = 0
    if (!clearAudio() && !disposed) voiceError.value = '音频元素释放未确认，请关闭页面并检查浏览器声音权限。'
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

  /** 授权失效的旧断开可能等待SDK；迟到反馈不得覆盖随后创建的新连接。 */
  async function disconnectChangedAuthorization() {
    const leaving = leaveVoiceRoom(),
      revision = epoch
    await leaving
    if (!disposed && epoch === revision && !connection.value)
      voiceError.value = ['当前会话授权已变化，已停止使用旧连接。', voiceError.value].filter(Boolean).join(' ')
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
    /** 接口/本站契约错误保留反馈；SDK加载与连接错误只展示固定安全提示。 */
    let failurePhase: 'credential' | 'sdk' | 'connection' = 'credential'
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
      failurePhase = 'sdk'
      const { Room, RoomEvent, Track } = await loadSdk()
      if (!current()) return
      failurePhase = 'connection'
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
      const playback = () => {
        if (!owns()) return
        audioPlaybackBlocked.value = !owned.canPlaybackAudio
        if (!audioPlaybackBlocked.value) audioPlaybackError.value = ''
      }
      room.on(RoomEvent.TrackSubscribed, (track) => {
        if (!owns() || track.kind !== Track.Kind.Audio) return
        const element = track.attach()
        audio.add(element)
        const elements = trackAudio.get(track) ?? new Set<HTMLMediaElement>()
        elements.add(element)
        trackAudio.set(track, elements)
        receivedAudioTracks.value = trackAudio.size
        attach()
      })
      room.on(RoomEvent.TrackUnsubscribed, (track) => {
        if (!owns()) return
        const elements = new Set([...(trackAudio.get(track) ?? []), ...track.detach()])
        trackAudio.delete(track)
        receivedAudioTracks.value = trackAudio.size
        for (const element of elements) {
          audio.delete(element)
          if (!removeAudio(element)) voiceError.value = '音频元素释放未确认，请关闭页面并检查浏览器声音权限。'
        }
      })
      room.on(RoomEvent.ParticipantConnected, count)
      room.on(RoomEvent.ParticipantDisconnected, count)
      room.on(RoomEvent.LocalTrackPublished, microphone)
      room.on(RoomEvent.LocalTrackUnpublished, microphone)
      room.on(RoomEvent.AudioPlaybackStatusChanged, playback)
      room.on(RoomEvent.Reconnecting, () => {
        if (owns()) phase.value = 'reconnecting'
      })
      room.on(RoomEvent.Reconnected, () => {
        if (owns()) {
          phase.value = 'connected'
          count()
          microphone()
          playback()
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
      playback()
      await nextTick()
      if (owns()) attach()
    } catch (cause) {
      if (!current()) return
      const error =
        failurePhase === 'sdk'
          ? '语音组件加载失败，请刷新页面后重试。'
          : failurePhase === 'connection'
            ? '语音媒体连接未建立，请检查网络、房间状态和媒体服务后重新加入。'
            : message(cause)
      if (failurePhase === 'credential' && cause instanceof ApiRequestError && cause.status === 401)
        session.expireSession?.(userId, sessionRevision)
      const leaving = leaveVoiceRoom(),
        leavingRevision = epoch
      await leaving
      if (
        !disposed &&
        epoch === leavingRevision &&
        session.user?.id === userId &&
        session.sessionRevision === sessionRevision
      )
        voiceError.value = [error, voiceError.value].filter(Boolean).join(' ')
    } finally {
      if (current()) voiceJoining.value = null
    }
  }

  /** 必须直接从用户点击调用SDK，保留浏览器手势；不自动索取凭据、重连或打开麦克风。 */
  async function resumeVoiceAudio() {
    const room = connection.value
    if (disposed || !room || phase.value !== 'connected' || audioPlaybackBusy.value || !session.user) return
    const revision = epoch,
      userId = session.user.id,
      sessionRevision = session.sessionRevision
    if (!active(revision, userId, sessionRevision)) {
      await disconnectChangedAuthorization()
      return
    }
    const owns = () => active(revision, userId, sessionRevision) && connection.value === room
    audioPlaybackBusy.value = true
    audioPlaybackError.value = ''
    try {
      // 此前不能await/nextTick，否则一些浏览器会丢失本次明确点击的播放授权。
      await room.startAudio()
      if (owns()) {
        audioPlaybackBlocked.value = !room.canPlaybackAudio
        if (audioPlaybackBlocked.value)
          audioPlaybackError.value = '声音播放仍未确认，请检查浏览器或系统音频权限后重试。'
      }
    } catch {
      if (owns()) {
        audioPlaybackBlocked.value = !room.canPlaybackAudio
        audioPlaybackError.value = '声音播放未确认，请检查浏览器或系统音频权限后重试。'
      }
    } finally {
      if (owns()) audioPlaybackBusy.value = false
    }
  }

  /** 明确点击才请求设备权限；同一轮串行，按SDK事实更新而非布尔反转。 */
  async function toggleMicrophone() {
    const room = connection.value
    if (disposed || !room || phase.value !== 'connected' || microphoneBusy.value || !session.user) return
    if (controlledPolicy && !controlledPolicy.current()) {
      await disconnectChangedAuthorization()
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
        voiceError.value = microphoneMessage(cause)
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
    audioPlaybackBlocked,
    audioPlaybackBusy,
    audioPlaybackError,
    participantCount,
    receivedAudioTracks,
    phase,
    joinVoiceRoom,
    leaveVoiceRoom,
    toggleMicrophone,
    resumeVoiceAudio,
  }
}
