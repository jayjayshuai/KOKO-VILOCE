package cn.kokonexus.api.identity;

/** 重复注册的公开业务冲突；固定消息、不携带数据库cause，避免RPC泄露账号或序列化驱动异常。 */
public final class RegistrationConflictException extends RuntimeException {

    /** 明确RPC兼容版本，不记录输入或数据库诊断。 */
    private static final long serialVersionUID = 1L;

    public RegistrationConflictException() {
        super("邮箱或用户名已被注册，请登录或使用其他邮箱和用户名");
    }
}
