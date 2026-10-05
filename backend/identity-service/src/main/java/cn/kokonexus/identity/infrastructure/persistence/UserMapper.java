package cn.kokonexus.identity.infrastructure.persistence;

import cn.kokonexus.identity.domain.UserAccount;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** identity-service：持久化映射；值参数绑定，复杂查询在 XML。 */
public interface UserMapper extends BaseMapper<UserAccount> {}
