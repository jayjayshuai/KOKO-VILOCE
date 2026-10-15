package cn.kokonexus.identity.domain;

import lombok.Getter;
import lombok.Setter;

/** 用户单调授权围栏；不能删后重置轮次。 */
@Getter
@Setter
public class DiscordLinkCursor {

    /** 所属平台用户。 */
    private Long userId;
    /** 每次新绑定意图递增，旧回调不得覆盖。 */
    private Long generation;
    /** 本轮授权UUID，尚未发起时可空。 */
    private String activeRequestId;
}
