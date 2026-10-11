<script setup lang="ts">
import { computed, watch } from 'vue';
import { RouterLink, useRoute } from 'vue-router';
import { useAuthStore } from '../stores/auth';
import { useVoiceCallStore } from '../stores/voice-call';
const auth = useAuthStore(),
  callStore = useVoiceCallStore(),
  route = useRoute();
/** 只有同身份同登录轮次才能操作已有通话；身份变化立即清投影，房间组件另行释放设备。 */
watch(
  () => [auth.user?.id, auth.sessionRevision, callStore.call?.registration],
  () => {
    const call = callStore.call;
    if (call && (call.userId !== auth.user?.id || call.sessionRevision !== auth.sessionRevision)) {
      callStore.clear(call.registration);
      void call.controls.disconnect();
    }
  },
  { immediate: true, flush: 'sync' },
);
const call = computed(() => callStore.call);
const minimized = computed(
  () => !!call.value && !(route.name === 'voice-room' && route.params.roomId === call.value.roomId),
);
const label = computed(() =>
  call.value?.status.phase === 'connected'
    ? '通话中'
    : call.value?.status.phase === 'reconnecting'
      ? '正在恢复声音'
      : call.value?.status.phase === 'joining'
        ? '正在连接声音'
        : '声音未连接',
);
</script>
<template>
  <aside v-if="minimized && call" class="persistent-voice-call" aria-label="常驻语音通话">
    <div
      ><strong>{{ call.title }}</strong
      ><span role="status"
        >{{ label }} · {{ call.status.participantCount }} 人 ·
        {{ call.status.microphoneEnabled ? '麦克风已开启' : '麦克风关闭' }}</span
      ></div
    >
    <RouterLink class="secondary" :to="`/voice/rooms/${call.roomId}`">返回通话</RouterLink>
    <button v-if="call.status.phase === 'idle'" class="primary" @click="call.controls.connect()">连接声音</button>
    <button
      v-else
      class="secondary"
      :disabled="!call.status.canToggleMicrophone || call.status.microphoneBusy"
      @click="call.controls.toggleMicrophone()"
      >{{ call.status.microphoneEnabled ? '关闭麦克风' : '开启麦克风' }}</button
    >
    <button class="secondary" @click="call.controls.leave()">离开通话</button>
  </aside>
</template>
<style scoped>
.persistent-voice-call {
  position: fixed;
  bottom: 20px;
  left: 264px;
  right: 24px;
  z-index: 45;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  padding: 16px;
  border: 1px solid #6551a8;
  border-radius: 12px;
  background: #191624;
  box-shadow: 0 8px 30px #0006;
}
.persistent-voice-call > div {
  display: grid;
  gap: 6px;
  flex: 1;
  min-width: 160px;
}
.persistent-voice-call span {
  color: #c0b9d3;
  font-size: 0.875rem;
}
@media (max-width: 900px) {
  .persistent-voice-call {
    left: 12px;
    right: 12px;
    bottom: 12px;
  }
}
</style>
