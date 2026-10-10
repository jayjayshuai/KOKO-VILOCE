-- 跨房间失效租约扫描；不修改已应用迁移，不改变现有成员/麦位/授权事实。
-- 房间内命令继续使用idx_voice_active_member；后台只发现有界房间，写入时另持房间锁。
CREATE INDEX idx_voice_expired_members ON voice_room_member(member_state,lease_until,room_id);
