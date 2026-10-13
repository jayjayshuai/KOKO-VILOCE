package cn.kokonexus.community.application;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Base64;

/** 公开作品游标只定位读取，不赋予权限；绑定作者，保留数据库微秒精度和同时间ID顺序。 */
public record AuthorPostCursor(
    /** 游标原作者；禁止误用于另一个主页。 */ long ownerId,
    /** 已确认边界的发布时间，Asia/Shanghai。 */ LocalDateTime publishedAt,
    /** 边界文章ID，同时间按ID降序继续。 */ long postId
) {
    /** 固定六位小数，与DATETIME(6)一致，不接受时区/日期归一化。 */
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern(
        "uuuu-MM-dd'T'HH:mm:ss.SSSSSS"
    ).withResolverStyle(ResolverStyle.STRICT);

    public String encode() {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                (ownerId + "|" + FORMAT.format(publishedAt) + "|" + postId).getBytes(StandardCharsets.US_ASCII)
            );
    }

    /** 解析不信任客户端内容；非法/跨作者游标在数据库查询前拒绝。 */
    public static AuthorPostCursor decode(String value, long ownerId) {
        if (value == null) return null;
        try {
            if (!value.matches("[A-Za-z0-9_-]{1,160}")) throw new IllegalArgumentException();
            String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII).split(
                "\\|",
                -1
            );
            if (
                parts.length != 3 || !parts[0].matches("[1-9][0-9]{0,18}") || !parts[2].matches("[1-9][0-9]{0,18}")
            ) throw new IllegalArgumentException();
            var cursor = new AuthorPostCursor(
                Long.parseLong(parts[0]),
                LocalDateTime.parse(parts[1], FORMAT),
                Long.parseLong(parts[2])
            );
            if (
                cursor.ownerId != ownerId ||
                cursor.publishedAt.getYear() < 1000 ||
                cursor.publishedAt.getYear() > 9999 ||
                !cursor.encode().equals(value)
            ) throw new IllegalArgumentException();
            return cursor;
        } catch (IllegalArgumentException | java.time.DateTimeException exception) {
            throw new IllegalArgumentException("作品游标无效或不属于当前作者");
        }
    }
}
