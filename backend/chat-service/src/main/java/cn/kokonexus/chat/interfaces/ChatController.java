package cn.kokonexus.chat.interfaces;

import cn.kokonexus.chat.application.ChatDirectory;
import cn.kokonexus.chat.application.ChatService;
import cn.kokonexus.chat.interfaces.ChatViews.ConversationView;
import cn.kokonexus.chat.interfaces.ChatViews.MessageView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 聊天 HTTP 用例边界；身份由可信网关注入，不接受请求正文里的 senderId。 */
@RestController
@RequestMapping("/api/chat")
@Tag(name = "实时私信与群聊")
@RequiredArgsConstructor
public class ChatController {

    /** 持久化用例，事务由 Spring 代理执行。 */
    private final ChatService service;
    /** RPC 身份目录。 */
    private final ChatDirectory directory;

    @PostMapping("/direct")
    @Operation(summary = "建立或读取私信", description = "按用户名精确查找；用户对唯一，重复请求返回同一会话。")
    public ConversationView direct(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @Valid @RequestBody HandleRequest request
    ) {
        var peer = directory.byHandle(request.handle());
        var owner = directory.byId(userId);
        cn.kokonexus.chat.domain.Conversation c;
        try {
            c = service.create(owner, List.of(peer), peer.displayName(), false);
        } catch (DuplicateKeyException conflict) {
            c = service.findDirect(ChatService.directKey(userId, Long.parseLong(peer.id())));
            if (c == null) throw conflict;
        }
        return ConversationView.from(c, service.memberList(userId, c.getId()), userId);
    }

    @PostMapping("/groups")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "创建群聊", description = "总人数 2～50，创建者为群主；用户名必须对应可用账号。")
    public ConversationView group(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @Valid @RequestBody GroupRequest request
    ) {
        var peers = request.handles().stream().map(directory::byHandle).toList();
        var c = service.create(directory.byId(userId), peers, request.title(), true);
        return ConversationView.from(c, service.memberList(userId, c.getId()), userId);
    }

    @GetMapping("/conversations")
    @Operation(summary = "游标分页读取本人会话", description = "按 UUID 升序；最大 100 条，成员移除后会话不再返回。")
    public List<ConversationView> list(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @RequestParam(required = false) String after,
        @RequestParam(defaultValue = "50") int size
    ) {
        // 每个会话重新校验成员；并发移除的会话不泄露内容。
        var views = new java.util.ArrayList<ConversationView>();
        for (var c : service.list(userId, after, size)) {
            try {
                views.add(ConversationView.from(c, service.memberList(userId, c.getId()), userId));
            } catch (cn.kokonexus.common.api.ResourceNotFoundException removed) {
                /* 已移除/解散的快照不返回。 */
            }
        }
        return views;
    }

    @GetMapping("/conversations/{id}/messages")
    @Operation(
        summary = "分页读取或补拉消息",
        description = "before 用于旧历史，after 用于重连补拉，不能同时指定；最多 100 条，升序返回，入群前历史不可读。"
    )
    public List<MessageView> history(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable String id,
        @RequestParam(required = false) Long before,
        @RequestParam(required = false) Long after,
        @RequestParam(defaultValue = "50") int size
    ) {
        return service.history(userId, id, before, after, size).stream().map(MessageView::from).toList();
    }

    @PostMapping("/conversations/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "前移本人已读游标", description = "旧序号不回退；不接受超过会话最后消息的序号。")
    public void read(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable String id,
        @RequestBody ReadRequest request
    ) {
        service.read(userId, id, request.seq());
    }

    @PostMapping("/conversations/{id}/members")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "群主添加成员", description = "新成员只能读取加入后的消息，最多 50 人。")
    public void add(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable String id,
        @Valid @RequestBody HandleRequest request
    ) {
        service.add(userId, id, directory.byHandle(request.handle()));
    }

    @DeleteMapping("/conversations/{id}/members/{targetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        summary = "移除成员或退出群聊",
        description = "群主可移除他人，普通成员只能退出；群主须通过解散接口关闭群。"
    )
    public void remove(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable String id,
        @PathVariable long targetId
    ) {
        service.remove(userId, id, targetId);
    }

    @PatchMapping("/conversations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "群主修改群名")
    public void rename(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable String id,
        @Valid @RequestBody TitleRequest request
    ) {
        service.rename(userId, id, request.title());
    }

    @DeleteMapping("/conversations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "群主解散群聊", description = "软关闭会话，停止所有成员的消息读写；记录保留用于审计。")
    public void close(@Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId, @PathVariable String id) {
        service.close(userId, id);
    }

    /** chat-service：请求契约；字段校验以公开接口约束为准。 */
    public record HandleRequest(
        @Schema(description = "精确公开用户名，不使用邮箱") @NotBlank @Size(min = 3, max = 32) String handle
    ) {}

    /** chat-service：请求契约；字段校验以公开接口约束为准。 */
    public record GroupRequest(
        @Schema(description = "群名称，最多 80 字符") @NotBlank @Size(max = 80) String title,
        @Schema(description = "其他成员用户名，1～49 人，不包含自己")
        @jakarta.validation.constraints.NotNull
        @Size(min = 1, max = 49)
        List<@NotBlank @Size(min = 3, max = 32) String> handles
    ) {}

    /** chat-service：请求契约；字段校验以公开接口约束为准。 */
    public record ReadRequest(@Schema(description = "最后已阅读序号，不得超过会话最后消息") long seq) {}

    /** chat-service：请求契约；字段校验以公开接口约束为准。 */
    public record TitleRequest(
        @Schema(description = "新群名称，最多 80 字符") @NotBlank @Size(max = 80) String title
    ) {}
}
