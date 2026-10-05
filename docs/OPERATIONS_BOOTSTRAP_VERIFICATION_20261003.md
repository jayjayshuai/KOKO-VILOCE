# 离线首管理员与 Triple 网络开发验收

2026-10-03，阶段 2/6 的增量代码及隔离验收，阶段仍进行中。
没有发布服务或前端，没有执行生产迁移，没有选定或授权真实管理员。AI 短剧保持下线。

## 初始化实现

新增 `OperationsBootstrapCommand/Service`、MP XML Mapper、Lombok 审批投影及离线
`OperationsBootstrapCli`。Service 不注册 Spring 扫描 Bean，不暴露 HTTP/RPC；工具不启动
Flyway、Web、Nacos 或 MQ。身份 V11 是新迁移，不修改早先已应用的 V8/V9。

负责人明确核对数据库实例、库、现有账号 ID/handle、请求 UUID、上海到期时间与工单原因。
plan 只读；apply 须匹配完整摘要，并检查首次授权历史、ACTIVE 账号、启用角色及未来一天内到期。
角色、账号授权版本、追加审计、单例审批绑定在同一真实事务中提交；历史记录不当作当前授权。
手册见 [负责人初始化运行手册](OPERATIONS_BOOTSTRAP_RUNBOOK.md)。

## 本次实际执行

- `backend/mvn -q test` 退出码 0：46 份新 XML、215 项，失败/错误/跳过均 0。
  报告修改时间 23:32:58～23:33:53（上海）；Surefire 没有 timestamp 属性，使用原文件时间，
  不捏造 XML 时间。当前本地执行 JDK 26，源码编译目标 Java 21；服务器隔离进程为 Java 21。
  既有 Mockito 动态 agent、Unsafe 和 commons-logging 警告仍存在。
- 其中新增 8 项初始化用例、2 项 CLI 参数用例、4 项真实 Triple 网络用例。
  无前端改动，本轮没有重新执行前端；上一轮 69 项前端报告与构建仍单独保留。
- 限权隔离 MySQL 从已有七份迁移和九个合成用户继续执行，真实十一份 Flyway 全部成功；
  迁移未自动产生角色、审计或审批行。显式 UTC session 下仍使用上海业务到期时钟。
- 独立 CLI JVM 的 plan 与真实事务代理 plan 得到同一绑定摘要，均零写入；未审批/错误审批、
  不匹配 handle、禁用账号和过长有效期被拒绝。
- 最后审批 INSERT 注入真实 MySQL CHECK 错误 3819，角色、账号版本、审计和绑定全回滚，
  没把其他异常或 mock 故障当作 SQL 验收。
- 去除隔离约束后，12 路实际首次 apply 只写入 1 角色、1 审计、1 绑定，账号版本为 1；
  全部回执相同，另一个账号或修改原命令不能再次初始化。
- 隔离库模拟撤权、改 handle、禁用账号后，原用例与独立 CLI apply 返回历史回执；
  角色仍撤销、账号版本仍为 2，无新审计/绑定；零个有效管理员也不能自动重新初始化。
  此处 CLI apply 验证的是历史重复路径，不冒称 CLI 已对生产首次新赋权。
- 还复验原有真实 RBAC/密码二次确认、独立 REQUIRES_NEW 五次预算/12 路竞争、
  外部连接改密码的新鲜读取、审计失败回滚、guard 后撤权、凭据到期与清理。

## Triple 网络范围

测试调用真实 `OutboxOperationsClient`、Dubbo 3.3.6 服务/引用、Triple socket 与 Hessian2 STRICT。
三个固定 group 到达各自服务，保留微秒/大整数字符串/审计列表，业务异常跨网络仍正确。
人为让处理越过调用超时，客户端给出未知结果且没有自动重试；放行后原 UUID 可查询受理事实。

首轮虽然 4 项测试通过，但 Dubbo 把配置的 IPv4 127.0.0.1 换成了局域网地址；不能算环回隔离。
首轮监听已关闭，原报告 `deploy/operations-triple-first-non-loopback-20261003.xml` 保留。
后续改为 IPv6 `::1`，在每个实际 export URL 断言 `bind.ip=::1`，不是只检查输入配置。
本轮全量测试再次通过，拥有的端口 60799 收尾后无监听；不停止用户其他端口。

领域仍是内存夹具，direct URL 没用 Nacos，auth/Redis/Spring 发布装配没有参与；
因此这里只证明严格序列化的真实传输，不证明三域数据库、Sa-Token、权限 RPC、
Nacos 发现和 Broker 已完整联调，不能把这一项替换完整正向运营链路。

## 证据与环境保护

| 工件 | SHA-256 |
| --- | --- |
| `deploy/operations-bootstrap-evidence-20261003c.tgz` | `9630ad336ef5ecf8031157f4b0d33c9df72326ebd5f0230cec19346e1966d301` |
| `deploy/operations-bootstrap-full-surefire-20261003c.tgz` | `0e704cd6e43ecb3f660a45aea32dabc942b4562b38533c247a6f70f3a0580332` |
| SQL 输入 `core.tgz` | `9f1b678237799e22ab88cd079809a2e36c4b51e5f2a5e538d8e5df82d01f8cbd` |
| 隔离库 SQL 备份 | `370bbdaa329e45567fd03af5d8aebdd81f79a17c8fe74b7de8ac57f4735a56fb` |

SQL runner 退出码 0，隔离库、限权用户和临时容器先备份再清理，剩余计数为 `0,0`。
生产容器前后 ID/启动时间/状态/OOM/重启数相同，7 份生产文件哈希相同。
9 个 KOKO 服务仍 healthy、AI 五业务容器 exited；KOKO Web/API 200、AI 根入口 503。
只读证据核验器 `deploy/tests/verify-operations-bootstrap-evidence.ps1` 已实际通过；输出
`deploy/operations-bootstrap-verification-20261003c.json` 明确标注未发布、未真实赋权、阶段未完成。
首轮 JSON 因 PowerShell 行对象附带 Provider 元数据过大，保留原输出，修正为纯字符串后再核验；
这不是 SQL 失败，也没有替换原始测试报告。

本轮测试库的备份仅是合成数据证据，不是生产备份恢复或异地灾难恢复演练。

## 后续实际身份 RPC 与 SQL 联调（e 轮）

本轮继续用限权库和一次性 Java 21 进程，384 MiB 硬内存/无额外 swap、无映射端口，
不注册 Nacos。启动前可用物理内存门槛 800 MiB，运行期间低于 300 MiB 则中止并清理。
只在容器内 `::1` 注册真实 `OperationsAuthorizationRpcServiceImpl` 与 identity
`OutboxOperationsRpcServiceImpl`，用实际 Gateway Client 网络引用，保持 STRICT/retries=0。
其中 Service 是真实 Spring 事务代理/MP Mapper，但没有启动完整 Boot ApplicationContext。

实际 Provider 读取 MySQL 权限、独立预算及摘要确认，拒绝错误密码、禁用账号、
错误会话和跨业务域确认；队列读取实际 SQL。实际 Outbox 审计 CHECK 故障经 RPC 转为不带
内部 cause 的 503，Mapper 边界确认错误码 3819 和指定约束名，事件及审计整事务回滚。
解除隔离约束后，原确认/原 UUID 重试只产生一条审计，状态为 PENDING，不等于发送/消费完成。
实际管理者角色 RPC 撤销后，账号版本增加，远程权限/队列/旧凭据立即拒绝，无额外重放事实。

14 组 SQL 行为检查全部通过，含前面的 4 组 bootstrap；runner/清理均退出码 0。
SQL 备份后，临时库/用户剩余 `0,0`、容器已删除；生产快照及 7 份文件哈希不变，9 个健康服务正常，
AI 五服务仍 exited。工件 `deploy/operations-rpc-sql-evidence-20261003e.tgz` SHA-256
`db892a6a7c7e051b645787854794bcb06c767187be2a71b9b484c314380d0d99`，隔离 SQL 备份
`5501926cda874862b2b6861f9d70a031c84d62578204cf3749ace86bb5270dd2`。
只读核验器以 PowerShell 7 直接执行 `-RpcSql` 已通过，实际汇总为
`deploy/operations-rpc-sql-verification-20261003e.json`，核验时间 2026-10-04 00:02（上海）。
此前 Windows PowerShell 调用未选中预期轮次，因 c 的不可变目录已存在而拒绝；没有覆盖旧目录，
之后直接执行正确模式。d 是未执行的构建，发现需精确观察指定 SQL 故障后使用 e，不算失败或成功测试。
本次扩充的是验收 harness，生产源码未再变动，全模块测试仍引用本次 23:33 的 215 项原报告。

这比领域夹具的纯网络检查更进一步，但没有验证社区/直播权限适配的第二跳 RPC、
三域独立库、Nacos 服务发现、Redis/Sa-Token、HTTP/UI 或 Broker，完整联调仍未通过。
c 轮已移除 125 MiB 可再解包依赖，原工件、SQL、日志保留；不是删除项目或备份。
e 轮同样移除 125 MiB 副本，清理脚本退出码 0；原证据包/SQL/JAR 均保留，可再解包。
SSH 在清理发送前两次断开，重新只读检查确认日志不存在、目录仍在且没有清理进程，
之后才在新连接执行；不是把观察超时当成失败后盲目重跑。
最终磁盘可用瞬时值 8367 MiB，KOKO Web/API 公网 200、AI 根入口 503；不是容量认证。

## 未完成门槛

继续验证完整 Spring 启动/Dubbo/Nacos/Redis、社区/直播第二跳权限 RPC 与三域重放联动、
Broker 人工重放实际入箱、正向运营 UI、生产备份恢复及分服务发布回滚。
真实运营账号、域名/TLS、告警接收人及容量仍需负责人输入/授权；没有为此新增付费资源。
阶段 2～9 和正式上线仍保持未完成，不用本局部验收缩小原目标。
