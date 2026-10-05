# Netty 聊天与开发契约阶段交付记录

> 公开版：部署地址与用户目录已脱敏，须按实际环境替换；不是原始验收地址。私有证据未改动。

本文件保留 V1 原始发布时点的历史结果与指纹。随后聊天安全 V2 和新版前端已更新，当前部署与验收以 [安全阶段交付记录](CHAT_SAFETY_VERIFICATION_20261001.md) 为准；下文的旧指纹、103 项测试和未实现功能描述不是当前状态。

## 交付范围

日期：2026-10-01。工作区 `D:\KOKO`，阶段服务器 `deployment.example.invalid`。
Java 主导，新增独立 chat-service；复用 Gateway、Sa-Token/Redis、Nacos/Dubbo、MyBatis-Plus/MySQL/Flyway。
LiveKit 负责音视频；聊天由 Netty WebSocket 处理。此记录不代表 Discord、直播转码或整个平台正式上线完成。

已实现：私信用户对唯一、群聊创建/改名/成员增删/退出/解散、历史与已读、事务消息序号、客户端 UUID 幂等重试、提交后 ACK、无正文同步提示、断线补拉，以及前端消息中心。

Lombok 用于实体访问器，不采用包含敏感字段的自动 toString。Java 属性及 HTTP 契约使用中文注释与 OpenAPI3 Tag/Operation/Schema；前端业务类型、聊天待确认状态和组件属性补充字段语义。Knife4j 登录后聚合账号/创作者、社区、直播、语音、通知、媒体与聊天七组契约。

## 验证结果

- 最后一次 `backend/mvn -q test`：退出码 0，103 项测试，失败/错误/跳过均为 0。日志中的 database offline 是既有故障注入用例，不是生产数据库状态。
- `web/npm run build`：Vue TypeScript 和 Vite 生产构建通过。LiveKit 动态分包约 583 KiB，仍有构建体积告警。
- Java 注释审计：`patches=[], fallbacks=[]`；前端业务契约审计：`patch=null, missing=[]`。这不等于人工审查了每个函数或完成全系统文档治理。
- 隔离真实 MySQL：24 路并发发送、事务回滚、同 UUID 重试/冲突、历史边界、移除/重加、已读不回退、解散保留记录通过；测试 schema/用户及临时凭据文件已清理。
- 真实 TCP Netty 协议测试：握手身份/Origin、提交后 ACK、无效消息不成功、连接上限与释放、撤销会话通过；这些测试中的用例服务采用 mock，不能单独证明真实数据库事务。
- 公开端到端 `deploy/tests/chat-stage-smoke.ps1`：56 项断言通过，覆盖真实 Gateway/Netty/MySQL/Redis、私信/群聊、伪造身份、错误 Origin、离线补拉、成员边界、七组文档、Knife4j 静态资源、注销后的旧连接失效。
- 随后重新发布并重启聊天 JVM，`chat-handshake-check.ps1 -VerifyHistory` 确认两条已提交私信仍保留，公开 WebSocket 握手 101，媒体契约隐藏可信身份头。
- 最终公开首页、新 JS/CSS 与原社区发现接口均返回 200。
- 18:30 采样：九个带 healthcheck 的 KOKO 容器全部 healthy，当前容器 OOM=false、restart=0。LiveKit 不带该健康检查，此项不替代 RTC 双客户端验收。

## 发布问题及修复

Gateway YAML 缩进错误曾导致启动失败：先回滚旧网关，再修复并增加实际 YAML 解析测试。语音服务漏导入公共文档路径配置、通知服务缺少 Springdoc HTTP 端点，已修复并补回归验证。

Nginx 单文件 bind mount 在宿主机 patch 替换 inode 后仍读旧配置：reload 成功不代表新配置生效。核对容器内配置后，仅重启入口代理重新挂载，数据库等共享中间件未重启；真实公开握手从 400 恢复为 101。发布/回滚手册已记录该检查。

测试清理先导出定向 SQL，再删除精确身份及其会话。早期备份锁表和缺少默认 schema 时，脚本在删除前停止；修复为事务导出及显式 USE。失败与修正后的备份均保留在 `backup-chat-20261001a`，不得将不完整备份用于恢复。

五份无凭据清单记录的 20 个合成账号及所属测试会话、成员、消息均已清理，逐份剩余记录检查为零；真实用户内容未纳入删除目标。备份 SQL 可定向恢复，恢复前必须先核对身份、外键和当前数据冲突。

## 最终部署指纹

以下为实际服务器 artifact 与本地构建匹配的 SHA-256（未包含密码或会话令牌）：

| 工件 | SHA-256 |
| --- | --- |
| chat-service.jar | `ceb1d3162a449e54f69500a7d49bc6d375aa9548b9cc8c82839d691b6630ec97` |
| asset-service.jar | `4bfb15fb83dad64998957c378be3ef471f269bdd6a9b56bb449c1ff8d1d42001` |
| voice-service.jar | `2234c2a30f0de8974bd12c22710507496dff1b12014480ec9922acb7abd93fa5` |
| notification-service.jar | `6f42013e07f58b1f63680d0c46ed5a918f47d9b3875aa0d2e64b88f99522ca0a` |
| 前端发布归档 | `6d4d9262d830764c7872e1f445bc7b5d42840d07a05007ce288dd5e81ff56d3f` |

公开前端资源：`index-Dyr1A86N.js`、`index-BZWpGH2K.css`。发布前备份包含旧服务、前端、配置和六个业务库，位于 `/srv/deployment-home/koko-nexus-release/backup-chat-20261001a`，只属于同机备份。

## 未通过的正式上线门槛

浏览器工具读取超时，未完成真实页面点击/布局验收。公网仍为 HTTP，必须取得域名并配置 HTTPS/WSS 与安全 Cookie 才能开放真实用户使用。当前聊天只允许单实例，跨节点分发、持续压力/慢客户端容量验证、审核/举报/拉黑、合规留存、异地备份恢复和外部告警仍未完成。

服务器采样为 3718 MiB 物理内存、1129 MiB available、14335 MiB swap（已用 8726 MiB），磁盘可用 8.9 GiB/使用 85%。这些是某时点采样，不是容量承诺；扩 swap 不等于物理内存扩容，500 连接是保护阈值而非该服务器压测结果。
