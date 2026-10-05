package cn.kokonexus.identity;

import cn.kokonexus.outbox.OutboxConfiguration;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

/** identity-service：IdentityApplication 领域类型；字段单位、状态及可空性见各属性说明。 */
@SpringBootApplication
@ComponentScan(basePackages = { "cn.kokonexus.identity", "cn.kokonexus.common" })
@EnableDubbo
@MapperScan({ "cn.kokonexus.identity.infrastructure.persistence", "cn.kokonexus.outbox.persistence" })
/** identity-service：IdentityApplication 领域类型；字段单位、状态及可空性见各属性说明。 */
@Import(OutboxConfiguration.class)
public class IdentityApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityApplication.class, args);
    }
}
