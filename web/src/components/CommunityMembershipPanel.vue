<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue';
import type { Community } from '../api';
import {
  communityMembershipApi as api,
  type CommunityMember,
  type MembershipStatus,
} from '../services/community-membership';

const props = defineProps<{
  /** 当前认证用户 ID，只用于界面，不替代服务端授权。 */
  userId: string;
  /** 从公开卡片进入的初始社区 ID；未提供时显示本人列表。 */
  initialId?: string;
}>();
const emit = defineEmits<{
  /** 关闭后销毁请求和临时视图。 */
  close: [];
  /** 已提交成员变更，父页面重新读取真实人数。 */
  changed: [];
}>();
const joined = ref<Community[]>([]),
  selected = ref<MembershipStatus | null>(null);
const members = ref<CommunityMember[]>([]),
  selectedId = ref('');
const nextJoined = ref<string | null>(null),
  nextMember = ref<string | null>(null);
const loading = ref(false),
  busy = ref(false),
  error = ref(''),
  success = ref('');
let disposed = false,
  generation = 0;
let reads: AbortController | undefined;
const writes = new AbortController();
const failure = (cause: unknown) => (cause instanceof Error ? cause.message : '社区操作失败');

function startRead() {
  reads?.abort();
  reads = new AbortController();
  loading.value = true;
  error.value = '';
  return { current: ++generation, signal: reads.signal };
}
function currentRead(current: number) {
  return !disposed && current === generation;
}

/** 列表只逐页读取，返回列表时清除上一个社区的私密元信息。 */
async function loadJoined(more = false) {
  if (disposed || busy.value || loading.value) return;
  selected.value = null;
  selectedId.value = '';
  members.value = [];
  nextMember.value = null;
  const { current, signal } = startRead();
  if (!more) {
    joined.value = [];
    nextJoined.value = null;
  }
  try {
    const page = await api.joined(more ? (nextJoined.value ?? undefined) : undefined, signal);
    if (!currentRead(current)) return;
    const unique = new Map((more ? joined.value : []).map((item) => [item.id, item]));
    page.items.forEach((item) => unique.set(item.id, item));
    joined.value = [...unique.values()];
    nextJoined.value = page.nextBefore;
  } catch (cause) {
    if (currentRead(current) && !signal.aborted) error.value = failure(cause);
  } finally {
    if (currentRead(current)) loading.value = false;
  }
}

/** 切换社区取消旧请求，成员授权失效时不保留已显示的私密详情。 */
async function select(id: string, afterWrite = false) {
  if (disposed || (busy.value && !afterWrite)) return;
  selectedId.value = id;
  selected.value = null;
  members.value = [];
  nextMember.value = null;
  const { current, signal } = startRead();
  try {
    const status = await api.status(id, signal);
    const page = status.role ? await api.members(id, undefined, signal) : undefined;
    if (!currentRead(current)) return;
    selected.value = status;
    members.value = page?.items ?? [];
    nextMember.value = page?.nextBefore ?? null;
  } catch (cause) {
    if (currentRead(current) && !signal.aborted) {
      error.value = afterWrite ? `操作已提交，但重新读取失败，请刷新核实：${failure(cause)}` : failure(cause);
    }
  } finally {
    if (currentRead(current)) loading.value = false;
  }
}

async function moreMembers() {
  if (disposed || busy.value || loading.value || !nextMember.value || !selected.value?.role) return;
  const id = selectedId.value,
    before = nextMember.value;
  const { current, signal } = startRead();
  try {
    const page = await api.members(id, before, signal);
    if (!currentRead(current)) return;
    const unique = new Map(members.value.map((member) => [member.userId, member]));
    page.items.forEach((member) => unique.set(member.userId, member));
    members.value = [...unique.values()];
    nextMember.value = page.nextBefore;
  } catch (cause) {
    if (currentRead(current) && !signal.aborted) {
      selected.value = null;
      members.value = [];
      error.value = failure(cause);
    }
  } finally {
    if (currentRead(current)) loading.value = false;
  }
}

async function mutate(kind: 'join' | 'leave' | 'remove', target?: CommunityMember) {
  if (disposed || loading.value || busy.value || !selected.value) return;
  if (kind === 'leave' && !confirm('确认退出社区？社区关系不等同于聊天群或语音房成员。')) return;
  if (kind === 'remove' && (!target || !confirm('确认移除成员？这不是封禁，公开社区中对方可以重新加入。'))) return;
  const id = selectedId.value;
  busy.value = true;
  error.value = '';
  success.value = '';
  try {
    if (kind === 'join') await api.join(id, writes.signal);
    else if (kind === 'leave') await api.leave(id, writes.signal);
    else await api.remove(id, target!.userId, writes.signal);
    if (disposed) return;
    emit('changed');
    success.value =
      kind === 'join' ? '已加入社区。' : kind === 'leave' ? '已退出社区。' : '已移除成员；公开社区仍可重新加入。';
    if (kind === 'leave') {
      selected.value = null;
      selectedId.value = '';
      members.value = [];
      nextMember.value = null;
    } else await select(id, true);
  } catch (cause) {
    if (!disposed) error.value = failure(cause);
  } finally {
    if (!disposed) busy.value = false;
  }
  if (!disposed && kind === 'leave' && !selectedId.value) await loadJoined();
}

onMounted(() => {
  if (props.initialId) void select(props.initialId);
  else void loadJoined();
});
onUnmounted(() => {
  disposed = true;
  generation++;
  reads?.abort();
  writes.abort();
});
</script>

<template>
  <section class="membership-panel modal manager-modal" aria-label="社区成员中心">
    <header><h2>社区成员中心</h2><button class="secondary" @click="emit('close')">关闭</button></header>
    <p class="hint">社区入会不自动加入聊天群或语音房。成员名称为入会快照，旧记录以角色与 ID 标识。</p>
    <p v-if="error" role="alert" class="form-error">{{ error }}</p>
    <p v-if="success" role="status" class="success">{{ success }}</p>
    <p v-if="loading" role="status">正在读取社区事实…</p>
    <div v-if="selected">
      <h3>{{ selected.community.name }}</h3
      ><p>{{ selected.community.description }}</p>
      <p
        >{{ selected.community.members }} 位成员 · {{ selected.community.visibility === 'PUBLIC' ? '公开' : '私密' }} ·
        {{ selected.role === 'OWNER' ? '你是所有者' : selected.role ? '你已加入' : '你尚未加入' }}</p
      >
      <div class="actions"
        ><button class="secondary" :disabled="busy || loading" @click="loadJoined()">我的已加入社区</button
        ><button class="secondary" :disabled="busy || loading" @click="select(selectedId)">刷新详情</button>
        <button
          v-if="!selected.role && selected.community.visibility === 'PUBLIC'"
          class="primary"
          :disabled="busy || loading"
          @click="mutate('join')"
          >加入社区</button
        >
        <button v-if="selected.role === 'MEMBER'" class="secondary" :disabled="busy || loading" @click="mutate('leave')"
          >退出社区</button
        ></div
      >
      <div v-if="selected.role" class="member-list"
        ><h3>成员</h3
        ><article v-for="member in members" :key="member.userId"
          ><div
            ><strong>{{ member.displayName || (member.role === 'OWNER' ? '社区所有者' : '成员') }}</strong
            ><small
              >{{ member.handle ? '@' + member.handle + ' · ' : '' }}ID {{ member.userId }} ·
              {{ member.role === 'OWNER' ? '所有者' : '成员' }} ·
              {{ new Date(member.joinedAt).toLocaleString('zh-CN') }}</small
            ></div
          ><button
            v-if="selected.role === 'OWNER' && member.userId !== props.userId && member.role !== 'OWNER'"
            class="secondary"
            :disabled="busy || loading"
            @click="mutate('remove', member)"
            >移除</button
          ></article
        ><button v-if="nextMember" class="secondary" :disabled="busy || loading" @click="moreMembers"
          >加载更多成员</button
        ></div
      >
    </div>
    <div v-else-if="!selectedId"
      ><div class="actions"
        ><h3>我的已加入社区</h3
        ><button class="secondary" :disabled="busy || loading" @click="loadJoined()">刷新列表</button></div
      ><article v-for="item in joined" :key="item.id" class="joined-row"
        ><div
          ><strong>{{ item.name }}</strong
          ><small
            >/{{ item.slug }} · {{ item.members }} 人 · {{ item.visibility === 'PUBLIC' ? '公开' : '私密' }}</small
          ></div
        ><button class="secondary" :disabled="busy || loading" @click="select(item.id)">查看社区</button></article
      ><p v-if="!joined.length && !loading && !error" class="hint">尚未加入活跃社区，可从公开社区卡片查看并加入。</p
      ><button v-if="nextJoined" class="secondary" :disabled="busy || loading" @click="loadJoined(true)"
        >加载更多社区</button
      ></div
    >
    <div v-else-if="!loading" class="actions"
      ><button class="secondary" :disabled="busy" @click="select(selectedId)">重新查询</button
      ><button class="secondary" :disabled="busy" @click="loadJoined()">返回本人列表</button></div
    >
  </section>
</template>

<style scoped>
.membership-panel {
  color: #dce3f0;
  background: #12151e;
  width: min(820px, 95vw);
  max-height: 90vh;
  overflow: auto;
  padding: 24px;
  border-radius: 18px;
}
.membership-panel header,
.actions,
.member-list article,
.joined-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.member-list article,
.joined-row {
  padding: 14px;
  border: 1px solid #343847;
  border-radius: 12px;
  margin: 12px 0;
  background: #191f2a;
}
.membership-panel small {
  display: block;
  color: #a8b5c8;
  overflow-wrap: anywhere;
}
.hint {
  color: #a8b5c8;
  font-size: 13px;
}
.success {
  color: #9ad2b8;
}
@media (max-width: 600px) {
  .membership-panel {
    padding: 14px;
  }
  .member-list article > div,
  .joined-row > div {
    min-width: 0;
    flex: 1;
  }
}
</style>
