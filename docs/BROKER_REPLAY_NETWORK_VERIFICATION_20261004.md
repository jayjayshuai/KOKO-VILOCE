# 五服务实际 Broker 人工重放验收（2026-10-04）

## 结果与边界

12:31:07～12:34:19（Asia/Shanghai）实际五个 Java 21 Boot JVM、独立 Nacos/Redis/
NameServer/Broker、四套限权 MySQL 完整迁移，七组 HTTP/SQL/Broker 行为通过。
修复 Gateway 的实际发现转发缺陷；随后 Java 21 全模块 224 项测试、47 份 XML，
失败/错误/跳过均为 0。没有生产迁移、发布、真实管理员赋权或正向运营 UI 验收。

历史 DEAD 事件是四个明确合成夹具（attempts/totalAttempts=10），不是实际等待十次失败；
LIVE_STARTED 也是合成事件，不代表真实推流开播。注册、发布创作者、关注关系、真实
Sa-Token/Redis、三域人工确认和 RPC 授权、Scheduled OutboxRelay、MQ 发送/消费、
通知事务及关注者 RPC 扇出均未用模拟替代。没有直接调用 deliver 或人工再次 send。

## 实际缺陷与修复

初始五服务均健康，Nacos 注册通知 HTTP 实例 127.0.0.1:42085；通知服务直连 200，
但 Gateway `/api/notifications` 503。检查实际网关 JAR 没有 Spring Cloud LoadBalancer。
在 gateway-service 添加 BOM 管理的 `spring-cloud-starter-loadbalancer`，新增配置回归，
打包实际包含 starter/loadbalancer 4.3.0。修正后的 Gateway 通过同一路由完成实际入箱读取，
没有把 lb:// 改成固定 URL 规避发现。官方依据见
[Gateway lb 路由](https://docs.spring.io/spring-cloud-gateway/docs/current/reference/html/)。

首次 HTTP 轮 a 已注册三个合成账号，并完成明确离线 CLI 审批、角色授予、创作者发布/关注，
在第一次通知读取遇到 503，未创建历史 DEAD 夹具。五个拥有的 JVM 已停止且日志保留。
修复网关后 JVM 轮 b 启动；HTTP 轮 b 注册另外三个合成账号，再初始化被“已有授权历史”
门禁拒绝，没有删除审批历史或直接改数据库绕过。HTTP 轮 c 登录原 a 账号、使用原授权，
重新确认访问权限及本人资料/关注基线，然后验证完整闭环。原合成管理员授权未扩展到生产。

## 七组通过行为

| 检查 | 实际证据 |
| --- | --- |
| 五服务与迁移 | 五个独立 JVM；HTTP/RPC 仅 127.0.0.1 或 ::1；身份/社区/直播/通知迁移数 11/11/5/2 |
| 真实关注基线 | 创作者已发布，真实关注关系；Outbox → Broker → 独立通知服务入箱 |
| Broker 无响应 | 仅 pause 本轮 Broker，三域实际 relay 为 RETRY、尝试累计增加、保留错误；夹具收件箱为 0，鉴权 HTTP 仍 200 |
| SQL 故障 | 身份事件发送成功后 SENT 更新被指定 CHECK 拒绝；评论入箱 CHECK 失败令 receipt 一起回滚；采样消费失败计数 2；本人标已读 204、他人（含合成管理员）404 |
| 恢复与幂等 | unpause 同一 Broker、删除指定 CHECK 后由调度和 Broker 重投自动恢复；三域 SENT/generation1、各一条审计/入箱/DELIVERED receipt，直播扇出 DONE；原命令再请求仍 202，已读时间不变 |
| 偏好抑制 | COMMENT 关闭后 SUPPRESSED receipt1/inbox0；重新开启，再重复发送仍保持抑制，最终收件箱总数 3 |
| 撤权和登出 | 合成操作员撤权后全部三域 403；真实 Redis 登出后原会话 401 |

真实 Broker 索引按不同事件键查询，前三域分别 **6/6/6** 个不同消息 ID，抑制事件 **4** 个。
暂停时已经进入网络发送缓冲的消息可在恢复后到达，因此不能把发送超时视作绝对未送达。
这些重复由实际 relay/失败确认产生，索引读取器只查询，不发送。收件箱及已读幂等由实际数据库证明。
两端保留自己的生产客户端依赖：发送端 5.3.1、通知消费者 5.3.2，没有强行合并 classloader。

## 隔离、停止与清理

服务器四个固定 label/ID 容器只在 internal 172.28.104.0/24，无宿主机发布端口；Redis64、
Nacos256、NameServer128、Broker256 MiB，合计 704 MiB 物理上限。有限 swap 仅作用于
这些容器，不增加云费用/更改主机交换区。临时 SSH 转发仅开发机环回七端口。

两轮本地 JVM 用持有的精确进程对象停止（ExitCode=-1 为测试强制终止，不是优雅停机证明）。
SQL 四库备份并校验后，停止且删除四个容器、私网、四库与限权用户；凭据失效后删除服务器
runtime.json/redis.conf 和本地临时凭据，隧道关闭且所有测试端口无监听。
服务器 lease exit0；生产容器 ID/StartedAt/状态/OOM/重启数快照一致，八份文件校验全部 OK。
九个 KOKO 容器 healthy，AI 五个容器 exited；`/koko/` 与发现接口 200，AI 根路径/API 503。
删除的是上述测试数据和临时资源，SQL 备份及失败/成功轮证据仍可恢复；没有删除生产数据。

## 可复核证据

| 归档 | SHA-256 |
| --- | --- |
| deploy/replay-network-local-evidence-20261004abc.tgz | 93a57532a284589c536d036c48bc619ed38e2e09697ada81e691e8838d6a9f31 |
| deploy/replay-network-server-evidence-20261004ab.tgz | 470914af437891e7fae9e30135659e8d1799a3899ea0db0fed3b71e4221652b7 |
| deploy/replay-backend-full-surefire-20261004b.tgz | 2af26061f2db763bac361bbd3f44cd8b654e44cdf8ce849da612bafab6c47b3f |
| 服务器归档内 isolated-databases.sql | 8587f8be3141375171fded68a28ac7323ad1dbf48715c9e2593dbe89148e60e9 |

实际 Gateway JAR：c7c3acbe9e7acb045f098babaa47a51b8052f133719887be6ff66b466fd87b83。
其他四个 JAR 见轮 b artifacts.json；通知 JAR 与先前已部署版本哈希一致，不表示这轮部署。
源码与输入快照在归档中；原始 HTTP 令牌/确认秘密/数据库与 Redis 凭据不落盘归档。
离线复核入口 `deploy/tests/verify-replay-network-evidence-20261004.ps1`，只读归档并写新的固定后缀结果。
实际复核已通过，结果 `deploy/replay-network-evidence-verification-20261004a.json`，包含六组归档/
进程/SQL/Broker/测试/清理交叉检查。之后仅删除轮 a 五个重复 JAR 和三轮 HTTP tools 提取副本，
共 992,001,454 字节；可从已校验归档恢复。轮 b 的实际五个 JAR、所有源码/日志及归档保留，
删除记录 `deploy/replay-network-local-cleanup-20261004a.json`；离线复核不依赖已删除副本。

## 下一步

人工重放的实际 Broker 入箱缺口已关闭；仍需正向运营 UI、生产审批/迁移发布与公网回归。
外部告警接收人、DLQ 到重放处置、恢复演练、容量以及真实直播/RTC/TLS 门槛尚未通过。
遵循 enterprise-development 的安全与交付要求保留失败轮、备份后定向删除，不勾除阶段 2～9 整体门槛。
