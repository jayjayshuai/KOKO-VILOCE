package cn.kokonexus.api.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReplayCommandBindingTest {

    /** 不指向真实用户或事件的固定协议夹具。 */
    private static final String REQUEST = "ABCDEFAB-1234-1234-1234-123456789ABC";
    /** 不指向真实用户或事件的固定协议夹具。 */
    private static final String EVENT = "abcdefab-1234-1234-1234-123456789abc";
    /** 长度合法且不包含生产工单信息。 */
    private static final String REASON = "仅用于命令绑定隔离协议验收";

    @Test
    void canonicalizationMatchesBusinessCommand() {
        var command = new OutboxReplayCommand(REQUEST, EVENT, 2, "  " + REASON + "  ");
        var normalized = ReplayCommandBinding.normalize("identity", command);
        assertEquals(REQUEST.toLowerCase(java.util.Locale.ROOT), normalized.requestId());
        assertEquals(REASON, normalized.reason());
        assertEquals(
            ReplayCommandBinding.fingerprint(10, "identity", normalized),
            ReplayCommandBinding.fingerprint(10, "identity", command)
        );
    }

    @Test
    void everyBoundFieldChangesFingerprint() {
        var command = command();
        String original = ReplayCommandBinding.fingerprint(10, "identity", command);
        assertNotEquals(original, ReplayCommandBinding.fingerprint(11, "identity", command));
        assertNotEquals(original, ReplayCommandBinding.fingerprint(10, "community", command));
        for (var changed : List.of(
            new OutboxReplayCommand("11111111-1111-1111-1111-111111111111", EVENT, 2, REASON),
            new OutboxReplayCommand(REQUEST, "22222222-2222-2222-2222-222222222222", 2, REASON),
            new OutboxReplayCommand(REQUEST, EVENT, 3, REASON),
            new OutboxReplayCommand(REQUEST, EVENT, 2, REASON + "修改")
        )) {
            assertNotEquals(original, ReplayCommandBinding.fingerprint(10, "identity", changed));
        }
    }

    @Test
    void invalidDomainIdentityAndCommandsAreRejected() {
        for (String domain : List.of("IDENTITY", "identity;DROP TABLE", "", "../../live")) {
            assertThrows(IllegalArgumentException.class, () -> ReplayCommandBinding.normalize(domain, command()));
        }
        assertThrows(IllegalArgumentException.class, () -> ReplayCommandBinding.normalize(null, command()));
        assertThrows(IllegalArgumentException.class, () -> ReplayCommandBinding.normalize("identity", null));
        assertThrows(IllegalArgumentException.class, () -> ReplayCommandBinding.fingerprint(0, "identity", command()));
        for (var invalid : List.of(
            new OutboxReplayCommand(REQUEST, EVENT, -1, REASON),
            new OutboxReplayCommand("1-1-1-1-1", EVENT, 2, REASON),
            new OutboxReplayCommand(REQUEST, null, 2, REASON),
            new OutboxReplayCommand(REQUEST, EVENT, 2, null),
            new OutboxReplayCommand(REQUEST, EVENT, 2, "短原因"),
            new OutboxReplayCommand(REQUEST, EVENT, 2, "长".repeat(501))
        )) {
            assertThrows(IllegalArgumentException.class, () -> ReplayCommandBinding.normalize("identity", invalid));
        }
    }

    @Test
    void secretResponseDoesNotLeakInDiagnosticString() {
        var confirmation = new ReplayConfirmation("fixture-secret-not-production", LocalDateTime.now());
        assertFalse(confirmation.toString().contains(confirmation.confirmationToken()));
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ReplayCommandBinding.sha256("abc")
        );
    }

    private static OutboxReplayCommand command() {
        return new OutboxReplayCommand(REQUEST, EVENT, 2, REASON);
    }
}
