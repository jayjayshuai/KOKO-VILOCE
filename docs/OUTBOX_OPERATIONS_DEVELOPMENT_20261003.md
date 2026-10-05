# 通知投递运维前后端开发与隔离验收

2026-10-03。路线阶段 2/6 的开发进展，不代表人工重放已公开上线或阶段全部通过。
AI 短剧保持下线，本轮未发布 Java 服务、Web 或生产数据库迁移，也未为真实账号赋权。

## 实现的真实契约

- identity/community/live 采用三个固定 Dubbo group，各自服务端固定域，客户端不能选择表名。
- 服务端默认 `OPERATIONS_ENABLED=false`；开启前必须完成迁移、联调、TLS 与负责人授权。
- 每次查询和重放均检查当前权限；Gateway 身份与会话摘要来自 Sa-Token，不接受客户端身份头。
- DEAD 队列按 `created_at/id` 降序复合游标，页大小 1～50；审计按受理代次分页，1～20。
- 详情也能查询 PENDING/SENT，原请求受理查询同时限定事件与请求 UUID。
- 受理为 HTTP 202，不代表 Broker 消费或通知送达；不存在受理记录时 404 不证明在途请求终止。
- 敏感响应 `no-store`；不返回领取 token，RPC/数据库故障转换为不带内部异常的 503。
- 11 种运营 RPC record 补 `Serializable`，精确列出的契约白名单保持 Dubbo STRICT 检查。

主要文件：`web/src/views/OperationsView.vue`、`web/src/composables/outbox-workspace.ts`、
`web/src/stores/operations-retry.ts`、`backend/event-outbox` 的 ReadService/Facade/Mapper，
三个发布域的授权适配和 RPC Provider，以及 Gateway Controller/Client/响应隐私过滤器。
DTO 属性有中文 Schema，运营 Mapper 实体使用 Lombok；复杂查询保留 XML。

## 前端行为与边界

独立 `#/operations` 私有路由提供三域选择、真实权限读取、DEAD 页、详情、追加审计、原因、
本人密码确认、原请求查询和相同命令重试。加载、空态、403、404、503 和未知写结果分别反馈。
账号 ID 与累计次数使用字符串，库 DATETIME 微秒原样传递，不添加猜测的时区。

Pinia 仅在当前账号/会话的内存中跨路由保留固定 UUID/事件/代次/原因；不保存密码或确认秘密。
账号或会话轮次变更同步清除；卸载取消请求、忽略迟到结果。不确定命令不能直接结束，
只读 404 也不能让页面新建请求 ID。回执/审计完整匹配才显示已受理。
浏览器刷新仍会丢失内存命令，页面明确要求先记录工单与原 UUID；这不是持久工单系统。
权限撤销后私有事件事实撤下，不以旧页面状态继续授权。

公网明文 HTTP 禁用准备/密码确认，仅 HTTPS 或本机隔离环境允许敏感交互。
这是客户端保护，不代替正式发布时的 HTTPS、网络隔离和服务端配置门槛。

## 实际执行的验收

1. 最终全模块 `mvn -q test`：43 份新报告、201 项，失败/错误/跳过均 0。
   报告时间为 22:46:24～22:47:04 左右，以 JSON 内各报告时间为准。
   真 WebFlux/Sa-Token 的 11 项 HTTP 测试使用内存会话 DAO 和 RPC 桩，不证明 Redis/Nacos 联调。
2. 真实 Dubbo Hessian2 编解码：11 种契约往返相等，包括微秒、空日期、大整数字符串及不可变列表。
   另测未列入白名单的 Serializable 载荷被 STRICT 拒绝，没有关闭序列化安全。
   首次测试因缺白名单失败，原报告保留在 `deploy/operations-codec-first-failure-20261003.xml`。
   这仍不是实际 Triple 网络、Nacos 注册和三域服务发现验收。
3. `npm test`：69 项，含新增 18 项运营状态测试，失败/跳过/取消均 0；使用真实 Vue/Pinia 和生产 TS。
   网络桩不替代真实浏览器正向业务链路。
4. `npm run build`：vue-tsc/Vite 通过，1722 模块；运营懒加载 JS 20.39 kB。
   LiveKit 懒加载块 583.03 kB 的体积警告仍存在，没有伪称优化完成。
5. c/e 两轮真实隔离 MySQL：三域原 Outbox、重放及新索引 SQL 实际执行，真实 Spring 事务代理。
   每域 64 条同微秒记录，翻页前一条变 SENT，剩余 63 条不重复/不漏；字符串精度和微秒保留。
   观察 SENT 详情、三代追加审计/代次分页、原回执不可覆写、跨事件请求 404、撤权查询拒绝。
   还复验 16 路相同/竞争请求、真实 CHECK 故障和唯一键竞争回滚、旧租约及累计次数。
   这里的授权是隔离权限事实夹具，不是生产 RBAC、密码或 Redis；SQL 用 ScriptUtils 执行，
   不冒称本轮三域完整 Flyway 历史启动已验收。
6. d 轮加入 3000 条非 DEAD 背景并刷新统计，实际投影仍选旧 ready 索引并 filesort，断言失败。
   没有跳过或降低断言：修正查询为专用索引后 e 轮三域 `Backward index scan`，无 filesort。
   背景规模与 EXPLAIN 不代表容量压测，也不证明所有深游标/并发删除情况下恒定开销。
7. 浏览器读取本地真实新代码，匿名 `#/operations` 显示登录门禁，没有私有队列或密码确认。
   截图在 `docs/outbox-operations-evidence-20261003c/anonymous-operations-gate.jpg`。
   未伪造管理员数据，正向运营页面、响应式队列和点击重放尚未验收。

## 迁移与发布顺序

新增身份 V10、社区 V11、直播 V5：`idx_outbox_operations_dead(status,created_at,id)`。
未修改早先已应用的迁移；这些新迁移只在隔离库执行，生产未应用。
运营查询依赖该索引，不能在未迁移的库开启查询；旧 JAR 不使用新增运营索引。
身份 V8/V9 和重放迁移同样还需正式分服务备份、Flyway 核对、JAR 发布与回滚边界审核。
首管理员初始化不能通过普通注册或默认角色产生，仍须工具与负责人明确选定账号。

## 证据与环境保护

- SQL 输入/备份/失败与成功日志：`deploy/outbox-operations-evidence-20261003cde.tgz`，
  SHA-256 `caa7c33d5d66742c41db98d00002d3eb7e9a6bd0b2f0c50deefc3e1adb965b55`。
- 最终原始 43 份 XML：`deploy/outbox-operations-full-surefire-20261003e.tgz`，
  SHA-256 `b2bfee35d37c3f3bd342540dfa14a059c59e0b19caa8ecb1ee1a5e1cccc22f7e`。
- 汇总：`deploy/outbox-operations-full-tests-20261003e.json`、前端 tests/build 日志，
  只读证据校验器 `deploy/tests/verify-outbox-operations-evidence.ps1` 的实际输出
  `deploy/outbox-operations-evidence-20261003e.json`。
- c/d/e 限权三库与用户均先备份再删除，剩余计数 `0,0`；一次性 Java 容器均删除。
  失败 d 的 SQL/日志也保留。三轮生产容器 ID/启动时间/状态与 7 份文件哈希前后相同。
- 公网 `/`、`/api/` 为 503，`/koko/` 与社区发现 API 为 200；9 个 KOKO 健康服务不变。
- 清理脚本仅针对三个已验证绝对路径的依赖解包副本，原 JAR、输入包、SQL 与记录保留。
  它不是数据备份/灾难恢复工具，执行结果另见清理日志。
  实际移除三个各 125 MiB 的依赖解包副本，日志为
  `deploy/outbox-operations-cleanup-runtime-20261003e.log`，退出码 0；文件可从保留原 JAR 再解包。
  磁盘可用瞬时采样 8354 MiB，不等于容量验收。

## 下一步及未完成门槛

本记录之后首管理员离线工具/身份 V11 和 direct-loopback Triple 已补验证，见
[增量记录](OPERATIONS_BOOTSTRAP_VERIFICATION_20261003.md)；仍需真实 Spring 启动与
Triple/Nacos/Redis 完整联调、角色撤销与密码确认的
跨域链路、Broker 人工重放实际入箱、正向运营 UI、发布前备份和分服务迁移/发布/回滚核验。
告警接收人、异地恢复、容量、域名/TLS、Discord 与音视频等原路线门槛继续保留。
只有代码、局部 SQL 和 HTTP 桩通过，不能把阶段 2/6 或完整企业级上线勾为完成。
