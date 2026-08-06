package com.dunwugudao.crawler.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 爬虫节点进程启动类。
 * <p>扫描 com.dunwugudao.crawler 下所有模块。
 * 双数据源（openGauss + ClickHouse）下不加 @MapperScan，
 * 由每个 Mapper 接口上的 @Mapper 注解 + mybatis-spring-boot-starter 自动发现，
 * 通过 @Qualifier 注入对应 SqlSessionTemplate。</p>
 */
@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.dunwugudao.crawler")
public class CrawlerWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CrawlerWorkerApplication.class, args);
    }
}
