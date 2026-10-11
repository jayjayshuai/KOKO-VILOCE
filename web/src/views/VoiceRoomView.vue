<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { RouterLink, useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '../stores/auth';
import VoiceInteractionPanel from '../components/VoiceInteractionPanel.vue';
import { consumeVoiceRoomEntry, validVoiceRoomId } from '../services/voice-room-entry';
defineOptions({ inheritAttrs: false });
const auth = useAuthStore(),
  route = useRoute(),
  router = useRouter();
/** 实际地址ID，非法地址不发送任何房间请求。 */
const roomId = computed(() => (typeof route.params.roomId === 'string' ? route.params.roomId : ''));
/** 仅用于展示与一次明确进入意图，不赋予房间权限。 */
const entry = ref<ReturnType<typeof consumeVoiceRoomEntry>>(null);
watch(
  () => [roomId.value, auth.user?.id, auth.sessionRevision],
  () => {
    entry.value = auth.user ? consumeVoiceRoomEntry(roomId.value, auth.user.id, auth.sessionRevision) : null;
  },
  { immediate: true, flush: 'sync' },
);
</script>
<template>
  <main class="voice-room-page" aria-label="语音房间页">
    <div class="workspace-page-heading">
      <div
        ><p class="workspace-eyebrow">VOICE ROOM</p><h1>{{ entry?.title || '语音房间' }}</h1
        ><p>这是房间页面。默认收听，麦克风必须由你明确开启。</p></div
      >
      <RouterLink class="secondary" to="/voice">返回语音空间</RouterLink>
    </div>
    <section v-if="!validVoiceRoomId(roomId)" class="workspace-error" role="alert"
      >房间地址无效，请从语音空间选择房间。</section
    >
    <VoiceInteractionPanel
      v-else-if="auth.user"
      :key="`${roomId}:${auth.user.id}:${auth.sessionRevision}`"
      :room-id="roomId"
      :room-title="entry?.title"
      :user-id="auth.user.id"
      :session-revision="auth.sessionRevision"
      presentation="room"
      :automatic-entry="!!entry?.enter"
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
