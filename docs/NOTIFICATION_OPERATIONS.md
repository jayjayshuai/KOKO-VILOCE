# 通知链路值守手册（阶段环境）

> 公开版：部署地址与用户目录已脱敏，须按实际环境替换；不是原始验收地址。私有证据未改动。

2026-10-03 的独立 Broker 演练见 [可靠性记录](NOTIFICATION_RELIABILITY_VERIFICATION_20261003.md)。
发送/消费的真实故障与重投在隔离库通过，未变更生产事件或共用 Broker。
`deploy/tests/run-notification-reliability-check.sh` 不是生产恢复工具：固定的测试名称、
不可覆盖 stage/backup、限权库和精确 ID/标签仅用于本批次验证；不得拿它修改生产 DEAD。
首轮 a 的直接内存启动失败、b 的共用客户端加载限制和 c 的补验均保留，不重复覆盖记录。

后续 [DEAD 重放核心](OUTBOX_REPLAY_CORE_VERIFICATION_20261003.md) 已通过三库 SQL 验证，
但真实权限、HTTP/RPC 与运营页面尚未接通/发布；生产人工处置仍按以下门槛，不直接修改 DEAD。
新迁移和累计计数的旧 JAR 回退边界见该记录。不要把新核心类存在当成生产工具已可用。

## 告警含义与第一响应

`KokoOutboxDead` 表示身份、社区或直播库存在耗尽重试的事件；它不会自行继续发送。
`KokoOutboxStuck` 表示最老未确认事件超过 5 分钟，先检查 Broker/NameServer、服务日志、
数据库连接与 Prometheus 采样状态。`KokoNotificationFanoutDead`/`Stuck` 对应直播
粉丝扇出任务。`TelemetryUnavailable` 表示指标采样失败，水位 `-1` 不能按无积压处理。
`KokoNotificationConsumeFailures` 表示十分钟内消费失败超过三次，应核对 Broker
重试和 DLQ。所有服务离线告警先区分容器故障与监控抓取失败。

用只读查询定位，替换 `<业务库>` 为 `koko_identity`、`koko_community` 或
`koko_live`，不要将用户提供的表名拼接到自动化 SQL：

```sql
SELECT id, event_type, recipient_id, actor_id, status, attempts,
       created_at, next_attempt_at, last_error
FROM <业务库>.outbox_event
WHERE status IN ('DEAD', 'RETRY')
ORDER BY created_at, id
LIMIT 50;

SELECT event_id, actor_id, status, attempts, follower_cursor,
       created_at, last_error
FROM koko_notification.notification_fanout_job
WHERE status IN ('DEAD', 'RETRY')
ORDER BY created_at, event_id
LIMIT 50;
```

确认业务事务已提交、事件载荷合法且目标用户/内容仍可用后，再决定重放或纠正数据。
同一事件重复进入 Broker 允许，但 `(event_id, recipient_id)` 回执必须保证通知不
重复展示。当前**没有**带审核记录的重放工具，不要直接批量修改 `DEAD` 记录；
需要逐事件登记工单、备份和复核后执行人工处置。Broker DLQ 也须独立核对，
Outbox 零死信不代表消费者没有毒消息。

## 发布与回退边界

2026-10-01 的发布前备份位于服务器
`/srv/deployment-home/koko-nexus-release/backup-stage2-metrics-202609302223`，包含四个旧 JAR、
Compose、四库 SQL dump，以及原项目共用 Prometheus 配置。四个 Flyway 索引迁移
向前兼容，回退 JAR 时不应删除已应用的迁移。共用 Prometheus 配置若需回退，先比较
现行文件是否又被其他项目修改；仅在确认无新变更后恢复备份文件、运行
`promtool check config`/`check rules` 并 HUP 热加载，随后核查原项目抓取目标。

Alertmanager 的 `local-operations` 接收器目前没有外部通知渠道；规则可见不等于
值班人员收到消息。正式上线前须配置经授权的通知接收人，并验证送达、静默、升级。
