package cn.kokonexus.chat.application;

import cn.kokonexus.chat.persistence.ChatSyncMapper;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 同业务事务登记持久失效版本；不可在提交后补写，避免进程崩溃丢同步。 */
@Service
@RequiredArgsConstructor
public class ChatSyncWriter {

    /** 用户版本表；版本写入失败必须使业务事务失败。 */
    private final ChatSyncMapper mapper;

    /** 业务锁取得后，按全局用户顺序登记；不能在登记之后再申请业务锁。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(Collection<Long> userIds) {
        if (
            userIds == null ||
            userIds.isEmpty() ||
            userIds.size() > 50 ||
            userIds.stream().anyMatch(id -> id == null || id <= 0)
        ) {
            throw new IllegalArgumentException("同步用户范围必须为 1～50 个有效用户");
        }
        for (long userId : userIds.stream().distinct().sorted().toList()) {
            int affected = mapper.advance(userId);
            if (affected != 1 && affected != 2) {
                throw new IllegalStateException("聊天同步版本登记失败");
            }
        }
    }
}
