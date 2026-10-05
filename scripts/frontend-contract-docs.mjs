/** 前端契约字段注释审计；只输出 apply_patch 输入，不改写文件。 */
import fs from 'node:fs'
const file = 'web/src/api.ts'
const source = fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n')
const descriptions = {
  id: '业务标识字符串；雪花 ID 禁止转成 number',
  email: '登录邮箱，个人敏感信息',
  handle: '公开用户名',
  displayName: '公开显示名称',
  avatarUrl: '头像预览地址；未设置时缺省',
  slug: '公开访问路径标识',
  name: '业务名称',
  description: '业务说明；未设置时缺省',
  badge: '社区短徽标',
  members: '当前社区成员数',
  visibility: '公开或私有；权限由服务端独立判断',
  version: '服务端乐观锁版本，更新必须携带当前值',
  title: '业务标题',
  creator: '直播创建者显示名称',
  viewers: '供应商确认的观看人数，不伪造直播在线状态',
  category: '直播分类',
  interactive: '是否允许互动连麦',
  status: '服务端领域状态，不允许客户端自行转移',
  topic: '语音房主题；未设置时缺省',
  owner: '语音房创建者显示名称',
  maxParticipants: '语音房人数上限',
  url: 'LiveKit 公开连接地址；正式运营使用 TLS',
  token: '限房间的短期入会令牌，禁止日志和持久化',
  roomName: '媒体供应商房间名称',
  userId: '用户 ID 字符串',
  headline: '创作者主页短介绍',
  bio: '创作者个人简介',
  bannerUrl: '封面预览地址；未设置时缺省',
  avatarAssetId: '已验证归属的头像资产 UUID；未绑定时缺省',
  bannerAssetId: '已验证归属的封面资产 UUID；未绑定时缺省',
  followerCount: '服务端统计的粉丝数',
  ownerId: '资源所有者 ID 字符串',
  excerpt: '文章摘要',
  body: '纯文本正文；列表投影可能不包含正文',
  coverUrl: '文章封面预览地址；未设置时缺省',
  coverAssetId: '已验证归属的文章封面资产 UUID；未绑定时缺省',
  kind: '内容类型，当前仅 ARTICLE',
  publishedAt: '正式发布时间，未发布时缺省；Asia/Shanghai',
  updatedAt: '服务端最后修改时间；Asia/Shanghai',
  likeCount: '服务端统计的点赞数',
  commentCount: '服务端统计的可见评论数',
  favoriteCount: '服务端统计的收藏数',
  items: '当前页数据，不代表全量结果',
  page: '从 1 开始的页码',
  size: '每页条数，受服务端上限约束',
  total: '查询结果总条数',
  creatorId: '创作者用户 ID 字符串',
  followedByMe: '当前已认证用户是否已关注',
  favoritedByMe: '当前已认证用户是否已收藏',
  actorId: '通知触发者用户 ID 字符串',
  eventType: '通知事件类型',
  resourceId: '通知关联的业务资源 ID',
  summary: '通知摘要，纯文本展示',
  createdAt: '服务端创建时间；Asia/Shanghai',
  readAt: '已读时间，未读时缺省；Asia/Shanghai',
  likedByMe: '当前已认证用户是否已点赞',
  postId: '所属文章 ID 字符串',
  purpose: '资产用途 AVATAR/BANNER/POST_COVER',
  contentType: '服务端归一化媒体 MIME 类型',
  byteSize: '归一化图片大小，字节',
  width: '图片宽度，像素',
  height: '图片高度，像素',
  nextCursor: '下一页资产游标；没有下一页时为 null',
  usedBytes: '当前用户已占用字节数',
  maxBytes: '当前用户允许占用的最大字节数',
  usedImages: '当前用户占用图片数量',
  maxImages: '当前用户最大图片数量',
}
const missing = []
const updated = source.replace(/export type [A-Za-z]+(?:<T>)? = \{([\s\S]*?)\}/g, (original, body) => {
  if (body.includes('/**')) return original
  const fields = [...body.matchAll(/([A-Za-z]+)(\??):\s*([^;\r\n]+)/g)]
  return (
    original.slice(0, original.indexOf('{')) +
    '{\n' +
    fields
      .map(([, name, optional, type]) => {
        if (!descriptions[name]) missing.push(name)
        return `  /** ${descriptions[name] || '需要人工补充字段语义'}。 */\n  ${name}${optional}: ${type.trim()}\n`
      })
      .join('') +
    '}'
  )
})
if (missing.length) {
  process.stderr.write(JSON.stringify({ missing }))
  process.exit(1)
}
const patch =
  updated === source
    ? null
    : `*** Begin Patch\n*** Update File: ${file}\n@@\n${source
        .split('\n')
        .map((line) => '-' + line)
        .join('\n')}\n${updated
        .split('\n')
        .map((line) => '+' + line)
        .join('\n')}\n*** End Patch`
process.stdout.write(JSON.stringify({ patch, missing }))
