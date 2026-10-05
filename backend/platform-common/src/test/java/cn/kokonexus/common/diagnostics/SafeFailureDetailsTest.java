package cn.kokonexus.common.diagnostics;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 异常链定位保留，不输出SQL/供应商消息里的秘密；循环/长链也必须有界。 */
class SafeFailureDetailsTest {

    @Test
    void rootAndNestedTypesAndCodeLocationAreVisibleButMessagesNeverCopied() {
        var cause = new java.io.IOException("password=secret-token-and-SQL-content");
        var wrapper = new IllegalStateException("user-content-never-copy", cause);
        String value = SafeFailureDetails.describe(wrapper);
        assertTrue(value.contains("IllegalStateException"));
        assertTrue(value.contains("IOException"));
        assertTrue(value.contains("SafeFailureDetailsTest"));
        assertFalse(value.contains("secret-token"));
        assertFalse(value.contains("user-content"));
        assertTrue(value.length() <= 500);
    }

    @Test
    void cyclicAndDeepCauseChainsAndLongFramesAreBounded() {
        var first = new IllegalStateException("secret-a");
        var second = new IllegalArgumentException("secret-b");
        first.initCause(second);
        second.initCause(first);
        assertTrue(SafeFailureDetails.describe(first).length() <= 500);
        Throwable deep = new RuntimeException("last-secret");
        for (int i = 0; i < 50; i++) deep = new RuntimeException("secret-" + i, deep);
        String details = SafeFailureDetails.describe(deep);
        assertTrue(details.length() <= 500);
        assertFalse(details.contains("secret"));
    }

    @Test
    void messageAndStringOverridesCannotBeInvokedByDiagnosticProjection() {
        var unsafe = new RuntimeException() {
            @Override
            public String getMessage() {
                throw new AssertionError("must not access");
            }

            @Override
            public String toString() {
                throw new AssertionError("must not stringify");
            }
        };
        assertFalse(SafeFailureDetails.describe(unsafe).isEmpty());
        assertEquals("unknown", SafeFailureDetails.describe(null));
    }
}
