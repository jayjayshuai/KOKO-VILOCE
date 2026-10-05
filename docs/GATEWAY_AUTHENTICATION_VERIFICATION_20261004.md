# 网关同步鉴权线程隔离

属于阶段 2 / 6 / 8 的基础安全与可靠性修复，代码已完成、本地验证通过，尚未部署。
按用户指定的 enterprise-development 规范，重点验证 EventLoop、上下文释放、背压和错误边界，未以存在 reactive 依赖推断不存在阻塞。

## 原因与实现

直接读取当前固定依赖 Sa-Token 1.46.0 的 `SaReactorFilter` 和 `SaTokenDaoForRedisTemplate` 源码：前者同步执行 auth 回调，后者通过同步 StringRedisTemplate 访问 Redis。原 Gateway 的 `StpUtil.checkLogin/isLogin/getLoginIdAsString` 因而可能在 Netty 请求线程上阻塞。

新增 `OffloadedSaReactorFilter`，保留官方过滤器的路由、钩子和 finally 上下文清理，仅把同步鉴权调度到本 Bean 拥有的有界弹性池。默认 4 个工作线程、每线程 64 个等待任务；这不是 64 个全局请求上限，也不是容量认证。线程数允许 1～32，每线程等待任务允许 1～256，无效配置启动失败。

- `GATEWAY_AUTH_WORKERS` / `GATEWAY_AUTH_QUEUED_TASKS_PER_THREAD` 在 Gateway YAML 中配置；线程池随 Bean 的 close 释放。
- 调度拒绝或已关闭的池返回 503 / `AUTH_BUSY`，包含 no-store / Retry-After: 1，不回退 EventLoop、不调用鉴权或下游。
- 下游链以 defer 在鉴权完成后才组装。调度拒绝处理位于 flatMap 前，下游同步组装或异步拒绝不会被改写为“鉴权池忙”。
- 未登录保持 401；角色/权限不足为 403；鉴权基础设施或其他非授权拒绝异常故障关闭为 503 / `AUTH_UNAVAILABLE`。固定 JSON 不暴露底层异常、会话或密码。
- 所有经过此过滤器的请求共用该工作池；公共入口也不绕过饱和限制。Redis 命令超时、连接池、上游连接和整体流量限额仍需真实环境验证，该池不是完整反滥用策略。

## 本次验证

- 新增 8 项测试使用实际 Netty DefaultEventLoop、真实 Reactor 有界调度器和官方 Sa-Token 上下文，不用线程名模拟 EventLoop。阻塞鉴权期间原 EventLoop 可以执行其他任务，鉴权在 koko-auth 工作线程执行，同步上下文绑定正确且在下游调用前已清除。
- 单工作线程、一个排队任务时第三请求拒绝；池关闭后故障关闭；close 重复安全；非法配置拒绝；下游同步/异步 RejectedExecutionException 保留原异常；官方认证错误钩子与工作线程复用上下文正确；生产错误钩子拒绝 Redis 故障且脱敏。
- 原有 11 项实际 WebFlux/Sa-Token 运营 HTTP 测试继续通过，覆盖匿名与伪造身份头、实际会话绑定、密码拒绝、撤权、限速、角色命令、微秒游标、参数、202 幂等受理、404 与 503。会话存储为隔离内存 DAO，RPC 为桩，不据此声称 Redis/Nacos 通过。
- 最终全模块 `mvn -q test` 退出 0：223 项 / 47 份 XML，失败、错误、跳过均 0；时间 01:09:27～01:10:28（Asia/Shanghai），本地 JDK 26、编译目标 Java 21。报告 `deploy/gateway-auth-full-tests-20261004b.json`、执行日志 `deploy/gateway-auth-full-tests-20261004b.log`。
- 原始 XML 档案 `deploy/gateway-auth-full-surefire-20261004b.tgz` SHA-256：`38bc48c696393a8ef8fcfada56c07a591758a776bf0ee1b091e39795d32cca9d`。
- 随后 `mvn -q -pl gateway-service -am package -DskipTests` 退出 0，仅证明可打包；不代替上述行为测试。当前 Gateway 可执行 JAR SHA-256：`c9b7765ab1e6bedf52d3c170a5540753e1c7e98463dbcc5310cf36b6d59ff313`。未上传或替换生产 JAR。
- 已把此 JAR 留存为不可变的 `deploy/gateway-auth-build-20261004a.jar`，避免后续构建覆盖本轮证据。只读验证器 `deploy/tests/verify-gateway-authentication-evidence.ps1` 执行成功，输出 `deploy/gateway-authentication-verification-20261004b.json`，核对摘要、223 项原始 XML、8 项新行为 / 11 项既有 HTTP 以及 JAR 中新过滤器的 Java 21 class major=65 和固定 Redis 适配器版本。

早期 targeted-a 是 PowerShell Maven 参数引号错误，targeted-b 是测试方法引用的 Java 重载歧义，均未执行行为测试；修正后的 targeted-c 18 项通过。随后新增第 8 项 Redis 异常测试及错误分类，最终 full-b 的 223 项才覆盖完整当前代码；full-a 不冒充该最终结果。失败日志未覆盖。

## 交付边界与下一步

本轮不修改线上 Gateway JAR、不重启生产服务、不迁移生产数据库或开放运营 API。AI 短剧继续下线。前端未变，没有拿历史前端测试充当本次验证。

下一步使用独立 Redis / Nacos 与完整 Gateway 启动验证真实会话、实际 RPC、慢 Redis / 中断后的失败关闭与恢复，并串联三域重放 Broker/收件箱和运营 UI；备份/回滚与上述验收通过后才发布。未指定真实管理员和域名/TLS时，敏感公网操作仍保持关闭。

同日后续已完成完整 Boot/Nacos/Redis/HTTP 联调及实际 CLIENT PAUSE 超时/恢复，
见 [独立网络证据](GATEWAY_NETWORK_VERIFICATION_20261004.md)。本记录中的 223 项测试范围不因此变为
Broker/UI/容量或生产发布通过；生产 JAR 仍未替换。
