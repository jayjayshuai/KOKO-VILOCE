/** 注释覆盖审计/迁移工具：仅生成 apply_patch 输入，不直接改写源码。 */
import fs from 'node:fs'
import path from 'node:path'
const root = path.resolve('backend')
const words = {
  id: '唯一业务标识；HTTP 用户/雪花 ID 使用字符串表示',
  ownerId: '资源所有者用户 ID',
  userId: '操作用户 ID',
  actorId: '触发事件的用户 ID',
  recipientId: '通知接收用户 ID',
  postId: '所属文章 ID',
  communityId: '所属社区 ID',
  creatorId: '创作者用户 ID',
  email: '登录邮箱，个人敏感信息',
  password: '登录密码，仅输入使用，禁止日志输出',
  passwordHash: 'BCrypt 密码摘要，禁止外部序列化与日志输出',
  handle: '公开用户名，用于精确查询',
  displayName: '用户公开显示名称',
  title: '业务标题',
  name: '业务名称',
  description: '业务说明',
  badge: '社区短徽标',
  slug: '公开访问路径标识',
  visibility: '资源可见范围，PUBLIC 或 PRIVATE',
  status: '业务状态，允许值以所属领域状态机为准',
  version: '乐观锁版本，修改必须携带当前值',
  createdAt: '服务端创建时间，数据库时区 Asia/Shanghai',
  updatedAt: '服务端最后修改时间，Asia/Shanghai',
  publishedAt: '正式发布时间；未发布为空，Asia/Shanghai',
  closedAt: '关闭时间；未关闭为空，Asia/Shanghai',
  joinedAt: '加入时间，Asia/Shanghai',
  readyAt: '下次可执行时间，Asia/Shanghai',
  readAt: '已读时间；未读为空，Asia/Shanghai',
  startedAt: '开始时间；未开始为空，Asia/Shanghai',
  endedAt: '结束时间；未结束为空，Asia/Shanghai',
  avatarUrl: '公开头像地址',
  bannerUrl: '公开横幅地址',
  coverUrl: '公开封面地址',
  avatarAssetId: '经归属验证的头像资产 UUID',
  bannerAssetId: '经归属验证的横幅资产 UUID',
  coverAssetId: '经归属验证的封面资产 UUID',
  headline: '创作者主页短介绍',
  bio: '创作者个人简介',
  body: '纯文本正文，不解释 HTML',
  excerpt: '内容摘要',
  kind: '领域资源类型',
  role: '成员角色',
  category: '内容分类',
  followerCount: '关注人数',
  memberCount: '成员人数',
  viewerCount: '观看人数',
  likeCount: '点赞总数',
  commentCount: '评论总数',
  favoriteCount: '收藏总数',
  maxParticipants: '房间人数上限',
  interactive: '是否允许互动连麦',
  topic: '主题或消息队列 Topic，具体见所属类型',
  creatorName: '创作者名称快照',
  ownerName: '所有者名称快照',
  eventId: '事件幂等 ID',
  eventType: '通知事件类型',
  resourceId: '事件关联的业务资源 ID',
  summary: '通知摘要',
  attempts: '已尝试执行次数',
  followerCursor: '关注者扇出游标，单调前进',
  provider: '真实媒体供应商名称',
  providerInputId: '媒体供应商输入 ID，不代表已经开播',
  providerRoomName: '供应商房间名称',
  contentType: '归一化媒体 MIME 类型',
  byteSize: '归一化资源字节数',
  width: '图像像素宽度',
  height: '图像像素高度',
  objectKey: '内部对象存储键，禁止直接暴露给客户端',
  purpose: '图片用途，AVATAR/BANNER/POST_COVER',
  sha256: '归一化内容 SHA-256 摘要',
  imageCount: '占用的图片数量，包含待清理预留',
  apiKey: '供应商 API 标识，禁止日志输出',
  apiSecret: '供应商密钥，禁止序列化和日志输出',
  expectedKey: '可信网关内部密钥字节，常量时间比较',
  internalKey: '网关到下游的内部密钥，禁止日志输出',
  bucket: '专用对象存储桶名称',
  publicUrl: '客户端可访问的媒体服务地址',
  assetPublicBase: '受管图片公开读取地址前缀',
  nameServer: 'RocketMQ Nameserver 地址',
  producerGroup: '生产者组名称',
  group: '消费者组名称',
  ownerBytes: '单用户图片字节数额度',
  ownerImages: '单用户图片数量额度',
  totalBytes: '全局图片字节数额度',
  totalImages: '全局图片数量额度',
  sharedCapacity: '是否采用共享额度行',
  uploadSlot: '单实例上传并发槽，控制图像解码内存峰值',
  outstanding: '待投递数量；-1 表示未成功采集',
  dead: '永久失败数量；-1 表示未成功采集',
  oldestAgeSeconds: '最旧待处理事件年龄，秒',
  telemetryUp: '遥测查询是否成功，1 成功/0 失败',
  consumeFailures: '消费者失败计数指标',
  meterRegistry: '监控指标注册器',
  tokenName: '会话令牌的 Cookie/请求头名称',
  tokenValue: '会话令牌，仅认证响应；禁止写日志或本地存储',
  expiresIn: '令牌有效期，秒',
  page: '页码，从 1 开始',
  size: '单页条数，受接口最大值限制',
  total: '匹配条件的总条数',
  items: '当前页的业务投影列表',
  nextCursor: '下一页游标；无更多记录时为空',
  before: '向前分页游标',
  after: '向后补拉游标',
  limit: '查询条数上限',
  likedByMe: '当前用户是否已点赞',
  favoritedByMe: '当前用户是否已收藏',
  followedByMe: '当前用户是否已关注',
  enabled: '是否启用对应业务能力',
  maxBytes: '允许的最大字节数',
  usedBytes: '已占用字节数，含未清理预留',
  maxImages: '允许的最大图片数量',
  usedImages: '已占用图片数量',
  event: '业务事件投影',
  traceId: '请求追踪标识，不含密钥',
  code: '稳定的业务错误码',
  message: '业务提示消息，不输出内部堆栈',
  capacity: '限流窗口允许的请求数',
  bucketName: '限流维度名称',
  strict: '依赖不可用时是否拒绝请求',
  user: '用户公开响应投影',
  url: '客户端连接地址',
  token: '短期连接凭据，禁止日志输出',
  roomName: '媒体房间名称',
  bytes: '归一化媒体内容字节数组，禁止写日志',
  extension: '受支持的媒体文件扩展名',
  members: '会话当前成员投影列表',
  failClosed: '限流依赖不可用时是否拒绝请求',
  timestamp: '服务端响应时间',
  passwordEncoder: 'BCrypt 密码编码器，当前工作因子 12',
  creator: '创作者公开显示名称',
  viewers: '当前观看人数',
  assetId: '经归属验证的受管媒体资产 UUID',
  legacyUrl: '旧版本外部图片地址，仅用于兼容读取',
  owner: '资源所有者公开名称',
}
const methodWords = {
  register: '注册账号',
  login: '账号登录',
  logout: '注销当前会话',
  me: '读取本人账号',
  create: '创建业务资源',
  list: '分页读取资源',
  detail: '读取资源详情',
  mine: '读取本人资源',
  update: '更新本人资源',
  delete: '删除业务资源',
  archive: '归档本人资源',
  join: '加入房间或社区',
  leave: '退出房间或社区',
  publish: '发布本人资源',
  save: '保存本人业务配置',
  start: '开始业务会话',
  end: '结束业务会话',
  close: '关闭业务资源',
  follow: '关注创作者',
  unfollow: '取消关注',
  following: '读取本人关注',
  favorite: '收藏文章',
  unfavorite: '取消收藏',
  favorites: '读取本人收藏',
  comment: '发表评论',
  comments: '读取评论',
  removeComment: '删除有权操作的评论',
  engagement: '读取互动状态',
  like: '点赞文章',
  unlike: '取消点赞',
  read: '标记本人已读',
  preferences: '读取通知偏好',
  updatePreferences: '更新通知偏好',
}
function describe(name, type) {
  if (words[name]) return words[name]
  if (/Logger/.test(type)) return '本类诊断日志，禁止输出密码、令牌和业务正文'
  if (/Pattern/.test(type)) return '服务端格式校验规则，禁止客户端覆盖'
  if (/RedisScript/.test(type)) return 'Redis 原子限流脚本，计数与过期同时设置'
  if (/Duration/.test(type)) return '限流时间窗口'
  if (/RpcService/.test(type)) return `${type.replace(/.*\./, '')} 跨服务契约代理，不直接读取其他服务数据库`
  if (/Mapper/.test(type)) return `${type} 持久化映射器，复杂 SQL 使用 XML`
  if (/Service/.test(type)) return `${type} 业务用例依赖，事务由 Spring 代理管理`
  if (/ObjectMapper/.test(type)) return 'JSON 序列化器，沿用统一时间和字段配置'
  if (/Redis/.test(type)) return 'Redis 客户端，访问必须设置超时并明确故障策略'
  if (/Client|Producer|Consumer|Gateway|Storage|Inspector|Validator|Writer|Sender|Lookup/.test(type))
    return `${type} 外部或领域适配器，失败不伪装为业务成功`
  if (/^[A-Z_]+$/.test(name)) return `${name} 服务端协议常量，不接受客户端覆盖`
  return `${name}（${type}），供当前领域类型使用；具体约束见业务接口`
}
function walk(dir) {
  return fs
    .readdirSync(dir, { withFileTypes: true })
    .flatMap((e) =>
      e.isDirectory()
        ? e.name === 'target'
          ? []
          : walk(path.join(dir, e.name))
        : e.name.endsWith('.java')
          ? [path.join(dir, e.name)]
          : [],
    )
}
const patches = []
const fallbacks = new Set()
for (const file of walk(root).filter(
  (f) =>
    f.includes(`${path.sep}src${path.sep}main${path.sep}`) && (!process.argv[2] || path.resolve(process.argv[2]) === f),
)) {
  const original = fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n')
  let content = original
  // 仅移除完全平凡的实体访问器，@Getter/@Setter 不生成 toString，避免泄露摘要与密钥。
  if (content.includes('@TableName') && !/@(?:lombok\.)?Getter/.test(content)) {
    content = content
      .replace(/\n    public [\w<>]+ get\w+\(\) \{ return \w+; \}/g, '')
      .replace(/\n    public void set\w+\([\w<>]+ \w+\) \{ this\.\w+ = \w+; \}/g, '')
    content = content.replace(/(@TableName\()/, '@lombok.Getter\n@lombok.Setter\n$1')
  }
  content = content.replace(/(?:@lombok.Getter\n@lombok.Setter\n)+/g, '@lombok.Getter\n@lombok.Setter\n')
  const lines = content.split('\n')
  for (let i = 0; i < lines.length; i++) {
    const field = lines[i].match(
      /^(\s+)(?:private|protected|public)\s+(?:static\s+)?(?:final\s+)?([\w<>., ?\[\]]+)\s+(\w+)\s*(?:=|;)/,
    )
    if (field) {
      let at = i
      while (at > 0 && lines[at - 1].trim().startsWith('@')) at--
      if (!lines[at - 1]?.trim().endsWith('*/')) {
        const description = describe(field[3], field[2].trim())
        if (
          !words[field[3]] &&
          !/Mapper|Service|Logger|Pattern|Script|Duration|Rpc|ObjectMapper|Redis|Client|Producer|Consumer|Gateway|Storage|Inspector|Validator|Writer|Sender|Lookup/.test(
            field[2],
          ) &&
          !/^[A-Z_]+$/.test(field[3])
        )
          fallbacks.add(`${field[3]}:${field[2]}`)
        lines.splice(at, 0, `${field[1]}/** ${description}。 */`)
        i++
      }
    }
  }
  content = lines.join('\n')
  // 遍历 record 的平衡括号，仅改组件声明，避免误碰方法参数。
  let from = 0
  while (true) {
    const match = /\brecord\s+\w+\s*\(/g
    match.lastIndex = from
    const m = match.exec(content)
    if (!m) break
    const start = m.index + m[0].length
    let depth = 1,
      end = start,
      quote = false,
      escaped = false
    for (; end < content.length; end++) {
      const c = content[end]
      if (quote) {
        if (escaped) escaped = false
        else if (c === '\\') escaped = true
        else if (c === '"') quote = false
        continue
      }
      if (c === '"') {
        quote = true
        continue
      }
      if (c === '(') depth++
      if (c === ')' && --depth === 0) break
    }
    // 上面减深度仅针对右括号。
    if (end >= content.length) break
    const params = content.slice(start, end)
    let parts = [],
      last = 0,
      paren = 0,
      angle = 0,
      q = false,
      esc = false
    for (let p = 0; p < params.length; p++) {
      const c = params[p]
      if (q) {
        if (esc) esc = false
        else if (c === '\\') esc = true
        else if (c === '"') q = false
        continue
      }
      if (c === '"') {
        q = true
        continue
      }
      if (c === '(') paren++
      if (c === ')') paren--
      if (c === '<') angle++
      if (c === '>') angle--
      if (c === ',' && !paren && !angle) {
        parts.push(params.slice(last, p))
        last = p + 1
      }
    }
    parts.push(params.slice(last))
    const amended = parts
      .map((part) => {
        if (part.includes('@Schema') || part.includes('@io.swagger.v3.oas.annotations.media.Schema')) return part
        const name = part.match(/([A-Za-z]\w*)\s*$/)?.[1]
        if (!name) return part
        const description = describe(name, '响应/请求字段')
        if (!words[name]) fallbacks.add(`record:${name}`)
        return part.replace(
          /^(\s*)/,
          `$1@io.swagger.v3.oas.annotations.media.Schema(description = ${JSON.stringify(description)}) `,
        )
      })
      .join(',')
    content = content.slice(0, start) + amended + content.slice(end)
    from = start + amended.length + 1
  }
  if (
    content.includes('@RestController') &&
    !content.includes('@RestControllerAdvice') &&
    !content.includes('@Tag(') &&
    !content.includes('annotations.tags.Tag')
  )
    content = content.replace(
      '@RestController',
      `@io.swagger.v3.oas.annotations.tags.Tag(name = "${path.basename(file, '.java')}")\n@RestController`,
    )
  if (content.includes('@RestController') && !content.includes('@RestControllerAdvice'))
    content = content.replace(
      /((?:    @[^\n]+\n)+)(    public (?!record\b|class\b|interface\b)[\s\S]*?\b(\w+)\([^;]*?\)\s*\{)/g,
      (all, annotations, declaration, name) =>
        annotations.includes('@Operation') || annotations.includes('annotations.Operation')
          ? all
          : `${annotations}    @io.swagger.v3.oas.annotations.Operation(summary = "${methodWords[name] || name + ' 业务接口'}")\n${declaration}`,
    )
  content = content.replace(/    @io.swagger.v3.oas.annotations.Operation\([^\n]+\)\n(?=    public record)/g, '')
  if (content.includes('@RestControllerAdvice'))
    content = content.replace(/\s*@io.swagger.v3.oas.annotations.Operation\([^\n]+\)\n/g, '\n')
  const summaries = {
    discover: '读取公开创作者',
    discoverPage: '分页读取公开创作者',
    published: '读取已发布主页',
    followState: '读取关注状态',
    page: '分页读取本人通知',
    markRead: '标记本人通知已读',
    preference: '读取本人通知偏好',
    savePreference: '保存本人通知偏好',
  }
  for (const [method, summary] of Object.entries(summaries)) content = content.replaceAll(`${method} 业务接口`, summary)
  content = content
    .replace(
      /@RequestHeader\("X-Koko-User-Id"\)/g,
      '@io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id")',
    )
    .replace(
      /@Parameter\(hidden = true\) @io.swagger.v3.oas.annotations.Parameter\(hidden = true\)/g,
      '@Parameter(hidden = true)',
    )
  content = content.replace(
    /(?:@io.swagger.v3.oas.annotations.Parameter\(hidden = true\) )+/g,
    '@io.swagger.v3.oas.annotations.Parameter(hidden = true) ',
  )
  const declarations = content.split('\n')
  for (let i = 0; i < declarations.length; i++) {
    const decl = declarations[i].match(/^(\s*)(?:public\s+)?(?:final\s+)?(?:class|interface|enum|record)\s+(\w+)/)
    if (!decl) continue
    let at = i
    // 映射路径里的 {id} 是注解字符串，不应被误判为类体并制造重复 JavaDoc。
    while (at > 0 && /^\s*@[\w.]+(?:\([^;]*\))?\s*$/.test(declarations[at - 1])) at--
    if (declarations[at - 1]?.trim().endsWith('*/')) continue
    const name = decl[2]
    const domain = file.split(path.sep).find((p) => p.endsWith('-service')) || '平台公共契约'
    const purpose = /Controller$/.test(name)
      ? 'HTTP 业务边界；身份校验与归属决策由网关及业务用例共同执行'
      : /Mapper$/.test(name)
        ? '持久化映射；值参数绑定，复杂查询在 XML'
        : /Service$/.test(name)
          ? '业务用例；涉及写入时遵守领域事务与权限约束'
          : /Request$|Command$/.test(name)
            ? '请求契约；字段校验以公开接口约束为准'
            : /Exception$/.test(name)
              ? '领域失败语义；外部接口映射为对应状态码，不伪装成功'
              : /Configuration$/.test(name)
                ? '服务配置；不在源码内保存生产密钥'
                : `${name} 领域类型；字段单位、状态及可空性见各属性说明`
    declarations.splice(at, 0, `${decl[1]}/** ${domain}：${purpose}。 */`)
    i++
  }
  content = declarations.join('\n')
  if (content !== original)
    patches.push({
      file,
      patch: `*** Begin Patch\n*** Update File: ${file.replaceAll('\\', '/')}\n@@\n${original
        .split('\n')
        .map((l) => '-' + l)
        .join('\n')}\n${content
        .split('\n')
        .map((l) => '+' + l)
        .join('\n')}\n*** End Patch`,
    })
}
process.stdout.write(
  JSON.stringify({
    patches: process.argv[2] ? patches : patches.map((p) => ({ file: p.file })),
    fallbacks: [...fallbacks],
  }),
)
