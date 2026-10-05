package cn.kokonexus.community;

import cn.kokonexus.outbox.OutboxConfiguration;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

/** community-service：CommunityApplication 领域类型；字段单位、状态及可空性见各属性说明。 */
@SpringBootApplication
@MapperScan({ "cn.kokonexus.community.infrastructure.persistence", "cn.kokonexus.outbox.persistence" })
@Import(OutboxConfiguration.class)
@EnableDubbo
@ComponentScan(basePackages = { "cn.kokonexus.community", "cn.kokonexus.common" })
/** community-service：CommunityApplication 领域类型；字段单位、状态及可空性见各属性说明。 */
public class CommunityApplication {

    public static void main(String[] args) {
        SpringApplication.run(CommunityApplication.class, args);
    }
}
