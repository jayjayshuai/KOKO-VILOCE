# 聊天全局连接租约计划

2026-10-05局部验证通过、未发布。依据企业路线的多节点/反滥用与资源保护门槛，
将每节点三连接改为共享Redis上的每用户三份有效连接租约；不是三台物理设备识别。

## 不变量

- 认证成功后才申请，Redis Lua单键原子检查/登记，最多3份；每次连接由服务器生成新UUID。
- 用Redis TIME，不用各JVM墙上时钟；有效期120秒，PING/SEND先校验会话并续期。
- 过期/丢失租约不可续期复活；旧UUID释放不能删除另一新连接，未知写入回复只释放自己的UUID。
- Redis故障拒绝准入/发送，不降级为仅本机配额；任何阻塞I/O都在有界业务池。
- 正常关闭排队释放，拥塞/Redis失败依靠有限TTL；优雅停止先关连接，再等待有限释放任务。
- 关闭、握手异步返回及帧排队有一次性释放围栏；停止后的旧任务不能再附着新连接。
- SQL消息事实和成员授权仍按原事务实现；配额不是MySQL提交围栏/资金锁，也不承诺故障下
  始终仅3个物理TCP连接。JVM长暂停后旧连接可能仍在，但下一帧须失败关闭。
- Redis重启/数据丢失、主从切换/时钟跳变等不提供线性一致容量承诺；上线需要对应演练。

## 范围与验证

新增Redis脚本/适配器、Netty接入、无账号标签指标、握手429/依赖503和帧失效提示；
保留本机500总连接与三连接快速防护。新版本所有节点须共享同一配额Redis/前缀，
旧节点不登记，滚动升级未完成前仍不保证全局限制。无SQL迁移，不改已应用V1～V4。

先单元/真实TCP拒绝与资源回收，再两个独立Redis客户端/生产Lua的并发、自然TTL、
旧续期/释放、脚本缓存失效、真Redis暂停/恢复。仅限本轮容器/测试用户，先备份证据后清理。
身份与SQL可使用明确夹具，但不能将其冒称真实完整Gateway登录/生产容量验收。

实际六组真实Redis/两Netty通过，原自动清理失败后独立备份恢复/回收已核验；
修订编排未整链路复验，完整登录/SQL/UI、Redis切换、容量和生产发布仍待。
详见[验证记录](CHAT_GLOBAL_QUOTA_VERIFICATION_20261005.md)。

依据 [Redis脚本执行](https://redis.io/docs/latest/develop/programmability/eval-intro/)、
[TIME](https://redis.io/docs/latest/commands/time/) 与
[Spring Data Redis脚本](https://docs.spring.io/spring-data/redis/reference/redis/scripting.html)。
