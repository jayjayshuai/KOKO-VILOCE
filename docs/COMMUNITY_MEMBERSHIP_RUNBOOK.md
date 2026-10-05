# 社区成员契约与发布手册

> 公开版：部署地址与用户目录已脱敏，须按实际环境替换；不是原始验收地址。私有证据未改动。

社区关系独立于 Netty 会话、语音房和 LiveKit 房间，不进行隐式入群、邀请或通知。前端入口为侧栏「社区」、登录后的「我的社区」与公开社区卡片「查看 / 加入」。

## HTTP 契约

公网前缀 `/koko-api`，全部接口要求 Sa-Token 登录；下游只接受网关注入的可信身份，不使用请求体中的用户或角色。路径和返回游标中的雪花 ID 是字符串。

| 方法 / 路径 | 语义 |
| --- | --- |
| GET `/communities/{id}/membership` | `{community,role}`；公开社区非成员 role=null；私密社区非成员 404 |
| PUT `/communities/{id}/membership` | 加入公开 ACTIVE 社区，204；身份目录提供名字快照；重试无增量 |
| DELETE `/communities/{id}/membership` | 本人退出，204；不存在/未加入/归档无副作用；活跃所有者 409 |
| GET `/communities/joined?before=&size=20` | 本人 ACTIVE 社区 `{items,nextBefore}`；社区 ID 倒序独占游标 |
| GET `/communities/{id}/members?before=&size=20` | 当前成员可读名册 `{items,nextBefore}`；用户 ID 倒序独占游标 |
| DELETE `/communities/{id}/members/{targetId}` | 仅所有者移除非所有者成员，204；重试无增量 |

分页 size 为 1～50，before 为正数；非法参数 400。匿名 401、公开社区非所有者管理 403、私密外人/归档 404、人数上限和所有者保护 409。身份目录不可用映射为 503；不宣称已公网注入全部 SQL/RPC 超时分支。移除不是封禁，公开社区可重新加入。

Java 事务先锁社区，再读取/写入成员，与社区更新/归档的 UPDATE 行锁串行。成员关系、人数和 version 同事务，唯一联合主键兜底；更新受影响行数必须核验。读取授权使用当前锁读取并关闭 MyBatis 查询缓存，避免外层旧 RR 快照保留撤销权限。每次实际关系增删会递增社区版本，因此管理台旧版本编辑应收到 409 并重新读取。

`handle/displayName` 只保存入会时公开快照，不保存邮箱，也不假造旧所有者资料。名称变更不自动刷新旧快照。上限 1000 是保护阈值，不代表并发容量。旧数据库成员数若不一致，先诊断并审批修复，不能自动重写生产账本。

## 验证与发布

本地全模块 `mvn -q test`，前端 `npm run test:community-membership`、`npm run test:chat-history` 和 `npm run build`。真实 SQL 检查使用 `deploy/tests/community-membership-mysql-check.sh`，独立库/用户和一次性限资源 Java 容器；脚本拒绝覆盖已存在测试库，不停共用中间件。

服务器根目录 `/srv/deployment-home/koko-nexus-release`：

- `deploy/backup-community-membership.sh`：私有配置、旧 community jar/web、身份/社区 SQL 的同机备份，拒绝覆盖；SHA256SUMS 包含根目录相对路径，必须在发布根目录执行 `sha256sum -c backup-community-membership-20261002a/SHA256SUMS`，不是在 backup 子目录执行。
- `deploy/rollout-community-membership.sh`：仅 community/web 增量重建；不覆盖现有 Compose/.env，不重启 MySQL/Redis/Nacos、其他业务或原项目；社区健康后切前端，公开静态文件 644/目录 755，凭据仍 600，代理配置校验后 reload。
- 已存在 `artifacts/web-before-community-membership-20261002a` 时不要从头重跑发布脚本；先检查当前包、容器和迁移，确定中断位置后执行有限修复。
- Flyway V9 只扩展 nullable 名字字段，不更改 V1～V8，不回填猜测名称；迁移 success 后禁止删表/退迁移版本。优先向前修复。旧工件兼容回退需要隔离演练，本轮尚未执行，不盲目恢复整个数据库或旧配置。
- `deploy/tests/community-membership-smoke.ps1` 是公网真实业务验证，不是 UI 点击测试。使用精确合成账号/社区，并检查注销后旧令牌 401；日志/清单不保留密码和令牌。
- `deploy/tests/cleanup-community-membership.sh` 精确验证清单路径、ID/邮箱/用户名、slug/所有者、已注销会话，拒绝非测试成员、清单外社区与其他领域数据。先定向备份，事务中社区/账号行锁与临时 CHECK 断言再次核验，删除只限该清单，结果必须为 0。临时表明确选用 `koko_community`，不使用 mysql `--force`。当前备份后缀 `-cleanup-v2.sql`，拒绝覆盖。

阶段备份仅在同机，分领域导出不代表跨服务全局一致快照，也不替代异地备份、恢复演练、一键回滚、容量验证和正式 TLS。完整实测与工具限制见 [本轮交付记录](COMMUNITY_MEMBERSHIP_VERIFICATION_20261003.md)。
