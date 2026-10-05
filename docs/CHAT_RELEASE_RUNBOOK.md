# 聊天发布 / 回滚手册

> 公开版：部署地址与用户目录已脱敏，须按实际环境替换；不是原始验收地址。私有证据未改动。

## 本次发布资源

- 工作区 `D:\KOKO`；服务器目录 `/srv/deployment-home/koko-nexus-release`。
- 暂存 `stage-chat-20261001a`；发布前备份 `backup-chat-20261001a`，保留六个已有业务库 SQL、旧 jar、前端及配置；只是同机备份，不是异地灾备。
- 新增 `koko_chat`，Flyway V1 创建三张带唯一约束及外键的聊天表。身份/其他领域不改变已有迁移。
- chat REST 8087、Netty 8097，仅 Docker 内网。Gateway 通过独立 WebSocket 路由，公共 Nginx 在 `/koko-api/` 保留 Upgrade 头。
- 文档 UI 用 Knife4j 4.5.0 纯 UI + 当前 Springdoc 2.8.13，不引入 Springfox；参考 [Knife4j 官方版本说明](https://doc.xiaominfo.com/docs/quick-start/start-knife4j-version)。实体采用 Lombok Getter/Setter，不生成敏感字段的 toString。

## 发布顺序和检查

先做配置解析、全量 Maven 测试、Vue TypeScript/生产构建、隔离 MySQL 事务并发及 TCP WebSocket 测试。核对 jar SHA-256 后备份；只创建专用数据库和授权。

依次更新身份、社区、直播、语音、通知、媒体、聊天、网关，每步等待 health；失败立即停下，不继续切换前端。新网关路由曾因 YAML 缩进启动失败，已先恢复旧网关并重载 Nginx，随后新增 `StartupConfigurationTest` 解析真实应用配置并修正。后续发布必须保留该验收项，编译不证明 YAML 可启动。

执行 `deploy/finish-chat-release.sh` 完成失败后续发时，只更新网关、前端及 KOKO WebSocket 代理片段，不重启数据库等共享中间件。构建上下文必须使用 `deploy/.dockerignore` 白名单，不能包含 `.env` 和数据库备份。

单文件 bind mount 的配置若由 patch 替换 inode，容器可能仍读取旧文件，单纯 reload 不会更新。发布时比较宿主机和容器内配置内容，存在差异才单独重启入口代理重新挂载（入口可能短暂中断），随后 `nginx -t`、公开握手必须返回 101。本次实际发现并修正了该问题，不可仅以宿主机文件或重载成功判定路由生效。

发布后运行 `deploy/tests/chat-stage-smoke.ps1`，检查匿名拒绝、私信/建群/收发、重试/冲突、成员边界、已读、文档与注销后旧连接拒绝。每次生成独立无凭据合成数据清单，按清单调用 `cleanup-chat-stage.sh` 定向备份并清理，不删除真实用户内容。

## 回滚

本节为 V1 原始聊天发布的回滚。部署安全 V2 后以文末安全阶段约束为准，禁止直接降级为绕过拉黑的旧聊天实现。

1. 暂停新增聊天流量；`docker compose -f compose.production.yml stop chat` 只停聊天。
2. 从 `backup-chat-20261001a/artifacts/` 恢复旧服务 jar；恢复其 Compose 和旧前端，逐个 `up -d --no-deps --build`，逐个等待健康，禁止整套 `down -v`。
3. 必要时恢复 `nginx.before-chat.conf` 到已验证的 `/srv/deployment-home/xingmu/infra/nginx/default.conf`，核对容器挂载内容一致（不一致时仅重启入口代理），`nginx -t` 成功才 `nginx -s reload`，保留旧项目路由。
4. `koko_chat` 和已提交消息保留，不做 DROP，不倒退 Flyway。回滚网关/前端不等于业务数据恢复；恢复 SQL 需要另行核对数据时点和冲突。
5. 复查公开首页、旧接口、容器健康及 OOM/restart 状态；缺任何证据不得报告恢复成功。

## 尚未通过的正式上线门槛

公网域名与 HTTPS/WSS、安全 Cookie；完整 UI/移动端验收；聊天跨实例分发、压力/慢客户端持续容量测试；账号封禁/申诉、完整 RBAC、合规留存；异地备份、恢复演练及外部值班告警。当前仅可作为阶段验收环境，不能称整个平台企业级上线闭环完成。

## 聊天安全 V2 增量发布

暂存 `stage-chat-safety-20261001a`，备份 `backup-chat-safety-20261001a` 保存身份/聊天 SQL、旧 chat jar、前端、Compose 和私有 `.env`；仅同机备份，不替代恢复演练。先运行隔离数据库验收，再备份、更新 chat 等待健康，最后切换前端，仅重载入口代理；共享 MySQL/Redis/Nacos 和原项目不重启。参考 `deploy/backup-chat-safety.sh`、`deploy/rollout-chat-safety.sh`。

V2 新增安全锁、拉黑、举报和审计表，不修改 V1。迁移后禁止删表或倒退 Flyway，也禁止直接降级旧 chat 继续收消息，因为旧实现不会执行拉黑授权。若新版无法运行，先停 chat 写入，保留 V2 与所有事实，优先向前修复；完整回退必须另行评估安全事实的兼容性，不能将同机备份自动覆盖正在使用的数据。

运行 `chat-stage-smoke.ps1` 和 `chat-safety-smoke.ps1`；无审核员时只验公开默认拒绝，正向审核在隔离 MySQL 验证，不能冒充公网审核验收。待负责人选定公开用户名后，再解析实际 ID、配置最小审核权限、验收证据读取/结案/撤权。

`cleanup-chat-stage.sh` 接受这两个已知 stage 目录内的精确无凭据测试清单。删除前逐账号验证 ID/邮箱/用户名，并拒绝牵连非测试成员、举报、审核员或拉黑关系。保存定向 SQL 后在同事务中按外键顺序清理合成举报/审计/拉黑/消息/会话/账号与锁行，并检查计数归零。严禁对生产用户运行泛化清库命令。

## 历史工具 V3 增量发布（2026-10-02）

暂存 `stage-chat-history-20261002a`，同机备份 `backup-chat-history-20261002a`。`backup-chat-history.sh` 保存旧 chat、前端、身份/聊天 SQL、私有配置并生成工件校验和；`rollout-chat-history.sh` 只更新 chat/web，chat 健康后才切换前端，不覆盖服务器已有 Compose/.env，不重启共享中间件。Flyway V3 仅增加个人消息收藏表，既有 V1/V2 不变。

前端读取/取消收藏竞态的后续修正使用 `rollout-chat-history-readguard.sh`，仅重建 web。该脚本限制已知目录、校验 chat 指纹和原发布备份，拒绝覆盖已有回退目录；另存当前前端 `web-before-readguard.tgz` 和 `readguard-backup.sha256`，保留两版前端。发布前核对上传归档 SHA-256，执行 `bash -n`，发布后验证公网首页、脚本及 CSS 的真实 200 与内容指纹。

验证命令：后端全模块 `mvn -q test`、前端 `npm run test:chat-history` / `npm run build`；隔离 MySQL 使用 `chat-mysql-check.sh stage-chat-history-20261002a`。后者只建立已知的 `koko_chat_check_20261002` 数据库/专用用户，限制容器内存和 CPU，结束清理 schema、用户及凭据文件，必须复查计数为 0。不向共享数据库注入全局配置、停库或权限提升。

公网顺序运行 `chat-stage-smoke.ps1`、`chat-safety-smoke.ps1`、`chat-history-smoke.ps1`；各脚本创建独立合成账号、注销会话并写不含密码/令牌的精确清单。不要把旧结果或重复运行累计为新的独立用例。审核员默认空仍只验证公网拒绝；不擅自授予测试号审核权限。

更新后的 `cleanup-chat-stage.sh` 只额外接受这一已知 stage；删除前核对精确账号，并拒绝涉及非测试收藏者/会话的关系。先保存定向 SQL，收藏先于消息删除，仍保留全部 V2 安全关联检查。每份清单必须检查身份、会话、成员、消息、收藏与安全记录总剩余计数为 0；备份 SQL 权限仅本人可读，恢复前核对当前外键/身份冲突。

若仅前端修正失败，先保留现状，再从 `web-before-readguard.tgz` 恢复到单独核对的前端工件目录，只重建 web 并检查代理及公开资源；不恢复 SQL、不动 chat。如果整个 V3 发布失败，先停止聊天写入，保留 V3 与当前事实，优先向前修复；回退候选只能是本次备份中的 V2 安全实现，不是 V1。须先在隔离环境验证 V2 对已应用 V3 的 Flyway/数据兼容性与安全约束，尚未做该演练，不能宣称一键回滚已验收。严禁删表、倒退迁移或直接用备份覆盖正在使用的库。

本次 API/数据库/前端异步逻辑通过不替代浏览器点击/视觉、移动端、HTTPS/WSS、跨节点、压力容量或灾备门槛。具体证据见 [历史工具交付记录](CHAT_HISTORY_VERIFICATION_20261002.md)。

## 用户版本同步 V4 候选（2026-10-05，未发布）

V4 是新增表，不修改 V1～V3。每次聊天写入同事务登记受影响用户版本，SQL 写失败必须回滚业务。
先备份聊天库、当前 JAR/前端与私有配置，再验证迁移及单节点兼容，之后才讨论多节点。
不能用旧的只覆盖 V1～V3 的验收/清理脚本直接宣称 V4 已验收；清理必须考虑合成账号的版本行。
不重置活跃用户版本、不对在线表 TRUNCATE；删除并重建同版本可能产生 ABA 漏提示。

配置 `CHAT_SYNC_POLL_INTERVAL_MS`（250～10000，默认1000）与 `CHAT_WS_BIND_ADDRESS`
（容器内部默认0.0.0.0，环回测试127.0.0.1）；不得公开映射8097绕过网关。
轮询是单线程固定延迟、每批最多100用户、SQL读取超时2秒；不在Netty EventLoop执行。
每个节点仍以500连接为保护阈值，不是容量承诺。线上旧版每节点每用户三连接；
全局有效连接租约候选及升级约束见文末，不称为三台物理设备识别。

以下内部 Prometheus 指标不得向匿名公网开放，不含用户/会话高基数标签：

- `koko_chat_sync_poll_failures_total`：读取失败次数；恢复不清零。
- `koko_chat_sync_changed_users_total`：发现变化并调用通知的用户数，不是客户端已收到数。
- `koko_chat_sync_failed`：当前扫描失败状态。
- `koko_chat_sync_running`、`koko_chat_sync_connected_users`：运行及本机认证用户数。
- `koko_chat_sync_last_success_age_seconds`：完整成功扫描距今秒数，未成功过为-1；空闲不冒充成功。

告警需结合running/connected_users避免空闲误报，绑定实际外部接收人并验证送达。
本批验证使用SimpleMeterRegistry，不声称生产Prometheus/告警已接入。
固定WS入口不自动多节点：需另外验证Nginx/Gateway分流及两节点真实会话撤销。
失败先摘流并保持数据事实，优先向前修复；没有演练过V4回退，不删表或覆盖生产SQL。
详细范围见[同步计划](CHAT_CLUSTER_SYNC_PLAN.md)和[数据/网络验证](CHAT_CLUSTER_SYNC_VERIFICATION_20261005.md)。

### 显式 WS 发现（未发布）

2026-10-05续批四Boot/独立Nacos和需密码Redis完成八组真实HTTP/WS网络检查，
包括真实Cookie登录/注销、双节点、Redis暂停/恢复、优雅摘流/新JVM重启。
不是容量、浏览器双端或生产发布验收；见[完整网络记录](CHAT_GATEWAY_NETWORK_VERIFICATION_20261005.md)。

所有chat的HTTP注册须有规范1～65535的`koko-chat-websocket-port`，且与本机Netty监听一致。
先升级提供方、逐实例核对元数据/私网监听，才将Gateway的`CHAT_WS_SERVICE_URI`显式配置为
`lb:ws://koko-nexus-chat`；内部Netty确实具备TLS时才使用`lb:wss://...`。
公网WSS可在入口终止TLS并经私网WS转发，不把HTTP端口或任意元数据URL当WS目标。
新过滤器保留原WS/WSS协议意图，不被Nacos的HTTP scheme覆盖；旧缺元数据节点被拒绝503。

当前Compose保留原定址默认值并允许URI配置；本轮没有更改服务器配置。
`koko_gateway_chat_websocket_invalid_targets_total`是内部配置错误计数，无用户/节点标签；
不证明握手成功或客户端已收。WS发布前仍需真实UI、TLS、容量、备份/回退与公网验收。

浏览器续批Gateway新增Cookie HttpOnly/Lax；公开Compose传入`KOKO_COOKIE_SECURE`。
默认false仅保留阶段HTTP兼容，正式TLS发布必须true并检查登录/注销Cookie及浏览器WS。
七组独立无头页面检查通过，但辅助程序关闭流程修改的完整复验仍未通过；
失败退出、SSH观察恢复及清理记录见[浏览器验证](CHAT_BROWSER_VERIFICATION_20261005.md)。

### 共享Redis连接配额候选（未发布）

配额固定为每用户三份120秒有效租约，沿用当前chat的共享Redis连接及2秒命令超时，
不增加JVM或单独生产Redis。键为`koko:chat:connections:v1:{userId}`，不含令牌/正文。
新增Lua脚本随chat JAR发布，无额外SQL迁移，不改已应用V1～V4。
Redis ACL须允许单键Lua执行、TIME及脚本用到的ZSET/TTL/DEL命令；实际账号权限仍需
用隔离环境和真实配置验证，不能把本次独立Redis测试视为生产ACL已验收。

先保存每个节点原JAR、配置和前端指纹；先升级全部chat提供方并验证健康、身份与关闭释放，
再配置WS发现路由。新旧版本混用期间保留单节点入口或停止新聊天准入，不能宣称全局配额。
禁止对共享登录Redis执行FLUSHALL、SCRIPT FLUSH或删除真实账号配额键；故障演练只用隔离资源。
回退至不登记租约的旧chat将失去全局保护；摘流并关闭新版连接后按原TTL收敛，不清库，
保留消息及V4版本事实，兼容/回退演练尚未执行。

内部指标（非公网、没有账号/UUID标签）：`koko_chat_quota_denied_total`、
`koko_chat_quota_expired_total`、`koko_chat_quota_failures_total{operation="acquire|renew|release"}`、
`koko_chat_quota_release_rejected_total`。本轮使用SimpleMeterRegistry，不证明告警已送达。
真实Redis/两Netty六组检查通过，原自动收尾失败后已独立恢复并回收；
新配额仍需完整Boot/Gateway真实会话及SQL、正向浏览器、重启/主从故障、容量及生产发布。
见[配额计划](CHAT_GLOBAL_QUOTA_PLAN.md)与[验证记录](CHAT_GLOBAL_QUOTA_VERIFICATION_20261005.md)。
