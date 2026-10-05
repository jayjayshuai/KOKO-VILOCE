# 素材绑定保护与源码格式化 · 2026-10-04

状态：源码已实现绑定意图基础协议、前后端排版和可重复格式检查，未发布生产。
阶段 3 仍进行中，不代表 READY 孤儿清理、媒体备份恢复或企业级上线验收已完成。

## 实现范围

- 资产新增 V4 `asset_binding_intent`：请求 UUID 唯一、完整所有者/资产/用途指纹、资产外键、创建时间。
  不修改已有 V1–V3，也不把创建时间用作 TTL 删除依据。
- `AssetBindingService` 通过 Spring 事务代理先 `SELECT ... FOR UPDATE` 同一资产，再验证 READY、
  所有者和用途并持久化意图；相同请求重试幂等，指纹不符拒绝。释放也先锁资产并匹配完整指纹。
- `AssetRpcService` 新增 begin/complete；身份头像/横幅和动态封面写入改为调用该保护协议。
  旧 Provider 缺方法或领取结果未知失败关闭，不降级为 `isOwnedReady` 只读校验。
- 公共 `DurableBindingGuard` 要求有效本地写事务；明确提交/回滚后才释放。未知提交不释放，
  RPC 领取回复丢失或释放失败可能留下永久意图，优先阻止误删。释放故障不把已提交业务改报失败。
- Java、Vue、TS/MJS、CSS、XML/YAML 已格式化，新增锁定版本、排除规则和 format/check/debug 命令。
  排版规范、检查实现与运行方式见 [代码格式说明](CODE_FORMATTING.md)。

## 本轮最终本地验证

| 检查 | 最终证据 | 结果 |
| --- | --- | --- |
| Java 21 全模块测试及 Boot 打包 | `deploy/development-binding-package-20261004c.log` | 264 项，52 份 XML；失败/错误/跳过均 0，BUILD SUCCESS |
| 前端测试，含 14 个真实 Vue 模板编译 | `deploy/development-format-web-tests-20261004b.log` | 96 项，失败/取消/跳过/todo 均 0 |
| 前端类型检查和生产构建 | `deploy/development-format-web-build-20261004b.log` | 通过；LiveKit 约 583 kB chunk 告警保留 |
| 格式检查 | `deploy/development-format-check-20261004b.log` | 270 份 Java，changed=0；其他源码通过 |
| 格式幂等/语法核验 | `deploy/development-format-debug-20261004f.log` | Java CST/字面量及幂等，其他语言原生 debug-check 通过 |
| 格式核验工具反向测试 | `deploy/development-format-tool-tests-20261004a.log` | 7 项全部通过 |
| 开发工具依赖审计 | `deploy/development-format-dependency-audit-20261004b.json` | 本轮 8 个开发工具依赖，报告 0 漏洞；不是全产品安全审计 |

公共事务测试使用真实 Spring 代理和 AbstractPlatformTransactionManager 验证提交/回滚顺序、
未知提交保留、释放故障、无代理自调用和只读事务拒绝；管理器为测试实现，不冒充 JDBC 证据。
两个域适配器测试核对完整 RPC 参数、相关 UUID、拒绝/故障不降级及回调前不释放；
该部分手动事务上下文不代替真实领域数据库或网络联调。

## 真实隔离 MySQL

沿用服务器现有 MySQL，不新增中间件容器、业务 JVM、付费资源或生产角色。固定测试库
`koko_assetbinding_check_20261004` 与同名限权账号，连接走拥有的 loopback SSH 隧道 23317。
服务器脚本有 20 分钟租约、内存/磁盘保护，清理前执行有界备份并校验，失败不继续 DROP。

`deploy/asset-binding-sql-20261004a/verify-a` 的真实 helper 使用本轮 a 构建的资产类、
生产 MP XML、完整四份 Flyway 和 Spring/JDBC 事务代理。随后排版通过语法/字面量比对，
不把这次工作区 classpath 验证冒充冻结的完整发布 JAR 网络验收。结果 exit=0：

1. 四份迁移执行并 validate 通过；原 Flyway 警告 MySQL 8.4 高于其已验证 8.1，保留而不静默升级。
2. 首次领取、同请求重试单行、他人完成不移除、重复完成和事务回滚真实通过。
3. 有保护意图时，外键拒绝直接删除资产。
4. 测试事务模拟未来退役者持同一资产行锁；并行 begin 的实际 MySQL 1205 锁超时确认等待，
   退役者提交后 RETIRING 被拒绝，恢复 READY 后可领取。
5. 后续另一事务仍看得到未释放意图，不执行 TTL 清理。

退役 SQL 只在隔离 helper 中模拟，生产 RETIRING 领取/恢复、执行代次、对象删除和额度释放未实现。
合成资产不对应真实 MinIO 对象，不声称验证了对象上传/删除或跨域真实引用。

服务器归档 `deploy/assetbinding-mysql-server-evidence-20261004a.tgz`：

```text
archive SHA-256: b57f3331d8a228cab884eb2997d5dd59b45c04ceabed6f8dc3ffa8c7bc4ceed8
SQL SHA-256:     39b096af3d52e1e344e35d6174d8258f4e822f1d47ae762a2be87fde58012573
```

备份成功后库/账号计数 0/0，lease 与 cleanup exit=0；九个生产文件摘要 OK、运行中容器快照相同，
九个 KOKO 服务健康、五个 AI 项目容器保持 exited。测试凭据删除，helper 和本轮隧道停止。
这些是该次清理时观察，不是持续在线监控承诺。

## 格式化备份与失败记录

开发前源码备份 `deploy/development-format-before-20261004a.tgz`：
`0c79d423ed9f3be09c3d0a80ae84f3d29f8d6d84d6de960f6e8f4d3de7e91a20`。
批量格式化输入另存 `deploy/development-format-input-20261004a.tgz`，包含新增绑定基础源码。
源码范围不含历史迁移改写、冻结部署证据或生产配置覆盖。

离线复核 `deploy/binding-format-evidence-verification-20261004a/verdict.json` 于 20:34 检查通过：
38 份旧迁移逐文件 SHA-256 未变、52 份最终测试 XML 的时间/计数一致、服务器归档与 SQL 摘要、
生产前后快照、备份后 0/0 清理、凭据和 helper/隧道/监听端口消失均符合本轮边界。
最终源码和报告封存于 `deploy/binding-format-local-evidence-20261004a.tgz`，不含运行凭据。

保留的失败与修复：

- 第一次 SSH 启动连接超时，在创建测试库之前；同脚本后续成功，不假报首次成功。
- 原生 Java debug-check 耗时异常及单文件 `RangeError`；全库只读异常进程定向停止。
  插件 2.8.1 对比也失败且开发依赖审计出现漏洞，未保留该版本，最终 2.11.0 审计为 0。
- 独立 Java CST 检查首轮发现 import 排序，明确只放行导入顺序；第二轮发现 Lua 文本块缩进改写，
  对该常量明确禁用 formatter，保持原始字面量，不对所有字符串设宽泛忽略。
- a 轮 Vue 构建因格式化去掉多语句事件分号失败；Vue override 改为保留分号，b 轮通过，
  新增 14 份真实模板编译防回归。
- b 轮后端因两个 POM 原字符串断言失败；改为禁用外部实体的 XML 依赖节点解析，c 轮全打包通过。
  原失败构建和日志保留，不混入最终 264 项通过计数。

## 下一步门槛

- 增加持久化领域尝试记录/可核对结束凭据和运营审计，安全处理领取回复丢失、释放失败与永久意图；
  当前没有自动补偿消费者，不可以按 TTL 或“目前无引用”删未知结果的保护记录。
- 实现并验证 READY→RETIRING 领取/引用复核/恢复、执行代次和对象故障恢复；旧写入者升级/停写前
  清理开关必须关闭。现有 PENDING/CLEANING 对账不能处理 RETIRING。
- 新 binding RPC 的 Triple/Nacos、两域真实事务网络、异常窗口、完整素材 UI 和浏览器视觉未验收。
  新增协议为兼容性扩展但不能滚动部署客户端优先；先资产 V4/Provider，再两个写域，最后再考虑清理。
- 新 Boot JAR/前端 dist 仅本地构建，未替换线上 artifact 或迁移生产资产库。
  TLS、容量、外部告警接收人、媒体恢复及原路线中的上线门槛仍需完成。
