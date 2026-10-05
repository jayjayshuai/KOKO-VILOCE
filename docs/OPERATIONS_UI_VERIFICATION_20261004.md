# 通知投递运营 UI 实际联调记录（2026-10-04）

结论：隔离环境中，真实浏览器通过生产 Vue 构建、Gateway/Sa-Token、三域 Dubbo、
四套限权 MySQL、独立 Redis/Nacos/RocketMQ 完成了人工重放和撤权链路。
不是模拟 API 或直接修改收件箱；没有生产赋权、迁移或发布，阶段 2～9 不整体勾选。
随后发现发现页 HTTP 404；注册分组隔离只通过配置回归，真实网络复验仍待完成。

依照用户指定 enterprise-development 的安全/交付规范：合成账号与隔离授权、真实 SQL
审计故障、保留不确定结果、备份后定向清理、公开上线门槛明确分离。

## 环境与构建

本轮 UI 操作时间为上海时间 12:55～13:09；证据离线复核在清理后完成。
开发机五个实际 Java 21.0.12.1 Boot：identity 71992、community 73904、live 75964、
notification 75256、gateway 75768。HTTP 42081/42082/42083/42085/42080 仅 127.0.0.1，
Triple 42881/42882/42883 仅 ::1。没有启动 voice/asset/chat JVM。
完整 Flyway 迁移数为身份 11、社区 11、直播 5、通知 2。

服务器独立 internal 网络 `koko-operations-ui-20261004a`（172.28.105.0/24），
仅四个带 `koko.verification=operations-ui-20261004a` 标签的固定容器。
Redis/Nacos/NameServer/Broker 物理上限 64/256/128/256 MiB，没有宿主发布端口；
SSH 七条转发只绑定开发机环回。没有停共用中间件或更改主机交换区。
临时 Nacos 关闭认证只用于私网夹具，不证明生产认证、容量或默认凭据治理。

前端两轮均为实际 `vue-tsc -b && vite build` 输出，由固定环回 45173 的静态/代理服务
转发到真实 Gateway；代理没有生成业务响应。隔离构建使用 `/` 与 `/api` 基址，不改变
生产 `/koko/` 和 `/koko-api` 配置。第一轮 69 项，改进后的第二轮 73 项前端测试通过，
零失败、取消、跳过或待办；生产构建通过，仍有大于 500 kB chunk 提示，不算性能验收。

本轮使用先前不可变五 JAR：Gateway
`c7c3acbe9e7acb045f098babaa47a51b8052f133719887be6ff66b466fd87b83`，
其余见 `deploy/operations-ui-local-20261004a/artifacts.json`。它们不包含后续注册分组修正。
之前 224 项后端验收记录仍为历史事实；后续新增配置测试后的全模块结果为 225 项。

## 实际浏览器和数据库事实

三个合成账号通过真实注册创建，仅测试管理员经离线 plan/apply 初始化，实际 HTTP
授权测试操作员 NOTIFICATION_OPERATOR；普通用户没有运营权限。未给生产账号提权。
真实发布创作者资料和关注生成基线 Outbox；另外准备身份 23、社区 1、直播 1 条 DEAD
历史夹具，身份时间统一到同一微秒，用于检验复合游标；attempts/totalAttempts=10 是
夹具初始事实，不代表本轮经历十次真实发送失败，直播夹具不代表真实推流。

| 场景 | 观察与交叉验证 |
| --- | --- |
| 匿名和普通用户 | 匿名页面要求登录；普通用户真实登录后无运营读权限，不获取死信列表 |
| 操作员分页 | 第一批 20 条；继续加载得到 23 条同微秒、不同 ID 的身份事件，末页按钮消失 |
| 安全展示 | 含 `<img ...>` 的错误夹具以插值显示；DOM 中 `.ops-preview img` 为 0，不执行标记 |
| 原因与本人确认 | 过短原因不能准备命令；错误密码确认 403，不发起重放，清空密码并保留原命令 |
| 审计 SQL 故障 | 指定事件审计 INSERT CHECK 拒绝后重放 503；真实 SQL 保持 DEAD/generation0/audit0 |
| 不确定结果 | 原请求只读查询 404；UI 提醒在途仍可能提交，只能重试相同命令，隐藏取消入口 |
| SPA 切页返回 | 到通知中心再浏览器返回，原 uncertain 命令与 requestId 不变；不是整页刷新恢复证明 |
| 原命令恢复 | 移除指定 CHECK，本人确认后原请求受理 202；SQL 为 SENT/generation1、原请求审计1、入箱1 |
| 三域闭环 | 社区和直播也经 UI 确认/受理 202、事实刷新与原请求查询；各一条审计/入箱，直播 fanout DONE |
| 撤权与登出 | 实际角色 revoke 后事实接口 403，页面清空旧摘要并关闭访问；真实 logout 200 后匿名门禁 |

身份原命令 ID 为 `3d3693e5-17ad-4336-a6f7-767db6e04a4c`，事件
`10000000-0000-4000-8000-000000000023`；失败和恢复使用相同命令，没有换新 UUID。
社区命令 `fab90d4c-612d-4bb5-aeca-cde7216d2092`，直播命令
`2cd2a13b-5af6-431a-8d4f-ee49895a7736`。最终 SQL 断言身份总 Outbox 24（包括真实关注
基线）、剩余 DEAD 22、上述原请求审计恰好 1，三个主事件各一条收件箱。
真实生产客户端发送端 5.3.1、消费者 5.3.2，未合并或替换客户端依赖。

本轮没有发生确认接口 429，不把限速算作本轮浏览器证据。审计列表 GET 200 和原请求
查询成功、SQL 审计内容已核验；部分截图取得时审计列表仍在加载，未单独断言完整
追加历史可视化或多页审计翻页，也未执行只读 reviewer 账号 UI，这些仍须补验。

## 前端完善及布局

`web/src/views/OperationsView.vue` 新命令准备后等待真实 Vue 渲染，再把焦点移至本人密码。
被拒绝、已有命令、命令被替换或仍忙碌时不抢焦点；四项新增 SFC 状态测试覆盖这些边界。
死信列表增加有界滚动、滚动链隔离、键盘可聚焦 region 和焦点可视状态，移动端降低高度。

新构建的浏览器实际 DOM：窄屏 viewport404/page389，列表高302、内容高3539，
当前焦点 `outbox-password`；桌面 viewport1440/page1425，两列
513.016/589.984，列表高558、内容高2635。两者无横向溢出且长队列独立滚动。
新构建中准备另一个命令后取消的是尚未发送的 prepared 命令，不取消不确定命令。
截图见 `deploy/operations-ui-browser-20261004a/desktop-workspace-top.jpg` 和
`focus-mobile.jpg`；顶端截图避免了先前 sticky 页头与截图滚动偏移带来的视觉错位。

## 新发现的路由缺口与配置修正

代理日志保留实际 `GET /api/discovery/posts/page 404`，直播发现先 404 后 200；
voice 503 是该服务本轮未启动，不能把二者合并为同一故障。
已有 Controller 定义上述分页路径，Boot 日志显示同名 HTTP 服务注册到原 RPC 分组；
Gateway 的 Dubbo 通知观察到直播实例数由 1 变 2。实际自有 dubbo-3.3.6 字节码表明
应用级实例使用 registry group 注册；这些证据支持 HTTP 与 RPC 混用分组的嫌疑，但
清理前未抓取每次网关选择的实例，尚不宣称已逐请求证明所有 404 原因。

已将 Gateway 和六个 HTTP 提供方的发现分组改为
`${NACOS_HTTP_GROUP:${NACOS_GROUP:KOKO_NEXUS_PROD}_HTTP}`；Dubbo 保持原 NACOS_GROUP。
Compose 明确区分 KOKO_NEXUS_PROD_HTTP 与 KOKO_NEXUS_PROD。新增真实 YAML 解析测试
验证默认、隔离继承、HTTP 单独覆盖，225 项/47份 XML 零失败、错误或跳过。
代码快照为 `deploy/operations-discovery-config-20261004a`。
随后全模块仅打包命令通过，五个新 JAR 已冻结到该目录的 artifacts 并记录 SHA-256；
从新 Gateway/社区/直播/通知 JAR 中提取的实际 YAML 均包含 HTTP 分组隔离。
这些是打包证据，不是启动或网络成功证据。网络启动器新增显式 `-FreshArtifacts`，
复验配置变更时不能默认复用此前未包含新配置的三个领域 JAR。

此配置未部署，且未执行拆分后的真实 Nacos/LB 复验。必须用新提供方与新网关一起
验证注册列表、连续发现请求和 Dubbo 第二跳，不能仅改 Gateway 或靠固定 HTTP URI
宣称发现修复。当前生产 Compose 仍用固定内网 URI，生产八份文件摘要没有变化。
单机不是无中断滚动环境，切换注册分组需按核实的实际路由安排发布窗口/回退。

## 清理与备份

先停止精确拥有的两个 UI 预览与五个 Boot，再备份四套测试库并校验，删除固定
四容器、网络、四库及限权用户；原凭据失效后删除本地与服务器临时凭据并关闭隧道。
五个 Boot ExitCode=-1 是测试强制停止，不证明优雅停机。服务器 lease exit0，库/用户
残留计数均为0，生产 ID/启动/状态/重启/OOM 快照相同，八份文件摘要均 OK。
九个 KOKO 容器 healthy；AI 五容器 exited，根/API 503，KOKO 页面/发现接口200。
删除的测试数据可从 SQL 备份恢复，没有删除生产业务或停止共用中间件。

| 证据 | SHA-256 |
| --- | --- |
| operations-ui-local-evidence-20261004ab.tgz | 45ff788ad90e58f6e6a8a05de82dee99036f477ad0b494874022bcdc71b1cd59 |
| operations-ui-server-evidence-20261004a.tgz | 984aa35adebd0bc4051d646f5661190d58298ec2c6417740b6b5cf19442be7fe |
| 服务器归档中 isolated-databases.sql | 48c6213446528503c98999f3be6e88033a5476ebe4bbae0894baa86f7b297ddd |
| operations-discovery-surefire-20261004a.tgz | fff5167d9007e1bf73009830a6e4a2be7a47c5193ef3ae2064b11ddfae929b7c |

离线复核脚本 `deploy/tests/verify-operations-ui-evidence-20261004.ps1`；首轮把路由命名
写成猜测路径，复核失败，保留目录 a。按实际日志修正后目录 b 的 verdict.json 通过
五组原归档/进程/HTTP/SQL/布局/测试/清理检查。该脚本只是交叉复核，不代替浏览器操作。
凭据、会话和确认秘密不进入归档，固定合成数据不含真实用户资料。

## 后续顺序

1. 拆分注册分组的真实 Nacos/LB 与三域 RPC 回归，保留本轮 404，不伪称全发现通过。
2. 只读 reviewer UI、追加审计多页、故障恢复的双端/通知 UI 等剩余交互。
3. 发布前负责人选择真实运营账号；明确域名/TLS、安全凭据、容量、外部告警和恢复门槛，
   才逐服务备份、迁移、发布及公网验收；未指定时生产运营入口继续关闭。

实际直播推流/回放、Discord 正式 OAuth、RTC 双客户端、跨节点聊天、异地恢复和阶段
3～9 的其余能力仍未完成。本轮局部联调不等同企业级整体上线。
