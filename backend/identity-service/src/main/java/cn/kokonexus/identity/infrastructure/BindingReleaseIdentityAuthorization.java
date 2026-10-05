package cn.kokonexus.identity.infrastructure;

import cn.kokonexus.api.operations.BindingReleaseReplayCommand;
import cn.kokonexus.identity.application.BindingReleaseConfirmationService;
import cn.kokonexus.identity.application.OperationsAuthorityService;
import cn.kokonexus.outbox.binding.BindingReleaseReplayAuthorization;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 身份域本地已代理的事实授权，不自调用 Dubbo。 */
@Component
@RequiredArgsConstructor
public class BindingReleaseIdentityAuthorization implements BindingReleaseReplayAuthorization {

    /** 当前权限代理。 */
    private final OperationsAuthorityService authority;
    /** 独立资产确认代理。 */
    private final BindingReleaseConfirmationService confirmation;

    @Override
    public void requirePermission(long actor, String permission) {
        authority.requirePermission(Long.toString(actor), permission);
    }

    @Override
    public void validateProof(
        long actor,
        String sessionHash,
        String domain,
        BindingReleaseReplayCommand command,
        String token
    ) {
        confirmation.validate(Long.toString(actor), sessionHash, domain, command, token);
    }
}
