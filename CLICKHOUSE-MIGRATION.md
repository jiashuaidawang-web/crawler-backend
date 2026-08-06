# openGauss → ClickHouse 改造说明（2026-08-05）

## 1. 总体策略：双数据源（分析 + 操作分离）

ClickHouse **不适合** OLTP 工作负载（行级 UPDATE、行锁、RETURNING、事务）。
经代码审计，`crawl_task / crawl_log / crawl_alert / crawl_node / trade_log` 是纯操作型表，
**保留 openGauss**；其余 20 张分析型表迁 ClickHouse。

```
openGauss（主数据源 @Primary）    → crawl_task / crawl_log / crawl_alert / crawl_node / trade_log
ClickHouse（分析型数据源）        → stock_daily/weekly, index/board_daily, 池子×5,
                                   dragon_tiger, dt_detail, main_fund_flow, northbound_flow,
                                   stock_board_rel, board_basic, concept, financial,
                                   sentiment_daily, theme_factor_daily, trend_candidate_daily,
                                   four_dimension_daily, news_event
```

## 2. 去重范式变更（最大改造点）

| 原 openGauss 范式 | 新 ClickHouse 范式 |
|---|---|
| 逐行 `selectDataSource()` → 比优先级 → `updateRow()`/`insertIfAbsent()` | 无条件批量 `batchInsert()` |
| 依赖行锁 + 事务 + `WHERE NOT EXISTS` | 去重由表引擎 `ReplacingMergeTree(_ver=data_source)` 承接 |
| 写时裁决"高优先级覆盖" | 查询时 `FINAL` / `argMax` 取权威行 |

代价：同一自然键多源数据短期共存（合并异步），T+1 复盘场景可接受。

## 3. 引擎选择

- 只追加行情/池子/资金流/龙虎榜 → `MergeTree`
- 需要覆盖的维表 → `ReplacingMergeTree(_ver=data_source)`：
  `board_basic` / `concept` / `stock_board_rel`
- ORDER BY = 查询最高频过滤字段（ts_code+trade_date / board_code+trade_date / ...）
- PARTITION BY = `toYYYYMM(trade_date)`

## 4. 已改文件清单

### 新增
- `crawler-persistence/.../config/DataSourceConfig.java` —— 双数据源 + 双 SqlSessionFactory
- `crawler-persistence/.../config/SourceTypeTypeHandler.java` —— 替代 MP @EnumValue
- `crawler-persistence/.../service/ClickHouseBatchInserter.java` —— JDBC 批量写入工具
- `crawler-persistence/src/main/resources/clickhouse-schema.sql` —— CK 建表 DDL
- `crawler-persistence/src/main/resources/mapper/*.xml`（13 个）—— batchInsert 多行 VALUES

### 改造
- `pom.xml`（父）—— 依赖管理换 `mybatis-spring-boot-starter 3.0.3` + `clickhouse-jdbc 0.6.5:all`
- `crawler-persistence/pom.xml` —— 去 MP starter，加 mybatis-spring-boot + clickhouse-jdbc
- `crawler-worker/src/main/resources/application.yml` —— 双数据源配置（spring.datasource.pg / .ch）
- `crawler-admin/src/main/resources/application.yml` —— 同上
- 全部 `entity/*.java` —— 去 `@TableId/@TableName`，改纯 POJO
- `StockDailyMapper.java` + `.xml` —— 去 BaseMapper，改 batchInsert
- `BoardBasicMapper.java` —— 去 BaseMapper，改原生 SQL
- `StockBoardRelMapper.java` + `.xml` —— 改 batchInsert（ReplacingMergeTree 去重）
- 其他 11 个 `XxxMapper.java` + `.xml` —— 去 selectDataSource/updateRow/insertIfAbsent，改 batchInsert
- `DedupWriter.java` —— 全部 writer 改批量追加，去 `@Transactional`
- `BoardBasicSyncService.java` —— 去 QueryWrapper，改原生 SQL
- `ClaimService.java` —— 去 UpdateWrapper，改 JdbcTemplate 原生 SQL（crawl_task 留 openGauss）
- `VolumeValidator.java` —— 去 QueryWrapper，改 JdbcTemplate
- `SeedGenerator.java` —— 去 QueryWrapper，改原生 SQL
- `SourceType.java` —— 去 @EnumValue，改用 SourceTypeTypeHandler
- `CrawlerWorkerApplication.java` / `CrawlerAdminApplication.java` —— 去 @MapperScan，exclude MP auto-config

## 5. 关键 pom 依赖变更

```xml
<!-- 父 pom dependencyManagement -->
<mybatis-spring-boot.version>3.0.3</mybatis-spring-boot.version>
<clickhouse-jdbc.version>0.6.5</clickhouse-jdbc.version>
<!-- 新增 -->
<dependency>
  <groupId>org.mybatis.spring.boot</groupId>
  <artifactId>mybatis-spring-boot-starter</artifactId>
  <version>${mybatis-spring-boot.version}</version>
</dependency>
<dependency>
  <groupId>com.clickhouse</groupId>
  <artifactId>clickhouse-jdbc</artifactId>
  <version>${clickhouse-jdbc.version}</version>
  <classifier>all</classifier>
</dependency>

<!-- crawler-persistence/pom.xml：去 mybatis-plus-spring-boot3-starter，换上述两个 + 保留 postgresql -->
```

## 6. application.yml 数据源配置

```yaml
spring:
  datasource:
    pg:
      driver-class-name: org.postgresql.Driver
      url: jdbc:postgresql://127.0.0.1:15432/postgres
      username: dbuser
      password: OpenGauss@2026
    ch:
      driver-class-name: com.clickhouse.jdbc.ClickHouseDriver
      url: jdbc:clickhouse://127.0.0.1:8123/crawler
      username: default
      password: ""
      jdbc-url-parameters: compress=true&use_server_time_zone=false&rewrite_batch_inserts=true
```

## 7. 查询去重写法（替代原 selectDataSource）

```sql
-- 取某行合并后权威值（ReplacingMergeTree 去重后）
SELECT * FROM stock_daily FINAL WHERE ts_code = '600000.SH' AND trade_date = '2026-08-05' LIMIT 1;

-- 聚合查询取权威行（FINAL 在大表上慢，用 argMax 更高效）
SELECT argMax(*, data_source) FROM stock_daily
WHERE ts_code = '600000.SH' GROUP BY ts_code, trade_date;
```

## 8. 数据迁移方案（openGauss → ClickHouse）

推荐步骤：
1. 在 CK 跑 `clickhouse-schema.sql` 建库 `crawler` + 全部表
2. 用 `pg_dump --data-only --table=stock_daily ...` 导出每张表 CSV
3. 用 `clickhouse-client --query="INSERT INTO stock_daily FORMAT CSV"` 导入
4. 或写 Java 迁移任务：openGauss `select *` → `ClickHouseBatchInserter.batchInsert`
5. 校验行数：`select count(*) from stock_daily` 两边比对

注意：
- `NUMERIC(p,s)` → `ClickHouse Decimal(p,s)` 精度对应
- `BOOLEAN` → `UInt8`
- `BIGSERIAL` 列（id / event_id / alert_id / log_id / task_id）在 CK 表已去掉或改为应用生成
- 时间 `TIMESTAMP` → `DateTime`（精度秒）

## 9. openGauss → ClickHouse 坑点清单

| # | 坑 | 影响 | 解决 |
|---|---|---|---|
| 1 | CK 无行锁 / SKIP LOCKED | crawl_task 认领 | crawl_task 留 openGauss |
| 2 | CK 无事务 | @Transactional 全去掉 | DedupWriter 去 @Transactional |
| 3 | CK UPDATE 异步重写分区 | 行级 updateRow 不可用 | 改 ReplacingMergeTree 追加 |
| 4 | CK 无 RETURNING | claim() 返回被认领行 | claim() 留 openGauss |
| 5 | CK 无自增主键 | @TableId(AUTO) 失效 | entity 去 @TableId |
| 6 | CK 无 BOOLEAN | is_limit_up 等 | DDL 改 UInt8 |
| 7 | CK 无 VARCHAR 长度 | 全部改 String | DDL |
| 8 | CK 无 FK | stock_board_rel 唯一约束 | ReplacingMergeTree 替代 |
| 9 | CK 无 MP 方言 | 去 mybatis-plus-starter | 换 mybatis-spring-boot-starter |
| 10 | CK 无 @EnumValue | SourceType 枚举映射 | 自定义 SourceTypeTypeHandler |
| 11 | CK 单条 INSERT 极慢 | 写入性能 | 批量 ≥1000 行 |
| 12 | CK ReplacingMergeTree 合并不实时 | 短期多源共存 | 查询用 FINAL / argMax |
| 13 | @MapperScan 双数据源冲突 | 启动报错 | 去 @MapperScan，用 @Mapper |
| 14 | CK JDBC 0.6.x 批量语法差异 | foreach 批量 | 多行 VALUES + rewrite_batch_inserts=true |
