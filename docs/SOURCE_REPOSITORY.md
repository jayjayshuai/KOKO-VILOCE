# 源码仓库与提交边界

源码远端：`https://github.com/jayjayshuai/KOKO-VILOCE.git`。

提交Java模块/版本迁移、Web源码与测试、构建锁文件、规范工具、通用部署模板和脱敏验收说明。
首次导入不是生产发布：当前语音P0仍进行中，新绑定凭据协议仍未完成数据库/网络验收，
不可因仓库有代码就启用未验收开关或整体发布。状态见README及企业路线。

不提交服务器.env、临时凭据、node_modules、target/dist、SSH文件、隔离资源、数据库备份、
大型验收包或实际运行输出。它们保留在本机/服务器原位置，未因Git初始化删除。
`deploy/tests`与服务器专用发布/回收脚本留在本地，不公开操作地址、SSH用户或部署目录。
公开文档内相关地址与目录为脱敏占位，不能直接当作可执行部署命令；原始记录仍在私有证据包。
验收文档中的本地证据链接不保证在GitHub可下载；摘要用于原持有人复核。

开发构建见README。生产路径仅公共变量，按需复制`web/.env.production.example`到
`web/.env.production`再构建；后端秘密通过部署环境提供，不复制前端模板承载密钥。
工作台UI验收脚本改为必须临时注入`KOKO_UI_FIXTURE_PASSWORD`，不发布旧固定验收密码。
该环境专用脚本也不进入公开仓库。聊天允许来源必须通过`KOKO_CHAT_ALLOWED_ORIGINS`注入，
仓库模板不带真实服务器地址；本次仅修改待提交模板，不修改服务器当前运行配置。
Nacos密码也必须从部署环境显式注入，模板不提供默认登录密码。
首次提交保留历史迁移文件原字节（包括已有空白EOF），不为了Git空白提示改Flyway校验和。

`.gitignore`只是误提交防护，不替代秘密扫描。提交前查看暂存路径与diff，不以
`git add -f`绕过排除规则，不强推或覆盖远端已有历史。

## 提交防护

本地启用`git config core.hooksPath .githooks`；新克隆须同样启用。
`npm run check:secrets`扫描实际暂存blob，`npm run check:secrets:history`扫描HEAD可达历史。
pre-commit检查暂存，pre-push从Git stdin检查实际推送对象的可达历史（不限HEAD），
避免删除当前文件或推送其他分支绕过。发现风险、Git读取失败、不可检查的二进制或过大blob均拒绝，
只输出文件路径与规则名，不输出秘密值。默认检查私钥、常见供应商令牌、JWT/Bearer、
URL凭据、硬编码凭据、个人目录及非白名单部署/证据文件。

需屏蔽实际部署主机时，仅在本机`.git/config`以`koko.privateHost`追加，配置不提交。
文档使用`deployment.example.invalid`及`/srv/deployment-home/`脱敏占位；
Web拒绝不安全连接的测试采用文档专用IP，不指向真实部署。
防护为启发式匹配，不是“绝无秘密”的证明，也不能替代代码审查；Git钩子可被绕过，
未来CI及远端平台Secret Scanning仍须配置，不能仅靠本机钩子作为唯一安全门槛。
