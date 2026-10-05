package cn.kokonexus.identity.infrastructure;

import cn.kokonexus.api.operations.OutboxReplayCommand;
import cn.kokonexus.identity.application.OperationsAuthorityService;
import cn.kokonexus.outbox.operations.OutboxDomainAuthorization;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 身份域直接调用已代理的事实授权用例，避免自身 Dubbo 循环调用。 */
@Component
@RequiredArgsConstructor
public class OutboxIdentityAuthorization implements OutboxDomainAuthorization {

    /** 当前权限/确认事务代理，不持有权限缓存。 */
    private final OperationsAuthorityService authority;

    @Override
    public void requirePermission(long actor, String permission) {
        authority.requirePermission(Long.toString(actor), permission);
    }

    @Override
    public void validateProof(
        long actor,
        String sessionHash,
        String domain,
        OutboxReplayCommand command,
        String token
    ) {
        authority.validateReplayConfirmation(Long.toString(actor), sessionHash, domain, command, token);
    }
}
