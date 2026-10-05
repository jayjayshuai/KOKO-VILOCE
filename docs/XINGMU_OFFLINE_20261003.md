# AI 短剧项目临时下线记录

> 公开版：部署地址与用户目录已脱敏，须按实际环境替换；不是原始验收地址。私有证据未改动。

2026-10-03 用户要求“AI短剧项目先下线”。本次是可恢复停用，不是删除项目。

## 已执行与验证

- 停止 `infra-gateway-service-1`、`infra-ai-service-1`、`infra-drama-service-1`、`infra-crawler-service-1`、`infra-realtime-service-1`。Docker inspect 确认全部 exited；4 个 Java 服务退出码 143（SIGTERM），crawler 为 0，未出现强制 SIGKILL 的 137。
- 公网根首页、`/api/`、`/ws`、`/assets/`、`/doc.html`、`/v3/api-docs` 返回 503 和暂时下线提示，带 `Cache-Control: no-store` 与 `Retry-After: 3600`。Retry-After 是客户端重试建议，不是自动恢复计划。
- `/koko/` 与 `/koko-api/discovery/communities?limit=12` 公网实际请求仍为 200。保留原 `/rtc` 代理配置；本轮不据此声称已验证音视频通话。
- KOKO 的 9 个有健康检查的服务仍 healthy，LiveKit 容器 running。共享 Nginx 仅 reload，未重启；KOKO 与共享基础设施的容器 ID、StartedAt、running 状态前后逐行一致。
- 原 AI 短剧 Compose 与 `.env` SHA-256 前后校验一致；没有删除或重建容器，没有删除数据卷、MySQL 数据库、SQLite、媒体、镜像、代码和既有备份。新留存的是本次路由配置回滚副本，不冒充数据库全量备份。
- 原本绑定 `127.0.0.1:8080` 的短剧网关端口已无法建立连接。
- 本次瞬时采样 MemAvailable 从 876 MiB 变为 1460 MiB，swap 使用从 9512 MiB 变为 7805 MiB；不是性能或容量认证。

## 配置与证据

服务器配置：`/srv/deployment-home/xingmu/infra/nginx/default.conf`。

原配置 SHA-256：`853cbd599ada65cdb3509147ac0a827e71cc4b91ef8a13166fa87bc5b9620033`。

下线配置 SHA-256：`f6349c0df6fb7563f0379267d86bd326ee72dc14a22e155250d186d64b734c9e`。

权限受限的回滚副本和记录目录：`/srv/deployment-home/xingmu-offline-record-20261003a`，含 `default.conf.before`、`default.conf.offline`、Nginx 配置检查、HTTP 状态、停止容器列表、共享服务前后快照、Compose/.env 校验与内存采样。

执行文件：`deploy/offline-xingmu-20261003.sh` 与 `deploy/xingmu-offline-20261003.conf`；脚本有原配置哈希和目标容器 Compose 标签校验，拒绝覆盖已有记录目录。不能作为无需核查的通用下线脚本反复执行。

## 恢复门槛与顺序

恢复需要用户明确要求。本次未创建自动恢复任务。Docker 显式 stop 会保留容器；人工 `docker start` 或 Compose up 可能重新启动它们，不能因没有删除代码就认为永远不会恢复。

1. 核对当前 Nginx 配置是否仍是上述下线版本；后续 KOKO 路由有改动时，合并恢复短剧路由，不能盲目覆盖旧整份配置。
2. 只启动以上 5 个业务容器，确认内部健康、数据库/MQ 与任务状态。不要对共用 Compose 执行 down，也不要执行带卷删除选项的操作。
3. 业务就绪后，恢复短剧代理与页面位置。单文件 bind mount 必须原位覆盖，不通过 mv 换 inode。
4. `docker exec infra-edge-proxy-1 nginx -t` 成功后 reload，验证短剧入口和 KOKO 入口均正常。未完成验证前不要称作恢复成功。

## KOKO 当前开发状态

前端工作台改造仍为本地进行中，本轮没有发布新前端、重建 KOKO、迁移数据库或执行通知故障演练。本记录不代表前端改造或全项目生产验收完成。

## 2026-10-04 状态复核

00:57（Asia/Shanghai）通过 SSH 只读复核：上述 5 个 AI 短剧容器均为 exited/running=false，KOKO 的 9 个带健康检查的服务均 healthy，LiveKit running。当前 Nginx 配置 SHA-256 仍与下线版本一致。

服务器本机请求 `/`、`/api/`、`/ws`、`/doc.html` 均为 503，首页包含 `Cache-Control: no-store` 和 `Retry-After: 3600`。开发机另行请求公网根首页、`/api/` 为 503，`/koko/` 与 `/koko-api/discovery/communities?limit=12` 为 200。

本次没有再次停止容器、改服务器配置、删除数据或恢复 AI 服务；仅复核既有下线状态并更新本记录。以上检查不等同于 KOKO 全业务或音视频验收。
