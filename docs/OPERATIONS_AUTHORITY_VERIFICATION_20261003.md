# 运营 RBAC、本人二次确认与角色审计开发验收

> 公开版：部署地址与用户目录已脱敏，须按实际环境替换；不是原始验收地址。私有证据未改动。

2026-10-03；属于路线阶段 2/6 的局部开发验收，未发布生产，未给真实账号赋权。
本轮采用 enterprise-development：区分单元/HTTP/真实 SQL 证据，并按账号当前权限、
独立事务限速、审计回滚和默认关闭设计；不以测试通过代替生产闭环。

## 实现及协议

- identity V8 定义三种固定角色及权限关系、账号授权版本、确认限速与短期确认摘要。
  迁移不创建任何用户角色。V9 保存只追加角色审计，应用无更新/删除审计 API；不声称 DBA 不能修改。
- OPERATIONS_ADMIN 可读/重放 Outbox 并管理他人角色；NOTIFICATION_OPERATOR 可读/重放；
  NOTIFICATION_AUDITOR 只读。当前 ACTIVE 账号、启用角色、ACTIVE 关系及有效期均参与实际联查。
- 角色变更先验证管理者本人密码，锁固定 guard，重新读取管理者权限/密码/版本，再锁目标账号。
  角色关系、账号 operations_version 与追加审计同库事务；禁止修改本人角色。
  UUID 绑定操作者、目标、角色、预期版本、状态、期限和原因。重复命令返回历史回执，不重执行。
  DATETIME(6) 到期参数超过微秒精度明确拒绝，避免首次数据库截断后幂等冲突。
- 重放确认生成 32 字节强随机秘密、五分钟有效；库中只存 SHA256。
  绑定当前账号、会话摘要、固定业务域、规范化完整命令、账号授权版本和密码哈希指纹。
  原登录令牌不传给下游，秘密响应/密码请求的 toString 脱敏，不写浏览器持久存储。
- 每账号固定一分钟窗口最多五次本人确认；独立 Bean 的 REQUIRES_NEW 事务先提交预算，
  错误密码导致外层事务回滚不会恢复次数。角色管理和重放确认共享同一预算。
- Gateway 使用 Sa-Token 当前权限 Provider，RPC 在 boundedElastic 工作线程调用，不缓存授权。
  网络错误拒绝而非降级允许；密码失败/无权限为 403，不误触发前端 401 注销；限速为 429/Retry-After:60。
- 两端 `OPERATIONS_ENABLED` 默认 false；Gateway 不安装运营路由/Provider，identity 拒绝写能力。
  只开 Gateway 而未开 identity 也不会允许运营写操作。三域重新排队 RPC/HTTP 尚未接通。

开发中的 HTTP 契约：GET `/api/operations/access` 只查本人；POST
`/api/operations/replay-confirmations` 只确认，不重新排队；POST `/api/operations/role-changes`
需管理员本人密码。均有 OpenAPI 注释；不表示运行中 Gateway 已拥有这些接口。

## 时间边界

本次只读核验共享 MySQL 的 SYSTEM/session 时区实际为 UTC，不能把 JDBC 的
`serverTimezone=Asia/Shanghai` 当作数据库 CURRENT_TIMESTAMP 已转上海时间。
新运营 SQL 显式 `DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 8 HOUR)`，所有运营到期、
限速起点、确认创建/到期及角色审计受理时间使用上海 DATETIME，未来一年边界也用同一时钟。
没有修改共享数据库/主机时区；既有业务时间字段未在本轮批量迁移，不声称全项目时区治理完成。

## 本轮实际测试

最后完整命令：`cd backend; mvn -q test`，21:34:04–21:34:50 的 39 份 Surefire 报告：
182 项、失败 0、错误 0、跳过 0。比上一轮 142 项新增 40 项；报告摘要与每份报告哈希在
`deploy/operations-authority-full-tests-20261003.json`，命令输出在同名前缀 `.log`。
39 份原始报告另存 `deploy/operations-authority-full-surefire-20261003.tgz`，SHA256
`558f9a5a856c72bb570cbd34bf8145fbbdbf2366942c030e3b872b1cbf48b075`，防后续重跑覆盖原报告。
随后只换行排版一个 HTTP 测试参数，六项 Controller 测试再次通过，不把它重复计入 182 项。
Mockito 自附加、JDK Unsafe 和 Commons Logging 等现有运行警告仍存在，未声称消除。

六项 HTTP 测试使用真实 WebFlux、Sa-Token Reactor 适配器/认证过滤器及本进程隔离会话：
匿名/伪造身份头拒绝、真实会话与账号绑定、boundedElastic、错误密码 403 后仍可读本人权限、
撤权不进入密码 RPC、429 重试头、真实操作者与目标账号区分、参数 400 和请求诊断脱敏。
HTTP 测试 RPC 为 mock、会话存储为内存，**不是 Redis/Nacos/Dubbo 网络联调**。
另验证默认关闭组件未安装、显式开关、RPC 故障拒绝及不携带原异常中的敏感参数。

## 真实 MySQL/事务证据

`deploy/tests/OperationsAuthoritySqlCheck.java` 加载本地新编译 identity/service-api/common/outbox 类，
依赖从线上 notification 原始 JAR 解包；Java 21 一次性容器 320 MiB、不额外使用 swap、1 CPU、
无发布端口、无 Docker socket、不启动 Nacos/RPC 或读取生产账号。
限权用户只拥有 `koko_operations_check_20261003`；先真实 Flyway 至 V7，插入七个合成账号，
再应用 V8/V9，共九份迁移。直接置入角色只限此事实夹具，不是生产管理员初始化。

实际八条 CHECK 和最终 PASS：

1. 有存量账号的迁移后授权版本为零且没有用户角色；审计员、过期、禁用角色/账号均拒绝。
   每连接强制 UTC session，角色上海期限与权限 SQL 的未来/过去判断仍正确。
2. 五次错误密码分别触发外层回滚，独立事务次数仍为五；第六次限速，过期窗口重置为一。
   凭据只存摘要，创建至到期恰好 300 秒；改账号、会话、域、命令拒绝。
3. 十二路真实 REQUIRES_NEW 调用恰好五个成功，实际计数五，非 mock 或本机锁替代。
4. 初次读取密码后另一独立连接提交密码变更；后续真实 MyBatis 读取拒绝旧快照，预算已提交。
5. 管理接口撤权/再授权递增关系及账号版本；旧确认持续拒绝；重复原命令仍返回初次回执，只有两条审计。
6. 真正 MySQL 审计 CHECK 3819 故障使角色关系/账号版本均回滚，独立确认预算仍保留；移除约束后同命令恢复。
7. guard 获取后另一连接提交管理者撤权，已确认的管理者在修改目标前被再次授权检查拒绝。
   此项不是两个管理员完整 API 并发/死锁容量验收，仍需后续补测。
8. 密码变更使确认失效；数据库到期拒绝；限量清理仅删除已过期行，保留未过期行。

成功轮次 b 的记录：`/srv/deployment-home/koko-nexus-release/backup-operations-authority-20261003b`。
隔离 SQL 备份 SHA256：`e7b38bb6ab2785a191f744cec88f48fecdae050139e230c8698200c5398305f4`。
首轮 a 在任何测试库创建前，因脚本切换工作目录后相对哈希清单解析失败而退出 1；
修正哈希校验工作目录后另建不可覆盖的 b，成功退出 0。首轮输入和失败日志未删除、未算通过。

归档 `deploy/operations-authority-evidence-20261003ab.tgz` 的 SHA256：
`3b2478792765db698886a9076ee50066e6ba367dda7e4960915c73ae2adeaac5`。
本地 `verify-operations-authority-evidence.ps1` 实际执行并验证输入工件/SQL 备份哈希、八条 CHECK、
PASS、退出码、清理为零、生产前后快照及七个原文件哈希。结果：`deploy/operations-authority-evidence-20261003.json`。

## 清理、运行状态与剩余门槛

测试 SQL 备份成功后，准确删除本次临时库/用户/容器并复查为零；原生产容器 ID、启动时间、
重启次数、OOM 状态与七个配置/JAR 哈希前后不变，九个 KOKO 健康检查仍 healthy。
AI 短剧五个业务容器保持 exited，根入口 503，KOKO 页面与公开社区 API 200。
仅回收 b 的可重新解包依赖副本；原始 JAR、测试包、SQL、日志与控制标记保留。
准确回收 125 MiB，事后磁盘可用 8390 MiB；执行记录另存
`deploy/operations-authority-cleanup-runtime-20261003.log`。这是瞬时磁盘采样，不是容量验收。

尚未完成：首管理员负责人显式初始化工具/实际账号选择、真实 Dubbo/Redis 联调、
三域死信分页/详情/审计与二次确认重放接入、运营 Vue 页面、独立 Broker 重投完整闭环、
备份/分服务发布和公网正向/撤权验收。敏感运营功能正式开放还需 HTTPS 和相应安全/容量门槛。
本轮未修改生产 .env、未应用生产 V7/V8/V9、未发布任何服务/前端，不自动给现有账号提权。
新 identity JAR 的 MP 实体含 operations_version，发布前须先验证并应用相应迁移，不能跳过后直接替换。
账号撤权阻断后续执行，不承诺追撤已经授权的在途跨库事务；阶段 2/6 及整个目标保持进行中。
