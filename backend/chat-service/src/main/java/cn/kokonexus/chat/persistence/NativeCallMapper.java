package cn.kokonexus.chat.persistence;

import cn.kokonexus.chat.domain.NativeCall;
import cn.kokonexus.chat.domain.NativeCallCommand;
import cn.kokonexus.chat.domain.NativeCallParticipant;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 原生通话关键并发SQL；先锁聊天会话，数据库唯一忙线约束跨进程生效。 */
public interface NativeCallMapper extends BaseMapper<NativeCall> {
    NativeCall lock(@Param("id") String id);
    NativeCall original(
        @Param("conversationId") String conversationId,
        @Param("actorId") long actorId,
        @Param("requestId") String requestId
    );
    NativeCall activeConversation(@Param("conversationId") String conversationId);
    List<NativeCallParticipant> participants(@Param("callId") String callId);
    int insertCall(@Param("row") NativeCall row);
    int insertParticipant(@Param("row") NativeCallParticipant row);
    int accept(
        @Param("callId") String callId,
        @Param("userId") long userId,
        @Param("scope") String scope,
        @Param("identity") String identity
    );
    int participantState(
        @Param("callId") String callId,
        @Param("userId") long userId,
        @Param("expected") String expected,
        @Param("state") String state
    );
    int advance(
        @Param("id") String id,
        @Param("version") long version,
        @Param("state") String state,
        @Param("reason") String reason
    );
    int finishParticipants(@Param("callId") String callId);
    NativeCallCommand command(
        @Param("callId") String callId,
        @Param("actorId") long actorId,
        @Param("requestId") String requestId
    );
    int insertCommand(@Param("row") NativeCallCommand row);
    int heartbeat(@Param("callId") String callId, @Param("actorId") long actorId, @Param("scope") String scope);
    int retire(
        @Param("jobKey") String jobKey,
        @Param("callId") String callId,
        @Param("version") long version,
        @Param("action") String action,
        @Param("identity") String identity
    );
    boolean eligible(@Param("callId") String callId);
    List<String> scan(@Param("after") String after);
}
