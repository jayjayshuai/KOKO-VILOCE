<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue';
import { Bell, Check, RefreshCw, SlidersHorizontal } from 'lucide-vue-next';
import { api, type NotificationItem, type NotificationType } from '../api';
defineProps<{
  /** 页面 key 隔离的当前账号；服务端仍只接受会话本人。 */
  userId: string;
}>();
const types: NotificationType[] = ['FOLLOW', 'COMMENT', 'LIVE_STARTED'];
const labels: Record<NotificationType, string> = { FOLLOW: '新关注', COMMENT: '文章评论', LIVE_STARTED: '关注的直播' };
const items = ref<NotificationItem[]>([]),
  page = ref(0),
  total = ref(0);
const loading = ref(false),
  preferenceLoading = ref(false),
  busy = ref(false);
const error = ref(''),
  preferenceError = ref(''),
  success = ref('');
const preferences = reactive<Partial<Record<NotificationType, boolean>>>({});
const reads = new AbortController(),
  writes = new AbortController();
let disposed = false;
const failure = (cause: unknown) => (cause instanceof Error ? cause.message : '通知请求失败');

/** 服务端事实分页；失败保留已读列表但显示错误，不假装空通知。 */
async function load(reset = true, afterWrite = false) {
  if (disposed || loading.value || (busy.value && !afterWrite)) return;
  loading.value = true;
  error.value = '';
  try {
    const result = await api.notifications(reset ? 1 : page.value + 1, 20, reads.signal);
    if (disposed) return;
    const unique = new Map((reset ? [] : items.value).map((item) => [item.id, item]));
    result.items.forEach((item) => unique.set(item.id, item));
    items.value = [...unique.values()];
    page.value = result.page;
    total.value = result.total;
  } catch (cause) {
    if (!disposed) error.value = afterWrite ? `已读已提交，但重新读取失败：${failure(cause)}` : failure(cause);
  } finally {
    if (!disposed) loading.value = false;
  }
}

/** 偏好读取独立失败，不让偏好依赖阻断已成功读取的收件箱。 */
async function loadPreferences() {
  if (disposed || preferenceLoading.value || busy.value) return;
  preferenceLoading.value = true;
  preferenceError.value = '';
  try {
    const result = await Promise.all(types.map((type) => api.notificationPreference(type, reads.signal)));
    if (disposed) return;
    for (const value of result) preferences[value.eventType] = value.enabled;
  } catch (cause) {
    if (!disposed) preferenceError.value = failure(cause);
  } finally {
    if (!disposed) preferenceLoading.value = false;
  }
}

async function markRead(item: NotificationItem) {
  if (disposed || busy.value || loading.value || item.readAt) return;
  busy.value = true;
  error.value = '';
  success.value = '';
  try {
    await api.markNotificationRead(item.id, writes.signal);
    if (disposed) return;
    success.value = '已读状态已提交。';
    await load(true, true);
  } catch (cause) {
    if (!disposed) error.value = failure(cause);
  } finally {
    if (!disposed) busy.value = false;
  }
}

async function togglePreference(type: NotificationType) {
  if (disposed || busy.value || preferenceLoading.value || preferences[type] === undefined) return;
  busy.value = true;
  preferenceError.value = '';
  success.value = '';
  try {
    const value = await api.saveNotificationPreference(type, !preferences[type], writes.signal);
    if (disposed) return;
    preferences[value.eventType] = value.enabled;
    success.value = '接收偏好已保存。';
  } catch (cause) {
    if (!disposed) preferenceError.value = failure(cause);
  } finally {
    if (!disposed) busy.value = false;
  }
}
onMounted(() => {
  void load();
  void loadPreferences();
});
onUnmounted(() => {
  disposed = true;
  reads.abort();
  writes.abort();
});
</script>
<template>
  <div class="workspace-page-heading"
    ><div
      ><p class="workspace-eyebrow">NOTIFICATION INBOX</p><h1>通知中心</h1
      ><p>关注、评论与直播事件的真实收件箱。</p></div
    ><button class="secondary" :disabled="busy || loading" @click="load()"><RefreshCw :size="16" />刷新</button></div
  >
  <div v-if="success" class="workspace-success" role="status"><Check :size="16" />{{ success }}</div>
  <div class="notification-workspace-grid"
    ><section class="workspace-card"
      ><div class="workspace-card-heading"
        ><h2>我的通知</h2><span v-if="page" class="muted">{{ total }} 条</span></div
      >
      <p v-if="error" class="workspace-error compact" role="alert">{{ error }}</p>
      <div v-if="items.length" class="notification-inbox"
        ><article v-for="item in items" :key="item.id" :class="{ unread: !item.readAt }"
          ><span class="notification-symbol"><Bell :size="18" /></span
          ><div
            ><div class="notification-title"
              ><strong>{{ labels[item.eventType] }}</strong
              ><span v-if="!item.readAt" class="unread-indicator">未读</span></div
            ><p>{{ item.summary }}</p
            ><small>{{ new Date(item.createdAt).toLocaleString('zh-CN') }}</small></div
          ><button
            v-if="!item.readAt"
            class="secondary compact-button"
            :disabled="busy || loading"
            @click="markRead(item)"
            >标为已读</button
          ><span v-else class="muted">已读</span></article
        ></div
      >
      <p v-if="loading" class="workspace-skeleton" role="status">正在读取通知…</p>
      <div v-if="page && !items.length && !loading && !error" class="workspace-empty"
        ><Bell :size="28" /><h3>暂时没有通知</h3><p>真实事件到达后才会出现在这里。</p></div
      >
      <button v-if="error" class="secondary" :disabled="busy || loading" @click="load()">重新读取</button>
      <button
        v-else-if="items.length < total"
        class="secondary load-more"
        :disabled="busy || loading"
        @click="load(false)"
        >加载更多通知</button
      > </section
    ><aside class="workspace-card notification-settings"
      ><div class="workspace-card-heading"><h2>接收偏好</h2><SlidersHorizontal :size="18" /></div
      ><p class="workspace-note">关闭类型后，新事件会按偏好抑制。已有通知和已读记录不会被清空。</p
      ><div v-for="type in types" :key="type" class="preference-setting"
        ><span>{{ labels[type] }}</span
        ><button
          role="switch"
          :aria-checked="preferences[type] ?? false"
          :aria-label="labels[type] + '通知'"
          :disabled="busy || preferenceLoading || preferences[type] === undefined"
          :class="{ enabled: preferences[type] === true }"
          @click="togglePreference(type)"
          >{{ preferences[type] === undefined ? '未读取' : preferences[type] ? '开启' : '关闭' }}</button
        ></div
      ><p v-if="preferenceLoading" class="workspace-note" role="status">正在读取偏好…</p
      ><p v-if="preferenceError" class="form-error" role="alert">{{ preferenceError }}</p
      ><button v-if="preferenceError" class="secondary" :disabled="busy || preferenceLoading" @click="loadPreferences"
        >重试偏好读取</button
      ></aside
    ></div
  >
</template>
