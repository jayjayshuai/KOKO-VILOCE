package cn.kokonexus.community.interfaces;

import cn.kokonexus.community.application.CommunityMembershipService;
import cn.kokonexus.community.domain.CommunityMember;
import cn.kokonexus.community.infrastructure.CommunityIdentityDirectory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 社区成员 HTTP 边界：只接受网关身份，成员投影不包含邮箱或内部凭据。 */
@RestController
@RequestMapping("/api/communities")
@RequiredArgsConstructor
@Tag(name = "社区成员与入会")
public class CommunityMembershipController {

    /** 当前成员授权和同库事务。 */
    private final CommunityMembershipService service;
    /** 入会前读取公开身份，RPC 不持有社区数据库锁。 */
    private final CommunityIdentityDirectory directory;

    @GetMapping("/{id}/membership")
    @Operation(summary = "查询本人入会状态", description = "私密社区仅当前成员可读；归档社区不可访问。")
    public StatusView status(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable long id
    ) {
        var status = service.status(userId, id);
        return new StatusView(CommunityController.CommunityView.from(status.community()), status.role());
    }

    @PutMapping("/{id}/membership")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "加入公开社区", description = "最多 1000 人；重复入会幂等，私密社区不允许自行加入。")
    public void join(@Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId, @PathVariable long id) {
        service.join(userId, id, directory.byId(userId));
    }

    @DeleteMapping("/{id}/membership")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        summary = "退出本人社区关系",
        description = "重复退出无副作用；所有者不能退出活跃社区，归档后不修改关系。"
    )
    public void leave(@Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId, @PathVariable long id) {
        service.leave(userId, id);
    }

    @GetMapping("/joined")
    @Operation(
        summary = "分页查询本人的已加入社区",
        description = "ACTIVE 社区，ID 倒序游标；包含所有者关系，最多 50 条。"
    )
    public CommunitySlice joined(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @RequestParam(required = false) Long before,
        @RequestParam(defaultValue = "20") int size
    ) {
        var found = service.joined(userId, before, size);
        var page = found.subList(0, Math.min(found.size(), size));
        return new CommunitySlice(
            page.stream().map(CommunityController.CommunityView::from).toList(),
            found.size() > size ? Long.toString(page.getLast().getId()) : null
        );
    }

    @GetMapping("/{id}/members")
    @Operation(summary = "成员游标分页", description = "仅当前成员可读；名称为入会快照，旧记录可能无名称，最多 50 条。")
    public MemberSlice members(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable long id,
        @RequestParam(required = false) Long before,
        @RequestParam(defaultValue = "20") int size
    ) {
        var found = service.memberPage(userId, id, before, size);
        var page = found.subList(0, Math.min(found.size(), size));
        return new MemberSlice(
            page.stream().map(MemberView::from).toList(),
            found.size() > size ? Long.toString(page.getLast().getUserId()) : null
        );
    }

    @DeleteMapping("/{id}/members/{targetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        summary = "所有者移除社区成员",
        description = "不能移除所有者，重复移除幂等；不是永久封禁，公开社区可重新加入。"
    )
    public void remove(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable long id,
        @PathVariable long targetId
    ) {
        service.remove(userId, id, targetId);
    }

    /** 本人入会状态，不携带内部所有者字段或账户隐私。 */
    public record StatusView(
        @Schema(description = "当前授权可见的社区元信息") CommunityController.CommunityView community,
        @Schema(description = "本人角色 OWNER/MEMBER；未加入为 null") String role
    ) {}

    /** 当前授权可见成员的公开入会快照。 */
    public record MemberView(
        @Schema(description = "成员用户 ID，雪花 ID 用字符串") String userId,
        @Schema(description = "OWNER 或 MEMBER") String role,
        @Schema(description = "服务端入会时间 Asia/Shanghai") LocalDateTime joinedAt,
        @Schema(description = "入会时公开用户名快照；历史记录可空") String handle,
        @Schema(description = "入会时公开显示名称快照；历史记录可空") String displayName
    ) {
        static MemberView from(CommunityMember member) {
            return new MemberView(
                Long.toString(member.getUserId()),
                member.getRole(),
                member.getJoinedAt(),
                member.getHandle(),
                member.getDisplayName()
            );
        }
    }

    /** 有上限的成员游标页。 */
    public record MemberSlice(
        @Schema(description = "本批当前成员安全投影") List<MemberView> items,
        @Schema(description = "下一批独占用户 ID 上界；无更多为 null") String nextBefore
    ) {}

    /** 本人 ACTIVE 社区游标页。 */
    public record CommunitySlice(
        @Schema(description = "本批本人已加入的 ACTIVE 社区") List<CommunityController.CommunityView> items,
        @Schema(description = "下一批独占社区 ID 上界；无更多为 null") String nextBefore
    ) {}
}
