package cn.kokonexus.outbox.binding;

import cn.kokonexus.api.asset.AssetUrl;
import cn.kokonexus.outbox.persistence.BindingReleaseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 必须与业务提交处于同一本地事务，不注册内存任务替代持久化。 */
@RequiredArgsConstructor
public class BindingReleaseWriter {

    /** 当前写域数据库的 MP 映射，不访问资产库。 */
    private final BindingReleaseMapper mapper;

    /** 登记失败使领域事务回滚；没有事务或只读事务时不写入任务。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void stage(long ownerId, String assetId, String purpose, String requestId) {
        if (
            ownerId <= 0 ||
            AssetUrl.canonicalId(assetId) == null ||
            AssetUrl.canonicalId(requestId) == null ||
            !org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive() ||
            org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        ) {
            throw new IllegalStateException("资产释放任务必须登记在有效写事务中");
        }
        BindingRelease release = new BindingRelease();
        release.setRequestId(requestId);
        release.setOwnerId(ownerId);
        release.setAssetId(assetId);
        release.setPurpose(purpose);
        release.setStatus("PENDING");
        release.setAttempts(0);
        if (mapper.insert(release) != 1) throw new IllegalStateException("资产释放任务未持久化");
    }
}
