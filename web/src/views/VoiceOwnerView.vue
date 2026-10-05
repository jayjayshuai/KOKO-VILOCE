<script setup lang="ts">
import { Headphones, Plus, RefreshCw } from 'lucide-vue-next';
import { watch } from 'vue';
import { useVoiceOwnerWorkspace } from '../composables/voice-owner-workspace';
const props = defineProps<{
  /** 当前可信用户；后端不从请求正文读取此ID。 */
  userId?: string;
  /** 同账号重新登录也需隔离旧异步结果。 */
  sessionRevision: number;
  /** 同会话建房成功后触发事实重取，不含创建请求或凭据。 */
  refreshRevision?: number;
}>();
const emit = defineEmits<{
  /** 打开既有真实建房表单，不模拟创建成功。 */
  create: [];
  /** 已被服务端确认关闭，用于移除公开快照和本机SDK连接。 */
  closed: [roomId: string];
}>();
const {
  rooms,
  nextBefore,
  loading,
  readError,
  writeError,
  success,
  confirmation,
  closing,
  load,
  prepareClose,
  cancelClose,
  confirmClose,
} = useVoiceOwnerWorkspace(props, (id) => emit('closed', id));
/** 只翻译服务器状态，不从列表条数推算在线数。 */
const statuses: Record<string, string> = { PROVISIONING: '准备中', OPEN: '开放', CLOSED: '已关闭', FAILED: '创建失败' };
watch(
  () => props.refreshRevision,
  () => {
    void load();
  },
);
</script>
<template>
  <div class="workspace-page-heading">
    <div
      ><p class="workspace-eyebrow">VOICE OWNER WORKSPACE</p><h1>我的语音房</h1
      ><p>管理本人房间的真实状态，关闭操作需要再次确认。</p></div
    >
    <div class="page-actions">
      <button type="button" class="secondary" :disabled="loading || !!closing" @click="load()"
        ><RefreshCw :size="16" />刷新</button
      >
      <button type="button" class="primary" :disabled="!!closing" @click="emit('create')"
        ><Plus :size="17" />创建语音房</button
      >
    </div>
  </div>
  <p v-if="writeError" class="form-error" role="alert">{{ writeError }}</p>
  <p v-if="success" role="status">{{ success }}</p>
  <section
    v-if="confirmation"
    class="workspace-card voice-close-confirmation"
    role="region"
    aria-label="关闭语音房确认"
  >
    <h2>确认关闭「{{ confirmation.title }}」？</h2>
    <p>会请求媒体服务删除房间并确认后台关闭状态。此操作不是离开房间，不能直接重新开放。</p>
    <div class="page-actions">
      <button type="button" class="secondary" :disabled="!!closing" @click="cancelClose">取消</button>
      <button type="button" class="primary" :disabled="!!closing" @click="confirmClose">{{
        closing ? '正在确认关闭…' : '确认关闭房间'
      }}</button>
    </div>
  </section>
  <section class="workspace-card" :aria-busy="loading">
    <div class="workspace-card-heading"><h2>本人房间</h2><Headphones :size="20" /></div>
    <p v-if="loading" role="status">正在读取房间状态…</p>
    <div v-if="readError" class="workspace-error" role="alert"
      ><p>{{ readError }}</p
      ><button type="button" class="secondary" :disabled="loading || !!closing" @click="load(!rooms.length)"
        >重试读取</button
      ></div
    >
    <div v-else-if="!loading && !rooms.length" class="workspace-empty"
      ><h3>还没有语音房</h3><p>创建房间后，可在这里查看开放、准备中、失败及关闭状态。</p></div
    >
    <ul v-if="rooms.length" class="voice-owner-list">
      <li v-for="room in rooms" :key="room.id">
        <div
          ><h3>{{ room.title }}</h3
          ><p>{{ room.topic || '未设置话题' }}</p
          ><small>/{{ room.slug }} · 人数上限 {{ room.maxParticipants }}</small></div
        >
        <span class="status-tag">{{ statuses[room.status] || '未知状态' }}</span>
        <button
          v-if="room.status === 'OPEN'"
          type="button"
          class="secondary"
          :disabled="loading || !!closing || !!confirmation"
          :aria-label="`关闭房间 ${room.title}`"
          @click="prepareClose(room)"
          >关闭房间</button
        >
      </li>
    </ul>
    <button v-if="nextBefore" type="button" class="secondary" :disabled="loading || !!closing" @click="load(false)">{{
      loading ? '读取中…' : '加载更早的房间'
    }}</button>
  </section>
  <p class="hint"
    >列表按房间ID游标分页，不是在线人数统计。取消页面等待不撤销后台操作；旧入会凭据的即时撤销及麦位授权仍在开发。</p
  >
</template>
<style scoped>
.voice-owner-list {
  list-style: none;
  padding: 0;
  margin: 0 0 20px;
}
.voice-owner-list li {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 20px 0;
  border-bottom: 1px solid var(--border-color, #303541);
}
.voice-owner-list li > div {
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
}
.voice-owner-list h3,
.voice-owner-list p {
  margin: 0 0 8px;
}
.voice-close-confirmation {
  margin-bottom: 20px;
}
@media (max-width: 640px) {
  .voice-owner-list li {
    align-items: flex-start;
    flex-wrap: wrap;
  }
  .voice-owner-list li > div {
    flex-basis: 100%;
  }
}
</style>
