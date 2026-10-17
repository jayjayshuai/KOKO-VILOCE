package cn.kokonexus.asset.interfaces;

/** 只支持单字节区间，拒绝溢出、多Range及越界；鉴权必须先于此解析以免泄露资源长度。 */
public record VideoByteRange(
    /** 起始字节，包含。 */ long start,
    /** 结束字节，包含。 */ long end,
    /** 是否实际处理Range请求。 */ boolean partial
) {
    public long length() {
        return end - start + 1;
    }

    public static VideoByteRange parse(String header, long size) {
        if (size <= 0) throw new IllegalArgumentException("视频长度无效");
        if (header == null) return new VideoByteRange(0, size - 1, false);
        if (!header.matches("bytes=([0-9]{1,19}-[0-9]{0,19}|-[0-9]{1,19})")) throw new IllegalArgumentException(
            "视频范围无效"
        );
        try {
            String[] values = header.substring(6).split("-", -1);
            long start, end;
            if (values[0].isEmpty()) {
                long suffix = Long.parseLong(values[1]);
                if (suffix <= 0) throw new IllegalArgumentException();
                start = Math.max(0, size - suffix);
                end = size - 1;
            } else {
                start = Long.parseLong(values[0]);
                end = values[1].isEmpty() ? size - 1 : Math.min(Long.parseLong(values[1]), size - 1);
            }
            if (start >= size || end < start) throw new IllegalArgumentException();
            return new VideoByteRange(start, end, true);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("视频范围无效");
        }
    }
}
