<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { RouterLink, useRoute } from 'vue-router';
import {
  ArrowUpRight,
  Bell,
  ChevronDown,
  Compass,
  FileText,
  Headphones,
  Image,
  LogOut,
  Menu,
  MessageCircle,
  Plus,
  Radio,
  Settings,
  ShieldCheck,
  Users,
  X,
} from 'lucide-vue-next';
import type { UserIdentity } from '../api';
import type { WorkspacePage } from '../types/workspace';
import { workspacePages } from '../router/pages';

const props = defineProps<{
  /** 已确认的当前用户；不保存会话令牌。 */
  user: UserIdentity | null;
  /** 已匹配的页面信息。 */
  page: WorkspacePage;
  /** 注销请求期间禁用重复提交。 */
  loggingOut: boolean;
}>();
const emit = defineEmits<{
  /** 打开实际账号表单。 */
  auth: [];
  /** 由父用例执行真实注销。 */
  logout: [];
  /** 打开现有真实创作表单。 */
  create: [];
}>();
const route = useRoute();
const drawer = ref(false),
  accountMenu = ref(false);
const sidebar = ref<HTMLElement>(),
  menuButton = ref<HTMLButtonElement>(),
  accountElement = ref<HTMLElement>();
let originalOverflow = '';
let drawerFocusFrame: number | undefined;
function closeMenus(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    if (drawer.value) {
      event.preventDefault();
      drawer.value = false;
    }
    if (accountMenu.value) {
      event.preventDefault();
      accountMenu.value = false;
      accountElement.value?.querySelector<HTMLButtonElement>('button')?.focus();
    }
    return;
  }
  if (event.key !== 'Tab' || !drawer.value || !sidebar.value) return;
  const targets = [...sidebar.value.querySelectorAll<HTMLElement>('a[href], button')].filter(
    (element) => element.getClientRects().length > 0,
  );
  const first = targets[0],
    last = targets.at(-1);
  if (!first || !last) return;
  if (!sidebar.value.contains(document.activeElement) || (event.shiftKey && document.activeElement === first)) {
    event.preventDefault();
    (event.shiftKey ? last : first).focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
}
function outsideAccount(event: PointerEvent) {
  if (accountMenu.value && event.target instanceof Node && !accountElement.value?.contains(event.target))
    accountMenu.value = false;
}
watch(drawer, async (value) => {
  if (drawerFocusFrame !== undefined) cancelAnimationFrame(drawerFocusFrame);
  if (value) {
    originalOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    await nextTick();
    // 等抽屉可见样式进入一帧后再聚焦；隐藏到可见的 CSS 过渡不能吞掉焦点。
    drawerFocusFrame = requestAnimationFrame(() => {
      if (drawer.value) sidebar.value?.querySelector<HTMLElement>('a[href]')?.focus();
    });
  } else {
    document.body.style.overflow = originalOverflow;
    menuButton.value?.focus();
  }
});
watch([drawer, accountMenu], ([nav, account]) => {
  document.removeEventListener('keydown', closeMenus);
  document.removeEventListener('pointerdown', outsideAccount);
  if (nav || account) document.addEventListener('keydown', closeMenus);
  if (account) document.addEventListener('pointerdown', outsideAccount);
});
onBeforeUnmount(() => {
  if (drawerFocusFrame !== undefined) cancelAnimationFrame(drawerFocusFrame);
  document.removeEventListener('keydown', closeMenus);
  document.removeEventListener('pointerdown', outsideAccount);
  if (drawer.value) document.body.style.overflow = originalOverflow;
});
const initials = computed(() => props.user?.displayName.slice(0, 1).toUpperCase() || 'K');
const icons = {
  explore: Compass,
  communities: Users,
  creators: Users,
  voice: Headphones,
  live: Radio,
  studio: FileText,
  assets: Image,
  messages: MessageCircle,
  notifications: Bell,
  account: Settings,
};
const groups = [
  { key: 'space', title: '探索空间' },
  { key: 'creator', title: '创作者工作台' },
  { key: 'collaboration', title: '交流与通知' },
];
function focusContent() {
  const content = document.getElementById('workspace-content');
  content?.focus();
  content?.scrollIntoView({ block: 'start' });
}
watch(
  () => route.fullPath,
  () => {
    drawer.value = false;
    accountMenu.value = false;
  },
);
</script>

<template>
  <div class="workspace-shell">
    <a class="skip-link" href="#workspace-content" @click.prevent="focusContent">跳到主要内容</a>
    <button v-if="drawer" class="nav-scrim" aria-label="关闭导航" @click="drawer = false" />
    <aside
      ref="sidebar"
      class="workspace-sidebar"
      :class="{ 'is-open': drawer }"
      :role="drawer ? 'dialog' : undefined"
      :aria-modal="drawer ? true : undefined"
      aria-label="主导航"
    >
      <RouterLink to="/explore" class="workspace-brand"
        ><span class="brand-symbol">K</span><span><strong>KOKO NEXUS</strong><small>可可星联</small></span></RouterLink
      >
      <button class="mobile-only drawer-close" aria-label="关闭导航" @click="drawer = false"><X :size="20" /></button>
      <div class="workspace-switch"
        ><span class="workspace-dot" /><span>创作者与社区空间</span><small>工作台</small></div
      >
      <nav class="workspace-navigation">
        <section v-for="group in groups" :key="group.key" class="nav-group">
          <h2>{{ group.title }}</h2>
          <RouterLink
            v-for="item in workspacePages.filter((candidate) => candidate.group === group.key)"
            :key="item.name"
            :to="item.path"
            class="workspace-nav-item"
            :class="{ active: route.name === item.name }"
            :aria-current="route.name === item.name ? 'page' : undefined"
          >
            <component :is="icons[item.name as keyof typeof icons]" :size="18" /><span>{{ item.title }}</span>
          </RouterLink>
        </section>
      </nav>
      <div class="sidebar-bottom"
        ><RouterLink to="/account" class="workspace-nav-item" :class="{ active: route.name === 'account' }"
          ><Settings :size="18" />账户与连接</RouterLink
        >
        <RouterLink
          v-if="user"
          to="/operations"
          class="workspace-nav-item"
          :class="{ active: route.name === 'operations' }"
          ><ShieldCheck :size="18" />通知投递运维</RouterLink
        >
        <div class="environment-note"><span>阶段验收环境</span><small>完整上线门槛仍在推进</small></div>
        <RouterLink
          v-if="user"
          to="/operations/binding-releases"
          class="workspace-nav-item"
          :class="{ active: route.name === 'binding-operations' }"
          :aria-current="route.name === 'binding-operations' ? 'page' : undefined"
        >
          <ShieldCheck :size="18" />绑定释放排障
        </RouterLink>
      </div>
    </aside>
    <div class="workspace-main">
      <header class="workspace-topbar">
        <div class="breadcrumb"
          ><button
            ref="menuButton"
            class="mobile-only icon-button"
            aria-label="打开导航"
            :aria-expanded="drawer"
            @click="drawer = true"
            ><Menu :size="20" /></button
          ><span>KOKO Nexus</span><span class="breadcrumb-separator">/</span><strong>{{ page.title }}</strong></div
        >
        <div class="workspace-header-actions">
          <button class="secondary compact-button" @click="emit('create')"
            ><Plus :size="16" /><span>开始创作</span></button
          >
          <template v-if="user"
            ><RouterLink to="/notifications" class="icon-button" aria-label="通知中心"><Bell :size="18" /></RouterLink
            ><div ref="accountElement" class="account-menu">
              <button
                class="account-trigger"
                aria-label="账户菜单"
                :aria-expanded="accountMenu"
                @click="accountMenu = !accountMenu"
                ><span class="account-avatar">{{ initials }}</span
                ><span class="account-name">{{ user.displayName }}</span
                ><ChevronDown :size="15"
              /></button>
              <div v-if="accountMenu" class="account-dropdown"
                ><strong>{{ user.displayName }}</strong
                ><small>@{{ user.handle }}</small
                ><RouterLink to="/account"><Settings :size="16" />账户与连接</RouterLink
                ><button
                  :disabled="loggingOut"
                  @click="
                    accountMenu = false;
                    emit('logout');
                  "
                  ><LogOut :size="16" />{{ loggingOut ? '正在退出…' : '退出登录' }}</button
                ></div
              >
            </div></template
          >
          <button v-else class="primary compact-button" @click="emit('auth')"
            >登录 / 注册<ArrowUpRight :size="15"
          /></button>
        </div>
      </header>
      <main id="workspace-content" class="workspace-content" tabindex="-1"><slot /></main>
      <footer class="workspace-footer"
        ><span>KOKO Nexus · 创作者实时社区</span><span>内容 · 社区 · 实时交流</span></footer
      >
    </div>
  </div>
</template>
