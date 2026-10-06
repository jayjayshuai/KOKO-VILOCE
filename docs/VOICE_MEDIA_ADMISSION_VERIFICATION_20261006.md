# 自建LiveKit Java信令准入候选验证

2026-10-06开发批次，媒体授权计划第一步的候选实现。未发布、未迁移生产，
Gateway与voice准入开关默认false；CONTROLLED房间媒体准备度仍false。

## 实现与不变量

- 唯一候选路径为`/api/media/livekit/rtc`(WS)及`/api/media/livekit/rtc/validate`(GET)。
  保持Sa-Token网站身份门禁；按固定路由ID再次核对原始路径，编码别名不得跳过准入。
  `StripPrefix=3`后才转发到SFU，不代理`/twirp`等管理API。
- Cookie/网站令牌头/客户端身份头/网关内部密钥不转发到SFU，网关也不持有媒体管理密钥。
  JWT只传voice域脱敏RPC请求；依赖异常转换固定503，拒绝403，不自动重试。
- JWT校验固定HS256与发行方、当前网站用户、时间、规范房间ID与麦克风范围；
  拒绝管理、视频/屏幕、数据发布、自改元信息、SIP能力、重复JSON键、网络密钥头、
  非法签名、额外段、padding和超界。当前SDK空`sip`对象可接受，不等于允许SIP。
- 当前Active身份二跳在SQL事务之外；房间短事务当前锁核对OPEN/LEGACY/精确供应商名。
  CLOSING/CLOSED与CONTROLLED旧令牌拒绝。不是持续会话授权，也不证明核验到握手间无竞态。
- RPC有界独立池，不占Netty EventLoop；线程、每线程排队数、超时和候选Origin校验有上限。
  WS要求配置来源，普通同源GET验证不强求Origin；两种凭据/重复JWT或网站令牌查询参数拒绝。
- SDK签发新令牌禁数据发布/自改元信息；文本聊天仍走Netty。旧十分钟平台令牌兼容缺data字段。
- 候选边缘路径关闭含JWT的access/error请求日志；Gateway关闭内置WS帧TRACE的默认配置，
  媒体专用异常处理避免通用500打印原始URI。已升级失败只关闭本次连接，不向WS写JSON。
  部署仍不得开启网络wiretap、敏感组件TRACE或外部代理完整请求采集；本轮不是全日志零泄露认证。
- 前端准入媒体基址必须同源，跨源在SDK创建前拒绝，仍默认不开麦，不偷偷开CONTROLLED入口。

## 本轮执行证据

Java21离线Maven全模块407项，失败/错误/跳过均0；最终核验器的异常分类修正后另外复验
voice及依赖模块，启动和隐私收尾修正后复验Gateway/voice及依赖模块。前端195项通过，
生产构建通过；LiveKit按需chunk超过500KB的警告保留。
Java语法/字面量/格式幂等392文件通过，本批YAML/前端格式与差异空白检查通过。

新增检查范围：

- 实际Java LiveKit SDK生成令牌，再由生产HMAC核验器验证；不是只使用自制JWT样本。
- 当前状态/身份失败的用例单元检查使用明确Mapper/身份桩，不证明数据库或二跳网络。
- 真环回Triple、STRICT可序列化请求与生产Gateway Client：身份与令牌保留、拒绝、故障、
  实际超时且不重试；领域结果为明确夹具，不是voice/identity/SQL全网联调。
- 真环回HTTP/WS、Gateway实际FilteringWebHandler/StripPrefix/WS路由及回显SFU夹具：
  路径转换、Cookie/身份/密钥清除、身份绑定、拒绝不升级、普通GET不伪装WS。
  供应商握手503时实际连接有界异常关闭，不产生业务帧；不是LiveKit protobuf/ICE/音轨。
- 媒体异常处理在真实Netty HTTP响应下返回固定502/no-store，不输出JWT或供应商错误；
  非媒体路由错误仍传播。未用没有native transport的Mock响应假装网络验收。
- 前端composable实际代码检查同源代理基址、跨源拒绝与默认不开麦，SDK为明确桩。

失败轮次保留：Java`var`复合声明、Mock请求类型不匹配、Mockito抛异常后的错误重设、
SDK空sip导致误拒、误用包私有响应类型及无native Mock响应、WS异常关闭码只认1006的
过严测试。本轮实际观测Reactor Netty发1002错误关闭帧；最终验证异常码与无业务帧、
有界结束，而不是将未知握手故障冒充正常关闭。未将这些失败写成成功证据。

本地Boot复验又发现voice原入口缺EnableDubbo：类能直接构造、TCP夹具能export不证明实际
应用已注册Provider。已补入口扫描与配置断言，再检查实际进程导出；不能沿用之前空缺的
RPC监听状态当作准入联调成功。私有调试设置仍不提交，仍禁止自动迁移远程项目库。

收尾实际验证：最新8个本机Boot健康UP，全部JDWP握手、7个数据源JDBC/服务器会话只读、
三处HTTP写423、匿名401通过，voice Controller实际断点命中后正常恢复。另以独立Triple
客户端调用本机Boot导出的voice准入Provider，确认为默认关闭的固定不可用异常；不是只看
监听端口或日志类名，不执行身份/SQL/媒体业务。这不证明启用后的正向准入链路。

## 尚未证明与发布门槛

原SFU 7880端口和`/rtc`旧代理仍兼容既有部署，因此**候选入口未实现部署级唯一准入**。
没有改变生产入口/账号/数据库，没有真实Sa-Token→准入RPC→身份二跳→SQL→LiveKit RTC
正向验收、TLS/证书续期、容量或浏览器媒体双端。不将局部协议检查称为完整P1完成。

下一步依[媒体授权实施计划](VOICE_MEDIA_AUTHORIZATION_PLAN.md)补同事务绑定/轮次与撤销意图、
媒体执行租约/CAS/有限补偿、旧令牌/晚到握手/刷新令牌竞态及持续清退；实证前不开受控媒体。
现有远程Schema仍落后，本地只读调试保护和默认关闭保持，不为这批准入迁移线上库。
