<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';
import { Mic, MicOff, Phone, PhoneOff } from 'lucide-vue-next';
import { useAuthStore } from '../stores/auth';
import { useNativeCallStore } from '../stores/native-call';
import { nativeCallApi } from '../services/native-calls';
import { audioConnectionOwnership } from '../services/audio-ownership';
import { createNativeCallControl } from '../composables/native-call-control';
import { useVoiceConnection } from '../composables/voice-connection';
import { useDialogAccessibility } from '../composables/dialog-accessibility';

const auth = useAuthStore(),
  store = useNativeCallStore();
const audioRoot = ref<HTMLElement | null>(null),
  bar = ref<HTMLElement | null>(null);
let media!: ReturnType<typeof useVoiceConnection>;
const control = createNativeCallControl(
  auth,
  () => media.leaveVoiceRoom(),
  () => audioConnectionOwnership.occupied(),
);
const state = control.state;
const self = computed(() => state.view?.participants.find((p) => p.userId === auth.user?.id));
let mediaCallId = '',
  attempt = '',
  wantedMicrophone = false;
const canUseMedia = () =>
  !!state.view &&
  state.view.id === mediaCallId &&
  state.view.state === 'ACTIVE' &&
  self.value?.state === 'ACCEPTED' &&
  state.localConsent &&
  !state.stopped;
media = useVoiceConnection(
  auth,
  async (id, signal) => {
    const credential = await nativeCallApi.credential(id, signal);
    const endpoint = new URL(credential.url),
      page = new URL(location.href);
    if (
      credential.callId !== id ||
      !/^\/(api|koko-api)\/media\/native-call\/livekit$/.test(endpoint.pathname) ||
      endpoint.host !== page.host ||
      endpoint.protocol !== (page.protocol === 'https:' ? 'wss:' : 'ws:')
    )
      throw new Error('私人音频入口或通话身份不匹配，不建立连接。');
    return { ...credential, roomName: `koko-call-${id}` };
  },
  audioRoot,
  undefined,
  { enabled: true, current: canUseMedia, canPublish: canUseMedia },
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
} = media;
const incoming = computed(() => self.value?.state === 'INVITED' && state.view?.state !== 'ENDED');
const visible = computed(() => !!auth.user && (!!state.view || !!state.pending || !!state.error));
const initiator = computed(
  () => state.view?.participants.find((p) => p.userId === state.view?.initiatorId)?.displayName ?? '联系人',
);
const title = computed(() => (incoming.value ? `${initiator.value} 邀请你语音` : '私信 / 群聊语音'));
const label = computed(() =>
  state.view?.state === 'ENDED'
    ? '通话已结束'
    : incoming.value
      ? '接听前不会采集你的麦克风'
      : state.view?.state === 'RINGING'
        ? '等待对方接听…'
        : phase.value === 'connected'
          ? '音频已连接'
          : phase.value === 'joining'
            ? '正在连接音频…'
            : phase.value === 'reconnecting'
              ? '网络恢复中…'
              : '声音未连接',
);
const draftOpen = computed(() => store.draft?.kind === 'GROUP');
const selected = ref<string[]>([]);
const peers = computed(() => store.draft?.members.filter((p) => p.userId !== auth.user?.id) ?? []);
useDialogAccessibility(
  draftOpen,
  () => {
    store.draft = null;
  },
  () => state.busy,
);
watch(
  () => [state.enabled, state.busy, state.pending, state.view?.state],
  () => {
    store.enabled = state.enabled;
    store.occupied = !!state.pending || state.busy || (!!state.view && state.view.state !== 'ENDED');
  },
  { immediate: true },
);
watch(
  () => store.draft,
  async (draft) => {
    selected.value = [];
    if (!draft || store.userId !== auth.user?.id || store.sessionRevision !== auth.sessionRevision) return;
    if (draft.kind === 'DIRECT') {
      store.draft = null;
      wantedMicrophone = true;
      await control.invite(draft.id);
    }
  },
);
async function callGroup() {
  const draft = store.draft;
  if (!draft || selected.value.length < 1 || selected.value.length > 7 || state.busy) return;
  const targets = [...selected.value];
  store.draft = null;
  wantedMicrophone = true;
  await control.invite(draft.id, targets);
}
async function accept() {
  wantedMicrophone = true;
  await control.command('ACCEPT');
}
function reconnect() {
  wantedMicrophone = false;
  control.resume();
}
watch(
  () => [state.view?.id, state.view?.state, self.value?.state, state.localConsent, state.stopped, state.mediaRevision],
  async () => {
    const view = state.view;
    if (!view || view.state !== 'ACTIVE' || self.value?.state !== 'ACCEPTED' || !state.localConsent || state.stopped)
      return;
    const key = `${view.id}:${state.mediaRevision}`;
    if (attempt === key) return;
    attempt = key;
    mediaCallId = view.id;
    // 仅复用本机音频连接适配器，不创建/加入公开语音房或调用其媒体接口。
    await media.joinVoiceRoom({
      id: view.id,
      title: '私人语音通话',
      slug: view.id,
      owner: initiator.value,
      maxParticipants: 8,
      status: 'OPEN',
      controlled: true,
    });
    if (attempt === key && canUseMedia() && phase.value === 'connected' && wantedMicrophone && !microphoneEnabled.value)
      await media.toggleMicrophone();
  },
);
let disposed = false,
  lifecycle = 0,
  polling: ReturnType<typeof setTimeout> | undefined,
  watchdog: ReturnType<typeof setInterval> | undefined;
async function poll(revision: number) {
  if (disposed || revision !== lifecycle || !auth.user) return;
  if (!state.checked || !state.enabled) await control.initialize();
  if (disposed || revision !== lifecycle) return;
  // 未开放/旧后端只核对一次，不每30秒制造404；重新登录或刷新后重新核对。
  if (!state.enabled) return;
  await control.heartbeat();
  await control.refresh();
  if (!disposed && revision === lifecycle)
    polling = setTimeout(() => void poll(revision), document.visibilityState === 'hidden' ? 8000 : 3000);
}
watch(
  () => [auth.user?.id, auth.sessionRevision],
  () => {
    lifecycle++;
    clearTimeout(polling);
    control.reset();
    store.reset(auth.user?.id, auth.sessionRevision);
    wantedMicrophone = false;
    mediaCallId = '';
    attempt = '';
    if (auth.user) void poll(lifecycle);
  },
  { immediate: true, flush: 'sync' },
);
function resumePage() {
  control.expireUnverified();
  if (document.visibilityState === 'visible') {
    void control.heartbeat();
    void control.refresh();
  }
}
function pageHide() {
  control.stopLocal();
}
let resize: ResizeObserver | undefined;
function reserve() {
  const height = visible.value && bar.value ? Math.ceil(bar.value.getBoundingClientRect().height) + 32 : 0;
  document.documentElement.style.setProperty('--koko-native-call-space', `${height}px`);
}
watch([visible, bar], async () => {
  resize?.disconnect();
  await nextTick();
  if (disposed) return;
  reserve();
  if (bar.value && typeof ResizeObserver !== 'undefined') {
    resize = new ResizeObserver(reserve);
    resize.observe(bar.value);
  }
});
onMounted(() => {
  watchdog = setInterval(control.expireUnverified, 5000);
  document.addEventListener('visibilitychange', resumePage);
  window.addEventListener('focus', resumePage);
  window.addEventListener('pagehide', pageHide);
});
onBeforeUnmount(() => {
  disposed = true;
  lifecycle++;
  clearTimeout(polling);
  clearInterval(watchdog);
  resize?.disconnect();
  control.dispose();
  store.reset();
  document.removeEventListener('visibilitychange', resumePage);
  window.removeEventListener('focus', resumePage);
  window.removeEventListener('pagehide', pageHide);
  document.documentElement.style.removeProperty('--koko-native-call-space');
});
</script>
<template>
  <div ref="audioRoot" class="native-call-audio" aria-hidden="true"></div>
  <aside v-if="visible" ref="bar" class="native-call-bar" aria-label="私信与群聊通话">
    <div class="native-call-summary"
      ><strong><Phone :size="16" />{{ title }}</strong
      ><span role="status">{{ label }}</span>
      <small v-if="phase === 'connected'"
        >{{ microphoneEnabled ? '麦克风已开启' : '麦克风关闭' }} · {{ participantCount }} 人连接 ·
        {{ receivedAudioTracks ? '对方音频已接入' : '等待对方开麦' }}</small
      >
    </div>
    <div class="native-call-actions">
      <template v-if="incoming"
        ><button class="primary" :disabled="state.busy || !!state.pending" @click="accept">接听并开麦</button>
        <button class="secondary" :disabled="state.busy || !!state.pending" @click="control.command('DECLINE')"
          >拒接</button
        ></template
      >
      <template v-else-if="state.view && state.view.state !== 'ENDED'">
        <button
          v-if="phase === 'connected'"
          class="secondary"
          :disabled="microphoneBusy"
          @click="media.toggleMicrophone()"
          ><MicOff v-if="microphoneEnabled" :size="16" /><Mic v-else :size="16" />{{
            microphoneEnabled ? '关闭麦克风' : '开启麦克风'
          }}</button
        >
        <button
          v-else-if="state.view.state === 'ACTIVE' && self?.state === 'ACCEPTED'"
          class="primary"
          :disabled="phase === 'joining' || state.busy || !!state.pending"
          @click="reconnect"
          >重新连接声音</button
        >
        <button
          class="secondary"
          :disabled="state.busy || !!state.pending"
          @click="
            control.command(
              state.view.initiatorId === auth.user?.id && state.view.state === 'RINGING' ? 'END' : 'LEAVE',
            )
          "
          ><PhoneOff :size="16" />挂断</button
        >
      </template>
      <button v-if="state.pending && !state.busy" class="secondary" @click="control.retry">重试原操作</button>
      <button v-if="state.pending || phase === 'joining'" class="text-button" @click="control.stopLocal"
        >停止本机声音</button
      >
      <button
        v-if="!state.pending && !state.busy && (!state.view || state.view.state === 'ENDED')"
        class="secondary"
        @click="control.dismiss"
        >关闭提示</button
      >
      <RouterLink
        v-if="state.view"
        class="text-button"
        :to="{ path: '/messages', query: { conversation: state.view.conversationId } }"
        >打开聊天</RouterLink
      >
    </div>
    <p v-if="state.busy" class="native-call-note" role="status">正在确认通话操作…</p>
    <p v-if="state.error || voiceError" class="native-call-note error" role="alert">{{ state.error || voiceError }}</p>
    <p v-if="state.syncNote" class="native-call-note" role="status">{{ state.syncNote }}</p>
    <div v-if="audioPlaybackBlocked" class="native-call-note"
      ><button class="secondary" :disabled="audioPlaybackBusy" @click="media.resumeVoiceAudio">点击恢复声音播放</button
      ><span>{{ audioPlaybackError }}</span></div
    >
    <small class="native-call-note"
      >站内切换页面可保留通话；手机锁屏或系统后台可能中断。若未弹权限，请点“开启麦克风”。</small
    >
  </aside>
  <div
    v-if="draftOpen && store.draft"
    class="modal-backdrop native-call-dialog"
    role="dialog"
    aria-modal="true"
    aria-label="选择群语音成员"
    tabindex="-1"
    @click.self="store.draft = null"
  >
    <form class="native-call-picker" @submit.prevent="callGroup"
      ><header
        ><h2>发起群语音</h2><button type="button" class="secondary" @click="store.draft = null">取消</button></header
      >
      <p>{{ store.draft.title }} · 选择 1～7 位成员。接听后自动连接声音并尝试开启麦克风。</p>
      <fieldset
        ><legend>邀请成员（已选 {{ selected.length }}/7）</legend>
        <label v-for="peer in peers" :key="peer.userId"
          ><input
            v-model="selected"
            type="checkbox"
            :value="peer.userId"
            :disabled="selected.length >= 7 && !selected.includes(peer.userId)"
          />{{ peer.displayName }} <small>@{{ peer.handle }}</small></label
        > </fieldset
      ><button class="primary" :disabled="selected.length < 1 || selected.length > 7 || state.busy">发起群语音</button>
    </form>
  </div>
</template>
<style scoped>
:global(.workspace-shell .workspace-content) {
  padding-bottom: calc(56px + var(--koko-voice-call-space, 0px) + var(--koko-native-call-space, 0px));
}
.native-call-audio {
  display: none;
}
.native-call-bar {
  position: fixed;
  left: 264px;
  right: 24px;
  bottom: calc(20px + var(--koko-voice-call-space, 0px));
  z-index: 46;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  padding: 16px;
  border: 1px solid #6e59b8;
  border-radius: 14px;
  background: #1b1728;
  box-shadow: 0 8px 30px #0006;
  max-height: 65svh;
  overflow: auto;
}
.native-call-summary {
  display: grid;
  gap: 6px;
  flex: 1;
  min-width: 180px;
}
.native-call-summary strong {
  display: flex;
  align-items: center;
  gap: 8px;
}
.native-call-summary span,
.native-call-note {
  color: #cbc4dd;
  font-size: 0.875rem;
}
.native-call-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.native-call-actions button {
  min-height: 44px;
}
.native-call-actions .text-button {
  display: inline-flex;
  align-items: center;
  width: auto;
  min-height: 44px;
  padding: 6px 8px;
}
.native-call-note {
  flex-basis: 100%;
  margin: 0;
  overflow-wrap: anywhere;
}
.native-call-note.error {
  color: #ffb2b2;
}
.native-call-dialog {
  z-index: 80;
  padding: 16px;
}
.native-call-picker {
  width: min(500px, 100%);
  max-height: 85svh;
  overflow: auto;
  padding: 24px;
  border-radius: 16px;
  background: #171b25;
  border: 1px solid #353b4f;
}
.native-call-picker header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.native-call-picker h2 {
  margin: 0;
}
.native-call-picker fieldset {
  display: grid;
  gap: 4px;
  max-height: 40svh;
  overflow: auto;
  margin: 16px 0;
  border: 1px solid #353b4f;
  border-radius: 10px;
}
.native-call-picker label {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 44px;
  padding: 8px;
  overflow-wrap: anywhere;
}
.native-call-picker input {
  width: 20px;
  height: 20px;
  flex-shrink: 0;
}
@media (max-width: 900px) {
  .native-call-bar {
    left: 12px;
    right: 12px;
    bottom: calc(12px + var(--koko-voice-call-space, 0px));
  }
}
</style>
