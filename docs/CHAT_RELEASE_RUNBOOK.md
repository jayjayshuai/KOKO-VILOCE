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
