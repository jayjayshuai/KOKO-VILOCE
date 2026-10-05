package cn.kokonexus.api.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 实际编码动作隔离和输入边界，不使用替代摘要函数。 */
class BindingReleaseCommandBindingTest {

    private static final String ID = "81000000-0000-4000-8000-000000000001",
        TARGET = "81000000-0000-4000-8000-000000000002";
    private static final String REASON = "工单内容和原始根因已明确处理";
    private static final BindingReleaseReplayCommand COMMAND = new BindingReleaseReplayCommand(ID, TARGET, 0, REASON);

    @Test
    void eachBoundFieldAndActionChangesActualFingerprint() {
        var value = BindingReleaseCommandBinding.fingerprint(10, "identity", COMMAND);
        assertTrue(value.matches("[0-9a-f]{64}"));
        assertNotEquals(value, BindingReleaseCommandBinding.fingerprint(11, "identity", COMMAND));
        assertNotEquals(value, BindingReleaseCommandBinding.fingerprint(10, "community", COMMAND));
        assertNotEquals(
            value,
            ReplayCommandBinding.fingerprint(10, "identity", new OutboxReplayCommand(ID, TARGET, 0, REASON))
        );
        for (var change : List.of(
            new BindingReleaseReplayCommand(TARGET, TARGET, 0, REASON),
            new BindingReleaseReplayCommand(ID, ID, 0, REASON),
            new BindingReleaseReplayCommand(ID, TARGET, 1, REASON),
            new BindingReleaseReplayCommand(ID, TARGET, 0, REASON + "不同")
        ))
            assertNotEquals(value, BindingReleaseCommandBinding.fingerprint(10, "identity", change));
    }

    @Test
    void canonicalInputAndNoReasonInDiagnosticString() {
        var normalized = BindingReleaseCommandBinding.normalize(
            "identity",
            new BindingReleaseReplayCommand(ID.toUpperCase(), TARGET, 0, " " + REASON + " ")
        );
        assertEquals(COMMAND, normalized);
        assertFalse(COMMAND.toString().contains(REASON));
    }

    @Test
    void invalidDomainsIdentifiersGenerationsAndUnicodeMinimumFailBeforeCredentialUse() {
        assertThrows(IllegalArgumentException.class, () -> BindingReleaseCommandBinding.normalize("live", COMMAND));
        for (var value : List.of(
            new BindingReleaseReplayCommand("1-1-1-1-1", TARGET, 0, REASON),
            new BindingReleaseReplayCommand(ID, TARGET, 11, REASON),
            new BindingReleaseReplayCommand(ID, TARGET, -1, REASON),
            new BindingReleaseReplayCommand(ID, TARGET, 0, "😀😀😀😀😀")
        ))
            assertThrows(IllegalArgumentException.class, () ->
                BindingReleaseCommandBinding.normalize("identity", value)
            );
    }
}
