<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue';
import type { ChatMessage } from '../services/chat';
import {
  chatSafetyApi,
  type ChatBlock,
  type ChatReport,
  type ModerationItem,
  type ReportReason,
  type ReportStatus,
} from '../services/chat-safety';

const props = defineProps<{
  /** 当前已认证用户，权限仍由服务端校验。 */
  userId: string;
  /** 从有权读取的历史中选中的消息；未发起举报时缺省。 */
  message?: ChatMessage;
}>();
const emit = defineEmits<{
  /** 关闭安全中心，不关闭外部聊天连接。 */
  close: [];
  /** 拉黑设置提交成功，通知外层刷新自己的设置。 */
  blocksChanged: [];
}>();
const tab = ref<'blocks' | 'reports' | 'moderation'>(props.message ? 'reports' : 'blocks');
const loading = ref(false),
  busy = ref(false),
  error = ref(''),
  success = ref('');
const blocks = ref<ChatBlock[]>([]),
  reports = ref<ChatReport[]>([]),
  queue = ref<ModerationItem[]>([]);
const canModerate = ref(false),
  capabilityError = ref('');
const blockHandle = ref(''),
  reason = ref<ReportReason>('HARASSMENT'),
  detail = ref('');
const selectedMessage = ref(props.message);
const status = ref<ReportStatus>('PENDING'),
  note = ref(''),
  selected = ref<ModerationItem>();
const after = ref<string>(),
  hasMore = ref(false);
const labels: Record<ReportStatus, string> = { PENDING: '待审核', RESOLVED: '已受理结案', REJECTED: '不予受理' };
let disposed = false,
  generation = 0;
let readController: AbortController | undefined;
const writeController = new AbortController();
const failure = (e: unknown) => (e instanceof Error ? e.message : '聊天安全操作失败');

/** 切页/刷新时取消旧请求并核对代次，避免旧队列覆盖新状态。 */
async function load(more = false) {
  const current = ++generation;
  readController?.abort();
  readController = new AbortController();
  const signal = readController.signal,
    targetTab = tab.value,
    cursor = more ? after.value : undefined;
  loading.value = true;
  error.value = '';
  try {
    if (targetTab === 'blocks') {
      const page = await chatSafetyApi.blocks(cursor, signal);
      if (disposed || current !== generation) return;
      blocks.value = more ? [...blocks.value, ...page] : page;
      after.value = page.at(-1)?.id;
      hasMore.value = page.length === 100;
    } else if (targetTab === 'reports') {
      const page = await chatSafetyApi.reports(cursor, signal);
      if (disposed || current !== generation) return;
      reports.value = more ? [...reports.value, ...page] : page;
      after.value = page.at(-1)?.id;
      hasMore.value = page.length === 50;
    } else {
      const page = await chatSafetyApi.queue(status.value, cursor, signal);
      if (disposed || current !== generation) return;
      queue.value = more ? [...queue.value, ...page] : page;
      after.value = page.at(-1)?.report.id;
      hasMore.value = page.length === 50;
    }
  } catch (e) {
    if (!disposed && current === generation && !signal.aborted) error.value = failure(e);
  } finally {
    if (!disposed && current === generation) loading.value = false;
  }
}
async function action(operation: () => Promise<void>) {
  if (busy.value || disposed) return;
  busy.value = true;
  error.value = '';
  success.value = '';
  try {
    await operation();
    if (!disposed) await load();
  } catch (e) {
    if (!disposed) error.value = failure(e);
  } finally {
    if (!disposed) busy.value = false;
  }
}
const block = () =>
  action(async () => {
    const saved = await chatSafetyApi.block(blockHandle.value.trim(), writeController.signal);
    if (disposed) return;
    blockHandle.value = '';
    success.value = `已拉黑 @${saved.handle}；已有共同群的消息不会被自动隐藏。`;
    emit('blocksChanged');
  });
const unblock = (item: ChatBlock) =>
  action(async () => {
    if (!confirm(`确认解除对 @${item.handle} 的拉黑？对方的设置不会改变。`)) return;
    await chatSafetyApi.unblock(item.targetId, writeController.signal);
    if (!disposed) {
      success.value = '已解除本人的拉黑设置。';
      emit('blocksChanged');
    }
  });
const report = () =>
  action(async () => {
    if (!selectedMessage.value || selectedMessage.value.senderId === props.userId) return;
    const result = await chatSafetyApi.report(
      selectedMessage.value,
      reason.value,
      detail.value.trim(),
      writeController.signal,
    );
    if (!disposed) {
      selectedMessage.value = undefined;
      detail.value = '';
      success.value = `举报已受理，编号 ${result.id}。当前状态：${labels[result.status]}。`;
    }
  });
const review = (decision: 'RESOLVED' | 'REJECTED') =>
  action(async () => {
    if (!selected.value || !note.value.trim()) return;
    if (!confirm('确认提交最终审核决定？结案不能再次改写，不会自动封禁账号。')) return;
    await chatSafetyApi.review(selected.value.report, decision, note.value.trim(), writeController.signal);
    if (!disposed) {
      selected.value = undefined;
      note.value = '';
      success.value = '审核决定和审计记录已保存。';
    }
  });
watch(
  () => props.message,
  (message) => {
    selectedMessage.value = message;
    detail.value = '';
    if (message) tab.value = 'reports';
  },
);
watch([tab, status], () => {
  selected.value = undefined;
  note.value = '';
  hasMore.value = false;
  void load();
});
onMounted(() => {
  void load();
  void chatSafetyApi
    .capabilities(writeController.signal)
    .then((value) => {
      if (!disposed) canModerate.value = value.moderation;
    })
    .catch((e) => {
      if (!disposed) capabilityError.value = `审核能力检查失败：${failure(e)}`;
    });
});
onUnmounted(() => {
  disposed = true;
  generation++;
  readController?.abort();
  writeController.abort();
});
</script>

<template>
  <section class="safety-panel" aria-label="聊天安全中心">
    <header
      ><div><h3>聊天安全中心</h3><small>拉黑、举报与人工审核 · 不替代紧急求助</small></div
      ><button class="secondary" @click="emit('close')">返回聊天</button></header
    >
    <nav aria-label="安全中心栏目"
      ><button :class="{ active: tab === 'blocks' }" @click="tab = 'blocks'">拉黑设置</button
      ><button :class="{ active: tab === 'reports' }" @click="tab = 'reports'">我的举报</button
      ><button v-if="canModerate" :class="{ active: tab === 'moderation' }" @click="tab = 'moderation'"
        >人工审核</button
      ></nav
    >
    <p v-if="error" class="form-error" role="alert">{{ error }}</p
    ><p v-if="success" class="success" role="status">{{ success }}</p
    ><p v-if="capabilityError" class="hint">{{ capabilityError }}</p>
    <div class="safety-content">
      <template v-if="tab === 'blocks'">
        <p class="hint">任一方拉黑后不能发送新私信或相互邀请入群。历史仍可查看；已有共同群的消息仍可见。</p>
        <form @submit.prevent="block"
          ><label
            >公开用户名<input
              v-model.trim="blockHandle"
              minlength="3"
              maxlength="32"
              required
              placeholder="不是邮箱" /></label
          ><button class="secondary" :disabled="busy || !blockHandle">拉黑用户</button></form
        >
        <article v-for="item in blocks" :key="item.id"
          ><div
            ><strong>{{ item.displayName }} · @{{ item.handle }}</strong
            ><small>{{ new Date(item.createdAt).toLocaleString('zh-CN') }}</small></div
          ><button class="secondary" :disabled="busy" @click="unblock(item)">解除拉黑</button></article
        >
        <p v-if="!loading && !blocks.length && !error" class="empty">没有主动拉黑的用户。</p>
      </template>
      <template v-else-if="tab === 'reports'">
        <form v-if="selectedMessage" class="report-form" @submit.prevent="report"
          ><h4>举报 {{ selectedMessage.senderName }} 的消息</h4><blockquote>{{ selectedMessage.body }}</blockquote
          ><label
            >原因<select v-model="reason"
              ><option value="HARASSMENT">骚扰</option
              ><option value="SPAM">垃圾信息</option
              ><option value="THREAT">威胁</option
              ><option value="OTHER">其他</option></select
            ></label
          ><label
            >说明<textarea
              v-model.trim="detail"
              required
              maxlength="500"
              placeholder="描述问题，避免填写无关个人隐私。"
            ></textarea></label
          ><button class="primary" :disabled="busy || !detail.trim()">提交真实消息举报</button
          ><button type="button" class="secondary" :disabled="busy" @click="selectedMessage = undefined"
            >取消举报</button
          ></form
        >
        <article v-for="item in reports" :key="item.id" class="report-item"
          ><div
            ><strong>{{ labels[item.status] }} · {{ item.reason }}</strong
            ><small>{{ new Date(item.createdAt).toLocaleString('zh-CN') }} · {{ item.id }}</small
            ><p>{{ item.detail }}</p
            ><p v-if="item.reviewNote">审核说明：{{ item.reviewNote }}</p
            ><small v-if="item.reviewedAt"
              >处理时间：{{ new Date(item.reviewedAt).toLocaleString('zh-CN') }}</small
            ></div
          ></article
        >
        <p v-if="!loading && !reports.length && !error" class="empty">尚未提交举报。在聊天消息旁选择「举报」。</p>
      </template>
      <template v-else>
        <p class="hint">证据仅审核员可读，请勿复制到公共频道。结案必须填写说明，不会自动封禁账号。</p>
        <label
          >队列状态<select v-model="status"
            ><option value="PENDING">待审核</option
            ><option value="RESOLVED">已受理结案</option
            ><option value="REJECTED">不予受理</option></select
          ></label
        >
        <article v-for="item in queue" :key="item.report.id" class="report-item"
          ><div
            ><strong>{{ labels[item.report.status] }} · {{ item.report.reason }}</strong
            ><small>举报人 {{ item.reporterId }} · 目标 {{ item.report.reportedUserId }}</small
            ><p>举报说明：{{ item.report.detail }}</p
            ><blockquote>{{ item.evidenceBody }}</blockquote
            ><p v-if="item.report.reviewNote">结论：{{ item.report.reviewNote }}</p
            ><button
              v-if="
                item.report.status === 'PENDING' &&
                item.reporterId !== props.userId &&
                item.report.reportedUserId !== props.userId
              "
              class="secondary"
              :disabled="busy"
              @click="
                selected = item;
                note = '';
              "
              >处理此举报</button
            ><small v-else-if="item.report.status === 'PENDING'">涉及本人，必须交由其他审核员处理。</small></div
          ></article
        >
        <form v-if="selected" class="review-form" @submit.prevent="review('RESOLVED')"
          ><h4>最终处理 · {{ selected.report.id }}</h4
          ><label>人工审核说明<textarea v-model.trim="note" maxlength="500" required></textarea></label
          ><button class="primary" :disabled="busy || !note.trim()">受理并结案</button
          ><button type="button" class="secondary" :disabled="busy || !note.trim()" @click="review('REJECTED')"
            >不予受理</button
          ></form
        >
        <p v-if="!loading && !queue.length && !error" class="empty">当前状态没有举报。</p>
      </template>
      <p v-if="loading" role="status">正在读取服务端记录…</p>
      <div class="pagination"
        ><button class="secondary" :disabled="busy || loading" @click="load()">刷新</button
        ><button v-if="hasMore" class="secondary" :disabled="busy || loading" @click="load(true)">加载更多</button></div
      >
    </div>
  </section>
</template>

<style scoped>
.safety-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  flex: 1;
  color: #dce3f0;
  background: #12151e;
}
.safety-panel header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
}
.safety-panel h3 {
  margin: 0 0 8px;
}
.safety-panel small {
  display: block;
  color: #9aa9bd;
  overflow-wrap: anywhere;
}
.safety-panel nav {
  display: flex;
  gap: 8px;
  margin: 16px 0;
  flex-wrap: wrap;
}
.safety-panel nav button {
  padding: 10px 16px;
  border: 1px solid #343847;
  border-radius: 10px;
  background: #191f2a;
  color: #dce3f0;
}
.safety-panel nav button.active {
  border-color: #78a4d5;
  background: #26374d;
}
.safety-content {
  overflow: auto;
  min-height: 0;
  padding-right: 6px;
}
.safety-panel form {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  align-items: flex-end;
  padding: 14px;
  background: #191f2a;
  border-radius: 12px;
  margin: 12px 0;
}
.safety-panel label {
  display: grid;
  gap: 6px;
  min-width: 180px;
  flex: 1;
}
.safety-panel input,
.safety-panel textarea,
.safety-panel select {
  width: 100%;
  padding: 10px;
  border: 1px solid #343847;
  border-radius: 8px;
  color: #e5eaf4;
  background: #0b0d13;
}
.safety-panel textarea {
  min-height: 90px;
  resize: vertical;
}
.safety-panel article {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: center;
  padding: 16px;
  border-bottom: 1px solid #303442;
}
.safety-panel article > div {
  min-width: 0;
  flex: 1;
}
.safety-panel p,
.safety-panel blockquote {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.safety-panel blockquote {
  margin: 12px 0;
  padding: 12px;
  border-left: 3px solid #6b95c7;
  background: #0b0d13;
}
.safety-panel .report-form,
.safety-panel .review-form {
  display: grid;
  grid-template-columns: 1fr;
}
.success {
  color: #9ad2b8;
}
.pagination {
  display: flex;
  gap: 12px;
  padding: 16px 0;
}
.hint {
  color: #a8b5c8;
  font-size: 13px;
}
@media (max-width: 600px) {
  .safety-panel header {
    align-items: flex-start;
  }
  .safety-panel article {
    flex-direction: column;
    align-items: stretch;
  }
  .safety-panel label {
    min-width: 0;
  }
}
</style>
