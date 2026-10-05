<script setup lang="ts">
import { computed } from 'vue';
import { RouterLink } from 'vue-router';
import {
  ArrowRight,
  BookOpen,
  ChevronRight,
  FileText,
  Headphones,
  Heart,
  MessageCircle,
  Plus,
  Radio,
  RefreshCw,
  Users,
  Video,
} from 'lucide-vue-next';
import type { Community, CreatorPost, CreatorProfile, VoiceRoom } from '../api';
import type { DiscoverySnapshot, PublicSection, WorkspaceAction } from '../types/workspace';
const props = defineProps<{
  /** 当前公开领域；explore 显示聚合首页。 */
  section: PublicSection;
  /** 服务端实际公开快照，不含硬编码示例业务。 */
  data: DiscoverySnapshot;
  /** 初次/整体快照读取中。 */
  loading: boolean;
  /** 快照读取失败原因。 */
  error: string;
  /** 分页读取进行中。 */
  pageLoading: boolean;
  /** 分页错误，不能冒充已到列表尾部。 */
  pageError: string;
  /** 本人 ID，只用于关注按钮显示。 */
  userId?: string;
  /** 本人公开显示名，可为空。 */
  displayName?: string;
  /** 实际已关注的创作者 ID。 */
  followedIds: Set<string>;
  /** 正在提交关注的对象。 */
  followSubmitting: string | null;
  /** 正在连接的语音房。 */
  voiceJoining: string | null;
  /** 实际语音连接错误。 */
  voiceError: string;
}>();
const emit = defineEmits<{
  retry: [];
  loadMore: [kind: 'creators' | 'posts'];
  action: [kind: WorkspaceAction];
  readPost: [post: CreatorPost];
  follow: [creator: CreatorProfile];
  community: [id?: string];
  joinVoice: [room: VoiceRoom];
}>();
const headings = {
  explore: { title: '你的创作，与社区连接。', copy: '发现值得关注的内容，在社区里交流，让新的想法发生。' },
  communities: { title: '找到属于你的社区。', copy: '公开发现与私密成员管理有不同权限，加入后才能查看成员名册。' },
  creators: { title: '发现正在创作的人。', copy: '关注创作者的真实主页，读他们已发布的内容。' },
  voice: { title: '用声音，开始交流。', copy: '开放语音房由自建 LiveKit 承载，连接状态以实际媒体服务返回为准。' },
  live: { title: '为下一场直播做好准备。', copy: '排期已经支持；实际视频推流、转码与回放尚未接入。' },
};
const heading = computed(() => headings[props.section]);
function focusLatest() {
  document.getElementById('latest-content')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
}
const show = (section: PublicSection) => props.section === 'explore' || props.section === section;
const created = (item: CreatorPost) =>
  item.publishedAt ? new Date(item.publishedAt).toLocaleDateString('zh-CN') : '已发布';
const actionForSection = computed<WorkspaceAction>(() =>
  props.section === 'communities'
    ? 'community'
    : props.section === 'creators'
      ? 'profile'
      : props.section === 'voice'
        ? 'voice'
        : props.section === 'live'
          ? 'live'
          : 'post',
);
const actionLabels: Record<WorkspaceAction, string> = {
  post: '发布内容',
  profile: '创建我的主页',
  community: '创建社区',
  'community-manage': '管理我的社区',
  voice: '创建语音房',
  live: '创建直播排期',
};
</script>
<template>
  <div class="workspace-page-heading"
    ><div
      ><p class="workspace-eyebrow">{{
        section === 'explore' ? 'YOUR CREATIVE SPACE' : 'EXPLORE · ' + section.toUpperCase()
      }}</p
      ><h1>{{ heading.title }}</h1
      ><p>{{ heading.copy }}</p></div
    ><button class="secondary compact-button" :disabled="loading" @click="emit('retry')"
      ><RefreshCw :size="16" :class="{ spin: loading }" />刷新</button
    ></div
  >
  <section v-if="section === 'explore'" class="workspace-hero"
    ><div
      ><span class="hero-kicker">{{ displayName ? `欢迎回来，${displayName}` : '欢迎来到 KOKO Nexus' }}</span
      ><h2>记录想法。建立社区。<br />让交流持续发生。</h2><p>从一篇文章或一个开放的讨论空间开始。</p
      ><div class="page-actions"
        ><button class="primary" @click="emit('action', 'post')"><Plus :size="17" />开始创作</button
        ><RouterLink to="/communities" class="hero-link">探索社区<ArrowRight :size="16" /></RouterLink></div></div
    ><div class="hero-orbit" aria-hidden="true"
      ><div class="orbit-line orbit-one" /><div class="orbit-line orbit-two" /><span class="orbit-node node-content"
        ><FileText :size="26" /></span
      ><span class="orbit-node node-voice"><Headphones :size="24" /></span
      ><span class="orbit-node node-community"><Users :size="26" /></span><span class="orbit-center">K</span></div
    ></section
  >
  <div class="workspace-section-toolbar"
    ><nav v-if="section === 'communities'" class="page-actions" aria-label="社区操作"
      ><button class="secondary" @click="emit('community')"><Users :size="16" />我的已加入社区</button
      ><button class="secondary" @click="emit('action', 'community-manage')">管理我的社区</button></nav
    ><p v-else class="snapshot-time">{{
      data.loadedAt && !error
        ? `最近读取 ${new Date(data.loadedAt).toLocaleTimeString('zh-CN')}`
        : '数据来自真实服务接口'
    }}</p
    ><button class="primary compact-button" @click="emit('action', actionForSection)"
      ><Plus :size="16" />{{ actionLabels[actionForSection] }}</button
    ></div
  >
  <div v-if="loading" class="workspace-skeleton-grid" aria-busy="true" role="status"
    ><div v-for="index in 3" :key="index" class="workspace-skeleton"><span />正在读取内容…</div></div
  >
  <div v-else-if="error" class="workspace-error" role="alert"
    ><h2>暂时无法读取空间</h2><p>{{ error }}</p
    ><button class="secondary" @click="emit('retry')">重新连接</button></div
  >
  <template v-else>
    <div v-if="section === 'explore'" class="workspace-metrics"
      ><article
        ><span class="metric-icon"><BookOpen :size="20" /></span
        ><div
          ><strong>{{ data.postTotal }}</strong
          ><span>已发布文章</span></div
        ><button aria-label="查看最新文章" @click="focusLatest"><ChevronRight :size="16" /></button></article
      ><article
        ><span class="metric-icon"><Users :size="20" /></span
        ><div
          ><strong>{{ data.creatorTotal }}</strong
          ><span>公开创作者</span></div
        ><RouterLink to="/creators" aria-label="查看公开创作者"><ChevronRight :size="16" /></RouterLink></article
      ><article
        ><span class="metric-icon"><Headphones :size="20" /></span
        ><div
          ><strong>{{ data.voiceRooms.length }}</strong
          ><span>语音房 · 当前列表</span></div
        ><RouterLink to="/voice" aria-label="查看语音房"><ChevronRight :size="16" /></RouterLink></article
    ></div>
    <div class="discovery-workspace" :class="{ 'with-context': section === 'explore' }"
      ><div class="discovery-primary">
        <section v-if="section === 'explore'" id="latest-content" class="workspace-section"
          ><div class="workspace-section-heading"
            ><div><p class="workspace-eyebrow">LATEST STORIES</p><h2>最新内容</h2></div
            ><RouterLink to="/studio" class="section-link">内容工作台<ArrowRight :size="15" /></RouterLink></div
          ><div v-if="data.posts.length" class="content-card-grid"
            ><article v-for="post in data.posts" :key="post.id" class="content-card"
              ><button class="content-card-open" @click="emit('readPost', post)"
                ><div class="content-card-cover" :class="{ 'no-cover': !post.coverUrl }"
                  ><img v-if="post.coverUrl" :src="post.coverUrl" :alt="post.title" loading="lazy" /><BookOpen
                    v-else
                    :size="27" /></div
                ><div class="content-card-body"
                  ><small>文章 · {{ created(post) }}</small
                  ><h3>{{ post.title }}</h3
                  ><p>{{ post.excerpt || '作者未填写摘要，点击阅读完整内容。' }}</p
                  ><div class="content-card-meta"
                    ><span>阅读文章<ArrowRight :size="13" /></span
                    ><span
                      ><Heart :size="13" />{{ post.likeCount }}<MessageCircle :size="13" />{{ post.commentCount }}</span
                    ></div
                  ></div
                ></button
              ></article
            ></div
          ><div v-else class="workspace-empty"
            ><BookOpen :size="28" /><h3>还没有公开内容</h3><p>创建草稿并由你确认发布后，内容才会出现在这里。</p
            ><button class="secondary" @click="emit('action', 'post')">创建内容</button></div
          ><button
            v-if="data.posts.length < data.postTotal"
            class="secondary load-more"
            :disabled="pageLoading"
            @click="emit('loadMore', 'posts')"
            >{{ pageLoading ? '读取中…' : '加载更多内容' }}</button
          ></section
        >
        <section v-if="show('communities')" class="workspace-section"
          ><div class="workspace-section-heading"
            ><div><p class="workspace-eyebrow">COMMUNITY SPACES</p><h2>公开社区</h2></div
            ><RouterLink v-if="section === 'explore'" to="/communities" class="section-link"
              >探索社区<ArrowRight :size="15" /></RouterLink
            ><small v-else class="muted">当前返回最多 12 个公开社区</small></div
          ><div v-if="data.communities.length" class="community-card-grid"
            ><article v-for="item in data.communities" :key="item.id" class="space-card"
              ><div class="space-card-heading"
                ><span class="space-badge">{{ item.badge }}</span
                ><span class="status-tag">公开社区</span></div
              ><h3>{{ item.name }}</h3
              ><p>{{ item.description || '这个社区尚未填写介绍。' }}</p
              ><div class="space-card-footer"
                ><span><Users :size="14" />{{ item.members }} 位成员</span
                ><button class="secondary compact-button" @click="emit('community', item.id)"
                  >查看 / 加入<ArrowRight :size="13" /></button></div></article></div
          ><div v-else class="workspace-empty"
            ><Users :size="28" /><h3>这里等待第一个社区</h3><p>创建一个社区，邀请成员加入交流。</p
            ><button class="secondary" @click="emit('action', 'community')">创建社区</button></div
          ></section
        >
        <section v-if="show('creators')" class="workspace-section"
          ><div class="workspace-section-heading"
            ><div><p class="workspace-eyebrow">PEOPLE WHO CREATE</p><h2>创作者</h2></div
            ><RouterLink v-if="section === 'explore'" to="/creators" class="section-link"
              >发现更多<ArrowRight :size="15" /></RouterLink></div
          ><div v-if="data.creators.length" class="creator-workspace-grid"
            ><article v-for="item in data.creators" :key="item.userId" class="creator-workspace-card"
              ><div class="creator-workspace-banner"
                ><img v-if="item.bannerUrl" :src="item.bannerUrl" alt="" loading="lazy" /></div
              ><span class="creator-workspace-avatar"
                ><img v-if="item.avatarUrl" :src="item.avatarUrl" :alt="item.displayName" loading="lazy" /><span
                  v-else
                  >{{ item.displayName.slice(0, 1) }}</span
                ></span
              ><div class="creator-workspace-body"
                ><h3>{{ item.displayName }}</h3
                ><p>{{ item.headline || '创作者尚未填写介绍。' }}</p
                ><small>{{ item.followerCount }} 人关注 · /{{ item.slug }}</small
                ><button
                  v-if="userId !== item.userId"
                  class="secondary compact-button"
                  :disabled="followSubmitting === item.userId"
                  @click="emit('follow', item)"
                  >{{ followedIds.has(item.userId) ? '已关注 · 取消' : '关注创作者' }}</button
                ></div
              ></article
            ></div
          ><div v-else class="workspace-empty"
            ><Users :size="28" /><h3>创作者即将从这里开始</h3><p>发布主页后，访客才能在这里发现你。</p
            ><button class="secondary" @click="emit('action', 'profile')">建立我的主页</button></div
          ><button
            v-if="data.creators.length < data.creatorTotal"
            class="secondary load-more"
            :disabled="pageLoading"
            @click="emit('loadMore', 'creators')"
            >{{ pageLoading ? '读取中…' : '加载更多创作者' }}</button
          ></section
        >
        <section v-if="show('voice')" class="workspace-section"
          ><div class="workspace-section-heading"
            ><div><p class="workspace-eyebrow">VOICE ROOMS</p><h2>开放语音房</h2></div
            ><RouterLink v-if="section === 'explore'" to="/voice" class="section-link"
              >进入语音空间<ArrowRight :size="15" /></RouterLink></div
          ><div v-if="data.voiceRooms.length" class="voice-workspace-list"
            ><article v-for="room in data.voiceRooms" :key="room.id" class="voice-workspace-card"
              ><span class="voice-room-mark"><Headphones :size="21" /></span
              ><div
                ><h3>{{ room.title }}</h3
                ><p>{{ room.topic || '自由交流' }}</p
                ><small>{{ room.owner }} · 上限 {{ room.maxParticipants }} 人</small></div
              ><button
                class="secondary compact-button"
                :disabled="voiceJoining === room.id"
                @click="emit('joinVoice', room)"
                >{{ voiceJoining === room.id ? '连接中…' : '加入讨论' }}</button
              ></article
            ></div
          ><div v-else class="workspace-empty"
            ><Headphones :size="28" /><h3>当前没有开放语音房</h3><p>建立开放讨论空间，连接成功后即可加入语音交流。</p
            ><button class="secondary" @click="emit('action', 'voice')">创建语音房</button></div
          ><p v-if="voiceError" class="form-error" role="alert">{{ voiceError }}</p></section
        >
        <section v-if="show('live')" class="workspace-section"
          ><div class="workspace-section-heading"
            ><div><p class="workspace-eyebrow">LIVE SPACES</p><h2>正在直播</h2></div
            ><RouterLink v-if="section === 'explore'" to="/live" class="section-link"
              >直播空间<ArrowRight :size="15" /></RouterLink></div
          ><div v-if="data.liveRooms.length" class="community-card-grid"
            ><article v-for="room in data.liveRooms" :key="room.id" class="space-card"
              ><span class="status-tag positive"><Radio :size="14" />服务已确认开播</span><h3>{{ room.title }}</h3
              ><p>{{ room.creator }} · {{ room.category }}</p
              ><small>{{ room.viewers }} 人观看</small
              ><p class="workspace-note">当前客户端未接入视频播放器，不提供伪造播放地址。</p></article
            ></div
          ><div v-else class="workspace-empty"
            ><Video :size="28" /><h3>当前没有已确认的直播</h3
            ><p>可以先创建排期；正式推流、转码与回放仍待媒体供应商接入。</p
            ><button class="secondary" @click="emit('action', 'live')">建立直播排期</button></div
          ></section
        >
        <p v-if="pageError" class="workspace-error compact" role="alert">{{ pageError }}</p> </div
      ><aside v-if="section === 'explore'" class="discovery-context"
        ><section class="workspace-card quick-start"
          ><p class="workspace-eyebrow">MAKE SOMETHING</p><h2>下一步，从这里开始</h2
          ><button @click="emit('action', 'post')"
            ><span class="quick-icon"><FileText :size="18" /></span
            ><span><strong>写一篇新内容</strong><small>先保存草稿，再确认发布</small></span
            ><ChevronRight :size="16" /></button
          ><button @click="emit('action', 'community')"
            ><span class="quick-icon"><Users :size="18" /></span
            ><span><strong>建立你的社区</strong><small>真实成员与所有者管理</small></span
            ><ChevronRight :size="16" /></button
          ><button @click="emit('action', 'voice')"
            ><span class="quick-icon"><Headphones :size="18" /></span
            ><span><strong>开始语音讨论</strong><small>需要媒体连接实际就绪</small></span
            ><ChevronRight :size="16" /></button></section
        ><section class="workspace-card connection-guide"
          ><span class="status-tag">能力边界</span><h2>连接正在逐步完善</h2
          ><p>Discord OAuth、视频推流和回放尚未开放。当前页面不会展示假连接或默认直播。</p
          ><RouterLink to="/account" class="section-link"
            >账户与连接<ArrowRight :size="14" /></RouterLink></section></aside
    ></div>
  </template>
</template>
