package cn.kokonexus.chat.application;

import cn.kokonexus.api.identity.ChatIdentity;
import cn.kokonexus.chat.domain.ChatBlock;
import cn.kokonexus.chat.domain.ChatReport;
import cn.kokonexus.chat.domain.ChatReportReview;
import cn.kokonexus.chat.domain.Member;
import cn.kokonexus.chat.persistence.BlockMapper;
import cn.kokonexus.chat.persistence.ConversationMapper;
import cn.kokonexus.chat.persistence.MemberMapper;
import cn.kokonexus.chat.persistence.MessageMapper;
import cn.kokonexus.chat.persistence.ReportMapper;
import cn.kokonexus.chat.persistence.ReviewMapper;
import cn.kokonexus.chat.persistence.SafetyLockMapper;
import cn.kokonexus.common.api.ForbiddenOperationException;
import cn.kokonexus.common.api.ResourceNotFoundException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 反骚扰事实与审核事务；不缓存权限，不将结案伪装成账号封禁。 */
@Service
@RequiredArgsConstructor
public class ChatSafetyService {

    /** 允许的举报原因，不能由客户端增加类型。 */
    private static final Set<String> REASONS = Set.of("HARASSMENT", "SPAM", "THREAT", "OTHER");
    /** 允许的最终决定，状态不可回退。 */
    private static final Set<String> DECISIONS = Set.of("RESOLVED", "REJECTED");
    /** 每人最大拉黑关系数，用户级锁保护并发计数。 */
    private static final int BLOCK_LIMIT = 1000;
    /** 每人滚动 24 小时最大新举报数，重试不占配额。 */
    private static final int REPORT_LIMIT = 20;
    /** 关键事务锁和状态条件更新。 */
    private final SafetyLockMapper locks;
    /** 拉黑事实，所有公开读取均包含主动用户身份。 */
    private final BlockMapper blocks;
    /** 举报事实及证据，不能直接作为 HTTP 响应。 */
    private final ReportMapper reports;
    /** 只追加的最终审核审计。 */
    private final ReviewMapper reviews;
    /** 会话锁及有效状态。 */
    private final ConversationMapper conversations;
    /** 当前成员和历史边界。 */
    private final MemberMapper members;
    /** 真实消息与证据来源。 */
    private final MessageMapper messages;

    /** 与私信发送处于同一事务，锁一直保持到消息提交；RR 下必须当前读。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireContactAllowed(long first, long second) {
        lockPair(first, second);
        Integer blocked = locks.blockedPair(Math.min(first, second), Math.max(first, second));
        if (blocked == null) {
            throw new IllegalStateException("联系授权状态不可用");
        }
        if (blocked != 0) {
            throw new ForbiddenOperationException("当前用户关系不允许新私信或邀请");
        }
    }

    /** 先锁用户配额再锁用户对；重复拉黑保持原 UUID 和创建时间。 */
    @Transactional
    public ChatBlock block(long ownerId, ChatIdentity target) {
        long targetId = Long.parseLong(target.id());
        validatePair(ownerId, targetId);
        locks.lockActor(ownerId);
        lockPair(ownerId, targetId);
        ChatBlock existing = blocks.selectOne(
            Wrappers.<ChatBlock>lambdaQuery().eq(ChatBlock::getOwnerId, ownerId).eq(ChatBlock::getTargetId, targetId)
        );
        if (existing != null) {
            requireOne(locks.setBlocking(Math.min(ownerId, targetId), Math.max(ownerId, targetId), ownerId, true));
            return existing;
        }
        if (blocks.selectCount(Wrappers.<ChatBlock>lambdaQuery().eq(ChatBlock::getOwnerId, ownerId)) >= BLOCK_LIMIT) {
            throw new IllegalStateException("拉黑列表已达 1000 人上限");
        }
        ChatBlock block = new ChatBlock();
        block.setId(UUID.randomUUID().toString());
        block.setOwnerId(ownerId);
        block.setTargetId(targetId);
        block.setTargetHandle(target.handle());
        block.setTargetName(target.displayName());
        block.setCreatedAt(LocalDateTime.now());
        requireOne(blocks.insert(block));
        requireOne(locks.setBlocking(Math.min(ownerId, targetId), Math.max(ownerId, targetId), ownerId, true));
        return block;
    }

    /** 只能解除自己的设置；对方拉黑仍然有效，重复解除也成功。 */
    @Transactional
    public void unblock(long ownerId, long targetId) {
        validatePair(ownerId, targetId);
        locks.lockActor(ownerId);
        // 不为不存在的拉黑关系创建用户对行，避免任意目标 ID 的幂等解除变成无限存储分配。
        ChatBlock existing = blocks.selectOne(
            Wrappers.<ChatBlock>lambdaQuery().eq(ChatBlock::getOwnerId, ownerId).eq(ChatBlock::getTargetId, targetId)
        );
        if (existing == null) {
            return;
        }
        lockPair(ownerId, targetId);
        requireOne(blocks.deleteById(existing.getId()));
        requireOne(locks.setBlocking(Math.min(ownerId, targetId), Math.max(ownerId, targetId), ownerId, false));
    }

    /** 分页只返回本人的主动设置，不透露对方是否拉黑自己。 */
    @Transactional(readOnly = true)
    public List<ChatBlock> blocked(long ownerId, String after, int size) {
        page(after, size);
        var query = Wrappers.<ChatBlock>lambdaQuery()
            .eq(ChatBlock::getOwnerId, ownerId)
            .orderByAsc(ChatBlock::getId)
            .last("LIMIT " + size);
        if (after != null) {
            query.gt(ChatBlock::getId, after);
        }
        return blocks.selectList(query);
    }

    /** 举报证据在当前成员授权和会话锁下取自真实消息，不接受客户端传入正文快照。 */
    @Transactional
    public ChatReport report(long reporterId, String conversationId, String messageId, String reason, String detail) {
        ChatService.uuid(conversationId);
        ChatService.uuid(messageId);
        if (reason == null || !REASONS.contains(reason)) {
            throw new IllegalArgumentException("举报原因无效");
        }
        detail = text(detail, "举报说明");
        locks.lockActor(reporterId);
        var conversation = conversations.lock(conversationId);
        var member = members.selectOne(
            Wrappers.<Member>lambdaQuery()
                .eq(Member::getConversationId, conversationId)
                .eq(Member::getUserId, reporterId)
        );
        var message = messages.selectById(messageId);
        if (
            conversation == null ||
            !"ACTIVE".equals(conversation.getStatus()) ||
            member == null ||
            message == null ||
            !conversationId.equals(message.getConversationId()) ||
            message.getSeq() <= member.getJoinedSeq()
        ) {
            throw new ResourceNotFoundException("消息不存在或无举报权限");
        }
        if (message.getSenderId() == reporterId) {
            throw new IllegalArgumentException("不能举报自己的消息");
        }
        ChatReport existing = reports.selectOne(
            Wrappers.<ChatReport>lambdaQuery()
                .eq(ChatReport::getReporterId, reporterId)
                .eq(ChatReport::getMessageId, messageId)
        );
        if (existing != null) {
            if (!existing.getReason().equals(reason) || !existing.getDetail().equals(detail)) {
                throw new IllegalStateException("同一消息已举报，重复提交不能改变原因或说明");
            }
            return existing;
        }
        if (
            reports.selectCount(
                Wrappers.<ChatReport>lambdaQuery()
                    .eq(ChatReport::getReporterId, reporterId)
                    .ge(ChatReport::getCreatedAt, LocalDateTime.now().minusHours(24))
            ) >= REPORT_LIMIT
        ) {
            throw new IllegalStateException("24 小时内最多提交 20 份新举报");
        }
        ChatReport report = new ChatReport();
        report.setId(UUID.randomUUID().toString());
        report.setReporterId(reporterId);
        report.setMessageId(messageId);
        report.setConversationId(conversationId);
        report.setReportedUserId(message.getSenderId());
        report.setReason(reason);
        report.setDetail(detail);
        report.setEvidenceBody(message.getBody());
        report.setStatus("PENDING");
        report.setVersion(0L);
        report.setCreatedAt(LocalDateTime.now());
        requireOne(reports.insert(report));
        return report;
    }

    /** 举报进度只允许举报本人读取，离群后仍可查结论，但不返回证据正文。 */
    @Transactional(readOnly = true)
    public ChatReport mine(long reporterId, String id) {
        ChatService.uuid(id);
        var report = reports.selectOne(
            Wrappers.<ChatReport>lambdaQuery().eq(ChatReport::getId, id).eq(ChatReport::getReporterId, reporterId)
        );
        if (report == null) {
            throw new ResourceNotFoundException("举报不存在或无访问权限");
        }
        return report;
    }

    /** 本人举报使用 UUID 游标；审核队列使用独立有权限的入口。 */
    @Transactional(readOnly = true)
    public List<ChatReport> mine(long reporterId, String after, int size) {
        page(after, size);
        var query = Wrappers.<ChatReport>lambdaQuery()
            .eq(ChatReport::getReporterId, reporterId)
            .orderByAsc(ChatReport::getId)
            .last("LIMIT " + size);
        if (after != null) {
            query.gt(ChatReport::getId, after);
        }
        return reports.selectList(query);
    }

    /** 仅供已由控制器校验 Sa-Token 审核权限的调用者使用，不在此方法伪造身份。 */
    @Transactional(readOnly = true)
    public List<ChatReport> queue(String status, String after, int size) {
        page(after, size);
        if (!"PENDING".equals(status) && !DECISIONS.contains(status)) {
            throw new IllegalArgumentException("举报状态无效");
        }
        var query = Wrappers.<ChatReport>lambdaQuery()
            .eq(ChatReport::getStatus, status)
            .orderByAsc(ChatReport::getId)
            .last("LIMIT " + size);
        if (after != null) {
            query.gt(ChatReport::getId, after);
        }
        return reports.selectList(query);
    }

    /** 最终结论和审核审计同事务；原决定重试幂等，旧版本或改写已结案事实拒绝。 */
    @Transactional
    public ChatReport review(long reviewerId, String id, long version, String decision, String note) {
        ChatService.uuid(id);
        if (reviewerId < 1 || version < 0 || decision == null || !DECISIONS.contains(decision)) {
            throw new IllegalArgumentException("审核决定或版本无效");
        }
        note = text(note, "审核说明");
        ChatReport report = locks.lockReport(id);
        if (report == null) {
            throw new ResourceNotFoundException("举报不存在");
        }
        if (report.getReporterId() == reviewerId || report.getReportedUserId() == reviewerId) {
            throw new ForbiddenOperationException("不能审核自己提交或涉及自己的举报");
        }
        if (!"PENDING".equals(report.getStatus())) {
            var prior = reviews.selectOne(
                Wrappers.<ChatReportReview>lambdaQuery().eq(ChatReportReview::getReportId, id)
            );
            if (
                prior != null &&
                prior.getReviewerId() == reviewerId &&
                prior.getDecision().equals(decision) &&
                prior.getNote().equals(note)
            ) {
                return report;
            }
            throw new IllegalStateException("举报已结案，不能覆盖其他审核决定");
        }
        LocalDateTime now = LocalDateTime.now();
        if (locks.review(id, version, decision, note, now) != 1) {
            throw new IllegalStateException("举报版本已变化，请刷新后再审核");
        }
        ChatReportReview audit = new ChatReportReview();
        audit.setId(UUID.randomUUID().toString());
        audit.setReportId(id);
        audit.setReviewerId(reviewerId);
        audit.setDecision(decision);
        audit.setNote(note);
        audit.setCreatedAt(now);
        requireOne(reviews.insert(audit));
        report.setStatus(decision);
        report.setVersion(Math.addExact(report.getVersion(), 1));
        report.setReviewNote(note);
        report.setReviewedAt(now);
        return report;
    }

    private void lockPair(long first, long second) {
        validatePair(first, second);
        locks.lockPair(Math.min(first, second), Math.max(first, second));
    }

    private static void validatePair(long first, long second) {
        if (first < 1 || second < 1 || first == second) {
            throw new IllegalArgumentException("目标用户无效或为自己");
        }
    }

    private static String text(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 500 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(label + "必须为 1～500 字符纯文本");
        }
        return value.trim();
    }

    private static void page(String after, int size) {
        if (after != null) {
            ChatService.uuid(after);
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("分页上限为 100");
        }
    }

    private static void requireOne(int affected) {
        if (affected != 1) {
            throw new IllegalStateException("聊天安全事实写入失败");
        }
    }
}
