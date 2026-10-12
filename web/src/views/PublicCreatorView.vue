<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { RouterLink, useRoute } from 'vue-router';
import { RefreshCw, Share2, UserRound } from 'lucide-vue-next';
import { managedImageUrl } from '../api';
import { useAuthStore } from '../stores/auth';
import { usePublicCreator } from '../composables/public-creator';
const emit = defineEmits<{ /** 打开现有真实登录弹窗，不自动关注。 */ login: [] }>();
const route = useRoute(),
  auth = useAuthStore();
const {
  profile,
  relation,
  loading,
  error,
  relationLoading,
  relationError,
  writing,
  uncertain,
  success,
  load,
  readRelation,
  toggleFollow,
} = usePublicCreator(() => ({
  slug: typeof route.params.slug === 'string' ? route.params.slug : '',
  userId: auth.user?.id,
  sessionRevision: auth.sessionRevision,
}));
/** 新页面只显示已验证受管图片，不执行外部/任意协议的历史URL。 */
const image = (id?: string) =>
  id && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(id) ? managedImageUrl(id) : '';
const avatar = computed(() => image(profile.value?.avatarAssetId)),
  banner = computed(() => image(profile.value?.bannerAssetId));
const followers = computed(() => relation.value?.followerCount ?? profile.value?.followerCount);
const shareUrl = computed(() =>
  profile.value ? `${location.origin}${location.pathname}#/creators/${encodeURIComponent(profile.value.slug)}` : '',
);
const shareMessage = ref('');
watch(shareUrl, () => {
  shareMessage.value = '';
});
async function copyAddress() {
  if (!shareUrl.value) return;
  const address = shareUrl.value;
  try {
    await navigator.clipboard.writeText(address);
    if (shareUrl.value === address) shareMessage.value = '主页地址已复制。';
  } catch {
    if (shareUrl.value === address) shareMessage.value = '浏览器未允许复制，请手动选择下方地址。';
  }
}
</script>
<template>
  <main class="creator-detail" aria-label="UP主公开主页">
    <div class="workspace-page-heading">
      <div
        ><p class="workspace-eyebrow">CREATOR PROFILE</p><h1>UP 主主页</h1
        ><p>这里展示已发布的公开资料，不包含私人草稿。</p></div
      >
      <RouterLink class="secondary" to="/creators">返回创作者</RouterLink>
    </div>
    <p v-if="loading" role="status" class="workspace-note">正在读取公开主页…</p>
    <section v-if="error" class="workspace-error" role="alert"
      ><h2>主页暂不可用</h2><p>{{ error }}</p
      ><p>尚未发布、已不可见或不存在的主页不会展示私人资料。</p
      ><button class="secondary" :disabled="loading" @click="load">重新读取主页</button></section
    >
    <article v-if="profile && !error" class="workspace-card">
      <img v-if="banner" class="detail-banner" :src="banner" alt="创作者主页封面" />
      <div class="detail-identity"
        ><img v-if="avatar" class="detail-avatar" :src="avatar" :alt="`${profile.displayName}的头像`" /><UserRound
          v-else
          :size="48"
          aria-hidden="true"
        /><div
          ><h2>{{ profile.displayName }}</h2
          ><p>{{ profile.headline || '暂未填写短介绍' }}</p
          ><small>{{ followers }} 人关注 · /{{ profile.slug }}</small></div
        ></div
      >
      <section aria-label="创作者介绍"
        ><h3>关于我</h3><p class="detail-bio">{{ profile.bio || '创作者还没有填写介绍。' }}</p></section
      >
      <div class="page-actions">
        <RouterLink v-if="auth.user?.id === profile.userId" class="secondary" to="/studio">维护我的主页</RouterLink>
        <button v-else-if="!auth.user" class="primary" @click="emit('login')">登录后关注</button>
        <button
          v-else
          class="primary"
          :disabled="loading || writing || relationLoading || !relation || uncertain"
          @click="toggleFollow"
          >{{
            writing
              ? '正在提交…'
              : uncertain
                ? '关注待核实'
                : relationLoading
                  ? '正在核验关注…'
                  : relation?.followedByMe
                    ? '取消关注'
                    : '关注创作者'
          }}</button
        >
        <button
          v-if="auth.user && auth.user.id !== profile.userId"
          class="secondary"
          :disabled="writing || relationLoading || loading"
          @click="readRelation"
          ><RefreshCw :size="16" />查询关注状态</button
        >
        <button class="secondary" @click="copyAddress"><Share2 :size="16" />复制主页地址</button>
      </div>
      <p v-if="success" class="workspace-success" role="status">{{ success }}</p>
      <p v-if="relationError" class="workspace-error compact" role="alert">{{ relationError }}</p>
      <p v-if="uncertain" class="workspace-note">查询只核实当前服务端事实，不会自动重发关注或取消关注。</p>
      <label class="workspace-field"
        >公开主页地址<input readonly :value="shareUrl" @focus="($event.target as HTMLInputElement).select()"
      /></label>
      <p v-if="shareMessage" class="workspace-note" role="status">{{ shareMessage }}</p>
    </article>
  </main>
</template>
<style scoped>
.creator-detail {
  min-width: 0;
}
.detail-banner {
  width: 100%;
  max-height: 240px;
  object-fit: cover;
  border-radius: 12px;
}
.detail-identity {
  display: flex;
  align-items: center;
  gap: 20px;
  margin: 24px 0;
}
.detail-avatar {
  width: 80px;
  height: 80px;
  border-radius: 50%;
  object-fit: cover;
}
.detail-identity > div {
  min-width: 0;
  overflow-wrap: anywhere;
}
.detail-bio {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.8;
}
.creator-detail article {
  overflow-wrap: anywhere;
}
.creator-detail .page-actions {
  flex-wrap: wrap;
  margin: 24px 0;
}
@media (max-width: 600px) {
  .detail-identity {
    gap: 12px;
  }
  .detail-avatar {
    width: 56px;
    height: 56px;
  }
}
</style>
