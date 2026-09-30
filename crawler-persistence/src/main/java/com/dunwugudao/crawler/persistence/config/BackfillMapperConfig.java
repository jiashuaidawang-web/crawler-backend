package com.dunwugudao.crawler.persistence.config;

import com.dunwugudao.crawler.persistence.mapper.StockTradeCalendarMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 回填专用 Mapper 注册（独立于 DataSourceConfig，避免改动现有配置）。
 * <p>注册 StockTradeCalendarMapper 到 ClickHouse SqlSessionFactory。</p>
 */
@Configuration
public class BackfillMapperConfig {

    @Bean
    public MapperFactoryBean<StockTradeCalendarMapper> stockTradeCalendarMapper(
            @Qualifier("chSqlSessionFactory") SqlSessionFactory factory) throws Exception {
        MapperFactoryBean<StockTradeCalendarMapper> fb = new MapperFactoryBean<>(StockTradeCalendarMapper.class);
        fb.setSqlSessionFactory(factory);
        return fb;
    }
}
