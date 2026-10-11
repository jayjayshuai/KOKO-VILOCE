<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { RouterView, useRoute, useRouter } from 'vue-router';
import {
  Archive,
  BadgeCheck,
  Bell,
  BookOpen,
  Bookmark,
  Compass,
  FileText,
  Headphones,
  Heart,
  LogOut,
  MessageCircle,
  Mic,
  MicOff,
  Pencil,
  Plus,
  Radio,
  RefreshCw,
  Settings,
  Trash2,
  UserRound,
  Users,
  Video,
} from 'lucide-vue-next';
import {
  ApiRequestError,
  api,
  managedImageUrl,
  type Community,
  type CreatorPost,
  type CreatorProfile,
  type LiveRoom,
  type ManagedImage,
  type NotificationItem,
  type NotificationType,
  type PostComment,
  type PostEngagement,
  type PostFavoriteState,
  type VoiceRoom,
} from './api';
import { useAuthStore } from './stores/auth';
import ManagedImagePicker from './components/ManagedImagePicker.vue';
import WorkspaceShell from './components/WorkspaceShell.vue';
import CommunityMembershipPanel from './components/CommunityMembershipPanel.vue';
import { unknownPage, workspacePages } from './router/pages';
import type { DiscoveryDomain, DiscoveryDomainState, DiscoverySnapshot, WorkspaceAction } from './types/workspace';
import { useDialogAccessibility } from './composables/dialog-accessibility';
import { useVoiceConnection } from './composables/voice-connection';
import VoiceInteractionPanel from './components/VoiceInteractionPanel.vue';
import VoicePlaybackControls from './components/VoicePlaybackControls.vue';
import PersistentVoiceCallBar from './components/PersistentVoiceCallBar.vue';
import CreatorSaveStatus from './components/CreatorSaveStatus.vue';
import { useCreatorEditorWrites } from './composables/creator-editor-writes';
import { useUnsavedEditorGuard } from './composables/unsaved-editor-guard';
import { useCreatorContentActions } from './composables/creator-content-actions';

const auth = useAuthStore();
const route = useRoute(),
  router = useRouter();
const workspacePage = computed(() => workspacePages.find((page) => page.name === route.name) ?? unknownPage);
/** 文本连接不能复用同账号另一登录轮次；其余工作台仍保留原有命令恢复生命周期。 */
const workspaceViewKey = computed(() => {
  const identity = `${String(route.name)}:${auth.user?.id ?? 'anonymous'}`;
  return ['messages', 'voice-room'].includes(String(route.name)) ? `${identity}:${auth.sessionRevision}` : identity;
});
const loggingOut = ref(false),
  workspaceError = ref('');
const loadedAt = ref<string | null>(null);
const studioProfile = ref<CreatorProfile | null>(null),
  studioLoading = ref(false),
  studioError = ref('');
let disposed = false,
  discoveryRevision = 0,
  studioRevision = 0;
let studioRead: AbortController | undefined;
/** 分区保留各自取消器与轮次；单独重试语音不能废弃已成功的内容。 */
const discoveryReads: Partial<Record<DiscoveryDomain, AbortController>> = {};
const discoveryDomains: DiscoveryDomain[] = ['communities', 'live', 'voice', 'creators', 'posts'];
const discoveryStates = reactive<Record<DiscoveryDomain, DiscoveryDomainState>>({
  communities: { status: 'idle', error: '', loadedAt: null },
  live: { status: 'idle', error: '', loadedAt: null },
  voice: { status: 'idle', error: '', loadedAt: null },
  creators: { status: 'idle', error: '', loadedAt: null },
  posts: { status: 'idle', error: '', loadedAt: null },
});
let dialogReadRevision = 0;
/** 同一弹窗/会话轮次内的读取才可更新私有表单与集合。 */
const dialogContext = () => ({ revision: ++dialogReadRevision, session: auth.sessionRevision, userId: auth.user?.id });
const currentDialog = (context: ReturnType<typeof dialogContext>) =>
  !disposed &&
  context.revision === dialogReadRevision &&
  context.session === auth.sessionRevision &&
  context.userId === auth.user?.id;
const communityHubOpen = ref(false),
  communityHubId = ref('');
function openCommunityHub(id = '') {
  if (!auth.user) {
    dialog.value = 'auth';
    formError.value = '请先登录，再查看或加入社区。';
    return;
  }
  communityHubId.value = id;
  communityHubOpen.value = true;
}
const communities = ref<Community[]>([]);
const ownedCommunities = ref<Community[]>([]);
const liveRooms = ref<LiveRoom[]>([]);
const voiceRooms = ref<VoiceRoom[]>([]);
/** 本人建房确认后刷新房主工作台，不以提交按钮点击当作新房间。 */
const voiceOwnerRefreshRevision = ref(0);
/** 受控房间仅打开SQL互动，禁止复用原媒体凭据。 */
const selectedInteractionRoom = ref<VoiceRoom | null>(null),
  voiceInteractionBusy = ref(false);
/** 语音建房请求与共享表单分离轮次，换会话时旧结果不覆盖新页面。 */
let voiceCreateRevision = 0,
  voiceCreatePending = false;
const creators = ref<CreatorProfile[]>([]);
const posts = ref<CreatorPost[]>([]);
const ownedPosts = ref<CreatorPost[]>([]);
const selectedPost = ref<CreatorPost | null>(null);
const postComments = ref<PostComment[]>([]);
const commentPage = ref(1);
const commentTotal = ref(0);
const commentLoading = ref(false);
const postEngagement = ref<PostEngagement>({ likeCount: 0, commentCount: 0, likedByMe: false });
const postFavorite = ref<PostFavoriteState>({ favoriteCount: 0, favoritedByMe: false });
const favoritePosts = ref<CreatorPost[]>([]);
const followedCreators = ref<CreatorProfile[]>([]);
const followedIds = ref<Set<string>>(new Set());
const creatorPage = ref(1);
const creatorTotal = ref(0);
const postPage = ref(1);
const postTotal = ref(0);
const collectionPage = ref(1);
const collectionTotal = ref(0);
const collectionLoading = ref(false);
const collectionError = ref('');
const pageLoading = ref(false);
const pageError = ref('');
const followSubmitting = ref<string | null>(null);
const commentBody = ref('');
const interactionSubmitting = ref(false);
/** 文章详情/身份切换独立轮次，不因评论翻页废弃在途写入。 */
const postReaderRevision = ref(0),
  /** 本人点赞/收藏已实际核验，未核验不能基于默认false反向切换。 */
  readerRelationsReady = ref(false),
  readerRelationsLoading = ref(false),
  readerRelationsError = ref(''),
  /** 评论POST回复丢失不能用同文字的列表记录充当提交收据。 */
  commentWriteUnconfirmed = ref(false),
  readerCommentsRefreshing = ref(false);
/** 本人关系读取可取消且有独立轮次，旧finally不能解锁新读取。 */
let readerRelationRead = new AbortController(),
  readerRelationReadRevision = 0;
const loading = computed(() => discoveryDomains.some((domain) => discoveryStates[domain].status === 'loading'));
const dialog = ref<
  | 'auth'
  | 'community'
  | 'live'
  | 'voice'
  | 'voice-interaction'
  | 'manage'
  | 'creator'
  | 'postManage'
  | 'post'
  | 'postReader'
  | 'favorites'
  | 'following'
  | 'notifications'
  | null
>(null);
const authMode = ref<'login' | 'register'>('login');
const submitting = ref(false);
const imageUploading = ref(false);
const formError = ref('');
const credentials = reactive({ email: '', password: '', handle: '', displayName: '' });
const community = reactive({ slug: '', name: '', description: '', badge: '' });
const communityEdit = reactive<{
  id: string;
  name: string;
  description: string;
  badge: string;
  visibility: 'PUBLIC' | 'PRIVATE';
  version: number;
}>({
  id: '',
  name: '',
  description: '',
  badge: '',
  visibility: 'PUBLIC',
  version: 0,
});
const live = reactive({ slug: '', title: '', category: '', interactive: true });
const voice = reactive({ slug: '', title: '', topic: '', maxParticipants: 30, controlled: true });
const creatorProfile = reactive<CreatorProfile>({
  userId: '',
  slug: '',
  displayName: '',
  headline: '',
  bio: '',
  avatarUrl: '',
  bannerUrl: '',
  avatarAssetId: '',
  bannerAssetId: '',
  status: 'DRAFT',
  version: 0,
  followerCount: 0,
});
const postEdit = reactive({
  id: '',
  slug: '',
  title: '',
  excerpt: '',
  body: '',
  coverUrl: '',
  coverAssetId: '',
  status: 'DRAFT' as 'DRAFT' | 'PUBLISHED',
  version: 0,
});
/** 同类型编辑器切换也独立于旧保存/读取轮次。 */
const editorFormRevision = ref(0),
  /** 主页首次读取未完成时不能把默认版本零当成已确认新建。 */
  creatorProfileLoading = ref(false),
  creatorProfileLoaded = ref(false);
const creatorEditor = useCreatorEditorWrites(
  () => ({
    userId: auth.user?.id,
    sessionRevision: auth.sessionRevision,
    kind: dialog.value,
    formRevision: editorFormRevision.value,
  }),
  {
    postConfirmed: applyConfirmedPost,
    profileConfirmed: applyConfirmedProfile,
    busyChanged: (value) => {
      submitting.value = value;
    },
  },
);
const { recovery: editorRecovery, success: editorSuccess, error: editorError } = creatorEditor;
/** 编辑器未保存保护只保留内存基线，不持久化正文或令牌。 */
const editorGuard = useUnsavedEditorGuard(
  () => ({
    userId: auth.user?.id,
    sessionRevision: auth.sessionRevision,
    kind: dialog.value,
    formRevision: editorFormRevision.value,
  }),
  (): Record<string, string> =>
    dialog.value === 'post'
      ? {
          slug: postEdit.slug,
          title: postEdit.title,
          excerpt: postEdit.excerpt,
          body: postEdit.body,
          coverUrl: postEdit.coverUrl,
          coverAssetId: postEdit.coverAssetId,
        }
      : {
          slug: creatorProfile.slug,
          displayName: creatorProfile.displayName,
          headline: creatorProfile.headline,
          bio: creatorProfile.bio,
          avatarUrl: creatorProfile.avatarUrl ?? '',
          bannerUrl: creatorProfile.bannerUrl ?? '',
          avatarAssetId: creatorProfile.avatarAssetId ?? '',
          bannerAssetId: creatorProfile.bannerAssetId ?? '',
        },
  () => dialog.value === 'post' || creatorProfileLoaded.value,
  () => submitting.value || imageUploading.value,
  () => !!editorRecovery.value,
  router,
  () => {
    dialog.value = null;
  },
);
const { dirty: editorDirty } = editorGuard;
/** 归档独立于编辑器保存；仅应用当前身份确认的列表变化。 */
const contentActions = useCreatorContentActions(
  () => ({ userId: auth.user?.id, sessionRevision: auth.sessionRevision }),
  (id) => {
    ownedPosts.value = ownedPosts.value.filter((item) => item.id !== id);
    posts.value = posts.value.filter((item) => item.id !== id);
  },
);
const { busy: archiveBusy, error: archiveError, success: archiveSuccess, pending: archivePending } = contentActions;
const remoteAudioRoot = ref<HTMLElement | null>(null);
const voiceState = useVoiceConnection(auth, api.joinVoiceRoom, remoteAudioRoot);
const {
  connectedVoice,
  voiceJoining,
  voiceError,
  microphoneEnabled,
  microphoneBusy,
  audioPlaybackBlocked,
  audioPlaybackBusy,
  audioPlaybackError,
  participantCount,
  phase: voicePhase,
  toggleMicrophone,
  resumeVoiceAudio,
  leaveVoiceRoom,
} = voiceState;
const initials = computed(() => auth.user?.displayName.slice(0, 1).toUpperCase() || '访');

async function loadDiscovery(domain?: DiscoveryDomain) {
  ++discoveryRevision;
  pageLoading.value = false;
  pageError.value = '';
  await Promise.all((domain ? [domain] : discoveryDomains).map(loadDiscoveryDomain));
}

/** 每个请求独立发布结果；慢服务不阻塞其他卡片，旧请求不能覆盖新的成功或错误。 */
async function loadDiscoveryDomain(domain: DiscoveryDomain) {
  discoveryReads[domain]?.abort();
  const controller = new AbortController();
  discoveryReads[domain] = controller;
  const current = () => !disposed && discoveryReads[domain] === controller;
  discoveryStates[domain].status = 'loading';
  discoveryStates[domain].error = '';
  try {
    if (domain === 'communities') {
      const result = await api.communities(controller.signal);
      if (!current()) return;
      communities.value = result;
    } else if (domain === 'live') {
      const result = await api.liveRooms(controller.signal);
      if (!current()) return;
      liveRooms.value = result;
    } else if (domain === 'voice') {
      const result = await api.voiceRooms(controller.signal);
      if (!current()) return;
      voiceRooms.value = result;
    } else if (domain === 'creators') {
      const result = await api.creatorPage(1, 12, controller.signal);
      if (!current()) return;
      creators.value = result.items;
      creatorPage.value = result.page;
      creatorTotal.value = result.total;
      if (auth.user) void syncFollowStates(result.items);
    } else {
      const result = await api.postPage(1, 12, controller.signal);
      if (!current()) return;
      posts.value = result.items;
      postPage.value = result.page;
      postTotal.value = result.total;
    }
    const completedAt = new Date().toISOString();
    discoveryStates[domain] = { status: 'ready', error: '', loadedAt: completedAt };
    loadedAt.value = completedAt;
  } catch (cause) {
    if (!current()) return;
    discoveryStates[domain].status = 'error';
    discoveryStates[domain].error = cause instanceof Error ? cause.message : '服务暂时不可用';
  }
}

async function syncFollowStates(items: CreatorProfile[]) {
  const userId = auth.user?.id,
    revision = auth.sessionRevision;
  if (!userId) return;
  const states = await Promise.allSettled(
    items.filter((item) => item.userId !== auth.user?.id).map((item) => api.followState(item.userId)),
  );
  if (disposed || auth.user?.id !== userId || auth.sessionRevision !== revision) return;
  const ids = new Set(followedIds.value);
  for (const state of states) {
    if (state.status !== 'fulfilled') continue;
    if (state.value.followedByMe) ids.add(state.value.creatorId);
    else ids.delete(state.value.creatorId);
    const item = items.find((candidate) => candidate.userId === state.value.creatorId);
    if (item) item.followerCount = state.value.followerCount;
  }
  followedIds.value = ids;
}

async function loadMore(kind: 'creators' | 'posts') {
  if (disposed || discoveryStates[kind].status !== 'ready' || pageLoading.value) return;
  const revision = discoveryRevision,
    signal = discoveryReads[kind]?.signal;
  pageLoading.value = true;
  pageError.value = '';
  try {
    if (kind === 'creators') {
      const result = await api.creatorPage(creatorPage.value + 1, 12, signal);
      if (disposed || revision !== discoveryRevision) return;
      creators.value.push(
        ...result.items.filter((item) => !creators.value.some((existing) => existing.userId === item.userId)),
      );
      creatorPage.value = result.page;
      creatorTotal.value = result.total;
      await syncFollowStates(result.items);
    } else {
      const result = await api.postPage(postPage.value + 1, 12, signal);
      if (disposed || revision !== discoveryRevision) return;
      posts.value.push(...result.items.filter((item) => !posts.value.some((existing) => existing.id === item.id)));
      postPage.value = result.page;
      postTotal.value = result.total;
    }
  } catch (cause) {
    if (!disposed && revision === discoveryRevision)
      pageError.value = cause instanceof Error ? cause.message : '加载下一页失败';
  } finally {
    if (!disposed && revision === discoveryRevision) pageLoading.value = false;
  }
}

async function openCollection(kind: 'favorites' | 'following') {
  if (!auth.user) {
    dialog.value = 'auth';
    formError.value = '请先登录。';
    return;
  }
  dialog.value = kind;
  collectionError.value = '';
  collectionLoading.value = true;
  favoritePosts.value = [];
  followedCreators.value = [];
  collectionPage.value = 1;
  collectionTotal.value = 0;
  const context = dialogContext();
  try {
    if (kind === 'favorites') {
      const result = await api.favorites(1);
      if (!currentDialog(context)) return;
      favoritePosts.value = result.items;
      collectionTotal.value = result.total;
    } else {
      const result = await api.following(1);
      if (!currentDialog(context)) return;
      followedCreators.value = result.items;
      collectionTotal.value = result.total;
      followedIds.value = new Set(result.items.map((item) => item.userId));
    }
  } catch (cause) {
    if (currentDialog(context)) collectionError.value = cause instanceof Error ? cause.message : '列表加载失败';
  } finally {
    if (currentDialog(context)) collectionLoading.value = false;
  }
}

async function loadMoreCollection() {
  const kind = dialog.value;
  if ((kind !== 'favorites' && kind !== 'following') || collectionLoading.value) return;
  const context = dialogContext();
  collectionLoading.value = true;
  collectionError.value = '';
  try {
    const next = collectionPage.value + 1;
    if (kind === 'favorites') {
      const result = await api.favorites(next);
      if (!currentDialog(context)) return;
      favoritePosts.value.push(
        ...result.items.filter((item) => !favoritePosts.value.some((existing) => existing.id === item.id)),
      );
      collectionTotal.value = result.total;
    } else {
      const result = await api.following(next);
      if (!currentDialog(context)) return;
      followedCreators.value.push(
        ...result.items.filter((item) => !followedCreators.value.some((existing) => existing.userId === item.userId)),
      );
      collectionTotal.value = result.total;
      followedIds.value = new Set([...followedIds.value, ...result.items.map((item) => item.userId)]);
    }
    collectionPage.value = next;
  } catch (cause) {
    if (currentDialog(context)) collectionError.value = cause instanceof Error ? cause.message : '加载下一页失败';
  } finally {
    if (currentDialog(context)) collectionLoading.value = false;
  }
}

async function toggleFollow(item: CreatorProfile) {
  if (!auth.user) {
    dialog.value = 'auth';
    formError.value = '请先登录，再关注创作者。';
    return;
  }
  if (auth.user.id === item.userId) return;
  if (followSubmitting.value) return;
  const userId = auth.user.id,
    revision = auth.sessionRevision;
  followSubmitting.value = item.userId;
  collectionError.value = '';
  pageError.value = '';
  try {
    const followed = followedIds.value.has(item.userId);
    const result = followed ? await api.unfollowCreator(item.userId) : await api.followCreator(item.userId);
    if (disposed || auth.user?.id !== userId || auth.sessionRevision !== revision) return;
    const ids = new Set(followedIds.value);
    if (result.followedByMe) ids.add(item.userId);
    else ids.delete(item.userId);
    followedIds.value = ids;
    item.followerCount = result.followerCount;
    const listed = creators.value.find((creator) => creator.userId === item.userId);
    if (listed) listed.followerCount = result.followerCount;
    if (!result.followedByMe) {
      followedCreators.value = followedCreators.value.filter((creator) => creator.userId !== item.userId);
      collectionTotal.value = Math.max(collectionTotal.value - 1, 0);
    }
  } catch (cause) {
    collectionError.value = pageError.value = cause instanceof Error ? cause.message : '关注操作失败';
  } finally {
    followSubmitting.value = null;
  }
}

async function loadOwnedCommunities() {
  if (!auth.user) {
    ownedCommunities.value = [];
    return;
  }
  const userId = auth.user.id,
    revision = auth.sessionRevision;
  const result = await api.myCommunities();
  if (!disposed && auth.user?.id === userId && auth.sessionRevision === revision) ownedCommunities.value = result;
}

async function openCommunityManager() {
  if (!auth.user) {
    showAuth();
    formError.value = '请先登录，再管理社区。';
    return;
  }
  formError.value = '';
  communityEdit.id = '';
  dialog.value = 'manage';
  try {
    await loadOwnedCommunities();
  } catch (cause) {
    formError.value = cause instanceof Error ? cause.message : '无法加载社区';
  }
}

function editCommunity(item: Community) {
  Object.assign(communityEdit, {
    id: item.id,
    name: item.name,
    description: item.description || '',
    badge: item.badge,
    visibility: item.visibility,
    version: item.version,
  });
  formError.value = '';
}

function requireLogin(next: 'community' | 'live' | 'voice') {
  if (next === 'voice') voice.controlled = true;
  dialog.value = auth.user ? next : 'auth';
  formError.value = auth.user ? '' : '请先登录，再使用创作者功能。';
}

function newPostDraft() {
  if (submitting.value || imageUploading.value || !auth.user) return;
  if (!editorGuard.requestLeave()) return;
  editorFormRevision.value++;
  dialogReadRevision++;
  Object.assign(postEdit, {
    id: '',
    slug: '',
    title: '',
    excerpt: '',
    body: '',
    coverUrl: '',
    coverAssetId: '',
    status: 'DRAFT',
    version: 0,
  });
  formError.value = '';
  dialog.value = 'post';
  editorGuard.markConfirmed();
}

function editPost(item: CreatorPost) {
  if (submitting.value || imageUploading.value || archiveBusy.value || !auth.user) return;
  if (archivePending.value?.id === item.id) {
    workspaceError.value = '这篇内容的归档结果未确认，请先在工作台核实。';
    return;
  }
  if (!editorGuard.requestLeave()) return;
  if (
    item.ownerId !== auth.user.id ||
    typeof item.body !== 'string' ||
    !['DRAFT', 'PUBLISHED'].includes(item.status || '')
  ) {
    formError.value = '请从本人工作台读取可编辑的完整内容。';
    return;
  }
  editorFormRevision.value++;
  dialogReadRevision++;
  Object.assign(postEdit, {
    id: item.id,
    slug: item.slug,
    title: item.title,
    excerpt: item.excerpt || '',
    body: item.body || '',
    coverUrl: item.coverUrl || '',
    coverAssetId: item.coverAssetId || '',
    status: item.status || 'DRAFT',
    version: item.version || 0,
  });
  formError.value = '';
  dialog.value = 'post';
  editorGuard.markConfirmed();
}

async function openPostStudio() {
  formError.value = '';
  if (!auth.user) {
    dialog.value = 'auth';
    formError.value = '请先登录，再发布内容。';
    return;
  }
  dialog.value = 'postManage';
  const context = dialogContext();
  try {
    const result = await api.myPosts();
    if (currentDialog(context)) ownedPosts.value = result;
  } catch (cause) {
    if (currentDialog(context)) formError.value = cause instanceof Error ? cause.message : '无法加载内容';
  }
}

async function savePost(publish: boolean) {
  if (imageUploading.value || submitting.value) return;
  formError.value = '';
  if (await creatorEditor.savePost(postEdit, publish)) {
    if (postEdit.status === 'PUBLISHED') void loadDiscovery();
  }
}

/** 每个已确认步骤立即保存ID/版本；下一步发布失败也不回退为未创建表单。 */
function applyConfirmedPost(saved: CreatorPost) {
  Object.assign(postEdit, saved);
  editorGuard.markConfirmed();
  const ownedIndex = ownedPosts.value.findIndex((item) => item.id === saved.id);
  if (ownedIndex >= 0) ownedPosts.value[ownedIndex] = saved;
  else ownedPosts.value.unshift(saved);
  const publicIndex = posts.value.findIndex((item) => item.id === saved.id);
  if (saved.status === 'PUBLISHED') {
    if (publicIndex >= 0) posts.value[publicIndex] = saved;
    else posts.value.unshift(saved);
  } else if (publicIndex >= 0) posts.value.splice(publicIndex, 1);
}

async function archivePost(item: CreatorPost) {
  if (submitting.value || imageUploading.value || archiveBusy.value || archivePending.value) return;
  if (!window.confirm(`确认归档“${item.title}”？归档后将立即停止公开展示。`)) return;
  await contentActions.archive(item);
  if (archiveSuccess.value) void loadDiscovery();
}

async function readPost(item: CreatorPost) {
  if (!editorGuard.requestLeave()) return;
  resetPostReaderOperations();
  formError.value = '';
  selectedPost.value = null;
  postComments.value = [];
  commentBody.value = '';
  dialog.value = 'postReader';
  const context = dialogContext();
  try {
    const [detail, comments] = await Promise.all([api.post(item.slug), api.postCommentsPage(item.slug, 1)]);
    if (!currentDialog(context)) return;
    selectedPost.value = detail;
    postEngagement.value = { likeCount: detail.likeCount, commentCount: detail.commentCount, likedByMe: false };
    postFavorite.value = { favoriteCount: detail.favoriteCount, favoritedByMe: false };
    postComments.value = comments.items;
    commentPage.value = comments.page;
    commentTotal.value = comments.total;
    await refreshPostRelations();
  } catch (cause) {
    if (currentDialog(context)) formError.value = cause instanceof Error ? cause.message : '内容加载失败';
  }
}

/** 公开正文可以保留，旧账号互动关系、输入、在途标记不能跨身份保留。 */
function resetPostReaderOperations() {
  postReaderRevision.value++;
  readerRelationReadRevision++;
  readerRelationRead.abort();
  readerRelationsReady.value = !auth.user;
  readerRelationsLoading.value = false;
  readerRelationsError.value = '';
  readerCommentsRefreshing.value = false;
  commentLoading.value = false;
  commentWriteUnconfirmed.value = false;
  commentBody.value = '';
  interactionSubmitting.value = false;
  postEngagement.value = {
    likeCount: selectedPost.value?.likeCount ?? 0,
    commentCount: selectedPost.value?.commentCount ?? 0,
    likedByMe: false,
  };
  postFavorite.value = { favoriteCount: selectedPost.value?.favoriteCount ?? 0, favoritedByMe: false };
}

/** 固定请求的文章/账号/登录轮次，即使新页仍是同一文章也不认可旧账号回复。 */
function readerContext() {
  const revision = postReaderRevision.value,
    userId = auth.user?.id,
    sessionRevision = auth.sessionRevision,
    postId = selectedPost.value?.id;
  return {
    postId,
    userId,
    current: () =>
      !disposed &&
      dialog.value === 'postReader' &&
      postReaderRevision.value === revision &&
      auth.user?.id === userId &&
      auth.sessionRevision === sessionRevision &&
      selectedPost.value?.id === postId,
  };
}
const validReaderCount = (value: unknown) => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;

/** 读取双方事实后一起应用，失败保留公开计数但不假装本人尚未点赞/收藏。 */
async function refreshPostRelations() {
  const context = readerContext();
  if (!context.postId || !context.current() || interactionSubmitting.value) return;
  if (!auth.user) {
    readerRelationsReady.value = true;
    return;
  }
  readerRelationRead.abort();
  readerRelationRead = new AbortController();
  const revision = ++readerRelationReadRevision;
  readerRelationsReady.value = false;
  readerRelationsLoading.value = true;
  readerRelationsError.value = '';
  try {
    const [engagement, favorite] = await Promise.all([
      api.postEngagement(context.postId, readerRelationRead.signal),
      api.postFavoriteState(context.postId, readerRelationRead.signal),
    ]);
    if (!context.current() || revision !== readerRelationReadRevision) return;
    if (
      !engagement ||
      !favorite ||
      typeof engagement.likedByMe !== 'boolean' ||
      typeof favorite.favoritedByMe !== 'boolean' ||
      !validReaderCount(engagement.likeCount) ||
      !validReaderCount(engagement.commentCount) ||
      !validReaderCount(favorite.favoriteCount)
    )
      throw new Error('互动状态响应未确认。');
    postEngagement.value = engagement;
    postFavorite.value = favorite;
    Object.assign(selectedPost.value!, {
      likeCount: engagement.likeCount,
      commentCount: engagement.commentCount,
      favoriteCount: favorite.favoriteCount,
    });
    readerRelationsReady.value = true;
  } catch (cause) {
    if (context.current() && revision === readerRelationReadRevision)
      readerRelationsError.value = cause instanceof Error ? cause.message : '互动状态读取失败';
  } finally {
    if (context.current() && revision === readerRelationReadRevision) readerRelationsLoading.value = false;
  }
}

/** 评论未知结果只刷新可见列表，不清除未知标记或重发原POST。 */
async function refreshReaderComments() {
  const context = readerContext(),
    slug = selectedPost.value?.slug;
  if (
    !context.current() ||
    !slug ||
    interactionSubmitting.value ||
    commentLoading.value ||
    readerCommentsRefreshing.value
  )
    return;
  readerCommentsRefreshing.value = true;
  try {
    const result = await api.postCommentsPage(slug, 1);
    if (!context.current()) return;
    if (
      !result ||
      result.page !== 1 ||
      !validReaderCount(result.total) ||
      !Array.isArray(result.items) ||
      result.items.length > 20 ||
      result.items.some((value) => value.postId !== context.postId || typeof value.body !== 'string')
    )
      throw new Error('评论分页响应未确认。');
    postComments.value = result.items;
    commentPage.value = result.page;
    commentTotal.value = result.total;
    postEngagement.value.commentCount = result.total;
    selectedPost.value!.commentCount = result.total;
  } catch (cause) {
    if (context.current()) formError.value = cause instanceof Error ? cause.message : '评论读取失败';
  } finally {
    if (context.current()) readerCommentsRefreshing.value = false;
  }
}

async function loadMoreComments() {
  if (!selectedPost.value || commentLoading.value || interactionSubmitting.value || readerCommentsRefreshing.value)
    return;
  const context = dialogContext();
  commentLoading.value = true;
  formError.value = '';
  try {
    const result = await api.postCommentsPage(selectedPost.value.slug, commentPage.value + 1);
    if (!currentDialog(context)) return;
    postComments.value.push(
      ...result.items.filter((item) => !postComments.value.some((existing) => existing.id === item.id)),
    );
    commentPage.value = result.page;
    commentTotal.value = result.total;
  } catch (cause) {
    if (currentDialog(context)) formError.value = cause instanceof Error ? cause.message : '评论加载失败';
  } finally {
    if (currentDialog(context)) commentLoading.value = false;
  }
}

async function togglePostFavorite() {
  if (
    !selectedPost.value ||
    dialog.value !== 'postReader' ||
    interactionSubmitting.value ||
    commentLoading.value ||
    readerCommentsRefreshing.value
  )
    return;
  if (!auth.user) {
    dialog.value = 'auth';
    formError.value = '请先登录，再收藏内容。';
    return;
  }
  if (!readerRelationsReady.value || readerRelationsLoading.value) return;
  const context = readerContext();
  interactionSubmitting.value = true;
  formError.value = '';
  try {
    const result = postFavorite.value.favoritedByMe
      ? await api.unfavoritePost(context.postId!)
      : await api.favoritePost(context.postId!);
    if (!context.current()) return;
    if (!result || typeof result.favoritedByMe !== 'boolean' || !validReaderCount(result.favoriteCount))
      throw new Error('收藏状态响应未确认。');
    postFavorite.value = result;
    selectedPost.value.favoriteCount = result.favoriteCount;
    const listed = posts.value.find((item) => item.id === selectedPost.value?.id);
    if (listed) listed.favoriteCount = result.favoriteCount;
    if (!result.favoritedByMe) {
      favoritePosts.value = favoritePosts.value.filter((item) => item.id !== selectedPost.value?.id);
      collectionTotal.value = Math.max(collectionTotal.value - 1, 0);
    }
  } catch (cause) {
    if (context.current()) {
      readerRelationsReady.value = false;
      readerRelationsError.value = `收藏结果未确认，请先核验互动状态。${cause instanceof Error ? cause.message : '收藏操作失败'}`;
    }
  } finally {
    if (context.current()) interactionSubmitting.value = false;
  }
}

async function togglePostLike() {
  if (
    !selectedPost.value ||
    dialog.value !== 'postReader' ||
    interactionSubmitting.value ||
    commentLoading.value ||
    readerCommentsRefreshing.value
  )
    return;
  if (!auth.user) {
    dialog.value = 'auth';
    formError.value = '请先登录，再点赞内容。';
    return;
  }
  if (!readerRelationsReady.value || readerRelationsLoading.value) return;
  const context = readerContext();
  interactionSubmitting.value = true;
  formError.value = '';
  try {
    const result = postEngagement.value.likedByMe
      ? await api.unlikePost(context.postId!)
      : await api.likePost(context.postId!);
    if (!context.current()) return;
    if (
      !result ||
      typeof result.likedByMe !== 'boolean' ||
      !validReaderCount(result.likeCount) ||
      !validReaderCount(result.commentCount)
    )
      throw new Error('点赞状态响应未确认。');
    postEngagement.value = result;
    selectedPost.value.likeCount = postEngagement.value.likeCount;
    const index = posts.value.findIndex((item) => item.id === selectedPost.value?.id);
    if (index >= 0) posts.value[index].likeCount = postEngagement.value.likeCount;
  } catch (cause) {
    if (context.current()) {
      readerRelationsReady.value = false;
      readerRelationsError.value = `点赞结果未确认，请先核验互动状态。${cause instanceof Error ? cause.message : '点赞操作失败'}`;
    }
  } finally {
    if (context.current()) interactionSubmitting.value = false;
  }
}

async function submitComment() {
  if (
    !selectedPost.value ||
    dialog.value !== 'postReader' ||
    !commentBody.value.trim() ||
    interactionSubmitting.value ||
    commentLoading.value ||
    readerCommentsRefreshing.value ||
    commentWriteUnconfirmed.value
  )
    return;
  if (!auth.user) {
    dialog.value = 'auth';
    formError.value = '请先登录，再发表评论。';
    return;
  }
  const context = readerContext(),
    body = commentBody.value.trim();
  interactionSubmitting.value = true;
  formError.value = '';
  try {
    const result = await api.commentPost(context.postId!, body);
    if (!context.current()) return;
    if (
      !result ||
      result.postId !== context.postId ||
      result.userId !== context.userId ||
      typeof result.id !== 'string' ||
      !/^[1-9][0-9]{0,18}$/.test(result.id) ||
      BigInt(result.id) > 9223372036854775807n ||
      result.body !== body ||
      !validReaderCount(result.version)
    )
      throw new Error('评论回复的归属或正文未确认。');
    postComments.value.unshift(result);
    commentTotal.value += 1;
    if (commentBody.value.trim() === body) commentBody.value = '';
    postEngagement.value.commentCount += 1;
    selectedPost.value.commentCount = postEngagement.value.commentCount;
    const index = posts.value.findIndex((item) => item.id === selectedPost.value?.id);
    if (index >= 0) posts.value[index].commentCount = postEngagement.value.commentCount;
  } catch (cause) {
    if (context.current()) {
      commentWriteUnconfirmed.value = !(cause instanceof ApiRequestError && [400, 422].includes(cause.status));
      formError.value = `${commentWriteUnconfirmed.value ? '评论提交结果未确认，服务端可能已执行，请勿重复发送。' : '评论被拒绝，请检查正文。'}${cause instanceof Error ? cause.message : '评论发布失败'}`;
    }
  } finally {
    if (context.current()) interactionSubmitting.value = false;
  }
}

async function deleteComment(item: PostComment) {
  if (
    !selectedPost.value ||
    !auth.user ||
    dialog.value !== 'postReader' ||
    interactionSubmitting.value ||
    commentLoading.value ||
    readerCommentsRefreshing.value
  )
    return;
  const row = postComments.value.find((value) => value.id === item.id);
  if (
    !row ||
    (row.userId !== auth.user.id && selectedPost.value.ownerId !== auth.user.id) ||
    !window.confirm('确认删除这条评论？')
  )
    return;
  const context = readerContext();
  interactionSubmitting.value = true;
  formError.value = '';
  try {
    await api.deletePostComment(context.postId!, row.id, row.version);
    if (!context.current()) return;
    postComments.value = postComments.value.filter((comment) => comment.id !== item.id);
    commentTotal.value = Math.max(commentTotal.value - 1, 0);
    postEngagement.value.commentCount = Math.max(postEngagement.value.commentCount - 1, 0);
    selectedPost.value.commentCount = postEngagement.value.commentCount;
  } catch (cause) {
    if (context.current())
      formError.value = `删除结果未确认，请先刷新评论。${cause instanceof Error ? cause.message : '评论删除失败'}`;
  } finally {
    if (context.current()) interactionSubmitting.value = false;
  }
}

function newCreatorDraft() {
  Object.assign(creatorProfile, {
    userId: auth.user?.id || '',
    slug: auth.user?.handle.replaceAll('_', '-') || '',
    displayName: auth.user?.displayName || '',
    headline: '',
    bio: '',
    avatarUrl: '',
    bannerUrl: '',
    avatarAssetId: '',
    bannerAssetId: '',
    status: 'DRAFT',
    version: 0,
  });
}

async function openCreatorStudio() {
  if (submitting.value || imageUploading.value) return;
  if (!editorGuard.requestLeave()) return;
  formError.value = '';
  if (!auth.user) {
    dialog.value = 'auth';
    formError.value = '请先登录，再创建 UP 主主页。';
    return;
  }
  dialog.value = 'creator';
  editorFormRevision.value++;
  creatorProfileLoaded.value = false;
  creatorProfileLoading.value = true;
  newCreatorDraft();
  const context = dialogContext();
  try {
    const result = await api.myCreatorProfile();
    if (currentDialog(context)) {
      if (
        !result ||
        result.userId !== auth.user?.id ||
        !Number.isSafeInteger(result.version) ||
        result.version < 0 ||
        !['DRAFT', 'ACTIVE', 'SUSPENDED'].includes(result.status)
      )
        throw new Error('本人主页响应未确认。');
      Object.assign(creatorProfile, result);
      creatorProfileLoaded.value = true;
      editorGuard.markConfirmed();
    }
  } catch (cause) {
    if (currentDialog(context) && cause instanceof ApiRequestError && cause.status === 404) {
      creatorProfileLoaded.value = true;
      editorGuard.markConfirmed();
    }
    if (currentDialog(context) && (!(cause instanceof ApiRequestError) || cause.status !== 404)) {
      formError.value = cause instanceof Error ? cause.message : '无法加载创作者资料';
    }
  } finally {
    if (currentDialog(context)) creatorProfileLoading.value = false;
  }
}

async function saveCreatorProfile(publish: boolean) {
  if (imageUploading.value || submitting.value || !creatorProfileLoaded.value || creatorProfileLoading.value) return;
  formError.value = '';
  if (await creatorEditor.saveProfile(creatorProfile, publish)) {
    if (creatorProfile.status === 'ACTIVE') void loadDiscovery();
  }
}

function applyConfirmedProfile(saved: CreatorProfile) {
  Object.assign(creatorProfile, saved);
  editorGuard.markConfirmed();
  studioProfile.value = saved;
  const index = creators.value.findIndex((item) => item.userId === saved.userId);
  if (saved.status === 'ACTIVE') {
    if (index >= 0) creators.value[index] = saved;
    else creators.value.unshift(saved);
  } else if (index >= 0) creators.value.splice(index, 1);
}

/** 用户确认覆盖本地表单后只查询实际本人接口，从不重发保存/发布。 */
function recheckCreatorSubmission() {
  if (submitting.value || imageUploading.value || !editorRecovery.value) return;
  if (!window.confirm('重新读取会用服务端最新状态覆盖当前表单；只查询，不会重复保存或发布。是否继续？')) return;
  void creatorEditor.recheck();
}

function onImageBusy(value: boolean) {
  imageUploading.value = value;
}

function setCreatorImage(target: 'avatar' | 'banner', image: ManagedImage) {
  if (target === 'avatar') {
    creatorProfile.avatarAssetId = image.id;
    creatorProfile.avatarUrl = managedImageUrl(image.id);
  } else {
    creatorProfile.bannerAssetId = image.id;
    creatorProfile.bannerUrl = managedImageUrl(image.id);
  }
}

function clearCreatorImage(target: 'avatar' | 'banner') {
  if (target === 'avatar') {
    creatorProfile.avatarAssetId = '';
    creatorProfile.avatarUrl = '';
  } else {
    creatorProfile.bannerAssetId = '';
    creatorProfile.bannerUrl = '';
  }
}

function setPostCover(image: ManagedImage) {
  postEdit.coverAssetId = image.id;
  postEdit.coverUrl = managedImageUrl(image.id);
}

function clearPostCover() {
  postEdit.coverAssetId = '';
  postEdit.coverUrl = '';
}

async function submitAuth() {
  if (submitting.value || loggingOut.value) return;
  submitting.value = true;
  formError.value = '';
  try {
    if (authMode.value === 'login') await auth.login(credentials.email, credentials.password);
    else await auth.register(credentials.email, credentials.password, credentials.handle, credentials.displayName);
    credentials.password = '';
    dialog.value = null;
    await syncFollowStates(creators.value);
  } catch (cause) {
    formError.value = cause instanceof Error ? cause.message : '认证失败';
  } finally {
    submitting.value = false;
  }
}

async function createCommunity() {
  submitting.value = true;
  formError.value = '';
  try {
    const created = await api.createCommunity(community);
    communities.value.unshift(created);
    ownedCommunities.value.unshift(created);
    dialog.value = null;
    Object.assign(community, { slug: '', name: '', description: '', badge: '' });
  } catch (cause) {
    formError.value = cause instanceof Error ? cause.message : '创建失败';
  } finally {
    submitting.value = false;
  }
}

async function saveCommunity() {
  submitting.value = true;
  formError.value = '';
  try {
    const updated = await api.updateCommunity(communityEdit.id, communityEdit);
    const ownedIndex = ownedCommunities.value.findIndex((item) => item.id === updated.id);
    if (ownedIndex >= 0) ownedCommunities.value[ownedIndex] = updated;
    const publicIndex = communities.value.findIndex((item) => item.id === updated.id);
    if (updated.visibility === 'PUBLIC') {
      if (publicIndex >= 0) communities.value[publicIndex] = updated;
      else communities.value.unshift(updated);
    } else if (publicIndex >= 0) communities.value.splice(publicIndex, 1);
    editCommunity(updated);
  } catch (cause) {
    formError.value = cause instanceof Error ? cause.message : '保存失败';
  } finally {
    submitting.value = false;
  }
}

async function archiveCommunity(item: Community) {
  if (!window.confirm(`确认归档“${item.name}”？归档后不会再公开展示。`)) return;
  submitting.value = true;
  formError.value = '';
  try {
    await api.archiveCommunity(item.id, item.version);
    ownedCommunities.value = ownedCommunities.value.filter((current) => current.id !== item.id);
    communities.value = communities.value.filter((current) => current.id !== item.id);
    communityEdit.id = '';
  } catch (cause) {
    formError.value = cause instanceof Error ? cause.message : '归档失败';
  } finally {
    submitting.value = false;
  }
}

async function createLive() {
  submitting.value = true;
  formError.value = '';
  try {
    await api.createLive(live);
    dialog.value = null;
  } catch (cause) {
    formError.value = cause instanceof Error ? cause.message : '创建失败';
  } finally {
    submitting.value = false;
  }
}

async function createVoiceRoom() {
  if (submitting.value || !auth.user) return;
  const userId = auth.user.id,
    sessionRevision = auth.sessionRevision,
    formRevision = dialogReadRevision,
    revision = ++voiceCreateRevision;
  const current = () =>
    !disposed &&
    revision === voiceCreateRevision &&
    auth.user?.id === userId &&
    auth.sessionRevision === sessionRevision &&
    dialogReadRevision === formRevision;
  voiceCreatePending = true;
  submitting.value = true;
  formError.value = '';
  try {
    const created = await api.createVoiceRoom({ ...voice });
    if (!current()) return;
    voiceRooms.value.unshift(created);
    voiceOwnerRefreshRevision.value++;
    dialog.value = null;
  } catch (cause) {
    if (current()) formError.value = cause instanceof Error ? cause.message : '创建失败';
  } finally {
    if (revision === voiceCreateRevision) {
      voiceCreatePending = false;
      if (auth.user?.id === userId && auth.sessionRevision === sessionRevision) submitting.value = false;
    }
  }
}

watch(
  [() => auth.user?.id, () => auth.sessionRevision],
  () => {
    resetPostReaderOperations();
    if (dialog.value === 'post' || dialog.value === 'creator') dialog.value = null;
    Object.assign(postEdit, {
      id: '',
      slug: '',
      title: '',
      excerpt: '',
      body: '',
      coverUrl: '',
      coverAssetId: '',
      status: 'DRAFT',
      version: 0,
    });
    creatorProfileLoaded.value = false;
    creatorProfileLoading.value = false;
    newCreatorDraft();
    if (voiceCreatePending) {
      voiceCreateRevision++;
      voiceCreatePending = false;
      submitting.value = false;
    }
  },
  { flush: 'sync' },
);

async function joinVoiceRoom(target: VoiceRoom, mode: 'listen' | 'speak' = 'listen') {
  if (!auth.user) {
    dialog.value = 'auth';
    formError.value = '请先登录，再加入语音房。';
    return;
  }
  if (target.controlled) {
    await router.push({
      name: 'voice-room',
      params: { roomId: target.id },
      state: {
        kokoVoiceEntry: {
          roomId: target.id,
          title: target.title,
          userId: auth.user.id,
          revision: auth.sessionRevision,
          documentOrigin: globalThis.performance?.timeOrigin,
          mode,
        },
      },
    });
    return;
  }
  await voiceState.joinVoiceRoom(target);
}

const discoverySnapshot = computed<DiscoverySnapshot>(() => ({
  communities: communities.value,
  liveRooms: liveRooms.value,
  voiceRooms: voiceRooms.value,
  creators: creators.value,
  posts: posts.value,
  creatorTotal: creatorTotal.value,
  postTotal: postTotal.value,
  loadedAt: loadedAt.value,
  domains: discoveryStates,
}));

function showAuth() {
  dialog.value = 'auth';
  formError.value = '';
  workspaceError.value = '';
}
async function workspaceAction(kind: WorkspaceAction) {
  if (submitting.value || imageUploading.value || interactionSubmitting.value) return;
  if (kind === 'post') {
    if (route.name !== 'studio' && (await router.push('/studio'))) return;
    if (auth.user) newPostDraft();
    else showAuth();
    return;
  }
  if (kind === 'profile') {
    void openCreatorStudio();
    return;
  }
  if (kind === 'community-manage') {
    void openCommunityManager();
    return;
  }
  requireLogin(kind);
}

/** 本人工作台跨路由/账号取消读取；仅 404 表示主页尚未创建。 */
async function loadStudio() {
  studioRead?.abort();
  const revision = ++studioRevision,
    userId = auth.user?.id,
    session = auth.sessionRevision;
  if (!userId || !auth.initialized) return;
  const controller = new AbortController();
  studioRead = controller;
  studioLoading.value = true;
  studioError.value = '';
  try {
    const [myPosts, profile] = await Promise.all([
      api.myPosts(controller.signal),
      api.myCreatorProfile(controller.signal).catch((cause) => {
        if (cause instanceof ApiRequestError && cause.status === 404) return null;
        throw cause;
      }),
    ]);
    if (disposed || revision !== studioRevision || auth.user?.id !== userId || auth.sessionRevision !== session) return;
    ownedPosts.value = myPosts;
    studioProfile.value = profile;
  } catch (cause) {
    if (!disposed && revision === studioRevision)
      studioError.value = cause instanceof Error ? cause.message : '本人工作台读取失败';
  } finally {
    if (!disposed && revision === studioRevision) studioLoading.value = false;
  }
}

async function logout() {
  if (loggingOut.value) return;
  if (submitting.value || imageUploading.value || interactionSubmitting.value || followSubmitting.value) {
    workspaceError.value = '请等待当前操作完成后退出，避免提交结果无法确认。';
    return;
  }
  if (!editorGuard.requestLeave()) return;
  loggingOut.value = true;
  workspaceError.value = '';
  try {
    await auth.logout();
    closeDialog();
    communityHubOpen.value = false;
    void leaveVoiceRoom();
  } catch (cause) {
    workspaceError.value = `退出未成功，请重试：${cause instanceof Error ? cause.message : '服务暂不可用'}`;
  } finally {
    loggingOut.value = false;
  }
}
async function retrySession() {
  if (auth.initialized && !loggingOut.value && !submitting.value) await auth.restore();
}
function closeDialog() {
  if (!submitting.value && !imageUploading.value && !interactionSubmitting.value && !voiceInteractionBusy.value) {
    if (!editorGuard.requestLeave()) return;
    dialog.value = null;
  }
}
const anyDialogOpen = computed(() => !!dialog.value || communityHubOpen.value);
useDialogAccessibility(
  anyDialogOpen,
  () => {
    if (communityHubOpen.value) communityHubOpen.value = false;
    else closeDialog();
  },
  () => submitting.value || imageUploading.value || interactionSubmitting.value || voiceInteractionBusy.value,
);

const viewBindings = computed(() => {
  if (workspacePage.value.section)
    return {
      section: workspacePage.value.section,
      data: discoverySnapshot.value,
      loading: loading.value,
      pageLoading: pageLoading.value,
      pageError: pageError.value,
      userId: auth.user?.id,
      displayName: auth.user?.displayName,
      followedIds: followedIds.value,
      followSubmitting: followSubmitting.value,
      voiceJoining: voiceJoining.value,
      voiceError: voiceError.value,
      onRetry: loadDiscovery,
      onLoadMore: loadMore,
      onAction: workspaceAction,
      onReadPost: readPost,
      onFollow: toggleFollow,
      onCommunity: openCommunityHub,
      onJoinVoice: joinVoiceRoom,
    };
  if (route.name === 'studio')
    return {
      posts: ownedPosts.value,
      profile: studioProfile.value,
      loading: studioLoading.value,
      error: studioError.value,
      actionBusy: archiveBusy.value || submitting.value || imageUploading.value,
      actionError: archiveError.value,
      actionSuccess: archiveSuccess.value,
      archivePendingId: archivePending.value?.id,
      onArchive: archivePost,
      onRecheckArchive: contentActions.recheck,
      onRead: readPost,
      onRefresh: loadStudio,
      onCreate: newPostDraft,
      onEdit: editPost,
      onProfile: openCreatorStudio,
    };
  if (route.name === 'messages')
    return { userId: auth.user?.id, embedded: true, onClose: () => router.push('/explore') };
  if (route.name === 'voice-owner')
    return {
      userId: auth.user?.id,
      sessionRevision: auth.sessionRevision,
      refreshRevision: voiceOwnerRefreshRevision.value,
      onCreate: (controlled = true) => {
        requireLogin('voice');
        voice.controlled = controlled;
      },
      onClosed: (roomId: string) => {
        voiceRooms.value = voiceRooms.value.filter((room) => room.id !== roomId);
        if (connectedVoice.value?.id === roomId || voiceJoining.value === roomId) void leaveVoiceRoom();
      },
    };
  if (route.name === 'notifications') return { userId: auth.user?.id };
  if (route.name === 'account')
    return {
      user: auth.user,
      onFollowing: () => openCollection('following'),
      onFavorites: () => openCollection('favorites'),
    };
  return {};
});
watch(
  [() => route.name, () => auth.user?.id, () => auth.initialized],
  ([name, userId], [, previousUser]) => {
    if (userId !== previousUser) {
      if (previousUser) void leaveVoiceRoom();
      ++dialogReadRevision;
      ownedPosts.value = [];
      ownedCommunities.value = [];
      studioProfile.value = null;
      favoritePosts.value = [];
      followedCreators.value = [];
      followedIds.value = new Set();
      communityHubOpen.value = false;
      postEngagement.value = {
        likeCount: selectedPost.value?.likeCount ?? 0,
        commentCount: selectedPost.value?.commentCount ?? 0,
        likedByMe: false,
      };
      postFavorite.value = { favoriteCount: selectedPost.value?.favoriteCount ?? 0, favoritedByMe: false };
      if (!userId && dialog.value !== 'auth' && dialog.value !== 'postReader') dialog.value = null;
      if (previousUser && userId !== previousUser && dialog.value !== 'auth' && dialog.value !== 'postReader')
        dialog.value = null;
      newCreatorDraft();
      if (userId && !loading.value) void syncFollowStates(creators.value);
    }
    studioRead?.abort();
    ++studioRevision;
    studioLoading.value = false;
    if (name === 'studio' && userId && auth.initialized) void loadStudio();
  },
  { immediate: true },
);
watch(
  dialog,
  (current, previous) => {
    if (previous === 'postReader' && current !== 'postReader') resetPostReaderOperations();
    ++dialogReadRevision;
    collectionLoading.value = false;
    commentLoading.value = false;
    if (previous === 'auth' && current !== 'auth') credentials.password = '';
    if (!current && route.name === 'studio' && auth.user) void loadStudio();
  },
  { flush: 'sync' },
);
onMounted(() => {
  void auth.restore();
  void loadDiscovery();
});
onBeforeUnmount(() => {
  disposed = true;
  readerRelationRead.abort();
  ++discoveryRevision;
  ++studioRevision;
  for (const read of Object.values(discoveryReads)) read.abort();
  studioRead?.abort();
  void leaveVoiceRoom();
});
</script>

<template>
  <WorkspaceShell
    :user="auth.user"
    :page="workspacePage"
    :logging-out="loggingOut"
    @auth="showAuth"
    @logout="logout"
    @create="workspaceAction('post')"
  >
    <div v-if="auth.sessionError" class="workspace-error compact" role="alert"
      ><strong>{{ auth.sessionExpired ? '会话已失效' : '会话状态未确认' }}</strong
      ><p>{{ auth.sessionError }}</p
      ><button
        v-if="!auth.sessionExpired"
        class="secondary"
        :disabled="!auth.initialized || submitting"
        @click="retrySession"
        >重新读取会话</button
      ></div
    >
    <p v-if="workspaceError" class="workspace-error compact" role="alert">{{ workspaceError }}</p>
    <section v-if="workspacePage.requiresAuth && !auth.initialized" class="workspace-empty" role="status"
      ><RefreshCw class="spin" :size="24" /><h2>正在恢复会话</h2><p>确认身份后才会读取本人内容。</p></section
    >
    <section v-else-if="workspacePage.requiresAuth && !auth.user" class="workspace-empty auth-required"
      ><UserRound :size="30" /><h1>{{ workspacePage.title }}</h1
      ><p>{{
        auth.sessionExpired
          ? '会话已失效，请重新登录后继续。'
          : auth.sessionError
            ? '会话读取失败，暂不能加载私有页面。请重试上方会话读取。'
            : '登录后才能读取本人数据和使用该功能。'
      }}</p
      ><button class="primary" @click="showAuth">登录 / 注册</button></section
    >
    <RouterView v-else v-slot="{ Component }"
      ><KeepAlive include="VoiceRoomView" :max="1"
        ><component :is="Component" :key="workspaceViewKey" v-bind="viewBindings" /></KeepAlive
    ></RouterView>
    <PersistentVoiceCallBar />
  </WorkspaceShell>
  <div
    v-if="dialog"
    class="modal-backdrop"
    role="dialog"
    aria-modal="true"
    aria-label="业务操作"
    tabindex="-1"
    @click.self="closeDialog"
  >
    <form
      v-if="dialog === 'auth'"
      class="modal"
      :aria-busy="submitting"
      @submit.prevent="submitAuth"
      @invalid.capture.prevent="formError = '请检查邮箱格式；用户名需 3–32 位字母、数字或下划线，密码需 10–72 位。'"
      ><button
        type="button"
        class="close"
        aria-label="关闭对话框"
        :disabled="submitting || imageUploading || interactionSubmitting"
        @click="closeDialog"
        >×</button
      ><p class="eyebrow">ACCOUNT</p><h2>{{ authMode === 'login' ? '登录 KOKO Nexus' : '创建正式账号' }}</h2
      ><label
        >邮箱<input
          v-model.trim="credentials.email"
          type="email"
          required
          autocomplete="email"
          :disabled="submitting" /></label
      ><label v-if="authMode === 'register'"
        >用户名<input
          v-model.trim="credentials.handle"
          pattern="[a-zA-Z0-9_]{3,32}"
          required
          :disabled="submitting" /></label
      ><label v-if="authMode === 'register'"
        >显示名称<input v-model.trim="credentials.displayName" maxlength="80" required :disabled="submitting" /></label
      ><label
        >密码<input
          v-model="credentials.password"
          type="password"
          minlength="10"
          maxlength="72"
          required
          :disabled="submitting"
          :autocomplete="authMode === 'register' ? 'new-password' : 'current-password'" /></label
      ><p class="hint">密码需 10–72 位；提交后请等待结果，不会自动重复注册。</p
      ><p v-if="formError" class="form-error" role="alert">{{ formError }}</p
      ><button type="submit" class="primary wide" :disabled="submitting">{{
        submitting ? '正在提交…' : authMode === 'login' ? '登录' : '注册并登录'
      }}</button
      ><button
        type="button"
        class="text-button"
        :disabled="submitting"
        @click="
          authMode = authMode === 'login' ? 'register' : 'login';
          formError = '';
        "
        >{{ authMode === 'login' ? '没有账号？立即注册' : '已有账号？返回登录' }}</button
      ></form
    >
    <VoiceInteractionPanel
      class="modal"
      v-else-if="dialog === 'voice-interaction' && selectedInteractionRoom && auth.user"
      :key="`${selectedInteractionRoom.id}:${auth.user.id}:${auth.sessionRevision}`"
      :room-id="selectedInteractionRoom.id"
      :room-title="selectedInteractionRoom.title"
      :user-id="auth.user.id"
      :session-revision="auth.sessionRevision"
      @busy="voiceInteractionBusy = $event"
      @close="closeDialog"
    />
    <form v-else-if="dialog === 'community'" class="modal" @submit.prevent="createCommunity"
      ><button
        type="button"
        class="close"
        aria-label="关闭对话框"
        :disabled="submitting || imageUploading || interactionSubmitting"
        @click="closeDialog"
        >×</button
      ><p class="eyebrow">NEW COMMUNITY</p><h2>创建社区</h2
      ><label>社区名称<input v-model.trim="community.name" maxlength="80" required /></label
      ><label
        >社区地址<input
          v-model.trim="community.slug"
          pattern="[a-z0-9-]{3,64}"
          placeholder="creator-hub"
          required /></label
      ><label>徽标<input v-model.trim="community.badge" pattern="[a-zA-Z0-9]{1,8}" placeholder="UP" required /></label
      ><label>简介<textarea v-model.trim="community.description" maxlength="500"></textarea></label
      ><p v-if="formError" class="form-error">{{ formError }}</p
      ><button class="primary wide" :disabled="submitting">创建社区</button></form
    >
    <form v-else-if="dialog === 'live'" class="modal" @submit.prevent="createLive"
      ><button
        type="button"
        class="close"
        aria-label="关闭对话框"
        :disabled="submitting || imageUploading || interactionSubmitting"
        @click="closeDialog"
        >×</button
      ><p class="eyebrow">NEW LIVE</p><h2>创建直播排期</h2
      ><label>直播标题<input v-model.trim="live.title" maxlength="120" required /></label
      ><label
        >直播地址<input v-model.trim="live.slug" pattern="[a-z0-9-]{3,80}" placeholder="weekly-show" required /></label
      ><label>分类<input v-model.trim="live.category" maxlength="60" required /></label
      ><label class="checkbox"><input v-model="live.interactive" type="checkbox" />允许观众申请连麦</label
      ><p class="hint">创建后为“待开播”。只有媒体供应商返回真实推流输入后才能开播。</p
      ><p v-if="formError" class="form-error">{{ formError }}</p
      ><button class="primary wide" :disabled="submitting">创建直播</button></form
    >
    <form v-else-if="dialog === 'voice'" class="modal" @submit.prevent="createVoiceRoom"
      ><button
        type="button"
        class="close"
        aria-label="关闭对话框"
        :disabled="submitting || imageUploading || interactionSubmitting"
        @click="closeDialog"
        >×</button
      ><p class="eyebrow">LIVEKIT VOICE</p><h2>创建语音房</h2
      ><label>房间标题<input v-model.trim="voice.title" maxlength="120" required /></label
      ><label
        >房间地址<input
          v-model.trim="voice.slug"
          pattern="[a-z0-9-]{3,80}"
          placeholder="creator-talk"
          required /></label
      ><label>话题<textarea v-model.trim="voice.topic" maxlength="300"></textarea></label
      ><label>人数上限<input v-model.number="voice.maxParticipants" type="number" min="2" max="100" required /></label
      ><p class="hint">创建操作会实时调用自建 LiveKit；媒体服务不可用时不会返回假成功。</p>
      <p v-if="voice.controlled" class="hint"
        >入房默认静音；上麦并解除闭麦后，点击开启麦克风。语音需要 HTTPS 和浏览器麦克风权限，连接状态以实际结果为准。</p
      ><p v-if="formError" class="form-error">{{ formError }}</p
      ><button class="primary wide" :disabled="submitting">创建语音房</button></form
    >
    <form v-else-if="dialog === 'creator'" class="modal creator-modal" @submit.prevent="saveCreatorProfile(false)">
      <button
        type="button"
        class="close"
        aria-label="关闭对话框"
        :disabled="submitting || imageUploading || interactionSubmitting"
        @click="closeDialog"
        >×</button
      >
      <p class="eyebrow">CREATOR STUDIO</p><h2>UP 主主页</h2>
      <div v-if="creatorProfileLoaded" class="profile-status"
        ><span :class="creatorProfile.status.toLowerCase()">{{
          creatorProfile.status === 'ACTIVE' ? '已发布' : creatorProfile.status === 'DRAFT' ? '草稿' : '已暂停'
        }}</span
        ><small>版本 {{ creatorProfile.version }}</small></div
      >
      <p v-if="creatorProfileLoading" role="status" class="hint">正在读取本人主页，确认当前版本前不能保存。</p>
      <button
        v-if="!creatorProfileLoaded && !creatorProfileLoading"
        type="button"
        class="secondary"
        @click="openCreatorStudio"
        >重新读取主页</button
      >
      <fieldset
        class="creator-editor-fields"
        :disabled="submitting || creatorProfileLoading || !creatorProfileLoaded || !!editorRecovery"
      >
        <label
          >主页地址<input
            v-model.trim="creatorProfile.slug"
            pattern="[a-z0-9][a-z0-9-]{1,62}[a-z0-9]"
            maxlength="64"
            placeholder="creator-name"
            required
        /></label>
        <label>UP 主名称<input v-model.trim="creatorProfile.displayName" maxlength="80" required /></label>
        <label
          >一句话介绍<input v-model.trim="creatorProfile.headline" maxlength="120" placeholder="告诉大家你在创作什么"
        /></label>
        <label>详细介绍<textarea v-model.trim="creatorProfile.bio" maxlength="1000"></textarea></label>
        <ManagedImagePicker
          label="头像图片"
          purpose="AVATAR"
          :current-url="creatorProfile.avatarUrl"
          :disabled="submitting || imageUploading || !creatorProfileLoaded || !!editorRecovery"
          @uploaded="(image) => setCreatorImage('avatar', image)"
          @cleared="clearCreatorImage('avatar')"
          @busy="onImageBusy"
        />
        <ManagedImagePicker
          label="主页封面"
          purpose="BANNER"
          :current-url="creatorProfile.bannerUrl"
          :disabled="submitting || imageUploading || !creatorProfileLoaded || !!editorRecovery"
          @uploaded="(image) => setCreatorImage('banner', image)"
          @cleared="clearCreatorImage('banner')"
          @busy="onImageBusy"
        />
      </fieldset>
      <p class="hint">首次保存为草稿。发布后图片才向访客开放；后续修改采用版本号校验，避免多个窗口互相覆盖。</p>
      <p v-if="editorDirty && !editorRecovery" class="hint" role="status"
        >有未保存修改；关闭或离开前会提醒，不会自动保存。</p
      >
      <p v-if="formError" class="form-error">{{ formError }}</p>
      <CreatorSaveStatus
        :success="editorSuccess"
        :error="editorError"
        :needs-recheck="!!editorRecovery"
        :busy="submitting"
        @recheck="recheckCreatorSubmission"
      />
      <div class="creator-form-actions"
        ><button
          class="secondary"
          type="submit"
          :disabled="submitting || imageUploading || !creatorProfileLoaded || !!editorRecovery"
          >保存</button
        ><button
          v-if="creatorProfile.status === 'DRAFT'"
          class="primary"
          type="button"
          :disabled="submitting || imageUploading || !creatorProfileLoaded || !!editorRecovery"
          @click="saveCreatorProfile(true)"
          >保存并发布</button
        ></div
      >
    </form>
    <div v-else-if="dialog === 'manage'" class="modal manager-modal"
      ><button
        type="button"
        class="close"
        aria-label="关闭对话框"
        :disabled="submitting || imageUploading || interactionSubmitting"
        @click="closeDialog"
        >×</button
      ><p class="eyebrow">COMMUNITY CONSOLE</p><h2>我的社区</h2>
      <form v-if="communityEdit.id" class="manager-form" @submit.prevent="saveCommunity">
        <button
          type="button"
          class="text-button back-button"
          @click="
            communityEdit.id = '';
            formError = '';
          "
          >← 返回社区列表</button
        >
        <label>社区名称<input v-model.trim="communityEdit.name" maxlength="80" required /></label>
        <label>徽标<input v-model.trim="communityEdit.badge" pattern="[a-zA-Z0-9]{1,8}" required /></label>
        <label
          >可见性<select v-model="communityEdit.visibility"
            ><option value="PUBLIC">公开</option
            ><option value="PRIVATE">私密</option></select
          ></label
        >
        <label>简介<textarea v-model.trim="communityEdit.description" maxlength="500"></textarea></label>
        <p class="hint">当前版本 {{ communityEdit.version }}。如其他窗口已修改，保存会返回冲突并要求刷新。</p>
        <p v-if="formError" class="form-error">{{ formError }}</p>
        <button class="primary wide" :disabled="submitting">{{ submitting ? '保存中…' : '保存修改' }}</button>
      </form>
      <div v-else class="managed-list">
        <article v-for="item in ownedCommunities" :key="item.id"
          ><div class="community-badge">{{ item.badge }}</div
          ><div
            ><strong>{{ item.name }}</strong
            ><small
              >/{{ item.slug }} · {{ item.visibility === 'PUBLIC' ? '公开' : '私密' }} · v{{ item.version }}</small
            ></div
          ><div class="managed-actions"
            ><button class="icon-button" title="编辑" @click="editCommunity(item)"><Pencil :size="17" /></button
            ><button class="icon-button danger" title="归档" :disabled="submitting" @click="archiveCommunity(item)"
              ><Archive :size="17" /></button></div
        ></article>
        <div v-if="!ownedCommunities.length" class="empty compact">你还没有可管理的社区。</div>
        <p v-if="formError" class="form-error">{{ formError }}</p>
      </div>
    </div>
    <div v-else-if="dialog === 'postManage'" class="modal manager-modal"
      ><button
        type="button"
        class="close"
        aria-label="关闭对话框"
        :disabled="submitting || imageUploading || interactionSubmitting"
        @click="closeDialog"
        >×</button
      ><p class="eyebrow">CONTENT CONSOLE</p
      ><div class="console-heading"
        ><h2>我的内容</h2><button class="primary" @click="newPostDraft"><Plus :size="16" />新建文章</button></div
      ><div class="managed-list post-managed-list"
        ><article v-for="item in ownedPosts" :key="item.id"
          ><div class="post-type"><FileText :size="20" /></div
          ><div
            ><strong>{{ item.title }}</strong
            ><small
              >/{{ item.slug }} · {{ item.status === 'PUBLISHED' ? '已发布' : '草稿' }} · v{{ item.version }}</small
            ></div
          ><div class="managed-actions"
            ><button class="icon-button" title="编辑" @click="editPost(item)"><Pencil :size="17" /></button
            ><button
              class="icon-button danger"
              title="归档"
              :disabled="submitting || archiveBusy || !!archivePending"
              @click="archivePost(item)"
              ><Archive :size="17" /></button></div></article
        ><div v-if="!ownedPosts.length" class="empty compact">你还没有内容草稿。创建后可先保存，再正式发布。</div
        ><p v-if="formError" class="form-error">{{ formError }}</p>
        <p v-if="archiveError" class="form-error" role="alert">{{ archiveError }}</p>
        <p v-if="archiveSuccess" class="workspace-success" role="status">{{ archiveSuccess }}</p>
        <button v-if="archivePending" class="secondary" :disabled="archiveBusy" @click="contentActions.recheck()"
          >查询归档结果</button
        ></div
      ></div
    >
    <form v-else-if="dialog === 'post'" class="modal post-modal" @submit.prevent="savePost(false)">
      <button
        type="button"
        class="close"
        aria-label="关闭编辑器"
        :disabled="submitting || imageUploading"
        @click="closeDialog"
        >×</button
      >
      <p class="eyebrow">ARTICLE EDITOR</p><h2>{{ postEdit.id ? '编辑内容' : '创建内容草稿' }}</h2>
      <div class="profile-status"
        ><span :class="postEdit.status.toLowerCase()">{{
          !postEdit.id ? '本地未保存' : postEdit.status === 'PUBLISHED' ? '已发布' : '草稿'
        }}</span
        ><small>{{ postEdit.id ? `版本 ${postEdit.version}` : '尚无服务端版本' }}</small></div
      >
      <fieldset class="creator-editor-fields" :disabled="submitting || !!editorRecovery">
        <label
          >内容地址<input
            v-model.trim="postEdit.slug"
            pattern="[a-z0-9][a-z0-9-]{1,78}[a-z0-9]"
            maxlength="80"
            placeholder="my-first-story"
            required
        /></label>
        <label>标题<input v-model.trim="postEdit.title" maxlength="160" required /></label>
        <label
          >摘要<textarea v-model.trim="postEdit.excerpt" maxlength="300" placeholder="用于发现页展示"></textarea>
        </label>
        <label
          >正文<textarea v-model.trim="postEdit.body" class="article-body" maxlength="100000" required></textarea>
        </label>
        <ManagedImagePicker
          label="文章封面"
          purpose="POST_COVER"
          :current-url="postEdit.coverUrl"
          :disabled="submitting || imageUploading || !!editorRecovery"
          @uploaded="setPostCover"
          @cleared="clearPostCover"
          @busy="onImageBusy"
        />
      </fieldset>
      <p class="hint">内容会先以草稿保存，发布后封面才向访客开放。修改带版本号，其他窗口已更新时不会静默覆盖。</p>
      <p v-if="editorDirty && !editorRecovery" class="hint" role="status"
        >有未保存修改；关闭或离开前会提醒，不会自动保存。</p
      >
      <p v-if="formError" class="form-error">{{ formError }}</p>
      <CreatorSaveStatus
        :success="editorSuccess"
        :error="editorError"
        :needs-recheck="!!editorRecovery"
        :busy="submitting"
        @recheck="recheckCreatorSubmission"
      />
      <div class="creator-form-actions"
        ><button class="secondary" type="submit" :disabled="submitting || imageUploading || !!editorRecovery">{{
          submitting ? '保存中…' : postEdit.status === 'PUBLISHED' ? '保存修改' : '保存草稿'
        }}</button
        ><button
          v-if="postEdit.status === 'DRAFT'"
          class="primary"
          type="button"
          :disabled="submitting || imageUploading || !!editorRecovery"
          @click="savePost(true)"
          >保存并发布</button
        ></div
      >
    </form>
    <div v-else-if="dialog === 'favorites' || dialog === 'following'" class="modal manager-modal"
      ><button
        type="button"
        class="close"
        aria-label="关闭对话框"
        :disabled="submitting || imageUploading || interactionSubmitting"
        @click="closeDialog"
        >×</button
      ><p class="eyebrow">MY COLLECTION</p><h2>{{ dialog === 'favorites' ? '我的收藏' : '我的关注' }}</h2
      ><div v-if="collectionLoading && collectionPage === 1" class="state">正在加载…</div
      ><div v-else class="managed-list"
        ><template v-if="dialog === 'favorites'"
          ><article v-for="item in favoritePosts" :key="item.id"
            ><div class="post-type"><Bookmark :size="19" /></div
            ><div
              ><strong>{{ item.title }}</strong
              ><small>{{ item.excerpt || '点击阅读文章' }}</small></div
            ><button class="secondary" @click="readPost(item)">阅读</button></article
          ></template
        ><template v-else
          ><article v-for="item in followedCreators" :key="item.userId"
            ><div class="creator-avatar">{{ item.displayName.slice(0, 1) }}</div
            ><div
              ><strong>{{ item.displayName }}</strong
              ><small>{{ item.followerCount }} 人关注 · /{{ item.slug }}</small></div
            ><button class="secondary" :disabled="followSubmitting === item.userId" @click="toggleFollow(item)"
              >取消关注</button
            ></article
          ></template
        ><div v-if="collectionTotal === 0" class="empty compact">{{
          dialog === 'favorites' ? '还没有收藏文章。' : '还没有关注创作者。'
        }}</div
        ><button
          v-if="(dialog === 'favorites' ? favoritePosts.length : followedCreators.length) < collectionTotal"
          class="secondary"
          :disabled="collectionLoading"
          @click="loadMoreCollection"
          >{{ collectionLoading ? '加载中…' : '加载更多' }}</button
        ><p v-if="collectionError" class="form-error">{{ collectionError }}</p></div
      ></div
    >
    <article v-else-if="dialog === 'postReader'" class="modal reader-modal"
      ><button
        type="button"
        class="close"
        aria-label="关闭对话框"
        :disabled="submitting || imageUploading || interactionSubmitting"
        @click="closeDialog"
        >×</button
      ><template v-if="selectedPost"
        ><div
          v-if="selectedPost.coverUrl"
          class="reader-cover"
          :style="{ backgroundImage: `url(${selectedPost.coverUrl})` }"
        ></div
        ><p class="eyebrow">CREATOR ARTICLE</p><h2>{{ selectedPost.title }}</h2
        ><p class="reader-meta"
          >发布于 {{ selectedPost.publishedAt ? new Date(selectedPost.publishedAt).toLocaleString('zh-CN') : '' }} · /{{
            selectedPost.slug
          }}</p
        ><p class="reader-body">{{ selectedPost.body }}</p
        ><p v-if="readerRelationsLoading" class="hint" role="status">正在核验本人点赞与收藏状态…</p>
        <p v-if="readerRelationsError" class="form-error" role="alert">{{ readerRelationsError }}</p>
        <button
          v-if="auth.user && !readerRelationsReady"
          class="secondary"
          :disabled="readerRelationsLoading || interactionSubmitting"
          @click="refreshPostRelations"
          >重新核验互动状态</button
        >
        <div class="reader-engagement"
          ><button
            class="like-button"
            :class="{ liked: readerRelationsReady && postEngagement.likedByMe }"
            :disabled="
              interactionSubmitting ||
              !readerRelationsReady ||
              readerRelationsLoading ||
              commentLoading ||
              readerCommentsRefreshing
            "
            @click="togglePostLike"
            ><Heart :size="18" :fill="readerRelationsReady && postEngagement.likedByMe ? 'currentColor' : 'none'" />{{
              !readerRelationsReady ? '点赞状态待核验' : postEngagement.likedByMe ? '已点赞' : '点赞'
            }}
            · {{ postEngagement.likeCount }}</button
          ><button
            class="like-button"
            :class="{ liked: readerRelationsReady && postFavorite.favoritedByMe }"
            :disabled="
              interactionSubmitting ||
              !readerRelationsReady ||
              readerRelationsLoading ||
              commentLoading ||
              readerCommentsRefreshing
            "
            @click="togglePostFavorite"
            ><Bookmark
              :size="18"
              :fill="readerRelationsReady && postFavorite.favoritedByMe ? 'currentColor' : 'none'"
            />{{ !readerRelationsReady ? '收藏状态待核验' : postFavorite.favoritedByMe ? '已收藏' : '收藏' }} ·
            {{ postFavorite.favoriteCount }}</button
          ><span><MessageCircle :size="17" />{{ postEngagement.commentCount }} 条评论</span></div
        ><section class="comment-section"
          ><form class="comment-form" @submit.prevent="submitComment">
            <textarea
              v-model.trim="commentBody"
              aria-label="评论正文"
              maxlength="2000"
              :placeholder="auth.user ? '写下你的评论…' : '登录后参与评论'"
              :disabled="!auth.user || interactionSubmitting || commentWriteUnconfirmed"
            ></textarea
            ><button
              v-if="auth.user"
              class="primary"
              :disabled="
                interactionSubmitting ||
                commentLoading ||
                readerCommentsRefreshing ||
                commentWriteUnconfirmed ||
                !commentBody.trim()
              "
              >发表评论</button
            ><button
              v-else
              type="button"
              class="secondary"
              @click="
                dialog = 'auth';
                formError = '请先登录，再发表评论。';
              "
              >登录后评论</button
            ></form
          ><p v-if="formError" class="form-error" role="alert">{{ formError }}</p>
          <p v-if="commentWriteUnconfirmed" class="hint" role="status"
            >评论提交结果未确认，原文已保留但不会自动重发。查看列表不能证明原请求已结束；关闭或刷新会丢失本机提示。</p
          >
          <button
            v-if="commentWriteUnconfirmed"
            class="secondary"
            :disabled="interactionSubmitting || commentLoading || readerCommentsRefreshing"
            @click="refreshReaderComments"
            >{{ readerCommentsRefreshing ? '正在读取…' : '查看最新评论' }}</button
          ><div class="comment-list"
            ><article v-for="item in postComments" :key="item.id"
              ><div class="comment-avatar">{{ item.userId === auth.user?.id ? initials : '成' }}</div
              ><div
                ><strong>{{ item.userId === auth.user?.id ? auth.user?.displayName : '社区成员' }}</strong
                ><small>{{ new Date(item.createdAt).toLocaleString('zh-CN') }}</small
                ><p>{{ item.body }}</p></div
              ><button
                v-if="item.userId === auth.user?.id || selectedPost.ownerId === auth.user?.id"
                class="comment-delete"
                title="删除评论"
                :disabled="interactionSubmitting || commentLoading || readerCommentsRefreshing"
                @click="deleteComment(item)"
                ><Trash2 :size="15" /></button></article
            ><div v-if="!postComments.length" class="empty compact">还没有评论，来发表第一条真实评论。</div></div
          ><button
            v-if="postComments.length < commentTotal"
            class="secondary load-more"
            :disabled="commentLoading || interactionSubmitting || readerCommentsRefreshing"
            @click="loadMoreComments"
            >{{ commentLoading ? '加载中…' : '加载更多评论' }}</button
          ></section
        ></template
      ><div v-else-if="formError" class="state error-state">{{ formError }}</div
      ><div v-else class="state"><RefreshCw class="spin" />正在加载正文…</div></article
    >
  </div>
  <aside v-if="connectedVoice || voiceJoining" class="voice-dock" aria-label="当前语音连接"
    ><div ref="remoteAudioRoot" class="remote-audio" /><div
      ><strong>{{ connectedVoice?.title || '正在加入语音房' }}</strong
      ><span role="status">{{
        voicePhase === 'joining'
          ? '正在连接，可取消'
          : voicePhase === 'reconnecting'
            ? '媒体连接恢复中…'
            : `${participantCount} 人在线 · 加入默认不开麦`
      }}</span>
      <p v-if="voiceError" role="alert" class="form-error">{{ voiceError }}</p>
      <VoicePlaybackControls
        :phase="voicePhase"
        :blocked="audioPlaybackBlocked"
        :busy="audioPlaybackBusy"
        :error="audioPlaybackError"
        @resume="resumeVoiceAudio" /></div
    ><button
      v-if="connectedVoice"
      class="icon-button"
      :disabled="microphoneBusy || voicePhase !== 'connected'"
      :aria-pressed="microphoneEnabled"
      :aria-label="microphoneEnabled ? '关闭麦克风' : '打开麦克风'"
      :title="microphoneEnabled ? '关闭麦克风' : '打开麦克风'"
      @click="toggleMicrophone"
      ><Mic v-if="microphoneEnabled" :size="18" /><MicOff v-else :size="18" /></button
    ><button class="leave-button" @click="leaveVoiceRoom">{{ voiceJoining ? '取消加入' : '离开' }}</button></aside
  >
  <div
    v-if="communityHubOpen && auth.user"
    class="modal-backdrop"
    role="dialog"
    aria-modal="true"
    aria-label="社区成员中心"
    tabindex="-1"
    @click.self="communityHubOpen = false"
    ><CommunityMembershipPanel
      :key="communityHubId"
      :user-id="auth.user.id"
      :initial-id="communityHubId"
      @close="communityHubOpen = false"
      @changed="
        loadDiscovery();
        loadOwnedCommunities().catch(
          (cause) => (workspaceError = cause instanceof Error ? cause.message : '本人社区读取失败'),
        );
      "
  /></div>
</template>
