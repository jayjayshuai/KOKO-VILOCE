<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, shallowRef, watch } from 'vue';
import { useVoiceConnection } from '../composables/voice-connection';
import { Headphones, Mic, MicOff } from 'lucide-vue-next';
import { voiceMediaCredentialsApi, type ControlledMediaCredential } from '../services/voice-media-credentials';
import type { InteractionSnapshot } from '../services/voice-interaction';
import VoicePlaybackControls from './VoicePlaybackControls.vue';
import { waitForVoiceMediaRetirement } from '../services/voice-media-preparation';
import type { ScopedVoiceCallStatus } from '../stores/voice-call';
const emit = defineEmits<{ state: [value: ScopedVoiceCallStatus] }>();
const props = defineProps<{
  /** 当前已核验的房间。 */ roomId: string;
  /** 展示标题，不参与授权。 */ roomTitle?: string;
  /** 当前登录用户。 */ userId: string;
  /** 同账号重登录也改变该轮次。 */ sessionRevision: number;
  /** 当前本人授权快照，失权/过期为空。 */ snapshot: InteractionSnapshot | null;
  /** 当前读取成功且页面在线可见，未知写入期间false。 */ fresh: boolean;
  /** 只阻止新连接，不使已有通话因定期读取而断开。 */ reading?: boolean;
  /** 用户点击后核验最新快照，可抢占静默同步；不发送业务命令。 */ prepareConnection?: () => Promise<boolean>;
  /** 独立房间页使用声音控件标题，不再提示弹窗步骤。 */ inRoom?: boolean;
  /** 明确一键发言，父级编排入房/申请，不能绕过当前授权。 */ quickStart?: () => Promise<void>;
  /** 明确仅收听，取消未消费的设备意图。 */ listenOnly?: () => Promise<void>;
  /** 串行编排反馈，后台轮询不复用。 */ quickBusy?: boolean;
  /** 仅取消释放过程互斥；发言准备不能阻止用户取消设备意图。 */ listenBusy?: boolean;
}>();
/** 仅在内存持有当前授权投影，断开后清除JWT引用。 */
const credential = shallowRef<ControlledMediaCredential | null>(null);
/** 仅凭据接口候选配置，不声明RTP或正式环境就绪。 */
const enabled = ref(false),
  /** 本人能力查询在途，换登录轮次会取消。 */
  loading = ref(false),
  /** 查询故障独立显示，不当作已经关闭或成功连接。 */
  capabilityError = ref(''),
  /** 当前SDK音频元素附着点，卸载时由连接composable清除。 */
  audioRoot = ref<HTMLElement | null>(null);
let disposed = false,
  capabilityRevision = 0,
  read = new AbortController();
/** 用户明确连接操作的独立轮次，取消或换身份后旧读取不得重新连接。 */
let connectRevision = 0;
/** 前置核验也必须显示反馈，不能静默吞掉用户点击。 */
const connectionPreparing = ref(false);
const mySeat = computed(() =>
  props.snapshot?.seats.find((seat) => seat.state === 'ON_MIC' && seat.userId === props.userId),
);
const contextCurrent = () =>
  !disposed && enabled.value && props.fresh && props.snapshot?.roomId === props.roomId && !!props.snapshot.mySessionId;
const grantCurrent = () =>
  contextCurrent() &&
  (!credential.value ||
    (credential.value.sessionId === props.snapshot?.mySessionId &&
      credential.value.seatNo === (mySeat.value?.seatNo ?? null) &&
      credential.value.canPublish === (!!mySeat.value && !mySeat.value.muted)));
/** 由响应props读取身份；切登录轮次时基本composable立即取消并清理旧SDK。 */
const session = {
  get user() {
    return props.userId ? { id: props.userId } : null;
  },
  get sessionRevision() {
    return props.sessionRevision;
  },
};
const voice = useVoiceConnection(
  session,
  async (id, signal) => {
    const snapshot = props.snapshot,
      revision = props.sessionRevision,
      user = props.userId;
    if (!enabled.value || !contextCurrent() || !snapshot?.mySessionId) throw new Error('请先核验本人互动会话');
    // Vue快照可能原地更新；跨await只保存不可变标量，不能拿同一个对象的新值核验旧回复。
    const expectedSession = snapshot.mySessionId,
      expectedVersion = snapshot.version;
    await waitForVoiceMediaRetirement(id, signal);
    if (
      disposed ||
      !contextCurrent() ||
      props.sessionRevision !== revision ||
      props.userId !== user ||
      props.snapshot?.mySessionId !== expectedSession ||
      props.snapshot?.version !== expectedVersion
    )
      throw new Error('房间权限在准备期间变化，请重新核验后连接语音');
    const result = await voiceMediaCredentialsApi.issue(id, expectedSession, expectedVersion, signal);
    if (
      disposed ||
      !contextCurrent() ||
      props.sessionRevision !== revision ||
      props.userId !== user ||
      props.snapshot?.mySessionId !== expectedSession ||
      props.snapshot?.version !== expectedVersion
    )
      throw new Error('媒体请求所属快照已变化');
    if (
      result.sessionId !== expectedSession ||
      typeof result.generation !== 'string' ||
      !/^[1-9][0-9]{0,18}$/.test(result.generation) ||
      BigInt(result.generation) > 9223372036854775807n ||
      (result.seatNo !== null && (!Number.isInteger(result.seatNo) || result.seatNo < 1 || result.seatNo > 8)) ||
      typeof result.canPublish !== 'boolean' ||
      result.expiresInSeconds !== 120 ||
      result.seatNo !== (mySeat.value?.seatNo ?? null) ||
      result.canPublish !== (!!mySeat.value && !mySeat.value.muted)
    )
      throw new Error('媒体凭据与当前麦位不一致');
    credential.value = result;
    return result;
  },
  audioRoot,
  undefined,
  { enabled: true, current: grantCurrent, canPublish: () => !!credential.value?.canPublish },
);
const {
  phase,
  voiceError,
  microphoneEnabled,
  microphoneBusy,
  participantCount,
  receivedAudioTracks,
  audioPlaybackBlocked,
  audioPlaybackBusy,
  audioPlaybackError,
} = voice;
/** 只向常驻条传递实际SDK投影，不传递设备、SDK或凭据。 */
watch(
  () => ({
    roomId: props.roomId,
    userId: props.userId,
    sessionRevision: props.sessionRevision,
    phase: phase.value,
    microphoneEnabled: microphoneEnabled.value,
    microphoneBusy: microphoneBusy.value,
    participantCount: participantCount.value,
    canToggleMicrophone: phase.value === 'connected' && !!credential.value?.canPublish && grantCurrent(),
  }),
  (value) => emit('state', value),
  { immediate: true },
);
async function disconnect() {
  connectRevision++;
  connectionPreparing.value = false;
  credential.value = null;
  await voice.leaveVoiceRoom();
}
/** 已有明确发言意图才能调用；幂等开启而非翻转，不绕过设备或房主权限。 */
async function enableMicrophone() {
  if (disposed || phase.value !== 'connected' || !credential.value?.canPublish || !grantCurrent()) return false;
  if (!microphoneEnabled.value) await voice.toggleMicrophone();
  return microphoneEnabled.value;
}
/** 用户选择只收听，设备许可在途时取消整轮连接，防止迟到采集复活。 */
async function disableMicrophone() {
  if (microphoneBusy.value) await disconnect();
  else if (microphoneEnabled.value) await voice.toggleMicrophone();
}
async function loadCapability() {
  if (disposed || loading.value) return;
  read.abort();
  read = new AbortController();
  const revision = ++capabilityRevision;
  loading.value = true;
  capabilityError.value = '';
  try {
    const result = await voiceMediaCredentialsApi.capability(read.signal);
    if (disposed || revision !== capabilityRevision) return;
    if (typeof result.enabled !== 'boolean') throw new Error('媒体能力响应无效');
    enabled.value = result.enabled;
  } catch (error) {
    if (!disposed && revision === capabilityRevision) {
      enabled.value = false;
      capabilityError.value = error instanceof Error ? error.message : '媒体能力读取失败';
    }
  } finally {
    if (!disposed && revision === capabilityRevision) loading.value = false;
  }
}
async function connect() {
  if (disposed || connectionPreparing.value || phase.value !== 'idle') return;
  const revision = ++connectRevision,
    room = props.roomId,
    user = props.userId,
    session = props.sessionRevision;
  const current = () =>
    !disposed &&
    revision === connectRevision &&
    room === props.roomId &&
    user === props.userId &&
    session === props.sessionRevision;
  connectionPreparing.value = true;
  voiceError.value = '';
  try {
    if (loading.value)
      await new Promise<void>((resolve) => {
        const stop = watch(
          () => [loading.value, connectionPreparing.value],
          () => {
            if (!loading.value || !connectionPreparing.value) {
              stop();
              resolve();
            }
          },
          { flush: 'sync' },
        );
        if (!loading.value || !connectionPreparing.value) {
          stop();
          resolve();
        }
      });
    if (!current()) return;
    if (!enabled.value || props.reading || !contextCurrent()) {
      voiceError.value = props.reading
        ? '房间状态正在读取，请待核验结束后连接。'
        : '请先加入房间并核验当前权限，再连接语音。';
      return;
    }
    const verified = props.prepareConnection ? await props.prepareConnection() : true;
    await nextTick();
    if (!current()) return;
    if (!verified || !contextCurrent()) {
      voiceError.value = '本次入房权限核验未通过，请查看房间提示后重新连接。';
      return;
    }
    await voice.joinVoiceRoom({
      id: props.roomId,
      title: props.roomTitle || '当前房间',
      slug: '',
      owner: '',
      maxParticipants: 100,
      status: 'OPEN',
      controlled: true,
    });
  } catch {
    if (current()) voiceError.value = '本次声音连接未确认，请检查房间状态和网络后重新连接。';
  } finally {
    if (current()) connectionPreparing.value = false;
  }
}
watch(
  () => [
    enabled.value,
    props.roomId,
    props.userId,
    props.sessionRevision,
    props.snapshot?.mySessionId,
    props.fresh,
    mySeat.value?.seatNo,
    mySeat.value?.muted,
  ],
  () => {
    if (!grantCurrent()) {
      if (connectionPreparing.value) voiceError.value = '房间权限在核验期间变化，已取消本次声音连接。';
      void disconnect();
    }
  },
  { flush: 'sync' },
);
watch(
  () => [props.roomId, props.userId, props.sessionRevision],
  () => {
    connectRevision++;
    connectionPreparing.value = false;
    capabilityRevision++;
    read.abort();
    loading.value = false;
    enabled.value = false;
    credential.value = null;
    void loadCapability();
  },
  { immediate: true },
);
watch(
  phase,
  (value) => {
    if (value === 'idle') credential.value = null;
  },
  { flush: 'sync' },
);
onBeforeUnmount(() => {
  disposed = true;
  connectRevision++;
  connectionPreparing.value = false;
  capabilityRevision++;
  read.abort();
  credential.value = null;
});
/** 房间页只编排一次明确进入；不开放设备或凭据引用给父级。 */
defineExpose({
  connect,
  disconnect,
  enableMicrophone,
  disableMicrophone,
  toggleMicrophone: voice.toggleMicrophone,
  connected: () => phase.value === 'connected',
});
</script>
<template>
  <section
    aria-label="受控语音连接"
    class="workspace-card voice-console"
    :class="{ 'in-room': inRoom, 'is-publishing': microphoneEnabled }"
  >
    <div class="voice-console-heading"
      ><h3>{{ inRoom ? '你的声音' : '3 · 连接声音' }}</h3
      ><span v-if="inRoom && enabled" class="microphone-indicator"
        ><Mic v-if="microphoneEnabled" :size="16" /><MicOff v-else :size="16" />{{
          microphoneEnabled ? '麦克风已开启' : '麦克风关闭'
        }}</span
      ></div
    >
    <p v-if="capabilityError" role="alert" class="form-error">{{ capabilityError }}</p>
    <button v-if="capabilityError" type="button" class="secondary" :disabled="loading" @click="loadCapability"
      >重新核验媒体能力</button
    >
    <p v-if="loading" role="status">正在核验声音服务，请稍候…</p>
    <p v-else-if="!enabled" role="status">受控语音连接尚未开放，成员和麦位操作不会自动开启麦克风。</p>
    <template v-else>
      <p class="voice-connection-status" role="status">{{
        connectionPreparing && phase === 'idle'
          ? '正在核验入房权限…'
          : phase === 'connected'
            ? `已连接 · 当前媒体成员 ${participantCount}`
            : phase === 'joining'
              ? '正在准备并连接声音…'
              : phase === 'reconnecting'
                ? '正在恢复连接…'
                : '未连接'
      }}</p>
      <div v-if="inRoom && quickStart && listenOnly" class="voice-quick-controls">
        <button
          class="primary"
          :class="{ 'is-publishing': microphoneEnabled }"
          :disabled="quickBusy || microphoneBusy || connectionPreparing"
          @click="microphoneEnabled ? voice.toggleMicrophone() : quickStart()"
        >
          <MicOff v-if="microphoneEnabled" :size="20" /><Mic v-else :size="20" />{{
            microphoneEnabled ? '关闭麦克风' : quickBusy ? '正在准备发言…' : '开始发言'
          }}
        </button>
        <button class="secondary" :disabled="listenBusy" @click="listenOnly()"
          ><Headphones :size="20" />{{ quickBusy && !microphoneEnabled ? '取消发言并收听' : '只收听' }}</button
        >
      </div>
      <p v-if="!snapshot?.mySessionId" class="hint">{{
        inRoom && quickStart ? '选择“开始发言”或“只收听”，会为你加入并连接房间。' : '请先加入房间，才能连接声音。'
      }}</p>
      <p v-else-if="phase === 'idle'" class="hint">{{
        inRoom && quickStart
          ? '点击“开始发言”，系统会自动入房、申请并连接；选择“只收听”不会开启麦克风。'
          : '点击“连接语音”开始收听。只有麦位已授权且解除闭麦后，才能开启麦克风。'
      }}</p>
      <p v-else-if="phase === 'connected' && !credential?.canPublish" class="hint">{{
        inRoom && quickStart
          ? '当前仅收听。点击“开始发言”会申请麦位；房主允许后自动接通，首次需要浏览器麦克风授权。'
          : '已连接，当前仅收听。要发声，请在发言控制区选择“我要发言”，取得麦位并解除闭麦，然后重新连接。'
      }}</p>
      <p v-else-if="phase === 'connected' && !microphoneEnabled" class="hint">{{
        inRoom && quickStart
          ? '房主已允许发言。点击“开始发言”，并允许浏览器使用麦克风即可。'
          : '发言权限已就绪，但麦克风还没开启。点击“开启麦克风”，并允许浏览器访问麦克风。'
      }}</p>
      <p v-else-if="microphoneEnabled" role="status"
        >麦克风已开启，正在向房间发布音轨。对方还需要连接语音并允许声音播放。</p
      >
      <p v-if="reading && (!inRoom || phase !== 'connected')" class="hint"
        >正在核验房间状态，请稍候再连接；定期读取不会主动中断已有通话。</p
      >
      <p v-if="phase === 'connected' && participantCount <= 1" role="status"
        >目前只有你连接了声音，请让对方也进入房间并连接语音。</p
      >
      <p v-else-if="phase === 'connected' && receivedAudioTracks === 0" role="status"
        >对方已连接，但尚未收到对方音轨。请让对方取得发言权限并开启麦克风。</p
      >
      <p v-else-if="phase === 'connected'" class="hint"
        >已订阅
        {{ receivedAudioTracks }} 条对方音轨（不等于已听见）。若仍无声，请检查媒体音量、蓝牙输出及声音播放提示。</p
      >
      <template v-if="!inRoom || !quickStart || !listenOnly"
        ><button
          type="button"
          class="primary"
          :disabled="connectionPreparing || reading || !contextCurrent() || phase !== 'idle'"
          @click="connect"
          >连接语音</button
        >
        <button
          type="button"
          class="secondary"
          :disabled="phase === 'idle' && !connectionPreparing"
          @click="disconnect"
          >{{ connectionPreparing && phase === 'idle' ? '取消连接' : '断开语音' }}</button
        >
        <button
          type="button"
          class="secondary"
          :disabled="phase !== 'connected' || microphoneBusy || !credential?.canPublish || !grantCurrent()"
          @click="voice.toggleMicrophone"
          >{{ microphoneEnabled ? '关闭麦克风' : '开启麦克风' }}</button
        >
      </template>
      <details class="voice-connection-details"
        ><summary>连接信息与后台说明</summary>
        <p class="hint"
          >切换站内页面会保留通话。下麦、房主禁麦或授权失效会断开；手机锁屏可能被系统冻结，需要回到页面重新核验。</p
        >
        <p v-if="credential" class="hint"
          >当前授权轮次 {{ credential.generation }} · {{ credential.canPublish ? '允许本人麦克风发布' : '仅收听' }}</p
        >
      </details>
      <p v-if="voiceError" role="alert" class="form-error">{{ voiceError }}</p>
      <VoicePlaybackControls
        :phase="phase"
        :blocked="audioPlaybackBlocked"
        :busy="audioPlaybackBusy"
        :error="audioPlaybackError"
        @resume="voice.resumeVoiceAudio"
      />
      <div ref="audioRoot" aria-hidden="true" />
    </template>
  </section>
</template>
<style scoped>
.voice-quick-controls {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin: 16px 0;
}
.voice-console.in-room {
  border-color: #534774;
  background: linear-gradient(135deg, #1c1d2d, #171c28);
}
.voice-console-heading {
  display: flex;
  justify-content: space-between;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}
.voice-console-heading h3 {
  margin: 0;
}
.microphone-indicator {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #bec6d5;
  padding: 6px 10px;
  border: 1px solid #4b4860;
  border-radius: 999px;
}
.is-publishing .microphone-indicator {
  color: #9be3c8;
  border-color: #3b7c68;
}
.voice-connection-status {
  color: #c9c3df;
}
.voice-quick-controls button {
  min-height: 48px;
  padding: 12px 22px;
  font-size: 15px;
}
.voice-quick-controls .primary.is-publishing {
  background: #b64158;
  border-color: #dc6b7e;
}
.voice-quick-controls .primary.is-publishing:hover {
  background: #ca5068;
}
.voice-connection-details {
  margin-top: 14px;
  border-top: 1px solid #403c51;
  padding-top: 12px;
}
.voice-connection-details summary {
  cursor: pointer;
  color: #bcb6ce;
  font-size: 12px;
}
@media (max-width: 600px) {
  .voice-quick-controls button {
    flex: 1 1 120px;
    justify-content: center;
    padding: 12px;
  }
}
</style>
