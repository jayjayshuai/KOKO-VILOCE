package cn.kokonexus.common.diagnostics;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** 保存有界异常类型/因果链与代码位置，不复制可能包含SQL、密钥或业务正文的异常消息。 */
public final class SafeFailureDetails {

    /** 与Outbox last_error字段预算一致，不依赖日志后端截断。 */
    private static final int MAX_LENGTH = 500;
    /** 循环cause或超深链有界，不能在调度线程无限遍历。 */
    private static final int MAX_DEPTH = 8;

    private SafeFailureDetails() {}

    /** 不调用getMessage/toString；每个cause保留类型和首帧位置，最多500字符。 */
    public static String describe(Throwable failure) {
        if (failure == null) return "unknown";
        StringBuilder result = new StringBuilder();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < MAX_DEPTH && visited.add(current)) {
            if (!result.isEmpty()) result.append(" <- ");
            result.append(current.getClass().getName());
            StackTraceElement[] frames = current.getStackTrace();
            if (frames.length > 0) {
                var frame = frames[0];
                result
                    .append('@')
                    .append(frame.getClassName())
                    .append('.')
                    .append(frame.getMethodName())
                    .append(':')
                    .append(frame.getLineNumber());
            }
            current = current.getCause();
            if (result.length() >= MAX_LENGTH) break;
        }
        if (current != null) result.append(" [bounded]");
        return result.substring(0, Math.min(result.length(), MAX_LENGTH));
    }
}
