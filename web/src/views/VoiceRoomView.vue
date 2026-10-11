<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue';
import { RouterLink, useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '../stores/auth';
import VoiceInteractionPanel from '../components/VoiceInteractionPanel.vue';
import { consumeVoiceRoomEntry, validVoiceRoomId } from '../services/voice-room-entry';
import { useVoiceCallStore, type ScopedVoiceCallStatus } from '../stores/voice-call';
defineOptions({ inheritAttrs: false });
const auth = useAuthStore(),
  route = useRoute(),
  router = useRouter();
const callStore = useVoiceCallStore();
/** 缓存视图离开路由时不能把全局route的新参数当作自己的房间。 */
const roomId = ref('');
/** 仅代理当前实例公开的设备控制，无SDK或凭据引用。 */
const panel = ref<InstanceType<typeof VoiceInteractionPanel> | null>(null);
let registration = 0;
/** 缓存组件拥有的登录作用域，后台换身份不得重放原导航意图。 */
let selectedUser = '',
  selectedRevision = -1;
/** 仅用于展示与一次明确进入意图，不赋予房间权限。 */
const entry = ref<ReturnType<typeof consumeVoiceRoomEntry>>(null);
watch(
  () => [route.name, route.params.roomId, auth.user?.id, auth.sessionRevision],
  () => {
    if (selectedUser !== (auth.user?.id || '') || selectedRevision !== auth.sessionRevision) {
      callStore.clear(registration);
      roomId.value = '';
      entry.value = null;
      selectedUser = auth.user?.id || '';
      selectedRevision = auth.sessionRevision;
    }
    if (!auth.user) {
      callStore.clear(registration);
      return;
    }
    if (route.name !== 'voice-room') return;
    const next = typeof route.params.roomId === 'string' ? route.params.roomId : '';
    const intent = consumeVoiceRoomEntry(next, auth.user.id, auth.sessionRevision);
    if (
      roomId.value === next &&
      callStore.call?.registration === registration &&
      callStore.call.sessionRevision === auth.sessionRevision
    )
      return;
    callStore.clear(registration);
    roomId.value = next;
    entry.value = intent;
    if (!validVoiceRoomId(next)) return;
    const scope = {
      roomId: next,
      userId: auth.user.id,
      sessionRevision: auth.sessionRevision,
      title: intent?.title || '语音房间',
    };
    // 控制闭包保存本次不可变所有权，不能跟随以后同房间重新认领的全局registration。
    let ownerRegistration = 0;
    const current = () =>
      callStore.call?.registration === ownerRegistration &&
      roomId.value === scope.roomId &&
      auth.user?.id === scope.userId &&
      auth.sessionRevision === scope.sessionRevision;
    registration = callStore.attach(scope, {
      connect: async () => {
        if (current()) await panel.value?.connect();
      },
      toggleMicrophone: async () => {
        if (current()) await panel.value?.toggleMicrophone();
      },
      disconnect: async () => {
        if (current()) await panel.value?.disconnect();
      },
      leave: async () => {
        if (current()) await panel.value?.leave();
      },
    });
    ownerRegistration = registration;
  },
  { immediate: true, flush: 'sync' },
);
function mediaState(value: ScopedVoiceCallStatus) {
  if (value.roomId !== roomId.value || value.userId !== auth.user?.id || value.sessionRevision !== auth.sessionRevision)
    return;
  callStore.update(registration, value);
}
function left() {
  callStore.clear(registration);
  roomId.value = '';
  entry.value = null;
}
onBeforeUnmount(() => {
  callStore.clear(registration);
});
</script>
<template>
  <main class="voice-room-page" aria-label="语音房间页">
    <div class="workspace-page-heading">
      <div
        ><p class="workspace-eyebrow">VOICE ROOM</p><h1>{{ entry?.title || '语音房间' }}</h1
        ><p>切换站内页面仍保留通话；点击“离开房间”才结束。麦克风必须由你明确开启。</p></div
      >
      <RouterLink class="secondary" to="/voice">返回语音空间</RouterLink>
    </div>
    <section v-if="!validVoiceRoomId(roomId)" class="workspace-error" role="alert"
      >房间地址无效，请从语音空间选择房间。</section
    >
    <VoiceInteractionPanel
      v-else-if="auth.user"
      :key="`${roomId}:${auth.user.id}:${auth.sessionRevision}`"
      ref="panel"
      :room-id="roomId"
      :room-title="entry?.title"
      :user-id="auth.user.id"
      :session-revision="auth.sessionRevision"
      presentation="room"
      :automatic-entry="!!entry?.enter"
      @media-state="mediaState"
      @left="left"
      @close="router.push('/voice')"
    />
  </main>
</template>
<style scoped>
.voice-room-page {
  width: 100%;
  min-width: 0;
}
</style>
