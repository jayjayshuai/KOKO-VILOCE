# 首个运营管理员：负责人审批与离线执行

这是一次性初始化工具，不是 Web 接口、注册功能或无人值守启动钩子。
普通账号不会因迁移、首次登录或服务重启而获得运营角色。
初始化工具已经隔离验收，但未为线上账号执行，也未发布身份 V11。

## 审批和执行前提

负责人必须明确指定现有账号 ID、准确 handle、工单原因和上海到期时间；不得选“第一个用户”，
不得从测试账号复制 ID。首次 `OPERATIONS_ADMIN` 必须在未来 24 小时内到期，不能永久赋权。
负责人应先确认后续轮值/授权撤销安排；过期不会删除授权历史，也不允许自动重新初始化。

生产执行前须先完成：数据库备份及恢复验证、身份 V8～V11 分服务迁移与版本核对、
运营 RPC/认证全链路验收，以及域名/TLS、内网访问约束与负责人授权。
当前公网为 HTTP，敏感运营页面仍不开放。这份手册不授权管理员选择、赋权或发布。

离线连接使用专门受保护的进程环境：

| 环境项 | 用途 |
| --- | --- |
| `DATABASE_URL` | 明确的 MySQL 连接 URL，不含 user/password 查询参数 |
| `DATABASE_USER` / `DATABASE_PASSWORD` | 独立数据库凭据，不放命令行、工单或版本库 |
| `OPERATIONS_BOOTSTRAP_APPROVAL` | 人工核对 plan 后才设置的 64 位摘要，apply 必填 |

初始化需要 SELECT 和相关授权/审计 INSERT、版本 UPDATE 权限；不需要创建账号、表或迁移权限。
工具不会执行 Flyway、HTTP、Nacos、MQ 或普通 Spring Boot 启动。缺表/缺权限时失败关闭。
授权历史检查涵盖角色关系、角色审计和审批单例；所有管理员已过期/撤销也不是首次初始化。
灾难恢复或管理员锁死要走另行审批的恢复流程，不能删历史、清空关系或反复自举。

## 两阶段命令

固定入口类是 `cn.kokonexus.identity.tooling.OperationsBootstrapCli`。
classpath 必须包含同一已核对版本的 identity classes、service-api、MP/Spring/MySQL 运行依赖。
生产 fat JAR 不能用普通 `-cp identity-service.jar` 当作已包含 BOOT-INF/classes；应在服务器受限
的专用临时目录解包同一已核对 SHA-256 的 JAR，classpath 指向 `BOOT-INF/classes` 与
`BOOT-INF/lib/*`。不能运行 Boot JarLauncher，它会启动业务服务。
只允许在受控内网/服务器环境连接数据库，公共网络连接还须校验 TLS 证书。

命令参数固定为七项（下述变量由负责人显式填写，不提供真实或默认值）：

```bash
java -Xms16m -Xmx48m -XX:MaxMetaspaceSize=64m -cp "$verified_classpath" \
  cn.kokonexus.identity.tooling.OperationsBootstrapCli \
  plan "$database" "$user_id" "$exact_handle" "$original_request_uuid" "$shanghai_expiry" "$ticket_reason"
```

到期格式如 `YYYY-MM-DDTHH:mm:ss.ffffff`，不带 Z/UTC offset；最多六位小数。
plan 不产生账号、角色、审计或审批行。它输出明确库名、账号 ID、handle、UUID、到期时间和摘要。
核对正确后由负责人批准该具体命令，将摘要置入受保护的 `OPERATIONS_BOOTSTRAP_APPROVAL`
进程环境，再将唯一变更的模式参数 `plan` 改为 `apply`，其余六项原样保留。

摘要绑定 MySQL `@@server_uuid`、当前库、账号、handle、请求 UUID、到期时间与完整原因。
更换实例/库/账号/时间/原因就不再匹配原审批。它不是登录令牌、签名服务或替代人工审批；
拥有数据库写权限的人本已能改库，安全边界仍是服务器权限、凭据保护和责任人流程。
摘要及工单可以定位命令，不应把它们作为可公开的管理员通行证。

## 受理、重试和撤权

apply 在真实事务中锁定运营 guard 和目标账号，检查账号 ACTIVE、handle、角色启用和到期时间，
一次写入角色、递增账号授权版本、追加 `SERVER_BOOTSTRAP` 审计及审批单例。
任一步写入失败，全部回滚；同 UUID 同命令的并发只接受一次。
`ACCEPTED_FACT` 是历史受理结果，不证明角色现在仍有效，也不证明任何通知已送达。

退出码 0 表示本次工具读到了匹配的受理事实；退出码 2 表示参数、审批、状态或依赖未确认。
连接或提交结果未知时，保留原 UUID/完整六项参数/原摘要，先检查受保护的审计。
只允许重试原命令；不新建 UUID 或用新到期时间“重试”。
同 UUID 改内容会冲突；原命令历史重试在账号改名、禁用、角色撤销后仍只返回旧回执，不重新赋权。

首次角色是短期引导权限，后续人员授权使用既有管理者密码二次确认及角色审计流程。
不得通过本 CLI 为第二人授予角色，也不得把数据库手工更新冒充正常撤权验收。
执行后的账号、审计与审批是业务事实，不当作测试垃圾清理；保留审批和版本记录。

## 已验证范围与下一步

2026-10-03 已在限权隔离 MySQL 执行全部十一份身份迁移、真实事务代理、十二路首次审批，
实际审批 CHECK 故障回滚，以及独立 CLI JVM 的无写 plan/撤权后的同命令 apply。
CLI 首次新赋权与正式网络环境仍需发布前联调，不能把历史 apply 测试说成线上首管理员已启用。
原始证据与网络限制见 [初始化与 Triple 验收](OPERATIONS_BOOTSTRAP_VERIFICATION_20261003.md)。
