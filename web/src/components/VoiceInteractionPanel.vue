<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useVoiceInteractionWorkspace } from '../composables/voice-interaction-workspace';
import VoiceMediaPlanPanel from './VoiceMediaPlanPanel.vue';
import ControlledVoiceMediaPanel from './ControlledVoiceMediaPanel.vue';
import type { VoiceCommandType, VoiceCommand, SeatRequest } from '../services/voice-interaction';
const props = defineProps<{
  /** 仅展示用名称快照，权限仍绑定roomId。 */ roomTitle?: string;
  /** 本次操作房间。 */ roomId: string;
  /** 当前可信账号，HTTP仍用Cookie。 */ userId: string;
  /** 本次登录轮次。 */ sessionRevision: number;
}>();
const emit = defineEmits<{
  /** 不撤销后台未知写入。 */ close: [];
  /** 父页面禁止并行操作。 */ busy: [value: boolean];
}>();
const state = useVoiceInteractionWorkspace(props);
const { actions, actionLoading, actionError, loadActions } = state;
const {
  capabilities,
  snapshot,
  loading,
  busy,
  readError,
  writeError,
  pending,
  success,
  load,
  prepareAction,
  actionPreparing,
  join,
  command,
  transmit,
  discard,
  fresh,
  lastVerifiedAt,
  online,
  checkReceipt,
} = state;
/** 目标只来自服务器名单，服务端仍当前读授权。 */ const target = ref('');
/** 房主管理与在麦态不混用。 */ const manager = computed(
  () => fresh.value && !!snapshot.value?.mySessionId && ['OWNER', 'ADMIN'].includes(snapshot.value.myRole || ''),
);
const owner = computed(() => fresh.value && !!snapshot.value?.mySessionId && snapshot.value.myRole === 'OWNER');
/** 高级进度只在用户展开时读取，不为普通通话反复请求后台诊断。 */
const showMediaPlan = ref(false);
/** 仅切换使用引导，不授予发布权限，也不自动操作麦克风。 */
const intent = ref<'listen' | 'speak'>('listen');
/** 本人麦位与空位均来自已核验快照，不从界面选择推断权限。 */
const mySeat = computed(() =>
  snapshot.value?.seats.find((seat) => seat.state === 'ON_MIC' && seat.userId === props.userId),
);
const freeSeat = computed(() => snapshot.value?.seats.find((seat) => seat.state === 'EMPTY'));
/** 本人仍在等待的申请，防止新手重复提交新请求。 */
const myApplication = computed(() =>
  snapshot.value?.requests.find((request) => request.type === 'APPLY' && request.userId === props.userId),
);
/** 未确认原请求或当前权限时不新建命令。 */ const disabled = computed(
  () => !fresh.value || !online.value || loading.value || busy.value || actionPreparing.value || !!pending.value,
);
/** 固定状态名称，不声称已经真实发声。 */ const labels = {
  EMPTY: '空麦',
  LOCKED: '已锁',
  RESERVED: '预约中',
  ON_MIC: '在麦状态 · 非音轨确认',
};
watch(
  () => busy.value || actionPreparing.value,
  (value) => emit('busy', value),
  { flush: 'sync' },
);
watch(
  () => [snapshot.value?.members, manager.value, props.userId] as const,
  ([members]) => {
    if (target.value && !members?.some((member) => member.userId === target.value)) target.value = '';
    if (!target.value && manager.value && members?.some((member) => member.userId === props.userId))
      target.value = props.userId;
  },
  { flush: 'sync' },
);
/** 新操作发送前核验多人房最新版本；不重试任何已发送/结果未知的请求。 */
async function execute(
  type: VoiceCommandType,
  args: Partial<Pick<VoiceCommand, 'seatNo' | 'targetUserId' | 'seatRequestId' | 'value'>> = {},
) {
  if (disabled.value) return;
  const scope = [props.roomId, props.userId, props.sessionRevision] as const;
  const input = { ...args };
  if (
    ['KICK', 'TRANSFER', 'ADMIN'].includes(type) &&
    !confirm('确认修改房间权限或席位？撤权会清退旧媒体连接，不能由此远程开启他人的麦克风。')
  )
    return;
  if (!(await prepareAction())) return;
  if (disabled.value || scope[0] !== props.roomId || scope[1] !== props.userId || scope[2] !== props.sessionRevision)
    return;
  void command(type, input);
}
/** 入房也是一次用户明确写入，先读最新能力，失败/换身份不发送。 */
async function joinRoom() {
  if (disabled.value) return;
  const scope = [props.roomId, props.userId, props.sessionRevision] as const;
  if (!(await prepareAction())) return;
  if (
    disabled.value ||
    snapshot.value?.mySessionId ||
    scope[0] !== props.roomId ||
    scope[1] !== props.userId ||
    scope[2] !== props.sessionRevision
  )
    return;
  void join();
}
function canReview(request: SeatRequest) {
  return request.type === 'APPLY' ? manager.value : request.userId === props.userId;
}
</script>
<template>
  <section class="workspace-card voice-interaction" aria-label="语音房互动核心" :aria-busy="loading || busy">
    <div class="workspace-card-heading"
      ><h2>进入语音房</h2
      ><button type="button" class="secondary" :disabled="busy || actionPreparing" @click="emit('close')"
        >关闭面板</button
      ></div
    >
    <p class="hint">{{ roomTitle || '当前房间' }}</p>
    <p class="hint" role="note"
      >手机与电脑请用不同账号，保持本页在前台；一端戴耳机，避免回声。加入不会自动打开麦克风。</p
    >
    <p v-if="readError" class="form-error" role="alert">{{ readError }}</p>
    <p v-if="!online" class="form-error" role="status"
      >网络已离线，停止同步与续约；恢复后先核验当前房间状态，不自动重试操作。</p
    >
    <p v-else-if="snapshot && !fresh" class="form-error" role="status"
      >以下为旧快照，当前权限未确认，已暂停新操作；请刷新房间状态。</p
    >
    <p v-if="lastVerifiedAt" class="hint"
      >最近核验：{{ new Date(lastVerifiedAt).toLocaleTimeString() }}（本机时间，非租约期限）</p
    >
    <p v-if="writeError" class="form-error" role="alert">{{ writeError }}</p>
    <p v-if="success" role="status">{{ success }}</p>
    <div v-if="pending" class="workspace-error"
      ><p>原请求结果未确认。重试保持原 UUID、会话及版本，放弃只停止本地等待，不取消后台操作。</p
      ><div class="page-actions"
        ><button type="button" class="secondary" :disabled="busy || !online" @click="checkReceipt"
          >只查询原提交结果</button
        ><button type="button" class="secondary" :disabled="busy || !online" @click="transmit">使用原请求重试</button
        ><button type="button" class="secondary" :disabled="busy || !online" @click="discard"
          >放弃旧请求并重取</button
        ></div
      ></div
    >
    <button type="button" class="secondary" :disabled="busy || actionPreparing || !online" @click="load()">{{
      loading ? '读取中…' : '刷新房间状态'
    }}</button>
    <p v-if="capabilities && !capabilities.enabled" role="status">互动核心尚未开放，不能执行成员或麦位操作。</p>
    <template v-if="capabilities?.enabled">
      <section class="voice-guide-step" aria-label="加入房间">
        <h3>1 · 加入房间</h3>
        <p v-if="!snapshot?.mySessionId">先加入，才能收听或申请发言。</p>
        <p v-else role="status">已加入房间。接下来选择收听或发言。</p>
        <button v-if="!snapshot?.mySessionId" type="button" class="primary" :disabled="disabled" @click="joinRoom"
          >加入房间</button
        >
        <button v-else type="button" class="secondary" :disabled="disabled" @click="execute('LEAVE')">离开房间</button>
      </section>
      <section class="voice-guide-step" aria-label="选择语音方式">
        <h3>2 · 你想做什么？</h3>
        <p class="hint">这里切换操作指引，不会开关麦克风。停止发声请点击第 3 步的“关闭麦克风”。</p>
        <div class="page-actions">
          <button
            type="button"
            :class="intent === 'listen' ? 'primary' : 'secondary'"
            :aria-pressed="intent === 'listen'"
            @click="intent = 'listen'"
            >只收听</button
          >
          <button
            type="button"
            :class="intent === 'speak' ? 'primary' : 'secondary'"
            :aria-pressed="intent === 'speak'"
            @click="intent = 'speak'"
            >我要发言</button
          >
        </div>
        <p v-if="intent === 'listen'">不用申请麦位，直接点击下方“连接语音”。若听不到，检查手机音量和“恢复声音播放”。</p>
        <template v-else>
          <p v-if="!snapshot?.mySessionId">请先完成第 1 步，加入房间。</p>
          <template v-else-if="mySeat">
            <p v-if="mySeat.muted">你在麦位 {{ mySeat.seatNo }}，但房间发言权限仍关闭。先解除闭麦。</p>
            <p v-else role="status">发言权限已就绪。下一步连接语音，再点击“开启麦克风”。</p>
            <button
              v-if="mySeat.muted"
              type="button"
              class="primary"
              :disabled="disabled"
              @click="execute('MUTE', { seatNo: mySeat.seatNo, value: false })"
              >允许我发言（解除闭麦）</button
            >
            <button
              type="button"
              class="secondary"
              :disabled="disabled"
              @click="execute('DOWN', { seatNo: mySeat.seatNo })"
              >结束发言并下麦</button
            >
          </template>
          <p v-else-if="myApplication" role="status"
            >已申请麦位 {{ myApplication.seatNo }}，等待房主同意；等待期间可以连接语音收听。</p
          >
          <p v-else-if="!freeSeat">暂无空麦位，可以先连接语音收听。</p>
          <template v-else>
            <p>{{ manager ? '你是房主或房管，可以直接上麦。' : '申请后需要房主或房管同意，不能自动开启麦克风。' }}</p>
            <button
              type="button"
              class="primary"
              :disabled="disabled"
              @click="
                execute(manager ? 'PULL' : 'APPLY', {
                  seatNo: freeSeat.seatNo,
                  ...(manager ? { targetUserId: userId } : {}),
                })
              "
              >{{ manager ? '自己上麦' : '申请发言' }}</button
            >
          </template>
        </template>
      </section>
      <ControlledVoiceMediaPanel
        :room-id="roomId"
        :room-title="roomTitle"
        :user-id="userId"
        :session-revision="sessionRevision"
        :snapshot="snapshot"
        :fresh="fresh && online && !pending"
        :reading="loading"
      />
      <details @toggle="showMediaPlan = ($event.target as HTMLDetailsElement).open">
        <summary>高级：媒体授权和退场进度</summary>
        <VoiceMediaPlanPanel
          v-if="showMediaPlan"
          :room-id="roomId"
          :user-id="userId"
          :session-revision="sessionRevision"
          :allowed="fresh && !!snapshot"
        />
      </details>
      <template v-if="snapshot">
        <section
          v-if="manager && snapshot.requests.some((request) => request.type === 'APPLY')"
          aria-label="待处理发言申请"
        >
          <h3>有人申请发言</h3>
          <div
            v-for="request in snapshot.requests.filter((item) => item.type === 'APPLY')"
            :key="request.id"
            class="page-actions"
          >
            <span
              >{{ snapshot.members.find((member) => member.userId === request.userId)?.displayName || '成员' }} · 麦位
              {{ request.seatNo }}</span
            >
            <button
              type="button"
              class="primary"
              :disabled="disabled"
              @click="execute('ACCEPT', { seatRequestId: request.id })"
              >同意发言</button
            >
            <button
              type="button"
              class="secondary"
              :disabled="disabled"
              @click="execute('REJECT', { seatRequestId: request.id })"
              >拒绝</button
            >
          </div>
        </section>
        <details class="voice-management">
          <summary>高级：麦位、预约和房间管理</summary>
          <p class="hint"
            >房间版本 {{ snapshot.version }} · 有效成员 {{ snapshot.members.length }} ·
            90秒租约，面板关闭后停止续约。</p
          >
          <label v-if="manager" class="workspace-field"
            >目标成员<select v-model="target" :disabled="disabled"
              ><option value="">请选择成员</option
              ><option v-for="member in snapshot.members" :key="member.userId" :value="member.userId"
                >{{ member.displayName }} · {{ member.role }}</option
              ></select
            ></label
          >
          <div class="voice-seat-grid"
            ><article v-for="seat in snapshot.seats" :key="seat.seatNo" class="voice-seat-card"
              ><h3>麦位 {{ seat.seatNo }}</h3
              ><p>{{ labels[seat.state] }}</p
              ><small v-if="seat.userId"
                >{{ snapshot.members.find((member) => member.userId === seat.userId)?.displayName || '成员' }} ·
                {{ seat.muted ? '希望闭麦' : '希望开麦' }}</small
              ><div class="page-actions">
                <button
                  v-if="seat.state === 'EMPTY' && snapshot.mySessionId"
                  type="button"
                  class="secondary"
                  :disabled="disabled"
                  @click="execute('APPLY', { seatNo: seat.seatNo })"
                  >申请麦位</button
                >
                <template v-if="manager && seat.state === 'EMPTY'"
                  ><button
                    type="button"
                    class="secondary"
                    :disabled="disabled || !target"
                    @click="execute('INVITE', { seatNo: seat.seatNo, targetUserId: target })"
                    >邀请</button
                  ><button
                    type="button"
                    class="secondary"
                    :disabled="disabled || !target"
                    @click="execute('PULL', { seatNo: seat.seatNo, targetUserId: target })"
                    >{{ target === userId ? '自己上麦' : '抱麦' }}</button
                  ></template
                >
                <button
                  v-if="manager && ['EMPTY', 'LOCKED'].includes(seat.state)"
                  type="button"
                  class="secondary"
                  :disabled="disabled"
                  @click="execute('LOCK', { seatNo: seat.seatNo, value: seat.state === 'EMPTY' })"
                  >{{ seat.state === 'EMPTY' ? '锁麦' : '解锁' }}</button
                >
                <button
                  v-if="seat.state === 'ON_MIC' && seat.userId === userId"
                  type="button"
                  class="secondary"
                  :disabled="disabled"
                  @click="execute('DOWN', { seatNo: seat.seatNo })"
                  >下麦</button
                >
                <template v-if="seat.state === 'ON_MIC' && (manager || seat.userId === userId)"
                  ><button
                    type="button"
                    class="secondary"
                    :disabled="disabled"
                    @click="execute('MUTE', { seatNo: seat.seatNo, value: !seat.muted })"
                    >{{ seat.muted ? '解除闭麦' : '设置闭麦状态' }}</button
                  ><button
                    v-if="manager"
                    type="button"
                    class="secondary"
                    :disabled="disabled"
                    @click="execute('KICK', { seatNo: seat.seatNo })"
                    >踢下麦</button
                  ></template
                >
              </div></article
            ></div
          >
          <section aria-label="本人或管理方可见预约"
            ><h3>预约请求</h3><p v-if="!snapshot.requests.length" class="hint">没有当前可见的有效预约。</p
            ><article v-for="request in snapshot.requests" :key="request.id"
              ><p
                >{{ request.type === 'APPLY' ? '申请' : '邀请' }} · 麦位 {{ request.seatNo }} ·
                {{ snapshot.members.find((member) => member.userId === request.userId)?.displayName || '成员' }}</p
              ><div class="page-actions"
                ><button
                  v-if="canReview(request)"
                  type="button"
                  class="secondary"
                  :disabled="disabled"
                  @click="execute('ACCEPT', { seatRequestId: request.id })"
                  >同意</button
                ><button
                  v-if="manager"
                  type="button"
                  class="secondary"
                  :disabled="disabled"
                  @click="execute('REJECT', { seatRequestId: request.id })"
                  >拒绝</button
                ><button
                  v-if="request.userId === userId"
                  type="button"
                  class="secondary"
                  :disabled="disabled"
                  @click="execute('CANCEL', { seatRequestId: request.id })"
                  >取消预约</button
                ></div
              ></article
            ></section
          >
          <section v-if="owner" aria-label="房间角色管理"
            ><h3>长期房管与房主</h3
            ><div class="page-actions"
              ><button
                type="button"
                class="secondary"
                :disabled="disabled || !target || target === userId"
                @click="execute('ADMIN', { targetUserId: target, value: true })"
                >设为房管</button
              ><button
                type="button"
                class="secondary"
                :disabled="disabled || !target || target === userId"
                @click="execute('TRANSFER', { targetUserId: target })"
                >转让房主</button
              ></div
            ><p v-if="!snapshot.administrators.length" class="hint">尚未设置长期房管。</p
            ><div v-for="admin in snapshot.administrators" :key="admin.userId" class="page-actions"
              ><span>{{ admin.displayName }}（可能离线）</span
              ><button
                type="button"
                class="secondary"
                :disabled="disabled"
                @click="execute('ADMIN', { targetUserId: admin.userId, value: false })"
                >撤销房管</button
              ></div
            ></section
          >
          <section v-if="manager" aria-label="房主管理操作审计">
            <h3>操作审计</h3
            ><button type="button" class="secondary" :disabled="actionLoading || busy" @click="loadActions()"
              >读取房管审计</button
            >
            <p v-if="actionError" class="form-error" role="alert">{{ actionError }}</p>
            <p v-if="actions && !actions.items.length && !actionError" class="hint">本轮未查询到操作记录。</p>
            <ul v-if="actions"
              ><li v-for="action in actions.items" :key="action.version"
                >版本 {{ action.version }} · {{ action.type }} · 操作者 {{ action.actorId || '系统' }} · 目标
                {{ action.targetUserId || '无' }} · 麦位 {{ action.seatNo || '无' }} · 目标值
                {{ action.value === null ? '无' : action.value ? '开启' : '关闭' }} ·
                {{ action.createdAt }}（数据库时间）</li
              ></ul
            >
            <button
              v-if="actions?.nextBefore"
              type="button"
              class="secondary"
              :disabled="actionLoading || busy"
              @click="loadActions(true)"
              >更早审计</button
            >
          </section>
        </details>
      </template>
    </template>
  </section>
</template>
<style scoped>
.voice-interaction {
  margin: 20px 0;
  overflow-wrap: anywhere;
}
.voice-interaction .page-actions {
  margin: 12px 0;
  flex-wrap: wrap;
}
.voice-guide-step {
  margin: 16px 0;
  padding: 16px;
  border: 1px solid var(--border-color, #303541);
  border-radius: 12px;
}
.voice-guide-step h3 {
  margin: 0 0 12px;
}
.voice-management summary {
  cursor: pointer;
  padding: 12px 0;
}
.voice-seat-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin: 20px 0;
}
@media (min-width: 1200px) {
  .voice-seat-grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}
.voice-seat-card {
  padding: 16px;
  border: 1px solid var(--border-color, #303541);
  border-radius: 12px;
}
.voice-seat-card h3 {
  margin: 0 0 10px;
}
@media (max-width: 640px) {
  .voice-seat-grid {
    grid-template-columns: 1fr;
  }
}
</style>
