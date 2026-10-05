# 绑定恢复完整网络验证（2026-10-05）

结论：隔离环境8组检查通过，使用实际新Gateway/身份/社区/资产Boot和生产Provider，
不是以传输夹具代替领域服务。完成的是恢复控制面集成，不是整个平台正式上线。
前批313项后端/126项前端证据仍独立保存；本批没有重新执行这些全量测试，不重复计入。

## 环境与授权边界

- Java21四服务在开发机，HTTP 42080/42081/42082/42086仅127.0.0.1，Triple/metadata
  42881/42882/42886仅::1。两次启动冻结相同4份JAR摘要，第一次关闭worker，第二次开启。
- 服务器限权三库：`koko_bindingnet_{identity,community,asset}_20261005`，
  测试用户`koko_bindingnet_20261005`仅获三库权限；真实Flyway分别16/15/5份。
- 自有internal网络`172.28.107.0/24`，Nacos/Redis没有宿主机发布端口；Redis认证开启。
  物理内存上限合计320MiB，Nacos允许有界交换空间；350MiB可用内存保护、60分钟租约。
  通过精确四端口SSH环回隧道访问，不复用生产Nacos组/Redis实例。
- Nacos组`KOKO_BINDING_RECOVERY_CHECK_20261005`与`_HTTP`分离；实际查询3个HTTP和3个RPC
  健康实例，身份HTTP不注册；网关真实lb发现，Dubbo严格序列化、固定契约和零自动重试。
- 五个`bindingnet_*`账号经真实HTTP注册；离线审批仅引导本批合成管理员，真实角色接口
  赋操作员/审核员/通知员。没有选择真实用户或改变生产权限/配置。
- MinIO指向不可连接的本机45999，使用合成非秘密配置。4份READY元数据及配额是明确SQL夹具，
  没有实际图片对象。历史DEAD和九代审计也是夹具，不声称真实发生十次历史故障。
  不读写共享桶、不执行READY删除；这不是图片上传/对象恢复/业务绑定提交的端到端验收。

## 本次结果

`deploy/binding-recovery-network-http-20261005a/checks.json`6组，01:53:25完成：

1. 实际四Boot、独立HTTP/RPC组发现和真实迁移。
2. 注册、隔离管理员引导、专用角色赋权；普通用户、通知员和管理员不隐含资产读权限。
3. 经真实Nacos发现的Triple调用asset begin，4个保护意图持久落库。
4. 两域错误密码/审核员无确认权限、另一登录会话不可复用证明；audit CHECK故障返回503，
   原命令查询404，真实SQL核验队列/审计全部回滚。CHECK仅在本批两库，不使用SUPER触发器。
5. 移除故障后沿用原commandId，再确认受理202；同键重试保持唯一审计，真实审计actor不接受
   伪造客户端身份。历史gen9→10；五次limit2排出十代审计，受理时asset保护尚未seal。
6. 真实撤权两域读取/恢复拒绝；重新赋权不能复活旧确认（授权版本围栏）。

`deploy/binding-recovery-network-http-20261005b/checks.json`2组，01:55:56完成：

1. 开启新worker后的四Boot与两组注册重新核验。
2. 真实调度经Triple→asset seal，四任务SENT；保护意图4→0、永久完成标记0→4。
   迟到begin返回false，重复seal无新增副作用，READY数量和配额仍不变；原命令还能查询受理事实。

实际次数见`sql-checks.log`，不是“一次RPC成功”承诺：

| 域/任务 | generation | 原累计 | 完成累计 | 本代领取 |
| --- | --- | --- | --- | --- |
| 身份/首代恢复 | 1 | 10 | 13 | 3 |
| 身份/第十代 | 10 | 100 | 103 | 3 |
| 社区/首代恢复 | 1 | 10 | 11 | 1 |
| 社区/第十代 | 10 | 100 | 101 | 1 |

身份日志保留冷启动时asset Provider尚未发现的`No provider available`。最终校验累计=
原累计+本代、预算1～10，没有为了固定次数而删除故障日志，也没有清零累计计数。

## 失败证据与UI缺口

- 预检a：PowerShell `$Phase:`语法错误，之后改`${Phase}`；另一次DateTime/ISO字符串比较
  导致错误PID不匹配，核实UTC ticks一致后修正比较。均发生在helper或业务HTTP前，没有业务写入。
  原失败脚本和两份记录保留在`deploy/binding-recovery-network-preflight-20261005a`。
- 预览45175启动过，但浏览器和备用入口均报
  `failed to write kernel assets: 系统找不到指定的路径。(os error 3)`，重置后也无法初始化。
  没有成功创建/读取页面、登录、点击或截图；不把HTTP/单元测试当UI验收。
- 当前生产构建base为`/koko/`、API为`/koko-api`；本地Boot只有`/api`。
  此预览启动没有做前缀rewrite，后续授权UI须使用明确的隔离base/API构建或测试专用代理，
  不能沿用错误的根URL声称有效。生产路由配置没有因此修改。

## 备份、定向清理与生产不变

服务器记录`backup-binding-recovery-network-20261005a`，02:00清理成功。
先生成69,662字节三库dump并核验SHA，再删除精确三库、测试用户、自有两容器与network；
counts为0/0/0/0，cleanup.exit-code为0。Redis退出0、Nacos退出143，均无OOM。
生产既有容器ID/start/status/OOM/restarts前后文件相同，9份文件摘要全部OK；
9个KOKO容器healthy、5个AI短剧容器exited，没有生产迁移、发布或赋权。

服务器归档已下载并复核：

- `deploy/binding-recovery-network-server-evidence-20261005a.tgz`，27,957字节；
  SHA256 `05acc9ca30c7c291d3211def7bdba55c743f79601f1029595297b102f6a63a4c`。
- dump SHA256 `17d002b8a6194e62da1d739f6dd26a289163eabc45d40ff18057e33d2ae49097`。

两批本地8个JVM均经launcher定向停止并有terminal记录，exit=-1是有意Kill，不称优雅停机验收。
预览进程已停止。服务器清理与归档核实后，仅停止本批精确SSH PID 102836/102552，
相应会话exit1是本地停止，不推翻服务器cleanup0；本地全部测试监听归零。
临时运行凭据JSON已删除；数据库dump含合成账号哈希/审计，仅作为受保护证据，不公开下载。

本地另行冻结`binding-recovery-network-local-evidence-20261005a.tgz`及manifest/摘要sidecar，
包含实际JAR、两批启动/HTTP/SQL日志与源快照、预检失败、服务器备份和本次文档；不覆盖
前批`binding-recovery-local-evidence-20261005a.tgz`（SHA256
`15ac02b3ed16670afcd384006b497edd2fd9999e159e38519ede20369ddd09b6`）。

## 保留的交付门槛

授权正向UI、生产备份恢复演练、生产新旧worker无混跑升级、TLS与负责人审批/发布/公网验收，
仍未完成。没有Broker、本批Redis故障注入、真实存储对象或RTC测试，不能覆盖这些门槛。
未知意图核对、READY退役/对象删除协议仍未实现，全部生产恢复开关保持关闭。
企业开发skill要求按真实层验证且区分受理/完成；据此保留合成夹具、失败日志与未验收项。
