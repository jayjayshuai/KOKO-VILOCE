package cn.kokonexus.outbox.operations;

import cn.kokonexus.api.operations.OutboxReplayCommand;

/** 业务域事实授权边界；确认域由本域 Facade 固定，不由 RPC 客户端提供。 */
public interface OutboxDomainAuthorization extends OutboxOperationsAuthorizer {
    void validateProof(long operatorId, String sessionHash, String domain, OutboxReplayCommand command, String token);
}
