package cn.kokonexus.live;

import cn.kokonexus.outbox.OutboxConfiguration;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

/** live-service：LiveApplication 领域类型；字段单位、状态及可空性见各属性说明。 */
@SpringBootApplication
@EnableDubbo
@MapperScan({ "cn.kokonexus.live.infrastructure.persistence", "cn.kokonexus.outbox.persistence" })
@ComponentScan(basePackages = { "cn.kokonexus.live", "cn.kokonexus.common" })
/** live-service：LiveApplication 领域类型；字段单位、状态及可空性见各属性说明。 */
@Import(OutboxConfiguration.class)
public class LiveApplication {

    public static void main(String[] args) {
        SpringApplication.run(LiveApplication.class, args);
    }
}
