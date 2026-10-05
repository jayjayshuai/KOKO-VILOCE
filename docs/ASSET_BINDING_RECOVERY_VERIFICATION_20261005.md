# 绑定释放人工恢复 · 2026-10-05

本批完成身份/社区两域恢复核心、专用确认与权限、Gateway API、Vue工作台及
本地/隔离数据库验证。**未发布生产；完整新链路网络、授权浏览器及上线门槛仍未通过。**
按enterprise-development规范落实字段/接口中文注释、Lombok、MP XML、增量Flyway、
失败闭合与分层证据，不声称完整P3C、安全认证或平台全部完成。

## 已实现

- 身份/社区V15新增人工代次、本代次数并回填历史attempts；身份V16新增
  `ASSET_BINDING_RECOVERY`，只含`asset:binding:read`、`asset:binding:replay`。
  无真实账号赋权、无通知/旧管理员扩权。44份旧迁移与前批冻结档案逐份哈希一致，当前47份。
- 原绑定requestId与人工commandId分离。锁原任务后检查幂等；仅无租约、DEAD、
  本代十次耗尽、原代次匹配且人工代次<10允许受理。同库事务重排并追加审计；
  审计失败全部回滚，同键改操作者/目标/原因/代次拒绝。任务已SENT仍能返回原受理事实。
- attempts永久累计最多110，本代最多10；worker按本代预算退避/耗尽、同次领取更新两计数。
  随机租约CAS拒绝旧成功/失败；不直接调用资产RPC、不删除对象、不释放未知意图、不启用READY清理。
- 专用动作指纹绑定账号、固定域、完整命令；独立确认表只存随机秘密摘要。
  本人密码使用独立提交的限速预算；五分钟票据绑定会话、授权/密码版本和DB到期。
  通知票据不能替代；当前授权检查不保证追撤已经授权的在途事务。
- Gateway从当前Sa-Token取得身份/会话，固定identity/community，独立STRICT契约白名单、
  零自动重试、同步工作离开Netty EventLoop。202只说明受理，成功/失败均no-store；
  SQL/RPC不可用不伪装成功或空队列。Lombok构造注入与Knife4j/Schema字段说明同步。
- Vue可按原requestId查询已离开DEAD的任务，显示累计/本代预算；审计5条一页、最多10条。
  本人密码确认与受理分两步，秘密只留临时内存；超时/取消/404以及未知后的拒绝不丢幂等键。
  原命令跨路由保留，刷新后可以重录完整原字段，强制未知并先查询，不生成替代ID或自动写入。
  账号/会话/域变化和卸载取消旧请求、清秘密，迟到结果不恢复秘密。公网非HTTPS禁止发送密码/
  票据；HTTP环回例外仅用于隔离验收，不能替代服务端TLS与可信代理检查。

## API / 发布配置

基路径`/api/operations/binding-releases/{identity|community}`：

- POST `/confirmations`：本人密码确认完整原命令，不排队。
- POST `/replays`：确认票据受理，HTTP202。
- GET `/tasks/{requestId}/commands/{commandId}`：原命令审计事实。
- GET `/tasks/{requestId}/audits`：代次倒序、exclusive `beforeGeneration`、limit1～20。

同时开启`OPERATIONS_ENABLED`、`ASSET_BINDING_OPERATIONS_ENABLED`、
`ASSET_BINDING_REPLAY_ENABLED`才允许写，默认均关闭。读取不需要恢复开关。
`ASSET_BINDING_RELEASE_ENABLED`独立控制异步worker，不因受理自动开启。
无直播域恢复、任意RPC服务名、默认管理员或新公网管理端口。
任务/审计使用业务DB的DATETIME(6)原值，无时区；确认沿用身份域显式上海时间。
未修改共享DB的SYSTEM/UTC配置，不将两种时间混称统一时区。

## 最终本地验证

- Java21全模块package g：313项、65份Surefire XML，失败/错误/跳过均0；
  上海时间01:25:57结束，`deploy/binding-recovery-package-20261005g.log`。
- 新核心/确认/默认关闭及HTTP测试；实际WebFlux/Sa验证身份、权限、202、no-store和无效参数。
  HTTP领域边界仍是桩，不证明完整Boot联调。真实Hessian2验证四个新record与严格白名单。
- 新3项Triple测试使用生产Gateway Client、IPv6 loopback真网络与专用确认第二跳：
  双域路由、微秒/record/列表、拒绝/限速/缺失/冲突、超时零重试及原命令后续查询通过。
  禁止injvm、断言实际bind.ip；本类框架/引用/导出全部回收。
  **领域是夹具，不算真实密码/授权/MySQL/Nacos/Redis或生产Provider完整链路。**
  IPv6 host校验告警和人为超时日志保留，不隐藏成“无错误日志”。
- Web全量126项通过，失败/取消/跳过均0；恢复专属16项运行真实TS/Vue/Pinia，网络为桩。
  `deploy/binding-recovery-web-tests-20261005d.log`。TypeScript/Vite构建d通过，
  LiveKit583.03kB chunk警告仍在，未提高阈值掩盖。
- 最终format:check c / format:debug b：325份Java changed=0、语法/字面量及幂等通过；
  XML/YAML/Vue/TS通过，格式器回归7项通过。旧SQL迁移不批量格式化。
- 没有本批授权页面正向浏览器、窄屏/键盘操作与视觉验收证据，不复用旧截图。

## 实际MySQL / 失败记录

仅复用现有MySQL8.4的两个固定隔离schema与一个只拥有两库权限的临时用户。
20分钟租约、内存/磁盘门槛、有限时备份与trap，不新增服务器JVM/容器。
本机环回SSH隧道，真实Flyway、生产MP XML、Spring事务代理。
社区授权为进程内真实身份适配，**不能与Triple夹具结果拼成完整领域网络证明**。

本地`deploy/binding-recovery-sql-20261005a/verify-*`均不覆盖：

- a：Java路径预检失败，未启动helper/访问DB，保留preflight-failure.json。
- b：两域迁移与部分身份行为通过；故障触发器因二进制日志下无SUPER权限失败。
  未提权/修改共享global参数；先备份清理此轮，再开始新租约。
- c：新租约上首个JDBC握手超时，未收到数据库包，隧道随后明确退出。
  保留冻结源码/类/退出日志；确认旧隧道退出后才重建，d的空库SQL检查仍阻止重跑已用库。
- d：新租约上helper于01:20:05退出0。身份V16/社区V15及validate通过。
  先在V14写DEAD10与未来PENDING7，证明回填不抹历史；八路同命令仅一审计、
  临时CHECK使审计失败且整事务回滚、字段冲突拒绝、有效新租约和旧租约围栏、
  第二代十次失败累计到30、十代上限拒绝与到期耗尽扫描通过。
  错密码独立预算、会话/命令/通知动作隔离、过期/授权版本变化和双域撤权拒绝通过。
  审计回滚Facade用例明确使用确认替身，其余密码确认是实际代理；该故障用例不算完整密码网络证明。
  没有资产RPC或对象删除。Flyway/MySQL8.4兼容告警保留，不声称官方认证。

原构建失败保留：b测试误用service-api未提供的AssertJ，改JUnit而非添加依赖；
f传输夹具误传限速异常构造参数，修正后g全量通过。前端a缺少store闭合致构建失败；
排版check a在最终格式前失败，后续最终日志通过。不同轮次日志/快照不覆盖。

服务器租约a/b均先非空dump和SHA校验再删除专属库/用户，cleanup退出0、计数0/0/0。
生产九文件摘要、容器ID/启动时间/重启数等前后不变；KOKO九项健康、AI五服务仍exited。
服务器归档与下载哈希相同：

- a：`e9fcbe8f5a05cf9f4868da6a0eeb375ce1a6d54e4ed0333044883ce46a0f6863`
- b：`e35e30221836bb2d014d1a5090a0fdf173c10d7395df9853aca380dd572b8082`

本机runtime.json已删除、环回隧道已停止，仅回收已完成的本轮SSH句柄。
失败数据库可从对应隔离备份恢复；这不是生产备份恢复演练。
完整交付证据档案`deploy/binding-recovery-local-evidence-20261005a.tgz`，摘要独立保存在
同名`.sha256.json`；不打包运行凭据/共享环境，外部SQL jar仅记录交付时摘要，不冒称运行时冻结。

## 尚未完成

1. 新两域实际生产配置/Provider、完整Boot、私有Nacos/Redis/HTTP第二跳与资产seal联调，
   旧Provider和SQL/RPC故障、在途撤权边界；不能只看夹具。
2. 独立授权浏览器两步确认、丢回复/跨路由/刷新恢复、审核员分页、撤权、桌面/窄屏/键盘。
3. 增量备份/恢复与发布批准；先停所有旧worker，迁移升级全部新worker/读Provider，
   最后Gateway/Web。旧attempts<10逻辑不支持人工新代次，禁止混合版本启用。
4. 域名TLS/WSS、Secure会话与可信代理、负责人、容量、异地备份、RTC双端、
   Discord/推流凭据、告警真实送达仍未满足，生产恢复关闭。
5. 未知意图永久核对、RETIRING删除围栏、对象/额度恢复尚未实现，READY删除继续关闭。

见[恢复计划](ASSET_BINDING_RECOVERY_PLAN.md)、[运行手册](ASSET_BINDING_RECOVERY_RUNBOOK.md)、
[资产阶段](MEDIA_ASSET_PLAN.md)、[企业路线](ENTERPRISE_ROADMAP.md)。
