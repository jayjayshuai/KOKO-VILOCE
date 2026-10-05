package cn.kokonexus.api.identity;

import java.io.Serializable;

/** 平台公共契约：领域失败语义；外部接口映射为对应状态码，不伪装成功。 */
public class CreatorProfileConflictException extends IllegalStateException implements Serializable {

    public CreatorProfileConflictException(String message) {
        super(message);
    }
}
