<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useVoiceInteractionWorkspace } from '../composables/voice-interaction-workspace';
import VoiceMediaPlanPanel from './VoiceMediaPlanPanel.vue';
import ControlledVoiceMediaPanel from './ControlledVoiceMediaPanel.vue';
import type { VoiceCommandType, VoiceCommand, SeatRequest } from '../services/voice-interaction';
import { arriveInVoiceRoom, ensureVoiceRoomMembership, runVoiceSpeakingFlow } from '../services/voice-room-entry';
import type { ScopedVoiceCallStatus } from '../stores/voice-call';
const props = defineProps<{
  /** 仅展示用名称快照，权限仍绑定roomId。 */ roomTitle?: string;
  /** 本次操作房间。 */ roomId: string;
  /** 当前可信账号，HTTP仍用Cookie。 */ userId: string;
  /** 本次登录轮次。 */ sessionRevision: number;
  /** 参数化房间页布局；旧管理入口保留原布局。 */ presentation?: 'room';
  /** 同身份、同轮次的一次明确导航点击，刷新默认false。 */ automaticEntry?: boolean;
  /** 仅本次明确入口的设备意图，刷新/重新登录不能继承。 */ entryMode?: 'listen' | 'speak';
  /** 缓存切页时禁止首次自动开启设备，回到房间页才继续等待中的意图。 */ active?: boolean;
}>();
const emit = defineEmits<{
  /** 不撤销后台未知写入。 */ close: [];
  /** 父页面禁止并行操作。 */ busy: [value: boolean];
  /** 仅确认离房才清除恢复入口。 */ left: [];
  /** 实际SDK状态，供常驻条和后台策略使用。 */ 'media-state': [value: ScopedVoiceCallStatus];
}>();
/** 只延续已经连接的后台通话，不是自动入房或开设备许可。 */
const backgroundEnabled = ref(true),
  mediaConnected = ref(false);
const state = useVoiceInteractionWorkspace({
  get roomId() {
    return props.roomId;
  },
  get userId() {
    return props.userId;
  },
  get sessionRevision() {
    return props.sessionRevision;
  },
  get keepAlive() {
    return backgroundEnabled.value && mediaConnected.value;
  },
});
function mediaState(value: ScopedVoiceCallStatus) {
  if (value.roomId !== props.roomId || value.userId !== props.userId || value.sessionRevision !== props.sessionRevision)
    return;
  mediaConnected.value = value.phase === 'connected' || value.phase === 'reconnecting';
  if (lastMediaMic && !value.microphoneEnabled && !speechBusy.value)
    speechNotice.value = '麦克风已关闭，可选择只收听或重新开始发言。';
  lastMediaMic = value.microphoneEnabled;
  emit('media-state', value);
}
const { actions, actionLoading, actionError, loadActions } = state;
const {
  capabilities,
  snapshot,
  loading,
  busy,
  readError,
  readTransient,
  writeError,
  rejectionNotice,
  renewalError,
  renewalTransient,
  pending,
  success,
  load,
  prepareAction,
  actionPreparing,
  join,
  command,
  transmit,
  discard,
  fresh,
  lastVerifiedAt,
  online,
  checkReceipt,
} = state;
/** 目标只来自服务器名单，服务端仍当前读授权。 */ const target = ref('');
/** 房主管理与在麦态不混用。 */ const manager = computed(
  () => fresh.value && !!snapshot.value?.mySessionId && ['OWNER', 'ADMIN'].includes(snapshot.value.myRole || ''),
);
const owner = computed(() => fresh.value && !!snapshot.value?.mySessionId && snapshot.value.myRole === 'OWNER');
/** 保守判定待确认操作是否影响本人媒体；管理明确的其他目标不应切断自己的通话。 */
const pendingAffectsMedia = computed(() => {
  const attempt = pending.value;
  if (!attempt) return false;
  if (attempt.kind !== 'COMMAND') return true;
  const input = attempt.input;
  if (['MUTE', 'KICK', 'LOCK'].includes(input.type)) {
    const seat = snapshot.value?.seats.find((item) => item.seatNo === input.seatNo);
    return !seat || seat.userId === props.userId || (!seat.userId && input.type !== 'LOCK');
  }
  if (['INVITE', 'PULL'].includes(input.type)) return !input.targetUserId || input.targetUserId === props.userId;
  if (['ACCEPT', 'REJECT', 'CANCEL'].includes(input.type)) {
    const request = snapshot.value?.requests.find((item) => item.id === input.seatRequestId);
    return !request || request.userId === props.userId;
  }
  return true;
});
/** 高级进度只在用户展开时读取，不为普通通话反复请求后台诊断。 */
const showMediaPlan = ref(false);
/** 仅切换使用引导，不授予发布权限，也不自动操作麦克风。 */
const intent = ref<'listen' | 'speak'>('listen');
/** 只暴露连接控制，不获取JWT或开启设备。 */
const mediaPanel = ref<InstanceType<typeof ControlledVoiceMediaPanel> | null>(null);
/** 入房串行且保留真实失败。 */
const arrivalBusy = ref(false),
  arrivalError = ref('');
/** 一次显式发言意图及串行编排，不等于服务器发布授权。 */
const speechConsent = ref(false),
  speechBusy = ref(false),
  /** 只收听取消与设备释放在途时，不能并行重新开启设备。 */
  listenBusy = ref(false),
  speechNotice = ref('');
/** 取消/离房令在途编排失效，不能恢复旧设备意图。 */
let speechRevision = 0;
/** 仅消除失效的成功提示，不作为权限事实。 */
let lastMediaMic = false;
const foreground = () =>
  props.active !== false && (typeof document === 'undefined' || document.visibilityState !== 'hidden');
/** 成功恢复连接后清除旧入房失败，不清除业务未知请求或当前设备错误。 */
watch(
  () => mediaPanel.value?.connected(),
  (connected) => {
    if (connected) arrivalError.value = '';
  },
);
let disposed = false;
async function enterAndListen() {
  if (disposed || arrivalBusy.value || pending.value || busy.value) return;
  const scope = [props.roomId, props.userId, props.sessionRevision] as const;
  const current = () =>
    !disposed && scope[0] === props.roomId && scope[1] === props.userId && scope[2] === props.sessionRevision;
  arrivalBusy.value = true;
  arrivalError.value = '';
  try {
    const connected = await arriveInVoiceRoom({
      load,
      current,
      ready: () => fresh.value && online.value && !!capabilities.value?.enabled,
      joined: () => !!snapshot.value?.mySessionId,
      pending: () => !!pending.value,
      prepare: prepareAction,
      join,
      connect: async () => {
        await nextTick();
        if (current()) await mediaPanel.value?.connect();
      },
      connected: () => mediaPanel.value?.connected() === true,
    });
    if (current() && !connected)
      arrivalError.value = '尚未完成声音连接，请查看下方具体提示；不会自动重发未知入房请求。';
  } catch {
    if (current()) arrivalError.value = '入房未完成，请核验房间状态后重试。';
  } finally {
    if (current()) arrivalBusy.value = false;
  }
}
onMounted(() => {
  if (typeof document !== 'undefined') document.addEventListener('visibilitychange', resumeSpeakingWhenAllowed);
  if (props.presentation === 'room' && props.automaticEntry) {
    if (props.entryMode === 'speak') void startSpeaking();
    else void enterAndListen();
  }
});
onBeforeUnmount(() => {
  disposed = true;
  speechConsent.value = false;
  speechRevision++;
  if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', resumeSpeakingWhenAllowed);
});
/** 本人麦位与空位均来自已核验快照，不从界面选择推断权限。 */
const mySeat = computed(() =>
  snapshot.value?.seats.find((seat) => seat.state === 'ON_MIC' && seat.userId === props.userId),
);
const freeSeat = computed(() => snapshot.value?.seats.find((seat) => seat.state === 'EMPTY'));
/** 本人仍在等待的申请，防止新手重复提交新请求。 */
const myApplication = computed(() =>
  snapshot.value?.requests.find((request) => request.type === 'APPLY' && request.userId === props.userId),
);
/** 未确认原请求或当前权限时不新建命令。 */ const disabled = computed(
  () => !fresh.value || !online.value || loading.value || busy.value || actionPreparing.value || !!pending.value,
);
/** 固定状态名称，不声称已经真实发声。 */ const labels = {
  EMPTY: '空麦',
  LOCKED: '已锁',
  RESERVED: '预约中',
  ON_MIC: '在麦状态 · 非音轨确认',
};
watch(
  () => busy.value || actionPreparing.value,
  (value) => emit('busy', value),
  { flush: 'sync' },
);
watch(
  () => [snapshot.value?.members, manager.value, props.userId] as const,
  ([members]) => {
    if (target.value && !members?.some((member) => member.userId === target.value)) target.value = '';
    if (!target.value && manager.value && members?.some((member) => member.userId === props.userId))
      target.value = props.userId;
  },
  { flush: 'sync' },
);
/** 新操作发送前核验多人房最新版本；不重试任何已发送/结果未知的请求。 */
async function execute(
  type: VoiceCommandType,
  args: Partial<Pick<VoiceCommand, 'seatNo' | 'targetUserId' | 'seatRequestId' | 'value'>> = {},
) {
  if (disabled.value) return;
  const scope = [props.roomId, props.userId, props.sessionRevision] as const;
  const input = { ...args };
  if (
    ['KICK', 'TRANSFER', 'ADMIN'].includes(type) &&
    !confirm('确认修改房间权限或席位？撤权会清退旧媒体连接，不能由此远程开启他人的麦克风。')
  )
    return;
  if (!(await prepareAction())) return;
  if (disabled.value || scope[0] !== props.roomId || scope[1] !== props.userId || scope[2] !== props.sessionRevision)
    return;
  return command(type, input);
}
async function leaveRoomPage() {
  speechConsent.value = false;
  speechRevision++;
  speechBusy.value = false;
  // 先停止本人媒体，不把等待HTTP回包当成继续采集/播放的理由。
  await mediaPanel.value?.disconnect();
  await execute('LEAVE');
  if (!disposed && fresh.value && !pending.value && !snapshot.value?.mySessionId) {
    emit('left');
    emit('close');
  }
}
/** 管理操作在核验后按目标成员重新定位席位，不能因换位禁错人。 */
async function setMemberSpeaking(userId: string, allowed: boolean) {
  if (disabled.value || !manager.value || (allowed && !owner.value)) return;
  const scope = [props.roomId, props.userId, props.sessionRevision] as const;
  if (
    !(await prepareAction()) ||
    disabled.value ||
    !manager.value ||
    (allowed && !owner.value) ||
    scope[0] !== props.roomId ||
    scope[1] !== props.userId ||
    scope[2] !== props.sessionRevision
  )
    return;
  const seat = snapshot.value?.seats.find((item) => item.state === 'ON_MIC' && item.userId === userId);
  if (!seat || seat.muted === !allowed) return;
  return command('MUTE', { seatNo: seat.seatNo, value: !allowed });
}
/** 入房也是一次用户明确写入，先读最新能力，失败/换身份不发送。 */
async function joinRoom() {
  if (disabled.value) return;
  const scope = [props.roomId, props.userId, props.sessionRevision] as const;
  if (!(await prepareAction())) return;
  if (
    disabled.value ||
    snapshot.value?.mySessionId ||
    scope[0] !== props.roomId ||
    scope[1] !== props.userId ||
    scope[2] !== props.sessionRevision
  )
    return;
  void join();
}
/** 新手上麦在核验后重新选择席位/角色，不能提交点击前已过时的空位。 */
async function requestSpeaking() {
  if (disabled.value) return;
  const scope = [props.roomId, props.userId, props.sessionRevision] as const;
  if (!(await prepareAction())) return;
  if (
    disabled.value ||
    scope[0] !== props.roomId ||
    scope[1] !== props.userId ||
    scope[2] !== props.sessionRevision ||
    !snapshot.value?.mySessionId ||
    mySeat.value ||
    myApplication.value ||
    !freeSeat.value
  )
    return;
  return command(manager.value ? 'PULL' : 'APPLY', {
    seatNo: freeSeat.value.seatNo,
    ...(manager.value ? { targetUserId: props.userId } : {}),
  });
}
/** 一个点击完成入房/申请/连接；普通成员只等待房主，不自动批准自己。 */
async function startSpeaking() {
  if (
    disposed ||
    speechBusy.value ||
    listenBusy.value ||
    arrivalBusy.value ||
    busy.value ||
    pending.value ||
    !foreground()
  )
    return;
  intent.value = 'speak';
  speechConsent.value = true;
  const revision = ++speechRevision;
  await speakingFlow(revision);
}
async function speakingFlow(revision: number) {
  if (speechBusy.value) return;
  const scope = [props.roomId, props.userId, props.sessionRevision] as const;
  const current = () =>
    !disposed &&
    revision === speechRevision &&
    foreground() &&
    scope[0] === props.roomId &&
    scope[1] === props.userId &&
    scope[2] === props.sessionRevision;
  const ready = () =>
    fresh.value && online.value && !loading.value && !busy.value && !pending.value && !!snapshot.value?.mySessionId;
  speechBusy.value = true;
  speechNotice.value = '正在加入房间并准备发言…';
  try {
    const result = await runVoiceSpeakingFlow({
      current,
      ready,
      join: () =>
        ensureVoiceRoomMembership({
          load,
          current,
          ready: () => fresh.value && online.value && !!capabilities.value?.enabled,
          joined: () => !!snapshot.value?.mySessionId,
          pending: () => !!pending.value,
          prepare: prepareAction,
          join,
        }),
      hasSeat: () => !!mySeat.value,
      hasRequest: () => !!myApplication.value,
      owner: () => owner.value,
      muted: () => !!mySeat.value?.muted,
      takeSeat: async () => {
        speechNotice.value = '正在申请发言…';
        await requestSpeaking();
      },
      permit: async () => {
        if (mySeat.value) await execute('MUTE', { seatNo: mySeat.value.seatNo, value: false });
      },
      connect: async () => {
        speechNotice.value = '正在连接语音…';
        await nextTick();
        if (current()) await mediaPanel.value?.connect();
      },
      connected: () => mediaPanel.value?.connected() === true,
      consumeConsent: () => {
        speechConsent.value = false;
      },
      enableMicrophone: async () => {
        speechNotice.value = '请允许浏览器访问麦克风…';
        if (!current()) return false;
        return (await mediaPanel.value?.enableMicrophone()) === true;
      },
    });
    if (current()) {
      speechNotice.value =
        result === 'speaking'
          ? '麦克风已开启，可以直接说话。'
          : result === 'waiting'
            ? mySeat.value || myApplication.value
              ? '已连接收听，等待房主允许后会自动开始发言。'
              : '已连接收听，暂无空麦位；有空位后请再次点击开始发言。'
            : '尚未完成发言，请查看连接或麦克风提示，再点击开始发言重试。';
      if (result !== 'waiting' || (!mySeat.value && !myApplication.value)) speechConsent.value = false;
    }
  } catch {
    if (current()) {
      speechConsent.value = false;
      speechNotice.value = '发言准备未完成，请核验房间状态后重试。';
    }
  } finally {
    if (revision === speechRevision) speechBusy.value = false;
  }
}
/** 等待审批不重复申请；只有仍在前台、当前快照已允许时消费一次设备意图。 */
function resumeSpeakingWhenAllowed() {
  if (
    !disposed &&
    speechConsent.value &&
    !speechBusy.value &&
    !listenBusy.value &&
    !disabled.value &&
    foreground() &&
    mySeat.value &&
    !mySeat.value.muted
  )
    void speakingFlow(speechRevision);
}
watch(
  () => [
    speechConsent.value,
    speechBusy.value,
    fresh.value,
    loading.value,
    busy.value,
    pending.value,
    mySeat.value?.muted,
    props.active,
    listenBusy.value,
  ],
  resumeSpeakingWhenAllowed,
);
async function listenOnly() {
  if (disposed || listenBusy.value) return;
  const revision = ++speechRevision;
  listenBusy.value = true;
  intent.value = 'listen';
  speechConsent.value = false;
  speechBusy.value = false;
  speechNotice.value = '仅收听，不会自动打开麦克风。';
  try {
    await mediaPanel.value?.disableMicrophone();
    if (disposed || revision !== speechRevision) return;
    if (myApplication.value && !disabled.value) await execute('CANCEL', { seatRequestId: myApplication.value.id });
    if (!disposed && revision === speechRevision && !pending.value && !busy.value) await enterAndListen();
  } finally {
    if (revision === speechRevision) listenBusy.value = false;
  }
}
/** 房主一次同意完成安排麦位和许可；部分失败仍保留真实状态/未知请求。 */
async function approveSpeaking(request: SeatRequest) {
  const ack = await execute('ACCEPT', { seatRequestId: request.id });
  if (ack?.type === 'ACCEPT' && !disposed && owner.value && !disabled.value)
    await setMemberSpeaking(request.userId, true);
}
function canReview(request: SeatRequest) {
  return request.type === 'APPLY' ? manager.value : request.userId === props.userId;
}
/** 常驻条只代理本面板，不创建额外SDK。 */
defineExpose({
  startSpeaking,
  leave: leaveRoomPage,
  connect: async () => {
    await mediaPanel.value?.connect();
  },
  disconnect: async () => {
    await mediaPanel.value?.disconnect();
  },
  toggleMicrophone: async () => {
    await mediaPanel.value?.toggleMicrophone();
  },
});
</script>
<template>
  <section
    class="workspace-card voice-interaction"
    :class="{ 'room-layout': presentation === 'room' }"
    aria-label="语音房互动核心"
    :aria-busy="loading || busy"
  >
    <div class="workspace-card-heading"
      ><h2>{{ presentation === 'room' ? '房间控制' : '进入语音房' }}</h2
      ><button type="button" class="secondary" :disabled="busy || actionPreparing" @click="emit('close')">{{
        presentation === 'room' ? '返回列表' : '关闭面板'
      }}</button></div
    >
    <p class="hint">{{ roomTitle || '当前房间' }}</p>
    <p class="hint" role="note"
      >手机与电脑请用不同账号；建议戴耳机，避免回声。支持八人同时发言；“只收听”不采集麦克风，“加入并发言”会请求授权。</p
    >
    <div class="voice-sync-status" role="status" aria-live="polite" aria-atomic="true">
      <span v-if="!online">网络已离线，声音连接及新操作已暂停；恢复后请核验房间状态。</span>
      <span v-else-if="readError && readTransient"
        >房间同步暂时失败，正在退避重试；当前权限未确认，新操作和声音连接已暂停。</span
      >
      <span v-else-if="snapshot && !fresh">房间状态需要重新核验，已暂停新操作；请刷新房间状态。</span>
      <span v-else-if="renewalError && renewalTransient"
        >成员续约暂未确认，下一轮会重试；若连接中断，请重新核验房间状态。</span
      >
      <span v-else>成员状态在后台同步，不会重新加载页面。点击操作时才核验最新权限。</span>
    </div>
    <p v-if="readError && !readTransient" class="form-error" role="alert">{{ readError }}</p>
    <p v-if="writeError" class="form-error" role="alert">{{ writeError }}</p>
    <p v-if="rejectionNotice" class="hint" role="status">{{ rejectionNotice }}</p>
    <p v-if="renewalError && !renewalTransient" class="form-error" role="alert">{{ renewalError }}</p>
    <p v-if="success && presentation !== 'room'" role="status">{{ success }}</p>
    <p v-if="arrivalBusy" role="status">正在进入房间并准备声音，请稍候…</p>
    <p v-if="arrivalError" role="alert" class="form-error">{{ arrivalError }}</p>
    <p v-if="speechNotice" class="voice-speech-notice" role="status">{{ speechNotice }}</p>
    <ControlledVoiceMediaPanel
      v-if="presentation === 'room' && capabilities?.enabled"
      ref="mediaPanel"
      :in-room="true"
      :room-id="roomId"
      :room-title="roomTitle"
      :user-id="userId"
      :session-revision="sessionRevision"
      :snapshot="snapshot"
      :fresh="fresh && online && !pendingAffectsMedia"
      :reading="loading"
      :prepare-connection="prepareAction"
      :quick-start="startSpeaking"
      :listen-only="listenOnly"
      :quick-busy="speechBusy || listenBusy || arrivalBusy || busy || actionPreparing"
      :listen-busy="listenBusy"
      @state="mediaState"
    />
    <section v-if="presentation === 'room'" class="room-overview" aria-label="房间成员">
      <div class="workspace-card-heading"
        ><h3>房间成员 · {{ snapshot ? snapshot.members.length : '—' }}（有效租约）</h3>
        <button
          v-if="snapshot?.mySessionId"
          class="secondary"
          :disabled="disabled || arrivalBusy"
          @click="leaveRoomPage"
          >离开房间</button
        >
      </div>
      <p v-if="!snapshot?.mySessionId">你已打开房间页面，点击进入后开始收听。不会自动打开麦克风。</p>
      <button v-if="!snapshot?.mySessionId" class="primary" :disabled="disabled || arrivalBusy" @click="enterAndListen"
        >进入并收听</button
      >
      <div class="room-members"
        ><article v-for="member in snapshot?.members ?? []" :key="member.userId">
          <strong>{{ member.displayName }}</strong
          ><span
            >{{ member.userId === userId ? '你 · ' : ''
            }}{{ member.role === 'OWNER' ? '房主' : member.role === 'ADMIN' ? '房管' : '成员' }}</span
          >
          <small>{{ member.seatNo ? `麦位 ${member.seatNo}` : '听众' }} · 成员身份不代表已连接声音</small>
          <template
            v-if="member.seatNo && snapshot?.seats.find((seat) => seat.seatNo === member.seatNo)?.state === 'ON_MIC'"
          >
            <small>{{
              snapshot?.seats.find((seat) => seat.seatNo === member.seatNo)?.muted
                ? '房间闭麦 · 需房主允许'
                : '房主已允许发言'
            }}</small>
            <button
              v-if="owner"
              class="secondary"
              :disabled="disabled"
              @click="
                setMemberSpeaking(member.userId, !!snapshot?.seats.find((seat) => seat.seatNo === member.seatNo)?.muted)
              "
              >{{
                snapshot?.seats.find((seat) => seat.seatNo === member.seatNo)?.muted ? '允许发言' : '禁止发言'
              }}</button
            >
          </template>
        </article></div
      >
    </section>
    <div v-if="pending" class="workspace-error"
      ><p>原请求结果未确认。重试保持原 UUID、会话及版本，放弃只停止本地等待，不取消后台操作。</p
      ><div class="page-actions"
        ><button type="button" class="secondary" :disabled="busy || !online" @click="checkReceipt"
          >只查询原提交结果</button
        ><button type="button" class="secondary" :disabled="busy || !online" @click="transmit">使用原请求重试</button
        ><button type="button" class="secondary" :disabled="busy || !online" @click="discard"
          >放弃旧请求并重取</button
        ></div
      ></div
    >
    <button type="button" class="secondary" :disabled="busy || actionPreparing || !online" @click="load()">{{
      loading ? '读取中…' : '刷新房间状态'
    }}</button>
    <label class="hint"
      ><input v-model="backgroundEnabled" type="checkbox" />切换浏览器标签页时保留已连接通话（手机系统可能冻结）</label
    >
    <p v-if="capabilities && !capabilities.enabled" role="status">互动核心尚未开放，不能执行成员或麦位操作。</p>
    <template v-if="capabilities?.enabled">
      <section v-if="presentation !== 'room'" class="voice-guide-step" aria-label="加入房间">
        <h3>1 · 加入房间</h3>
        <p v-if="!snapshot?.mySessionId">先加入，才能收听或申请发言。</p>
        <p v-else role="status">已加入房间。接下来选择收听或发言。</p>
        <button v-if="!snapshot?.mySessionId" type="button" class="primary" :disabled="disabled" @click="joinRoom"
          >加入房间</button
        >
        <button v-else type="button" class="secondary" :disabled="disabled" @click="execute('LEAVE')">离开房间</button>
      </section>
      <section v-if="presentation !== 'room'" class="voice-guide-step" aria-label="选择语音方式">
        <h3>{{ presentation === 'room' ? '发言控制' : '2 · 你想做什么？' }}</h3>
        <p class="hint">这里选择收听或发言，不会自动开麦。停止发声请点击声音连接区的“关闭麦克风”。</p>
        <div class="page-actions">
          <button
            type="button"
            :class="intent === 'listen' ? 'primary' : 'secondary'"
            :aria-pressed="intent === 'listen'"
            @click="intent = 'listen'"
            >只收听</button
          >
          <button
            type="button"
            :class="intent === 'speak' ? 'primary' : 'secondary'"
            :aria-pressed="intent === 'speak'"
            @click="intent = 'speak'"
            >我要发言</button
          >
        </div>
        <p v-if="intent === 'listen'"
          >不用申请麦位，在声音连接区点击“连接语音”。若听不到，检查手机音量和“恢复声音播放”。</p
        >
        <template v-else>
          <p v-if="!snapshot?.mySessionId">请先完成第 1 步，加入房间。</p>
          <template v-else-if="mySeat">
            <p v-if="mySeat.muted"
              >你在麦位 {{ mySeat.seatNo }}，房间闭麦中。{{
                owner ? '允许发言后才能开麦。' : '请等待房主允许发言，你不能自行解除禁麦。'
              }}</p
            >
            <p v-else role="status">发言权限已就绪。在声音连接区连接语音，再点击“开启麦克风”。</p>
            <button
              v-if="mySeat.muted && owner"
              type="button"
              class="primary"
              :disabled="disabled"
              @click="execute('MUTE', { seatNo: mySeat.seatNo, value: false })"
              >允许我发言（解除闭麦）</button
            >
            <button
              type="button"
              class="secondary"
              :disabled="disabled"
              @click="execute('DOWN', { seatNo: mySeat.seatNo })"
              >结束发言并下麦</button
            >
          </template>
          <p v-else-if="myApplication" role="status"
            >已申请麦位 {{ myApplication.seatNo }}，等待房主同意；等待期间可以连接语音收听。</p
          >
          <p v-else-if="!freeSeat">暂无空麦位，可以先连接语音收听。</p>
          <template v-else>
            <p>{{ manager ? '你是房主或房管，可以直接上麦。' : '申请后需要房主或房管同意，不能自动开启麦克风。' }}</p>
            <button type="button" class="primary" :disabled="disabled" @click="requestSpeaking">{{
              manager ? '自己上麦' : '申请发言'
            }}</button>
          </template>
        </template>
      </section>
      <ControlledVoiceMediaPanel
        v-if="presentation !== 'room'"
        ref="mediaPanel"
        :in-room="presentation === 'room'"
        :room-id="roomId"
        :room-title="roomTitle"
        :user-id="userId"
        :session-revision="sessionRevision"
        :snapshot="snapshot"
        :fresh="fresh && online && !pendingAffectsMedia"
        :reading="loading"
        :prepare-connection="prepareAction"
        @state="mediaState"
      />
      <details @toggle="showMediaPlan = ($event.target as HTMLDetailsElement).open">
        <summary>高级：媒体授权和退场进度</summary>
        <VoiceMediaPlanPanel
          v-if="showMediaPlan"
          :room-id="roomId"
          :user-id="userId"
          :session-revision="sessionRevision"
          :allowed="fresh && !!snapshot"
        />
      </details>
      <template v-if="snapshot">
        <section
          v-if="manager && snapshot.requests.some((request) => request.type === 'APPLY')"
          aria-label="待处理发言申请"
        >
          <h3>有人申请发言</h3>
          <div
            v-for="request in snapshot.requests.filter((item) => item.type === 'APPLY')"
            :key="request.id"
            class="page-actions"
          >
            <span
              >{{ snapshot.members.find((member) => member.userId === request.userId)?.displayName || '成员' }} · 麦位
              {{ request.seatNo }}</span
            >
            <button type="button" class="primary" :disabled="disabled" @click="approveSpeaking(request)"
              >同意发言</button
            >
            <button
              type="button"
              class="secondary"
              :disabled="disabled"
              @click="execute('REJECT', { seatRequestId: request.id })"
              >拒绝</button
            >
          </div>
        </section>
        <details class="voice-management">
          <summary>高级：麦位、预约和房间管理</summary>
          <p v-if="lastVerifiedAt" class="hint"
            >最近核验：{{ new Date(lastVerifiedAt).toLocaleTimeString() }}（本机时间，非租约期限）</p
          >
          <p class="hint"
            >房间版本 {{ snapshot.version }} · 有效成员 {{ snapshot.members.length }} ·
            90秒租约，面板关闭后停止续约。</p
          >
          <label v-if="manager" class="workspace-field"
            >目标成员<select v-model="target" :disabled="disabled"
              ><option value="">请选择成员</option
              ><option v-for="member in snapshot.members" :key="member.userId" :value="member.userId"
                >{{ member.displayName }} · {{ member.role }}</option
              ></select
            ></label
          >
          <div class="voice-seat-grid"
            ><article v-for="seat in snapshot.seats" :key="seat.seatNo" class="voice-seat-card"
              ><h3>麦位 {{ seat.seatNo }}</h3
              ><p>{{ labels[seat.state] }}</p
              ><small v-if="seat.userId"
                >{{ snapshot.members.find((member) => member.userId === seat.userId)?.displayName || '成员' }} ·
                {{ seat.muted ? '希望闭麦' : '希望开麦' }}</small
              ><div class="page-actions">
                <button
                  v-if="seat.state === 'EMPTY' && snapshot.mySessionId"
                  type="button"
                  class="secondary"
                  :disabled="disabled"
                  @click="execute('APPLY', { seatNo: seat.seatNo })"
                  >申请麦位</button
                >
                <template v-if="manager && seat.state === 'EMPTY'"
                  ><button
                    type="button"
                    class="secondary"
                    :disabled="
                      disabled ||
                      !target ||
                      snapshot.seats.some((occupied) => occupied.state === 'ON_MIC' && occupied.userId === target)
                    "
                    @click="execute('INVITE', { seatNo: seat.seatNo, targetUserId: target })"
                    >邀请</button
                  ><button
                    type="button"
                    class="secondary"
                    :disabled="
                      disabled ||
                      !target ||
                      snapshot.seats.some((occupied) => occupied.state === 'ON_MIC' && occupied.userId === target)
                    "
                    @click="execute('PULL', { seatNo: seat.seatNo, targetUserId: target })"
                    >{{ target === userId ? '自己上麦' : '抱麦' }}</button
                  ></template
                >
                <button
                  v-if="manager && ['EMPTY', 'LOCKED'].includes(seat.state)"
                  type="button"
                  class="secondary"
                  :disabled="disabled"
                  @click="execute('LOCK', { seatNo: seat.seatNo, value: seat.state === 'EMPTY' })"
                  >{{ seat.state === 'EMPTY' ? '锁麦' : '解锁' }}</button
                >
                <button
                  v-if="seat.state === 'ON_MIC' && seat.userId === userId"
                  type="button"
                  class="secondary"
                  :disabled="disabled"
                  @click="execute('DOWN', { seatNo: seat.seatNo })"
                  >下麦</button
                >
                <template v-if="seat.state === 'ON_MIC' && (manager || seat.userId === userId)"
                  ><button
                    type="button"
                    class="secondary"
                    :disabled="disabled || (seat.muted && !owner)"
                    @click="setMemberSpeaking(seat.userId!, seat.muted)"
                    >{{ seat.muted ? '解除闭麦' : '设置闭麦状态' }}</button
                  ><button
                    v-if="manager"
                    type="button"
                    class="secondary"
                    :disabled="disabled"
                    @click="execute('KICK', { seatNo: seat.seatNo })"
                    >踢下麦</button
                  ></template
                >
              </div></article
            ></div
          >
          <section aria-label="本人或管理方可见预约"
            ><h3>预约请求</h3><p v-if="!snapshot.requests.length" class="hint">没有当前可见的有效预约。</p
            ><article v-for="request in snapshot.requests" :key="request.id"
              ><p
                >{{ request.type === 'APPLY' ? '申请' : '邀请' }} · 麦位 {{ request.seatNo }} ·
                {{ snapshot.members.find((member) => member.userId === request.userId)?.displayName || '成员' }}</p
              ><div class="page-actions"
                ><button
                  v-if="canReview(request)"
                  type="button"
                  class="secondary"
                  :disabled="disabled"
                  @click="
                    request.type === 'APPLY'
                      ? approveSpeaking(request)
                      : execute('ACCEPT', { seatRequestId: request.id })
                  "
                  >同意</button
                ><button
                  v-if="manager"
                  type="button"
                  class="secondary"
                  :disabled="disabled"
                  @click="execute('REJECT', { seatRequestId: request.id })"
                  >拒绝</button
                ><button
                  v-if="request.userId === userId"
                  type="button"
                  class="secondary"
                  :disabled="disabled"
                  @click="execute('CANCEL', { seatRequestId: request.id })"
                  >取消预约</button
                ></div
              ></article
            ></section
          >
          <section v-if="owner" aria-label="房间角色管理"
            ><h3>长期房管与房主</h3
            ><div class="page-actions"
              ><button
                type="button"
                class="secondary"
                :disabled="disabled || !target || target === userId"
                @click="execute('ADMIN', { targetUserId: target, value: true })"
                >设为房管</button
              ><button
                type="button"
                class="secondary"
                :disabled="disabled || !target || target === userId"
                @click="execute('TRANSFER', { targetUserId: target })"
                >转让房主</button
              ></div
            ><p v-if="!snapshot.administrators.length" class="hint">尚未设置长期房管。</p
            ><div v-for="admin in snapshot.administrators" :key="admin.userId" class="page-actions"
              ><span>{{ admin.displayName }}（可能离线）</span
              ><button
                type="button"
                class="secondary"
                :disabled="disabled"
                @click="execute('ADMIN', { targetUserId: admin.userId, value: false })"
                >撤销房管</button
              ></div
            ></section
          >
          <section v-if="manager" aria-label="房主管理操作审计">
            <h3>操作审计</h3
            ><button type="button" class="secondary" :disabled="actionLoading || busy" @click="loadActions()"
              >读取房管审计</button
            >
            <p v-if="actionError" class="form-error" role="alert">{{ actionError }}</p>
            <p v-if="actions && !actions.items.length && !actionError" class="hint">本轮未查询到操作记录。</p>
            <ul v-if="actions"
              ><li v-for="action in actions.items" :key="action.version"
                >版本 {{ action.version }} · {{ action.type }} · 操作者 {{ action.actorId || '系统' }} · 目标
                {{ action.targetUserId || '无' }} · 麦位 {{ action.seatNo || '无' }} · 目标值
                {{ action.value === null ? '无' : action.value ? '开启' : '关闭' }} ·
                {{ action.createdAt }}（数据库时间）</li
              ></ul
            >
            <button
              v-if="actions?.nextBefore"
              type="button"
              class="secondary"
              :disabled="actionLoading || busy"
              @click="loadActions(true)"
              >更早审计</button
            >
          </section>
        </details>
      </template>
    </template>
  </section>
</template>
<style scoped>
.voice-interaction {
  margin: 20px 0;
  overflow-wrap: anywhere;
}
.voice-interaction .page-actions {
  margin: 12px 0;
  flex-wrap: wrap;
}
.voice-guide-step {
  margin: 16px 0;
  padding: 16px;
  border: 1px solid var(--border-color, #303541);
  border-radius: 12px;
}
.voice-guide-step h3 {
  margin: 0 0 12px;
}
.room-layout {
  margin-top: 0;
}
/* 同步状态有固定最小空间，短暂网络提示不推动声音按钮上下跳动。 */
.voice-sync-status {
  min-height: 3.5rem;
  display: flex;
  align-items: center;
  padding: 8px 12px;
  margin: 12px 0;
  border-radius: 8px;
  background: var(--surface-subtle, #11151e);
  color: var(--text-muted, #a8aabc);
  font-size: 0.875rem;
  line-height: 1.5;
}
.room-overview {
  padding: 20px 0;
}
.room-members {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 12px;
  margin-top: 16px;
}
.room-members article {
  display: grid;
  gap: 8px;
  padding: 18px;
  border: 1px solid var(--border-color, #303541);
  border-radius: 12px;
}
.room-members span,
.room-members small {
  color: var(--text-muted, #a8aabc);
}
.voice-management summary {
  cursor: pointer;
  padding: 12px 0;
}
.voice-seat-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin: 20px 0;
}
@media (min-width: 1200px) {
  .voice-seat-grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}
.voice-seat-card {
  padding: 16px;
  border: 1px solid var(--border-color, #303541);
  border-radius: 12px;
}
.voice-seat-card h3 {
  margin: 0 0 10px;
}
@media (max-width: 640px) {
  .voice-seat-grid {
    grid-template-columns: 1fr;
  }
}
</style>
