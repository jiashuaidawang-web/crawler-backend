package com.dunwugudao.crawler.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 监控 / 种子 REST 服务启动类（独立端口 8081）。
 * <p>双数据源下不加 @MapperScan，由每个 Mapper 接口上的 @Mapper 注解自动发现。</p>
 */
@SpringBootApplication(scanBasePackages = "com.dunwugudao.crawler")
@EnableScheduling
public class CrawlerAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(CrawlerAdminApplication.class, args);
    }
}
