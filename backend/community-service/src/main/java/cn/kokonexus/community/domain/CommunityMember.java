package cn.kokonexus.community.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** community-service：CommunityMember 领域类型；字段单位、状态及可空性见各属性说明。 */
@lombok.Getter
@lombok.Setter
@TableName("community_member")
public class CommunityMember {

    /** 所属社区 ID。 */
    private Long communityId;
    /** 操作用户 ID。 */
    private Long userId;
    /** 成员角色。 */
    private String role;
    /** 加入时间，Asia/Shanghai。 */
    private LocalDateTime joinedAt;

    /** 入会时公开用户名快照；旧所有者记录可能为空，不是登录身份依据。 */
    private String handle;
    /** 入会时公开显示名称快照；不保存邮箱，不保证随资料修改同步更新。 */
    private String displayName;
}
