# 素材绑定释放补偿 · 2026-10-04

本轮完成已提交业务的持久化释放基础：源码、全量打包和隔离 SQL 验证通过，未发布生产。
不是未知领取/回滚失败的全部恢复方案，也没有开启 READY 自动删除。

## 业务保证

1. 身份头像/横幅、动态封面成功领取保护后，在同一本地业务事务插入 `asset_binding_release`。
   任务登记失败使业务回滚；只读、无事务或绕过代理调用也不能自动提交释放任务。
2. 提交后即时释放失败或进程在提交后崩溃，任务仍在事实库。任务仅随已提交业务可见，
   因而可作为 worker 的释放凭据，而不是拿 TTL 或“目前无引用”猜测结果。
3. 资产 V5 永久完成标记和保护删除同事务，防止重复释放、丢回复重试及迟到 begin 复活。
   新调用者只用 `sealBinding`，旧 Provider 缺方法时不能回退到不具备结束标记的旧接口。
4. 领取单批最多四条、六十秒租约、五秒 RPC 超时/零重试；成功/失败确认都匹配有效租约令牌。
   次数单调累计，退避最多 900 秒，十次后 DEAD；第十次进程崩溃也有有界耗尽扫描，不无限复活。
5. RPC 成功但本地 SQL 确认失败保留租约，由后续重复 seal 恢复，不改报为供应商业务失败。
   未知 begin 回复且业务未提交的保护，没有释放任务可自动删除，仍保留待核对。

范围：资产新增 V5；身份、社区各新增 V13；共享 `event-outbox` 增加释放 writer/relay/MP XML。
直播域不导入释放 writer/relay，不增加该表的运行查询。两个写域配置默认：

```yaml
koko.asset-binding.release-enabled: ${ASSET_BINDING_RELEASE_ENABLED:false}
```

该开关只控制异步 worker，不关闭同事务任务登记或即时完成尝试。先发布资产 V5/seal Provider，
再迁移并发布两个写域 V13，完成真实网络验收后才能批准开启补偿；不能客户端优先滚动发布。
本轮没有修改前端交互，不把历史 96 项前端结果计成本轮新测试。

## 本轮验证

- Java 21 全模块 `package`：277 项、54 份最终 XML，失败/错误/跳过均 0，BUILD SUCCESS。
  日志 `deploy/binding-release-package-20261004b.log`，结束 21:23:30。
- `format:check` / `format:debug`：278 份 Java changed=0，其他源码检查通过。
  记录 `deploy/binding-release-format-{check,debug}-20261004a.log`。
- 单元/真实 Spring 测试增加登记顺序、登记故障回滚、未知提交不即时释放、writer 禁止无事务写入、
  封存后不能复活、不同身份/用途拒绝、成功确认 SQL 故障不误分类及十次耗尽。
- 现有 Mockito 动态 agent 警告保留；格式检查不是 P3C、完整安全或容量认证。

## 实际三库 SQL

服务器沿用共享 MySQL；固定限权账号 `koko_bindingrelease_20261004` 仅拥有以下三库权限：

- `koko_bindingrelease_asset_20261004`：五份完整迁移及校验。
- `koko_bindingrelease_identity_20261004`：十三份完整迁移及校验。
- `koko_bindingrelease_community_20261004`：十三份完整迁移及校验。

实际调用生产 CreatorProfile/CreatorPost、BindingReleaseWriter 和 AssetBindingService 的 Spring/JDBC
事务代理、生产 MP XML。RPC 使用明确标注的进程内适配器，不冒充 Triple/Nacos 或完整 Boot 网络。
Synthetic 图片仅测试 UUID/元数据，不对应 MinIO 对象，不执行对象删除。

a 轮真实通过资料/动态提交、PENDING 重试、seal 实际完成后丢回复、幂等重试及迟到 begin 拒绝。
随后创建故障触发器被 MySQL 1419 拒绝（限权账号无 SUPER），停止且保留非零退出及日志。
没有提权或修改共享 `log_bin_trust_function_creators`。

b 轮恢复 helper 有局部变量作用域编译错误，未进入数据库；该版本源码保留。
c 轮只在精确确认原停止状态（资料 SENT、动态 PENDING、一条封面保护、无触发器）后继续，
不清表重置、不重复此前业务。改用隔离表临时 CHECK 约束产生真实 SQL 故障，约束随后移除：

- 动态 seal 成功而 SENT SQL 确认失败，保留 LEASED；到期后重复完成并恢复 SENT。
- 新释放任务 INSERT 失败，真实资料变更和任务共同回滚，明确回滚后的保护完成。
- begin 成功但回复丢失，域事务回滚且无已提交释放任务，保护保留；worker 不误删它。
- 六条合成租约夹具经两个并行真实 SQL 领取，不重叠；旧成功/失败令牌 CAS 均为零。
- 重新领取次数不重置，第十次租约过期转 DEAD，不再次领取。

c 轮 exit=0；证据 `deploy/binding-release-sql-20261004a/verify-c` 冻结实际类及资源/源码副本。
SQL helper 的源代码和失败 a/b 轮保留。最终源码后续补了 writer 的显式无事务拒绝防线，
该新增防线由本轮单位测试和全量打包覆盖；SQL 轮通过的是其已有 MANDATORY 事务代理路径。

## 清理与证据

三库和账号先做有界 SQL 备份、SHA-256 校验，再定向 DROP，四个剩余计数均 0。
lease/cleanup exit 均 0；九个生产文件摘要 OK、运行中容器前后快照一致，九个 KOKO 健康，
五个 AI 短剧容器保持 exited。helper/隧道已停止，本轮临时凭据已删除。

离线复核 `deploy/binding-release-local-evidence-20261004a/verdict.json` 在 21:28 通过：
39 份已有迁移逐文件摘要未变，277 项/54 份 XML 均为最终轮次，生产快照与九文件摘要一致，
helper/隧道/租约 SSH 客户端及凭据均已退出或删除。租约 SSH 客户端迟迟未自行结束，
在备份清理与归档均完成后，按精确命令/PID 定向停止；不因此把该连接退出当作正常自结束。
最终源码、报告和三轮 SQL 证据封存为 `deploy/binding-release-local-evidence-20261004a.tgz`。

服务器证据：`deploy/bindingrelease-mysql-server-evidence-20261004a.tgz`。

```text
archive SHA-256: fcf65b9485a27577e387ca3dd6277840f08c2d3004b40834378d17127fe4b0e2
SQL SHA-256:     e37b6ed80ea926839b8f45949438349eb660e038c7c501a0fd168b98b3beec69
```

这些是当次清理观察，不是持续在线监控或企业级上线认证。前一批基础来源见
[绑定保护与格式化记录](ASSET_BINDING_FORMATTING_VERIFICATION_20261004.md)。

## 仍需完成

- 没有提交凭据的未知领取、回滚后释放失败：需要持久化尝试/明确结束证据与审计核对，不能 TTL 删除。
- DEAD 任务查询、受权限保护的审计重放、指标/外部告警、任务与永久标记的容量/保留策略尚未实现。
- 真 Triple/Nacos 的新 seal 契约、超时窗口和两域端到端验收，完整新素材 UI 与发布回滚演练。
- RETIRING 执行代次、引用复核/恢复、对象故障恢复及额度释放仍未实现；旧写入者未升级之前
  不能开启 READY 清理。TLS、容量和媒体备份恢复等原上线门槛仍保留。
