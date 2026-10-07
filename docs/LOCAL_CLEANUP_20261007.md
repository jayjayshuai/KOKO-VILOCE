# 本机项目临时产物清理

2026-10-07 已删除 8 个精确目标，共 **1,537,362,697 字节（约 1.43 GiB）**。
删除前按绝对路径检查项目边界、符号链接和活动进程引用。

五个 `verify` 目录与保留的原始证据目录逐文件 SHA-256 完全一致：

- `.runtime/chat-browser-evidence-verify-20261005a`
- `.runtime/chat-gateway-evidence-verify-20261005a`
- `.runtime/chat-cluster-evidence-verify-20261005a`
- `.runtime/voice-core-delivery-verify-20261006a`
- `.runtime/voice-owner-delivery-verify-20261005a`

另删除已解压且对应可执行程序仍存在的三份下载包：

- `.runtime/temurin21-20261004a/jdk.zip`
- `.runtime/mysql-chat-cluster-20261005a/mysql-8.4.11-winx64.zip`
- `.runtime/mysql-media-lab-20261006a/mysql-8.4.8-winx64.zip`

保留本地调试启动器、Java/Python/浏览器依赖、所有 MySQL data、SQL 备份、原始证据、
归档、node_modules 和用户源码；删除后再次核对原件和可执行程序。
重复证据可从保留的原件或归档恢复；安装包需重新下载。

首次执行删除两份副本后，在更新清单 ACL 时因 Windows 审计权限不足停止。
恢复执行核对已完成目标与原清单，只处理剩余六项，沿用目录 ACL，未提权或更改系统权限。
私有清单、失败记录和完成报告留在忽略的 `.runtime`，没有提交路径摘要之外的私有运行内容。
服务器容器、镜像、数据库及其他项目未清理。
