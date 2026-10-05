# 媒体全状态引用开发与验证（2026-10-04）

本批完成源码层的全状态引用核验、所有者 HTTP 边界及素材中心查询。真实 MySQL 已验证
两域各12份迁移及生产 MP XML；后端244项、前端82项测试和生产构建通过。
没有发布生产，没有实现 READY 自动删除；新接口的完整 Boot/Dubbo/Gateway 联调及浏览器
点击/视觉验收未完成，不能用上一批运营页面截图代替，也不能认定媒体阶段完成。

按用户指定 enterprise-development：先追踪真实引用事实、区分权限与引用、保留失败，
测试只在固定限权库写夹具，先备份后删除，不用单元桩证明实际 SQL 或正式上线。

## 不变量和实现

- “没有公开引用”不等于孤儿。身份主页的 DRAFT/ACTIVE/SUSPENDED 头像与封面、文章
  DRAFT/PUBLISHED/ARCHIVED 封面都参与全引用核验；归档/停用恢复所需图片继续保护。
- 身份及内容通过各自 Mapper/XML 读取本域事实库，资产服务不跨库直连生产数据。
  新 RPC `hasProfileAssetReference`、`hasCoverReference` 只返回布尔值，不返回资源身份/正文。
- 原 `isPublishedProfileAsset`、`isPublishedCover` 和匿名内容读取授权保持独立；私有引用
  不会让草稿图片公开。新的域方法拒绝空、非法和非规范 UUID。
- 身份/社区各新增 Flyway V12：全状态查询以资产 ID 为索引前缀，保留原发布状态索引，
  不修改已应用的旧迁移。身份使用两个独立 EXISTS，内容使用封面 EXISTS。
- 资产服务先检查本人、READY、规范化 ID，再调用两域；任一域故障返回503，即使另一域
  已确认引用也不返回不完整快照。查询没有对象写入/删除、状态转换或额度修改。
- readiness 现在执行两个新全状态 RPC；旧 Provider 缺方法/故障或缺失快照时 DOWN，
  只返回固定原因，不泄露地址/异常细节。不得在旧 Provider 尚未升级时认定新资产就绪。

新增 GET `/api/assets/images/{id}/references`（阶段外部前缀由 Gateway 配置决定）：

| 字段/结果 | 语义 |
| --- | --- |
| profileReferenced | 任意状态主页头像/封面存在实际引用 |
| postReferenced | 任意状态文章封面存在实际引用 |
| checkedAt | 两域查询完成的 UTC 带偏移时间，不保证同一事务快照 |
| 404 | 他人、缺失或非 READY 图片；不暴露引用信息 |
| 503 ASSET_REFERENCE_UNAVAILABLE | 任一引用域不可用，不表示无引用 |

请求需真实登录且下游来源过滤器验证内部网关密钥；客户端不能自行填写身份头授权。
Controller 的成功、404及域503都设置 `Cache-Control: no-store`。网关真实 Sa-Token/Reactor
单元测试验证匿名 references、metadata、quota 三路径401且不放行；仅匹配合法内容路径
GET可进入下游，图片本身仍由资产服务授权。该测试未连接真实 Redis，不冒充网络证明。
字段有 Schema/中文说明，沿用项目 Lombok 实体、MP、Knife4j/OpenAPI 体系。

## 素材中心

选择本人图片后可手动“查看图片引用”，分别显示主页/文章观察值、北京时间以及
“不是安全删除许可”的说明。空选择不发送请求；不会添加可成功的删除占位按钮。

loading/重复点击保护、失败与重试、响应布尔值/带偏移时间验证、切图/换账号/会话轮次/
卸载取消及旧响应失效已实现。确定的当前401才使会话过期；旧401不能注销新账号。
查询开始清除旧快照，503不能保留“未使用”的假成功。状态不持久化到浏览器存储。

七个新增真实 TS/Vue watch 测试与原测试合计82项：零失败、取消、跳过和todo。
vue-tsc及Vite构建通过；现有 LiveKit大chunk提示仍保留。项目未定义lint命令，未声称
运行lint。没有为展示注入Vue业务状态/伪造网络，故真实界面交互验收仍待新完整环境。

## 后端和实际 SQL

后端第a轮因 Mockito 对已有异常桩再次使用when而抛出旧异常；不是被业务catch吞掉的
绿色结果。改用doThrow，原测试源码/XML与完整失败日志保留。b轮241项通过，补就绪和
网关路径边界后c轮Java21全模块244项、48份XML，零失败/错误/跳过，Reactor SUCCESS。
新增用例涵盖两域失败聚合、公开授权独立、所有权/READY前置拒绝、无存储/额度副作用、
HTTP错误映射/来源密钥、缺身份、就绪不完整快照及匿名网关路径。

真实 SQL 使用现有 MySQL8.4，只创建两个固定库和专用用户：

- `koko_assetrefs_identity_check_20261004`
- `koko_assetrefs_community_check_20261004`
- `koko_assetrefs_check_20261004`，仅以上两库权限，无全局权限。

没有新容器、Nacos/Redis/Broker或对象存储变更，不停止生产。开发机只开
127.0.0.1:23316 SSH转发，Java helper96MiB堆，初始20分钟/至少350MiB服务器可用内存保护。
编译当前域Mapper/实体，加载项目生产XML及完整filesystem Flyway迁移，不用手写SQL
模拟Mapper查询。合成账号/资料/文章只在两库，UUID仅引用夹具，未声称已上传MinIO对象。

启动a的PowerShell原生参数未加引号，使Java误解析file.encoding，未进入数据库。
b实际JDBC/Flyway遇到15秒读取超时，原SSH转发随后exit1（connection reset）；不假定
迁移未执行。只读观察确认两库tables均空后，重新连接准确转发，再运行固定验证：

- 两域分别完整迁移12份、Flyway validate成功，原失败库不被覆盖或删除来制造成功。
- 主页头像/封面各三状态，共六种实际匹配；内容三状态匹配。
- 原公开查询只允许 ACTIVE/PUBLISHED；草稿/停用/归档无公开授权。
- OTHER UUID、NULL不匹配；解绑后false；三个新索引实际存在。
- verify-a helper PID75552自然exit0；未执行对象写入、删除或后台清理。

仅证明查询和迁移，不证明绑定/删除互斥、分布式原子快照或索引容量。未运行EXPLAIN
或压测；Flyway11.7.2对MySQL8.4的兼容提示、已有commons-logging提示原样保留。

## 备份失败、恢复和清理

原lease记录0/requested-stop，但第一份isolated-databases.sql为0字节，未产出清理证明。
只读进程核验没有原脚本，具体备份停止原因未记录；不能把lease0说成正常清理成功。
恢复脚本先校验固定两库/用户计数2/1及拥有记录，再限时60秒写新独立SQL文件并校验。
原空文件保留，不覆盖失败事实；备份成功后才删除两个库和用户，最终计数0/0。

服务器九份生产文件摘要均OK（环境、Compose、Nginx和六JAR），生产容器ID/启动/状态/
OOM/重启数快照一致；九个KOKO服务健康、AI五服务exited。未发布本批源码或开启运营权。
临时凭据在用户删除失效后删除；本地原转发PID86600、重连PID72452及helper75552都不存在，
23316无监听。删除的仅本批测试库、用户和临时凭据；两库SQL保留，可恢复夹具。

| 归档/备份 | SHA-256 |
| --- | --- |
| asset-reference-local-evidence-20261004abc.tgz | 31f79ad368f1cdf8694126403d0ba3dc17d5a5dc2e5e5d7c9b17158a9af4a864 |
| assetrefs-mysql-server-evidence-20261004a.tgz | f107b83be741dff5797bd64c079eb3dbcd2df61d0f17b6c50a43cb0a9de04774 |
| isolated-databases-recovery.sql | e25a38875f2179d2de9eea0089694fe3e38925b4149b0e2d583d5de976b29bf8 |

本地归档含源代码/构建、全部48XML、前后失败/成功日志、实际SQL助手/源XML/迁移、
只读空库观察、退出与准确资源不存在记录；不含有效凭据或会话。服务器备份只有合成夹具。
`deploy/tests/verify-asset-reference-evidence-20261004.ps1` 从摘要已核对归档重新提取四组证据，
输出 `deploy/asset-reference-evidence-verification-20261004a/verdict.json` 为
`verified-readonly-reference-scope`。首次离线脚本续行解析失败已保留，修正后实际通过。
Warnings保留备份/网络失败与未验证范围，不输出“企业级全部通过”。

## 下一批和发布边界

媒体计划已写明绑定意图/RETIRING围栏/全引用核验/额度幂等的并发协议，但本批未实现。
现有失败上传清理只覆盖PENDING/CLEANING；不能将新两个布尔值直接连到storage.remove。
后续须真实MySQL并发/提交顺序、RPC结果未知、对象失败及恢复测试，再讨论开清理开关。

新增方法沿用RPC1.0.0为加法兼容，旧Provider会拒绝新调用，不降级到公开查询或假false。
正式发布前要核对生产所有待执行迁移（不仅V12，之前的运营迁移可能尚未部署），冻结新
构建，在完整隔离Boot/Gateway/Dubbo和浏览器完成权限/失败/交互验收。发布顺序先两域、
再资产、最后Web；所有新方法就绪后才接新客户端，回退顺序与数据兼容需预先验证。
生产管理员选择、TLS、外部告警、对象备份恢复和容量等原有门槛未解除。
