<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';
import { Bell, Check, RefreshCw, SlidersHorizontal } from 'lucide-vue-next';
import { api, type NotificationItem, type NotificationType } from '../api';
import { formatNotificationTime, notificationDate } from '../services/notification-time';
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
/** 偏好写入与收件箱已读是独立域，互不阻断等待。 */
const preferenceSaving = ref<NotificationType | null>(null);
const error = ref(''),
  preferenceError = ref(''),
  success = ref(''),
  preferenceSuccess = ref('');
const preferences = reactive<Partial<Record<NotificationType, boolean>>>({});
/** 写入失败不表示回滚；保留上次确认值，但禁止据此再次切换。 */
const uncertainPreferences = reactive<Partial<Record<NotificationType, boolean>>>({});
/** 即便其他类型保存成功，旧的未知结果仍必须有可见的核实入口。 */
const hasUncertainPreference = computed(() => types.some((type) => uncertainPreferences[type]));
/** 分页失败后重试原页，不把“加载更多”失败转换成刷新第一页。 */
const failedReadReset = ref(true);
/** 未确认的已读请求按ID保留；部分分页不能清除未见目标的状态。 */
const uncertainReadIds = reactive(new Set<string>());
/** 只筛选已加载的数据，不能把有限分页当全量未读数。 */
const unreadOnly = ref(false);
const loadedUnread = computed(() => items.value.filter((item) => !item.readAt).length);
const visibleItems = computed(() => (unreadOnly.value ? items.value.filter((item) => !item.readAt) : items.value));
const validId = (value: unknown) =>
  typeof value === 'string' && /^[1-9][0-9]{0,18}$/.test(value) && BigInt(value) <= 9223372036854775807n;
const reads = new AbortController(),
  writes = new AbortController();
let disposed = false;
const failure = (cause: unknown) => (cause instanceof Error ? cause.message : '通知请求失败');

/** 服务端事实分页；失败保留已读列表但显示错误，不假装空通知。 */
async function load(reset = true, afterWrite = false) {
  if (disposed || loading.value || (busy.value && !afterWrite)) return;
  const requestedPage = reset ? 1 : page.value + 1;
  loading.value = true;
  error.value = '';
  try {
    const result = await api.notifications(requestedPage, 20, reads.signal);
    if (disposed) return;
    if (
      !result ||
      result.page !== requestedPage ||
      !Number.isSafeInteger(result.total) ||
      result.total < 0 ||
      !Array.isArray(result.items) ||
      result.items.length > 20 ||
      result.items.some(
        (item) =>
          !item ||
          !validId(item.id) ||
          !types.includes(item.eventType) ||
          typeof item.summary !== 'string' ||
          !notificationDate(item.createdAt) ||
          (item.readAt != null && !notificationDate(item.readAt)),
      )
    )
      throw new Error('通知分页响应不符合契约，请重新读取。');
    const unique = new Map((reset ? [] : items.value).map((item) => [item.id, item]));
    result.items.forEach((item) => unique.set(item.id, item));
    items.value = [...unique.values()];
    page.value = result.page;
    total.value = result.total;
    result.items.forEach((item) => {
      if (item.readAt) uncertainReadIds.delete(item.id);
    });
  } catch (cause) {
    if (!disposed) {
      failedReadReset.value = reset;
      error.value = afterWrite ? `已读已提交，但重新读取失败：${failure(cause)}` : failure(cause);
    }
  } finally {
    if (!disposed) loading.value = false;
  }
}

/** 沿用上次失败的读取位置；只读重试从不重发已读或偏好写入。 */
function retryLoad() {
  return load(failedReadReset.value);
}

/** 偏好读取独立失败，不让偏好依赖阻断已成功读取的收件箱。 */
async function loadPreferences() {
  if (disposed || preferenceLoading.value || preferenceSaving.value) return;
  preferenceLoading.value = true;
  preferenceError.value = '';
  try {
    const result = await Promise.all(types.map((type) => api.notificationPreference(type, reads.signal)));
    if (disposed) return;
    // 三个结果按请求顺序核对后原子应用，错类型/缺字段不能清除未知写入状态。
    if (result.some((value, index) => !value || value.eventType !== types[index] || typeof value.enabled !== 'boolean'))
      throw new Error('通知偏好响应不符合契约，请重新读取。');
    for (const value of result) {
      preferences[value.eventType] = value.enabled;
      delete uncertainPreferences[value.eventType];
    }
  } catch (cause) {
    if (!disposed) preferenceError.value = failure(cause);
  } finally {
    if (!disposed) preferenceLoading.value = false;
  }
}

async function markRead(item: NotificationItem) {
  const currentItem = items.value.find((value) => value.id === item.id);
  if (
    disposed ||
    busy.value ||
    loading.value ||
    !currentItem ||
    !validId(currentItem.id) ||
    currentItem.readAt ||
    uncertainReadIds.has(item.id)
  )
    return;
  busy.value = true;
  uncertainReadIds.add(currentItem.id);
  error.value = '';
  success.value = '';
  try {
    await api.markNotificationRead(item.id, writes.signal);
    if (disposed) return;
    success.value = '已读状态已提交。';
    await load(true, true);
  } catch (cause) {
    if (!disposed) {
      uncertainReadIds.add(item.id);
      failedReadReset.value = true;
      error.value = `已读结果未确认，服务端可能已执行；请先重新读取。${failure(cause)}`;
    }
  } finally {
    if (!disposed) busy.value = false;
  }
}

async function togglePreference(type: NotificationType) {
  if (
    disposed ||
    preferenceSaving.value ||
    preferenceLoading.value ||
    preferences[type] === undefined ||
    uncertainPreferences[type]
  )
    return;
  const requestedEnabled = !preferences[type];
  preferenceSaving.value = type;
  preferenceError.value = '';
  preferenceSuccess.value = '';
  try {
    const value = await api.saveNotificationPreference(type, requestedEnabled, writes.signal);
    if (disposed) return;
    if (!value || value.eventType !== type || value.enabled !== requestedEnabled)
      throw new Error('偏好保存响应未确认请求状态。');
    preferences[type] = value.enabled;
    preferenceSuccess.value = `${labels[type]}通知已${value.enabled ? '开启' : '关闭'}。`;
  } catch (cause) {
    if (!disposed) {
      uncertainPreferences[type] = true;
      preferenceError.value = `保存结果未确认，服务端可能已执行；请重新读取偏好后再切换。${failure(cause)}`;
    }
  } finally {
    if (!disposed) preferenceSaving.value = null;
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
    ><section class="workspace-card" aria-labelledby="notification-inbox-heading" :aria-busy="loading || busy"
      ><div class="workspace-card-heading"
        ><h2 id="notification-inbox-heading">我的通知</h2><span v-if="page" class="muted">{{ total }} 条</span></div
      >
      <div v-if="page" class="page-actions">
        <button class="secondary" :aria-pressed="!unreadOnly" @click="unreadOnly = false">全部已加载</button>
        <button class="secondary" :aria-pressed="unreadOnly" @click="unreadOnly = true"
          >只看已加载未读（{{ loadedUnread }}）</button
        >
      </div>
      <p v-if="unreadOnly" class="workspace-note">只筛选已加载通知，不代表全部未读。仍可加载更多通知。</p>
      <p v-if="uncertainReadIds.size" class="workspace-note" role="status"
        >有已读操作待核实；对应按钮不会重复提交，请重新读取通知。</p
      >
      <p v-if="error" class="workspace-error compact" role="alert">{{ error }}</p>
      <div v-if="items.length" class="notification-inbox"
        ><article v-for="item in visibleItems" :key="item.id" :class="{ unread: !item.readAt }"
          ><span class="notification-symbol"><Bell :size="18" /></span
          ><div
            ><div class="notification-title"
              ><strong>{{ labels[item.eventType] }}</strong
              ><span v-if="!item.readAt" class="unread-indicator">未读</span></div
            ><p>{{ item.summary }}</p
            ><small>{{ formatNotificationTime(item.createdAt) }}</small></div
          ><button
            v-if="!item.readAt"
            class="secondary compact-button"
            :disabled="busy || loading || uncertainReadIds.has(item.id)"
            @click="markRead(item)"
            >{{ uncertainReadIds.has(item.id) ? '已读待核实' : '标为已读' }}</button
          ><span v-else class="muted">已读</span></article
        ></div
      >
      <p v-if="unreadOnly && items.length && !visibleItems.length && !loading" role="status"
        >已加载通知中没有未读项；可以查看全部或加载更多。</p
      >
      <p v-if="loading" class="workspace-skeleton" role="status">正在读取通知…</p>
      <div v-if="page && !items.length && !loading && !error" class="workspace-empty"
        ><Bell :size="28" /><h3>暂时没有通知</h3><p>真实事件到达后才会出现在这里。</p></div
      >
      <button v-if="error" class="secondary" :disabled="busy || loading" @click="retryLoad">重新读取</button>
      <button
        v-else-if="items.length < total"
        class="secondary load-more"
        :disabled="busy || loading"
        @click="load(false)"
        >加载更多通知</button
      > </section
    ><aside
      class="workspace-card notification-settings"
      aria-labelledby="notification-preferences-heading"
      :aria-busy="preferenceLoading || !!preferenceSaving"
      ><div class="workspace-card-heading"
        ><h2 id="notification-preferences-heading">接收偏好</h2><SlidersHorizontal :size="18" /></div
      ><p class="workspace-note">关闭类型后，新事件会按偏好抑制。已有通知和已读记录不会被清空。</p
      ><p v-if="preferenceSuccess" class="workspace-success" role="status">{{ preferenceSuccess }}</p
      ><div v-for="type in types" :key="type" class="preference-setting"
        ><span>{{ labels[type] }}</span
        ><button
          role="switch"
          :aria-checked="preferences[type] ?? false"
          :aria-label="
            labels[type] + '通知' + (uncertainPreferences[type] ? '，保存结果待核实，勾选状态为上次确认值' : '')
          "
          :disabled="
            !!preferenceSaving || preferenceLoading || preferences[type] === undefined || uncertainPreferences[type]
          "
          :class="{ enabled: preferences[type] === true && !uncertainPreferences[type] }"
          @click="togglePreference(type)"
          >{{
            preferenceSaving === type
              ? '保存中…'
              : uncertainPreferences[type]
                ? '待核实'
                : preferences[type] === undefined
                  ? '未读取'
                  : preferences[type]
                    ? '开启'
                    : '关闭'
          }}</button
        ></div
      ><p v-if="preferenceLoading" class="workspace-note" role="status">正在读取偏好…</p
      ><p v-if="hasUncertainPreference" class="workspace-note" role="status"
        >有偏好保存结果待核实，上次确认值不代表当前服务端状态。读取前不会再次切换该类型。</p
      ><p v-if="preferenceError" class="form-error" role="alert">{{ preferenceError }}</p
      ><button
        v-if="preferenceError || hasUncertainPreference"
        class="secondary"
        :disabled="!!preferenceSaving || preferenceLoading"
        @click="loadPreferences"
        >重新读取偏好</button
      ></aside
    ></div
  >
</template>
