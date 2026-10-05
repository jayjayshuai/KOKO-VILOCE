# 通知域实施与阶段验收（阶段 2，进行中）

> 公开版：部署地址与用户目录已脱敏，须按实际环境替换；不是原始验收地址。私有证据未改动。

## 已核实的运行边界

- 2026-09-30 服务器正在运行 `apache/rocketmq:5.3.2` Broker 与 NameServer；当前
  3.6 GiB 物理内存，原 8 GiB swap 使用约 92%。应运营方要求新增 4 GiB swap 后，
  磁盘可用约 17 GiB。此举不代替物理内存、延迟和故障注入验收。
- 现有业务库按身份、社区、直播分离。业务写入不得同步依赖 Broker，也不能因
  Broker 不可用而把已经提交的关注/评论/开播操作报告为失败。
- 当前 Broker 对外暴露 Remoting 端口，没有已验证的 gRPC Proxy；Java 客户端需与
  实际服务端协议匹配，不能直接假设 5.x gRPC SDK 可连接。

## 已完成的阶段环境验证（2026-09-30）

- `notification-service`、`koko_notification`、RocketMQ 主题、网关路由与前端入口
  已部署。身份、社区、直播业务的 Flyway 迁移和 Outbox 随服务滚动上线；各 JVM
  和网页容器健康，公开内容接口 200，未登录通知接口 401。
- 两个临时账号完成关注、评论、已读、偏好抑制和重复关注验证。Outbox 对应事件
  均为 `SENT`；通知收件箱仅有应展示的记录，抑制事件有 `SUPPRESSED` 处理回执。
  他人标记已读返回 404。临时账号和跨库数据已定向删除。
- 直播扇出以定向测试事件完成 `SENT` → 扇出任务 `DONE` → 粉丝收件箱 1 条的
  验证；这**不是**真实开播测试，当前直播服务缺少已配置的媒体供应商，不能推流。
- 发布前做了同机备份：`/srv/deployment-home/koko-nexus-release/backup-stage2-20260930211857`。
  它不能替代异地备份或恢复演练。物理内存仍约 3.6 GiB，新增 4 GiB swap 后
  交换区总量约 12 GiB，不能以 swap 替代容量压测。

2026-10-01 增量：三个业务 Outbox 与通知扇出新增数据库水位指标，Flyway 分别升级
至身份 V5、社区 V7、直播 V3、通知 V2；后端本次全量 60 项测试无失败/错误/跳过。
共用 Prometheus 已抓取四个 KOKO 目标，原项目四个目标仍为 `up=1`。八条 KOKO
规则已加载；临时 `DEAD` 事件让 `KokoOutboxDead` 进入 firing，定向删除后指标
归零、告警解除。发布前备份：
`/srv/deployment-home/koko-nexus-release/backup-stage2-metrics-202609302223`。
监控规则与值守处置见 [`NOTIFICATION_OPERATIONS.md`](NOTIFICATION_OPERATIONS.md)。

2026-10-03 已完成独立 Broker + 限权 MySQL 库的真实故障演练：加载运行容器对应 JAR，
发送/消费两端分别使用自有 5.3.1/5.3.2 客户端，验证 Broker 中断/恢复、SQL 确认窗口、
消费回滚和 Broker 重投、抑制幂等、事务回滚及租约 CAS。精确临时资源清理通过，
生产容器与配置未变，AI 短剧保持下线。失败尝试与限制见
[演练记录](NOTIFICATION_RELIABILITY_VERIFICATION_20261003.md)。不据此声称真实开播、HA 或容量通过。

剩余阻断项：共用 RocketMQ 仍不应在该服务器上被停机故障注入；当前 Alertmanager 只有空本地接收器，
缺少真正的值班通知渠道；Broker 自身 DLQ 的审计、Outbox `DEAD` 人工重放、
备份恢复及容量上限也未验收。未满足这些条件前不标记阶段 2 完成。

## 一致性边界

1. 关注、评论、开播在各自数据库事务内写业务数据与 `outbox_event`，事件以 UUID
   唯一标识；没有业务状态变化时不生成重复事件。
2. 各服务的投递器短租约领取事件，向 RocketMQ 普通主题投递。成功后标记已发送，
   失败后指数退避，超过上限进入 `DEAD` 状态并记录日志；Prometheus 规则已能
   检测死信和积压，但外部通知渠道仍待配置。租约到期可重领。
3. 发送成功但数据库标记失败时允许再次投递；通知服务以
   `(event_id, recipient_id)` 唯一键去重，不承诺 Broker 恰好一次投递。
4. 通知服务在自身数据库事务内消费并持久化站内通知；用户读取、已读操作只能
   使用网关注入的本人 ID。消费失败由 Broker 重试；死信消费、审计与人工重放
   工具仍待完善。
   2026-10-03 已实现并隔离验证 Outbox 重放核心与持久 RBAC/二次确认/角色审计；
   三域 Dubbo 接入、运营页面和生产发布仍未完成。见
   [权限开发验收](OPERATIONS_AUTHORITY_VERIFICATION_20261003.md)。
5. 用户偏好在消费侧判断；对已关闭的通知类型仍记录处理结果，避免反复重试。

## 验收顺序

- 本地完成迁移、服务端、前端和自动化测试，并冻结消息契约。
- 部署前校验主题与 Consumer Group、网络连通、Broker 磁盘水位，以及新增 JVM
  对现有业务和 RTC 的影响。若容量验证不通过，不上线通知容器。
- 在验收环境故意停止 Broker：业务与 Outbox 同事务成功；Broker 恢复后最终投递。
- 故意让消费者重复收到事件：通知只出现一次；已读状态不被覆盖。
- 备份、逐服务发布、公网真实事件测试、定向清理、健康检查及恢复演练后，
  才能标记阶段完成。

Apache RocketMQ 官方文档指出消费侧须自行处理重复；其 5.x gRPC Java SDK 还要求
服务端启用 gRPC Proxy。因此当前设计采用数据库 Outbox + 消费侧唯一键，不以消息
事务或 SDK 名称替代实际协议和可靠性验证。参考：
[RocketMQ Java SDK](https://rocketmq.apache.org/docs/sdk/02java/)、
[消费去重实践](https://rocketmq.apache.org/docs/bestPractice/01bestpractice/)。
