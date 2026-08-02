package com.dunwugudao.crawler.worker;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 爬虫节点进程启动类。
 * <p>扫描 com.dunwugudao.crawler 下所有模块（core/strategy/persistence 的 @Service、@Component），
 * 并注册 persistence 的 Mapper。@EnableScheduling 启用 ClaimLoop / 心跳定时任务。</p>
 */
@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.dunwugudao.crawler")
@MapperScan("com.dunwugudao.crawler.persistence.mapper")
public class CrawlerWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CrawlerWorkerApplication.class, args);
    }
}
