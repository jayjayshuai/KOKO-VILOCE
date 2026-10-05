package cn.kokonexus.outbox.persistence;

import cn.kokonexus.api.operations.BindingReleaseCursor;
import cn.kokonexus.outbox.binding.BindingAttempt;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 凭据同行锁串行业务提交与核对；不接受动态表名或任意SQL。 */
public interface BindingAttemptMapper extends BaseMapper<BindingAttempt> {
    /** 获取原行锁；不存在不构成可释放证明。 */
    BindingAttempt lock(@Param("requestId") String requestId);
    /** 固定OPEN到终态，所有指纹与状态匹配才更新。 */
    int finish(@Param("attempt") BindingAttempt attempt, @Param("status") String status);
    /** 最多八行且跳过正在写入的凭据；时间仅减少扫描。 */
    List<BindingAttempt> idleOpen();
    /** 仅OPEN有界索引游标读取，非锁定核对，不执行补偿。 */
    List<BindingAttempt> openPage(@Param("cursor") BindingReleaseCursor cursor, @Param("limit") int limit);
}
