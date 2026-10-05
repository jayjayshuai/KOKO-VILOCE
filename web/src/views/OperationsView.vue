<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue';
import { AlertTriangle, CheckCircle2, Clock, FileClock, RefreshCw, ShieldCheck } from 'lucide-vue-next';
import { operationsApi } from '../services/operations';
import { useOperationsRetryStore } from '../stores/operations-retry';
import { useOutboxWorkspace } from '../composables/outbox-workspace';

defineProps<{
  /** 页面由已恢复的账号 key 隔离；身份最终由服务端认证，不能靠此属性赋权。 */
  userId: string;
}>();
const secure = location.protocol === 'https:' || ['localhost', '127.0.0.1', '[::1]'].includes(location.hostname);
const state = useOutboxWorkspace(operationsApi, useOperationsRetryStore(), secure);
const {
  access,
  accessError,
  accessLoading,
  domain,
  rows,
  cursor,
  loaded,
  loading,
  selected,
  audits,
  auditCursor,
  detailLoading,
  auditLoading,
  error,
  detailError,
  actionError,
  reason,
  password,
  busy,
  notice,
  pending,
  canRead,
  canReplay,
} = state;
/** 只为新准备且仍有效的命令衔接焦点，不把迟到渲染的焦点抢回旧命令。 */
const passwordInput = ref<HTMLInputElement>();
async function prepareReplay() {
  const previousRequest = pending.value?.command.requestId;
  state.prepare();
  const prepared = pending.value;
  if (!prepared || prepared.phase !== 'prepared' || prepared.command.requestId === previousRequest) return;
  await nextTick();
  if (pending.value?.command.requestId === prepared.command.requestId && !busy.value) passwordInput.value?.focus();
}
const domains = [
  { code: 'identity' as const, title: '身份事件', detail: '创作者关注' },
  { code: 'community' as const, title: '社区事件', detail: '内容评论' },
  { code: 'live' as const, title: '直播事件', detail: '开播通知' },
];
const statuses: Record<string, string> = {
  DEAD: '自动重试已耗尽',
  PENDING: '等待投递',
  RETRY: '等待重试',
  IN_FLIGHT: '投递处理中',
  SENT: '发送已确认',
};
const stamp = (value: string | null) => (value ? value.replace('T', ' ') : '—');
onMounted(state.loadAccess);
</script>

<template>
  <section class="workspace-section operations-workspace">
    <div class="workspace-section-heading"
      ><div
        ><p class="eyebrow">DELIVERY OPERATIONS</p><h1>通知投递运维</h1>
        <p>读取真实死信、审计和投递事实。人工重放只重新排队，不代表通知已经送达。</p></div
      >
      <button class="secondary" :disabled="accessLoading || busy" @click="state.loadAccess"
        ><RefreshCw :size="16" />重新读取权限</button
      >
    </div>
    <div class="ops-security-note"
      ><ShieldCheck :size="18" /><span>当前事实权限 · 本人密码二次确认 · 同库追加审计</span
      ><small>无默认管理员</small></div
    >
    <p v-if="accessLoading" class="workspace-skeleton" role="status">正在读取本人运营能力…</p>
    <div v-else-if="accessError" class="workspace-card ops-gate" role="alert"
      ><AlertTriangle :size="28" /><h2>运营能力暂不可读取</h2><p>{{ accessError }}</p
      ><button class="secondary" @click="state.loadAccess">重试权限读取</button></div
    >
    <div v-else-if="!canRead" class="workspace-card ops-gate"
      ><ShieldCheck :size="28" /><h2>{{ access?.enabled ? '当前账号没有运营读权限' : '运营功能尚未启用' }}</h2
      ><p>权限来自服务端当前角色。普通注册不会获得运营授权，也不能读取死信内容。</p></div
    >
    <template v-else>
      <div class="ops-domain-selector" role="group" aria-label="通知事件业务域"
        ><button
          v-for="item in domains"
          :key="item.code"
          :class="{ active: domain === item.code }"
          :aria-pressed="domain === item.code"
          :disabled="busy || !!pending"
          @click="state.chooseDomain(item.code)"
          ><strong>{{ item.title }}</strong
          ><small>{{ item.detail }}</small></button
        ></div
      >
      <div class="ops-summary"
        ><article class="workspace-card"
          ><span>当前角色</span><strong>{{ access?.roles.join(' / ') || '无角色' }}</strong></article
        >
        <article class="workspace-card"
          ><span>本页已读取死信</span><strong>{{ rows.length }}</strong
          ><small>不是全站总数</small></article
        >
        <article class="workspace-card"
          ><span>当前操作能力</span><strong>{{ canReplay ? '读取与受理重放' : '只读审计' }}</strong
          ><small>服务端逐请求再次检查</small></article
        ></div
      >
      <p v-if="!secure" class="ops-warning" role="status"
        >当前是公网明文 HTTP，密码确认已禁用。正式开放敏感运营操作必须先配置 HTTPS。</p
      >
      <div class="ops-columns">
        <section class="workspace-card ops-queue"
          ><div class="workspace-card-heading"
            ><h2>自动重试已耗尽</h2
            ><button class="secondary" :disabled="loading || busy" @click="state.load(true)"
              ><RefreshCw :size="15" />刷新队列</button
            ></div
          >
          <p class="workspace-note"
            >按创建时间与事件 ID 降序分页。新产生或状态变化的记录需刷新；旧快照不是当前受理依据。</p
          >
          <p v-if="error" class="form-error" role="alert">{{ error }}</p>
          <div v-if="loaded && !rows.length && !error && !loading" class="workspace-empty"
            ><CheckCircle2 :size="28" /><h3>当前页没有死信</h3
            ><p>仅代表当前业务域读取结果，不证明全站没有积压。</p></div
          >
          <div class="ops-event-list" role="region" aria-label="死信事件列表" tabindex="0"
            ><button
              v-for="event in rows"
              :key="event.id"
              class="ops-event"
              :class="{ active: selected?.id === event.id }"
              :disabled="busy || (!!pending && pending.command.eventId !== event.id)"
              @click="state.select(event.id)"
            >
              <span class="ops-event-top"
                ><strong>{{ event.eventType }}</strong
                ><span>{{ event.attempts }} 次尝试 · 代次 {{ event.replayGeneration }}</span></span
              >
              <span class="ops-id">{{ event.id }}</span
              ><span class="ops-preview">{{ event.lastError || '没有错误摘要' }}</span
              ><small>创建 {{ stamp(event.createdAt) }}（库时间）</small>
            </button></div
          >
          <p v-if="loading" class="workspace-note" role="status">正在读取死信页…</p>
          <button v-if="cursor" class="secondary load-more" :disabled="loading || busy" @click="state.load(false)"
            >加载下一页</button
          >
        </section>
        <section class="workspace-card ops-detail"
          ><div class="workspace-card-heading"><h2>事件与审计</h2><FileClock :size="18" /></div>
          <p v-if="detailLoading" class="workspace-skeleton" role="status">正在读取当前事件事实…</p>
          <p v-if="detailError" class="form-error" role="alert">{{ detailError }}</p>
          <div v-if="!selected && !detailLoading" class="workspace-empty"
            ><Clock :size="28" /><h3>选择一条事件</h3><p>读取详情、受理原因和不可覆写的审计快照。</p></div
          >
          <template v-if="selected"
            ><div class="ops-current"
              ><span class="ops-status" :class="selected.status.toLowerCase()">{{
                statuses[selected.status] || selected.status
              }}</span>
              <button class="secondary" :disabled="detailLoading || busy" @click="state.select(selected.id)"
                >刷新事件事实</button
              ></div
            >
            <p class="ops-id">{{ selected.id }}</p
            ><dl class="ops-facts"
              ><dt>原目标账号</dt><dd>{{ selected.recipientId }}</dd
              ><dt>原操作者</dt><dd>{{ selected.actorId }}</dd> <dt>本轮 / 累计尝试</dt
              ><dd>{{ selected.attempts }} / {{ selected.totalAttempts }}</dd
              ><dt>人工代次</dt><dd>{{ selected.replayGeneration }} / 10</dd> <dt>业务资源</dt
              ><dd>{{ selected.resourceId }}</dd
              ><dt>摘要</dt><dd>{{ selected.summary }}</dd
              ><dt>最后失败</dt><dd>{{ selected.lastError || '—' }}</dd> <dt>发送确认</dt
              ><dd>{{ stamp(selected.sentAt) }}（库时间）</dd></dl
            >
            <p class="workspace-note">SENT 仅表示生产者确认发送，不是收件箱消费完成。领取令牌不会返回给运营客户端。</p>
            <div v-if="canReplay && !pending" class="ops-prepare"
              ><label for="outbox-reason">重放原因 / 工单（10～500 字符）</label>
              <textarea
                id="outbox-reason"
                v-model="reason"
                maxlength="500"
                :disabled="busy || selected.status !== 'DEAD' || !secure"
                rows="3"
                placeholder="先调查故障根因，再明确说明受理原因"
              />
              <button
                class="primary"
                :disabled="busy || selected.status !== 'DEAD' || selected.replayGeneration >= 10 || !secure"
                @click="prepareReplay"
                >固定命令并继续本人确认</button
              ></div
            >
            <h3 class="ops-audit-heading">追加受理审计</h3
            ><p v-if="auditLoading" class="workspace-note" role="status">正在读取审计…</p>
            <p v-if="!audits.length && !auditLoading && !detailError" class="workspace-note"
              >当前事件尚无人工受理审计。</p
            >
            <div v-if="audits.length" class="ops-audit-scroll" role="region" aria-label="追加受理审计列表" tabindex="0">
              <ol class="ops-audits"
                ><li v-for="audit in audits" :key="audit.requestId"
                  ><strong>代次 {{ audit.expectedGeneration }} → {{ audit.acceptedGeneration }}</strong
                  ><small>操作者 {{ audit.operatorId }} · {{ stamp(audit.createdAt) }}（库时间）</small
                  ><p>{{ audit.reason }}</p
                  ><details
                    ><summary>查看原失败与请求 ID</summary><p class="ops-id">{{ audit.requestId }}</p
                    ><p>{{ audit.previousError || '没有原失败摘要' }}</p
                    ><small
                      >原轮次 {{ audit.previousAttempts }} 次 · 累计 {{ audit.totalAttemptsSnapshot }} 次</small
                    ></details
                  ></li
                ></ol
              >
            </div>
            <button
              v-if="auditCursor !== null"
              class="secondary"
              :disabled="auditLoading || busy"
              @click="state.loadAudits(false)"
              >加载更早审计</button
            >
          </template>
        </section>
      </div>
      <section v-if="pending" class="workspace-card ops-confirmation" aria-labelledby="replay-heading"
        ><h2 id="replay-heading">{{
          pending.phase === 'accepted'
            ? '已受理，不代表已送达'
            : pending.phase === 'uncertain'
              ? '原请求结果待确认'
              : '本人密码二次确认'
        }}</h2>
        <p class="workspace-note"
          >命令仅在当前账号会话的内存中跨页面保留。刷新浏览器会丢失未确认命令，请先记录工单与原请求
          ID；密码和确认令牌不保存。</p
        >
        <dl class="ops-facts"
          ><dt>原请求 ID</dt><dd class="ops-id">{{ pending.command.requestId }}</dd
          ><dt>固定域 / 事件</dt><dd>{{ pending.domain }} / {{ pending.command.eventId }}</dd
          ><dt>预期代次 / 原因</dt><dd>{{ pending.command.expectedGeneration }} / {{ pending.command.reason }}</dd></dl
        >
        <p v-if="pending.receipt" class="ops-success" role="status"
          >受理代次 {{ pending.receipt.generation }} · {{ stamp(pending.receipt.acceptedAt) }}（库时间）</p
        >
        <form
          v-if="pending.phase !== 'accepted' && canReplay"
          class="ops-confirm-form"
          @submit.prevent="state.confirmAndReplay"
          ><label for="outbox-password">本人密码（仅本次确认）</label
          ><input
            id="outbox-password"
            ref="passwordInput"
            v-model="password"
            type="password"
            autocomplete="off"
            maxlength="72"
            :disabled="busy || !secure"
          />
          <button class="primary" :disabled="busy || !secure || !password">{{
            busy ? '正在核验 / 受理…' : pending.phase === 'uncertain' ? '重新确认并重试同一命令' : '确认并受理原命令'
          }}</button></form
        >
        <div class="ops-command-actions"
          ><button class="secondary" :disabled="busy" @click="state.reconcile">只读查询原请求</button
          ><button v-if="pending.phase !== 'uncertain'" class="secondary" :disabled="busy" @click="state.finish">{{
            pending.phase === 'accepted' ? '结束此条处理' : '取消未发送的命令'
          }}</button
          ><button class="secondary" :disabled="busy || detailLoading" @click="state.select(pending.command.eventId)"
            >刷新原事件事实</button
          ></div
        >
        <p v-if="notice" class="ops-success" role="status">{{ notice }}</p>
      </section>
      <p v-if="actionError" class="form-error" role="alert">{{ actionError }}</p>
    </template>
  </section>
</template>

<style scoped>
.ops-security-note {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 18px;
  border: 1px solid #33465d;
  border-radius: 12px;
  color: #a7d7d0;
  background: #14242b;
  margin-bottom: 20px;
}
.ops-security-note small {
  margin-left: auto;
  color: #8fa7b9;
}
.ops-gate {
  text-align: center;
  padding: 44px 24px;
}
.ops-gate p {
  max-width: 620px;
  margin: 12px auto 24px;
  color: #9eb1c5;
  line-height: 1.7;
}
.ops-domain-selector {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
  margin: 20px 0;
}
.ops-domain-selector button {
  text-align: left;
  padding: 15px 18px;
  color: #b8c7d8;
  border: 1px solid #2b3a50;
  border-radius: 12px;
  background: #142033;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.ops-domain-selector button.active {
  border-color: #63b5a8;
  background: #183335;
  color: #d2f2e9;
}
.ops-domain-selector small {
  color: #8fa3ba;
}
.ops-summary {
  display: grid;
  grid-template-columns: 2fr 1fr 1.4fr;
  gap: 12px;
  margin: 18px 0;
}
.ops-summary article {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.ops-summary span,
.ops-summary small {
  color: #91a5bd;
  font-size: 12px;
}
.ops-summary strong {
  font-size: 15px;
  overflow-wrap: anywhere;
}
.ops-warning {
  border-left: 3px solid #d9ac61;
  background: #332d22;
  padding: 14px 16px;
  color: #e2c68f;
  border-radius: 5px;
  line-height: 1.6;
}
.ops-columns {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.15fr);
  gap: 18px;
}
.ops-columns > section {
  min-width: 0;
}
.ops-event-list {
  display: flex;
  flex-direction: column;
  gap: 9px;
  margin: 18px 0;
}
.ops-event {
  display: flex;
  flex-direction: column;
  gap: 9px;
  text-align: left;
  border: 1px solid #2a3b53;
  border-radius: 10px;
  background: #121e30;
  padding: 15px;
  color: #c4d2e1;
  width: 100%;
}
.ops-event.active {
  border-color: #63b5a8;
  background: #173036;
}
.ops-event-top {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  font-size: 12px;
}
.ops-event-top > span,
.ops-event small {
  color: #91a4bc;
}
.ops-preview {
  font-size: 12px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.ops-id {
  font-family: ui-monospace, monospace;
  font-size: 12px;
  overflow-wrap: anywhere;
  user-select: all;
}
.ops-current,
.ops-command-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}
.ops-current {
  justify-content: space-between;
  margin: 16px 0;
}
.ops-status {
  padding: 6px 9px;
  border-radius: 6px;
  background: #24364c;
  font-size: 12px;
}
.ops-status.dead {
  color: #ffd0a3;
  background: #443021;
}
.ops-status.sent {
  color: #a9dfc3;
  background: #213f31;
}
.ops-facts {
  display: grid;
  grid-template-columns: 118px minmax(0, 1fr);
  gap: 12px 15px;
  font-size: 12px;
  line-height: 1.6;
  margin: 20px 0;
}
.ops-facts dt {
  color: #92a7bf;
}
.ops-facts dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.ops-prepare,
.ops-confirm-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin: 20px 0;
}
.ops-prepare label,
.ops-confirm-form label {
  font-size: 13px;
  color: #c0d0e0;
}
.ops-prepare textarea,
.ops-confirm-form input {
  box-sizing: border-box;
  background: #0e1928;
  color: #d7e4ee;
  border: 1px solid #354963;
  border-radius: 8px;
  padding: 12px;
  width: 100%;
  font: inherit;
}
.ops-prepare button,
.ops-confirm-form button {
  align-self: flex-start;
}
.ops-audit-heading {
  font-size: 14px;
  margin-top: 28px;
}
.ops-audits {
  list-style: none;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.ops-audits li {
  border-left: 2px solid #406a70;
  padding: 10px 14px;
  background: #142234;
  border-radius: 0 8px 8px 0;
  font-size: 12px;
  overflow-wrap: anywhere;
}
.ops-audits li > small {
  display: block;
  margin: 8px 0;
  color: #97a9be;
}
.ops-audits details {
  color: #9fb5cb;
}
.ops-audits summary {
  cursor: pointer;
}
.ops-confirmation {
  margin-top: 20px;
  border-color: #52796e;
}
.ops-confirmation h2 {
  font-size: 18px;
  margin-top: 0;
}
.ops-confirm-form {
  max-width: 460px;
}
.ops-success {
  color: #a7ddbf;
  line-height: 1.6;
}
/* 长队列独立滚动，详情和原命令不再被二十条死信推至数屏之外；键盘仍可访问全部行。 */
.ops-event-list {
  max-height: min(62vh, 720px);
  overflow: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
}
.ops-event-list:focus-visible {
  outline: 2px solid #63b5a8;
  outline-offset: 4px;
  border-radius: 8px;
}
.ops-event {
  flex-shrink: 0;
}
/* 每条审计可展开失败快照，限制区域高度并保留键盘访问，不把确认区域推至无界页面尾部。 */
.ops-audit-scroll {
  max-height: min(62vh, 680px);
  overflow: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
}
.ops-audit-scroll:focus-visible {
  outline: 2px solid #63b5a8;
  outline-offset: 4px;
  border-radius: 8px;
}
.ops-audit-scroll .ops-audits {
  margin: 0;
}
@media (max-width: 1100px) {
  .ops-columns {
    grid-template-columns: 1fr;
  }
  .ops-summary {
    grid-template-columns: 1fr 1fr;
  }
  .ops-summary article:first-child {
    grid-column: 1/-1;
  }
}
@media (max-width: 600px) {
  .ops-domain-selector {
    grid-template-columns: 1fr;
  }
  .ops-summary {
    grid-template-columns: 1fr;
  }
  .ops-summary article:first-child {
    grid-column: auto;
  }
  .ops-security-note {
    align-items: flex-start;
    flex-wrap: wrap;
  }
  .ops-security-note small {
    margin-left: 28px;
  }
  .ops-facts {
    grid-template-columns: 1fr;
    gap: 6px;
  }
  .ops-facts dd {
    margin-bottom: 10px;
  }
  .ops-event-top {
    flex-direction: column;
  }
  .ops-command-actions {
    align-items: stretch;
    flex-direction: column;
  }
}
@media (max-width: 600px) {
  .ops-event-list,
  .ops-audit-scroll {
    max-height: min(45vh, 480px);
  }
}
</style>
