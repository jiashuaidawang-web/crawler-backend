package com.dunwugudao.crawler.persistence.config;

import com.dunwugudao.crawler.core.model.SourceType;
import com.dunwugudao.crawler.persistence.mapper.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 双数据源配置。
 *   - pg（openGauss）：复用 Spring Boot 自动配置的 DataSource（spring.datasource.*），
 *     行为与单数据源时完全一致，零改造。
 *   - ch（ClickHouse）：手动构建 HikariDataSource。
 */
@Configuration
@Import(DataSourceAutoConfiguration.class)  // 确保 Spring Boot 自动配置 DataSource
public class DataSourceConfig {

    // ==================== openGauss（主库，操作型）—— 复用 Spring Boot 自动配置 ====================

    /**
     * pg SqlSessionFactory：使用 Spring Boot 自动配置的 DataSource。
     * 由于 pg 用默认 spring.datasource.* 前缀，Spring Boot 会自动创建 DataSource bean，
     * 这里直接注入即可。
     */
    @Primary
    @Bean(name = "pgSqlSessionFactory")
    public SqlSessionFactory pgSqlSessionFactory(
            @Qualifier("pgDataSource") DataSource pgDs) throws Exception {
        SqlSessionFactoryBean fb = new SqlSessionFactoryBean();
        fb.setDataSource(pgDs);
        fb.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath:mapper/*.xml"));
        org.apache.ibatis.session.Configuration conf = new org.apache.ibatis.session.Configuration();
        conf.setMapUnderscoreToCamelCase(true);
        conf.getTypeHandlerRegistry().register(SourceType.class, new SourceTypeTypeHandler());
        fb.setConfiguration(conf);
        return fb.getObject();
    }

    /**
     * pg DataSource：复用 Spring Boot 自动配置。
     * 通过 @ConfigurationProperties 绑定 spring.datasource.* 并初始化 HikariDataSource。
     */
    @Primary
    @Bean(name = "pgDataSource")
    @ConfigurationProperties(prefix = "spring.datasource")
    public DataSource pgDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Primary
    @Bean(name = "pgSqlSessionTemplate")
    public SqlSessionTemplate pgSqlSessionTemplate(
            @Qualifier("pgSqlSessionFactory") SqlSessionFactory f) {
        return new SqlSessionTemplate(f);
    }

    @Primary
    @Bean(name = "pgJdbcTemplate")
    public JdbcTemplate pgJdbcTemplate(@Qualifier("pgDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    // ==================== ClickHouse（分析型） ====================

    @Bean(name = "chDataSource", destroyMethod = "close")
    public DataSource chDataSource(
            @Value("${spring.datasource.ch.jdbc-url}") String url,
            @Value("${spring.datasource.ch.username}") String username,
            @Value("${spring.datasource.ch.password}") String password) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(username);
        cfg.setPassword(password);
        cfg.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
        cfg.setMaximumPoolSize(5);
        cfg.setConnectionTimeout(10000);
        return new HikariDataSource(cfg);
    }

    @Bean(name = "chSqlSessionFactory")
    public SqlSessionFactory chSqlSessionFactory(@Qualifier("chDataSource") DataSource ds) throws Exception {
        SqlSessionFactoryBean fb = new SqlSessionFactoryBean();
        fb.setDataSource(ds);
        fb.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath:mapper/*.xml"));
        org.apache.ibatis.session.Configuration conf = new org.apache.ibatis.session.Configuration();
        conf.setMapUnderscoreToCamelCase(true);
        conf.setDefaultExecutorType(org.apache.ibatis.session.ExecutorType.SIMPLE);
        conf.getTypeHandlerRegistry().register(SourceType.class, new SourceTypeTypeHandler());
        fb.setConfiguration(conf);
        return fb.getObject();
    }

    @Bean(name = "chSqlSessionTemplate")
    public SqlSessionTemplate chSqlSessionTemplate(
            @Qualifier("chSqlSessionFactory") SqlSessionFactory f) {
        return new SqlSessionTemplate(f);
    }

    @Bean(name = "chJdbcTemplate")
    public JdbcTemplate chJdbcTemplate(@Qualifier("chDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    // ==================== openGauss mapper beans（操作型） ====================

    @Bean public MapperFactoryBean<CrawlTaskMapper> crawlTaskMapper(@Qualifier("pgSqlSessionFactory") SqlSessionFactory f) throws Exception { return newMapper(f, CrawlTaskMapper.class); }
    @Bean public MapperFactoryBean<CrawlLogMapper> crawlLogMapper(@Qualifier("pgSqlSessionFactory") SqlSessionFactory f) throws Exception { return newMapper(f, CrawlLogMapper.class); }
    @Bean public MapperFactoryBean<CrawlAlertMapper> crawlAlertMapper(@Qualifier("pgSqlSessionFactory") SqlSessionFactory f) throws Exception { return newMapper(f, CrawlAlertMapper.class); }
    @Bean public MapperFactoryBean<CrawlNodeMapper> crawlNodeMapper(@Qualifier("pgSqlSessionFactory") SqlSessionFactory f) throws Exception { return newMapper(f, CrawlNodeMapper.class); }
    @Bean public MapperFactoryBean<TradeLogMapper> tradeLogMapper(@Qualifier("pgSqlSessionFactory") SqlSessionFactory f) throws Exception { return newMapper(f, TradeLogMapper.class); }
    @Bean public MapperFactoryBean<StockBackfillStatusMapper> stockBackfillStatusMapper(@Qualifier("pgSqlSessionFactory") SqlSessionFactory f) throws Exception { return newMapper(f, StockBackfillStatusMapper.class); }

    // ==================== ClickHouse mapper beans（分析型） ====================

    @Bean public MapperFactoryBean<StockDailyMapper> stockDailyMapper(@Qualifier("chSqlSessionFactory") SqlSessionFactory f) throws Exception { return newMapper(f, StockDailyMapper.class); }
    @Bean public MapperFactoryBean<StockWeeklyMapper> stockWeeklyMapper(@Qualifier("chSqlSessionFactory") SqlSessionFactory f) throws Exception { return newMapper(f, StockWeeklyMapper.class); }
    @Bean public MapperFactoryBean<StockKlineMinuteMapper> stockKlineMinuteMapper(@Qualifier("chSqlSessionFactory") SqlSessionFactory f) throws Exception { return newMapper(f, StockKlineMinuteMapper.class); }
    @Bean public MapperFactoryBean<IndexDailyMapper> indexDailyMapper(@Qualifier("chSqlSessionFactory") SqlSessionFactory f) throws Exception { return newMapper(f, IndexDailyMapper.class); }
    @Bean public MapperFactoryBean<BoardDailyMapper> boardDailyMapper(@Qualifier("chSqlSessionFactory") SqlSessionFactory f) throws Exception { return newMapper(f, BoardDailyMapper.class); }
    @Bean public MapperFactoryBean<BoardBasicMapper> boardBasicMapper(@Qualifier("chSqlSessionFactory") SqlSessionFactory f) throws Exception { return newMapper(f, BoardBasicMapper.class); }
    @Bean public MapperFactoryBean<LimitUpPoolMapper> limitUpPoolMapper(@Qualifier("chSqlSessionFactory") SqlSessionFactory f) throws Exception { return newMapper(f, LimitUpPoolMapper.class); }
    @Bean public MapperFactoryBean<LimitDownPoolMapper> limitDownPoolMapper(@Qualifier("chSqlSessionFactory") SqlSessionFactory f) throws Exception { return newMapper(f, LimitDownPoolMapper.class); }
    @Bean public MapperFactoryBean<ZhabanPoolMapper> zhabanPoolMapper(@Qualifier("chSqlSessionFactory") SqlSessionFactory f) throws Exception { return newMapper(f, ZhabanPoolMapper.class); }
    @Bean public MapperFactoryBean<StrongPoolMapper> strongPoolMapper(@Qualifier("chSqlSessionFactory") SqlSessionFactory f) throws Exception { return newMapper(f, StrongPoolMapper.class); }
    @Bean public MapperFactoryBean<CixinPoolMapper> cixinPoolMapper(@Qualifier("chSqlSessionFactory") SqlSessionFactory f) throws Exception { return newMapper(f, CixinPoolMapper.class); }
    @Bean public MapperFactoryBean<DragonTigerMapper> dragonTigerMapper(@Qualifier("chSqlSessionFactory") SqlSessionFactory f) throws Exception { return newMapper(f, DragonTigerMapper.class); }
    @Bean public MapperFactoryBean<DtDetailMapper> dtDetailMapper(@Qualifier("chSqlSessionFactory") SqlSessionFactory f) throws Exception { return newMapper(f, DtDetailMapper.class); }
    @Bean public MapperFactoryBean<MainFundFlowMapper> mainFundFlowMapper(@Qualifier("chSqlSessionFactory") SqlSessionFactory f) throws Exception { return newMapper(f, MainFundFlowMapper.class); }
    @Bean public MapperFactoryBean<StockBoardRelMapper> stockBoardRelMapper(@Qualifier("chSqlSessionFactory") SqlSessionFactory f) throws Exception { return newMapper(f, StockBoardRelMapper.class); }
    @Bean public MapperFactoryBean<ConceptMapper> conceptMapper(@Qualifier("chSqlSessionFactory") SqlSessionFactory f) throws Exception { return newMapper(f, ConceptMapper.class); }
    @Bean public MapperFactoryBean<FinancialMapper> financialMapper(@Qualifier("chSqlSessionFactory") SqlSessionFactory f) throws Exception { return newMapper(f, FinancialMapper.class); }
    @Bean public MapperFactoryBean<NewsEventMapper> newsEventMapper(@Qualifier("chSqlSessionFactory") SqlSessionFactory f) throws Exception { return newMapper(f, NewsEventMapper.class); }
    @Bean public MapperFactoryBean<NorthboundFlowMapper> northboundFlowMapper(@Qualifier("chSqlSessionFactory") SqlSessionFactory f) throws Exception { return newMapper(f, NorthboundFlowMapper.class); }
    @Bean public MapperFactoryBean<TradeCalendarMapper> tradeCalendarMapper(@Qualifier("chSqlSessionFactory") SqlSessionFactory f) throws Exception { return newMapper(f, TradeCalendarMapper.class); }
    @Bean public MapperFactoryBean<ThsPlateMapper> thsPlateMapper(@Qualifier("chSqlSessionFactory") SqlSessionFactory f) throws Exception { return newMapper(f, ThsPlateMapper.class); }

    @Bean public MapperFactoryBean<StockTaskConfigMapper> stockTaskConfigMapper(@Qualifier("chSqlSessionFactory") SqlSessionFactory f) throws Exception { return newMapper(f, StockTaskConfigMapper.class); }

    private <T> MapperFactoryBean<T> newMapper(SqlSessionFactory f, Class<T> type) throws Exception {
        MapperFactoryBean<T> fb = new MapperFactoryBean<>(type);
        fb.setSqlSessionFactory(f);
        return fb;
    }
}
