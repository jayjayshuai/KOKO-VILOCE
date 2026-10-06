package cn.kokonexus.voice.infrastructure.media;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 只有显式启用退场执行才安装定时任务，不因计划迁移自动访问SFU。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "koko.voice.media-retirement", name = "enabled", havingValue = "true")
public class VoiceMediaRetirementConfiguration {}
