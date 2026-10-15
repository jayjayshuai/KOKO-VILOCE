package cn.kokonexus.chat.application;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class NativeCallConfigurationTest {

    @Test
    void incompleteMediaPathKeepsNativeCallControlAndReconcilerDisabledByDefault() {
        new ApplicationContextRunner()
            .withUserConfiguration(NativeCallService.class, NativeCallReconciler.class)
            .run(context -> {
                assertNull(context.getStartupFailure());
                assertEquals(0, context.getBeansOfType(NativeCallService.class).size());
                assertEquals(0, context.getBeansOfType(NativeCallReconciler.class).size());
            });
    }
}
