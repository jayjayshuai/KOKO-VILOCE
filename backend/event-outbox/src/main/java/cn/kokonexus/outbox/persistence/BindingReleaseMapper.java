package cn.kokonexus.outbox.persistence;

import cn.kokonexus.outbox.binding.BindingRelease;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** SQL 原子领取与令牌 CAS；RPC I/O 不占用数据库事务锁。 */
public interface BindingReleaseMapper extends BaseMapper<BindingRelease> {
    /** 租约过期且达到十次的崩溃任务转 DEAD，不重置次数。 */
    int exhaustExpired();
    /** 原子领取最多四行；租约六十秒，次数单调递增。 */
    int claim(@Param("token") String token);
    /** 仅查询本轮持有的有界任务。 */
    List<BindingRelease> claimed(@Param("token") String token);
    /** 仅有效租约令牌可确认成功。 */
    int sent(@Param("requestId") String requestId, @Param("token") String token);
    /** 有效令牌故障转重试/DEAD，退避由本代次数决定，累计次数不清零。 */
    int failed(
        @Param("requestId") String requestId,
        @Param("token") String token,
        @Param("dead") boolean dead,
        @Param("delaySeconds") int delaySeconds
    );
}
