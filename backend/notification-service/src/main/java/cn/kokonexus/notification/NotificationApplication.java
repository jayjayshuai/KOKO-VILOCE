package cn.kokonexus.notification;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** notification-service：NotificationApplication 领域类型；字段单位、状态及可空性见各属性说明。 */
@SpringBootApplication
@MapperScan("cn.kokonexus.notification.infrastructure.persistence")
@EnableScheduling
@EnableDubbo
@ComponentScan(basePackages = { "cn.kokonexus.notification", "cn.kokonexus.common" })
/** notification-service：NotificationApplication 领域类型；字段单位、状态及可空性见各属性说明。 */
public class NotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
