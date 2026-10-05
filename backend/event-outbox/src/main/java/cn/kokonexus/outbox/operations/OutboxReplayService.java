package cn.kokonexus.outbox.operations;

import cn.kokonexus.api.operations.OutboxReplayCommand;
import cn.kokonexus.api.operations.OutboxReplayReceipt;
import cn.kokonexus.common.api.ResourceNotFoundException;
import cn.kokonexus.outbox.persistence.OutboxReplayMapper;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** DEAD 单事件受理用例；必须经 Spring 事务代理调用，生产尚须接通真实 RBAC/二次确认/RPC。 */
public class OutboxReplayService {

    /** 人工受理所需权限，不接受请求自定义权限名称。 */
    public static final String REPLAY_PERMISSION = "notification:outbox:replay";
    /** 同一事件的人工轮次上限，防止绕过自动重试上限无限重发。 */
    private static final long MAX_REPLAY_GENERATION = 10;
    /** 严格 UUID 格式，避免 UUID.fromString 接受缩写输入。 */
    private static final Pattern UUID_PATTERN = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    );
    /** 同库锁、条件更新与追加审计映射。 */
    private final OutboxReplayMapper mapper;
    /** 服务端授权检查，默认不注册允许全部的实现。 */
    private final OutboxOperationsAuthorizer authorizer;

    public OutboxReplayService(OutboxReplayMapper mapper, OutboxOperationsAuthorizer authorizer) {
        this.mapper = Objects.requireNonNull(mapper);
        this.authorizer = Objects.requireNonNull(authorizer);
    }

    /**
     * 接受一次重放而非同步发送；数据库和 Broker 仍非分布式事务。
     * RC 下先锁事件再读审计：同事件竞争串行，新受理对等待者可见，不锁不存在审计 UUID 的 gap。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OutboxReplayReceipt replay(long operatorId, OutboxReplayCommand command) {
        if (operatorId <= 0) throw new IllegalArgumentException("操作者标识无效");
        authorizer.requirePermission(operatorId, REPLAY_PERMISSION);
        if (command == null) throw new IllegalArgumentException("重放命令不能为空");
        String requestId = uuid(command.requestId());
        String eventId = uuid(command.eventId());
        String reason = command.reason() == null ? "" : command.reason().strip();
        if (reason.length() < 10 || reason.length() > 500 || command.expectedGeneration() < 0) {
            throw new IllegalArgumentException("确认代次或原因格式无效，原因须为 10 到 500 字符");
        }
        OutboxReplayState state = mapper.lockEvent(eventId);
        if (state == null) throw new ResourceNotFoundException("事件不存在");
        OutboxReplayAudit existing = mapper.findAudit(requestId);
        if (existing != null) {
            if (
                !eventId.equals(existing.getEventId()) ||
                operatorId != existing.getOperatorId() ||
                command.expectedGeneration() != existing.getExpectedGeneration() ||
                !reason.equals(existing.getReason())
            ) {
                throw new IllegalStateException("请求标识已用于其他命令");
            }
            return receipt(existing);
        }
        if (
            !"DEAD".equals(state.getStatus()) ||
            state.getClaimToken() != null ||
            state.getLeaseUntil() != null ||
            state.getReplayGeneration() != command.expectedGeneration()
        ) {
            throw new IllegalStateException("事件状态或确认代次已改变，请刷新后重新确认");
        }
        if (state.getReplayGeneration() >= MAX_REPLAY_GENERATION) {
            throw new IllegalStateException("人工重放已达十轮上限，需通过工单调查根因，不能无限重新排队");
        }
        var audit = new OutboxReplayAudit();
        audit.setRequestId(requestId);
        audit.setEventId(eventId);
        audit.setOperatorId(operatorId);
        audit.setExpectedGeneration(command.expectedGeneration());
        audit.setAcceptedGeneration(command.expectedGeneration() + 1);
        audit.setReason(reason);
        audit.setPreviousAttempts(state.getAttempts());
        audit.setTotalAttemptsSnapshot(state.getTotalAttempts());
        audit.setPreviousError(state.getLastError());
        if (mapper.requeue(eventId, command.expectedGeneration()) != 1) {
            throw new IllegalStateException("事件重新排队失败，未受理");
        }
        try {
            if (mapper.insertAudit(audit) != 1) throw new IllegalStateException("审计写入失败，未受理");
        } catch (DuplicateKeyException conflict) {
            // 不吞异常后提交事件：不同事件复用同一 requestId 时必须回滚，再以新命令明确确认。
            throw new IllegalStateException("请求标识或事件代次已被其他命令使用", conflict);
        }
        OutboxReplayAudit saved = mapper.findAudit(requestId);
        if (saved == null) throw new IllegalStateException("审计未持久化，未受理");
        return receipt(saved);
    }

    private static OutboxReplayReceipt receipt(OutboxReplayAudit audit) {
        return new OutboxReplayReceipt(
            audit.getRequestId(),
            audit.getEventId(),
            audit.getAcceptedGeneration(),
            audit.getCreatedAt()
        );
    }

    private static String uuid(String value) {
        if (value == null || !UUID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("事件和受理标识必须为标准 UUID");
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
