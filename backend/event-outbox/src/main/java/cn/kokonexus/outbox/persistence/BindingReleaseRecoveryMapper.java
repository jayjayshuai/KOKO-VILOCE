package cn.kokonexus.outbox.persistence;

import cn.kokonexus.outbox.binding.BindingRelease;
import cn.kokonexus.outbox.binding.BindingReleaseAudit;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 原任务行锁、受理 CAS、不可变追加审计；不发送资产 RPC。 */
public interface BindingReleaseRecoveryMapper {
    BindingRelease lockRelease(@Param("requestId") String requestId);
    BindingReleaseAudit findAudit(@Param("commandId") String commandId);
    int requeue(@Param("requestId") String requestId, @Param("generation") int generation);
    int insertAudit(BindingReleaseAudit audit);
    List<BindingReleaseAudit> audits(
        @Param("requestId") String requestId,
        @Param("beforeGeneration") Integer beforeGeneration,
        @Param("limit") int limit
    );
}
