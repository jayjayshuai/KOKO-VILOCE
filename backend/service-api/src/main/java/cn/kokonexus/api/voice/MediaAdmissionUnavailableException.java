package cn.kokonexus.api.voice;

/** 媒体准入依赖故障；固定消息，禁止携带JWT、SQL或底层异常原文。 */
public class MediaAdmissionUnavailableException extends RuntimeException {

    public MediaAdmissionUnavailableException() {
        super("媒体准入暂不可用");
    }
}
