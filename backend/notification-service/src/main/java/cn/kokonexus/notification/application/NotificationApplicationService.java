package cn.kokonexus.notification.application;

import cn.kokonexus.common.api.ResourceNotFoundException;
import cn.kokonexus.notification.domain.NotificationEvent;
import cn.kokonexus.notification.domain.NotificationInbox;
import cn.kokonexus.notification.infrastructure.persistence.NotificationInboxMapper;
import cn.kokonexus.notification.infrastructure.persistence.NotificationPreferenceMapper;
import cn.kokonexus.notification.infrastructure.persistence.NotificationReceiptMapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** notification-service：业务用例；涉及写入时遵守领域事务与权限约束。 */
@Service
public class NotificationApplicationService {

    /** NotificationInboxMapper 持久化映射器，复杂 SQL 使用 XML。 */
    private final NotificationInboxMapper inboxMapper;
    /** NotificationPreferenceMapper 持久化映射器，复杂 SQL 使用 XML。 */
    private final NotificationPreferenceMapper preferenceMapper;
    /** NotificationReceiptMapper 持久化映射器，复杂 SQL 使用 XML。 */
    private final NotificationReceiptMapper receiptMapper;

    public NotificationApplicationService(
        NotificationInboxMapper inboxMapper,
        NotificationPreferenceMapper preferenceMapper,
        NotificationReceiptMapper receiptMapper
    ) {
        this.inboxMapper = inboxMapper;
        this.preferenceMapper = preferenceMapper;
        this.receiptMapper = receiptMapper;
    }

    @Transactional
    public boolean deliver(NotificationEvent event) {
        event.validate();
        if (receiptMapper.claim(event.eventId(), event.recipientId()) == 0) return false;
        if (Boolean.FALSE.equals(preferenceMapper.enabled(event.recipientId(), event.eventType()))) {
            if (receiptMapper.setDisposition(event.eventId(), event.recipientId(), "SUPPRESSED") != 1) {
                throw new IllegalStateException("通知处理记录更新失败");
            }
            return false;
        }
        NotificationInbox notification = new NotificationInbox();
        notification.setId(IdWorker.getId());
        notification.setEventId(event.eventId());
        notification.setRecipientId(event.recipientId());
        notification.setActorId(event.actorId());
        notification.setEventType(event.eventType());
        notification.setResourceId(event.resourceId());
        notification.setSummary(event.summary());
        if (inboxMapper.insertIdempotent(notification) != 1) {
            throw new IllegalStateException("通知事件与收件箱状态不一致");
        }
        if (receiptMapper.setDisposition(event.eventId(), event.recipientId(), "DELIVERED") != 1) {
            throw new IllegalStateException("通知处理记录更新失败");
        }
        return true;
    }

    @Transactional(readOnly = true)
    public NotificationPage page(long userId, int page, int size) {
        requireUser(userId);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 50);
        List<NotificationInbox> items = inboxMapper.selectPageForUser(
            userId,
            (long) (safePage - 1) * safeSize,
            safeSize
        );
        return new NotificationPage(items, safePage, safeSize, inboxMapper.countForUser(userId));
    }

    @Transactional
    public void markRead(long userId, long notificationId) {
        requireUser(userId);
        if (notificationId <= 0) throw new IllegalArgumentException("通知标识无效");
        if (inboxMapper.markRead(userId, notificationId) == 0 && !inboxMapper.existsForUser(userId, notificationId)) {
            throw new ResourceNotFoundException("通知不存在");
        }
    }

    @Transactional(readOnly = true)
    public boolean preference(long userId, String eventType) {
        requireUser(userId);
        requireEventType(eventType);
        return !Boolean.FALSE.equals(preferenceMapper.enabled(userId, eventType));
    }

    @Transactional
    public boolean savePreference(long userId, String eventType, boolean enabled) {
        requireUser(userId);
        requireEventType(eventType);
        preferenceMapper.save(userId, eventType, enabled);
        return enabled;
    }

    private void requireUser(long userId) {
        if (userId <= 0) throw new IllegalArgumentException("用户标识无效");
    }

    private void requireEventType(String eventType) {
        if (!("FOLLOW".equals(eventType) || "COMMENT".equals(eventType) || "LIVE_STARTED".equals(eventType))) {
            throw new IllegalArgumentException("通知类型无效");
        }
    }

    /** notification-service：NotificationPage 领域类型；字段单位、状态及可空性见各属性说明。 */
    public record NotificationPage(
        @io.swagger.v3.oas.annotations.media.Schema(description = "当前页的业务投影列表") List<NotificationInbox> items,
        @io.swagger.v3.oas.annotations.media.Schema(description = "页码，从 1 开始") int page,
        @io.swagger.v3.oas.annotations.media.Schema(description = "单页条数，受接口最大值限制") int size,
        @io.swagger.v3.oas.annotations.media.Schema(description = "匹配条件的总条数") long total
    ) {}
}
