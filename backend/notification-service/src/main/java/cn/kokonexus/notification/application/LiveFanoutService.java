package cn.kokonexus.notification.application;

import cn.kokonexus.api.identity.IdentityRpcService;
import cn.kokonexus.notification.domain.FanoutJob;
import cn.kokonexus.notification.domain.NotificationEvent;
import cn.kokonexus.notification.infrastructure.persistence.NotificationFanoutMapper;
import java.util.List;
import java.util.UUID;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** notification-service：业务用例；涉及写入时遵守领域事务与权限约束。 */
@Service
@ConditionalOnProperty(prefix = "koko.notification.consumer", name = "enabled", havingValue = "true")
public class LiveFanoutService {

    /** 本类诊断日志，禁止输出密码、令牌和业务正文。 */
    private static final Logger log = LoggerFactory.getLogger(LiveFanoutService.class);
    /** NotificationFanoutMapper 持久化映射器，复杂 SQL 使用 XML。 */
    private final NotificationFanoutMapper fanoutMapper;
    /** NotificationApplicationService 业务用例依赖，事务由 Spring 代理管理。 */
    private final NotificationApplicationService notificationService;

    /** IdentityRpcService 跨服务契约代理，不直接读取其他服务数据库。 */
    @DubboReference(version = "1.0.0", check = false, timeout = 10000, retries = 0)
    private IdentityRpcService identityRpcService;

    public LiveFanoutService(
        NotificationFanoutMapper fanoutMapper,
        NotificationApplicationService notificationService
    ) {
        this.fanoutMapper = fanoutMapper;
        this.notificationService = notificationService;
    }

    public void accept(NotificationEvent event) {
        try {
            UUID.fromString(event.eventId());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("直播事件标识无效", exception);
        }
        if (
            !"LIVE_STARTED".equals(event.eventType()) ||
            event.recipientId() != 0 ||
            event.actorId() <= 0 ||
            event.resourceId() == null ||
            event.resourceId().isBlank() ||
            event.resourceId().length() > 80 ||
            event.summary() == null ||
            event.summary().isBlank() ||
            event.summary().length() > 500
        ) {
            throw new IllegalArgumentException("直播事件无效");
        }
        fanoutMapper.insertIdempotent(event);
    }

    @Scheduled(fixedDelayString = "${koko.notification.fanout.poll-ms:5000}")
    public void tick() {
        String token = UUID.randomUUID().toString();
        try {
            if (fanoutMapper.claim(token) == 0) return;
            FanoutJob job = fanoutMapper.selectClaimed(token);
            if (job == null) return;
            try {
                List<String> followers = identityRpcService.pageFollowerIds(
                    String.valueOf(job.getActorId()),
                    String.valueOf(job.getFollowerCursor()),
                    100
                );
                long cursor = job.getFollowerCursor();
                for (String followerId : followers) {
                    long recipientId = Long.parseLong(followerId);
                    notificationService.deliver(
                        new NotificationEvent(
                            job.getEventId(),
                            recipientId,
                            job.getActorId(),
                            "LIVE_STARTED",
                            job.getResourceId(),
                            job.getSummary()
                        )
                    );
                    cursor = recipientId;
                }
                if (fanoutMapper.advance(job.getEventId(), token, cursor, followers.size() < 100) != 1) {
                    log.warn("Live fanout lost lease for event {}", job.getEventId());
                }
            } catch (Exception exception) {
                int attempts = job.getAttempts() == null ? 1 : job.getAttempts();
                boolean dead = attempts >= 10;
                int backoff = Math.min(1 << Math.min(attempts, 10), 900);
                String error = exception.getClass().getSimpleName() + ": " + exception.getMessage();
                fanoutMapper.fail(
                    job.getEventId(),
                    token,
                    dead,
                    backoff,
                    error.length() > 500 ? error.substring(0, 500) : error
                );
                log.warn(
                    "Live fanout {} for event {} after {} attempts",
                    dead ? "dead-lettered" : "retry scheduled",
                    job.getEventId(),
                    attempts
                );
            }
        } catch (Exception exception) {
            log.error("Live fanout poll failed", exception);
        }
    }
}
