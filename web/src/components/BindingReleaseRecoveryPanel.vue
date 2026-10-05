<script setup lang="ts">
import { nextTick, ref, toRef } from 'vue';
import type { BindingReleaseDomain, BindingReleaseTask } from '../services/binding-releases';
import type { OperationsAccess } from '../services/operations';
import { bindingRecoveryApi, bindingRecoverySecureOrigin } from '../services/binding-recovery';
import { useBindingRecoveryWorkspace } from '../composables/binding-recovery-workspace';
import { useBindingRecoveryStore } from '../stores/binding-recovery';
import { useAuthStore } from '../stores/auth';
const props = defineProps<{
  domain: BindingReleaseDomain;
  task: BindingReleaseTask | null;
  access: OperationsAccess | null;
}>();
const emit = defineEmits<{ accessDenied: [] }>();
const state = useBindingRecoveryWorkspace(
  bindingRecoveryApi,
  toRef(props, 'domain'),
  toRef(props, 'task'),
  toRef(props, 'access'),
  useAuthStore(),
  useBindingRecoveryStore(),
  undefined,
  () => emit('accessDenied'),
  bindingRecoverySecureOrigin,
);
const {
  reason,
  restoreRequestId,
  restoreCommandId,
  restoreGeneration,
  restoreReason,
  password,
  error,
  message,
  busy,
  audits,
  auditCursor,
  auditLoading,
  auditError,
  auditsLoaded,
  canRead,
  canReplay,
  pending,
  confirmationReady,
  secureTransport,
} = state;
const passwordInput = ref<HTMLInputElement | null>(null);
async function prepare() {
  state.prepare();
  await nextTick();
  passwordInput.value?.focus();
}
const stamp = (value: string) => value.replace('T', ' ');
</script>
<template>
  <section class="workspace-card recovery-panel">
    <h2>人工恢复与追加审计</h2>
    <p v-if="!secureTransport" role="status" class="form-error"
      >当前不是HTTPS或本机环回，不允许发送运营密码或确认票据；生产恢复须先完成TLS。</p
    >
    <p class="workspace-note"
      >仅重新排队已提交业务的保护释放任务，不删除资产。每代最多十次，人工最多十代；累计次数永不清零。服务开关默认关闭。</p
    >
    <p v-if="!canReplay" class="workspace-note"
      >当前仅可查审计。人工受理另需 ASSET_BINDING_RECOVERY 专用权限，通知重放票据无效。</p
    >
    <template v-if="canReplay && !pending">
      <label
        >工单 / 已处理的根因说明<textarea
          v-model="reason"
          rows="3"
          maxlength="500"
          :disabled="busy"
          placeholder="说明故障根因、处理依据和恢复原因（10～500字符）"
        />
      </label>
      <button
        class="secondary"
        :disabled="busy || task?.status !== 'DEAD' || task.generationAttempts !== 10 || task.replayGeneration >= 10"
        @click="prepare"
        >准备原任务恢复</button
      >
    </template>
    <details v-if="canRead && !pending" class="recovery-command">
      <summary>刷新后重录已保存的原命令</summary>
      <p class="workspace-note"
        >仅录入原操作者保存的完整字段，业务域使用当前所选域。不猜测原因、代次，不生成新ID。录入后先查询；404仍保留未知状态。没有原记录时先按原请求读取审计。</p
      >
      <form @submit.prevent="state.restore">
        <label>原绑定请求UUID<input v-model="restoreRequestId" maxlength="36" :disabled="busy" required /></label>
        <label>原人工命令UUID<input v-model="restoreCommandId" maxlength="36" :disabled="busy" required /></label>
        <label
          >保存的原确认代次<input
            v-model="restoreGeneration"
            inputmode="numeric"
            maxlength="2"
            :disabled="busy"
            required
        /></label>
        <label
          >保存的完整恢复原因<textarea v-model="restoreReason" rows="3" maxlength="500" :disabled="busy" required />
        </label>
        <button class="secondary" type="submit" :disabled="busy">重录原命令，不发送恢复</button>
      </form>
    </details>
    <section v-if="pending" class="recovery-command" aria-label="原人工命令">
      <h3>{{
        pending.phase === 'accepted'
          ? '原命令已受理'
          : pending.phase === 'uncertain'
            ? '原命令结果尚未确认'
            : '原命令待本人确认'
      }}</h3>
      <dl
        ><dt>固定业务域</dt><dd>{{ pending.domain }}</dd
        ><dt>原绑定请求</dt><dd>{{ pending.command.requestId }}</dd> <dt>人工命令ID</dt
        ><dd>{{ pending.command.commandId }}</dd
        ><dt>确认代次</dt
        ><dd>{{ pending.command.expectedGeneration }} → {{ pending.command.expectedGeneration + 1 }}</dd>
        <dt>恢复原因</dt><dd>{{ pending.command.reason }}</dd></dl
      >
      <p v-if="pending.domain !== domain || pending.command.requestId !== task?.requestId" class="workspace-note"
        >当前所选任务不同；以下确认 / 查询始终针对上面这条原命令，不替换目标。</p
      >
      <p v-if="pending.phase === 'uncertain'" role="status"
        >超时或取消不说明服务器已回滚。不得生成第二个ID重试；先查原命令，404也不能丢弃。</p
      >
      <p v-if="pending.receipt" role="status"
        >第 {{ pending.receipt.acceptedGeneration }} 代于
        {{ stamp(pending.receipt.acceptedAt) }} 受理。不代表保护释放完成，请刷新原请求事实。</p
      >
      <template v-if="canReplay && pending.phase !== 'accepted'">
        <label
          >本人密码（不保存）<input
            ref="passwordInput"
            v-model="password"
            type="password"
            maxlength="72"
            autocomplete="current-password"
            :disabled="busy"
        /></label>
        <button class="secondary" :disabled="busy || !password" @click="state.confirm">确认这条原命令</button>
        <button class="primary" :disabled="busy || !confirmationReady" @click="state.submit">{{
          pending.phase === 'uncertain' ? '幂等重试原命令' : '受理一次恢复'
        }}</button>
      </template>
      <button class="secondary" :disabled="busy || !canRead" @click="state.query">查询原命令受理事实</button>
      <button class="secondary" :disabled="busy || pending.phase === 'uncertain'" @click="state.finish">{{
        pending.phase === 'accepted' ? '已知受理，关闭原命令' : '取消尚未受理的命令'
      }}</button>
      <p class="workspace-note"
        >原命令仅在当前会话内存跨路由保留，密码与确认票据切页即清除。刷新浏览器前记录业务域、原请求、命令ID、原代次和完整原因；刷新后可重录并查询。不要记录密码或确认票据。</p
      >
    </section>
    <p v-if="busy" role="status">正在向服务端确认事实…</p><p v-if="error" role="alert" class="form-error">{{ error }}</p
    ><p v-if="message" role="status">{{ message }}</p>
    <div class="workspace-card-heading"
      ><h3>所选原请求的追加审计</h3
      ><button class="secondary" :disabled="auditLoading || !canRead || !task" @click="state.loadAudits(true)"
        >读取 / 刷新审计</button
      ></div
    >
    <p v-if="auditError" class="form-error" role="alert">{{ auditError }}</p
    ><p v-if="auditLoading" role="status">正在读取审计…</p>
    <p v-if="auditsLoaded && !audits.length && !auditLoading && !auditError" class="workspace-empty"
      >本次查询没有人工恢复审计。</p
    >
    <div v-if="audits.length" class="recovery-audits" tabindex="0" aria-label="人工恢复审计，可滚动">
      <article v-for="item in audits" :key="item.commandId"
        ><h4>第{{ item.acceptedGeneration }}代 · {{ stamp(item.createdAt) }}</h4>
        <p
          >操作者 {{ item.operatorId }} · 原累计 {{ item.previousAttempts }} 次 / 原本代
          {{ item.previousGenerationAttempts }} 次</p
        >
        <p>人工命令：{{ item.commandId }}</p
        ><p>{{ item.reason }}</p
        ><p>原固定失败类别：{{ item.previousFailure ?? '无' }}</p></article
      >
    </div>
    <button v-if="auditCursor !== null" class="secondary" :disabled="auditLoading" @click="state.loadAudits(false)"
      >下一页审计</button
    >
  </section>
</template>
<style scoped>
.recovery-panel {
  margin-top: 20px;
  line-height: 1.7;
}
label {
  display: grid;
  gap: 8px;
  margin: 14px 0;
}
input,
textarea {
  width: 100%;
  box-sizing: border-box;
}
.recovery-command {
  border: 1px solid #344759;
  padding: 16px;
  margin: 18px 0;
  border-radius: 12px;
}
dl {
  display: grid;
  grid-template-columns: 120px minmax(0, 1fr);
  gap: 8px;
}
dd {
  margin: 0;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
.recovery-command button {
  margin: 6px 8px 6px 0;
}
.recovery-audits {
  max-height: 360px;
  overflow-y: auto;
}
.recovery-audits article {
  border-bottom: 1px solid #344759;
  padding: 12px 0;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
@media (max-width: 600px) {
  dl {
    grid-template-columns: 1fr;
  }
  .recovery-command {
    padding: 12px;
  }
}
</style>
