<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { AlertTriangle, RefreshCw, ShieldCheck } from 'lucide-vue-next';
import { useAuthStore } from '../stores/auth';
import { operationsApi } from '../services/operations';
import { bindingReleaseApi } from '../services/binding-releases';
import { useBindingReleaseWorkspace } from '../composables/binding-release-workspace';
import BindingReleaseRecoveryPanel from '../components/BindingReleaseRecoveryPanel.vue';

const state = useBindingReleaseWorkspace(bindingReleaseApi, operationsApi.access, useAuthStore());
/** 原任务可能已离开 DEAD，仍可凭保存的 UUID 查询当前事实。 */
const inspectId = ref('');
function inspectRequest() {
  void state.select(inspectId.value.trim().toLowerCase());
}
const {
  access,
  domain,
  rows,
  cursor,
  selected,
  snapshot,
  accessLoading,
  loading,
  detailLoading,
  snapshotLoading,
  accessError,
  error,
  detailError,
  snapshotError,
  loaded,
  canRead,
  capped,
} = state;
const statuses = { PENDING: '等待释放', LEASED: '释放处理中', SENT: '释放已确认', DEAD: '自动重试已耗尽' };
const purposes = { AVATAR: '头像', BANNER: '创作者横幅', POST_COVER: '动态封面' };
const failures = {
  'release-unconfirmed': '资产服务尚未确认释放',
  'lease-exhausted': '过期租约累计耗尽',
  unknown: '未识别类别，需排查',
};
const stamp = (value: string) => value.replace('T', ' ');
const count = (value: number, limit: number) => (value >= limit ? `至少 ${limit}` : String(value));
onMounted(state.loadAccess);
</script>

<template>
  <section class="workspace-section binding-operations">
    <div class="workspace-section-heading">
      <div
        ><p class="eyebrow">ASSET BINDING OPERATIONS</p><h1>绑定释放排障</h1>
        <p>查看已提交业务的资产保护释放事实，不清除未知意图，不删除资产。</p></div
      >
      <button class="secondary" :disabled="accessLoading" @click="state.loadAccess"
        ><RefreshCw :size="16" />重新读取权限</button
      >
    </div>
    <p class="binding-note"><ShieldCheck :size="18" />独立读 / 恢复权限 · 服务端再次校验 · 无默认授权</p>
    <p v-if="accessLoading" role="status" class="workspace-skeleton">正在读取运营权限和任务事实…</p>
    <div v-if="accessError" role="alert" class="workspace-card binding-gate"
      ><AlertTriangle :size="24" /><h2>运营能力暂不可读取</h2><p>{{ accessError }}</p></div
    >
    <div v-else-if="!accessLoading && !canRead" class="workspace-card binding-gate">
      <ShieldCheck :size="24" /><h2>{{ access?.enabled ? '当前账号没有绑定释放读权限' : '运营功能尚未启用' }}</h2>
      <p>通知运营权限不等于资产排障权限。需明确授予 ASSET_BINDING_AUDITOR 角色；普通注册不会获得授权。</p>
    </div>
    <template v-if="canRead">
      <div class="binding-domains" role="group" aria-label="绑定释放业务域">
        <button
          v-for="item in [
            { code: 'identity' as const, title: '身份绑定' },
            { code: 'community' as const, title: '动态绑定' },
          ]"
          :key="item.code"
          class="secondary"
          :aria-pressed="domain === item.code"
          :class="{ active: domain === item.code }"
          @click="state.chooseDomain(item.code)"
          >{{ item.title }}</button
        >
      </div>
      <section class="workspace-card">
        <div class="workspace-card-heading"
          ><h2>当前域状态采样</h2
          ><button class="secondary" :disabled="snapshotLoading" @click="state.sample"
            ><RefreshCw :size="15" />刷新采样</button
          ></div
        >
        <p class="workspace-note"
          >每状态最多扫描 1001 条；达到上限显示下界。采样与队列不是同一事务，不代表全站精确总量。</p
        >
        <p v-if="snapshotLoading" role="status">正在读取数据库时点…</p>
        <p v-else-if="snapshotError" class="form-error" role="alert">{{ snapshotError }}（当前采样未知，不是零积压）</p>
        <template v-else-if="snapshot">
          <dl class="binding-summary"
            ><div
              ><dt>等待释放</dt><dd>{{ count(snapshot.pending, snapshot.sampleLimit) }}</dd></div
            >
            <div
              ><dt>处理中</dt><dd>{{ count(snapshot.leased, snapshot.sampleLimit) }}</dd></div
            >
            <div
              ><dt>自动耗尽</dt><dd>{{ count(snapshot.dead, snapshot.sampleLimit) }}</dd></div
            >
            <div
              ><dt>最旧等待 / 耗尽年龄</dt
              ><dd>{{ snapshot.oldestPendingAgeSeconds }} / {{ snapshot.oldestDeadAgeSeconds }} 秒</dd></div
            ></dl
          >
          <p class="workspace-note"
            >业务库采样时间原值（无时区，未换算本地时间）：{{ stamp(snapshot.observedAt) }}；不会在后台自动刷新。</p
          >
        </template>
      </section>
      <form class="binding-lookup workspace-card" @submit.prevent="inspectRequest">
        <label
          >查询原绑定请求（可查已离开DEAD的任务）<input
            v-model="inspectId"
            maxlength="36"
            placeholder="保存的原绑定 UUID"
        /></label>
        <button class="secondary" type="submit" :disabled="detailLoading || !inspectId.trim()">读取原请求事实</button>
      </form>
      <div class="binding-columns">
        <section class="workspace-card">
          <div class="workspace-card-heading"
            ><h2>自动重试已耗尽</h2
            ><button class="secondary" :disabled="loading" @click="state.load(true)"
              ><RefreshCw :size="15" />刷新队列</button
            ></div
          >
          <p class="workspace-note">创建时间与请求 UUID 降序。并发状态变化可能使旧页过时，请刷新后核对详情。</p>
          <p v-if="error" role="alert" class="form-error">{{ error }}</p>
          <p v-if="loaded && !rows.length && !loading && !error" class="workspace-empty">本次查询没有 DEAD 任务。</p>
          <div class="binding-list" tabindex="0" aria-label="绑定释放耗尽任务，可滚动">
            <button
              v-for="row in rows"
              :key="row.requestId"
              class="binding-task"
              :class="{ active: selected?.requestId === row.requestId }"
              @click="state.select(row.requestId)"
            >
              <strong>{{ purposes[row.purpose] }} · 累计 {{ row.attempts }} 次</strong><span>{{ row.requestId }}</span
              ><small>{{ stamp(row.createdAt) }}</small>
            </button>
          </div>
          <p v-if="loading" role="status">正在读取任务页…</p>
          <p v-if="capped" role="status">已读取两百条，仍有更多。请记录目标请求后刷新队列，避免无界缓存。</p>
          <button v-if="cursor && !capped" class="secondary" :disabled="loading" @click="state.load(false)"
            >读取下一页</button
          >
        </section>
        <section class="workspace-card">
          <div class="workspace-card-heading"
            ><h2>原请求当前事实</h2
            ><button
              v-if="selected"
              class="secondary"
              :disabled="detailLoading"
              @click="state.select(selected.requestId)"
              >刷新详情</button
            ></div
          >
          <p v-if="detailLoading" role="status">正在核对原请求…</p
          ><p v-else-if="detailError" role="alert" class="form-error">{{ detailError }}</p>
          <dl v-else-if="selected" class="binding-facts">
            <dt>原请求</dt><dd>{{ selected.requestId }}</dd
            ><dt>受管资产</dt><dd>{{ selected.assetId }}</dd> <dt>原用途</dt><dd>{{ purposes[selected.purpose] }}</dd
            ><dt>当前状态</dt><dd>{{ statuses[selected.status] }}</dd> <dt>永久累计尝试</dt
            ><dd>{{ selected.attempts }} / 110</dd><dt>人工代次 / 本代尝试</dt
            ><dd>{{ selected.replayGeneration }} / 10 · {{ selected.generationAttempts }} / 10</dd><dt>失败类别</dt
            ><dd>{{ selected.lastFailure ? failures[selected.lastFailure] : '无' }}</dd> <dt>创建时间</dt
            ><dd>{{ stamp(selected.createdAt) }}</dd
            ><dt>最近变更</dt><dd>{{ stamp(selected.updatedAt) }}</dd> <dt>下一自动尝试</dt
            ><dd>{{ stamp(selected.nextAttemptAt) }}（DEAD 不会因此自动重启）</dd>
          </dl>
          <p v-else class="workspace-empty">选择一条任务核对当前状态。SENT 仅表示保护释放已确认，不是资产删除。</p>
          <p class="binding-warning"
            >只读审核员不能受理恢复；下方人工操作需要独立权限、命令确认和审计。不能用通知重放替代，也不能直接清除资产意图。</p
          >
        </section>
      </div>
      <BindingReleaseRecoveryPanel
        :domain="domain"
        :task="selected"
        :access="access"
        @access-denied="state.loadAccess"
      />
    </template>
  </section>
</template>

<style scoped>
.binding-lookup {
  display: flex;
  gap: 12px;
  align-items: end;
  margin-top: 20px;
  flex-wrap: wrap;
}
.binding-lookup label {
  display: grid;
  gap: 8px;
  flex: 1;
  min-width: 220px;
}
.binding-note,
.binding-domains {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 20px 0;
}
.binding-note {
  color: #a7d7d0;
}
.binding-gate {
  padding: 36px;
  text-align: center;
  line-height: 1.7;
}
.binding-domains .active,
.binding-task.active {
  border-color: #63b5a8;
  background: #183335;
}
.binding-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 18px;
}
.binding-summary dt,
.binding-facts dt {
  color: #91a5bd;
  font-size: 12px;
}
.binding-summary dd {
  margin: 10px 0 0;
  font-size: 18px;
  overflow-wrap: anywhere;
}
.binding-columns {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.1fr);
  gap: 18px;
  margin-top: 18px;
}
.binding-columns > section {
  min-width: 0;
}
.binding-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 60vh;
  overflow: auto;
  margin: 18px 0;
  overscroll-behavior: contain;
}
.binding-task {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 9px;
  padding: 14px;
  border: 1px solid #33465d;
  background: #142033;
  border-radius: 10px;
  text-align: left;
  color: #c4d2e1;
}
.binding-task span,
.binding-facts dd {
  overflow-wrap: anywhere;
  font-family: ui-monospace, monospace;
  font-size: 12px;
}
.binding-task small {
  color: #91a5bd;
}
.binding-facts {
  display: grid;
  grid-template-columns: 120px minmax(0, 1fr);
  gap: 14px;
  line-height: 1.7;
}
.binding-facts dd {
  margin: 0;
}
.binding-warning {
  border-left: 3px solid #d9ac61;
  padding: 12px;
  background: #332d22;
  color: #e2c68f;
  line-height: 1.7;
}
@media (max-width: 1000px) {
  .binding-columns {
    grid-template-columns: 1fr;
  }
  .binding-summary {
    grid-template-columns: 1fr 1fr;
  }
}
@media (max-width: 600px) {
  .binding-facts {
    grid-template-columns: 1fr;
    gap: 6px;
  }
  .binding-facts dd {
    margin-bottom: 10px;
  }
  .binding-list {
    max-height: 45vh;
  }
}
</style>
