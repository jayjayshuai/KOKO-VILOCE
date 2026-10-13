package cn.kokonexus.community.application;

import cn.kokonexus.community.domain.CreatorPost;
import cn.kokonexus.community.infrastructure.persistence.CreatorPostMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.HashSet;
import java.util.Properties;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** 手动只读检查：环境指定私有连接文件，参数仅专用测试作者及预期公开数；不建表/改数据。 */
public class AuthorPostsMysqlReadCheck {

    public static void main(String[] args) {
        try {
            if (args.length != 2) throw new IllegalArgumentException("Expected own fixture author and published count");
            long owner = Long.parseLong(args[0]);
            int expected = Integer.parseInt(args[1]);
            if (owner < 1 || expected < 1 || expected > 120) throw new IllegalArgumentException(
                "Fixture scope invalid"
            );
            var config = new Properties();
            try (var input = Files.newInputStream(Path.of(System.getenv("KOKO_AUTHOR_POST_MYSQL_PROPERTIES")))) {
                config.load(input);
            }
            var auth = new Properties();
            auth.setProperty("user", config.getProperty("mysql.username"));
            auth.setProperty("password", config.getProperty("mysql.password"));
            auth.setProperty("connectTimeout", "8000");
            auth.setProperty("socketTimeout", "8000");
            var source = new AbstractDataSource() {
                public java.sql.Connection getConnection() throws java.sql.SQLException {
                    var connection = DriverManager.getConnection(config.getProperty("mysql.jdbc.url"), auth);
                    connection.setCatalog("koko_community");
                    connection.setReadOnly(true);
                    return connection;
                }

                public java.sql.Connection getConnection(String user, String password) throws java.sql.SQLException {
                    throw new UnsupportedOperationException();
                }
            };
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            var mapping = new MybatisConfiguration();
            mapping.setMapUnderscoreToCamelCase(true);
            mapping.setDefaultStatementTimeout(5);
            factory.setConfiguration(mapping);
            factory.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/CreatorPostMapper.xml")
            );
            var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(CreatorPostMapper.class);
            var seen = new HashSet<Long>();
            AuthorPostCursor cursor = null;
            CreatorPost previous = null;
            int pages = 0;
            do {
                var rows = mapper.selectPublishedByAuthor(
                    owner,
                    cursor == null ? null : cursor.publishedAt(),
                    cursor == null ? null : cursor.postId(),
                    13
                );
                if (rows.size() > 13) throw new AssertionError("Unbounded response");
                var visible = rows.subList(0, Math.min(12, rows.size()));
                for (var row : visible) {
                    if (
                        row.getOwnerId() != owner ||
                        !"PUBLISHED".equals(row.getStatus()) ||
                        row.getBody() != null ||
                        !seen.add(row.getId())
                    ) throw new AssertionError("Owner/public/projection/duplicate invariant");
                    if (
                        previous != null &&
                        !(
                            row.getPublishedAt().isBefore(previous.getPublishedAt()) ||
                            (row.getPublishedAt().equals(previous.getPublishedAt()) && row.getId() < previous.getId())
                        )
                    ) throw new AssertionError("Keyset order invariant");
                    previous = row;
                }
                pages++;
                if (pages > 11) throw new AssertionError("Unbounded check");
                cursor =
                    rows.size() > 12 ? new AuthorPostCursor(owner, previous.getPublishedAt(), previous.getId()) : null;
            } while (cursor != null);
            if (seen.size() != expected) throw new AssertionError("Published count not fixture expectation");
            boolean filesort;
            String index;
            var explainParameters = new java.util.HashMap<String, Object>();
            explainParameters.put("ownerId", owner);
            explainParameters.put("publishedAt", null);
            explainParameters.put("postId", null);
            explainParameters.put("limit", 13);
            var bound = factory
                .getObject()
                .getConfiguration()
                .getMappedStatement(CreatorPostMapper.class.getName() + ".selectPublishedByAuthor")
                .getBoundSql(explainParameters);
            if (
                !bound
                    .getParameterMappings()
                    .stream()
                    .map(parameter -> parameter.getProperty())
                    .toList()
                    .equals(java.util.List.of("ownerId", "limit"))
            ) throw new AssertionError("Explain parameter contract");
            try (
                var connection = source.getConnection();
                var statement = connection.prepareStatement("EXPLAIN " + bound.getSql())
            ) {
                statement.setLong(1, owner);
                statement.setInt(2, 13);
                statement.setQueryTimeout(5);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) throw new AssertionError("Explain missing");
                    index = rows.getString("key");
                    filesort = String.valueOf(rows.getString("Extra")).contains("filesort");
                }
            }
            System.out.println(
                "{\"passed\":true,\"recordsVerified\":" +
                    seen.size() +
                    ",\"pagesVerified\":" +
                    pages +
                    ",\"bodyExcluded\":true,\"sqlWrites\":false,\"index\":\"" +
                    index +
                    "\",\"filesort\":" +
                    filesort +
                    "}"
            );
        } catch (Throwable failure) {
            System.out.println(
                "{\"passed\":false,\"failureType\":\"" + failure.getClass().getSimpleName() + "\",\"sqlWrites\":false}"
            );
            System.exit(1);
        }
    }
}
