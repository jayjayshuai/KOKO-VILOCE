package cn.kokonexus.identity.tooling;

import cn.kokonexus.api.operations.OperationsAccessDeniedException;
import cn.kokonexus.identity.application.OperationsBootstrapCommand;
import cn.kokonexus.identity.application.OperationsBootstrapService;
import cn.kokonexus.identity.infrastructure.persistence.OperationsBootstrapMapper;
import cn.kokonexus.identity.infrastructure.persistence.OperationsRoleMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import java.time.LocalDateTime;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** 负责人显式离线入口：无 Web、Nacos、RPC、MQ、Flyway 或自动启动赋权。 */
public final class OperationsBootstrapCli {

    private OperationsBootstrapCli() {}

    /** 密码仅从受保护进程环境读取，不作为命令行参数或诊断内容。 */
    public static void main(String[] arguments) {
        try {
            var command = parse(arguments);
            String url = environment("DATABASE_URL");
            if (!url.startsWith("jdbc:mysql://") || url.matches("(?is).*([?&])(password|user|username)=.*")) {
                throw new IllegalArgumentException("须显式配置 MySQL URL，凭据只能放独立环境变量");
            }
            var source = new DriverManagerDataSource(
                url,
                environment("DATABASE_USER"),
                environment("DATABASE_PASSWORD")
            );
            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setLogImpl(NoLoggingImpl.class);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(configuration);
            factory.setMapperLocations(
                new ClassPathResource("mapper/OperationsRoleMapper.xml"),
                new ClassPathResource("mapper/OperationsBootstrapMapper.xml")
            );
            var sessions = new SqlSessionTemplate(factory.getObject());
            var target = new OperationsBootstrapService(
                sessions.getMapper(OperationsRoleMapper.class),
                sessions.getMapper(OperationsBootstrapMapper.class)
            );
            var proxy = new ProxyFactory(target);
            proxy.setProxyTargetClass(true);
            proxy.addAdvice(
                new TransactionInterceptor(
                    new DataSourceTransactionManager(source),
                    new AnnotationTransactionAttributeSource()
                )
            );
            var service = (OperationsBootstrapService) proxy.getProxy();
            if ("plan".equals(arguments[0])) {
                String hash = service.plan(arguments[1], command);
                System.out.println(
                    "PLAN_ONLY: no account or role was created; verify selected database/account/ticket."
                );
                System.out.println(
                    "database=" +
                        arguments[1] +
                        " userId=" +
                        command.userId() +
                        " handle=" +
                        command.expectedHandle() +
                        " requestId=" +
                        command.requestId() +
                        " expiresAt=" +
                        command.expiresAt() +
                        " timezone=Asia/Shanghai"
                );
                System.out.println("approvalHash=" + hash);
            } else {
                var receipt = service.apply(arguments[1], command, environment("OPERATIONS_BOOTSTRAP_APPROVAL"));
                System.out.println(
                    "ACCEPTED_FACT: requestId=" +
                        receipt.requestId() +
                        " userId=" +
                        receipt.userId() +
                        " role=" +
                        receipt.roleCode() +
                        " acceptedVersion=" +
                        receipt.version() +
                        "; historical receipt does not prove current authorization is unchanged."
                );
            }
        } catch (OperationsAccessDeniedException denied) {
            fail("APPROVAL_REQUIRED: no matching explicit approval.");
        } catch (IllegalArgumentException invalid) {
            fail("INVALID_INPUT: " + invalid.getMessage());
        } catch (IllegalStateException conflict) {
            // MyBatis 基础设施异常也可能继承 IllegalStateException；不输出连接错误或请求内容。
            fail("STATE_NOT_CONFIRMED: verify migrations, account and initialization history; retain original UUID.");
        } catch (Exception failure) {
            fail(
                "DEPENDENCY_OR_COMMIT_UNKNOWN: inspect protected server diagnostics; retain original UUID, do not retry a new command."
            );
        }
    }

    static OperationsBootstrapCommand parse(String[] arguments) {
        if (
            arguments == null ||
            arguments.length != 7 ||
            !("plan".equals(arguments[0]) || "apply".equals(arguments[0])) ||
            !arguments[1].matches("[a-z][a-z0-9_]{0,63}")
        ) {
            throw new IllegalArgumentException(
                "Usage: plan|apply database userId handle requestUUID ShanghaiExpiry reason"
            );
        }
        if (!arguments[2].matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("账号 ID 无效");
        try {
            return new OperationsBootstrapCommand(
                arguments[4],
                Long.parseLong(arguments[2]),
                arguments[3],
                LocalDateTime.parse(arguments[5]),
                arguments[6]
            ).normalized();
        } catch (NumberFormatException | java.time.format.DateTimeParseException invalid) {
            throw new IllegalArgumentException("账号 ID 或上海到期时间格式无效");
        }
    }

    private static String environment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少显式环境配置 " + name);
        return value;
    }

    private static void fail(String message) {
        System.err.println(message);
        System.exit(2);
    }
}
