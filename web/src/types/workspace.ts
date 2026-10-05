import type { Community, CreatorPost, CreatorProfile, LiveRoom, VoiceRoom } from '../api'

/** 公开页面领域选择，不代表服务端权限。 */
export type PublicSection = 'explore' | 'communities' | 'creators' | 'voice' | 'live'
/** 已实现用例入口；未实现能力不能加入可成功的操作清单。 */
export type WorkspaceAction = 'post' | 'profile' | 'community' | 'community-manage' | 'voice' | 'live'

/** 发现接口实际返回的快照；采样列表数与分页总数明确区分。 */
export interface DiscoverySnapshot {
  /** 当前最多 12 条公开社区，不是全站社区总量。 */
  communities: Community[]
  /** 当前最多 12 条已确认直播。 */
  liveRooms: LiveRoom[]
  /** 当前最多 12 条开放语音房。 */
  voiceRooms: VoiceRoom[]
  /** 已读取的公开创作者分页。 */
  creators: CreatorProfile[]
  /** 已读取的公开文章分页。 */
  posts: CreatorPost[]
  /** 创作者分页接口实际总量。 */
  creatorTotal: number
  /** 文章分页接口实际总量。 */
  postTotal: number
  /** 成功读取五个公开接口的客户端时间；失败没有新时间。 */
  loadedAt: string | null
}

/** 路由可读信息，身份限制由服务端和页面请求条件共同执行。 */
export interface WorkspacePage {
  /** 唯一路由名。 */
  name: string
  /** Hash 路由路径，兼容现有 /koko/ 子路径。 */
  path: string
  /** 导航与页面标题。 */
  title: string
  /** 对用户解释页面用途，不作伪造能力承诺。 */
  description: string
  /** 导航分组。 */
  group: 'space' | 'creator' | 'collaboration' | 'account'
  /** 是否要求恢复完成的真实会话。 */
  requiresAuth: boolean
  /** 公开页面域，非公开页面不存在。 */
  section?: PublicSection
}
