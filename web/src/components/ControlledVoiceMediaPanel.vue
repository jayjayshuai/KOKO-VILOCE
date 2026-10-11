<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue';
import { useVoiceConnection } from '../composables/voice-connection';
import { voiceMediaCredentialsApi, type ControlledMediaCredential } from '../services/voice-media-credentials';
import type { InteractionSnapshot } from '../services/voice-interaction';
import VoicePlaybackControls from './VoicePlaybackControls.vue';
import { waitForVoiceMediaRetirement } from '../services/voice-media-preparation';
const props = defineProps<{
  /** 当前已核验的房间。 */ roomId: string;
  /** 展示标题，不参与授权。 */ roomTitle?: string;
  /** 当前登录用户。 */ userId: string;
  /** 同账号重登录也改变该轮次。 */ sessionRevision: number;
  /** 当前本人授权快照，失权/过期为空。 */ snapshot: InteractionSnapshot | null;
  /** 当前读取成功且页面在线可见，未知写入期间false。 */ fresh: boolean;
  /** 只阻止新连接，不使已有通话因定期读取而断开。 */ reading?: boolean;
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
async function disconnect() {
  credential.value = null;
  await voice.leaveVoiceRoom();
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
function connect() {
  if (!enabled.value || props.reading || !contextCurrent()) return;
  void voice.joinVoiceRoom({
    id: props.roomId,
    title: props.roomTitle || '当前房间',
    slug: '',
    owner: '',
    maxParticipants: 100,
    status: 'OPEN',
    controlled: true,
  });
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
    if (!grantCurrent()) void disconnect();
  },
  { flush: 'sync' },
);
watch(
  () => [props.roomId, props.userId, props.sessionRevision],
  () => {
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
  capabilityRevision++;
  read.abort();
  credential.value = null;
});
</script>
<template>
  <section aria-label="受控语音连接" class="workspace-card">
    <h3>3 · 连接声音</h3>
    <p v-if="capabilityError" role="alert" class="form-error">{{ capabilityError }}</p>
    <button v-if="capabilityError" type="button" class="secondary" :disabled="loading" @click="loadCapability"
      >重新核验媒体能力</button
    >
    <p v-if="!enabled" role="status">受控语音连接尚未开放，成员和麦位操作不会自动开启麦克风。</p>
    <template v-else>
      <p role="status">{{
        phase === 'connected'
          ? `已连接 · 当前媒体成员 ${participantCount}`
          : phase === 'joining'
            ? '正在准备并连接声音…'
            : phase === 'reconnecting'
              ? '正在恢复连接…'
              : '未连接'
      }}</p>
      <p v-if="!snapshot?.mySessionId" class="hint">请先点击第 1 步的“加入房间”，才能连接声音。</p>
      <p v-else-if="phase === 'idle'" class="hint"
        >点击“连接语音”开始收听。只有麦位已授权且解除闭麦后，才能开启麦克风。</p
      >
      <p v-else-if="phase === 'connected' && !credential?.canPublish" class="hint"
        >已连接，当前仅收听。要发声，请在第 2 步选择“我要发言”，取得麦位并解除闭麦，然后重新连接。</p
      >
      <p v-else-if="phase === 'connected' && !microphoneEnabled" class="hint"
        >发言权限已就绪，但麦克风还没开启。点击“开启麦克风”，并允许浏览器访问麦克风。</p
      >
      <p v-else-if="microphoneEnabled" role="status"
        >麦克风已开启，正在向房间发布音轨。对方还需要连接语音并允许声音播放。</p
      >
      <p v-if="reading" class="hint">正在核验房间状态，请稍候再连接；定期读取不会主动中断已有通话。</p>
      <p v-if="phase === 'connected' && participantCount <= 1" role="status"
        >目前只有你连接了声音，请让对方也完成第 3 步。</p
      >
      <p v-else-if="phase === 'connected' && receivedAudioTracks === 0" role="status"
        >对方已连接，但尚未收到对方音轨。请让对方取得发言权限并开启麦克风。</p
      >
      <p v-else-if="phase === 'connected'" class="hint"
        >已订阅
        {{ receivedAudioTracks }} 条对方音轨（不等于已听见）。若仍无声，请检查媒体音量、蓝牙输出及声音播放提示。</p
      >
      <p class="hint">下麦、闭麦或切换页面后可能断开。手机锁屏或切到后台会暂停核验，请保持本页在前台。</p>
      <button
        type="button"
        class="primary"
        :disabled="reading || !contextCurrent() || phase !== 'idle'"
        @click="connect"
        >连接语音</button
      >
      <button type="button" class="secondary" :disabled="phase === 'idle'" @click="disconnect">断开语音</button>
      <button
        type="button"
        class="secondary"
        :disabled="phase !== 'connected' || microphoneBusy || !credential?.canPublish || !grantCurrent()"
        @click="voice.toggleMicrophone"
        >{{ microphoneEnabled ? '关闭麦克风' : '开启麦克风' }}</button
      >
      <p v-if="credential" class="hint"
        >当前授权轮次 {{ credential.generation }} · {{ credential.canPublish ? '允许本人麦克风发布' : '仅收听' }}</p
      >
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
