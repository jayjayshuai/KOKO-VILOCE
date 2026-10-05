package cn.kokonexus.outbox.binding;

import cn.kokonexus.api.operations.BindingReleaseReplayCommand;
import cn.kokonexus.outbox.operations.OutboxOperationsAuthorizer;

/** 独立资产动作授权，禁止通知确认票据跨动作复用。 */
public interface BindingReleaseReplayAuthorization extends OutboxOperationsAuthorizer {
    void validateProof(
        long actor,
        String sessionHash,
        String domain,
        BindingReleaseReplayCommand command,
        String token
    );
}
