package cn.kokonexus.notification.interfaces;

import cn.kokonexus.notification.application.NotificationApplicationService;
import cn.kokonexus.notification.application.NotificationApplicationService.NotificationPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** notification-service：HTTP 业务边界；身份校验与归属决策由网关及业务用例共同执行。 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "NotificationController")
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    /** NotificationApplicationService 业务用例依赖，事务由 Spring 代理管理。 */
    private final NotificationApplicationService service;

    public NotificationController(NotificationApplicationService service) {
        this.service = service;
    }

    @GetMapping
    @io.swagger.v3.oas.annotations.Operation(summary = "分页读取本人通知")
    public PageView page(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        NotificationPage result = service.page(userId, page, size);
        return new PageView(
            result.items().stream().map(NotificationView::from).toList(),
            result.page(),
            result.size(),
            result.total()
        );
    }

    @PutMapping("/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @io.swagger.v3.oas.annotations.Operation(summary = "标记本人通知已读")
    public void markRead(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable long notificationId
    ) {
        service.markRead(userId, notificationId);
    }

    @GetMapping("/preferences/{eventType}")
    @io.swagger.v3.oas.annotations.Operation(summary = "读取本人通知偏好")
    public PreferenceView preference(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable String eventType
    ) {
        return new PreferenceView(eventType, service.preference(userId, eventType));
    }

    @PutMapping("/preferences/{eventType}")
    @io.swagger.v3.oas.annotations.Operation(summary = "保存本人通知偏好")
    public PreferenceView savePreference(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable String eventType,
        @Valid @RequestBody PreferenceRequest request
    ) {
        return new PreferenceView(eventType, service.savePreference(userId, eventType, request.enabled()));
    }

    /** notification-service：请求契约；字段校验以公开接口约束为准。 */
    public record PreferenceRequest(
        @io.swagger.v3.oas.annotations.media.Schema(description = "是否启用对应业务能力") @NotNull Boolean enabled
    ) {}

    /** notification-service：PreferenceView 领域类型；字段单位、状态及可空性见各属性说明。 */
    public record PreferenceView(
        @io.swagger.v3.oas.annotations.media.Schema(description = "通知事件类型") String eventType,
        @io.swagger.v3.oas.annotations.media.Schema(description = "是否启用对应业务能力") boolean enabled
    ) {}

    /** notification-service：PageView 领域类型；字段单位、状态及可空性见各属性说明。 */
    public record PageView(
        @io.swagger.v3.oas.annotations.media.Schema(description = "当前页的业务投影列表") List<NotificationView> items,
        @io.swagger.v3.oas.annotations.media.Schema(description = "页码，从 1 开始") int page,
        @io.swagger.v3.oas.annotations.media.Schema(description = "单页条数，受接口最大值限制") int size,
        @io.swagger.v3.oas.annotations.media.Schema(description = "匹配条件的总条数") long total
    ) {}

    /** notification-service：NotificationView 领域类型；字段单位、状态及可空性见各属性说明。 */
    public record NotificationView(
        @io.swagger.v3.oas.annotations.media.Schema(description = "唯一业务标识；HTTP 用户/雪花 ID 使用字符串表示")
        String id,
        @io.swagger.v3.oas.annotations.media.Schema(description = "触发事件的用户 ID") String actorId,
        @io.swagger.v3.oas.annotations.media.Schema(description = "通知事件类型") String eventType,
        @io.swagger.v3.oas.annotations.media.Schema(description = "事件关联的业务资源 ID") String resourceId,
        @io.swagger.v3.oas.annotations.media.Schema(description = "通知摘要") String summary,
        @io.swagger.v3.oas.annotations.media.Schema(description = "服务端创建时间，数据库时区 Asia/Shanghai")
        LocalDateTime createdAt,
        @io.swagger.v3.oas.annotations.media.Schema(description = "已读时间；未读为空，Asia/Shanghai")
        LocalDateTime readAt
    ) {
        static NotificationView from(cn.kokonexus.notification.domain.NotificationInbox item) {
            return new NotificationView(
                String.valueOf(item.getId()),
                String.valueOf(item.getActorId()),
                item.getEventType(),
                item.getResourceId(),
                item.getSummary(),
                item.getCreatedAt(),
                item.getReadAt()
            );
        }
    }
}
