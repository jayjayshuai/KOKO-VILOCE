package cn.kokonexus.chat.application;

import cn.kokonexus.chat.persistence.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** 仅隔离验收入口：生产 Mapper/XML 与真实 Spring 事务代理，不启动完整 Boot/目录 RPC。 */
public final class ChatClusterFixture {

    /** 固定隔离库来源，禁止连接用户部署库或任意 JDBC 目标。 */
    public final DriverManagerDataSource source;
    /** 隔离夹具 SQL，不对外提供运维接口。 */
    public final JdbcTemplate jdbc;
    /** 生产 MyBatis/MP 映射会话。 */
    public final SqlSessionTemplate sessions;
    /** MANDATORY 同步写入代理。 */
    public final ChatSyncWriter writer;
    /** 生产反骚扰事务代理。 */
    public final ChatSafetyService safety;
    /** 生产聊天事务代理。 */
    public final ChatService service;

    public ChatClusterFixture() throws Exception {
        String url = System.getenv("CHAT_CLUSTER_JDBC");
        if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:33067/koko_chat_cluster_check_20261005\\?.+")) {
            throw new IllegalArgumentException("必须使用本机固定隔离 MySQL 库");
        }
        if (!"koko_chat_cluster_check_20261005".equals(System.getenv("CHAT_CLUSTER_USER"))) {
            throw new IllegalArgumentException("必须使用隔离限权 MySQL 用户");
        }
        source = new DriverManagerDataSource(
            url,
            System.getenv("CHAT_CLUSTER_USER"),
            System.getenv("CHAT_CLUSTER_PASSWORD")
        );
        jdbc = new JdbcTemplate(source);
        var bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(source);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        bean.setConfiguration(configuration);
        bean.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml"));
        var factory = bean.getObject();
        for (var mapper : java.util.List.of(
            MessageMapper.class,
            BlockMapper.class,
            ReportMapper.class,
            ReviewMapper.class
        )) {
            factory.getConfiguration().addMapper(mapper);
        }
        sessions = new SqlSessionTemplate(factory);
        writer = transactional(new ChatSyncWriter(sessions.getMapper(ChatSyncMapper.class)));
        safety = transactional(
            new ChatSafetyService(
                sessions.getMapper(SafetyLockMapper.class),
                sessions.getMapper(BlockMapper.class),
                sessions.getMapper(ReportMapper.class),
                sessions.getMapper(ReviewMapper.class),
                sessions.getMapper(ConversationMapper.class),
                sessions.getMapper(MemberMapper.class),
                sessions.getMapper(MessageMapper.class),
                writer
            )
        );
        service = transactional(
            new ChatService(
                sessions.getMapper(ConversationMapper.class),
                sessions.getMapper(MemberMapper.class),
                sessions.getMapper(MessageMapper.class),
                safety,
                writer
            )
        );
    }

    @SuppressWarnings("unchecked")
    private <T> T transactional(T target) {
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
            new TransactionInterceptor(
                new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()
            )
        );
        return (T) proxy.getProxy();
    }
}
