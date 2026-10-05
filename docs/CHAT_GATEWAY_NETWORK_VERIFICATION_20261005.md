# 聊天完整 Gateway 网络验证 · 2026-10-05

本批八组完整网络行为通过，**未发布**。真实共享会话/发现/WS 入口已经有隔离证据，
不是上一批固定身份夹具，也不等于正式公网、浏览器双端、容量或语音房 P0 全部完成。
按指定企业规范保留失败轮次、冻结产物、先备份后清理，未改动生产或恢复 AI 短剧。

## 实现

- Chat 的 Nacos HTTP 注册新增 `koko-chat-websocket-port`，取本节点 `CHAT_WS_PORT`。
- 新增 Gateway `ChatWebSocketTargetFilter`，位于实际 ReactiveLoadBalancer 选择之后，
  只映射所选聊天实例的 Netty 端口；主机及原始编码路径/查询保持，不另选节点。
- 实际 Nacos 实例显式返回 HTTP scheme，覆盖 SCG 的 ws override；因此明确验证原路由
  `lb:ws://koko-nexus-chat` / `lb:wss://koko-nexus-chat` 并保留该 WS/WSS 意图。
  不把 HTTP 端口当 WS，不降低明确的 WSS，不将元数据解析为任意 URL。
- 无效/缺失元数据、错误选择/协议返回固定 `503/CHAT_ROUTING_UNAVAILABLE`、
  no-store/Retry-After=1，传输链不继续。计数不含用户或节点标签。
- 原定址 WS 入口兼容；源码及 Compose 仍默认单节点定址，需显式配置才启用发现。
- Chat 新增 `REDIS_PASSWORD` 配置绑定；原有 1800 秒活跃超时保留，没有宣称本批新增该超时。

## 最终自动检查

- 全 Maven reactor `package`：357 测试、72 份 XML，失败/错误/跳过均0。
- 新 Gateway 目标测试10项，另有真实 YAML 配置解析/每节点端口绑定检查。
  使用实际 NacosServiceInstance 和 SCG DelegatingServiceInstance/URI 重建，
  不用只返回 null scheme 的默认实例掩盖依赖行为。
- 格式 check/debug-check：356 Java 文件及匹配的 XML/YAML/Vue/TS/脚本通过。
  格式/敏感信息守卫回归17项通过，git diff --check通过。
- 本批无前端业务改动，未冒称重新执行了前端或浏览器验收。
  上一批146项前端测试与生产构建是历史证据，见[聊天 V4 记录](CHAT_CLUSTER_SYNC_VERIFICATION_20261005.md)。

## 实际环境与产物

四个完整 Spring Boot JVM 在开发机，另外重启一次 chat-a。可执行 JAR 冻结后运行，
使用生产 Controller/Filter/Service/MP XML/事务/Sa-Token/Triple Provider，不注入身份替身。
全部实际监听地址按 PID 核验为127.0.0.1或::1，核验后才注册合成账号。

MySQL8.4.11在本机独立数据目录/环回33068，测试账号仅授权两库，不连接生产数据库。
执行完整身份17份及聊天4份迁移；这不表示身份绑定核对的全部业务已验收。
独立Nacos3.0.3和需密码Redis7.4在已授权服务器的私有Docker网络，仅通过SSH转发到本机。
没有发布容器端口；Nacos auth关闭，不据此声称生产注册中心认证已经验收。
服务器未新增业务 JVM/MySQL；Nacos256MiB物理/1GiB含交换限制，Redis64MiB/16MiB数据限额。
启动前资源、镜像和子网冲突检查；低于可用内存350MiB触发租约退出。

| 角色 | HTTP | Netty WS / Triple |
| --- | --- | --- |
| Gateway | 42190 | 无WS监听；RPC消费端 |
| 身份 | 42191 | Triple43191 |
| chat-a | 42197 | WS42997；RPC消费端 |
| chat-b | 42198 | WS42998；RPC消费端 |

HTTP注册独立 `_HTTP` 分组，RPC使用原分组；真实HTTP列表只有42197/42198及对应WS元数据。
测试 LoadBalancer 缓存TTL为1秒，不能推断生产默认缓存下的摘流耗时或端到端SLA。
传输是本机HTTP/WS和SSH隧道，不是公网HTTPS/WSS/RTC验收。

实际最终 JAR SHA256：

- gateway：`42d91c9afa60c70bb4904b1c8ac8ded9ed6a1e58e5e6c7109c3e2fe44d1d98e4`
- identity：`6c0975a487f526e77125032f44bda4a1602aaeaaf17594fc15211822e07c868f`
- chat：`882aee74fe4ad81a1caabbf0b9347770e7718be46d8b740d0aeb1baef3a3b90b`

## 八组真实行为

1. 四Boot/实际Nacos：聊天HTTP与WS端口配对、两实例，聊天V1～V4成功。
2. 经Gateway注册三个合成账号、建立真实Cookie会话，身份目录由实际Triple/RPC返回；
   伪造可信身份/密钥头不能冒充群主，匿名401、他人历史404、下游无内部密钥直连403。
3. 同一Gateway的两个WS连接实际落在不同节点，通过各节点认证用户指标核对；
   已提交ACK senderId为真实本人，远端无正文SYNC，HTTP补拉真实消息。
4. 经实际API新增/移除成员，通知新成员/被移除成员；新增只读加入后的消息，
   移除后列表为空/历史404，不因提示取得权限。
5. 暂停**本轮Redis**：Gateway认证503，既有Netty发送返回UNAVAILABLE，SQL消息数不增；
   恢复后真实会话可用，新WS连接继续提交，序号连续。没有暂停生产Redis或其他项目缓存。
6. Gateway注销真实Cookie会话，旧Cookie访问本人接口401；
   同一旧会话在两个Netty节点的已认证连接均拒绝后续PING并返回AUTH_REQUIRED。
   两个撤销探针通过内部可信密钥连接测试节点，使用真实会话，不伪造登录成功。
7. 优雅关闭chat-a，Nacos解除注册；新Gateway连接使用存活chat-b并持久提交消息。
   不是kill -9/网络分区、自动恢复或高可用容量认证。
8. 同冻结chat JAR的新JVM/PID重新启动chat-a；两实例再出现，四条已提交历史仍可读；
   新观察者重取版本，改名/解散后SYNC与真实历史404。

合成账号随机密码、真实会话令牌、SQL/Redis凭据仅在内存/进程环境传递，不进入命令参数或
测试输出。HTTP只在环回使用；没有用用户真实账号登录这些测试服务。

## 失败轮次

- 完整配置解析发现本批误加了重复active-timeout键，修正后重跑，未放宽YAML解析。
- 首轮完整启动器同时设置shutdown.enabled和shutdown.access，Boot3.5拒绝互斥属性，
  未进行账号/聊天检查；只保留access，并修正清理逻辑，避免把启动失败误报为停机失败。
- 第二轮四Boot和真实HTTP检查通过，但WS503。实际Nacos提供HTTP scheme覆盖ws；
  原默认实例测试没覆盖此行为。保留失败JAR、日志、合成库备份并回收资源；
  修正原路由协议保持，新增实际Nacos投影测试后第三轮八组全部通过。
- 首轮仅身份JVM启动失败退出1；后两轮已启动的全部JVM（含重启前后chat-a）均优雅退出0。
  不抹除失败轮次，不把首轮退出1改为正常停机。

## 备份、清理与隐私

最终两库一致性SQL备份SHA256：
`5034734f832a77962e48f0675cf34c28c79d52f0a0842343292f31e4bcf3a731`。
备份校验后定向删除两个合成库和限权用户，剩余库/用户`0/0`；本机MySQL退出0。
各Boot进程、转发器/监听端口已回收。失败轮次也有备份和清理证据。

最终服务器证据包SHA256：
`22b0d21c22f312ad0b5e0bec4cd6ffc27c884c834d8b44baef154b169105b44a`，下载后摘要一致。
核验精确容器ID/标签后回收本轮两个容器和私有网络，删除本轮临时缓存密码文件；
生产11个文件摘要/所有既有运行容器快照未变，KOKO健康、AI短剧仍下线。
这只是本次前后快照一致，不代替持续健康或全部云安全组审计。

JAR、辅助程序、日志、合成SQL和失败证据仅放本机私有归档，不进入GitHub。
不归档MySQL数据目录、自动生成私钥、CA密钥或用户密码文件。
候选源码另用临时Git index做敏感信息检查，正常index不改变，不自动提交/推送。

## 保留的门槛

- 正式TLS、安全Cookie与IP证书自动续期/到期监测；CA账户尚未创建，需要用户选择。
- 真实浏览器双端聊天接收、断线/注销/错误UI和移动端交互。
- 全局设备配额（目前三连接为每节点）、慢客户端与长期容量、注册中心/进程非正常故障、
  生产告警接收人及送达验证、迁移/回退和生产滚动发布/公网端到端验收。
- 语音房麦位/房间事件/媒体授权与补偿，Discord/直播及企业路线其余阶段。

完整目标保持进行中，不把这批网络通过标成阶段2～9或语音P0全部完成。
发布前先升级所有chat节点并确认元数据，再显式切WS发现路由；默认入口未自动改变。
见[Gateway实施计划](CHAT_GATEWAY_CLUSTER_PLAN.md)、[运行手册](CHAT_RELEASE_RUNBOOK.md)。
