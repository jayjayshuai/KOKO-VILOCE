package cn.kokonexus.chat.interfaces;

import cn.kokonexus.chat.application.ChatArchiveService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 历史工具 HTTP 边界，只接受网关认证身份，不允许通过收藏延长消息阅读权限。 */
@RestController
@RequestMapping("/api/chat/conversations/{id}")
@Tag(name = "会话历史检索与个人收藏")
@RequiredArgsConstructor
public class ChatArchiveController {

    /** 同库历史权限和收藏事务。 */
    private final ChatArchiveService archive;

    @GetMapping("/search")
    @Operation(
        summary = "检索本会话可读消息",
        description = "区分大小写的字面包含；2～64 字符，每批扫描最多 2000 个序号，最多 50 条。空结果仍可能有下一批。"
    )
    public Slice search(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable String id,
        @RequestParam String query,
        @RequestParam(required = false) Long before,
        @RequestParam(defaultValue = "20") int size
    ) {
        return Slice.from(archive.search(userId, id, query, before, size));
    }

    @GetMapping("/bookmarks")
    @Operation(
        summary = "分页读取本人在本会话的收藏",
        description = "只有当前可读消息，倒序游标，最多 50 条；不返回其他人的收藏设置。"
    )
    public Slice bookmarked(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable String id,
        @RequestParam(required = false) Long before,
        @RequestParam(defaultValue = "20") int size
    ) {
        return Slice.from(archive.bookmarked(userId, id, before, size));
    }

    @PutMapping("/bookmarks/{messageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        summary = "收藏当前可读的真实消息",
        description = "只保存本人引用，每会话最多 1000 条；重复收藏幂等，不复制正文。"
    )
    public void save(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable String id,
        @PathVariable String messageId
    ) {
        archive.save(userId, id, messageId);
    }

    @DeleteMapping("/bookmarks/{messageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "取消本人的消息收藏", description = "未收藏也成功，不改变其他成员设置或原消息。")
    public void remove(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable String id,
        @PathVariable String messageId
    ) {
        archive.remove(userId, id, messageId);
    }

    @DeleteMapping("/bookmarks")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        summary = "清空本会话的本人收藏",
        description = "只删除个人引用，包含重加入后不可读的旧引用，不删除消息。"
    )
    public void clear(@Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId, @PathVariable String id) {
        archive.clear(userId, id);
    }

    /** 以明确游标告知扫描是否结束，不要求无界总数统计。 */
    public record Slice(
        @Schema(description = "本批可读真实消息，按序号倒序") List<ChatViews.MessageView> items,
        @Schema(description = "下批独占序号上界，无后续为 null；空批次也可能存在") Long nextBefore
    ) {
        static Slice from(ChatArchiveService.MessageSlice slice) {
            return new Slice(slice.items().stream().map(ChatViews.MessageView::from).toList(), slice.nextBefore());
        }
    }
}
