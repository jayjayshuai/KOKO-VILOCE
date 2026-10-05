package cn.kokonexus.gateway.interfaces;

/** gateway-service：领域失败语义；外部接口映射为对应状态码，不伪装成功。 */
final class AuthenticationFailedException extends RuntimeException {

    AuthenticationFailedException() {
        super("邮箱或密码错误");
    }
}
