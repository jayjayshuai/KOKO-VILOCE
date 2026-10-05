package cn.kokonexus.voice.infrastructure.persistence;

import cn.kokonexus.voice.domain.VoiceInteraction.*;
import cn.kokonexus.voice.domain.VoiceRoom;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 房间行锁先于成员/席位，值绑定，不在EventLoop或外部媒体调用内执行。 */
@Mapper
public interface VoiceInteractionMapper {
    List<Action> actions(@Param("room") long room, @Param("before") Long before, @Param("limit") int limit);
    VoiceRoom lockRoom(@Param("room") long room);
    LocalDateTime databaseNow();
    Member member(@Param("room") long room, @Param("user") long user);
    List<Member> activeMembers(@Param("room") long room);
    List<Member> administrators(@Param("room") long room);
    List<Seat> seats(@Param("room") long room);
    List<SeatRequest> pendingRequests(@Param("room") long room);
    Receipt receipt(@Param("room") long room, @Param("user") long user, @Param("id") String id);
    List<String> recentReceipts(
        @Param("room") long room,
        @Param("user") long user,
        @Param("since") LocalDateTime since
    );
    List<String> receiptBudget(@Param("room") long room, @Param("user") long user);
    int insertSeat(@Param("room") long room, @Param("no") int no);
    int saveMember(@Param("member") Member member, @Param("joined") LocalDateTime joined);
    int saveSeat(@Param("seat") Seat seat);
    int insertRequest(@Param("request") SeatRequest request, @Param("now") LocalDateTime now);
    int finishRequest(@Param("id") String id, @Param("state") String state);
    int touchMember(
        @Param("room") long room,
        @Param("user") long user,
        @Param("session") String session,
        @Param("now") LocalDateTime now,
        @Param("until") LocalDateTime until
    );
    int setMemberState(@Param("room") long room, @Param("user") long user, @Param("state") String state);
    int setRole(@Param("room") long room, @Param("user") long user, @Param("role") String role);
    int transfer(@Param("room") long room, @Param("owner") long owner, @Param("target") Member target);
    int bumpVersion(@Param("room") long room);
    int insertReceipt(
        @Param("room") long room,
        @Param("user") long user,
        @Param("id") String id,
        @Param("receipt") Receipt receipt,
        @Param("now") LocalDateTime now
    );
    int audit(
        @Param("id") String id,
        @Param("room") long room,
        @Param("actor") Long actor,
        @Param("type") String type,
        @Param("version") long version,
        @Param("now") LocalDateTime now,
        @Param("target") Long target,
        @Param("seat") Integer seat,
        @Param("request") String request,
        @Param("value") Boolean value
    );
}
