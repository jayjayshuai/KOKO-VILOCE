# 2026-10-03 前端工作台阶段交付

> 公开版：部署地址与用户目录已脱敏，须按实际环境替换；不是原始验收地址。私有证据未改动。

沿用真实 Java API，数据库与 Java JAR 本批不变。KOKO 仍是 HTTP 阶段验收环境，不是正式上线证书。

## 实现与实际验收

Vue Router / Pinia 十个领域 URL，五个私有页面确认会话后才挂载；WorkspaceShell 与发现、内容、素材、通知、账户视图分离，Netty 消息内嵌。原有编辑表单/控制器仍在 App.vue，完整抽取尚未完成。

共享请求层处理 Cookie、Headers、FormData、空 200/204、JSON、取消、20 秒读取/90 秒上传等待上限，不自动重试写入；超时不代表服务器未提交。异步读取用轮次、身份及取消保护，失败不伪装空数据或默认偏好。

公网发现并修复真实缺陷：会话过期后通知写入返回 401，但旧页面仍显示已登录账号。修复版捕获请求发起时用户/会话轮次，仅当前业务 401 清除身份、卸载私有页面；旧请求、403、503、网络故障不误注销新账号。认证端点由 Store 自己处理；身份切换断开语音。不延长或改动服务端会话设置。

| 层级 | 本次实际证据 | 验证边界 |
| --- | --- | --- |
| 前端状态测试 | `npm test` 51/51，失败/跳过 0：历史 8、社区 10、工作台 33 | 执行真实 TS/SFC，网络桩不证明 SQL/Broker/DOM |
| 类型与产物 | vue-tsc / Vite 构建成功，1716 modules | 不代替业务验收 |
| 公网资源 | HTML、图标及全部分包 12 文件实际字节 SHA-256 与最终 dist 相同 | 不代替点击验收 |
| 内容/素材/通知/账户 UI | 专用账号登录；私有草稿保存及 v0；本人图片库 0/100 张、0/100 MiB；FOLLOW 关闭，切页恢复关闭，SQL enabled=0；账户身份和能力边界正确 | 没有上传；非空通知分页/已读、主页发布、群管理等未在本批 UI 验收 |
| Netty / SQL | 页面显示 READY 真实连接，创建私信及 2 人群，各发 1 条并显示“已保存”；SQL 确认 2 会话、4 成员、2 消息 | 未验双浏览器实时接收、断网、跨节点、压力 |
| 失效身份回归 | 撤销测试账号实际 3 个令牌，均 `/auth/me` 401；页面刷新立即清除顶部账号、卸载收件箱并显示登录门槛 | 不等于完整多端会话审计 |
| 移动/键盘 | 公网 390×844：clientWidth/scrollWidth=390；抽屉首焦点品牌，Shift+Tab 在抽屉内；Escape 返回“打开导航”，overflow 恢复；已 reset viewport | 不证明全部移动业务流程 |
| 清理/运行 | 合成账号、草稿及聊天数据已定向备份后清理，scoped rows=0；9 个 KOKO 健康检查 healthy，LiveKit running | 不证明异地恢复或 RTC 通话 |

日志 `deploy/frontend-tests-20261003b.log`。此批没有重跑 Java 全模块，不把历史 131 项计为本次结果。仓库无既有 lint/formatter 命令，不声称通过 lint。LiveKit 动态包约 583 kB/gzip 153 kB，500 kB 构建警告仍在；只在实际入会时加载，性能预算需实测。

浏览器曾超时/中断，部分导航改变 hash 但保留旧 JS；核对实际 script src 后加载新文档，不重复提交或因观察超时重启服务。`public-notifications.jpg` 是原版 401 缺陷证据，不是通过证据。WindowsApps Python 占位命令没有执行检查，随后服务器 python3 编译成功。早期 PowerShell 5 失败生成的空 fixture 报告保留；实际创建账号用 PowerShell 7。

## 发布与恢复

发布目录 `/srv/deployment-home/koko-nexus-release`。第一版 `stage-frontend-workspace-20261003a` / `backup-frontend-workspace-20261003a`；最终修复版 `stage-frontend-workspace-20261003b` / `backup-frontend-workspace-20261003b`。

最终 `frontend-session-web-20261003b.tgz` SHA-256：`8d7b6b4fd6c9c0d23c0cec99844b833125864736f70bfa2e464593500057f2ee`。

发布脚本 `rollout-frontend-session-20261003.sh` SHA-256：`350c2b7b07718ef4efd6c2fe0ee94cbed566ef0ceca5d04585326bdf082923ca`。

主 JS `index-B3GRLv50.js`、CSS `index-DsJR0JwT.css`；全部指纹见 `deploy/frontend-workspace-assets-20261003b.json`，a 版证据保留。仅重建 web，边缘 Nginx 检查后 reload；`.env`/Compose/边缘路由哈希一致，Java/共享中间件容器 ID、StartedAt、OOM/重启计数前后一致。

前一版保留 `artifacts/web-before-frontend-workspace-20261003b`，更早原版为 `...20261003a`，备份含 web.tgz。回滚分支本批未触发，不称故障回滚演练通过。恢复须备份当前 web、选择明确版本，只替换 web 产物并单服务构建，健康/边缘 reload 后重验字节与交互。已有 rollout 拒绝复用备份目录；不要覆盖 `.env`/Compose/整份边缘配置，不启动短剧。

## 定向测试数据清理

`workspace-ui-fixture-20261003a.json` 仅含两名合成身份，不保存令牌。`revoke-workspace-sessions.py` 精确核对 MySQL ID/邮箱/handle，从该用户 Sa-Token session 在进程内解析令牌，正常注销活跃会话，仅收回精确测试令牌残余键与 account session key，不清空 Redis、不输出令牌。服务器 `stage-frontend-workspace-20261003a/workspace-sessions-revoked.json` 记录第一账号 3 会话，第二账号 0 残余会话，session keys 均 0。

`cleanup-workspace-ui.sh` 固定两身份/草稿/会话 ID，检查无非测试成员、真实用户关系、其他业务或公开引用，定向 utf8mb4 dump 后在事务内 CHECK 再验。实际删除 2 账号、1 私有草稿、2 会话、4 成员、2 消息及测试偏好/锁，剩余 scoped rows=0。备份 `backup-frontend-workspace-20261003b/workspace-ui-fixture.sql` 及 `.sha256` 校验成功、权限受限，可恢复。不删除 MinIO 对象、不调整桶配额。清理后公网发现页保留原文章“111”、总数 1，没有验收文章。

AI 短剧 5 业务容器仍 exited、根入口 503；KOKO Web/API 200。共享中间件继续运行。

最后由 `verify-workspace-release.sh` 再次只读核对 SQL/session 归零、9 个健康容器、LiveKit 运行、保留容器指纹、下线短剧及 HTTP 状态，实际输出 PASS。服务器证据 `stage-frontend-workspace-20261003b/workspace-final-verification.txt`。本地 5174 预览监听已不存在，未停止用户其他端口。

## 页面证据与未完成项

`docs/frontend-evidence-20261003a/`：`public-workspace.jpg` 最终公网发现页；`public-message.jpg` 服务端确认的私信；`public-session-expired.jpg` 真实撤销后的登录门槛；`public-mobile-navigation.jpg` 移动导航。`desktop-preview.jpg` 仅本地预览。

完整编辑组件化、领域 API 分包、持久化未确认消息队列、历史前进/后退、所有编辑/成员/安全/历史工具与通知非空 UI 回归仍待完成。通知 Broker 隔离故障/重投、人工重放/外部告警、对象恢复、正式 Discord/媒体供应商、TLS、RTC 双客户端、完整 RBAC、容量及异地恢复继续遵守阶段 2～9，不认定全目标完成。
