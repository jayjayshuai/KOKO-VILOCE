package cn.kokonexus.identity.domain;

import lombok.Getter;
import lombok.Setter;

/** 首次审批绑定快照；不保存数据库密码，不提供修改或删除接口。 */
@Getter
@Setter
public class OperationsBootstrapApproval {

    /** 原受理 UUID。 */
    private String requestId;
    /** 目标实例、库和完整命令摘要。 */
    private String commandHash;
    /** 初始化时明确核对的账号标识。 */
    private String approvedHandle;
}
