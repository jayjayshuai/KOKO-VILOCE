<script setup lang="ts">
import { FileText, Pencil, Plus, RefreshCw, UserRound } from 'lucide-vue-next';
import type { CreatorPost, CreatorProfile } from '../api';
const props = defineProps<{
  /** 真实本人内容列表，未完成读取时不冒充空列表。 */
  posts: CreatorPost[];
  /** 本人主页事实；尚未创建为 null。 */
  profile: CreatorProfile | null;
  /** 页面读取进行中。 */
  loading: boolean;
  /** 当前读取错误。 */
  error: string;
}>();
const emit = defineEmits<{
  /** 重查服务器事实。 */
  refresh: [];
  /** 新建实际草稿表单。 */
  create: [];
  /** 编辑服务器返回的本人内容。 */
  edit: [post: CreatorPost];
  /** 打开本人 UP 主表单。 */
  profile: [];
}>();
</script>
<template>
  <div class="workspace-page-heading"
    ><div
      ><p class="workspace-eyebrow">CREATOR WORKSPACE</p><h1>内容工作台</h1
      ><p>从草稿开始，把你的想法变成真实发布的内容。</p></div
    ><div class="page-actions"
      ><button class="secondary" :disabled="loading" @click="emit('refresh')"><RefreshCw :size="16" />刷新</button
      ><button class="primary" @click="emit('create')"><Plus :size="17" />新建文章</button></div
    ></div
  >
  <div v-if="loading" class="workspace-skeleton" role="status">正在读取你的内容与主页…</div>
  <div v-else-if="error" class="workspace-error" role="alert"
    ><h2>工作台读取失败</h2><p>{{ error }}</p
    ><button class="secondary" @click="emit('refresh')">重新读取</button></div
  >
  <template v-else>
    <section class="studio-profile-card"
      ><div class="studio-profile-icon"><UserRound :size="24" /></div
      ><div
        ><p class="workspace-eyebrow">你的创作者主页</p><h2>{{ profile?.displayName || '建立你的 UP 主身份' }}</h2
        ><p>{{
          profile
            ? `/${profile.slug} · ${profile.status === 'ACTIVE' ? '已发布' : profile.status === 'DRAFT' ? '草稿' : '已暂停'}`
            : '完善资料、上传头像和封面，再由你确认发布。'
        }}</p></div
      ><button class="secondary" @click="emit('profile')">{{ profile ? '维护主页' : '创建主页' }}</button></section
    >
    <section class="workspace-card"
      ><div class="workspace-card-heading"
        ><h2>我的内容</h2><span class="muted">当前列表 {{ posts.length }} 篇</span></div
      >
      <div v-if="posts.length" class="studio-table-wrap"
        ><table class="studio-table"
          ><thead
            ><tr
              ><th scope="col">内容</th><th scope="col">状态</th><th scope="col">版本</th><th scope="col">操作</th></tr
            ></thead
          ><tbody
            ><tr v-for="post in posts" :key="post.id"
              ><td
                ><strong>{{ post.title }}</strong
                ><small>/{{ post.slug }}</small></td
              ><td
                ><span class="status-tag" :class="post.status === 'PUBLISHED' ? 'positive' : ''">{{
                  post.status === 'PUBLISHED' ? '已发布' : '草稿'
                }}</span></td
              ><td class="muted">v{{ post.version }}</td
              ><td
                ><button class="secondary compact-button" @click="emit('edit', post)"
                  ><Pencil :size="14" />编辑</button
                ></td
              ></tr
            ></tbody
          ></table
        ></div
      >
      <div v-else class="workspace-empty"
        ><FileText :size="30" /><h3>还没有内容草稿</h3><p>草稿只对你开放，确认发布后才会进入发现页。</p
        ><button class="primary" @click="emit('create')">创建第一篇文章</button></div
      >
    </section>
  </template>
</template>
