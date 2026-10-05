# KOKO Nexus Messaging v1

## 认证和连接

登录后连接 `/koko-api/chat/ws`（本地 `/api/chat/ws`）。浏览器自动携带同源 Cookie；禁止在 URL、localStorage 或帧正文传递登录令牌。Origin 必须精确匹配配置。网关校验 Sa-Token，删除伪造可信头，注入用户标识及内部密钥；Netty 握手和每次 SEND/PING 再验证共享 Redis 会话。聊天端口 8087/8097 仅 Docker 内网，不映射公网。

正式公网使用必须 HTTPS/WSS；当前 IP 的 HTTP 只是阶段验收入口。上线域名变更时同时修改 Origin 白名单。

## 帧协议

客户端每 25 秒发送 `{"type":"PING"}`，收到 `PONG`。握手成功收到 `READY`。只支持纯文本消息，帧及聚合消息最多 8192 字节；消息正文最大 2000 Java 字符。每连接最多 5 帧/秒，单用户最多 3 个连接，单实例最大 500 连接。

发送示例（均为示例 UUID，不是真实账号凭据）：

```json
{"type":"SEND","conversationId":"6efbcbdf-b588-4a8d-a81c-ce2149945790","clientMessageId":"a8b9d016-e9a1-4f5e-8570-b3c96521ef06","body":"你好"}
```

- `ACK` 的 `message` 是已提交消息，含服务端 UUID、会话内 seq、发送者及时间。ACK 不表示送达或已读。
- `ERROR` 含 code/message，可含 clientMessageId。超时和 UNAVAILABLE 必须复用原 UUID 与正文重试；同 UUID 改正文会冲突。
- `SYNC` 不带会话标识或正文，只提示查库，避免成员移除与推送之间的竞争泄露内容。前端合并短时间提示，定期同步兜底。
- 非成员、解散会话、无效格式不会收到成功 ACK；登录失效断开连接。慢客户端写缓冲不可写时断开，不能无限堆积。
- 任一方拉黑后，新私信 SEND 返回 `FORBIDDEN` 并带原 clientMessageId，不写消息、不消耗序号。原 UUID/正文对应的已提交消息仍返回原 ACK；不能因为后来拉黑就伪装为未提交。

## 恢复和群聊

`GET /chat/conversations` 游标分页本人会话，`GET /chat/conversations/{id}/messages?after={seq}` 向后补拉，`before` 向前分页，两者不能共用，单页最多 100。按服务端 UUID 去重。消息游标持久化是恢复事实来源，实时提示是尽力而为。当前仅支持单聊天实例，尚无跨节点广播，不得直接扩容多副本。

群主可以改名、增删成员及解散；普通成员只可退出，群主不能退出自己。总人数最多 50。新成员/重新加入者不能读取加入前的群历史。会话锁覆盖发消息、授权读历史及成员修改；已读使用 GREATEST，不回退，不超过最后已提交消息。

## 文档和运维

登录后 Knife4j：`/koko-api/chat/docs/doc.html`，聚合账号、社区、直播、语音、通知、媒体和聊天契约。HTTP API 使用 OpenAPI3 注解，不把可信身份头列为用户可填写的参数。Netty 帧不是 HTTP 操作，其契约以本文为准。

生产镜像 Java 21，聊天 JVM Xmx160m、最大直接内存32m、容器448MiB；业务线程2、队列128，JDBC池4。配置限额不是压测结果，500 连接是保护阈值，不代表当前小内存服务器达到500连接容量。拉黑与消息举报/人工结案契约见下节；账号封禁、申诉、完整运营 RBAC 和合规留存仍未完成。

## 聊天安全契约

以下路径以 `/koko-api` 为公开前缀，全部要求登录。前端在消息中心的「安全中心」设置拉黑，在他人消息旁发起真实消息举报。

- `GET/POST /chat/blocks`：本人设置列表/按公开用户名拉黑；`DELETE /chat/blocks/{targetId}`：仅解除本人设置。重复操作幂等，每人最多 1000 个设置，列表分页最多 100。不存在的设置解除不分配任意用户对记录。名单不透露对方的设置。
- 任一方拉黑阻止双方的新私信、新消息和相互新群邀请；保留私信历史，已有共同群依旧可收发。授权在共享用户对事务锁下当前读，不复用旧 RR 快照。
- `POST /chat/reports`：输入 conversationId/messageId/reason/detail；原因 HARASSMENT/SPAM/THREAT/OTHER，说明 1～500 字符。目标和证据只取真实消息，客户端伪造 evidenceBody/reportedUserId 不参与事实。只允许当前成员举报入群边界之后的他人消息，离群后再次提交返回 404。
- 同一举报人/消息一份，同原因说明重试返回原记录，改变内容返回 409；滚动 24 小时最多 20 份新举报，用户级锁序列化配额。
- `GET /chat/reports`、`GET /chat/reports/{id}`：仅本人进度，包括状态、结论及时间，不含证据、举报人或审核员 ID；离群后仍可查询本人的进度。
- `GET /chat/safety/capabilities`：只用于界面显隐。`GET /chat/moderation/reports` 必须有 `chat:reports:read`；`PATCH /chat/moderation/reports/{id}` 必须有 `chat:reports:review`，每次重新核对共享 Sa-Token 会话与可信用户头。
- 审核输入 version、decision（RESOLVED/REJECTED）、note（1～500 字符）；只能 PENDING→最终结论，旧版本或覆盖他人决定返回 409，决定/不可变审计同事务提交。原审核员相同决定/说明重试幂等；不能审核自己举报或涉及自己的消息。结案不等于账号封禁。
- 列表用 UUID 升序游标，不等于时间排序；并发新记录可能落在已翻过的游标前方，应刷新首批获取。受权限保护的审核投影单独提供证据正文；普通投影禁止直接序列化领域实体。

审核员初期通过 chat 的 `CHAT_MODERATOR_USER_IDS` 配置为明确的正整数用户 ID 列表，经 `StpInterface` 提供 Sa-Token 角色/权限，默认空值；不是完整动态 RBAC。必须由项目负责人指定实际账号，不能使用 `*` 或自动授予第一个注册用户。撤权需要更新配置、重建 chat 并验证无权限；不要缓存永久权限。

## 会话内历史检索与个人消息收藏（V3）

下列路径以 `/koko-api` 为公开前缀，全部要求登录和 ACTIVE 会话中的当前成员。事务会话锁与发送、移除成员、解散串行化；不接受客户端身份或正文作为事实。只返回 `seq > joinedSeq` 的可读消息，移除和重新加入不能通过收藏绕过旧历史边界。

| 方法 / 路径 | 契约 |
| --- | --- |
| `GET /chat/conversations/{id}/search?query=...&before=...&size=20` | 区分大小写的字面包含；2～64 Java UTF-16 字符，拒绝 NUL；`%/_/=` 都是普通字符 |
| `GET /chat/conversations/{id}/bookmarks?before=...&size=20` | 只返回本人当前可读的真实消息，不公开其他人的收藏 |
| `PUT /chat/conversations/{id}/bookmarks/{messageId}` | 204；真实、可读且属于该会话；每人每会话最多 1000 个引用，重试幂等 |
| `DELETE /chat/conversations/{id}/bookmarks/{messageId}` | 204；仅取消本人引用，未收藏亦成功，不修改原消息 |
| `DELETE /chat/conversations/{id}/bookmarks` | 204；清空本会话本人引用，包括重加入后不可读的旧引用，不影响其他成员 |

两个列表响应均为 `{ "items": [...], "nextBefore": number|null }`，按会话内序号倒序，单页 1～50 条；`before` 是独占上界，默认最后已提交序号 + 1，禁止非正数和超过末尾的游标。不要计算无界总数。额外匹配未返回时，下一页从本页最后返回序号之前继续，避免遗漏。

搜索每次仅扫描最多 2000 个序号，不是全文索引或相关度排序。无匹配仍可能返回 `nextBefore`：例如末尾 2205、入群边界 0，首批扫描 206～2205，空结果返回 206，调用方可继续扫描 1～205。只有 `nextBefore=null` 才表示这一轮已到可读历史边界。前端保留已提交搜索词；编辑输入不改变正在翻页的查询。参数通过 MP/XML 绑定，LIKE 字面转义与序号范围均在服务端执行。

收藏保存引用，不复制正文，也不授予永久访问权；配额包含不可读的旧引用，重新加入后可清空本会话本人设置释放配额。私信拉黑保留原可读历史，但不改变原有新消息阻断。搜索/收藏不更新已读序号，前端打开工具时暂停隐藏历史的自动已读。

JDBC/XML 查询超时和事务超时返回通用 `503/HISTORY_BUSY`（对应异常映射有测试），不输出 SQL 或凭据；有界扫描、数据库执行限制和查询超时是保护配置，不是容量或所有驱动超时分支已实测的证明。客户端读取取消不等于服务端写事务撤销；只有服务端成功响应才能显示成功。分页读取中禁止收藏写入，切换会话/栏目、卸载取消旧读取并忽略迟到响应。
