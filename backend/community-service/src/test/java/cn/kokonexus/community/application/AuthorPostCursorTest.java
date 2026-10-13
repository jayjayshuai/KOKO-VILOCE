package cn.kokonexus.community.application;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class AuthorPostCursorTest {

    @Test
    void preservesMicrosecondsAndPositiveLongBoundaries() {
        var value = new AuthorPostCursor(
            Long.MAX_VALUE,
            LocalDateTime.of(2026, 10, 13, 22, 1, 2, 123456000),
            Long.MAX_VALUE
        );
        assertThat(AuthorPostCursor.decode(value.encode(), Long.MAX_VALUE)).isEqualTo(value);
        assertThat(AuthorPostCursor.decode(null, 12)).isNull();
    }

    @Test
    void rejectsForeignOwnerInvalidCalendarAndNoncanonicalOrOverlongInputs() {
        for (var value : new String[] {
            "",
            "=",
            "a".repeat(161),
            encode("13|2026-10-13T22:00:00.000000|1"),
            encode("12|2026-02-30T22:00:00.000000|1"),
            encode("12|0000-01-01T00:00:00.000000|1"),
            encode("12|2026-10-13T22:00:00.123456789|1"),
            encode("012|2026-10-13T22:00:00.000000|1"),
            encode("12|2026-10-13T22:00:00.000000|9223372036854775808"),
            encode("12|2026-10-13T22:00:00.000000|0"),
            encode("12|2026-10-13T22:00:00.000000|1|extra"),
        })
            assertThatThrownBy(() -> AuthorPostCursor.decode(value, 12))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("作品游标无效或不属于当前作者");
    }

    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.US_ASCII));
    }
}
