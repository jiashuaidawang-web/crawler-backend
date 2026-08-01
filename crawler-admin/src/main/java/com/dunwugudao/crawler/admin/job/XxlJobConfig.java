package com.dunwugudao.crawler.admin.job;

import com.xxl.job.core.executor.XxlJobExecutor;
import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class XxlJobConfig {

    @Value("${xxl.job.admin.addresses}")
    private String adminAddresses;

    @Value("${xxl.job.access.token:}")
    private String accessToken;

    @Value("${xxl.job.executor.appname}")
    private String appname;

    @Value("${xxl.job.executor.address:}")
    private String address;

    @Value("${xxl.job.executor.ip:}")
    private String ip;

    @Value("${xxl.job.executor.port}")
    private int port;

    @Value("${xxl.job.executor.logpath:./logs/xxl-job}")
    private String logPath;

    @Value("${xxl.job.executor.logretentiondays:30}")
    private int logRetentionDays;

    @Bean
    public XxlJobExecutor xxlJobExecutor() {
        XxlJobSpringExecutor exec = new XxlJobSpringExecutor();
        exec.setAdminAddresses(adminAddresses);
        exec.setAppname(appname);
        exec.setAddress(address);
        exec.setIp(ip);
        exec.setPort(port);
        exec.setAccessToken(accessToken);
        exec.setLogPath(logPath);
        exec.setLogRetentionDays(logRetentionDays);
        return exec;
    }
}
