# 三域运营 SQL / Spring / Triple 联调记录

范围为阶段 2 / 6 的人工重放链路增量，不是整个阶段、完整微服务启动或正式上线验收。
AI 短剧保持下线。本文的三域产物在网关鉴权线程修复之前构建，不能作为新网关修复的运行证据。

## 实际行为

2026-10-04 在既有主机的独立限权库和单个限额 Java 21 容器中执行；没有生产用户、生产迁移或发布。

- 三个固定库分别完整应用身份 11、社区 11、直播 5 份 Flyway，校验成功；JDBC session 明确为 UTC，运营日期 SQL 显式上海时间。
- 每域建立独立 `AnnotationConfigApplicationContext`，使用生产 DomainConfiguration、Read/Replay Service、Mapper 和实际 Provider；事务管理由 Spring 建立，关键 Bean 断言为 AOP 代理。
- 生产 Gateway `OutboxOperationsClient` 的固定三组声明和生产 Provider 声明用于建立真实 TCP Triple 引用；社区/直播权限适配器通过真实身份第二跳 RPC，身份域仍使用本地权限服务。没有以纯权限桩代替这条链路。
- 合成账号通过离线审批工具和角色 RPC 初始化；迁移本身零默认赋权。普通用户读取、审核员写入、错误密码、跨会话与跨域确认均拒绝，错误密码限速事务实际持久化。
- 相同事件 ID / 请求 UUID 故意存在于三个库，验证组路由与凭据域绑定。每域指定审计 CHECK 产生 MySQL 3819，事件与审计整事务回滚；移除本次隔离故障约束后，原请求重试与重复重试只产生一条审计，代次 1、PENDING、轮次尝试归零、累计尝试不减。
- 角色 RPC 撤权后全部三域拒绝；重新赋权恢复读取但不复活旧确认，身份权限版本为 3。撤销本次拥有的身份 Provider 后，社区/直播真实适配器故障关闭，返回脱敏不可用错误；身份本地授权仍可读，不产生额外写入。
- 已检查实际 Linux `/proc/net/tcp`、`tcp6` 的 LISTEN 地址以及 Dubbo 内部元数据 URL，所有监听为环回；Docker 未发布端口，内存及内存+swap 上限均为 416 MiB。

本轮 PASS 仅证明重新进入 PENDING，不证明投递 Broker 或通知入箱；同一 JVM 中的多个模型/上下文不等同于多主机、完整 Spring Boot/Nacos/Redis 联调。

## 首轮失败与修正

a 轮退出 1、OOM=false：一个 Dubbo ApplicationModel 的第二跳引用和后续 Provider 使用不同 ApplicationConfig，STRICT duplicate config 校验失败。此前默认元数据导出还出现容器内部的通配监听；没有宿主机发布端口，失败容器已停止删除。

b 轮为每个 ApplicationModel 复用唯一 ApplicationConfig，并在首个引用前固定元数据 tri 协议与环回端口。保留 STRICT 校验和序列化白名单，未靠关闭检查通过。b 轮退出 0、OOM=false，9 个 CHECK 和最终 PASS / verified 标记齐备。

旧 c/e 轮证据仅证明当时检查过的业务协议环回与 Docker 无宿主机发布，不应追溯解读为已经检查所有内部监听；b 轮新增实际 TCP 表检查才证明这个更强范围。

## 原始证据与清理

- 本地 `deploy/three-domain-operations-evidence-20261004ab.tgz`：`0030515e1d663ae001a713541b9c83da83f996b6951ed922948d8fa9fa700a4a`。
- 当时全模块测试 215 项 / 46 份 XML，失败、错误、跳过均 0；报告修改时间 00:25:52～00:26:50。独立档案 `deploy/three-domain-full-surefire-20261004a.tgz`：`e4165370c29fd3f009243901fd0a4aeced513236cd4dd50012e014667d3c2045`。
- a SQL 备份摘要：`ebd591236ed4ddd7f1145fa2e672da06bb0a4cd1021dcb1e9e3838138954e04c`；b SQL 备份摘要：`47be7a5c02798c961967615241eabae981228e7b65a3e11e5afc6f864a847384`。
- 只读验证器 `deploy/tests/verify-three-domain-operations-evidence.ps1` 已执行，输出 `deploy/three-domain-operations-verification-20261004ab.json`；核对失败/成功退出、输入摘要、备份、9 组行为、生产快照、7 份生产文件摘要与临时库/用户归零。
- 原始服务 StartedAt、ID、OOM/restart 快照逐行不变；生产 `.env`、Compose、Nginx 和四个生产 JAR 摘要不变。两轮的三套临时库、临时数据库用户与拥有的容器已移除。
- 归档并在开发机校验后，只删除两个固定绝对路径下各约 125 MiB 的依赖解包副本；未删除 SQL、工件、日志、输入或备份。清理脚本和 `deploy/cleanup-three-domain-operations-runtime-20261004.log` 保留。清理后磁盘可用 8360 MiB，9 个 KOKO 服务 healthy，AI 五服务 exited。空间数是瞬时采样，不是容量认证。
- 清理日志已从服务器下载，SHA-256 为 `0f93894a6893d3cc01eeacccb4b9c06af83839cd753eeae0ae85b4b5f16b7e4d`；下载过程中一次 SSH 连接关闭，只重试已终止的下载，没有重新执行清理脚本。

脚本拒绝覆盖已存在的不可变证据输出目录；报告需直接调用 PowerShell 脚本，不要把旧测试报告当作本次执行结果。

## 未完成门槛

完整 Gateway 启动、独立 Redis 会话与 Nacos 注册/发现、真实 HTTP 第二跳、独立 Broker 人工重放/入箱、正向运营 UI、真实负责人初始化、域名/TLS、告警接收人、恢复与容量仍须分别完成。生产运营功能默认关闭，没有真实管理员授权，阶段 2～9 继续推进。
