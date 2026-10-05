import { apiBaseUrl, request } from './services/http'
// 保留现有业务模块引用路径，基础请求实现不再堆在领域契约中。
export { ApiRequestError, request } from './services/http'

export type UserIdentity = {
  /** 业务标识字符串；雪花 ID 禁止转成 number。 */
  id: string
  /** 登录邮箱，个人敏感信息。 */
  email: string
  /** 公开用户名。 */
  handle: string
  /** 公开显示名称。 */
  displayName: string
  /** 头像预览地址；未设置时缺省。 */
  avatarUrl?: string
}
export type Community = {
  /** 业务标识字符串；雪花 ID 禁止转成 number。 */
  id: string
  /** 公开访问路径标识。 */
  slug: string
  /** 业务名称。 */
  name: string
  /** 业务说明；未设置时缺省。 */
  description?: string
  /** 社区短徽标。 */
  badge: string
  /** 当前社区成员数。 */
  members: number
  /** 公开或私有；权限由服务端独立判断。 */
  visibility: 'PUBLIC' | 'PRIVATE'
  /** 服务端乐观锁版本，更新必须携带当前值。 */
  version: number
}
export type LiveRoom = {
  /** 业务标识字符串；雪花 ID 禁止转成 number。 */
  id: string
  /** 公开访问路径标识。 */
  slug: string
  /** 业务标题。 */
  title: string
  /** 直播创建者显示名称。 */
  creator: string
  /** 供应商确认的观看人数，不伪造直播在线状态。 */
  viewers: number
  /** 直播分类。 */
  category: string
  /** 是否允许互动连麦。 */
  interactive: boolean
  /** 服务端领域状态，不允许客户端自行转移。 */
  status: string
}
export type VoiceRoom = {
  /** 业务标识字符串；雪花 ID 禁止转成 number。 */
  id: string
  /** 公开访问路径标识。 */
  slug: string
  /** 业务标题。 */
  title: string
  /** 语音房主题；未设置时缺省。 */
  topic?: string
  /** 语音房创建者显示名称。 */
  owner: string
  /** 语音房人数上限。 */
  maxParticipants: number
  /** 服务端领域状态，不允许客户端自行转移。 */
  status: string
}
export type VoiceJoinCredential = {
  /** LiveKit 公开连接地址；正式运营使用 TLS。 */
  url: string
  /** 限房间的短期入会令牌，禁止日志和持久化。 */
  token: string
  /** 媒体供应商房间名称。 */
  roomName: string
}
export type CreatorProfile = {
  /** 用户 ID 字符串。 */
  userId: string
  /** 公开访问路径标识。 */
  slug: string
  /** 公开显示名称。 */
  displayName: string
  /** 创作者主页短介绍。 */
  headline: string
  /** 创作者个人简介。 */
  bio: string
  /** 头像预览地址；未设置时缺省。 */
  avatarUrl?: string
  /** 封面预览地址；未设置时缺省。 */
  bannerUrl?: string
  /** 已验证归属的头像资产 UUID；未绑定时缺省。 */
  avatarAssetId?: string
  /** 已验证归属的封面资产 UUID；未绑定时缺省。 */
  bannerAssetId?: string
  /** 服务端领域状态，不允许客户端自行转移。 */
  status: 'DRAFT' | 'ACTIVE' | 'SUSPENDED'
  /** 服务端乐观锁版本，更新必须携带当前值。 */
  version: number
  /** 服务端统计的粉丝数。 */
  followerCount: number
}
export type CreatorPost = {
  /** 业务标识字符串；雪花 ID 禁止转成 number。 */
  id: string
  /** 资源所有者 ID 字符串。 */
  ownerId: string
  /** 公开访问路径标识。 */
  slug: string
  /** 业务标题。 */
  title: string
  /** 文章摘要。 */
  excerpt: string
  /** 纯文本正文；列表投影可能不包含正文。 */
  body?: string
  /** 文章封面预览地址；未设置时缺省。 */
  coverUrl?: string
  /** 已验证归属的文章封面资产 UUID；未绑定时缺省。 */
  coverAssetId?: string
  /** 内容类型，当前仅 ARTICLE。 */
  kind: 'ARTICLE'
  /** 服务端领域状态，不允许客户端自行转移。 */
  status?: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
  /** 服务端乐观锁版本，更新必须携带当前值。 */
  version?: number
  /** 正式发布时间，未发布时缺省；Asia/Shanghai。 */
  publishedAt?: string
  /** 服务端最后修改时间；Asia/Shanghai。 */
  updatedAt?: string
  /** 服务端统计的点赞数。 */
  likeCount: number
  /** 服务端统计的可见评论数。 */
  commentCount: number
  /** 服务端统计的收藏数。 */
  favoriteCount: number
}
export type Page<T> = {
  /** 当前页数据，不代表全量结果。 */
  items: T[]
  /** 从 1 开始的页码。 */
  page: number
  /** 每页条数，受服务端上限约束。 */
  size: number
  /** 查询结果总条数。 */
  total: number
}
export type CreatorFollowState = {
  /** 创作者用户 ID 字符串。 */
  creatorId: string
  /** 服务端统计的粉丝数。 */
  followerCount: number
  /** 当前已认证用户是否已关注。 */
  followedByMe: boolean
}
export type PostFavoriteState = {
  /** 服务端统计的收藏数。 */
  favoriteCount: number
  /** 当前已认证用户是否已收藏。 */
  favoritedByMe: boolean
}
export type NotificationType = 'FOLLOW' | 'COMMENT' | 'LIVE_STARTED'
export type NotificationItem = {
  /** 业务标识字符串；雪花 ID 禁止转成 number。 */
  id: string
  /** 通知触发者用户 ID 字符串。 */
  actorId: string
  /** 通知事件类型。 */
  eventType: NotificationType
  /** 通知关联的业务资源 ID。 */
  resourceId: string
  /** 通知摘要，纯文本展示。 */
  summary: string
  /** 服务端创建时间；Asia/Shanghai。 */
  createdAt: string
  /** 已读时间，未读时缺省；Asia/Shanghai。 */
  readAt?: string
}
export type PostEngagement = {
  /** 服务端统计的点赞数。 */
  likeCount: number
  /** 服务端统计的可见评论数。 */
  commentCount: number
  /** 当前已认证用户是否已点赞。 */
  likedByMe: boolean
}
export type PostComment = {
  /** 业务标识字符串；雪花 ID 禁止转成 number。 */
  id: string
  /** 所属文章 ID 字符串。 */
  postId: string
  /** 用户 ID 字符串。 */
  userId: string
  /** 纯文本正文；列表投影可能不包含正文。 */
  body: string
  /** 服务端乐观锁版本，更新必须携带当前值。 */
  version: number
  /** 服务端创建时间；Asia/Shanghai。 */
  createdAt: string
}
export type ImagePurpose = 'AVATAR' | 'BANNER' | 'POST_COVER'
export type ManagedImage = {
  /** 业务标识字符串；雪花 ID 禁止转成 number。 */
  id: string
  /** 资产用途 AVATAR/BANNER/POST_COVER。 */
  purpose: ImagePurpose
  /** 服务端归一化媒体 MIME 类型。 */
  contentType: string
  /** 归一化图片大小，字节。 */
  byteSize: number
  /** 图片宽度，像素。 */
  width: number
  /** 图片高度，像素。 */
  height: number
}
export type ImageSlice = {
  /** 当前页数据，不代表全量结果。 */
  items: ManagedImage[]
  /** 下一页资产游标；没有下一页时为 null。 */
  nextCursor: string | null
}
export type ImageQuota = {
  /** 当前用户已占用字节数。 */
  usedBytes: number
  /** 当前用户允许占用的最大字节数。 */
  maxBytes: number
  /** 当前用户占用图片数量。 */
  usedImages: number
  /** 当前用户最大图片数量。 */
  maxImages: number
}

/** 登录响应仅消费用户投影，会话令牌由 HttpOnly Cookie 管理。 */
type LoginResult = { /** 当前已认证用户。 */ user: UserIdentity }
type NotificationPreference = {
  /** 用户配置的通知事件类型。 */
  eventType: NotificationType
  /** 是否接收该类型通知；不控制其他类型。 */
  enabled: boolean
}
/** 请求继承已注释的契约字段；写入时明确必填与版本约束。 */
type CommunityCreateRequest = Required<Pick<Community, 'slug' | 'name' | 'description' | 'badge'>>
type CommunityUpdateRequest = Required<Pick<Community, 'name' | 'description' | 'badge' | 'visibility' | 'version'>>
type PostWriteRequest = Required<Pick<CreatorPost, 'slug' | 'title' | 'excerpt' | 'body' | 'coverUrl'>> &
  Pick<CreatorPost, 'coverAssetId'>
type PostUpdateRequest = PostWriteRequest & Required<Pick<CreatorPost, 'version'>>
type LiveCreateRequest = Pick<LiveRoom, 'slug' | 'title' | 'category' | 'interactive'>
type VoiceCreateRequest = Required<Pick<VoiceRoom, 'slug' | 'title' | 'topic' | 'maxParticipants'>>

export const managedImageUrl = (id: string) => `${apiBaseUrl}/assets/images/${encodeURIComponent(id)}/content`

export const api = {
  imageQuota: (signal?: AbortSignal) => request<ImageQuota>('/assets/images/quota', { signal }),
  myImages: (purpose: ImagePurpose, before?: string, signal?: AbortSignal) =>
    request<ImageSlice>(
      `/assets/images?purpose=${purpose}&size=12${before ? `&before=${encodeURIComponent(before)}` : ''}`,
      { signal },
    ),
  uploadImage: (file: File, purpose: ImagePurpose, signal?: AbortSignal) => {
    const form = new FormData()
    form.append('purpose', purpose)
    form.append('file', file, file.name)
    return request<ManagedImage>('/assets/images', { method: 'POST', body: form, signal, timeoutMs: 90000 })
  },
  communities: (signal?: AbortSignal) => request<Community[]>('/discovery/communities?limit=12', { signal }),
  liveRooms: (signal?: AbortSignal) => request<LiveRoom[]>('/live/discovery?limit=12', { signal }),
  voiceRooms: (signal?: AbortSignal) => request<VoiceRoom[]>('/voice/rooms/discovery?limit=12', { signal }),
  creators: () => request<CreatorProfile[]>('/discovery/creators?limit=12'),
  posts: () => request<CreatorPost[]>('/discovery/posts?limit=12'),
  creatorPage: (page: number, size = 12, signal?: AbortSignal) =>
    request<Page<CreatorProfile>>(`/discovery/creators/page?page=${page}&size=${size}`, { signal }),
  postPage: (page: number, size = 12, signal?: AbortSignal) =>
    request<Page<CreatorPost>>(`/discovery/posts/page?page=${page}&size=${size}`, { signal }),
  following: (page: number, size = 12) =>
    request<Page<CreatorProfile>>(`/creators/following?page=${page}&size=${size}`),
  followState: (id: string) => request<CreatorFollowState>(`/creators/${id}/follow`),
  followCreator: (id: string) => request<CreatorFollowState>(`/creators/${id}/follow`, { method: 'PUT' }),
  unfollowCreator: (id: string) => request<CreatorFollowState>(`/creators/${id}/follow`, { method: 'DELETE' }),
  favorites: (page: number, size = 12) => request<Page<CreatorPost>>(`/content/favorites?page=${page}&size=${size}`),
  postFavoriteState: (id: string) => request<PostFavoriteState>(`/content/posts/${id}/favorite`),
  favoritePost: (id: string) => request<PostFavoriteState>(`/content/posts/${id}/favorite`, { method: 'PUT' }),
  unfavoritePost: (id: string) => request<PostFavoriteState>(`/content/posts/${id}/favorite`, { method: 'DELETE' }),
  notifications: (page: number, size = 20, signal?: AbortSignal) =>
    request<Page<NotificationItem>>(`/notifications?page=${page}&size=${size}`, { signal }),
  markNotificationRead: (id: string, signal?: AbortSignal) =>
    request<void>(`/notifications/${id}/read`, { method: 'PUT', signal }),
  notificationPreference: (type: NotificationType, signal?: AbortSignal) =>
    request<NotificationPreference>(`/notifications/preferences/${type}`, { signal }),
  saveNotificationPreference: (type: NotificationType, enabled: boolean, signal?: AbortSignal) =>
    request<NotificationPreference>(`/notifications/preferences/${type}`, {
      method: 'PUT',
      body: JSON.stringify({ enabled }),
      signal,
    }),
  post: (slug: string) => request<CreatorPost>(`/discovery/posts/${encodeURIComponent(slug)}`),
  postComments: (slug: string) =>
    request<PostComment[]>(`/discovery/posts/${encodeURIComponent(slug)}/comments?limit=50`),
  postCommentsPage: (slug: string, page: number, size = 20) =>
    request<Page<PostComment>>(`/discovery/posts/${encodeURIComponent(slug)}/comments/page?page=${page}&size=${size}`),
  me: () => request<UserIdentity>('/auth/me'),
  login: (email: string, password: string) =>
    request<LoginResult>('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    }),
  register: (email: string, password: string, handle: string, displayName: string) =>
    request<LoginResult>('/auth/register', {
      method: 'POST',
      body: JSON.stringify({ email, password, handle, displayName }),
    }),
  logout: () => request<void>('/auth/logout', { method: 'POST' }),
  createCommunity: (payload: CommunityCreateRequest) =>
    request<Community>('/communities', { method: 'POST', body: JSON.stringify(payload) }),
  myCommunities: () => request<Community[]>('/communities/mine'),
  updateCommunity: (id: string, payload: CommunityUpdateRequest) =>
    request<Community>(`/communities/${id}`, { method: 'PUT', body: JSON.stringify(payload) }),
  archiveCommunity: (id: string, version: number) =>
    request<void>(`/communities/${id}?version=${version}`, { method: 'DELETE' }),
  myCreatorProfile: (signal?: AbortSignal) => request<CreatorProfile>('/creators/me', { signal }),
  saveCreatorProfile: (payload: Omit<CreatorProfile, 'userId' | 'status'>) =>
    request<CreatorProfile>('/creators/me', { method: 'PUT', body: JSON.stringify(payload) }),
  publishCreatorProfile: (version: number) =>
    request<CreatorProfile>(`/creators/me/publish?version=${version}`, { method: 'POST' }),
  myPosts: (signal?: AbortSignal) => request<CreatorPost[]>('/content/posts/mine', { signal }),
  createPost: (payload: PostWriteRequest) =>
    request<CreatorPost>('/content/posts', { method: 'POST', body: JSON.stringify(payload) }),
  updatePost: (id: string, payload: PostUpdateRequest) =>
    request<CreatorPost>(`/content/posts/${id}`, { method: 'PUT', body: JSON.stringify(payload) }),
  publishPost: (id: string, version: number) =>
    request<CreatorPost>(`/content/posts/${id}/publish?version=${version}`, { method: 'POST' }),
  archivePost: (id: string, version: number) =>
    request<void>(`/content/posts/${id}?version=${version}`, { method: 'DELETE' }),
  postEngagement: (id: string) => request<PostEngagement>(`/content/posts/${id}/engagement`),
  likePost: (id: string) => request<PostEngagement>(`/content/posts/${id}/like`, { method: 'PUT' }),
  unlikePost: (id: string) => request<PostEngagement>(`/content/posts/${id}/like`, { method: 'DELETE' }),
  commentPost: (id: string, body: string) =>
    request<PostComment>(`/content/posts/${id}/comments`, {
      method: 'POST',
      body: JSON.stringify({ body }),
    }),
  deletePostComment: (postId: string, commentId: string, version: number) =>
    request<void>(`/content/posts/${postId}/comments/${commentId}?version=${version}`, { method: 'DELETE' }),
  createLive: (payload: LiveCreateRequest) =>
    request<LiveRoom>('/live', { method: 'POST', body: JSON.stringify(payload) }),
  createVoiceRoom: (payload: VoiceCreateRequest) =>
    request<VoiceRoom>('/voice/rooms', { method: 'POST', body: JSON.stringify(payload) }),
  joinVoiceRoom: (roomId: string, signal?: AbortSignal) =>
    request<VoiceJoinCredential>(`/voice/rooms/${roomId}/join`, { method: 'POST', signal, cache: 'no-store' }),
  closeVoiceRoom: (roomId: string) => request<void>(`/voice/rooms/${roomId}`, { method: 'DELETE' }),
}
