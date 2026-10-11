package cn.kokonexus.voice.application;

import cn.kokonexus.api.voice.*;
import cn.kokonexus.voice.infrastructure.persistence.VoiceMediaPlanMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 非事务有界发现，每房独立事务登记；部分失败已提交目标仍由持久worker继续，不伪称全部成功。 */
@Service
@RequiredArgsConstructor
public class VoiceWebsiteSessionRetirement {

    /** 按用户/摘要/状态索引发现，不输出摘要。 */ private final VoiceMediaPlanMapper media;
    /** 当前房间锁下清理核心事实的事务代理。 */ private final VoiceInteractionService core;

    public boolean retire(MediaWebsiteSessionCommand command) {
        if (
            command == null ||
            command.userId() == null ||
            !command.userId().matches("[1-9][0-9]{0,18}") ||
            !WebsiteSessionScope.valid(command.websiteScope())
        ) throw new MediaAdmissionUnavailableException();
        long user;
        try {
            user = Long.parseLong(command.userId());
        } catch (NumberFormatException invalid) {
            throw new MediaAdmissionUnavailableException();
        }
        for (long room : media.websiteRooms(user, command.websiteScope(), 16))
            core.retireWebsiteSession(room, user, command.websiteScope());
        return media.websiteRooms(user, command.websiteScope(), 1).isEmpty();
    }
}
