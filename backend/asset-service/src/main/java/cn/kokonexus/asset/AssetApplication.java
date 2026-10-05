package cn.kokonexus.asset;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** asset-service：AssetApplication 领域类型；字段单位、状态及可空性见各属性说明。 */
@SpringBootApplication
@MapperScan("cn.kokonexus.asset.infrastructure.persistence")
@EnableScheduling
@EnableDubbo
@ComponentScan(basePackages = { "cn.kokonexus.asset", "cn.kokonexus.common" })
/** asset-service：AssetApplication 领域类型；字段单位、状态及可空性见各属性说明。 */
public class AssetApplication {

    public static void main(String[] args) {
        SpringApplication.run(AssetApplication.class, args);
    }
}
