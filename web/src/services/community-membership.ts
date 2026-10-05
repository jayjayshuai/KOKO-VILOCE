import { request, type Community } from '../api'

export type MembershipStatus = {
  /** 服务端授权可见的社区，不接收客户端所有者身份。 */
  community: Community
  /** 本人当前角色；null 表示尚未加入。 */
  role: 'OWNER' | 'MEMBER' | null
}
export type CommunityMember = {
  /** 雪花用户 ID 始终使用字符串。 */
  userId: string
  /** 服务端当前成员角色。 */
  role: 'OWNER' | 'MEMBER'
  /** 入会时间 Asia/Shanghai。 */
  joinedAt: string
  /** 入会公开用户名快照，旧记录可能为空。 */
  handle: string | null
  /** 入会显示名称快照，不保证随资料变化同步，旧记录可能为空。 */
  displayName: string | null
}
export type MemberSlice = {
  /** 当前有权读取的本批成员。 */
  items: CommunityMember[]
  /** 独占用户 ID 上界，末页为 null。 */
  nextBefore: string | null
}
export type JoinedCommunitySlice = {
  /** 当前用户 ACTIVE 社区，不是全量列表。 */
  items: Community[]
  /** 独占社区 ID 上界，末页为 null。 */
  nextBefore: string | null
}
const communityPath = (id: string) => `/communities/${encodeURIComponent(id)}`
export const communityMembershipApi = {
  status: (id: string, signal?: AbortSignal) =>
    request<MembershipStatus>(`${communityPath(id)}/membership`, { signal }),
  joined: (before?: string, signal?: AbortSignal) =>
    request<JoinedCommunitySlice>(
      `/communities/joined?size=20${before ? `&before=${encodeURIComponent(before)}` : ''}`,
      { signal },
    ),
  members: (id: string, before?: string, signal?: AbortSignal) =>
    request<MemberSlice>(
      `${communityPath(id)}/members?size=20${before ? `&before=${encodeURIComponent(before)}` : ''}`,
      { signal },
    ),
  join: (id: string, signal?: AbortSignal) =>
    request<void>(`${communityPath(id)}/membership`, { method: 'PUT', signal }),
  leave: (id: string, signal?: AbortSignal) =>
    request<void>(`${communityPath(id)}/membership`, { method: 'DELETE', signal }),
  remove: (id: string, targetId: string, signal?: AbortSignal) =>
    request<void>(`${communityPath(id)}/members/${encodeURIComponent(targetId)}`, { method: 'DELETE', signal }),
}
