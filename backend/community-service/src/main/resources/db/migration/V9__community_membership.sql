ALTER TABLE community_member
    ADD COLUMN handle VARCHAR(32) NULL COMMENT '入会时公开用户名快照，历史成员可空，不作为认证依据',
    ADD COLUMN display_name VARCHAR(80) NULL COMMENT '入会时公开显示名称快照，不保存邮箱';
