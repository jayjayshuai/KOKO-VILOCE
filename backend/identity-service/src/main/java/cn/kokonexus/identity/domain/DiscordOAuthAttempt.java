package cn.kokonexus.identity.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 内部持久授权材料，不作为HTTP/RPC投影，不生成含密文的toString。 */
@Getter
@Setter
public class DiscordOAuthAttempt {

    /** 平台账号主键，与请求标识构成复合主键。 */
    private Long userId;
    /** 用户明确发起一次绑定的UUID，重试不能变更作用域。 */
    private String requestId;
    /** 网关生成的网站会话摘要，浏览器不能自行赋予。 */
    private String websiteScope;
    /** 随机state摘要，回调定位用，原始state仅以密文保存。 */
    private String stateDigest;
    /** 用户单调授权轮次，阻止被新授权替代的旧请求绑定。 */
    private Long generation;
    /** WAITING/EXCHANGING/VERIFIED/BOUND/DENIED/UNKNOWN/SUPERSEDED/CONFLICT。 */
    private String attemptStatus;
    /** 已领取的授权码摘要，禁止同state改换code。 */
    private String codeDigest;

    /** 仅用于恢复同一次授权URL；禁止序列化。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String encryptedState;

    /** 领取提交后清除；发送换码的工作线程持有独立局部明文。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String encryptedVerifier;

    /** 交换成功但未绑定的令牌保留，供后续受控撤销核对，不自动撤销新授权。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String encryptedRefreshToken;

    /** 供应商实际确认的Discord标识，可空。 */
    private String discordUserId;
    /** 实际确认的最小用户名快照，可空。 */
    private String discordUsername;
    /** 固定安全分类，不存响应正文或请求错误详情。 */
    private String failureCode;
    /** UTC创建时间，不使用JVM本地时间进行授权判断。 */
    private LocalDateTime createdAt;
    /** UTC绝对到期时间；SQL使用UTC_TIMESTAMP判断。 */
    private LocalDateTime expiresAt;
    /** UTC最近状态更新时间。 */
    private LocalDateTime updatedAt;

    /** 查询时由数据库计算到期条件，不持久化。 */
    @TableField(exist = false)
    private boolean fresh;
}
