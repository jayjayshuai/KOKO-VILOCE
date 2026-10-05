<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { chatApi, messageUuid, socketUrl, type ChatMessage, type Conversation } from '../services/chat';
import { chatSafetyApi } from '../services/chat-safety';
import { captureUnauthorizedSession } from '../services/http';
import ChatSafetyPanel from './ChatSafetyPanel.vue';
import ChatHistoryTools from './ChatHistoryTools.vue';
const props = defineProps<{
  /** 当前已认证用户 ID，服务端仍独立校验权限。 */ userId: string;
  /** 路由内嵌模式；离开页面仍执行 socket/订阅释放。 */ embedded?: boolean;
}>();
const emit = defineEmits<{ close: [] }>();
const conversations = ref<Conversation[]>([]);
const conversationsLoading = ref(false);
const activeId = ref('');
const messages = ref<ChatMessage[]>([]);
const active = computed(() => conversations.value.find((c) => c.id === activeId.value));
const owner = computed(() => active.value?.ownerId === props.userId);
const error = ref(''),
  loading = ref(false),
  busy = ref(false),
  connected = ref(false);
const body = ref(''),
  handle = ref(''),
  title = ref(''),
  handles = ref(''),
  memberHandle = ref(''),
  editTitle = ref('');
const hasOlder = ref(false),
  transcript = ref<HTMLElement>();
const safetyOpen = ref(false),
  reportMessage = ref<ChatMessage>();
const historyToolsOpen = ref(false),
  bookmarkSuccess = ref('');
const bookmarkWrites = new AbortController();
const blockedTargets = ref<string[]>([]);
const safetyReads = new AbortController();
/** 生命周期取消所有会话读取；切换会话另有独立历史请求围栏。 */
const reads = new AbortController();
let historyRequest = new AbortController(),
  listRequest = new AbortController();
let listRevision = 0,
  blockedRevision = 0;
/** 明确显示事实补拉状态，不把 WebSocket READY 当全部历史已同步。 */
const recovering = ref(false);
/** 同步故障独立于发送/管理错误；恢复成功仅清除此类错误。 */
const syncError = ref('');
/** 连接故障由 READY 独立清除，不吞掉发送/管理或事实读取的错误。 */
const connectionError = ref('');
const directPeer = computed(() =>
  active.value?.kind === 'DIRECT' ? active.value.members.find((member) => member.userId !== props.userId) : undefined,
);
const blockedDirect = computed(() => !!directPeer.value && blockedTargets.value.includes(directPeer.value.userId));

/** 主动设置仅用于客户端反馈，服务端每次发送仍会重新判断双方设置。 */
async function loadBlocked() {
  const revision = ++blockedRevision;
  const targets: string[] = [];
  let cursor: string | undefined;
  do {
    const page = await chatSafetyApi.blocks(cursor, safetyReads.signal);
    targets.push(...page.map((item) => item.targetId));
    cursor = page.length === 100 ? page.at(-1)?.id : undefined;
  } while (cursor && !disposed);
  if (!disposed && revision === blockedRevision) blockedTargets.value = targets;
}
function openReport(message: ChatMessage) {
  reportMessage.value = message;
  safetyOpen.value = true;
}
type Pending = {
  /** 首次发送生成的幂等 UUID，重试不改变。 */
  clientMessageId: string;
  /** 原目标会话；切换当前会话不能改变重试目标。 */
  conversationId: string;
  /** 原正文；同一 UUID 不允许改写正文。 */
  body: string;
  /** sending 等待服务端确认，retry 仅表示未确认，不能展示为已保存。 */
  state: 'sending' | 'retry';
  /** 十秒确认超时计时器；确认、关闭和卸载时清理。 */
  timer?: ReturnType<typeof setTimeout>;
};
const pending = ref<Pending[]>([]);
let socket: WebSocket | undefined;
let reconnect: ReturnType<typeof setTimeout> | undefined;
let heartbeat: ReturnType<typeof setInterval> | undefined;
let poll: ReturnType<typeof setInterval> | undefined;
let disposed = false,
  attempts = 0,
  selection = 0,
  syncing = false,
  needsSync = false;
let syncTimer: ReturnType<typeof setTimeout> | undefined;
const failure = (e: unknown) => (e instanceof Error ? e.message : '聊天操作失败');
const unread = (c: Conversation) =>
  Math.max(0, c.lastSeq - (c.members.find((m) => m.userId === props.userId)?.readSeq || 0));

async function loadConversations() {
  const revision = ++listRevision;
  listRequest.abort();
  listRequest = new AbortController();
  const signal = AbortSignal.any([reads.signal, listRequest.signal]);
  conversationsLoading.value = true;
  try {
    const items: Conversation[] = [];
    let cursor: string | undefined;
    do {
      const page = await chatApi.list(cursor, signal);
      if (disposed || revision !== listRevision) return false;
      items.push(...page);
      cursor = page.length === 100 ? page[page.length - 1]!.id : undefined;
    } while (cursor && !disposed);
    if (disposed || revision !== listRevision) return false;
    conversations.value = items;
    if (activeId.value && !items.some((c) => c.id === activeId.value)) {
      activeId.value = '';
      messages.value = [];
      historyToolsOpen.value = false;
      selection++;
      historyRequest.abort();
      historyRequest = new AbortController();
      loading.value = false;
      error.value = '会话已关闭或你已被移出群聊。';
    }
    return true;
  } catch (cause) {
    if (disposed || revision !== listRevision) return false;
    throw cause;
  } finally {
    if (!disposed && revision === listRevision) conversationsLoading.value = false;
  }
}
function clearPending(id: string) {
  const item = pending.value.find((p) => p.clientMessageId === id);
  if (item?.timer) clearTimeout(item.timer);
  pending.value = pending.value.filter((p) => p.clientMessageId !== id);
}
function merge(items: ChatMessage[]) {
  const byId = new Map(messages.value.map((m) => [m.id, m]));
  items.forEach((m) => {
    byId.set(m.id, m);
    clearPending(m.clientMessageId);
  });
  messages.value = Array.from(byId.values()).sort((a, b) => a.seq - b.seq);
}
async function markRead(id: string, seq: number) {
  if (safetyOpen.value || historyToolsOpen.value || !seq || document.visibilityState !== 'visible') return;
  if (
    (conversations.value.find((c) => c.id === id)?.members.find((m) => m.userId === props.userId)?.readSeq || 0) >= seq
  )
    return;
  await chatApi.read(id, seq, reads.signal);
  if (disposed) return;
  const member = conversations.value.find((c) => c.id === id)?.members.find((m) => m.userId === props.userId);
  if (member) member.readSeq = Math.max(member.readSeq, seq);
}
async function scrollBottom() {
  await nextTick();
  if (transcript.value) transcript.value.scrollTop = transcript.value.scrollHeight;
}
async function select(c: Conversation) {
  historyRequest.abort();
  historyRequest = new AbortController();
  const signal = AbortSignal.any([reads.signal, historyRequest.signal]);
  if (activeId.value !== c.id) body.value = '';
  activeId.value = c.id;
  messages.value = [];
  editTitle.value = c.title;
  hasOlder.value = false;
  historyToolsOpen.value = false;
  bookmarkSuccess.value = '';
  const generation = ++selection;
  loading.value = true;
  error.value = '';
  try {
    const items = await chatApi.history(c.id, '', signal);
    if (disposed || generation !== selection) return;
    merge(items);
    hasOlder.value = items.length === 100;
    await markRead(c.id, items[items.length - 1]?.seq || 0);
    await scrollBottom();
  } catch (e) {
    if (!disposed && generation === selection) error.value = failure(e);
  } finally {
    if (!disposed && generation === selection) {
      loading.value = false;
      if (needsSync) void sync();
    }
  }
}
async function older() {
  const id = activeId.value,
    generation = selection,
    first = messages.value[0]?.seq;
  if (!id || !first || loading.value) return;
  loading.value = true;
  try {
    const items = await chatApi.history(id, `&before=${first}`, AbortSignal.any([reads.signal, historyRequest.signal]));
    if (disposed || generation !== selection) return;
    merge(items);
    hasOlder.value = items.length === 100;
  } catch (e) {
    if (!disposed && generation === selection) error.value = failure(e);
  } finally {
    if (!disposed && generation === selection) {
      loading.value = false;
      if (needsSync) void sync();
    }
  }
}
/** 推送仅提示查库；循环补拉到尾部，以服务端 UUID 去重。 */
async function sync() {
  if (disposed) return;
  if (syncing) {
    needsSync = true;
    return;
  }
  syncing = true;
  needsSync = false;
  recovering.value = true;
  const startedSelection = selection;
  try {
    if (!(await loadConversations())) {
      needsSync = !disposed;
      return;
    }
    await loadBlocked();
    const id = activeId.value,
      generation = selection;
    if (!id) {
      syncError.value = '';
      return;
    }
    if (loading.value) {
      needsSync = true;
      return; // 由历史读取 finally 接续，不能立即重试形成查询忙循环。
    }
    const signal = AbortSignal.any([reads.signal, historyRequest.signal]);
    let cursor =
      messages.value[messages.value.length - 1]?.seq ??
      active.value?.members.find((m) => m.userId === props.userId)?.joinedSeq ??
      0;
    let page: ChatMessage[];
    do {
      page = await chatApi.history(id, `&after=${cursor}`, signal);
      if (disposed || generation !== selection) {
        needsSync = !disposed;
        return;
      }
      merge(page);
      cursor = page[page.length - 1]?.seq ?? cursor;
    } while (page.length === 100);
    await markRead(id, cursor);
    if (!disposed && generation === selection) syncError.value = '';
  } catch (e) {
    if (!disposed && startedSelection === selection) syncError.value = failure(e);
    if (!disposed && startedSelection !== selection) needsSync = true;
  } finally {
    syncing = false;
    if (!disposed) recovering.value = false;
    if (needsSync && !disposed && !loading.value) {
      needsSync = false;
      void sync();
    }
  }
}
function connect() {
  if (disposed) return;
  socket = new WebSocket(socketUrl());
  const current = socket;
  const notifyExpiredSession = captureUnauthorizedSession();
  current.onmessage = (event) => {
    if (current !== socket || disposed) return;
    try {
      const data = JSON.parse(event.data);
      if (data.type === 'READY') {
        connected.value = true;
        connectionError.value = '';
        attempts = 0;
        void sync();
      }
      if (data.type === 'SYNC' && !syncTimer)
        syncTimer = setTimeout(() => {
          syncTimer = undefined;
          void sync();
        }, 2000);
      if (data.type === 'ACK') {
        const m = data.message as ChatMessage;
        clearPending(m.clientMessageId);
        if (m.conversationId === activeId.value) {
          merge([m]);
          void scrollBottom();
        }
        void sync();
      }
      if (data.type === 'ERROR') {
        if (data.code === 'CONNECTION_EXPIRED')
          connectionError.value = data.message || '连接租约已失效，正在重新连接。';
        else error.value = data.message || '消息发送失败';
        const item = pending.value.find((p) => p.clientMessageId === data.clientMessageId);
        if (item) {
          if (item.timer) clearTimeout(item.timer);
          item.state = 'retry';
        }
        if (data.code === 'AUTH_REQUIRED') {
          disposed = true;
          reads.abort();
          historyRequest.abort();
          listRequest.abort();
          safetyReads.abort();
          bookmarkWrites.abort();
          cleanupSocket();
          // 不能仅停止补拉：确定失效须让应用边界卸载私有页面；旧连接的通知带原轮次。
          notifyExpiredSession();
        }
      }
    } catch {
      error.value = '聊天协议响应异常，请重新打开聊天。';
    }
  };
  current.onerror = () => {
    if (current !== socket || disposed) return;
    connectionError.value = '实时连接暂不可用，历史消息仍可查询。';
  };
  current.onclose = () => {
    if (current !== socket || disposed) return;
    connected.value = false;
    pending.value.forEach((p) => {
      if (p.timer) clearTimeout(p.timer);
      p.state = 'retry';
    });
    if (!disposed)
      reconnect = setTimeout(connect, Math.min(30000, 1000 * 2 ** Math.min(attempts++, 5)) + Math.random() * 500);
  };
}
function transmit(item: Pending) {
  const conversation = conversations.value.find((value) => value.id === item.conversationId);
  if (
    conversation?.kind === 'DIRECT' &&
    conversation.members.some(
      (member) => member.userId !== props.userId && blockedTargets.value.includes(member.userId),
    )
  ) {
    error.value = '你已拉黑对方，不能发送新私信。';
    item.state = 'retry';
    return;
  }
  if (!socket || !connected.value || socket.readyState !== WebSocket.OPEN) {
    error.value = '尚未连接，请等待恢复后重试。';
    item.state = 'retry';
    return;
  }
  if (pending.value.some((p) => p !== item && p.state === 'sending')) {
    error.value = '请等待上一条消息确认。';
    return;
  }
  item.state = 'sending';
  if (item.timer) clearTimeout(item.timer);
  socket.send(
    JSON.stringify({
      type: 'SEND',
      conversationId: item.conversationId,
      clientMessageId: item.clientMessageId,
      body: item.body,
    }),
  );
  item.timer = setTimeout(() => {
    item.state = 'retry';
    error.value = '未收到确认；重试复用消息 UUID，不重复插入。';
  }, 10000);
}
function send() {
  if (!activeId.value || !body.value.trim() || pending.value.length >= 20 || blockedDirect.value) return;
  if (pending.value.some((p) => p.state === 'sending')) {
    error.value = '请等待上一条消息确认。';
    return;
  }
  pending.value.push({
    clientMessageId: messageUuid(),
    conversationId: activeId.value,
    body: body.value,
    state: 'retry',
  });
  body.value = '';
  error.value = '';
  transmit(pending.value[pending.value.length - 1]!);
}
async function action(operation: () => Promise<unknown>) {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    await operation();
    await loadConversations();
  } catch (e) {
    error.value = failure(e);
  } finally {
    busy.value = false;
  }
}
const direct = () =>
  action(async () => {
    const c = await chatApi.direct(handle.value.trim());
    await loadConversations();
    await select(c);
    handle.value = '';
  });
const group = () =>
  action(async () => {
    const c = await chatApi.group(title.value.trim(), handles.value.split(/[\s,，]+/).filter(Boolean));
    await loadConversations();
    await select(c);
    title.value = '';
    handles.value = '';
  });
const add = () =>
  action(async () => {
    await chatApi.add(activeId.value, memberHandle.value.trim());
    memberHandle.value = '';
  });
const remove = (userId: string) =>
  action(async () => {
    if (confirm(userId === props.userId ? '确认退出群聊？' : '确认移除该成员？'))
      await chatApi.remove(activeId.value, userId);
  });
const rename = () =>
  action(async () => {
    await chatApi.rename(activeId.value, editTitle.value.trim());
  });
const closeGroup = () =>
  action(async () => {
    if (confirm('确认解散群聊？所有成员将不能再访问。')) await chatApi.close(activeId.value);
  });
async function bookmark(message: ChatMessage) {
  if (busy.value || disposed) return;
  const id = activeId.value,
    generation = selection;
  busy.value = true;
  error.value = '';
  bookmarkSuccess.value = '';
  try {
    await chatApi.save(id, message.id, bookmarkWrites.signal);
    if (!disposed && generation === selection) bookmarkSuccess.value = '消息已收藏，可在本会话历史工具查看。';
  } catch (e) {
    if (!disposed && generation === selection) error.value = failure(e);
  } finally {
    if (!disposed) busy.value = false;
  }
}
function cleanupSocket() {
  connected.value = false;
  if (reconnect) clearTimeout(reconnect);
  if (heartbeat) clearInterval(heartbeat);
  if (poll) clearInterval(poll);
  if (syncTimer) clearTimeout(syncTimer);
  pending.value.forEach((p) => {
    if (p.timer) clearTimeout(p.timer);
  });
  const previous = socket;
  socket = undefined;
  previous?.close();
}
/** 移动端返回列表结束当前历史读取轮次，避免把草稿发到下一位联系人。 */
function backToConversations() {
  ++selection;
  historyRequest.abort();
  historyRequest = new AbortController();
  activeId.value = '';
  messages.value = [];
  body.value = '';
  loading.value = false;
  historyToolsOpen.value = false;
}
/** 浏览器挂起期间提示可能未处理；返回可见页面立即补拉，不等下一次推送。 */
function resumeSync() {
  if (!disposed && document.visibilityState === 'visible') void sync();
}
onMounted(async () => {
  try {
    await loadConversations();
    await loadBlocked();
  } catch (e) {
    error.value = failure(e);
  }
  if (disposed) return;
  document.addEventListener('visibilitychange', resumeSync);
  window.addEventListener('focus', resumeSync);
  connect();
  heartbeat = setInterval(() => {
    if (connected.value && !pending.value.some((p) => p.state === 'sending'))
      socket?.send(JSON.stringify({ type: 'PING' }));
  }, 25000);
  poll = setInterval(() => {
    if (document.visibilityState === 'visible') void sync();
  }, 15000);
});
watch(safetyOpen, (open) => {
  if (!open) void sync();
});
watch(historyToolsOpen, (open) => {
  if (!open) void sync();
});
onUnmounted(() => {
  disposed = true;
  selection++;
  safetyReads.abort();
  reads.abort();
  listRequest.abort();
  historyRequest.abort();
  bookmarkWrites.abort();
  document.removeEventListener('visibilitychange', resumeSync);
  window.removeEventListener('focus', resumeSync);
  cleanupSocket();
});
</script>

<template>
  <section class="chat-panel" :class="{ embedded }" aria-label="私信与群聊">
    <header class="chat-heading"
      ><div
        ><h2>消息中心</h2
        ><small aria-live="polite">{{
          recovering
            ? '正在同步已保存消息…'
            : connected
              ? '实时连接已建立 · 跨节点同步'
              : '连接恢复中 · 可查询已保存消息'
        }}</small></div
      ><button
        v-if="!safetyOpen"
        type="button"
        class="secondary"
        @click="
          reportMessage = undefined;
          safetyOpen = true;
        "
        >安全中心</button
      ><button type="button" class="secondary" @click="emit('close')">关闭</button></header
    >
    <p v-if="error" class="form-error" role="alert">{{ error }}</p
    ><p v-if="connectionError" class="form-error" role="alert">{{ connectionError }}</p
    ><p v-if="syncError" class="form-error" role="alert">同步暂未完成：{{ syncError }}。连接恢复后会重新读取。</p
    ><p v-if="bookmarkSuccess" role="status">{{ bookmarkSuccess }}</p>
    <ChatSafetyPanel
      v-if="safetyOpen"
      :user-id="props.userId"
      :message="reportMessage"
      @close="
        safetyOpen = false;
        reportMessage = undefined;
      "
      @blocks-changed="loadBlocked().catch((e) => (error = failure(e)))"
    />
    <ChatHistoryTools
      v-else-if="historyToolsOpen && active"
      :key="active.id"
      :conversation-id="active.id"
      :user-id="props.userId"
      @close="historyToolsOpen = false"
      @report="openReport"
    />
    <div v-else class="chat-layout" :class="{ 'has-active': !!active }">
      <aside class="chat-conversations">
        <form @submit.prevent="direct"
          ><label
            >发起私信<input
              v-model.trim="handle"
              placeholder="对方用户名（不是邮箱）"
              minlength="3"
              maxlength="32"
              required /></label
          ><button class="secondary" :disabled="busy">开始私信</button></form
        >
        <details
          ><summary>创建群聊</summary
          ><form @submit.prevent="group"
            ><label>群名称<input v-model.trim="title" maxlength="80" required /></label
            ><label
              >成员用户名<textarea
                v-model.trim="handles"
                placeholder="空格或逗号分隔，不包含自己"
                required
              ></textarea></label
            ><button class="primary" :disabled="busy">创建群聊</button></form
          ></details
        >
        <button class="text-button" :disabled="busy" @click="action(loadConversations)">刷新会话</button>
        <button
          v-for="c in conversations"
          :key="c.id"
          class="chat-conversation"
          :class="{ selected: c.id === activeId }"
          @click="select(c)"
          ><strong>{{ c.title }}</strong
          ><small
            >{{ c.kind === 'GROUP' ? `群聊 · ${c.members.length} 人` : '私信'
            }}<span v-if="unread(c)"> · {{ unread(c) }} 未读</span></small
          ></button
        >
        <p v-if="conversationsLoading" role="status">正在读取会话…</p>
        <p v-else-if="!conversations.length && !error" class="empty compact">还没有会话，输入用户名开始交流。</p>
      </aside>
      <section v-if="active" class="chat-thread" :aria-label="`会话：${active.title}`">
        <div class="chat-thread-title"
          ><button class="secondary mobile-only" @click="backToConversations">返回会话</button
          ><h3>{{ active.title }}</h3
          ><button
            class="secondary"
            @click="
              historyToolsOpen = true;
              bookmarkSuccess = '';
            "
            >搜索 / 消息收藏</button
          ><details v-if="active.kind === 'GROUP'"
            ><summary>群成员与管理</summary
            ><div class="chat-management">
              <form v-if="owner" @submit.prevent="rename"
                ><label>群名称<input v-model.trim="editTitle" maxlength="80" required /></label
                ><button class="secondary" :disabled="busy">保存群名</button></form
              >
              <form v-if="owner" @submit.prevent="add"
                ><label
                  >添加成员<input v-model.trim="memberHandle" maxlength="32" required placeholder="公开用户名" /></label
                ><button class="secondary" :disabled="busy">添加</button></form
              >
              <div v-for="m in active.members" :key="m.userId" class="chat-member"
                ><span>{{ m.displayName }} · @{{ m.handle }}{{ m.userId === active.ownerId ? '（群主）' : '' }}</span
                ><button
                  v-if="owner && m.userId !== props.userId"
                  class="text-button"
                  :disabled="busy"
                  @click="remove(m.userId)"
                  >移除</button
                ></div
              >
              <button v-if="owner" class="secondary danger" :disabled="busy" @click="closeGroup">解散群聊</button
              ><button v-else class="secondary" :disabled="busy" @click="remove(props.userId)">退出群聊</button>
            </div></details
          ></div
        >
        <div ref="transcript" class="chat-transcript" aria-live="polite">
          <button v-if="hasOlder" class="text-button" :disabled="loading" @click="older">加载更早消息</button
          ><p v-if="loading">正在读取消息…</p>
          <p v-if="!messages.length && !loading" class="empty compact">还没有可读消息。新成员不显示加入前的群历史。</p>
          <article v-for="m in messages" :key="m.id" class="chat-message" :class="{ mine: m.senderId === props.userId }"
            ><small>{{ m.senderName }} · {{ new Date(m.createdAt).toLocaleString('zh-CN') }}</small
            ><p>{{ m.body }}</p
            ><small v-if="m.senderId === props.userId"
              >已保存<span
                v-if="
                  active.kind === 'DIRECT' &&
                  active.members.some((member) => member.userId !== props.userId && member.readSeq >= m.seq)
                "
              >
                · 对方已读</span
              ></small
            ><button v-else type="button" class="text-button" @click="openReport(m)">举报</button
            ><button type="button" class="text-button" :disabled="busy" @click="bookmark(m)">收藏消息</button></article
          >
          <article
            v-for="p in pending.filter((item) => item.conversationId === activeId)"
            :key="p.clientMessageId"
            class="chat-message mine pending"
            ><p>{{ p.body }}</p
            ><small>{{ p.state === 'sending' ? '等待确认…' : '尚未确认' }}</small
            ><button v-if="p.state === 'retry'" class="text-button" @click="transmit(p)">重试原消息</button></article
          >
        </div>
        <p v-if="blockedDirect" class="hint">你已拉黑对方，不能发送新私信；历史仍可查看。可在安全中心解除。</p>
        <form class="chat-composer" @submit.prevent="send">
          <textarea
            v-model="body"
            aria-label="消息正文"
            maxlength="2000"
            placeholder="Enter 发送，Shift+Enter 换行"
            @keydown.enter.exact.prevent="send"
            :disabled="!connected || blockedDirect"
          ></textarea
          ><button
            class="primary"
            :disabled="!connected || blockedDirect || !body.trim() || pending.some((p) => p.state === 'sending')"
            >发送</button
          ></form
        >
      </section>
      <div v-else class="empty">选择会话，或创建私信与群聊。</div>
    </div>
  </section>
</template>

<style scoped>
.chat-panel {
  width: min(1120px, 95vw);
  height: min(780px, 92vh);
  min-width: 0;
  color: #dce3f0;
  background: #171a23;
  border: 1px solid #303442;
  border-radius: 16px;
  padding: 24px;
  display: flex;
  flex-direction: column;
}
.chat-panel.embedded {
  width: 100%;
  height: calc(100dvh - 180px);
  min-height: 620px;
  max-height: 940px;
  box-shadow: none;
}
.chat-panel input,
.chat-panel textarea {
  width: 100%;
  border: 1px solid #343847;
  border-radius: 10px;
  padding: 10px;
  color: #e5eaf4;
  background: #10131b;
}
.chat-panel label {
  display: grid;
  gap: 7px;
  font-size: 12px;
}
.chat-heading,
.chat-thread-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}
.chat-heading > div {
  flex: 1;
  min-width: 150px;
}
.chat-heading h2 {
  margin: 0 0 6px;
  font-size: 20px;
}
.chat-heading small,
.chat-message small {
  color: #a1abc0;
  font-size: 11px;
}
.chat-layout {
  display: grid;
  grid-template-columns: 245px minmax(0, 1fr);
  gap: 22px;
  min-height: 0;
  flex: 1;
  margin-top: 22px;
}
.chat-conversations {
  overflow: auto;
  border-right: 1px solid #303442;
  padding-right: 18px;
}
.chat-conversations form,
.chat-management form {
  display: grid;
  gap: 10px;
  margin-bottom: 18px;
}
.chat-conversations summary,
.chat-thread summary {
  cursor: pointer;
  padding: 12px 0;
  font-size: 12px;
  color: #c1b7d7;
}
.chat-conversation {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  width: 100%;
  text-align: left;
  padding: 14px;
  margin: 8px 0;
  border: 1px solid #343847;
  border-radius: 11px;
  background: #202532;
  cursor: pointer;
  overflow-wrap: anywhere;
}
.chat-conversation.selected {
  background: #302943;
  border-color: #8c76b8;
}
.chat-conversation strong {
  font-size: 13px;
}
.chat-conversation small {
  font-size: 11px;
  color: #a1abc0;
}
.chat-thread {
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
  margin: 0;
  padding: 0;
  width: auto;
}
.chat-thread-title {
  align-items: center;
  padding-bottom: 14px;
}
.chat-thread-title h3 {
  margin: 0;
  overflow-wrap: anywhere;
}
.chat-thread-title > .mobile-only {
  display: none;
}
.chat-thread-title details {
  max-height: 230px;
  overflow: auto;
  width: 100%;
}
.chat-management {
  padding: 12px;
  background: #10131b;
  border-radius: 10px;
}
.chat-member {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  font-size: 12px;
}
.chat-member .text-button {
  width: auto;
  flex-shrink: 0;
}
.chat-transcript {
  flex: 1;
  overflow: auto;
  padding: 16px;
  background: #10131b;
  border-radius: 12px;
  min-height: 180px;
}
.chat-message {
  max-width: 85%;
  margin: 12px 0;
  background: #212634;
  border-radius: 12px;
  padding: 12px 15px;
  overflow-wrap: anywhere;
}
.chat-message.mine {
  margin-left: auto;
  background: #302a44;
}
.chat-message p {
  white-space: pre-wrap;
  margin: 8px 0;
  font-size: 13px;
}
.chat-message.pending {
  border: 1px dashed #9c83b9;
}
.chat-message .text-button {
  width: auto;
  margin: 5px 8px 0 0;
  font-size: 10px;
}
.chat-composer {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  margin-top: 14px;
}
.chat-composer textarea {
  resize: vertical;
  min-height: 72px;
  flex: 1;
}
.chat-composer button {
  flex-shrink: 0;
}
@media (max-width: 760px) {
  .chat-panel,
  .chat-panel.embedded {
    padding: 16px;
    min-height: 640px;
    height: calc(100dvh - 150px);
  }
  .chat-layout {
    grid-template-columns: minmax(0, 1fr);
    gap: 10px;
  }
  .chat-conversations {
    padding: 0;
    border: 0;
  }
  .chat-layout.has-active .chat-conversations {
    display: none;
  }
  .chat-layout:not(.has-active) > .empty {
    display: none;
  }
  .chat-thread-title > .mobile-only {
    display: inline-flex;
  }
  .chat-message {
    max-width: 98%;
  }
  .chat-composer {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
