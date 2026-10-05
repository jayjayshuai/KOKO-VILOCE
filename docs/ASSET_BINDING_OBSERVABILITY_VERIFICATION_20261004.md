# 绑定释放只读运维与指标 · 2026-10-04

本批完成身份/社区两个提交域的只读排障 API、前端工作台、独立读权限、
有界指标与告警规则草案。源码、打包、单元/HTTP/序列化测试和隔离 MySQL 验证通过。
**未发布生产，不等于整个项目完成，也不等于 DEAD 人工恢复闭环。**
继续遵循 enterprise-development 规范：字段及契约中文说明、Lombok 构造注入、
MP XML、独立 Flyway 增量、默认关闭和可追溯证据；不声称通过完整 P3C 或安全认证。

## 已实现的业务边界

- 身份、社区各新增 V14 索引 `(status, created_at, request_id)`。旧 42 份迁移与上一批
  冻结档案 SHA256 逐份比对未变，当前源码合计 44 份迁移；没有修改已执行迁移。
- 身份 V14 新增 `ASSET_BINDING_AUDITOR`，只含 `asset:binding:read`。
  不给任何真实账号赋权，不让通知权限或现有管理员角色自动获得资产排障权限。
  角色变更仍走既有身份域的非本人变更、二次确认、版本约束与审计流程。
- 网关使用当前 Sa-Token 身份；两个 Provider 再次按身份事实检查专用权限。
  只允许固定 `identity` / `community`，不接受任意 RPC 服务名或直播域。
  Dubbo 零自动重试；显式序列化白名单加入四个新契约，仍使用 STRICT。
- 只读事实不包含 owner、对象键、领取令牌、租约秘密或原始异常。
  失败类别固定为 `release-unconfirmed` / `lease-exhausted` / `unknown`。
  HTTP 成功和错误均不缓存。SQL/RPC 故障返回安全的不可用结果，不伪装空队列。
- DEAD 列表使用微秒时间与 UUID 的 exclusive 游标，SQL 单页 1～50，前端固定 20。
  详情读当前真实状态，可能已变成 SENT；SENT 仅说明释放保护已确认，不说明资产已删除。
  状态采样在一个 SQL 中完成，每状态计数最多 1001；达到上限是下界，不是精确全站总量。
- 前端增加独立路由 `/operations/binding-releases`：权限门禁、双域切换、DEAD 分页、
  当前详情、手动刷新采样和独立加载/错误/空状态。撤权/退出会清空敏感数据；
  AbortController 加请求代次隔离，旧账号、旧域、旧详情的晚到响应不能覆盖当前状态。
  列表最多缓存 200 条，无后台自动轮询；没有伪造的“重试成功”或删除按钮。
- 本批没有修改累计尝试、worker 租约 CAS、资产永久结束标记或 READY 清理策略。
  不直接重新排队 DEAD，不清除未知 begin 意图，不按 TTL 删除保护，不删除对象。

### 时间语义

接口传输数据库 `DATETIME(6)` 原值，**不带时区、不换算本地时间**，游标不得截断微秒。
实测共享 MySQL 的 session/global 为 SYSTEM、system 为 UTC；
例如本地上海 23:46 时采样原值为 15:46。页面、字段及接口注释已纠正为数据库时间原值。
本批没有修改共享数据库或容器时区。未来统一时间策略需单独迁移、兼容与发布验收。

## 开关、接口与发布顺序

身份/社区默认配置：

```yaml
koko.operations.enabled: ${OPERATIONS_ENABLED:false}
koko.asset-binding.operations-enabled: ${ASSET_BINDING_OPERATIONS_ENABLED:false}
koko.asset-binding.metrics-enabled: ${ASSET_BINDING_METRICS_ENABLED:false}
koko.asset-binding.release-enabled: ${ASSET_BINDING_RELEASE_ENABLED:false}
```

读 Provider 同时要求全局 operations 和 binding operations 开启；指标开关独立。
release-enabled 只控制异步 worker，不关闭既有同事务任务登记或提交后即时完成尝试。
直播域不创建新读服务/指标 Bean，也不新增该表的运行查询。

网关在全局 operations 开启时注册以下 GET；经现有代理前缀访问，不能另开公网管理端口：

- `/api/operations/binding-releases/{domain}/dead`
- `/api/operations/binding-releases/{domain}/tasks/{requestId}`
- `/api/operations/binding-releases/{domain}/snapshot`

列表参数 `limit`、`beforeCreatedAt`、`beforeRequestId`；游标必须成对，UUID 和时间有效。
当前没有 POST/PUT/DELETE 人工重放或删除接口。

发布前仍须：备份并验证恢复；资产 V5/seal Provider 先于提交域 V13，
两域执行 V14 后再发布新读 Provider，最后网关与 Web；新开关保持关闭，
完成新契约的真实 Nacos/Triple/Redis/Boot/HTTP 及授权 UI 验收后，再批准开启读取。
指标需另行批准内网 scrape，最后安装规则。未迁移时索引强制提示会失败闭合，不能报零。
不能通过回滚旧写入者或打开 READY 删除来“解决”积压。

## 指标与告警草案

指标每 30 秒进行一次有界 SQL 采样，默认首次延迟 5 秒；scrape 只读内存，不触发 SQL。
没有 user、asset、request、错误消息标签，状态标签固定；指标 Bean 保持生命周期引用。

- `koko_binding_release_backlog{status="PENDING|LEASED|DEAD"}`：最多 1001，未知为 -1。
- `koko_binding_release_oldest_age_seconds{status="PENDING|DEAD"}`：无任务为 0，未知为 -1。
- `koko_binding_release_telemetry_up`：有效采样 1，未采样/SQL 故障 0。
- `koko_binding_release_sample_limit`：1001。
- `koko_binding_release_sample_age_seconds`：单调时钟采样年龄，未知为 -1，可发现调度停滞。

`deploy/observability/binding-release-alerts.yml` 包含五条规则：DEAD、PENDING 超时、
采样失败、采样停滞、预期指标缺失；job 限于 `koko-identity` / `koko-community`。
共享 Prometheus 容器的 `promtool check rules /dev/stdin` 退出 0，报告 `SUCCESS: 5 rules found`。
**这只是语法检查**：未安装、reload、修改 scrape/receiver，也未验证触发时序或外部送达。
默认未启用指标的环境不能加载预期指标缺失规则；服务存活告警仍需既有独立 up 规则。

## 最终本地验证

- Java 21 全模块 `package`：291 项、59 份最终 Surefire XML；失败/错误/跳过均 0。
  `deploy/binding-observability-package-20261004i.log`，结束 23:52:54（上海时间）。
- 网关测试使用真实 WebFlux/Sa-Token 过滤与 HTTP 响应，覆盖匿名、权限不足、伪造身份、
  当前身份、微秒游标、no-store 和无效参数；RPC 边界仍是替身，不算真实网络链路。
- 实际 Hessian2 编解码覆盖四个新 record、LocalDateTime 与严格拒绝未列白名单类型。
- 两域 ApplicationContextRunner 验证默认关闭、双读开关、指标开关独立；不是完整 Boot。
  真实 PrometheusMeterRegistry 导出名称/标签，并证明 scrape 不再次读 SQL。
- 前端全量 109 项通过，失败/取消/跳过为 0。
  `deploy/binding-observability-web-tests-20261004f.log`（跨午夜复核）。
  绑定页面专属 12 项状态/契约测试：真实 Vue 状态逻辑和生产 TS，网络为替身，不算浏览器验收。
- `vue-tsc` / Vite 生产构建通过：`deploy/binding-observability-web-build-20261004d.log`。
  仍有 LiveKit 583.03 kB chunk 警告，未通过提高阈值隐藏，移动端网络预算仍需验收。
- `format:check` / `format:debug`：296 份 Java changed=0，语法/字面量保持和幂等通过；
  XML/YAML/Vue/TS 等匹配源码排版通过。Java 格式器回归 7 项通过。
  最终日志 `binding-observability-format-{check,debug}-20261004d.log`、
  `binding-observability-formatter-tests-20261004b.log`；数据库迁移没有批量格式化。
- 浏览器只观察到既有会话/路由依赖门禁，**没有本批授权页面正向操作或视觉验收证据**。
  不复用历史页面截图、旧 Boot 联调结果来宣称新页面/新 RPC 已通过。

### 保留的失败与中断

原始 a～i 后端日志、a～f 前端日志均保留，不覆盖第一次失败：
MeterRegistry 关闭方式编译错误、Mockito 再 stub 已抛错方法、503 固定文案断言、
新 record 未列严格白名单、Prometheus 测试误放在没有共享模块依赖的 gateway。
分别修正测试资源管理、doReturn、错误契约、精确白名单及测试模块位置后，全量重跑 i 通过。
前端首次失败含会话 watch 返回新数组造成误清空、异步测试边界及路由清单数未更新；
改为同步监听实际身份/会话版本、补晚到隔离及契约断言后，最终 f 通过。
另一次直接执行 `node --test` 未带必要的 VM 模块开关，12 项均在加载阶段失败；
错误调用保存在 `binding-observability-web-test-command-error-20261004a.log`。
使用包含 `--experimental-vm-modules` 的正式 `npm --prefix web test` 再跑全量 109 项通过，
不把命令错误计为产品功能失败，也不隐去这次执行记录。

## 真正 MySQL 验证和清理

只复用共享 MySQL 8.4：固定两套隔离 schema、一个仅有两库权限的临时用户，
20 分钟租约、内存/磁盘前置检查、有限时备份与 trap；没有新增服务器 JVM/容器。
本地通过仅绑定环回的 SSH 隧道，真实 Flyway、Spring 事务代理、生产 MP XML 和领域权限实现。
社区的身份查询在本轮使用进程内适配，因此 **不算生产 Dubbo 第二跳**。

脚本：`deploy/tests/binding-observability-mysql-20261004{,b,c}.sh`，
本地 `verify-binding-observability-mysql-20261004.ps1` 和 `BindingObservabilityMysqlCheck.java`。

- a：实际 SQL 执行后因原分页索引计划不满足预期退出 1；保留失败输入/输出和库备份。
  生产 XML 改为明确使用新复合索引，后续解释计划不再对手写相似 SQL 做证明。
- b：用户中断期间租约到期，lease=1、cleanup=0；未运行本地 SQL 验证，不能计为通过。
- c：23:46:11 退出 0；冻结当时的生产类/源码/资源、helper 类/源码、验证脚本和 stdout。
  最终修改仅含时间注释/前端提示及附加测试等，不以这份 SQL 证据替代网络/UI 验收。

c 验证两域各 14 份迁移与 validate；合成 1005 DEAD、1005 PENDING、1 LEASED：
微秒相同时间 UUID 顺序与第二页断点正确，采样截断 1001；详情真实 SENT、404 和安全字段；
EXPLAIN 使用**实际 XML BoundSql**，命中新索引并为 Backward index scan，无 filesort。
只在隔离身份库授予专用角色，再撤权验证两个读域拒绝；未向任何真实账号赋权。
隔离身份库临时删除新索引，验证 SQL 故障返回无原因泄露的不可用结果，指标 -1/up=0；
finally 恢复索引后采样和指标恢复。未触发 worker 或资产删除。
Flyway 对 MySQL 8.4 的版本兼容告警原样保留，不能据此宣称组合获得官方兼容认证。

每轮先备份两个隔离库并验证 SHA256，再删除仅属于本轮的 schema/user。
a/b/c 的 cleanup 均 0，库/用户计数均 `0,0,0`，生产文件哈希和容器 ID/启动时间等前后相同。
c 完成后环回隧道已停止，本地临时 runtime.json 已删除；失败资料仍可从备份恢复。
AI 短剧保持下线，未重启或替换生产服务、共享配置、规则或对象数据。

服务器归档下载后逐个与服务器 SHA256 对比一致：

| 轮次 | `bindingobserve-mysql-server-evidence-20261004*.tgz` SHA256 |
| --- | --- |
| a | `79c68da23fd058f0db6a7efb2a5c9024fbcbc4c8ffcfd28203a932bebaa2a631` |
| b | `3337fab402bac2639fc6bb32d74108a660c7b2fd0f431082e9500aa802352c2d` |
| c | `303dcfb3076146654f4c067d72ea0a128cd5aca988f045c80747821729336a2c` |

c SQL 备份 SHA256：`3451d47e50ef616bd05b61ab9cff993877c6dacd52696c060581474b0ce793c5`。
本地完整交付证据档案为 `deploy/binding-observability-local-evidence-20261004a.tgz`；
档案 SHA256 单独记录在同目录 `.sha256.json`，避免自引用哈希。

## 尚未完成及下一批验收

1. DEAD 人工恢复：单独动作权限、针对原绑定请求的二次确认、幂等命令/新执行代次、
   不清零累计次数、同事务追加审计与旧租约围栏；不能套用通知重放确认凭据。
   补 SQL 竞争/审计失败回滚/丢回复、真实协议、授权 UI 与发布验收。
2. 新读契约完整 Boot/Nacos/Redis/Triple 两跳、旧 Provider 不兼容失败闭合；
   真实权限账号、前端桌面/窄屏/键盘操作，权限撤销后真实网络恢复。
3. 未知 begin/业务结果的永久核对、回滚即时释放失败审计；无提交凭据时不能猜测释放。
4. RETIRING 删除围栏、全状态引用核验、对象故障/额度恢复、私有资产备份恢复和容量压测。
   READY 自动删除继续关闭。
5. 指标真实内网 scrape、五条告警的时序/缺失/恢复测试、外部 receiver 送达与责任人响应。
6. 域名与 HTTPS/WSS、服务器物理内存/容量、RTC 双客户端、Discord 正式凭据、
   视频推流/转码/CDN/回放等整体上线门槛仍未完成，不能把增加交换区视为高可用。

相关前置证据：[绑定释放补偿](ASSET_BINDING_RELEASE_VERIFICATION_20261004.md)、
[绑定保护与格式化](ASSET_BINDING_FORMATTING_VERIFICATION_20261004.md)、
[资产生命周期计划](MEDIA_ASSET_PLAN.md)、[企业交付路线](ENTERPRISE_ROADMAP.md)。
