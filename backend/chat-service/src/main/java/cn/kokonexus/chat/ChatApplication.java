package cn.kokonexus.chat;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/** 实时消息微服务入口；数据库与连接生命周期均独立于音视频服务。 */
@SpringBootApplication
@EnableDubbo
@MapperScan("cn.kokonexus.chat.persistence")
@ComponentScan({ "cn.kokonexus.chat", "cn.kokonexus.common" })
/** chat-service：ChatApplication 领域类型；字段单位、状态及可空性见各属性说明。 */
public class ChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatApplication.class, args);
    }
}
