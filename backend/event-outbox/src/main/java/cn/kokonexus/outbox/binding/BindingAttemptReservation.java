package cn.kokonexus.outbox.binding;

import cn.kokonexus.api.asset.AssetUrl;
import cn.kokonexus.outbox.persistence.BindingAttemptMapper;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 必须经独立Spring代理在begin前提交OPEN；失败不能发RPC，不用内存记录替代。 */
@RequiredArgsConstructor
public class BindingAttemptReservation {

    /** 当前写域MP映射，不跨库访问。 */
    private final BindingAttemptMapper mapper;

    /** 新UUID一次登记，重复/碰撞拒绝，不能把结束凭据重新打开。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reserve(long ownerId, String assetId, String purpose, String requestId) {
        if (
            ownerId <= 0 ||
            AssetUrl.canonicalId(assetId) == null ||
            AssetUrl.canonicalId(requestId) == null ||
            !Set.of("AVATAR", "BANNER", "POST_COVER").contains(purpose == null ? "" : purpose)
        ) {
            throw new IllegalArgumentException("绑定凭据指纹无效");
        }
        BindingAttempt row = new BindingAttempt();
        row.setRequestId(requestId);
        row.setOwnerId(ownerId);
        row.setAssetId(assetId);
        row.setPurpose(purpose);
        row.setStatus("OPEN");
        if (mapper.insert(row) != 1) throw new IllegalStateException("绑定凭据未独立持久化");
    }
}
