<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue';
import { voiceInteractionApi, type VoiceActionPage } from '../services/voice-interaction';
import VoiceMediaPlanPanel from './VoiceMediaPlanPanel.vue';
const props = defineProps<{
  /** 已确认本人房间ID。 */ roomId: string;
  /** 当前可信账号。 */ userId: string;
  /** 登录轮次，不能沿用旧账号的历史。 */ sessionRevision: number;
}>();
const emit = defineEmits<{ /** 关闭只取消等待，不改审计事实。 */ close: [] }>();
/** 授权读取结果；故障不假装空历史。 */ const page = ref<VoiceActionPage | null>(null),
  loading = ref(false),
  error = ref('');
let controller = new AbortController(),
  revision = 0,
  disposed = false;
async function load(more = false) {
  if (disposed || loading.value || !props.userId || (more && !page.value?.nextBefore)) return;
  controller.abort();
  controller = new AbortController();
  const expected = ++revision,
    room = props.roomId,
    user = props.userId,
    session = props.sessionRevision;
  const current = () =>
    !disposed &&
    revision === expected &&
    room === props.roomId &&
    user === props.userId &&
    session === props.sessionRevision;
  loading.value = true;
  error.value = '';
  try {
    const result = await voiceInteractionApi.actions(room, more ? page.value!.nextBefore : null, controller.signal);
    if (current())
      page.value = {
        items: more ? [...page.value!.items, ...result.items] : result.items,
        nextBefore: result.nextBefore,
      };
  } catch (cause) {
    if (current()) error.value = cause instanceof Error ? cause.message : '审计读取失败';
  } finally {
    if (current()) loading.value = false;
  }
}
const stop = watch(
  () => [props.roomId, props.userId, props.sessionRevision],
  () => {
    revision++;
    controller.abort();
    page.value = null;
    loading.value = false;
    error.value = '';
    void load();
  },
  { immediate: true, flush: 'sync' },
);
onBeforeUnmount(() => {
  disposed = true;
  revision++;
  controller.abort();
  stop();
  page.value = null;
  error.value = '';
  loading.value = false;
});
</script>
<template>
  <section class="workspace-card voice-audit-history" aria-label="已关闭房间审计" :aria-busy="loading">
    <div class="workspace-card-heading"
      ><h2>房间操作历史</h2><button type="button" class="secondary" @click="emit('close')">关闭历史</button></div
    >
    <p class="hint">房间 {{ roomId }} · 关闭后仅当前房主可读；不续约成员、不请求媒体凭据。</p>
    <VoiceMediaPlanPanel
      :room-id="roomId"
      :user-id="userId"
      :session-revision="sessionRevision"
      :allowed="!!page && !error"
    />
    <p v-if="error" class="form-error" role="alert">{{ error }}</p
    ><p v-if="loading" role="status">正在读取审计…</p>
    <button type="button" class="secondary" :disabled="loading" @click="load()">刷新历史</button>
    <p v-if="page && !page.items.length && !error && !loading">本轮未查询到审计记录。</p>
    <ul v-if="page"
      ><li v-for="action in page.items" :key="action.version"
        >版本 {{ action.version }} · {{ action.type }} · 操作者 {{ action.actorId || '系统' }} · 目标
        {{ action.targetUserId || '无' }} · 麦位 {{ action.seatNo || '无' }} · {{ action.createdAt }}（数据库时间）</li
      ></ul
    >
    <button v-if="page?.nextBefore" type="button" class="secondary" :disabled="loading" @click="load(true)"
      >更早历史</button
    >
  </section>
</template>
<style scoped>
.voice-audit-history {
  margin: 20px 0;
  overflow-wrap: anywhere;
}
.voice-audit-history li {
  margin: 12px 0;
}
</style>
