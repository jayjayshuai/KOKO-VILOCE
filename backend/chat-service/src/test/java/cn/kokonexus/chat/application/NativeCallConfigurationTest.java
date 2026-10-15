package cn.kokonexus.chat.application;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class NativeCallConfigurationTest {

    @Test
    void incompleteMediaPathKeepsNativeCallControlAndReconcilerDisabledByDefault() {
        new ApplicationContextRunner()
            .withUserConfiguration(
                NativeCallService.class,
                NativeCallReconciler.class,
                NativeCallMediaService.class,
                NativeCallRetirementState.class,
                cn.kokonexus.chat.infrastructure.media.NativeCallIdentity.class,
                cn.kokonexus.chat.infrastructure.media.NativeCallTokens.class,
                cn.kokonexus.chat.infrastructure.media.LiveKitNativeCallGateway.class,
                cn.kokonexus.chat.infrastructure.media.NativeCallRetirementWorker.class
            )
            .run(context -> {
                assertNull(context.getStartupFailure());
                assertEquals(0, context.getBeansOfType(NativeCallService.class).size());
                assertEquals(0, context.getBeansOfType(NativeCallReconciler.class).size());
                assertEquals(0, context.getBeansOfType(NativeCallMediaService.class).size());
                assertEquals(0, context.getBeansOfType(NativeCallRetirementState.class).size());
            });
    }
}
