<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useVoiceInteractionWorkspace } from '../composables/voice-interaction-workspace';
import VoiceMediaPlanPanel from './VoiceMediaPlanPanel.vue';
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
/** 未确认原请求或当前权限时不新建命令。 */ const disabled = computed(
  () => !fresh.value || !online.value || loading.value || busy.value || !!pending.value,
);
/** 固定状态名称，不声称已经真实发声。 */ const labels = {
  EMPTY: '空麦',
  LOCKED: '已锁',
  RESERVED: '预约中',
  ON_MIC: '在麦状态 · 媒体待接入',
};
watch(busy, (value) => emit('busy', value), { flush: 'sync' });
watch(
  () => snapshot.value?.members,
  (members) => {
    if (target.value && !members?.some((member) => member.userId === target.value)) target.value = '';
  },
  { flush: 'sync' },
);
function execute(
  type: VoiceCommandType,
  args: Partial<Pick<VoiceCommand, 'seatNo' | 'targetUserId' | 'seatRequestId' | 'value'>> = {},
) {
  if (
    ['KICK', 'TRANSFER', 'ADMIN'].includes(type) &&
    !confirm('确认修改房间权限或席位？这只提交后台状态，媒体仍未开放。')
  )
    return;
  void command(type, args);
}
function canReview(request: SeatRequest) {
  return request.type === 'APPLY' ? manager.value : request.userId === props.userId;
}
</script>
<template>
  <section class="workspace-card voice-interaction" aria-label="语音房互动核心" :aria-busy="loading || busy">
    <div class="workspace-card-heading"
      ><h2>房间互动面板</h2
      ><button type="button" class="secondary" :disabled="busy" @click="emit('close')">关闭面板</button></div
    >
    <p class="hint">{{ roomTitle || '当前房间' }} · ID {{ roomId }}</p>
    <p class="hint"
      >这是持久化成员与麦位状态。媒体权限尚未开放，不会访问麦克风或签发旧 LiveKit
      凭据；成员数是有效租约数，不是媒体在线数。</p
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
    <button type="button" class="secondary" :disabled="busy || !online" @click="load()">{{
      loading ? '读取中…' : '刷新房间状态'
    }}</button>
    <p v-if="capabilities && !capabilities.enabled" role="status">互动核心尚未开放，不能执行成员或麦位操作。</p>
    <template v-if="capabilities?.enabled">
      <VoiceMediaPlanPanel
        :room-id="roomId"
        :user-id="userId"
        :session-revision="sessionRevision"
        :allowed="fresh && !!snapshot"
      />
      <div class="page-actions"
        ><button v-if="!snapshot?.mySessionId" type="button" class="primary" :disabled="disabled" @click="join"
          >加入互动会话</button
        ><button v-else type="button" class="secondary" :disabled="disabled" @click="execute('LEAVE')"
          >退出互动会话</button
        ></div
      >
      <template v-if="snapshot">
        <p class="hint"
          >房间版本 {{ snapshot.version }} · 有效成员 {{ snapshot.members.length }} · 90秒租约，面板关闭后停止续约。</p
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
                >申请</button
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
                  >抱麦</button
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
                  >{{ seat.muted ? '申请开麦状态' : '设置闭麦状态' }}</button
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
