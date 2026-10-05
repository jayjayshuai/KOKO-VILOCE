# 完整 Boot / Nacos / Redis / HTTP 三域联调

2026-10-04 11:44:13～11:45:11（Asia/Shanghai）实际验证通过，属于阶段 2 / 6 / 8 的局部联调。
按用户指定的 enterprise-development 规范验证真实网络、权限、SQL 回滚和生命周期，不以 mock 或健康检查代替业务链路。
没有发布生产 Java/Web、生产迁移或真实管理员赋权。AI 短剧保持下线，阶段 2～9 未完成。

## 实际环境与边界

- 本机四个独立可执行 JAR 进程：Gateway、identity、community、live，完整 Spring Boot 自动配置，
  实际 Sa-Token 1.46.0 / 同步 Redis DAO / Dubbo 3.3.6。使用单独安装并校验官方 SHA-256 的
  Temurin 21.0.12.1+1；不修改全局 Java、PATH 或用户工具。
- 本机 HTTP `127.0.0.1:42080～42083`，实际 Triple 和 metadata 共用 `::1:42881～42883`。
  启动器在创建账号前核对每个持有 PID 的全部监听；Gateway 没有独立 RPC 监听。
- 服务器独立 Redis / Nacos 3.0.3、internal Docker 网络、三套限权 MySQL 库。
  Redis 64 MiB，要求随机专用密码；Nacos 最终物理内存上限 384 MiB，memory+swap 合计 1 GiB，
  CPU 1，私有 tmpfs；未增加宿主机交换区、物理内存或购买资源。
- SSH 开发机端只绑定环回，远端目标为已核实归属的测试网络 `172.19.0.2/3`。
  实际 Docker internal 网络没有创建宿主发布端口，不能凭 HostConfig.PortBindings 声称端口可用。
  MySQL 使用共享实例中的三个独立库，专用用户只授权这些库，不连接生产业务 schema。
- 测试 Nacos 的认证关闭，仅证明注册、发现、配置与 metadata 网络链路，不证明生产 Nacos 认证安全。
  与生产 Nacos 不共享服务注册数据；本轮没有共用 Broker 故障注入。
- `OUTBOX_ENABLED=false`，因此重放最终为 PENDING；受理不是成功投递、消费或用户收件箱展示。
  `OPERATIONS_ENABLED=true` 只给测试 JVM，线上默认关闭不变。

## 9 组实际行为与 SQL 证据

`deploy/tests/verify-gateway-network-http-20261004.ps1` 实际退出 0，原始 `checks.json` 有九组记录。
它使用真实 HTTP，不替换 Gateway Controller / Sa-Token / RPC Provider。

1. 四个 Boot 进程和全部 HTTP/RPC/metadata 监听归属、环回地址通过。
2. 匿名附带伪造可信用户头和 Gateway key 访问运营接口仍为 401。
3. 三个合成用户经真实身份 RPC 注册、BCrypt 验证、Redis 会话登录；header/cookie 会话读取本人，
   伪造头不改变身份。错误登录为 401，原会话仍有效；新账号没有默认运营权限，死信读取为 403。
4. 离线 CLI 对测试管理员完成实际 plan/apply，审批值仅放进工具进程环境，不写证据。
   随后真实角色变更 HTTP → 身份 RPC，Sa-Token 当前权限 Provider 观察到 NOTIFICATION_OPERATOR。
   所有账号均在专用测试库，本轮不选择或提权真实账号。
5. 三个固定 group 的真实 Provider 经注册发现调用；社区/直播经真实身份第二跳授权。
   三库同事件 ID 返回各自域 summary，错误密码、跨域及另一登录会话的确认凭据均为 403。
6. 每个测试库添加固定审计 CHECK 后，实际重放 HTTP 为 503，JDBC 检查原事件仍 DEAD、generation=0、
   attempts=10、totalAttempts=10、审计为 0。移除 CHECK 后原命令/原 UUID 返回 202，重复提交仍为 202，
   原请求查询为 200；三域均 PENDING、generation=1、attempts=0、totalAttempts=10、无旧租约、仅一条审计。
   本轮 HTTP 不暴露底层 SQL 异常，未额外断言 MySQL 错误号 3819。
7. 实际撤权后全部三域读取为 403；重新赋权不使旧二次确认凭据复活。
8. 真实 Redis `CLIENT PAUSE 3500 ALL`，同步会话读取约 2023 ms 后返回 503 / AUTH_UNAVAILABLE，
   no-store / Retry-After: 1。Gateway HTTP worker 显式为 1；并行三个匿名 info 请求实际为 6 / 3 / 2 ms，
   不被同步 Redis 等待占用。暂停自然解除后，原普通用户和运营用户会话均恢复 200。
   这是隔离故障行为证据，不是吞吐、容量认证或节点故障恢复保证。
9. Redis 实际注销使原普通用户会话读取为 401。

Boot 自己执行完整 Flyway，JDBC 查到 identity 11 / community 11 / live 5 个成功历史，非手工创建业务表。
HTTP 密码、会话令牌及二次确认原值只留在运行者内存；证据只保存合成账号标识、非秘密命令与行为结果。

## 失败轮次未冒充通过

- 首次服务器 Redis 因配置文件 UID/600 读取失败；Nacos 因私有 `/tmp` noexec 无法映射 RocksDB JNI。
  修正只影响本次 Redis UID / Nacos tmpfs，原容器 ID 和日志保留。
- 修正后的 Nacos 在 memory=memory-swap=512 MiB 时 exit=137 / OOM=true，Redis 正常。
  后续未增加物理上限，改为 384 MiB + 有界 swap。首个 swap 轮实际进程启动成功，
  但错误地检查 internal 网络上未发布的宿主端口，超过 readiness 期限后本轮容器被正常停止；
  并未凭容器 running 把此轮标为通过。第二轮改为核实的容器私网 IP，readiness=ok 才开始业务验证。
- Windows 本机 Nacos a/b 遇到早期 identity / JWT 配置未读取，日志保留；c 虽到健康接口，
  JRaft 27848 全网卡监听，启动器拒绝并终止。本机 d 的防火墙创建/有效性读取失败，未启动 Nacos JVM，
  不当作防火墙已生效。现在本机启动器在启动前直接拒绝已知不支持全环回绑定的固定版本。
- 固定依赖 [JRaft 1.3.14 源码](https://raw.githubusercontent.com/sofastack/sofa-jraft/v1.3.14/jraft-extension/rpc-grpc-impl/src/main/java/com/alipay/sofa/jraft/rpc/impl/GrpcRaftRpcFactory.java)
  使用 forPort，忽略 Endpoint IP；Nacos 的独立 gRPC IP 配置不覆盖此 JRaft 工厂。
- 四个 Boot 的 a 轮在 Nacos 尚未就绪时退出，保留日志；启动器新增实际 readiness 前置检查。
  b 轮健康但实际 RPC 为 0.0.0.0，创建测试账号前已停止。
  [Dubbo 3.3.6 ServiceConfig 源码](https://raw.githubusercontent.com/apache/dubbo/dubbo-3.3.6/dubbo-config/dubbo-config-api/src/main/java/org/apache/dubbo/config/ServiceConfig.java)
  会把无效 localhost 绑定地址转换为自动选址/anyhost；c 轮显式使用可接受的 IPv6 环回并核查实际 socket，
  而不是只检查 YAML。只有 c 轮完成上述业务验证。

## 产物与独立复核

本轮未修改生产 Java 源码，实际验证之前修复的有界鉴权过滤器。原 223 项 / 47 XML 单测见
[鉴权线程记录](GATEWAY_AUTHENTICATION_VERIFICATION_20261004.md)，不是本轮重新执行的 Maven 测试数。
本轮没有修改或重新构建前端，不用历史前端测试充当正向 UI 验收。

保留实际成功启动的四个不可变输入 `deploy/gateway-network-local-20261004c/artifacts/`：

| JAR | SHA-256 |
| --- | --- |
| gateway | `c9b7765ab1e6bedf52d3c170a5540753e1c7e98463dbcc5310cf36b6d59ff313` |
| identity | `e898097896a0982965b21825d58bdfe0bd8f7488b525cbcedb0ff6169d56afe3` |
| community | `4f17d4812f93f10bbbcc517b07a3fc341f16efa2f5f571fb307d8910fb888bd0` |
| live | `dc11802dd9b13f5dd8ee450c5bd13d6133d9830e81b17652c33525ee6749ad23` |

- 本机日志、原始 verifier / JDBC 源码、精确进程/监听/终止记录、HTTP 结果和失败轮次，112 文件档案：
  `deploy/gateway-network-local-evidence-20261004abcd.tgz`，SHA-256
  `81b338a8afbc5101bbb020a63da6ce7411d4bde0c44ac0fcbd2428224a9345fc`。
- 服务器启动/失败日志、SQL 备份、实际执行脚本、生产前后快照与清理证据：
  `deploy/gateway-network-server-evidence-20261004ab.tgz`，SHA-256
  `dbcc2abfb75d975d10dc6fd7cabe4fe1d6c9d760f523cc3942ec785dd5b211cf`。
- 三测试库 SQL 备份 SHA-256：`de0af8961ce37069c162ec660b3cf53a11dfbbdbec038e96c6259741702317a6`。
  它只备份本次合成数据，不冒充生产全量或异地备份。
- `deploy/tests/verify-gateway-network-evidence-20261004.ps1 -Suffix b` 实际退出 0，独立核对
  原始档案/源码/四 JAR、Java 21 实际运行日志、9 组 HTTP、三域 SQL、慢 Redis、精确终止与清理。
  输出 `deploy/gateway-network-verification-20261004abcd-b.json`；Broker/UI/ProductionDeployed/StageComplete 全为 false。

## 清理与后续门槛

- 四 Boot 是持有 Process 对象的本次测试进程，测试结束显式强制终止，ExitCode=-1；
  不把它们当作优雅停机验证。Nacos 由本次 lease 控制文件停止，exit=143 / OOM=false。
- SQL 备份校验成功后，三个固定测试 schema、限权用户、两容器及独立网络删除并复查为 0。
  本机/服务器临时数据库与 Redis 明文凭据删除，隧道关闭，全部本轮端口无监听。
- 服务器生产容器 ID/StartedAt/status/OOM/restart 快照逐行相同，`.env`、Compose、Nginx 和
  Gateway/identity/community/live/notification 五 JAR 共 8 文件 SHA-256 未变。9 个 KOKO 健康检查为 healthy。
  另由开发机检查公网 AI 根/API 503，KOKO Web/发现 API 200。没有据此声称全业务或 RTC 完成。
- 本机重复 a/b JAR 和 HTTP 展开依赖在核对成功轮 JAR/档案后定向删除，合计 1,075,872,687 bytes；
  成功轮 JAR、编译检查器、源码、档案保留，可重建这些副本。删除不影响项目源码或用户文件。

下一步仍是隔离 Broker 下的人工重放实际消费/入箱、运营正向 UI/撤权/重试与清理，
之后按负责人授权、域名/TLS、告警路由、恢复/容量门槛决定迁移和发布。没有真实管理员授权时不擅自提权。
