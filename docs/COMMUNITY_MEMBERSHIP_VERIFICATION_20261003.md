# 社区成员闭环交付记录

> 公开版：部署地址与用户目录已脱敏，须按实际环境替换；不是原始验收地址。私有证据未改动。

交付核验日期：2026-10-03（Asia/Shanghai）。项目 KOKO Nexus，工作区 `D:\KOKO`，阶段入口 `http://deployment.example.invalid/koko/`。开始于 10 月 2 日，工件/备份目录沿用 `20261002a`；服务容器创建时间为 2026-10-02 20:04/20:05，本日复连后确认实际发布状态，不重复部署或覆盖备份。此次是功能代码、状态逻辑、API 与发布核验通过，不是整个平台正式上线声明，也不是完整 UI 验收通过。

## 本轮前后端功能

公开社区真实加入、本人退出、我的 ACTIVE 社区游标列表、当前成员名册、所有者移除成员。加入/移除幂等，所有者保护，私密社区信息隔离，人数/版本与关系事务一致，最多 1000 人；列表单页 1～50，以字符串 ID 游标分页。移除不是永久封禁，公开社区可重新加入。

后端沿用 Java、Sa-Token/Gateway、Nacos/Dubbo、MP/XML、Lombok 和 Knife4j/OpenAPI。仅既有 community 服务增加用例、控制器、身份目录 RPC、绑定 SQL 和不可变 V9 扩展字段，无新 JVM/Go/中间件。身份 RPC 在成员事务外取得公开名字快照；成员表不包含邮箱，历史 null 名称显示角色/ID，不虚构账号资料。HTTP 只返回明确 DTO，不直接输出实体或内部身份。

前端社区中心接入侧栏、我的社区和公开卡片；加入/退出、名册/社区分页、所有者移除、确认提示、错误/加载/真实成功、提交后服务端重新读取。切换/卸载取消读取并忽略迟到响应；读取/写入互斥，失败不伪造人数或成功。已提交但刷新失败单独提示，撤销旧私密视图。取消客户端等待不等于撤销服务端提交。

按用户指定 enterprise-development skill 落实事务/权限不变量、注释、真实 MySQL 并发、备份后增量发布与精确清理；computer-use skill 用于页面验证，工具超时单独记账。社区关系不自动同步 Netty 群、语音房、LiveKit，不虚构频道/邀请/通知功能完成。

## 测试证据

- 本轮 `mvn -q test` 退出 0，重新汇总 Surefire XML：131 项、31 套件，失败/错误/跳过均 0；包括新增成员服务 8 项和 HTTP 投影/契约 2 项。不把 mock 测试称作全部真实数据库集成。JDK 25 的既有 Unsafe/Mockito 警告仍有，服务运行 Java 21。
- `npm run test:community-membership` 最终 10/10；真实 SFC setup 与 Vue 响应式逻辑，网络/生命周期替换。覆盖切换迟到、非成员不读名册、权限拒绝清除私密信息、失败无假成功、忙碌重复点击、退出确认、提交后刷新失败、卸载、两类实际游标分页/去重/失败。既有 `test:chat-history` 本轮重跑 8/8，验证共享 request 导出没有破坏已有异步用例。合计 18 项，不是 DOM/浏览器布局测试；Node VM Modules 实验性警告保留。
- Vue TypeScript/Vite 生产构建退出 0，业务 JS 170.45 kB，CSS 24.15 kB；LiveKit 583.03 kB 的既有体积警告未消除。
- Java 契约审计 `patches=[], fallbacks=[]`，前端基础契约审计 `patch=null, missing=[]`；人工检查新增字段和接口中文语义，不宣称全部方法文档治理或完整 P3C 扫描。
- 隔离 MySQL 与实际 MP/XML/Spring 事务代理：V1～V9 SQL，16 路同账号入会唯一/计数，私密/所有者/本人隔离和分页，外层旧 RR 快照先读后被另线程移除仍拒绝，真实 CHECK 注入导致关系插入回滚且人数/版本不变；999 人后 8 路竞争只接纳 1 人；归档边界；持社区锁时竞争入会被阻塞，归档提交后等待者拒绝且人数不变。输出 `PASS MySQL membership...`。快照字段来源另有单元/公网验证，不把测试名称误当成额外断言。
- 隔离库/用户 `koko_membership_check_20261002`、一次性 Java 容器，未改变生产库约束或全局参数；结束库/用户均复查为 0，私有 `mysql-check.env` 不存在。
- 公网最终 `community-membership-smoke.ps1` 71 项通过：真实 Gateway/Sa-Token/Redis/Dubbo/MySQL；匿名 401、伪造头/请求体不提权、重复关系不改人数/版本、快照与脱敏字符串 ID、成员/社区游标、参数边界、所有者保护、非所有者管理 403、私密外人 404、退出/移除/重加入、关系变动使旧编辑 409、归档隐藏/退出 no-op、OpenAPI 路径。finally 逐账号注销，原令牌 `/auth/me` 401，最终清单 sessionsRevoked=true。
- 收尾把脚本 PASS 输出移至 finally 之后，并在 sessionsRevoked=false 时抛错；防止后续注销失败仍打印整体通过。该输出/失败保护改动经过语法检查，未因此再创建第三批公网账号；本轮通过证据仍为上述 71 项和第二批清单，不虚增次数。
- 首跑业务部分 69 项后在 OpenAPI 断言失败：脚本误期望 `/api/communities/...`，实际文档按既有配置是 `/communities/...`；注销误期望 204，既有契约为 200。修正测试而非改正常接口，完整重跑 71 项通过；首跑不计为通过。首跑四账号的精确 Redis session 键 EXISTS 返回 0，同时其他 session 键计数 19，记录后允许定向清理；未保留原令牌。第二批另有旧令牌 HTTP 401 和精确 Redis session 键 0 双证据。

身份目录正向 RPC 在公网加入中验证；目录故障、公共慢 SQL/RPC、1000 人公网容量、长期慢客户端、独立故障域恢复均未在公网注入，不做该类通过声明。

## 发布和指纹

发布前备份 `/srv/deployment-home/koko-nexus-release/backup-community-membership-20261002a`：现有 Compose/.env、旧 community jar/web、身份/社区 SQL，四份 SHA256SUMS 均 OK。复查服务器 Compose/.env 与备份相同、`.env` 600。只 community/web 重建，无共用基础设施重启和新 JVM。中途 SSH 会话失去可轮询进程，复连先核验当前状态；已有备份/旧 web 目录不重复覆盖。早期手工核验在 backup 子目录导致相对路径失败，改在发布根目录后四份均通过，这是核验目录错误，不是备份损坏。

生产 `community_flyway_schema_history` V9 success=1。运行容器 `/app/app.jar` 校验与上传/本地构建相同。最终公网首页、JS、CSS HTTP 200，下载资源摘要与本地 dist 实字节相同；不是仅看到文件名就宣告上线。代理 `nginx -t` 通过。

| 工件 / 资源 | SHA-256 |
| --- | --- |
| `community-service.jar` | `0feff203eb8bdd44013c3b5dae10d0913c67cc806ffab0617c83b41d45d65498` |
| `community-membership-web-20261002a.tgz` | `513ba7ed9ec0ad4b25607e66c5295014e8a4d029f560195376d1bb577826624c` |
| `index-AsF-cJdm.js` | `bc4298b6f053ead83e2804e63501d8e347bacc6569696cc902cc3aeada5ea8e0` |
| `index-DaqeGL2w.css` | `7e4fd6dacdf196448f278bdc628ab83568ba83f8c25ee87ce13c3f0db0bb351b` |

九个带健康检查的 KOKO 容器全部 healthy、OOM=false、restart=0；LiveKit 运行且无容器健康检查，不能代替媒体测试。社区人数与实际关系 COUNT 差异总数为 0。采样：物理内存 3718 MiB、available 1015 MiB，swap 14335 MiB/已用 9591 MiB，磁盘剩余 8.2 GiB/86%；仅为即时状态，扩交换区不提高实时吞吐。

## 清理与恢复边界

两份无凭据清单，每份 4 个账号/3 个社区，合计 8/6：

- `membership-smoke-report-c8341e267be24d808396905a3590bc1c.json`：首跑未完整通过，69 断言后文档路径失败；后补 Redis session 0 证据。
- `membership-smoke-report-1ccd77cdc99f43eba09f42f3a4c156de.json`：完整 71 通过及注销后 401。

清理精确核验账号 ID/邮箱/handle、社区 ID/slug/所有者，拒绝外部成员/拥有社区、其他领域数据；仅备份/删除这批成员/社区/账号。首次已保存首批定向 SQL，但临时 CHECK 表未选数据库（ERROR 1046）而失败，事务/删除尚未开始，不误标清理完成。修正 `USE koko_community` 后另存 `-cleanup-v2.sql`，保留首份备份，重复核验；锁社区/账号并事务内 CHECK 再断言后删除。两批各自剩余总计 0，独立最终 SQL 复查全部 8 账号/6 社区计数 0，人数账实差异 0，未删除真实用户内容。

可恢复定向备份在上述 backup 目录下各清单同名 `-cleanup-v2.sql`，权限 600。分次 mysqldump 不宣称跨领域全局一致快照；恢复前必须隔离验证并核对当前 ID/外键冲突，不直接覆盖线上库。没有删除 Flyway 或其他服务数据。

## 独立未验收项

浏览器工具 getTab 超时，经文档规定的现有浏览器 tabs.get + DOM snapshot 成功读取匿名首页（真实文章 111 可见）；reload 再次超时并重置执行会话。未登录合成账号、未点击社区成员面板、未检查桌面/移动布局，没有可交付的交互截图。API、SFC 状态测试与资源校验不能冒充这项通过。

HTTPS/WSS、域名和安全 Cookie、曾公开凭据轮换、真实 RTC 双客户端、Discord OAuth、实际视频直播/回放、完整运营 RBAC/封禁申诉、跨节点聊天、容量/持续压力、异地恢复、通知故障演练和外部告警仍为正式运营门槛。所有者移除不是永久封禁；未擅自指定审核员或提升生产权限。后续按 [企业路线](ENTERPRISE_ROADMAP.md) 推进，不用本次社区功能解除既有门槛。
