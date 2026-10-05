# DEAD 重放核心开发与 SQL 验收（2026-10-03）

本轮是阶段 2/6 的后端核心增量，尚未接通生产运营权限、Dubbo/HTTP 或前端，不表示完整人工重放功能上线。
实施顺序与未完成项见 [重放计划](OUTBOX_REPLAY_PLAN.md)。AI 短剧仍下线，未发布或重启生产业务。

## 已实现

- identity V7、community V10、live V4 扩展 `total_attempts`、`replay_generation` 和同库重放审计表。
  三份新迁移 SHA-256 均为 `55ddb7e6ead7d023511cf7622320d4074b3cdafc7ef1335e9fd25d1f5d8e7a67`。
  原始 Outbox 三份迁移（身份 V4/社区 V6/直播 V2）字节相同，SHA-256 为
  `595f008a24a10983554943cb6506b3f3988729dfc3ada3b9810adc914d986d0b`。
- 新领取 SQL 同时增加当前轮次与生命周期累计次数；重放只重置当前预算，代次单调增加，
  不修改原通知 ID、正文、类型、业务关系或粉丝计数。
- 重放要求有效授权、标准 UUID、10～500 字符原因、确认代次、DEAD/无租约状态；每事件最多十轮人工重放。
- READ_COMMITTED 事务先锁事件再读审计，避免等待者使用旧 RR 快照漏掉已受理请求，
  不通过锁不存在的审计行引入 gap-lock 竞争。受理状态更新与审计同事务提交。
- requestId 绑定账号、事件、代次和原因；重复返回原受理时间/代次，即使事件随后 SENT，
  也不创建新投递。权限撤销后的重复请求仍拒绝。
- Lombok 用于新 ORM 快照实体，属性有中文说明；命令/回执有 Schema，复杂 SQL 用 XML。
  审计表只提供插入/读取，无应用更新/删除路径；这不是 DBA 不可篡改或外部 WORM 留存承诺。

## 本轮验证

20:02 执行 `mvn -q -pl event-outbox,identity-service,community-service,live-service -am test`，
受影响模块及依赖共 70 项，失败/错误/跳过均为 0，其中新增重放单元行为 11 项。
报告为 `deploy/outbox-replay-unit-tests-20261003.json`，不包含未重跑模块的历史数量。
之后只调整验收器异常识别、脚本及注释，未改变生产业务行为。

20:29～20:30 又执行全模块 `mvn -q test`，142 项、失败/错误/跳过均为 0，
新报告为 `deploy/outbox-replay-full-tests-20261003.json`；新增迁移兼容处理后的通知演练验收器
另用 `javac --release 21` 编译通过，不冒充重新运行 Broker 演练。
当前没有前端修改，未重复构建或把历史 UI 测试当成本轮运营页面验收。

真实 MySQL 演练加载本地本轮编译的核心 JAR（不是线上已部署 JAR），使用线上 notification JAR
内已有 Spring/MP/MySQL 依赖。临时用户仅访问三套 `koko_replay_*_check_20261003` 测试库。
测试中的 `fixture_ops_permission` 只是新鲜授权/fail-closed 的 SQL 夹具，不冒充生产 RBAC。
不启动 HTTP/Nacos、不发送 Broker 消息、不操作生产事件。

首轮 a：有存量 DEAD 的迁移、16 路相同请求等已执行，但故障捕获按 DataIntegrityViolationException
分类，实际 MySQL 3819 被当前 MyBatis/Spring 组合包装成 UncategorizedSQLException，验收器退出 1。
不得算整轮通过。保留 SQL/日志并清理成功。
修正后仅接受错误码 3819 且指定 `check_replay_audit_fault` 约束的异常，其他 SQL 失败仍使演练失败。

第二轮 b：identity/community/live 三套库分别完成以下真实 SQL 场景，九项 CHECK、最终 PASS，
验证器退出 0、OOMKilled=false，外层终端 `REPLAY_CHECK_EXIT=0`：

- 有存量 attempts=10 的迁移回填 total=10；16 路同命令仅一条审计、代次 1/预算 0，全部返回相同受理结果。
- 新领取后当前次数 1/累计 11；SENT 后重复请求仍返回原事实；撤销隔离权限后请求拒绝。
- 16 路不同 requestId 确认同一 DEAD 代次，仅一个胜者、一个审计。
- 真实 CHECK 拒绝审计写入，状态、尝试、累计、代次和先前错误全部回滚；解除故障后同命令可正常受理。
- 两个不同事件通过观察性屏障同时发现 requestId 未使用，再竞争真实唯一键；只一条审计，失败方事件保持 DEAD/代次 0。
- 真实 Mapper 领取、耗尽、重放及再领取后，旧 token 的成功/失败确认均为 0，不覆盖新轮次。

服务器 `backup-outbox-replay-20261003a`、`b` 保留原始 SQL/日志、创建容器 ID、状态、隔离配置与哈希，
生产容器 ID/StartedAt/OOM/restart 快照一致，七项配置/工件哈希一致。
三套测试库/一次性用户清理计数为 0，容器移除、临时密码文件删除。
KOKO 九个健康检查通过、Web/API 200，AI 根入口 503 且五个业务容器 exited。

证据归档 `deploy/outbox-replay-evidence-20261003ab.tgz` SHA-256：
`558214c94d8c05d1eb183f538710d630102e6b6c94d3ad81c27fc81173d6c79a`。
其中包含失败与成功轮次、原始数据库快照、输入包/脚本/校验清单；不含生产 `.env` 内容或一次性密码。

20:25 本地证据校验器独立验证归档/SQL 哈希、前后快照、七项配置/工件、失败/成功退出状态、
九项 CHECK 覆盖和清理计数，并从本机请求公网：根/API 503、KOKO Web/发现 API 200。
报告为 `deploy/outbox-replay-evidence-20261003.json`，可读原始证据为
`deploy/outbox-replay-evidence-20261003ab`。
随后定向移除了五个本批次生成的依赖解包 runtime 目录（通知 a/b/c、重放 a/b），
原始发布 JAR、测试包、SQL、日志与标记均保留，可从原始 JAR 重新解包；不是删除项目。
清理脚本核对原始 JAR 哈希、无活动验证器、备份/快照、精确路径与无符号链接后执行，
退出 0；删除前各目录约 249/249/249/125/125 MiB，完成后系统盘可用 8374 MiB（瞬时采样）。
服务器日志为 `stage-outbox-replay-20261003b/cleanup-runtime.log`。

## 发布与旧版本兼容门槛

新领取 XML 要求新列，不能先把公共 JAR 复制进未迁移的服务。三域分别停止旧发布者，
由新服务 Flyway 扩展并回填，再就绪放行；不是双实例零停机验证。本轮迁移只在测试库应用。
回退旧 JAR 不删除新列/审计表，普通旧投递仍可执行，但旧 XML 不维护 total_attempts。
重新启用新重放前应以各轮审计 previous_attempts 之和加当前 attempts 对账/恢复累计值，
不能把回退期间的统计滞后当成真实零尝试。对账脚本与完整发布/回退演练尚待补齐。
普通投递以每轮最多十次失败为预算；人工重放另外最多十轮，审计保留每轮失败，不绕过幂等回执。

下一步接通持久 RBAC、Sa-Token、二次确认、三域 RPC 和 Vue 运营路由，补分页/审计查询、
HTTP/权限撤销/前端交互及真实 Broker 受理后送达验收，再经过备份和发布。
没有真实管理员初始化授权时保持关闭；不擅自提权现有账号，不勾除阶段 2～9 门槛。
