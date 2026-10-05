# 通知可靠性隔离演练记录（2026-10-03）

本轮验证阶段 2 的 Broker 中断、恢复、重复投递和消费事务失败，不代表阶段 2 或全平台正式上线。
AI 短剧保持下线；共用 Broker、生产业务库、业务容器、配置和 Nginx 路由不得用于故障注入。

## 工件与真实性

验收器使用 `NotificationReliabilityCheck.java`，加载运行容器实际 `/app/app.jar` 对应的发布工件。
宿主脚本先校验发布 JAR 与运行容器 JAR 一致，再解包到只读挂载的隔离目录。
不启动业务 HTTP/Nacos 服务，不调用生产用户注册；仅在限权的两个测试库中创建非登录夹具。
真实 MP/XML、Spring 事务代理、OutboxRelay、RocketMqEventSender、通知用例和 RocketMQ 消费者参与验证。

- identity JAR SHA-256：`4168b40c487e86222a4356b54d85f4744c33e1b12c77193a9c913adbb8aa8bd8`。
- notification JAR SHA-256：`6f42013e07f58b1f63680d0c46ed5a918f47d9b3875aa0d2e64b88f99522ca0a`。
- 本轮准备时受影响模块 Maven 回归 35 项，失败/错误/跳过均为 0，报告为
  `deploy/notification-reliability-unit-tests-20261003.json`，时间为 19:11。
  此后只修改验收器/宿主脚本，未改生产业务 Java、迁移或前端，不冒充重新执行全模块测试。

## 尝试 a：启动失败，不能算验收通过

首轮独立 Broker 在 TimerWheel 初始化时抛出 `OutOfMemoryError: Direct buffer memory`，退出 255，
Docker `OOMKilled=false`；验证 Java 容器未启动。没有进入业务场景，空测试库备份不是业务恢复证据。
已备份日志/空测试库并清理临时库、用户、容器和网络，生产前后快照及配置哈希一致。

实际缓存的 `apache/rocketmq:5.3.2` 镜像字节码确认：默认时间轮有 604800 个槽，
长度为 `604800 × 2 × 32 = 38707200` 字节，约 36.9 MiB；仅 32 MiB 直接内存无法初始化。
时间精度不改变这里固定槽数，不能凭修改精度假称降低该分配。
后续保留默认时间轮/重投行为与 384 MiB Broker 容器硬上限，将最大堆从 192 调为 128 MiB、
直接内存从 32 调为 96 MiB。未改变共用 Broker 或增加云资源费用。

失败证据保留在服务器 `backup-notification-reliability-20261003a`，不可覆盖。

## 尝试 b：业务场景通过，但客户端依赖边界需补验

`stage-notification-reliability-20261003b/run.log` 保存业务 `PASS`、`CLEANUP PASS`，
外层终端输出 `CHECK_EXIT=0`；验证器真实退出 0 从 `temporary.final.txt` 核实。
实测包括：

- 真实关注提交 → Outbox → Broker → 收件箱 1 条；本人已读，他人已读拒绝。
- 仅停止脚本创建的独立 Broker：关注/粉丝计数提交，事件 RETRY、退避和租约释放持久化；
  恢复同一个 Broker 容器后事件 SENT、通知最终入箱，不重复关注。
- MySQL CHECK 拒绝 SENT 更新：消息已发送/入箱，解除故障后重发实际到达消费者，
  收件箱/回执各 1 条，已读时间未被改写。
- MySQL CHECK 拒绝收件箱写入：消费失败，回执/通知事务回滚为 0；解除故障后由 Broker
  RECONSUME_LATER 重投成功，验收器不主动再次 send/deliver 此事件。
- 偏好抑制后的重复投递不复活通知；业务外层事务异常回滚关系与事件；旧租约 CAS 被拒绝。
- 最终关注 6、粉丝数 6、收件箱 5、回执 6，未投递/DEAD 事件均为 0。

该轮合并 classpath 时优先选中了 identity 的 RocketMQ 5.3.1；线上 notification 自有客户端为
5.3.2。因此 b 能证明上述生产业务类/SQL 在该组合下的行为，不能独立证明两套线上客户端边界。
不删除这项限制；c 改为发送端私有类加载器、消费端 notification 自有依赖，并断言类来源。

## 尝试 c：线上双客户端依赖隔离通过

本轮验收器将真实发送适配器与其 identity 依赖隔离加载，事件/JSON/接口仍共享；
每次 send/close 恢复线程上下文加载器。消费端使用 notification 运行库优先的 classpath。
在启动消费前输出两端客户端 JAR 来源，并拒绝客户端被错误统一加载。
该方式是同进程依赖隔离，不冒充两个完整生产 JVM 的 HTTP/Nacos/线程池/容量验收。

实际输出确认发送端来自 `identity/BOOT-INF/lib/rocketmq-client-5.3.1.jar`、
消费端来自 `notification/BOOT-INF/lib/rocketmq-client-5.3.2.jar`，不同类加载器。
c 完整重复上述业务场景，输出六项 CHECK（含运行依赖来源）及最终 PASS，
关注/粉丝各 6、通知 5、回执 6、outstanding=0、dead=0。
验证器退出 0、OOMKilled=false；随后 `CLEANUP PASS`、`CHECK_EXIT=0`。
两套测试库和测试用户复查各为 0，临时一次性凭据文件已删除。

服务器证据目录 `backup-notification-reliability-20261003c` 包含已部署工件哈希、
隔离配置/网络、容器退出状态、实际消费日志、隔离数据库备份及 SHA-256、
生产前后快照和配置校验；控制信号文件保留在对应 stage 的 working/control。
本轮没有发布新的业务 JAR、前端、迁移或重启任何生产服务。

三轮证据已下载到 `deploy/notification-reliability-evidence-20261003abc.tgz`，SHA-256
`088b9d8ccbb2597fea0aa4c7b52b2591f32823b409061f3cf45bf5c2468e33ca`。
本地 `verify-notification-evidence.ps1` 独立核对归档哈希、各轮数据库备份哈希、生产快照、
五份配置/工件校验、清理计数和实际客户端来源；19:49 从本机构造真实公网 HTTP 请求，
根首页/API 503，KOKO Web/发现 API 200。结果为 `deploy/notification-reliability-evidence-20261003.json`。
可读原始证据在 `deploy/notification-reliability-evidence-20261003abc`；本轮测试库删除后仍可审计。

## 资源与清理边界

测试库：`koko_notify_identity_check_20261003`、`koko_notify_inbox_check_20261003`。
测试用户：`koko_notify_check_20261003`，仅有两套测试库权限；一次性密码文件不进日志/仓库。
测试 Broker/NameServer 无宿主端口；验证器只加入测试网络与 MySQL 所在内部网络，不挂 Docker socket。
NameServer/Broker/验证器总内存硬上限分别为 160/384/320 MiB，各自 memory-swap 等于 memory，
不以 swap 扩容当容量通过。开跑 MemAvailable 至少 900 MiB、磁盘可用至少 6000 MiB，
在途保护至少 300 MiB 可用内存；上限与瞬时读数不是压力测试结论。

每轮独立的 stage/backup 目录拒绝覆盖。宿主脚本只按本轮创建的精确容器 ID/标签停启与删除，
先保存日志、隔离 SQL dump 及 SHA-256，再删除测试库/用户；保留失败证据。
生产运行容器 ID/StartedAt/OOM/restart 前后快照和 `.env`、Compose、Nginx、两个 JAR 哈希须一致，
九个 KOKO 健康检查通过，Web/API 200、根入口 503、五个 AI 业务容器 exited 后才输出清理通过。

同日后续已定向回收三轮 `working/check/runtime` 依赖解包副本；原始发布 JAR、三轮测试包、
日志、SQL/哈希、控制标记和本地证据归档保留。依赖可从哈希一致的原始 JAR 重新解包，
不能据此称测试环境仍在运行或再执行历史 stage。回收详情见
[重放核心记录](OUTBOX_REPLAY_CORE_VERIFICATION_20261003.md)。

## 尚未覆盖

社区评论/直播粉丝大扇出故障、Broker 毒消息/DLQ 运维、管理员 DEAD 重放与不可变审计、
外部告警接收人、异地备份/恢复、完整通知 UI、真实开播、双节点/容量/实时性仍未通过。
通知链路没有“恰好一次”或 HA 保证；此演练不能勾除阶段 2 其余门槛。
