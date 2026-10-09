<script setup lang="ts">
defineProps<{
  /** SDK当前连接阶段；恢复连接期间不擅自启动播放。 */
  phase: 'idle' | 'joining' | 'connected' | 'reconnecting';
  /** SDK确认播放被阻止，不表示媒体链路不可用或麦克风被禁。 */
  blocked: boolean;
  /** 当前播放恢复调用在途，禁止重复点击。 */
  busy: boolean;
  /** 播放故障与连接/发布设备故障分别展示。 */
  error: string;
}>();
const emit = defineEmits<{
  /** 同步转交本次用户点击给连接所有者，不延迟或请求麦克风。 */
  resume: [];
}>();
</script>
<template>
  <section v-if="blocked || error" class="voice-playback-controls" aria-label="语音声音播放">
    <p role="status">{{ blocked ? '浏览器暂未允许播放房间声音。' : '声音播放尚未确认。' }}</p>
    <p class="workspace-note">点击只恢复收听，不会打开麦克风或重新加入房间。</p>
    <p v-if="error" role="alert" class="form-error">{{ error }}</p>
    <button
      type="button"
      class="secondary compact-button"
      :disabled="busy || phase !== 'connected'"
      @click="emit('resume')"
    >
      {{ busy ? '正在恢复声音…' : '恢复声音播放' }}
    </button>
  </section>
</template>
<style scoped>
.voice-playback-controls {
  display: grid;
  gap: 8px;
  min-width: 0;
  overflow-wrap: anywhere;
}
.voice-playback-controls p {
  margin: 0;
}
.voice-playback-controls button {
  justify-self: start;
}
</style>
