# 语音房方向建议

> 状态：**方向建议；2026-10-05 用户要求参考推进，P0 开始实施，未发布。**
> 下文建议不代表验收事实；最新实施/验证见 [语音房实施计划](../docs/VOICE_ROOM_IMPLEMENTATION_PLAN.md)。
> 范围：`voice-service` 的产品方向、核心机制、数据模型、接口与分期路线。
> 相关现状证据见 [README](../README.md)、`docs/IMPLEMENTATION_PLAN.md`、`docs/ENTERPRISE_ROADMAP.md`。

---

## 0. 这份文档要解决什么

当前平台的产品描述是「直播语音」，但代码实际形态是**社群 + 文本 IM + 纯语音房**，其中：

- **直播只有排期与状态机**，没有任何媒体链路；
- **语音房只有建房/关房/人数上限/入会令牌**，缺房间内全部互动机制；
- **频道是建了表没实现**，Discord 式角色权限矩阵不存在。

本文给出一个取舍建议：**把资源集中在语音房，把"直播"重新定义为"语音直播"（公开大房间 + 开麦 + 公屏 + 礼物），不做视频直播，不做 Discord 式频道+角色矩阵。**

---

## 1. 结论

**深做语音房。** 三条理由，全部基于现有资产而非市场判断：

1. **语音房是唯一已经打通并验证过实时音视频链路的方向。** `voice-service` 虽小，但 LiveKit 已接入、房间已可编排、入会令牌已可签发，且已在真实网络上验收。
2. **它不需要网络效应。** 一间房 15 个人就是完整体验；社群需要几千人才有价值，视频直播需要内容供给与 CDN 带宽，这两道护城河当前都挖不动。
3. **现有"偏重"的工程能力在这里全部变成优势。** 事务性发件箱、幂等、审计、重放、RBAC——对一个社交产品偏重，对**带资金流水的付费语音房是刚需**。

---

## 2. 为什么不是另外两个方向

| 所需能力 | 频道式社群 | 视频直播 | **语音房** |
|---|---|---|---|
| 实时音视频层 | 不需要 | **完全没有** | **已有且验收过** |
| 文本聊天 | 需要（已有，最成熟） | 需要 | 需要（已有） |
| 角色/权限矩阵 | **完全没有** | 不需要 | 不需要（麦位即可） |
| 移动端 / 推送 | 强烈需要（缺） | 强烈需要（缺） | 需要（缺） |
| 网络效应门槛 | 极高 | 中（要主播供给） | **低** |
| 变现路径 | 弱（订阅，需规模） | 强但需带宽/CDN 投入 | **强且不依赖规模** |

- **不做 Discord 式**：护城河是网络效应，且该形态依赖移动端+推送（当前均缺），用户关掉浏览器即失联。
- **不做视频直播**：推流、转码、CDN、播放器、带宽成本全部为零起点；`live-service` 的 `provider` / `providerInputId` 字段本质在等一个尚未选定的供应商。

---

## 3. 现状盘点

### 3.1 已有（可直接复用）

| 能力 | 位置 | 说明 |
|---|---|---|
| LiveKit 房间编排 | `voice-service/.../LiveKitVoiceMediaGateway.java` | `provision` / `issueJoinToken` / `delete`；`CanPublishSources(List.of("microphone"))`，**纯麦克风** |
| 房间模型 | `voice-service/.../domain/VoiceRoom.java` | ownerId、slug、title、topic、status、providerRoomName、maxParticipants |
| 入会凭证 | 同上 | 限房间、短时效令牌；密钥启动即校验长度，缺失则拒绝启动 |
| 文本 IM 底座 | `chat-service/` | 会话序号、幂等 ACK、已读游标、会话内搜索、收藏、拉黑、举报+人工结案 |
| 可靠投递 | `event-outbox/` | 事务内登记 + 租约 claim + 指数退避 + 死信 + 重放审计 |
| 运营权限 | `identity-service/` + `event-outbox/operations/` | RBAC、本人密码二次确认、审计留痕；不是双人审批 |
| 资产与配额 | `asset-service/` | 图片校验、MinIO、事务配额 |

### 3.2 缺失（本文要补的部分）

| 缺口 | 证据 |
|---|---|
| **麦位体系** | 全仓无 seat / 麦位相关类型；房间只有 `maxParticipants` |
| **房间内实时事件** | 无席位变更/静音/发言/礼物的推送协议 |
| **礼物与资金流水** | 全仓无 gift / wallet / 支付相关类型 |
| **房间成员与在线状态** | 无 `voice_room_member` 之类表；无心跳/离线判麦 |
| **房管操作审计** | 运营侧有审计，房间侧没有 |
| **语音侧内容安全** | 有举报与人工结案，无房间名/公屏关键词、无录音抽检 |
| **跨节点分发** | `chat-service` 无 pub/sub；README 自述「当前仅单实例，扩容前必须补跨节点分发」 |
| **推送通道** | 通知仅有站内收件箱；APNs / FCM / WebPush / 邮件 / 短信均无 |
| **移动端** | 只有 Vue 3 Web 工作台 |
| **HTTPS / WSS** | README 自述「公网 HTTP 仅用于阶段验收」 |

### 3.3 需要一并处理的既有技术债

- **`community_channel` 表已建但零引用**：`community-service/src/main/resources/db/migration/V1__community.sql` 建了 `community_channel`（name / channel_type / position / 同社区唯一），但 Java 源码中无任何引用。建议**明确标注为未实现或直接下线**，避免后来者误判已有频道能力。
- **`community_member.role` 仅是默认 `'MEMBER'` 的 VARCHAR**，无角色表、无权限矩阵。语音房**不要**建在这套之上，房间内权限用独立的房间角色表达。

---

## 4. 核心机制：麦位状态机

这是语音房区别于"语音通话"的全部价值所在，也是当前完全缺失的部分。

### 4.1 房间角色

| 角色 | 说明 |
|---|---|
| `OWNER` | 房主，唯一，可转让 |
| `ADMIN` | 房管，由房主设置，有踢人/闭麦/锁麦权限 |
| `HOST` | 嘉宾，在麦且参与内容 |
| `LISTENER` | 听众，默认角色 |

> 注意：这是**房间内**角色，与 `community_member.role` 无关，不要复用。

### 4.2 麦位状态

```
                    ┌──────── 锁麦 / 解锁（OWNER, ADMIN）────────┐
                    ▼                                            │
   EMPTY ──────────────────────────────────────────────────────┘
     │  ▲
     │  │ 下麦 / 踢下麦 / 心跳超时回收
     │  │
     │  └──────── ON_MIC ◄──── 抱麦（OWNER/ADMIN 直接拉）
     │                ▲
     │                │ 同意申请 / 接受邀请
     ▼                │
  RESERVED ───────────┘
   （申请中/邀请中，占用席位但不发声）
```

| 状态 | 含义 | 是否占位 |
|---|---|---|
| `EMPTY` | 空麦，可申请 | 否 |
| `LOCKED` | 房主锁麦，禁止申请 | 否（保留位置） |
| `RESERVED` | 已申请/已邀请，待确认 | **是** |
| `ON_MIC` | 在麦 | **是** |

### 4.3 操作与转换

| 操作 | 发起人 | 前置条件 | 结果 |
|---|---|---|---|
| 申请上麦 | `LISTENER` | 存在 `EMPTY` 席位 | 席位 → `RESERVED`，生成 `PENDING` 申请 |
| 邀请上麦 | `OWNER` / `ADMIN` | 存在 `EMPTY` 席位 | 席位 → `RESERVED`，生成 `PENDING` 邀请（带过期） |
| 抱麦 | `OWNER` / `ADMIN` | 存在 `EMPTY` 席位 | 席位 → `ON_MIC`（跳过确认） |
| 同意申请 | `OWNER` / `ADMIN` | 申请为 `PENDING` | 席位 → `ON_MIC`，角色 → `HOST` |
| 拒绝申请 | `OWNER` / `ADMIN` | 申请为 `PENDING` | 席位 → `EMPTY`，申请 → `REJECTED` |
| 下麦 | 本人在麦 | 在麦 | 席位 → `EMPTY`，角色 → `LISTENER` |
| 闭麦 / 开麦 | 本人 或 `OWNER`/`ADMIN` | 在麦 | `muted` 翻转，**不释放席位** |
| 锁麦 / 解锁 | `OWNER` / `ADMIN` | 席位为 `EMPTY` | `EMPTY` ↔ `LOCKED` |
| 踢下麦 | `OWNER` / `ADMIN` | 目标在麦 | 席位 → `EMPTY`，写审计，可选冷却 |
| 转让房主 | `OWNER` | 目标为房间成员 | `OWNER` ↔ `ADMIN`，写审计 |

### 4.4 并发与租约（复用项目已有模式）

| 风险 | 处理方式 |
|---|---|
| 两人抢同一麦位 | `UNIQUE(room_id, seat_no)` + `version` 乐观锁；唯一键冲突返回明确业务错误，不重试 |
| 房间人数上限竞态 | 与 `community_member` 一致：**锁房间行 + 同事务更新人数/版本** |
| 客户端崩溃占麦不清 | **席位租约**：`seat.lease_until` + 客户端心跳续约，超时回收。这是 `OutboxMapper.xml` 的 `claim_token` / `lease_until` 模式的直接复用 |
| 重复申请/重复上麦 | 幂等键（沿用 `clientMessageId`、`requestId` 的做法） |
| 房管滥用 | 所有管理动作写 `voice_room_action` 审计表，可回溯（对齐 `outbox_replay_audit` 的思路） |
| 申请洪泛 | 复用 `OperationsCredentialLimiter` 的限流思路，按用户+房间维度限频 |

**设计原则（与现有代码一致）：**
- 状态与人数在同一事务内更新，不跨事务修补；
- 依赖故障（LiveKit 不可达）**不伪装为业务成功**，参照 `AssetPublicationLookup` 的 `addSuppressed` + 专用异常模式；
- 房间侧写操作**必须**携带网关注入的身份，`voice-service` 当前没有网关密钥校验，**这是补麦位之前要先补的**（对齐 `AssetGatewayFilter` / `ChatGatewayFilter`）。

---

## 5. 数据模型建议

### 5.1 新增表

```sql
-- 房间在线成员与房间角色（与 community_member 无关）
CREATE TABLE voice_room_member (
    room_id     BIGINT      NOT NULL,
    user_id     BIGINT      NOT NULL,
    room_role   VARCHAR(16) NOT NULL DEFAULT 'LISTENER',  -- OWNER/ADMIN/HOST/LISTENER
    muted       TINYINT(1)  NOT NULL DEFAULT 0,
    joined_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    lease_until DATETIME(6) NULL,                          -- 心跳租约
    PRIMARY KEY (room_id, user_id),
    KEY idx_voice_member_stale (room_id, lease_until)
);

-- 麦位
CREATE TABLE voice_seat (
    room_id     BIGINT      NOT NULL,
    seat_no     INT         NOT NULL,
    state       VARCHAR(16) NOT NULL DEFAULT 'EMPTY',      -- EMPTY/LOCKED/RESERVED/ON_MIC
    user_id     BIGINT      NULL,
    muted       TINYINT(1)  NOT NULL DEFAULT 0,
    lease_until DATETIME(6) NULL,
    version     INT         NOT NULL DEFAULT 0,
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (room_id, seat_no),
    KEY idx_voice_seat_user (user_id),
    CONSTRAINT ck_voice_seat_state CHECK (state IN ('EMPTY','LOCKED','RESERVED','ON_MIC'))
);

-- 申请 / 邀请
CREATE TABLE voice_seat_request (
    id          CHAR(36)    NOT NULL,
    room_id     BIGINT      NOT NULL,
    seat_no     INT         NOT NULL,
    user_id     BIGINT      NOT NULL,
    request_type VARCHAR(16) NOT NULL,                     -- APPLY / INVITE
    state       VARCHAR(16) NOT NULL DEFAULT 'PENDING',     -- PENDING/ACCEPTED/REJECTED/EXPIRED/CANCELLED
    expires_at  DATETIME(6) NULL,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_voice_request_pending (room_id, state, created_at)
);

-- 房管操作审计
CREATE TABLE voice_room_action (
    id          CHAR(36)    NOT NULL,
    room_id     BIGINT      NOT NULL,
    operator_id BIGINT      NOT NULL,
    target_id   BIGINT      NULL,
    action      VARCHAR(32) NOT NULL,                      -- KICK/MUTE/LOCK/TRANSFER/BAN
    detail      VARCHAR(200) NULL,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_voice_action_room (room_id, created_at)
);
```

### 5.2 变现相关（见第 7 节的可靠性要求）

```sql
gift_catalog     -- 礼物目录：id/name/price/asset_url/enabled/version
wallet           -- 余额：user_id/coin_balance/version
wallet_ledger    -- 流水：id/user_id/delta/balance_after/reason/ref_id/created_at（只追加）
gift_order       -- 送礼幂等：request_id(UNIQUE)/room_id/seat_no/sender_id/receiver_id/gift_id/amount/state
```

### 5.3 复用而非新建

| 需求 | 复用 |
|---|---|
| 房间公屏 | `chat-service` 的 `conversation` + `chat_message`（房间 ↔ 会话 1:1 映射） |
| 礼物事件投递 | `event-outbox` 的 `OutboxWriter` + `OutboxRelay` |
| 开播/开房通知 | `notification-service` 的 fanout |
| 图片资产（礼物图、房间封面） | `asset-service` |

---

## 6. 接口建议

### 6.1 HTTP（经网关，路径前缀 `/api/voice/`）

```
GET    /api/voice/rooms/{slug}                        房间详情 + 席位快照
POST   /api/voice/rooms/{id}/join                     加入房间（返回 LiveKit 令牌 + 快照）
POST   /api/voice/rooms/{id}/leave                    离开房间（释放席位）
POST   /api/voice/rooms/{id}/heartbeat                续约租约

POST   /api/voice/rooms/{id}/seats/{seatNo}/apply      申请上麦
POST   /api/voice/rooms/{id}/seats/{seatNo}/invite     邀请上麦
POST   /api/voice/rooms/{id}/seats/{seatNo}/pull       抱麦
POST   /api/voice/rooms/{id}/requests/{reqId}/accept   同意
POST   /api/voice/rooms/{id}/requests/{reqId}/reject   拒绝
POST   /api/voice/rooms/{id}/seats/{seatNo}/leave      下麦
POST   /api/voice/rooms/{id}/seats/{seatNo}/mute       闭麦/开麦
POST   /api/voice/rooms/{id}/seats/{seatNo}/lock       锁麦/解锁
POST   /api/voice/rooms/{id}/seats/{seatNo}/kick       踢下麦
POST   /api/voice/rooms/{id}/owner/transfer            转让房主

POST   /api/voice/rooms/{id}/gifts                     送礼（必须带幂等键）
GET    /api/voice/rooms/{id}/leaderboard               房间榜单
```

### 6.2 WebSocket 事件

席位与状态变更**必须实时推送**，否则前端只能轮询。建议独立一条房间事件通道：

| 事件 | 触发 |
|---|---|
| `ROOM_SNAPSHOT` | 加入时全量（房间、席位、成员） |
| `SEAT_UPDATE` | 任何席位状态/占用变化 |
| `MEMBER_JOIN` / `MEMBER_LEAVE` | 成员进出 |
| `MUTE_STATE` | 闭麦/开麦 |
| `SPEAKING` | 发言中指示（可由 LiveKit 客户端 SDK 的活跃发言者事件映射） |
| `SEAT_REQUEST` | 新申请/邀请，仅推给 `OWNER`/`ADMIN` |
| `GIFT` | 送礼广播 |
| `KICKED` | 被踢，客户端应立即断开并提示 |
| `ROOM_CLOSED` | 房间关闭 |

> **前置依赖**：实时推送要求房间事件能在多个实例间分发。`chat-service` 当前无跨节点分发能力，**这是语音房扩容的硬阻塞**，见第 8 节 P0。

---

## 7. 礼物与资金流水：现有工程能力的正面复用

这是本文认为**最被低估的机会**。项目已有的事务性发件箱 + 幂等 + 审计 + 二次确认，对普通社交功能偏重，但**资金类操作正是它们的标准应用场景**：

| 资金场景要求 | 现有可直接复用的能力 |
|---|---|
| 送礼不可重复扣款 | `OutboxWriter` 的 `Propagation.MANDATORY` 同事务写入 + 唯一幂等键 |
| 扣款与入账必须一致 | 单事务内操作 `wallet` + `wallet_ledger` + `gift_order`，失败不落账 |
| 流水不可篡改 | `wallet_ledger` 只追加，不更新不删除 |
| 可对账、可回溯 | 复用 `outbox_replay_audit` 的审计思路 |
| 敏感操作需确认 | 现有为本人密码确认；提现/大额所需双人独立审批尚须另外实现，不能直接复用冒充 |
| 异常可排障 | **注意**：`OutboxRelay` 与 `BindingReleaseRelay` 当前丢失异常 cause，做资金流水前**必须先修**，否则对账失败无法定位 |

**注意边界**：本文不建议自建支付通道。充值与提现应接第三方支付，平台侧只维护可审计的内部账本。

---

## 8. 分期路线

### P0 — 上线门槛（不做完，后面都是空中楼阁）

| 项 | 说明 |
|---|---|
| HTTPS / WSS | README 自述缺失，实时语音必须有 WSS |
| 备份与恢复 | README 自述缺失；语音房有房间/流水数据后更关键 |
| 告警送达 | 当前仅有告警草案；资金类功能上线前必须有真实告警 |
| **chat 跨节点分发** | 房间事件与公屏都依赖它，是扩容硬阻塞 |
| **`voice-service` 网关密钥校验** | 当前 voice 不校验网关注入身份，补麦位前必须先补 |
| 推送通道 | 至少一份（WebPush 或移动端推送），否则"你关注的房间开播了"无处可去 |

### P1 — 麦位核心（语音房之所以是语音房）

- 房间成员与房间角色
- 麦位状态机（第 4 节全部转换）
- 心跳与租约回收
- 房间 WebSocket 事件通道
- 房管操作审计
- 前端房间面板（席位网格、上麦/下麦、闭麦）

### P2 — 变现

- 礼物目录 + 钱包 + 流水（严格按第 7 节）
- 送礼幂等与对账
- 房间榜单、房间热度
- 房主收益与结算（依赖双人确认）

### P3 — 安全与合规

- 房间名 / 公屏关键词过滤
- 语音内容抽检（录音留存 + 抽检流程）
- 房间内权限矩阵细化（`ADMIN` 权限项）
- 举报 → 房间处置联动（已有聊天侧举报底座）
- 封禁体系（房间级 / 平台级）与申诉

### P4 — 规模与体验

- 移动端（语音房没有移动端约等于没有产品）
- 房间发现、分类、榜单分发
- 多节点与容量门槛

---

## 9. 明确不做的事

| 不做 | 原因 |
|---|---|
| **视频直播（推流/转码/CDN）** | 零起点，成本最高，差异化最低 |
| **摄像头 / 屏幕共享** | LiveKit 支持但产品上主动关闭（当前仅授权 microphone）；保持语音定位 |
| **Discord 式频道 + 角色权限矩阵** | 护城河是网络效应，且依赖移动端+推送 |
| **在 `community_member.role` 上扩展房间权限** | 两套语义不同，混用会导致权限漏洞 |
| **自建支付通道** | 只维护内部可审计账本，充提走第三方 |

**需要一并决策的既有问题**：`community_channel` 表已建但零引用——建议**要么明确标注未实现，要么下线**，不要留着让人误判。

---

## 10. 风险与硬门槛

| 风险 | 说明 |
|---|---|
| 语音内容审核难 | 文本可过滤，语音需要录音+抽检+人工，成本远高于文本；国内合规是上线阻断项 |
| 移动端缺失 | 语音房的核心使用场景在移动端，Web 只能验证 |
| 单实例天花板 | 跨节点分发不做，房间数上不去 |
| 并发正确性 | 麦位抢占、人数上限、礼物扣款都是高并发写，必须走唯一键/乐观锁/单事务 |
| 依赖故障语义 | LiveKit / 支付不可达时不得伪装成功；当前 relay 层丢异常原因的问题需先修 |
| 封禁与合规留痕 | 房间处罚必须可审计、可申诉 |

---

## 11. 验收边界（什么算"完成"）

参照项目既有验收习惯，本方向的"完成"应至少满足：

1. **麦位状态机的每条转换都有自动化测试**，含并发抢占、租约超时回收、越权拒绝；
2. **资金流水的幂等与对账有真实数据库验证**，含重复请求、事务回滚、并发扣款；
3. **房间事件在真实多实例下不丢不重**（这是 P0 跨节点分发的验收条件，不能只测单实例）；
4. **HTTPS/WSS 在真实域名下验证通过**，不是本地自签；
5. **备份恢复做过一次真实演练**，含房间与流水数据；
6. **告警真实送达过**，不是只有规则文件；
7. **内容安全的处置链路端到端跑通一次**（举报 → 处置 → 留痕 → 申诉入口）。

**在上述任一未达成前，生产开关保持关闭，不对外声明已具备语音房能力。**

## 12. 实施记录（不替代上文验收）

2026-10-05 开始P0源码：语音服务内部网关密钥启动校验、可信身份/重复头/越界拒绝、
发现接口允许网关匿名访问，敏感响应no-store；Gateway清除伪造头后对语音及其文档注入内部密钥。
尚未部署，P0未通过，P1～P4未完成。建议缩减视频/Discord方向不等于已批准删除已有功能。
同步补了前端默认不开麦/取消/会话围栏与媒体清理、HTTPS页面及WSS连接门槛。
投递诊断增加有界安全cause链；SQL成功确认失败不伪装成Broker发送失败。
这些源码/局部验证不等于麦位授权、真实多节点/媒体闭环或资金能力，详见
[本批验证](../docs/VOICE_ROOM_P0_VERIFICATION_20261005.md)。
