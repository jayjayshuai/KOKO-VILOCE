package cn.kokonexus.identity.infrastructure.persistence;

import cn.kokonexus.identity.domain.DiscordConnectionEntity;
import cn.kokonexus.identity.domain.DiscordLinkCursor;
import cn.kokonexus.identity.domain.DiscordOAuthAttempt;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

/** 领取、围栏、绑定和审计使用同库XML；所有写入检查影响行数。 */
public interface DiscordLinkMapper extends BaseMapper<DiscordConnectionEntity> {
    boolean sessionRetired(@Param("userId") long userId, @Param("scope") String scope);
    int retireSession(@Param("userId") long userId, @Param("scope") String scope);
    int invalidateCursor(@Param("userId") long userId, @Param("generation") long generation);
    String lockUser(@Param("userId") long userId);
    int ensureCursor(@Param("userId") long userId);
    DiscordLinkCursor lockCursor(@Param("userId") long userId);
    int advance(
        @Param("userId") long userId,
        @Param("generation") long generation,
        @Param("requestId") String requestId
    );
    int recentCount(@Param("userId") long userId);
    DiscordOAuthAttempt findRequest(@Param("userId") long userId, @Param("requestId") String requestId);
    DiscordOAuthAttempt findState(@Param("userId") long userId, @Param("stateDigest") String stateDigest);
    int insertAttempt(@Param("row") DiscordOAuthAttempt row);
    int claim(
        @Param("userId") long userId,
        @Param("requestId") String requestId,
        @Param("scope") String scope,
        @Param("generation") long generation,
        @Param("codeDigest") String codeDigest
    );
    int storeToken(
        @Param("userId") long userId,
        @Param("requestId") String requestId,
        @Param("codeDigest") String codeDigest,
        @Param("encryptedToken") String encryptedToken
    );
    int verifyIdentity(
        @Param("userId") long userId,
        @Param("requestId") String requestId,
        @Param("codeDigest") String codeDigest,
        @Param("discordId") String discordId,
        @Param("username") String username
    );
    int settle(
        @Param("userId") long userId,
        @Param("requestId") String requestId,
        @Param("expected") String expected,
        @Param("status") String status,
        @Param("failureCode") String failureCode
    );
    int insertConnection(@Param("row") DiscordOAuthAttempt row);
    int bind(@Param("userId") long userId, @Param("requestId") String requestId, @Param("generation") long generation);
    int audit(@Param("row") DiscordOAuthAttempt row, @Param("action") String action);
}
