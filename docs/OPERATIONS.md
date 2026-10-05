# KOKO Nexus 单机运行手册

> 公开版：部署地址与用户目录已脱敏，须按实际环境替换；不是原始验收地址。私有证据未改动。

本文档对应当前部署目录 `/srv/deployment-home/koko-nexus-release`。服务器上的 `.env` 是唯一的业务数据库凭据来源，不应提交到仓库、复制到日志或发送到聊天工具。

## 服务与入口

2026-10-01 阶段环境版本：身份库迁移至 V5、社区库 V7、直播库 V3、通知库 V2、
资产库 V1。通知域和资产域的设计与验收边界分别见
[`NOTIFICATION_ARCHITECTURE.md`](NOTIFICATION_ARCHITECTURE.md) 和
[`MEDIA_ASSET_PLAN.md`](MEDIA_ASSET_PLAN.md)。最近一次发布前备份位于
`/srv/deployment-home/koko-nexus-release/backup-stage3-foundation-20261001a`，
仍在同一台主机上，不能替代异地备份。

- Web：`/koko/`
- API：`/koko-api/`
- 业务容器：`koko-nexus-web`、`koko-nexus-gateway`、`koko-nexus-identity`、`koko-nexus-community`、`koko-nexus-live`、`koko-nexus-voice`、`koko-nexus-notification`、`koko-nexus-asset`
- 媒体容器：固定版本 `livekit/livekit-server:v1.13.7`
- 业务容器只加入 `infra_default` 网络，不映射宿主机端口；公网流量只经过现有 Nginx。

## 发布

2026-10-03 仅 web 的工作台及会话修复发布见 [前端交付证据](FRONTEND_WORKSPACE_VERIFICATION_20261003.md)，最新同机备份为 `backup-frontend-workspace-20261003b`。路由采用 `/koko/#/...`，反向代理仍只代理 Web/API/RTC，不为私有页面开放新端口。AI 短剧按用户要求保持下线，恢复门槛见 [下线记录](XINGMU_OFFLINE_20261003.md)；不要对共用 Compose 执行 down 或全量 up。

1. 在可信构建机执行 `mvn test` 和前端生产构建。
2. 只将已验证的目标服务可执行 JAR、Web `dist` 与 `deploy` 清单复制到发布目录。
3. 校验 `.env` 权限与 Compose 渲染结果，禁止在命令输出中展开密码。
4. 首次发布执行 `docker compose up -d --build`；单服务发布使用 `docker compose up -d --no-deps --build <service>`，避免沿 `depends_on` 依赖图重建无关服务。等待目标及其调用方健康检查通过。
5. 如果 Gateway 或 Web 容器被重建，执行 `nginx -t` 后 reload 公网 Nginx，使其重新解析 Docker 上游地址。
6. 依次验证 Web、公开发现、匿名 401、登录与一条受保护写入链路；测试记录随后按测试标识定向清理。

更新 `community`、`live` 或 `voice` 容器后，Gateway 采用 Docker DNS 直连时必须执行一条真实下游请求确认地址解析正常。更新 `gateway` 或 `web` 后必须 reload 公网 Nginx。

不要在生产服务器上以源码目录直接运行 Maven 或 Vite，也不要把临时测试密码写入 shell history。

## 健康检查

```bash
cd /srv/deployment-home/koko-nexus-release
docker compose ps
docker stats --no-stream
curl -fsS http://127.0.0.1/koko/ >/dev/null
curl -fsS http://127.0.0.1/koko-api/discovery/communities >/dev/null
curl -fsS http://127.0.0.1/koko-api/discovery/posts >/dev/null
curl -fsS http://127.0.0.1/koko-api/live/discovery >/dev/null
curl -fsS http://127.0.0.1/koko-api/voice/rooms/discovery >/dev/null
curl -fsS http://127.0.0.1:7880/ >/dev/null
```

各 Java 服务提供 `/actuator/health` 与 `/actuator/prometheus`，但这些内部端点不应由公网 Nginx 暴露。

资产服务变更后还应验证：匿名 `/koko-api/assets/images` 返回 401；无内部网关密钥
直接访问资产容器返回 403；上传 JPEG/PNG 及本人读取成功，未公开图片的其他账号读取返回 404，
发布引用图片匿名读取返回 200，归档/解绑后返回 404，伪造 MIME 返回 400。
图片库按所有者/用途隔离，外人游标返回 404、错误用途/超大页返回 400。
临时账号、对象和数据库记录须按精确 ID 清理；验收脚本为
`deploy/tests/managed-media-stage-smoke.ps1`，定向清理脚本为 `deploy/tests/cleanup-managed-media-stage.sh`。
资产 Docker 健康检查使用 `/actuator/health/readiness`，包含数据库与两个发布状态 RPC；
`/actuator/health/liveness` 仅表示进程存活。首次查询预热失败时 readiness 必须保持 DOWN，
不能以 HTTP 端口可连接作为发布成功依据。

媒体配额默认每人 100 张/100 MiB，KOKO 桶总额 10000 张/1 GiB，配置项为
`ASSET_OWNER_QUOTA_BYTES`、`ASSET_OWNER_QUOTA_IMAGES`、`ASSET_TOTAL_QUOTA_BYTES`、
`ASSET_TOTAL_QUOTA_IMAGES`。额度覆盖所有用途和待清理失败上传，解绑不会即时释放。
个人超额为 409，桶超额为 503；单实例上传处理繁忙为 429，`Retry-After: 2`。
禁止只删数据库记录或手工修改账本来腾出空间，否则可能遗留对象或超分配。
只读核对脚本为 `deploy/tests/verify-asset-accounting.sh`；应确认账本差异为零。
V3 初次迁移须停止旧资产写入者后回填预算；当前单实例重建已满足此条件。
回滚到 V3 之前的 JAR 不会维护配额，因此只能用于受控维护，必须暂停上传入口，
重新启用配额前根据真实资产表对账。当前备份均在本机，不具备异地恢复能力。
本次仍保留支持 V3 的上一份配额 JAR：
`/srv/deployment-home/koko-nexus-release/stage3-quota-20261001a/asset-service-0.1.0-SNAPSHOT.jar`
（SHA-256 `00c613f3fb59828acae2f28ddc155943e526deb4d9d3d68400efb452aece914f`）；
它可作为本次内存保护改动的资产服务软件回退版本，继续使用当前数据库和配额配置，
不能把整个 Compose 回退到 V3 之前。当前含内存保护的版本另保留为同目录 `asset-service-guard.jar`。

## Gateway 限流

- 登录与注册：每个来源 IP 每分钟 10 次；Redis 异常时拒绝请求，避免认证入口失去保护。
- 写请求：已登录用户按用户 ID、匿名请求按来源 IP，每分钟 60 次；Redis 异常时拒绝请求。
- 读请求：每个来源 IP 每分钟 240 次；Redis 异常时允许读取，避免发现页整体不可用。
- 超限返回 `429`、`Retry-After`、`X-RateLimit-Limit` 与 `X-RateLimit-Remaining`。
- Prometheus 指标 `koko_gateway_ratelimit_total` 按 `bucket` 和 `result` 区分允许、拒绝和存储异常。应对 `rejected` 突增及任何 `store_error` 配置告警。

边缘代理必须覆盖而不是透传客户端提供的 `X-Real-IP`，否则攻击者可以伪造来源绕过 IP 限流。变更代理配置后，应验证第 11 次连续无效登录返回 `429`，并在测试结束后等待窗口到期。

## LiveKit 网络

- `/rtc`：由公网 Nginx 反向代理到 LiveKit 信令 WebSocket。
- `7881/TCP`：ICE/TCP 回退。
- `7882/UDP`：ICE/UDP mux，主要媒体通道。
- `3478/UDP`：内置 TURN/UDP。

主机监听端口不代表腾讯云安全组已放行。发布后必须从外部网络分别验证 TCP 与 UDP；未通过前不能宣称公网语音链路完成。浏览器麦克风要求安全上下文，因此正式环境必须使用受信任证书的 `https://` 和 `wss://`。

`voice-service` 的 JVM 预算不能低于当前 Compose 中的配置。Spring Boot、Dubbo、Nacos 与 LiveKit SDK 的组合在 96 MiB Metaspace 下会启动后 OOM；修改资源限制后必须等 `/actuator/health` 返回 `UP` 再放行流量。

重新创建 `livekit` 容器后，应确认 `voice-service` 健康；若 Docker DNS 对端地址仍是旧值，重启 `voice-service`，再等待 `gateway` 健康后执行建房、入房、关房冒烟测试。

## 日志与排障

通知链路已完成独立 Broker 的故障/重投演练，证据与版本加载边界见
[2026-10-03 可靠性记录](NOTIFICATION_RELIABILITY_VERIFICATION_20261003.md)。
仅允许停启演练脚本自身创建并核对标签/ID 的 Broker，不能停共享 Broker 排查。
通知 DEAD 人工重放/审计、Broker DLQ、外部告警和恢复/容量门槛仍未通过。

```bash
docker compose logs --since=15m gateway
docker compose logs --since=15m identity community live voice livekit
docker inspect --format '{{json .State.Health}}' koko-nexus-gateway
```

排障顺序：入口 Nginx → Gateway → Nacos/Dubbo → 领域服务 → MySQL/Redis。禁止为了恢复服务而跳过鉴权、关闭数据库约束或返回伪造成功。

## 备份

当前服务器已有 MySQL 和对象存储，但本项目尚未安装自动备份任务。正式导入用户数据前必须完成：

1. 对 `koko_identity`、`koko_community`、`koko_live`、`koko_voice`、`koko_notification`、`koko_asset` 做每日加密逻辑备份。
2. 将备份复制到不同故障域，并配置保留周期。
3. 每月在隔离环境执行恢复演练，记录恢复时间与校验结果。
4. 公开上线头像、封面和回放前，对 KOKO 桶启用版本化、生命周期及独立备份，演练恢复。

## 回滚

回滚以不可变构建产物为单位：保留上一版本目录和镜像标签，停止当前 Compose 后从上一目录启动，并重新验证健康检查。Flyway 迁移只允许向前兼容；涉及破坏性字段变更时必须先完成“扩展—迁移—收缩”，不能直接回退数据库文件。

当前发布目录尚未包含自动回滚脚本，因此任何回滚必须由值班人员按变更单执行并记录。不要使用 `docker compose down -v`，该命令会威胁持久化数据。

## 上线前硬门槛

社区成员 V9 的增量发布、清单校验清理与失败恢复边界见 [成员发布手册](COMMUNITY_MEMBERSHIP_RUNBOOK.md)；该批次只更新 community/web，不允许为此重启共享数据库/Redis/Nacos。阶段备份保存在同机，尚不满足异地恢复门槛。

- 配置域名、TLS 证书、HSTS 与安全 Cookie；当前 HTTP 地址只适合阶段验收。
- 更换所有曾通过聊天传递的服务器密码，并改为 SSH 密钥登录。
- 安装待处理的系统安全更新，并预留维护窗口重启验证。
- 正式上线前将主机升级到至少 8 GiB 内存并重新容量测试；目前新增资产 JVM 仅为阶段验收，
  不能将交换区扩容视为物理内存扩容。
- 2026-10-01 按运营方选择新增 `/swapfile-koko-nexus-extra` 2 GiB，已启用并写入
  `/etc/fstab`；操作前备份为 `/etc/fstab.koko-before-swap-20261001`。
  2026-10-01 16:25 实测总 swap 约 14 GiB，已用约 8.5 GiB、系统盘剩余约 9.1 GiB。
  增加 swap 只提高内存耗尽时的缓冲，
  不提高吞吐或实时音视频延迟表现；8 GiB 物理内存门槛仍未解除。
- 接入告警路由、自动备份和审计留存后，再开放真实用户注册。
