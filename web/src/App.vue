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
import type { DiscoverySnapshot, WorkspaceAction } from './types/workspace';
import { useDialogAccessibility } from './composables/dialog-accessibility';
import { useVoiceConnection } from './composables/voice-connection';
import VoiceInteractionPanel from './components/VoiceInteractionPanel.vue';

const auth = useAuthStore();
const route = useRoute(),
  router = useRouter();
const workspacePage = computed(() => workspacePages.find((page) => page.name === route.name) ?? unknownPage);
/** 文本连接不能复用同账号另一登录轮次；其余工作台仍保留原有命令恢复生命周期。 */
const workspaceViewKey = computed(() => {
  const identity = `${String(route.name)}:${auth.user?.id ?? 'anonymous'}`;
  return route.name === 'messages' ? `${identity}:${auth.sessionRevision}` : identity;
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
let discoveryRead: AbortController | undefined, studioRead: AbortController | undefined;
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
const loading = ref(true);
const error = ref('');
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
const voice = reactive({ slug: '', title: '', topic: '', maxParticipants: 30, controlled: false });
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
const remoteAudioRoot = ref<HTMLElement | null>(null);
const voiceState = useVoiceConnection(auth, api.joinVoiceRoom, remoteAudioRoot);
const {
  connectedVoice,
  voiceJoining,
  voiceError,
  microphoneEnabled,
  microphoneBusy,
  participantCount,
  phase: voicePhase,
  toggleMicrophone,
  leaveVoiceRoom,
} = voiceState;
const initials = computed(() => auth.user?.displayName.slice(0, 1).toUpperCase() || '访');

async function loadDiscovery() {
  discoveryRead?.abort();
  const revision = ++discoveryRevision,
    controller = new AbortController();
  discoveryRead = controller;
  loading.value = true;
  error.value = '';
  pageLoading.value = false;
  pageError.value = '';
  try {
    const [communityData, liveData, voiceData, creatorData, postData] = await Promise.all([
      api.communities(controller.signal),
      api.liveRooms(controller.signal),
      api.voiceRooms(controller.signal),
      api.creatorPage(1, 12, controller.signal),
      api.postPage(1, 12, controller.signal),
    ]);
    if (disposed || revision !== discoveryRevision) return;
    communities.value = communityData;
    liveRooms.value = liveData;
    voiceRooms.value = voiceData;
    creators.value = creatorData.items;
    creatorPage.value = creatorData.page;
    creatorTotal.value = creatorData.total;
    posts.value = postData.items;
    postPage.value = postData.page;
    postTotal.value = postData.total;
    loadedAt.value = new Date().toISOString();
    if (auth.user) await syncFollowStates(creatorData.items);
  } catch (cause) {
    if (!disposed && revision === discoveryRevision)
      error.value = cause instanceof Error ? cause.message : '服务暂时不可用';
  } finally {
    if (!disposed && revision === discoveryRevision) loading.value = false;
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
  if (disposed || loading.value || pageLoading.value) return;
  const revision = discoveryRevision,
    signal = discoveryRead?.signal;
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
  if (next === 'voice') voice.controlled = false;
  dialog.value = auth.user ? next : 'auth';
  formError.value = auth.user ? '' : '请先登录，再使用创作者功能。';
}

function newPostDraft() {
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
}

function editPost(item: CreatorPost) {
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
  submitting.value = true;
  formError.value = '';
  try {
    const payload = {
      slug: postEdit.slug,
      title: postEdit.title,
      excerpt: postEdit.excerpt,
      body: postEdit.body,
      coverUrl: postEdit.coverUrl,
      coverAssetId: postEdit.coverAssetId,
    };
    let saved = postEdit.id
      ? await api.updatePost(postEdit.id, { ...payload, version: postEdit.version })
      : await api.createPost(payload);
    if (publish && saved.status === 'DRAFT') saved = await api.publishPost(saved.id, saved.version || 0);
    Object.assign(postEdit, saved);
    const ownedIndex = ownedPosts.value.findIndex((item) => item.id === saved.id);
    if (ownedIndex >= 0) ownedPosts.value[ownedIndex] = saved;
    else ownedPosts.value.unshift(saved);
    const publicIndex = posts.value.findIndex((item) => item.id === saved.id);
    if (saved.status === 'PUBLISHED') {
      if (publicIndex >= 0) posts.value[publicIndex] = saved;
      else posts.value.unshift(saved);
      await loadDiscovery();
    }
  } catch (cause) {
    formError.value = cause instanceof Error ? cause.message : '内容保存失败';
  } finally {
    submitting.value = false;
  }
}

async function archivePost(item: CreatorPost) {
  if (!window.confirm(`确认归档“${item.title}”？归档后将立即停止公开展示。`)) return;
  submitting.value = true;
  formError.value = '';
  try {
    await api.archivePost(item.id, item.version || 0);
    ownedPosts.value = ownedPosts.value.filter((current) => current.id !== item.id);
    posts.value = posts.value.filter((current) => current.id !== item.id);
    await loadDiscovery();
  } catch (cause) {
    formError.value = cause instanceof Error ? cause.message : '归档失败';
  } finally {
    submitting.value = false;
  }
}

async function readPost(item: CreatorPost) {
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
    postComments.value = comments.items;
    commentPage.value = comments.page;
    commentTotal.value = comments.total;
    if (auth.user) {
      const [engagement, favorite] = await Promise.all([
        api.postEngagement(detail.id),
        api.postFavoriteState(detail.id),
      ]);
      if (!currentDialog(context)) return;
      postEngagement.value = engagement;
      postFavorite.value = favorite;
    } else {
      postEngagement.value = { likeCount: detail.likeCount, commentCount: detail.commentCount, likedByMe: false };
      postFavorite.value = { favoriteCount: detail.favoriteCount, favoritedByMe: false };
    }
  } catch (cause) {
    if (currentDialog(context)) formError.value = cause instanceof Error ? cause.message : '内容加载失败';
  }
}

async function loadMoreComments() {
  if (!selectedPost.value || commentLoading.value) return;
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
  if (!selectedPost.value) return;
  if (!auth.user) {
    dialog.value = 'auth';
    formError.value = '请先登录，再收藏内容。';
    return;
  }
  interactionSubmitting.value = true;
  formError.value = '';
  try {
    const result = postFavorite.value.favoritedByMe
      ? await api.unfavoritePost(selectedPost.value.id)
      : await api.favoritePost(selectedPost.value.id);
    postFavorite.value = result;
    selectedPost.value.favoriteCount = result.favoriteCount;
    const listed = posts.value.find((item) => item.id === selectedPost.value?.id);
    if (listed) listed.favoriteCount = result.favoriteCount;
    if (!result.favoritedByMe) {
      favoritePosts.value = favoritePosts.value.filter((item) => item.id !== selectedPost.value?.id);
      collectionTotal.value = Math.max(collectionTotal.value - 1, 0);
    }
  } catch (cause) {
    formError.value = cause instanceof Error ? cause.message : '收藏操作失败';
  } finally {
    interactionSubmitting.value = false;
  }
}

async function togglePostLike() {
  if (!selectedPost.value) return;
  if (!auth.user) {
    dialog.value = 'auth';
    formError.value = '请先登录，再点赞内容。';
    return;
  }
  interactionSubmitting.value = true;
  formError.value = '';
  try {
    postEngagement.value = postEngagement.value.likedByMe
      ? await api.unlikePost(selectedPost.value.id)
      : await api.likePost(selectedPost.value.id);
    selectedPost.value.likeCount = postEngagement.value.likeCount;
    const index = posts.value.findIndex((item) => item.id === selectedPost.value?.id);
    if (index >= 0) posts.value[index].likeCount = postEngagement.value.likeCount;
  } catch (cause) {
    formError.value = cause instanceof Error ? cause.message : '点赞操作失败';
  } finally {
    interactionSubmitting.value = false;
  }
}

async function submitComment() {
  if (!selectedPost.value || !commentBody.value.trim()) return;
  if (!auth.user) {
    dialog.value = 'auth';
    formError.value = '请先登录，再发表评论。';
    return;
  }
  interactionSubmitting.value = true;
  formError.value = '';
  try {
    postComments.value.unshift(await api.commentPost(selectedPost.value.id, commentBody.value));
    commentTotal.value += 1;
    commentBody.value = '';
    postEngagement.value.commentCount += 1;
    selectedPost.value.commentCount = postEngagement.value.commentCount;
    const index = posts.value.findIndex((item) => item.id === selectedPost.value?.id);
    if (index >= 0) posts.value[index].commentCount = postEngagement.value.commentCount;
  } catch (cause) {
    formError.value = cause instanceof Error ? cause.message : '评论发布失败';
  } finally {
    interactionSubmitting.value = false;
  }
}

async function deleteComment(item: PostComment) {
  if (!selectedPost.value || !window.confirm('确认删除这条评论？')) return;
  interactionSubmitting.value = true;
  formError.value = '';
  try {
    await api.deletePostComment(selectedPost.value.id, item.id, item.version);
    postComments.value = postComments.value.filter((comment) => comment.id !== item.id);
    commentTotal.value = Math.max(commentTotal.value - 1, 0);
    postEngagement.value.commentCount = Math.max(postEngagement.value.commentCount - 1, 0);
    selectedPost.value.commentCount = postEngagement.value.commentCount;
  } catch (cause) {
    formError.value = cause instanceof Error ? cause.message : '评论删除失败';
  } finally {
    interactionSubmitting.value = false;
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
  formError.value = '';
  if (!auth.user) {
    dialog.value = 'auth';
    formError.value = '请先登录，再创建 UP 主主页。';
    return;
  }
  dialog.value = 'creator';
  newCreatorDraft();
  const context = dialogContext();
  try {
    const result = await api.myCreatorProfile();
    if (currentDialog(context)) Object.assign(creatorProfile, result);
  } catch (cause) {
    if (currentDialog(context) && (!(cause instanceof ApiRequestError) || cause.status !== 404)) {
      formError.value = cause instanceof Error ? cause.message : '无法加载创作者资料';
    }
  }
}

async function saveCreatorProfile(publish: boolean) {
  if (imageUploading.value || submitting.value) return;
  submitting.value = true;
  formError.value = '';
  try {
    let saved = await api.saveCreatorProfile(creatorProfile);
    if (publish && saved.status === 'DRAFT') saved = await api.publishCreatorProfile(saved.version);
    Object.assign(creatorProfile, saved);
    studioProfile.value = saved;
    const index = creators.value.findIndex((item) => item.userId === saved.userId);
    if (saved.status === 'ACTIVE') {
      if (index >= 0) creators.value[index] = saved;
      else creators.value.unshift(saved);
      await loadDiscovery();
    }
  } catch (cause) {
    formError.value = cause instanceof Error ? cause.message : '创作者资料保存失败';
  } finally {
    submitting.value = false;
  }
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
    if (voiceCreatePending) {
      voiceCreateRevision++;
      voiceCreatePending = false;
      submitting.value = false;
    }
  },
  { flush: 'sync' },
);

async function joinVoiceRoom(target: VoiceRoom) {
  if (!auth.user) {
    dialog.value = 'auth';
    formError.value = '请先登录，再加入语音房。';
    return;
  }
  if (target.controlled) {
    selectedInteractionRoom.value = target;
    dialog.value = 'voice-interaction';
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
}));

function showAuth() {
  dialog.value = 'auth';
  formError.value = '';
  workspaceError.value = '';
}
function workspaceAction(kind: WorkspaceAction) {
  if (submitting.value || imageUploading.value || interactionSubmitting.value) return;
  if (kind === 'post') {
    void router.push('/studio');
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
  if (!submitting.value && !imageUploading.value && !interactionSubmitting.value && !voiceInteractionBusy.value)
    dialog.value = null;
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
      error: error.value,
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
      onCreate: (controlled = false) => {
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
  ++discoveryRevision;
  ++studioRevision;
  discoveryRead?.abort();
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
      ><component :is="Component" :key="workspaceViewKey" v-bind="viewBindings"
    /></RouterView>
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
    <form v-if="dialog === 'auth'" class="modal" @submit.prevent="submitAuth"
      ><button
        type="button"
        class="close"
        aria-label="关闭对话框"
        :disabled="submitting || imageUploading || interactionSubmitting"
        @click="closeDialog"
        >×</button
      ><p class="eyebrow">ACCOUNT</p><h2>{{ authMode === 'login' ? '登录 KOKO Nexus' : '创建正式账号' }}</h2
      ><label>邮箱<input v-model.trim="credentials.email" type="email" required autocomplete="email" /></label
      ><label v-if="authMode === 'register'"
        >用户名<input v-model.trim="credentials.handle" pattern="[a-zA-Z0-9_]{3,32}" required /></label
      ><label v-if="authMode === 'register'"
        >显示名称<input v-model.trim="credentials.displayName" maxlength="80" required /></label
      ><label
        >密码<input
          v-model="credentials.password"
          type="password"
          minlength="10"
          maxlength="72"
          required
          autocomplete="current-password" /></label
      ><p v-if="formError" class="form-error">{{ formError }}</p
      ><button class="primary wide" :disabled="submitting">{{
        submitting ? '正在提交…' : authMode === 'login' ? '登录' : '注册并登录'
      }}</button
      ><button
        type="button"
        class="text-button"
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
      ><p class="eyebrow">LIVEKIT VOICE</p><h2>{{ voice.controlled ? '创建受控房间（媒体待接入）' : '创建语音房' }}</h2
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
      <p v-if="voice.controlled" class="hint">受控房间仅开放成员/麦位持久状态，媒体授权尚未接入，不签发旧发布凭据。</p
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
      <div class="profile-status"
        ><span :class="creatorProfile.status.toLowerCase()">{{
          creatorProfile.status === 'ACTIVE' ? '已发布' : creatorProfile.status === 'DRAFT' ? '草稿' : '已暂停'
        }}</span
        ><small>版本 {{ creatorProfile.version }}</small></div
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
        :disabled="submitting || imageUploading"
        @uploaded="(image) => setCreatorImage('avatar', image)"
        @cleared="clearCreatorImage('avatar')"
        @busy="onImageBusy"
      />
      <ManagedImagePicker
        label="主页封面"
        purpose="BANNER"
        :current-url="creatorProfile.bannerUrl"
        :disabled="submitting || imageUploading"
        @uploaded="(image) => setCreatorImage('banner', image)"
        @cleared="clearCreatorImage('banner')"
        @busy="onImageBusy"
      />
      <p class="hint">首次保存为草稿。发布后图片才向访客开放；后续修改采用版本号校验，避免多个窗口互相覆盖。</p>
      <p v-if="formError" class="form-error">{{ formError }}</p>
      <div class="creator-form-actions"
        ><button class="secondary" type="submit" :disabled="submitting || imageUploading">保存</button
        ><button
          v-if="creatorProfile.status === 'DRAFT'"
          class="primary"
          type="button"
          :disabled="submitting || imageUploading"
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
            ><button class="icon-button danger" title="归档" :disabled="submitting" @click="archivePost(item)"
              ><Archive :size="17" /></button></div></article
        ><div v-if="!ownedPosts.length" class="empty compact">你还没有内容草稿。创建后可先保存，再正式发布。</div
        ><p v-if="formError" class="form-error">{{ formError }}</p></div
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
        ><span :class="postEdit.status.toLowerCase()">{{ postEdit.status === 'PUBLISHED' ? '已发布' : '草稿' }}</span
        ><small>版本 {{ postEdit.version }}</small></div
      >
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
        :disabled="submitting || imageUploading"
        @uploaded="setPostCover"
        @cleared="clearPostCover"
        @busy="onImageBusy"
      />
      <p class="hint">内容会先以草稿保存，发布后封面才向访客开放。修改带版本号，其他窗口已更新时不会静默覆盖。</p>
      <p v-if="formError" class="form-error">{{ formError }}</p>
      <div class="creator-form-actions"
        ><button class="secondary" type="submit" :disabled="submitting || imageUploading">{{
          submitting ? '保存中…' : postEdit.status === 'PUBLISHED' ? '保存修改' : '保存草稿'
        }}</button
        ><button
          v-if="postEdit.status === 'DRAFT'"
          class="primary"
          type="button"
          :disabled="submitting || imageUploading"
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
        ><div class="reader-engagement"
          ><button
            class="like-button"
            :class="{ liked: postEngagement.likedByMe }"
            :disabled="interactionSubmitting"
            @click="togglePostLike"
            ><Heart :size="18" :fill="postEngagement.likedByMe ? 'currentColor' : 'none'" />{{
              postEngagement.likedByMe ? '已点赞' : '点赞'
            }}
            · {{ postEngagement.likeCount }}</button
          ><button
            class="like-button"
            :class="{ liked: postFavorite.favoritedByMe }"
            :disabled="interactionSubmitting"
            @click="togglePostFavorite"
            ><Bookmark :size="18" :fill="postFavorite.favoritedByMe ? 'currentColor' : 'none'" />{{
              postFavorite.favoritedByMe ? '已收藏' : '收藏'
            }}
            · {{ postFavorite.favoriteCount }}</button
          ><span><MessageCircle :size="17" />{{ postEngagement.commentCount }} 条评论</span></div
        ><section class="comment-section"
          ><form class="comment-form" @submit.prevent="submitComment">
            <textarea
              v-model.trim="commentBody"
              maxlength="2000"
              :placeholder="auth.user ? '写下你的评论…' : '登录后参与评论'"
              :disabled="!auth.user"
            ></textarea
            ><button v-if="auth.user" class="primary" :disabled="interactionSubmitting || !commentBody.trim()"
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
          ><p v-if="formError" class="form-error">{{ formError }}</p
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
                :disabled="interactionSubmitting"
                @click="deleteComment(item)"
                ><Trash2 :size="15" /></button></article
            ><div v-if="!postComments.length" class="empty compact">还没有评论，来发表第一条真实评论。</div></div
          ><button
            v-if="postComments.length < commentTotal"
            class="secondary load-more"
            :disabled="commentLoading"
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
      <p v-if="voiceError" role="alert" class="form-error">{{ voiceError }}</p></div
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
