package cn.kokonexus.identity.application;

import cn.kokonexus.api.identity.AuthenticateIdentityCommand;
import cn.kokonexus.api.identity.RegisterIdentityCommand;
import cn.kokonexus.api.identity.UserIdentity;
import cn.kokonexus.identity.domain.UserAccount;
import cn.kokonexus.identity.infrastructure.persistence.UserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** identity-service：业务用例；涉及写入时遵守领域事务与权限约束。 */
@Service
public class IdentityApplicationService {

    /** 服务端格式校验规则，禁止客户端覆盖。 */
    private static final Pattern HANDLE_PATTERN = Pattern.compile("[a-zA-Z0-9_]{3,32}");
    /** UserMapper 持久化映射器，复杂 SQL 使用 XML。 */
    private final UserMapper userMapper;
    /** BCrypt 密码编码器，当前工作因子 12。 */
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);

    public IdentityApplicationService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Transactional
    public UserIdentity register(RegisterIdentityCommand command) {
        validateRegistration(command);
        UserAccount user = new UserAccount();
        user.setEmail(command.email().trim().toLowerCase(Locale.ROOT));
        user.setPasswordHash(passwordEncoder.encode(command.password()));
        user.setHandle(command.handle().trim().toLowerCase(Locale.ROOT));
        user.setDisplayName(command.displayName().trim());
        user.setStatus("ACTIVE");
        try {
            if (userMapper.insert(user) != 1) {
                throw new IllegalStateException("账号创建失败");
            }
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("邮箱或用户名已被注册", exception);
        }
        return toIdentity(user);
    }

    @Transactional(readOnly = true)
    public UserIdentity authenticate(AuthenticateIdentityCommand command) {
        String email = command.email() == null ? "" : command.email().trim().toLowerCase(Locale.ROOT);
        UserAccount user = userMapper.selectOne(Wrappers.<UserAccount>lambdaQuery().eq(UserAccount::getEmail, email));
        if (user == null || !passwordEncoder.matches(command.password(), user.getPasswordHash())) {
            throw new IllegalArgumentException("邮箱或密码错误");
        }
        return requireActive(user);
    }

    @Transactional(readOnly = true)
    public UserIdentity findActiveUser(String userId) {
        try {
            UserAccount user = userMapper.selectById(Long.valueOf(userId));
            if (user == null) {
                throw new IllegalArgumentException("用户不存在");
            }
            return requireActive(user);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("用户标识无效", exception);
        }
    }

    private UserIdentity requireActive(UserAccount user) {
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new IllegalArgumentException("账号当前不可用");
        }
        return toIdentity(user);
    }

    /** 精确查找活跃账号，禁止通过聊天接口枚举邮箱或读取密码字段。 */
    @Transactional(readOnly = true)
    public cn.kokonexus.api.identity.ChatIdentity findChatIdentity(String handle) {
        if (handle == null || !HANDLE_PATTERN.matcher(handle).matches()) {
            throw new IllegalArgumentException("用户名格式无效");
        }
        UserAccount user = userMapper.selectOne(
            Wrappers.<UserAccount>lambdaQuery()
                .eq(UserAccount::getHandle, handle.toLowerCase(Locale.ROOT))
                .eq(UserAccount::getStatus, "ACTIVE")
        );
        if (user == null) throw new IllegalArgumentException("用户不存在或不可用");
        return new cn.kokonexus.api.identity.ChatIdentity(
            user.getId().toString(),
            user.getHandle(),
            user.getDisplayName()
        );
    }

    private UserIdentity toIdentity(UserAccount user) {
        return new UserIdentity(
            String.valueOf(user.getId()),
            user.getEmail(),
            user.getHandle(),
            user.getDisplayName(),
            user.getAvatarUrl()
        );
    }

    private void validateRegistration(RegisterIdentityCommand command) {
        if (
            command.email() == null ||
            command.email().isBlank() ||
            command.email().length() > 254 ||
            !command.email().contains("@")
        ) {
            throw new IllegalArgumentException("邮箱格式无效");
        }
        if (command.password() == null || command.password().length() < 10 || command.password().length() > 72) {
            throw new IllegalArgumentException("密码长度必须为 10 到 72 个字符");
        }
        if (command.handle() == null || !HANDLE_PATTERN.matcher(command.handle()).matches()) {
            throw new IllegalArgumentException("用户名只能包含字母、数字和下划线，长度为 3 到 32 位");
        }
        if (command.displayName() == null || command.displayName().isBlank() || command.displayName().length() > 80) {
            throw new IllegalArgumentException("显示名称不能为空且不能超过 80 个字符");
        }
    }
}
