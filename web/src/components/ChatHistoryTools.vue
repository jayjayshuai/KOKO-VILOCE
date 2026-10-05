<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue';
import { chatApi, type ChatMessage } from '../services/chat';

const props = defineProps<{
  /** 当前选择会话 UUID；改变后清除旧消息及异步响应。 */
  conversationId: string;
  /** 当前认证用户 ID；不是服务端权限依据。 */
  userId: string;
}>();
const emit = defineEmits<{
  /** 关闭工具，返回实时历史。 */
  close: [];
  /** 选中可读的他人消息，交给外层真实举报入口。 */
  report: [message: ChatMessage];
}>();
const mode = ref<'search' | 'bookmarks'>('search'),
  query = ref(''),
  submittedQuery = ref('');
const items = ref<ChatMessage[]>([]),
  nextBefore = ref<number | null>(null);
const loading = ref(false),
  busy = ref(false),
  error = ref(''),
  success = ref(''),
  searched = ref(false);
let disposed = false,
  generation = 0;
let reads: AbortController | undefined;
const writes = new AbortController();
const failure = (e: unknown) => (e instanceof Error ? e.message : '历史工具操作失败');

/** 切换会话或模式必须取消旧查询，不能显示另一会话的延迟结果。 */
function reset() {
  generation++;
  reads?.abort();
  items.value = [];
  nextBefore.value = null;
  searched.value = false;
  query.value = '';
  submittedQuery.value = '';
  error.value = '';
  success.value = '';
  loading.value = false;
}
async function load(more = false) {
  if (disposed || busy.value) return;
  if (mode.value === 'search' && !more) {
    submittedQuery.value = query.value.trim();
    if (submittedQuery.value.length < 2 || submittedQuery.value.length > 64) {
      error.value = '搜索词需要 2～64 个字符。';
      return;
    }
  }
  reads?.abort();
  reads = new AbortController();
  const signal = reads.signal,
    current = ++generation,
    id = props.conversationId,
    target = mode.value;
  const before = more ? (nextBefore.value ?? undefined) : undefined;
  loading.value = true;
  error.value = '';
  if (!more) {
    items.value = [];
    nextBefore.value = null;
    searched.value = false;
  }
  try {
    const page =
      target === 'search'
        ? await chatApi.search(id, submittedQuery.value, before, signal)
        : await chatApi.bookmarks(id, before, signal);
    if (disposed || current !== generation || id !== props.conversationId) return;
    const unique = new Map((more ? items.value : []).map((message) => [message.id, message]));
    page.items.forEach((message) => unique.set(message.id, message));
    items.value = [...unique.values()].sort((a, b) => b.seq - a.seq);
    nextBefore.value = page.nextBefore;
    searched.value = true;
  } catch (e) {
    if (!disposed && current === generation && !signal.aborted) error.value = failure(e);
  } finally {
    if (!disposed && current === generation) loading.value = false;
  }
}
async function save(message: ChatMessage, remove = false) {
  // 分页读取中禁止写入，避免旧读取重新显示刚取消的收藏。
  if (busy.value || loading.value || disposed) return;
  const id = props.conversationId,
    current = generation;
  busy.value = true;
  error.value = '';
  success.value = '';
  try {
    if (remove) await chatApi.unsave(id, message.id, writes.signal);
    else await chatApi.save(id, message.id, writes.signal);
    if (disposed || id !== props.conversationId || current !== generation) return;
    if (remove) items.value = items.value.filter((item) => item.id !== message.id);
    success.value = remove ? '已取消本人的收藏，不影响原消息。' : '消息已收藏，重复操作不会重复保存。';
  } catch (e) {
    if (!disposed && id === props.conversationId && current === generation) error.value = failure(e);
  } finally {
    if (!disposed) busy.value = false;
  }
}
async function clear() {
  if (
    busy.value ||
    loading.value ||
    disposed ||
    !confirm('确认清空本会话的所有个人收藏引用？不删除原消息，也不影响其他人的收藏。')
  )
    return;
  const id = props.conversationId,
    current = generation;
  busy.value = true;
  error.value = '';
  success.value = '';
  try {
    await chatApi.clearBookmarks(id, writes.signal);
    if (!disposed && id === props.conversationId && current === generation) {
      items.value = [];
      nextBefore.value = null;
      success.value = '本会话的个人收藏已清空。';
    }
  } catch (e) {
    if (!disposed && id === props.conversationId && current === generation) error.value = failure(e);
  } finally {
    if (!disposed) busy.value = false;
  }
}
watch([() => props.conversationId, mode], () => {
  reset();
  if (mode.value === 'bookmarks') void load();
});
onUnmounted(() => {
  disposed = true;
  generation++;
  reads?.abort();
  writes.abort();
});
</script>

<template>
  <section class="history-tools" aria-label="会话历史检索与收藏">
    <header><h3>本会话历史工具</h3><button class="secondary" @click="emit('close')">返回实时聊天</button></header>
    <nav aria-label="历史工具栏目"
      ><button :class="{ active: mode === 'search' }" :disabled="busy" @click="mode = 'search'">搜索消息</button
      ><button :class="{ active: mode === 'bookmarks' }" :disabled="busy" @click="mode = 'bookmarks'"
        >我的消息收藏</button
      ></nav
    >
    <p class="hint">仅显示当前可读历史，收藏不延长权限。搜索和收藏不会自动将聊天尾部标记已读。</p>
    <form v-if="mode === 'search'" @submit.prevent="load()"
      ><label
        >字面搜索词<input
          v-model="query"
          required
          minlength="2"
          maxlength="64"
          placeholder="2～64 字符，区分大小写" /></label
      ><button class="secondary" :disabled="busy || loading">搜索本会话</button></form
    >
    <div v-else class="actions"
      ><button class="secondary" :disabled="busy || loading" @click="load()">刷新收藏</button
      ><button class="secondary" :disabled="busy || loading" @click="clear">清空本会话收藏</button></div
    >
    <p v-if="error" class="form-error" role="alert">{{ error }}</p
    ><p v-if="success" class="success" role="status">{{ success }}</p>
    <div class="results" aria-live="polite">
      <article v-for="message in items" :key="message.id"
        ><small
          >{{ message.senderName }} · 序号 {{ message.seq }} ·
          {{ new Date(message.createdAt).toLocaleString('zh-CN') }}</small
        ><p>{{ message.body }}</p
        ><div class="actions"
          ><button class="text-button" :disabled="busy || loading" @click="save(message, mode === 'bookmarks')">{{
            mode === 'bookmarks' ? '取消收藏' : '收藏消息'
          }}</button
          ><button
            v-if="message.senderId !== props.userId"
            class="text-button"
            :disabled="busy || loading"
            @click="emit('report', message)"
            >举报</button
          ></div
        ></article
      >
      <p v-if="loading" role="status">正在读取服务端历史…</p>
      <p v-if="searched && !items.length && !loading && !error" class="hint">{{
        nextBefore !== null
          ? '本批没有匹配，仍有更早历史，可继续搜索。'
          : mode === 'bookmarks'
            ? '本会话没有当前可读的个人收藏。'
            : '搜索完毕，没有匹配消息。'
      }}</p>
      <p v-if="!searched && mode === 'search' && !loading" class="hint"
        >每批最多检索 2000 个序号，按最新消息倒序返回，不是全文相关度排序。</p
      >
      <button v-if="nextBefore !== null" class="secondary" :disabled="loading || busy" @click="load(true)">{{
        mode === 'search' ? '继续搜索更早历史' : '加载更多收藏'
      }}</button>
    </div>
  </section>
</template>

<style scoped>
.history-tools {
  display: flex;
  flex-direction: column;
  min-height: 0;
  flex: 1;
  margin-top: 12px;
}
.history-tools header,
.history-tools nav,
.actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.history-tools header {
  justify-content: space-between;
}
.history-tools h3 {
  margin: 8px 0;
}
.history-tools nav button {
  padding: 10px 14px;
  border: 1px solid #343847;
  border-radius: 10px;
  background: #191f2a;
  color: #dce3f0;
}
.history-tools nav button.active {
  background: #26374d;
  border-color: #78a4d5;
}
.history-tools form {
  display: flex;
  gap: 12px;
  align-items: flex-end;
  margin: 12px 0;
}
.history-tools label {
  display: grid;
  gap: 6px;
  flex: 1;
}
.history-tools input {
  width: 100%;
  padding: 10px;
  background: #0b0d13;
  color: #e5eaf4;
  border: 1px solid #343847;
  border-radius: 8px;
}
.results {
  overflow: auto;
  min-height: 0;
  padding: 12px 0;
  flex: 1;
}
.results article {
  background: #191f2a;
  border: 1px solid #303442;
  border-radius: 12px;
  padding: 14px;
  margin: 12px 0;
}
.results article p {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.results small,
.hint {
  color: #a8b5c8;
  font-size: 13px;
}
.success {
  color: #9ad2b8;
}
@media (max-width: 600px) {
  .history-tools form {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
