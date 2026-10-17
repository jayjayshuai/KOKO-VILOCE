package cn.kokonexus.asset.infrastructure.persistence;

import cn.kokonexus.asset.domain.VideoDraft;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 视频额度、原请求和处理租约使用同库短事务及显式SQL。 */
public interface VideoDraftMapper extends BaseMapper<VideoDraft> {
    void ensureCapacity(@Param("owner") long owner);
    int lockGlobal();
    int reserve(@Param("owner") long owner, @Param("limit") int limit);
    VideoDraft original(@Param("owner") long owner, @Param("request") String request);
    int insertDraft(@Param("row") VideoDraft row);
    int queue(@Param("id") String id);
    int expire();
    int processingCount();
    VideoDraft next();
    int claim(@Param("id") String id, @Param("run") String run);
    boolean canProcess(@Param("id") String id, @Param("run") String run);
    boolean outputAllowed(@Param("id") String id, @Param("run") String run);
    int ready(@Param("row") VideoDraft row);
    int fail(@Param("id") String id, @Param("run") String run, @Param("code") String code);
    List<VideoDraft> owned(@Param("owner") long owner);
    VideoDraft ownedOne(@Param("owner") long owner, @Param("id") String id);
    VideoDraft lockVideo(@Param("id") String id);
    int beginWrite(
        @Param("id") String id,
        @Param("video") String video,
        @Param("kind") String kind,
        @Param("run") String run
    );
    int settleWrite(@Param("id") String id, @Param("video") String video);
    boolean settledWrite(@Param("id") String id, @Param("video") String video);
    int uncertainWrite(@Param("id") String id, @Param("video") String video);
    int unresolvedWrites(@Param("id") String id);
    int processed(@Param("id") String id, @Param("run") String run);
    int requestDelete(@Param("id") String id, @Param("request") String request);
    int cleanupBusy();
    VideoDraft nextDeletion();
    int claimDeletion(@Param("id") String id, @Param("token") String token);
    int deletionDone(@Param("id") String id, @Param("token") String token);
    int deletionFailed(@Param("id") String id, @Param("token") String token);
    int release(@Param("owner") long owner);
}
