# KOKO Nexus（可可星联）

面向创作者、社群与实时互动场景的 Java 主导微服务平台。当前主干提供真实账号认证、UP 主主页、文章草稿与发布、幂等点赞与评论治理、关注与收藏、通知收件箱、私有图片资产上传、社区创建/发现/成员管理、直播排期、受约束状态流转、自建 LiveKit 语音房，以及 Netty 私信与群聊；直播转码/CDN 与 Discord OAuth 尚未接入，不会以假地址或假成功替代。

## 架构

- Java 21、Spring Boot 3.5、Spring Cloud Gateway
- Sa-Token + Redis：网关统一会话与鉴权
- Nacos：服务发现与 Dubbo 注册中心
- Dubbo：身份服务内部 RPC
- MyBatis-Plus + MySQL + Flyway：持久化与版本化迁移
- Netty：聊天 WebSocket；Lombok：实体访问器；Knife4j + OpenAPI3：登录后接口文档
- Vue 3 + Router + Pinia + TypeScript：十二个领域页面的 Web 工作台
- Docker Compose：复用目标服务器现有 MySQL、Redis、Nacos

服务边界：

- `gateway-service`：公网唯一 API 入口，清理并重建可信用户头
- `identity-service`：账号、密码哈希、UP 主资料与身份查询（仅 Dubbo）
- `community-service`：社区、成员关系与创作者文章内容域
- `live-service`：直播排期和所有权约束状态机
- `voice-service`：语音房生命周期、LiveKit 房间编排和短期入会凭证
- `notification-service`：Outbox 事件消费、通知收件箱、用户偏好与直播粉丝扇出
- `asset-service`：JPEG/PNG 校验、MinIO 存储、本人图片库、用途/所有权绑定、公开读取授权、全状态引用核验、事务配额、上传处理并发限制及未完成上传清理；全状态核验本批仅源码，预签名 URL 与 READY 孤儿清理尚未完成
- `chat-service`：Netty 私信/群聊、事务消息序号、幂等 ACK、游标历史/已读、会话内字面搜索、个人消息收藏、群管理、拉黑、真实消息举报与权限隔离的人工结案；当前仅单实例，扩容前必须补跨节点分发
- `service-api`：跨服务 RPC 契约
- `platform-common`：统一错误响应
- `event-outbox`：业务事务内事件登记与可靠投递

更完整的交付边界和验收标准见 [实施计划](docs/IMPLEMENTATION_PLAN.md) 与 [企业级交付路线](docs/ENTERPRISE_ROADMAP.md)，部署、备份与回滚要求见 [运行手册](docs/OPERATIONS.md)。

登录后的「私信 / 群聊」入口提供消息中心；Knife4j 位于 `/koko-api/chat/docs/doc.html`，聚合七组实际 HTTP 契约。Netty 帧协议和群历史边界见 [聊天协议](docs/CHAT_PROTOCOL.md)，发布注意事项见 [聊天运行手册](docs/CHAT_RELEASE_RUNBOOK.md)。公网 HTTP 仅用于阶段验收，正式运营须先配置 HTTPS/WSS。

消息中心「安全中心」提供本人拉黑设置和举报进度；消息旁的「举报」只针对已提交的他人消息。审核权限默认关闭，须由负责人指定账号；结案不自动封禁，初期服务器 ID 白名单不是完整运营 RBAC。实现与上线边界见 [聊天安全阶段](docs/CHAT_SAFETY_PLAN.md)。

会话中「搜索 / 消息收藏」提供有界字面检索、个人收藏/取消、分页与清空。每批扫描最多 2000 个序号，空批仍可能有更早游标；收藏每人每会话最多 1000 个引用，不复制正文、不绕过移除/重加入的历史权限。工具面板不自动将隐藏聊天尾部标记已读。见 [历史工具计划](docs/CHAT_HISTORY_PLAN.md) 和 [本轮验收证据](docs/CHAT_HISTORY_VERIFICATION_20261002.md)。

## 本地验证

2026-10-05 按[语音房方向](backend/VOICE_ROOM_PLAN.md)优先推进P0：可信网关/身份校验与
入会no-store、前端默认不开麦/取消/会话围栏、媒体定向清理及公网HTTPS/WSS门槛已补源码。
投递异常保留有界安全cause链，不复制原始消息；未发布，麦位、房间事件、多节点与上线门槛仍缺。
`community_channel`仅预留表，未实现频道/角色矩阵；现有运营密码二次确认不代表双人审批。
详见[语音实施计划](docs/VOICE_ROOM_IMPLEMENTATION_PLAN.md)和[本批验证](docs/VOICE_ROOM_P0_VERIFICATION_20261005.md)。

语音P0投递续批已在真实MySQL/隔离Broker验证预算、并发、停顿恢复及SQL确认故障；
实际消息索引证明故障窗口会重复发送，不能冒充恰好一次。资源备份后回收，未发布。
见[投递验证](docs/VOICE_ROOM_OUTBOX_VERIFICATION_20261005.md)。

2026-10-05 最新源码补了两域绑定释放人工恢复：独立权限/密码确认、同事务受理/审计、
有限代次与累计计数保护，前端支持原命令查询/幂等重试、刷新重录和只读审计。
313项后端、126项前端、构建/格式、实际两域MySQL及独立Triple传输通过。
后续实际四Boot/两域Provider、Nacos/Dubbo/Redis/三库的8组网络检查通过，隔离备份与清理已复核。
未发布或真实赋权；授权UI、备份恢复、TLS与发布门槛仍缺，生产开关保持关闭。
见[最新验证](docs/ASSET_BINDING_RECOVERY_VERIFICATION_20261005.md)和[运行手册](docs/ASSET_BINDING_RECOVERY_RUNBOOK.md)。
本次网络边界、冷启动重试及浏览器工具故障见[完整网络记录](docs/ASSET_BINDING_RECOVERY_NETWORK_VERIFICATION_20261005.md)。

2026-10-04 最新源码补了绑定释放只读运维：身份/社区独立读权限、微秒 DEAD 游标页、
当前任务详情、有界状态指标和五条告警草案，前端提供独立排障工作台。
291 项后端、109 项前端、打包/格式检查及两域实际 MySQL 通过；未发布、无真实账号赋权，
人工重放/授权 UI/新 RPC 全链路与告警送达仍缺。详见
[最新验证与发布边界](docs/ASSET_BINDING_OBSERVABILITY_VERIFICATION_20261004.md)。

同日后续源码补了已提交绑定的持久化释放重试和永久完成标记，防止回复丢失/迟到请求复活保护。
277 项后端、全量打包及三库实际事务/SQL 验证通过；补偿调度默认关闭、未发布，
未知记录和 DEAD 运维仍需完善。见 [绑定释放补偿记录](docs/ASSET_BINDING_RELEASE_VERIFICATION_20261004.md)。

本轮新增资产持久化绑定保护，资料/动态在事务完成前保留意图，未知结果不释放，尚未发布。
Java 和前端源码已统一排版，可在根目录执行 `npm ci --ignore-scripts`、`npm run format:check`；
完整工具命令见 [代码格式说明](docs/CODE_FORMATTING.md)，本轮 264 项后端/96 项前端、
真实隔离 MySQL 及未完成门槛见 [绑定保护与格式化记录](docs/ASSET_BINDING_FORMATTING_VERIFICATION_20261004.md)。

2026-10-04 素材中心和资产 API 已在源码增加全状态引用核验，不将草稿/归档图片视为无引用，
也不将查询失败或快照当成安全删除许可；旧公开读取授权保持独立。两个隔离限权 MySQL
库已验证两域各12份 Flyway 及真实 MP XML。完整新接口网络、浏览器点击及发布仍待补验，
READY 自动删除未开启，AI 短剧继续下线。见 [开发与验证边界](docs/ASSET_REFERENCES_VERIFICATION_20261004.md)。

2026-10-03 通知可靠性在独立 Broker/限权 MySQL 库中完成真实中断恢复、确认失败重复、
消费事务回滚/Broker 重投与租约 CAS 验证，两端使用线上自有客户端依赖；生产服务未重启，
AI 短剧保持下线。局部验收、失败记录与尚未完成的死信运维/恢复/容量门槛见
[通知演练证据](docs/NOTIFICATION_RELIABILITY_VERIFICATION_20261003.md)。

同日新增 DEAD 单事件重放核心与同库追加审计，在三套隔离 MySQL 库验证并发幂等、
审计/唯一键失败回滚、累计尝试和旧租约；后续持久 RBAC/二次确认/角色审计代码通过真实 MySQL
验收，Sa-Token HTTP 行为与全模块 182 项测试通过。实际 Dubbo/Redis、三域重放接入和运营页面
尚未完成，没有发布生产重放入口。见 [权限开发验收](docs/OPERATIONS_AUTHORITY_VERIFICATION_20261003.md)、
[实施计划](docs/OUTBOX_REPLAY_PLAN.md) 与
[核心 SQL 证据](docs/OUTBOX_REPLAY_CORE_VERIFICATION_20261003.md)。

后续已补三域运维 RPC/HTTP、独立 Vue 运营页、微秒游标、原请求查询和同命令重试状态；
201 项后端、69 项前端测试、构建和三域 SQL/索引计划验证通过。严格序列化契约和白名单
以实际 Dubbo 编解码验证；真实网络联调/正向 UI/初始化/发布仍未完成，公网敏感操作不开放。
失败轮次和完整边界见 [运维前后端开发记录](docs/OUTBOX_OPERATIONS_DEVELOPMENT_20261003.md)。

后续离线首管理员工具/身份 V11 已完成限权 MySQL 并发、回滚和独立 CLI 验收，
全模块 215 项通过；三组 direct-loopback Triple 传输通过，但不代替 Nacos/Redis/Spring 全链路。
未为真实账号赋权或发布运营入口。见 [初始化证据](docs/OPERATIONS_BOOTSTRAP_VERIFICATION_20261003.md)
与 [负责人操作手册](docs/OPERATIONS_BOOTSTRAP_RUNBOOK.md)。
进一步已把真实身份 RPC Provider、权限/确认事务和隔离 MySQL 的单次重放/审计失败回滚/撤权
经环回 Triple 串联验收；未验证社区/直播第二跳、完整 Nacos/Redis/HTTP/Broker/UI，不等同上线。

2026-10-04 后续已完成三域独立限权库、生产 Spring 配置/事务代理/Provider 与社区/直播
身份第二跳 Triple 验证，失败轮次和原始 SQL/输入记录保留并清理临时资源；不是完整 Boot
注册发现或 Broker/UI 验收。见 [三域证据](docs/THREE_DOMAIN_OPERATIONS_VERIFICATION_20261004.md)。
网关同步 Sa-Token 鉴权已移入有界专用池，饱和/基础设施故障返回 503，不阻塞 Netty
EventLoop；当前全模块 223 项通过，尚未部署或验收真实 Redis。
见 [鉴权线程验证](docs/GATEWAY_AUTHENTICATION_VERIFICATION_20261004.md)。AI 短剧保持下线。

同日已补四个完整 Boot / 独立 Nacos / Redis / HTTP 的实际联调，九组行为验证真实会话、
三域/身份第二跳、审计 SQL 回滚、幂等重放、撤权和慢 Redis 超时/恢复；临时资源已清理，
生产未改，失败轮次保留。见 [网络联调证据](docs/GATEWAY_NETWORK_VERIFICATION_20261004.md)。

同日后续五个真实 Boot、私有 Nacos/Redis/Broker 与四库通过人工重放、故障恢复、
重复入箱/已读幂等、消费回滚重投、关注者扇出和偏好抑制；修复网关发现转发所需的
LoadBalancer 依赖。Java 21 全模块 224 项测试通过，备份/失败轮保留、临时资源已清理。
未生产发布，正向运营 UI 和正式上线门槛仍缺。见
[五服务 Broker 重放验收](docs/BROKER_REPLAY_NETWORK_VERIFICATION_20261004.md)。

同日后续真实浏览器完成隔离三域重放/入箱、审计故障回滚、原不确定命令恢复和撤权/登出。
密码焦点与有界长列表改进后 73 项前端测试/构建通过，归档与清理复核通过，生产未发布。
发现页 404 已记录；HTTP/RPC 注册分组隔离源代码及 225 项后端测试通过，拆分后真实
发现回归仍待完成，剩余 reviewer/审计分页 UI 和正式运营门槛不提前关闭。
见 [运营 UI 与路由缺口记录](docs/OPERATIONS_UI_VERIFICATION_20261004.md)。

随后新构建在独立Nacos/Redis与四库验证四HTTP/三RPC分组隔离，连续120次发现和40次
文档/通知读取200；真实审核员UI通过只读、审计两页/十代、安全展开和撤权/登出。
75项前端、重新执行225项后端及构建通过，资源备份清理，生产未发布。本轮无Broker，
执行中断的日志/lease缺口明确保留；具体边界见
[注册分组与审核员验证](docs/DISCOVERY_NETWORK_VERIFICATION_20261004.md)。

2026-10-03 已发布真实路由工作台，完成草稿保存、素材读取、通知偏好、Netty 私信/群聊发送与失效会话退出的公网 UI 核验，另有 390px 导航/焦点检查。51 项前端测试和 12 个公网产物字节校验通过，合成数据已备份后清理；未完成流程与回滚位置见 [工作台交付证据](docs/FRONTEND_WORKSPACE_VERIFICATION_20261003.md)。不是整个平台正式上线认证。

社区成员中心提供公开社区加入、本人退出、我的社区/成员游标分页及所有者移除。私密信息按当前关系授权，人数与关系同事务；社区入会不自动加入聊天群/语音房，移除不是永久封禁。见 [成员计划](docs/COMMUNITY_MEMBERSHIP_PLAN.md)、[契约与发布手册](docs/COMMUNITY_MEMBERSHIP_RUNBOOK.md) 和 [2026-10-03 交付证据](docs/COMMUNITY_MEMBERSHIP_VERIFICATION_20261003.md)。浏览器点击/布局尚未验收，API 通过不代表完整 UI 通过。

```bash
cd backend
mvn test

cd ../web
npm ci
npm run test:chat-history
npm run test:community-membership
npm run test:workspace
npm run build
```

本地运行需要可用的 MySQL、Redis 和 Nacos。生产部署清单位于 `deploy/compose.production.yml`，所有业务服务仅加入内部 Docker 网络，不直接映射宿主机端口。

## 安全约束

- 客户端提供的 `X-Koko-User-Id` 会在网关被删除，再从 Sa-Token 会话注入。
- 下游业务服务不对公网暴露，写操作必须携带网关注入的身份。
- 社区创建和所有者成员关系处于同一事务。
- 社区成员写入锁社区并同步人数/版本，所有者不可退出/被移除；私密外人、非成员名册读取与归档社区读取返回 404。
- UP 主资料以草稿创建，只有 `ACTIVE` 状态可公开发现；地址唯一且更新使用版本号乐观锁。
- 创作者文章使用 `DRAFT -> PUBLISHED -> ARCHIVED` 生命周期；只有所有者能修改、发布和归档，公开列表不返回正文大字段。
- 内容点赞由 `(post_id, user_id)` 联合主键保证幂等；评论关系和计数在同一事务中更新，评论作者或内容所有者可以软删除评论。
- 直播状态迁移在 SQL 中同时校验创建者和前置状态。
- 未配置真实媒体供应商时禁止进入 `LIVE`，不返回伪造推流密钥。
- LiveKit API 密钥只保存在服务器 `.env`；客户端只能取得限房间、十分钟有效的入会令牌。
- 密码使用 BCrypt（cost 12），生产凭据仅存在目标主机发布目录的 `.env`。
