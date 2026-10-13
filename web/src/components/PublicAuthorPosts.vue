<script setup lang="ts">
import { managedImageUrl, type CreatorPost } from '../api';
import { useAuthStore } from '../stores/auth';
import { usePublicAuthorPosts } from '../composables/public-author-posts';
import { formatAuthorPostTime } from '../services/author-post-time';
const props = defineProps<{ /** 已确认公开主页作者，不是当前访问者。 */ ownerId: string }>();
const emit = defineEmits<{ /** 交给已有真实正文阅读器，不以摘要冒充全文。 */ read: [post: CreatorPost] }>();
const auth = useAuthStore();
const { items, nextCursor, loaded, loading, error, timeZone, load, retry } = usePublicAuthorPosts(() => ({
  ownerId: props.ownerId,
  userId: auth.user?.id,
  sessionRevision: auth.sessionRevision,
}));
const cover = (post: CreatorPost) =>
  post.coverAssetId && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(post.coverAssetId)
    ? managedImageUrl(post.coverAssetId)
    : '';
</script>
<template>
  <section class="author-posts" aria-label="作者已发布作品" :aria-busy="loading">
    <div class="workspace-card-heading"
      ><h3>已发布作品</h3
      ><button type="button" class="secondary" :disabled="loading" @click="load()">刷新作品</button></div
    >
    <p class="workspace-note">按发布时间排列，仅展示公开作品；当前已加载 {{ items.length }} 篇，不代表全量总数。</p>
    <p v-if="loading" class="workspace-note" role="status">正在读取作品…</p>
    <div v-if="error" class="workspace-error compact" role="alert"
      ><p>{{ error }}</p
      ><button class="secondary" :disabled="loading" @click="retry">重试作品读取</button></div
    >
    <p v-else-if="loaded && !items.length" class="workspace-note">这位作者暂时没有已发布作品。</p>
    <div class="author-post-grid">
      <article v-for="post in items" :key="post.id" class="author-post-card">
        <img v-if="cover(post)" :src="cover(post)" alt="文章封面" loading="lazy" />
        <h4>{{ post.title }}</h4
        ><p>{{ post.excerpt || '作者尚未填写摘要。' }}</p>
        <time>{{ formatAuthorPostTime(post.publishedAt, timeZone) }}（北京时间）</time>
        <button class="secondary" @click="emit('read', post)">阅读《{{ post.title }}》</button>
      </article>
    </div>
    <button v-if="nextCursor && !error" class="secondary" :disabled="loading" @click="load(false)">加载更多作品</button>
  </section>
</template>
<style scoped>
.author-posts {
  margin-top: 32px;
  border-top: 1px solid #303541;
  padding-top: 24px;
  min-width: 0;
}
.author-post-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  margin: 16px 0;
}
.author-post-card {
  border: 1px solid #303541;
  border-radius: 12px;
  padding: 16px;
  overflow-wrap: anywhere;
  min-width: 0;
}
.author-post-card img {
  width: 100%;
  max-height: 160px;
  object-fit: cover;
  border-radius: 8px;
}
.author-post-card h4 {
  font-size: 17px;
  margin: 12px 0;
}
.author-post-card p {
  line-height: 1.7;
}
.author-post-card time {
  display: block;
  margin: 12px 0;
  color: #8b96ad;
  font-size: 12px;
}
.author-post-card button {
  max-width: 100%;
  white-space: normal;
  text-align: left;
}
@media (max-width: 600px) {
  .author-post-grid {
    grid-template-columns: 1fr;
  }
}
</style>
