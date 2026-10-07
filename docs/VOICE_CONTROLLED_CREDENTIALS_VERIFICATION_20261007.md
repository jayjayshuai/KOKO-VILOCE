# 受控媒体凭据与连接面板验证

2026-10-07，沿用原语音媒体授权计划，不将签发接口或页面候选替代持续SFU对账/公网RTC验收。

## 实现

- 新接口`POST /voice/rooms/{id}/interaction/media-credentials`要求本人当前session及已核验房间版本。
  Active身份RPC先于SQL事务；房间锁下检查OPEN/CONTROLLED、成员租约、随机绑定轮次、八席位
  及PENDING/PROCESSING/DEAD退场，返回不可变签发投影后用实际LiveKit SDK签名。
- 听众禁发布；仅当前ON_MIC且未闭麦者允许microphone。禁data、元信息修改与管理权限。
  UUID身份和房间名不含PII，凭据/投影toString脱敏，HTTP no-store，不回退原user-ID发布接口。
- 初次入会120秒，显式nbf/exp。新握手拒绝过期JWT；已准入UUID连接的内部retain只保留已签名
  投影，再核验网站会话/Active身份/SQL当前绑定。LEGACY仍按原JWT期限；这不是公开过期JWT准入。
- 独立受控连接面板使用专属接口，默认不开麦；听众不能触发发布设备。session、授权快照或麦位
  失效时取消旧请求/清凭据/定向断开。设备实际状态由SDK反馈，与SQL“希望开麦”分别显示。
  成员续约的busy不等于权限撤销；未知管理命令、离线/隐藏、读取失败仍使媒体失效。
- 候选开关默认false。配置开启要求完整核心/绑定准入/计划/信令/退场依赖，且媒体地址必须为
  Gateway专属WSS基址，环回开发例外。能力接口只声明配置，不宣称物理音轨就绪。

## 执行与失败修正

SDK签名测试发现默认未写nbf，若照此使用保留投影会被拒绝。现从同一UTC时刻显式写nbf/exp，
验证期限精确120秒；没有仅放宽断言。前端真实SFC测试发现快照原地更新使旧请求的版本校验
读取同一个对象的新值，旧结果能错误通过；现跨await捕获session/version标量，旧回复被拒绝。

独立本机MySQL8.4.8、V1～V4、实际MP XML/Spring事务代理十组通过，含新增当前授权投影、
待退场拒绝、错误session/版本、实际SDK签名与准入、跨用户拒绝及租约到期。
前九组真实SQL/竞争/回滚同时复验；退场确认的SQL场景不冒充物理SFU清退。

前端215项及TypeScript/生产构建通过；生产构建页面以明确API/SDK夹具验证听众禁发布、
麦位变化先断开、显式重连和设备点击、撤权清理、390px无横向溢出，pageerror为0。
网络诊断/格式/秘密工具23项通过，实际TCP/STUN测试拒绝伪事务回应，UDP send不视为可达。
最后全模块后端测试/打包433项，失败0、错误0、跳过0；全项目420份Java及其余格式检查通过。
计数来自本轮新生成Surefire/构建日志，不借用历史测试数。

## 实际SFU尝试与网络证据

使用隔离SQL生成两个随机合成房间和绑定，经真实SDK/服务器密钥创建自建SFU房间，
实际退场API确认后签受控凭据。身份RPC是明确桩；两浏览器使用合成音频设备，未读取个人麦克风。
信令走私有SSH转发；两次尝试未建立PeerConnection，未确认RTP，也未跑通过完整Gateway网站授权。
失败记录保留；第二次实际错误为`could not establish pc connection`，没有把它写成音频成功。

从本机访问公网TCP7881等待五秒超时、UDP3478无匹配STUN回应。服务器内网TCP7881可连接，
Docker已映射TCP7881/UDP7882/UDP3478，UFW未启用，DOCKER-USER无附加规则。
需要核对云安全组、链路/本机出口及ICE候选；证据不足以将唯一原因断言为云安全组。
已提出三个必要媒体端口的核对问题，未修改云规则、主机防火墙或重启共用SFU。

可执行`npm run check:media-network -- --host <主机或IP>`重复只读TCP/STUN检查。
该命令不证明UDP7882、WebRTC、TLS或完整媒体链路。必要端口说明来自
[LiveKit官方端口文档](https://docs.livekit.io/transport/self-hosting/ports-firewall/)，令牌生命周期依据
[官方令牌说明](https://docs.livekit.io/frontends/reference/tokens-grants/)。

## 收尾与未完成

两个随机测试SFU房间已按清单限定身份删除并查询确认不存在；实际测试JWT从私有夹具中移除。
固定实验Schema备份后删除，所属33079进程核验停止，离线程序/限权账号元数据/备份保留。
最终备份SHA-256：`4300c7575815712d4e7a3627807f93b306145a278b4806c2d2dd53afeb5ee98a`。
现有项目数据库没有迁移或写入本轮媒体测试。代码和页面候选未生产开放；mediaReady继续false。

最新本地八Boot/JDWP、七数据源只读、三处POST423、实际断点及语音发现200复验通过。
新能力默认false、调试POST423，私有启动桥增加显式禁用新开关；私有文件和用户原Mapper排版不提交。

持续SFU参与者/网站连接租约对账、晚到旧握手、物理音轨撤权时限、原7880/rtc绕过入口收紧、
完整网站Redis/RPC/SQL/Gateway/SFU、HTTPS/WSS及双端真实RTP仍是必须完成的原目标。
域名、视频供应商、Discord与正式上线门槛不因本批候选签发和局部检查完成而关闭。
