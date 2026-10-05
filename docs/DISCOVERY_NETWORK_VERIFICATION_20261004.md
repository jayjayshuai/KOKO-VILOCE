# HTTP/RPC 注册分组与审核员 UI 验收（2026-10-04）

结论：冻结的新构建在独立 Nacos/Redis 和四套限权库中，通过实际 `lb://` HTTP 发现与
三域 Dubbo/身份第二跳；审核员实际浏览器通过审计分页、只读边界、撤权和登出。
本轮没有 Broker，不把 PENDING 受理当成入箱；生产未迁移、赋权或发布。
原执行器中断导致 JVM 末尾日志/退出码缺失、服务器 lease exit1，明确保留证据缺口。

采用用户指定 enterprise-development 的隔离/真实行为/备份清理规范；容量不足不停止
共用中间件，不伪造绿色生命周期，也不因局部通过勾除路线阶段 2～9。

## 修正与验证范围

上一轮保留了文章/直播发现 404；旧配置确实让同名 HTTP 与 Dubbo 应用实例使用同一
Nacos 分组。源码已将七个 HTTP 服务的 discovery group 与原 RPC registry group 分离。
这轮实际查询注册中心证明四个 HTTP 实例、三个 RPC 提供方端口互不混入；连续 HTTP
请求成功。没有在旧轮记录每次网关选中的实例，不声称逐请求追溯了所有旧 404。

使用 `deploy/operations-discovery-config-20261004a/artifacts` 中五个冻结 JAR，
启动器逐项核对 SHA-256，不复用旧领域构建，不继承固定 HTTP 服务 URI。
Gateway 的六个 HTTP 路由显式使用 `lb://`；运行的 HTTP 服务只有社区、直播、通知与网关。
身份只注册 RPC。voice/asset/chat 的分组解析已覆盖单元测试，但本轮没有启动它们。

| 实际分组 | 服务与地址 |
| --- | --- |
| KOKO_DISCOVERY_CHECK_20261004_HTTP | gateway 127.0.0.1:42080、community :42082、live :42083、notification :42085 |
| KOKO_DISCOVERY_CHECK_20261004 | identity [::1]:42881、community [::1]:42882、live [::1]:42883 |

注册读者使用本项目真实 Nacos 3.0.3 SDK，前后两次观察均每服务恰好一个健康启用的
ephemeral 实例；HTTP 分组没有身份 RPC 实例。注册文本的 `[::1]` 按 IPv6 环回地址
比较，不把方括号当作错误端点，不放宽实际端口或允许任意地址。

## 资源保护和四组网络证据

服务器约 3718 MiB 物理内存、14 GiB swap，初次 MemAvailable 1020～1025 MiB，
四中间件模式的 1054 MiB 保护检查拒绝启动，没有创建资源。
注册修正不需要新的 MQ 故障演练，随后采用固定 `--discovery` 模式：只创建 Nacos256、
Redis64 MiB，物理上限合计320 MiB，启动前至少670、运行中至少350 MiB可用内存。
Broker/NameServer 不运行，所有业务 OUTBOX_ENABLED=false、通知 consumer=false。
未扩主机交换区、购置付费资源或停止生产组件；之前 Broker 入箱记录继续独立保留。

服务器私网 `koko-discovery-network-20261004a` 为 internal 172.28.106.0/24，无宿主端口，
两个容器有固定 label/ID；开发机 SSH 四个环回转发。MySQL 用户
`koko_discovery_check_20261004` 只拥有四个固定 `koko_discovery_*_check_20261004` 库。
五个真实 Java21 Boot 的 PID 为身份70448、社区69356、直播75340、通知76380、网关85696，
端口监听快照仅127.0.0.1/::1。完整 Flyway 成功数11/11/5/2。
私网 Nacos 无认证仅为夹具，不是生产身份认证和默认凭据治理的证明。

实际 HTTP 修正轮 c 完成四组：

1. 真实客户端读取四个 HTTP、三个 RPC 注册实例，两分组协议端口隔离。
2. 四个合成账号真实注册，离线 CLI plan/apply 只初始化测试管理员；真实 HTTP 授予
   NOTIFICATION_OPERATOR、只读 NOTIFICATION_AUDITOR，普通账号仍拒绝运营读取。
3. 社区发现、文章分页、直播发现各40次，共120个实际200；三个服务 OpenAPI各10次、
   本人通知列表10次，共40个实际200。文章页验证页码/大小/零总数，文档验证真实OpenAPI。
4. 审核员三域读取200、二次确认写入403；操作员三域确认/受理202/原请求查询200。
   历史身份事件第十代实际受理，审计limit2经五页连续得到10到1、无遗漏/重复。
   SQL断言三个主事件与历史事件为PENDING、正确代次/审计数量，通知收件箱0。

不声称上述短连续请求是容量压测、Broker入箱或真实直播开播。

## 审计前端和实际浏览器

前端 `operations.ts` 将审计每页从10条改为5条，十代上限下实际可以继续加载较早历史。
`OperationsView.vue` 增加审计滚动区域、键盘tabindex与焦点样式；展开失败快照不再令
工作台尾部无界增长，空态/失败/权限反馈和已有原请求幂等机制不变。
新增真实TS请求参数测试和追加审计页故障保留/重试接续测试。
75项前端测试零失败/取消/跳过/待办，vue-tsc与Vite生产构建通过；大chunk提示仍存在。

浏览器使用冻结的生产构建和真实 Gateway 代理，没有模拟API/直接注入Vue状态：

- 合成审核员登录后显示 NOTIFICATION_AUDITOR/只读审计；无重放原因或密码表单。
- 先读取五条实际审计、再点击“加载更早审计”，列表有序追加到十条，末页按钮消失。
- 展开原失败与请求ID，`<strong>unparsed</strong>` 为字面文本，详情内解析出的strong元素为0。
- 桌面viewport1280/page1265，两列438.594/504.391；审计高446、内容高1392。
- 移动viewport390/page375，审计高380、内容高1574，tabindex0；两者无横向溢出。
- 实际管理员撤销该测试审核员角色；事实查询403清除旧摘要和十条审计，重新读权限200
  显示“当前账号没有运营读权限”。真实logout200，保存的最终AX文本显示匿名登录门禁。

历史事件前九份审计是明确标注的SQL夹具，第十份是实际HTTP受理追加。
固定夹具时间13:30在UTC测试会话中晚于实际第十代受理的05:50；此轮按代次验证分页，
不证明时间线或九次历史真实失败。截图和库时间标签保留原事实，未为展示修改审计。
完整字段/时间契约的生产验收仍需单独检查。

浏览器对details的AX称为button，而DOM定位为generic，首个button定位超时后按实际
DOM文字成功展开；不是业务失败。Ctrl+Home和AX根滚动也未成功，后来按观察到的空白
区域滚动成功。完整截图可能改变vh呈现，布局指标来自运行时只读DOM，而非截图推测。
后续执行器恢复时本地测试页已不可访问，绑定错误页被浏览器安全策略拒绝，未绕过策略
自动关闭；本地45173服务和监听均已结束，测试页可手动关闭。

## 失败、中断与日志改进

HTTP轮a/b在只读注册检查阶段停止：比较器先拒绝`[::1]`的文本格式；没有创建合成账号。
保留原源码、SQL迁移断言和b的实际观察/失败日志；修正轮c使用同一实际五进程。

自动续跑前出现执行中断和一小时观测空档。恢复时原五PID及四隧道、预览端口均已不存在，
但旧启动器把stdout/stderr只保存在内存、等待finally才落盘，原启动/末尾日志和退出码
未留下，不能补写为“正常停止”。服务器lease exit1，两个容器redis0/nacos143、OOM=false；
停止时间与60分钟保护期限一致，但没有单独记录具体失败原因，不能确认只因期限。

已将启动器改为双流 CopyToAsync 写入 WriteThrough 文件，文件实时可读。独立Java21
探针原a/b把空文件误当异常，修正c在准确子进程仍运行时观察到stdout/stderr，再自然
退出0；源码和三轮证据保留。这只证明日志策略，不补齐原Boot缺失日志或证明优雅停机。

## 备份、清理与可复核证据

SQL四库先完整mysqldump并校验，再核对固定两个容器label/ID/网络ID，删除两容器、
私网、四库和限权用户；计数为0/0，凭据失效后删除服务器和本地临时文件。
生产容器ID/启动/状态/OOM/重启数快照一致，八份文件摘要均OK；九个KOKO容器healthy，
AI五容器exited，根/API503，KOKO页面/发现200。删除的仅测试资源，SQL备份仍可恢复。

| 归档 | SHA-256 |
| --- | --- |
| discovery-network-local-evidence-20261004abc.tgz | ea27846d3849fa688c3eb3f6108460e96b580b09abb16bbd43e9e610e35dbd74 |
| discovery-network-server-evidence-20261004a.tgz | 12f92051e4762dcf79e9242f10163bcabf5d0be3fbfb35d38e2661766d03ae98 |
| discovery-network-surefire-20261004a.tgz | a5aabd089c45c6e908740758659a3dfc52b8426ea2be7efbba514d7177e2674b |
| 服务器归档中isolated-databases.sql | 5620be0f59161aa07e752c58586e6faae78aad60eff1e7558345f09014716369 |

本轮Java21全模块重新执行225项、47份XML，零失败/错误/跳过；不使用历史测试数替代。
离线脚本 `deploy/tests/verify-discovery-network-evidence-20261004.ps1` 已交叉验证五组证据，
输出 `deploy/discovery-network-evidence-verification-20261004a/verdict.json` 为
`verified-scoped-evidence`，Warnings保留上述生命周期和浏览器清理限制，不宣称全部通过。
归档没有真实用户数据、数据库/Redis明文密码、会话或二次确认秘密。

## 后续

注册分离的本轮实际四HTTP/三RPC缺口、审核员UI和审计多页缺口已补证。
三域真实Broker入箱沿用前轮独立证据；正式责任账号、TLS、外部告警、恢复和容量没有
完成，不擅自开启公网运营权限。生产分组切换还需覆盖其他HTTP服务，并安排单机
发布窗口/回退；当前固定内部URI不能代替将来的全部发现验收。
继续推进媒体资产孤儿清理与备份恢复等可开发环节；Discord凭据、正式推流/回放、
RTC双客户端、跨节点聊天及其余路线门槛仍保持未完成。
