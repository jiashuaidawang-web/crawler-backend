# Phase 1 验证说明(编排骨架 + STOCK_DAILY 单阶段)

## 本次新增

### 文件
- `schema-pipeline.sql` —— 新建 2 张表 `pipeline_run` / `pipeline_stage`(openGauss)
- `crawler-persistence/.../entity/PipelineRun.java`、`PipelineStageRecord.java` —— 实体
- `crawler-persistence/.../mapper/PipelineMapper.java` —— 编排状态 Mapper
- `crawler-persistence/.../config/DataSourceConfig.java` —— 注册 `pipelineMapper` Bean
- `crawler-persistence/.../mapper/CrawlTaskMapper.java` —— 新增 `countByStatusLike` / `countAllLike`(完成探测)
- `crawler-admin/.../pipeline/` —— `PipelineStage`(枚举)、`FailurePolicy`、`SeedResult`、`ValidateResult`、`ValidateContext`、`PipelineValidator`、`TotalCountValidator`、`StageSeeder`、`DailyPipelineOrchestrator`、`PipelineStageResult`、`PipelineRunResult`
- `crawler-admin/.../controller/PipelineController.java` —— REST

### 核心设计
- **校验真值 = 上游总数**:STOCK_DAILY 用 `data.total`,LIMIT/POOL 用 `data.tc`,DRAGON_TIGER 用 `result.count`(实测),INDEX_DAILY/kamt 用 `data.total`/数组 size
- **去重判定**:`actual < total` 时查 CK 按自然键 GROUP BY HAVING count>1 得重复组数,差值能被重复解释=成功,否则真丢失
- **完成探测**:按 `task_type IN (...) AND unique_key LIKE 'TYPE|source|date%'` 聚合 `status IN ('PENDING','RETRY')`(因 crawl_task 无 trade_date 列)
- **Phase 1 仅接入 STOCK_DAILY**,验证编排骨架;其余 11 阶段 Phase 2 接入

---

## 启动前必须做(按顺序)

### 1. 建表(线上 openGauss 执行)
```bash
psql -h <host> -U <user> -d <db> -f schema-pipeline.sql
```
建完后确认:
```sql
\d pipeline_run
\d pipeline_stage
-- 确认 pipeline_run 有 UNIQUE(run_date),pipeline_stage 有 pipeline_stage_stage_id_seq 序列
```

### 2. 配置(可选,有默认值)
`application.yml` 的 `crawler.pipeline.*`(缺省已可用):
```yaml
crawler:
  pipeline:
    source: 1
    await-timeout-min: 60     # 单阶段等待超时
    poll-interval-sec: 30     # 轮询间隔
```

### 3. 启动 admin,确认无报错
重点看启动日志无 `PipelineMapper` / `TotalCountValidator` 注入失败。

---

## 端到端验证步骤

### 验证 A:跑批骨架 + STOCK_DAILY 总量校验
```bash
# 跑某天(用已有数据的日,如 2026-08-14)
curl -X POST "http://localhost:8081/api/job/pipeline/daily?date=2026-08-14"
# 应返回 JSON:status=RUNNING 或 SUCCESS,stages=[{stage=STOCK_DAILY,status,...}]
```
观察:
1. `pipeline_run` 写入一条 RUNNING→SUCCESS
2. `pipeline_stage` 写入 STOCK_DAILY 一行,`seeded_count`≈56,`expected_total`≈5600(近似)
3. 等待 worker 消费完成(轮询 30s 一次,日志 `[pipeline] date=... stage=... 等待完成,剩余 PENDING/RETRY=...`)
4. 完成后 `pipeline_stage.status` = DONE(若 actual≈total) 或 FAILED(若真丢失)

查状态:
```bash
curl "http://localhost:8081/api/job/pipeline/status?date=2026-08-14"
```

### 验证 B:幂等(重复调)
```bash
curl -X POST "http://localhost:8081/api/job/pipeline/daily?date=2026-08-14"
# 第二次应直接返回 status=SUCCESS(不会重复发种子)
```

### 验证 C:断点续跑
```bash
curl -X POST "http://localhost:8081/api/job/pipeline/resume?date=2026-08-14"
# 若 STOCK_DAILY 已 DONE,直接返回;若有未完成阶段,从该阶段继续
```

### 验证 D:校验逻辑(重点)

重跑(任务已存在,验证"inserted=0 但 expectedTotal 正确"的场景):
```bash
curl -X POST "http://localhost:8081/api/job/pipeline/daily?date=2026-08-14"
```
应看到 `expected_total`=5549(上游真实总数),`seeded_count`=0(幂等跳过),`actual_total`=CK 实际行数。
若 08-13 STOCK_DAILY 完整,`status`=DONE,`dup_rows`=重复组数,`lost_rows`=0。

若想看"真丢失"路径,可手动删几条 CK 的 stock_daily 数据再跑。

---

## 已知近似/待 Phase 2 修正

1. **STOCK_DAILY expectedTotal 用近似值** = `inserted * 100`(页大小)。最后一页可能不满 100,所以 expected 略偏高。Phase 2 改 `SeedGenerator.seedStockDailyPages` 直接返回真实的 `total`(它已探测到)。
2. **Phase 1 仅 STOCK_DAILY**。其余 11 阶段(LIMIT_POOL/REGION/INDUSTRY/CONCEPT/MAIN_FUND/NORTHBOUND/INDEX/DRAGON_TIGER/DETAIL)在 Phase 2 接入——每个需改对应 `seed*` 方法返回 `SeedResult`(带回上游总数)。
3. **前端重试/忽略闭环** Phase 2 实现。
4. **可视化前端** Phase 3。

---

## 风险与回滚
- 新增表 + 新增 mapper,不改现有表结构,不回写现有数据。
- 若编排有问题,现有 `dailySeed` / JobController / 手动 curl 完全不受影响(编排是增量新增)。
- 回滚:停掉 `/api/job/pipeline/*` 调用即可,删 `schema-pipeline.sql` 建的表。
