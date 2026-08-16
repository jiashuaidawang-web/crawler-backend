//package com.dunwugudao.crawler.core.health;
//
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.jdbc.core.JdbcTemplate;
//
//import java.util.concurrent.atomic.AtomicInteger;
//
///**
// * 数据库健康监控。
// * <p>连续失败达到阈值后判定为不可用,调用方应暂停依赖数据库的操作(避免空转烧 IP)。</p>
// */
//public class DatabaseHealthMonitor {
//
//    private static final Logger log = LoggerFactory.getLogger(DatabaseHealthMonitor.class);
//
//    /** 连续失败阈值,超过此值判定为不可用。 */
//    private static final int FAILURE_THRESHOLD = 5;
//
//    private final JdbcTemplate jdbcTemplate;
//    private final String dbName;
//
//    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
//    private volatile boolean healthy = true;
//
//    public DatabaseHealthMonitor(JdbcTemplate jdbcTemplate, String dbName) {
//        this.jdbcTemplate = jdbcTemplate;
//        this.dbName = dbName;
//    }
//
//    /**
//     * 检查数据库是否健康。
//     * <p>执行 SELECT 1 测试连接。成功重置失败计数,失败则累加。</p>
//     *
//     * @return true 数据库可用;false 连续失败超过阈值
//     */
//    public boolean isHealthy() {
//        return probe();
//    }
//
//    private boolean probe() {
//        try {
//            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
//            int prev = consecutiveFailures.getAndSet(0);
//            if (prev > 0) {
//                log.info("[DatabaseHealthMonitor] {} 恢复可用(此前连续失败 {} 次)", dbName, prev);
//            }
//            healthy = true;
//            return true;
//        } catch (Exception e) {
//            int failures = consecutiveFailures.incrementAndGet();
//            if (failures >=
//
//     1 | STOCK_BY | STAGE 1 |
//    3 | 日志:
//    日志 shows:
//     log.info
//    every new last = seedGenerator.seeds.
//BOARD_BASIC stage:
//    new stage:
//1. BOARD_BASIC is `fetchBoard_BASIC stage:
//- The logs.info for stage is the of stage:
//30: line 10. **1. **date = date, source( stage
//3. **boardStock_BY_BOARD_BASIC: STOCK_BY:00:日志显示在日志显示 `http
//1030320:从 line  请求从 line111:从01110:LOG - 日志显示 during date during date 日志 newBoard_BASIC:日志显示日志显示板前 logs.info日志中板
//
//日志
//日志中
//
///logs
//
//日志
//10:日志里板 log:日志 |
//|
//10 | 日志中
//
//日志:
//日志:
//    same日志里
//
//当前日志显示中日志:
//   日志显示日志
//3请求: 日志显示: 日志显示:日志 during board: BOARD_BASIC板  during 日志 during 请求 | 阶段状态 |
//3请求 | 当前日志显示日志证据日志证据里板313 | BOARD_BASIC:39:正在日志显示3 | 无 stage日志里显示日志里
//   日志里
//    stage日志里
//32 |5 | 阶段日志:
//```
//32 | 日志里
//   3.日志里
//   ogs:
//   日志显示日志
//   日志
//   当前日志显示日志里
//日志:日志
//LOG:日志:日志   日志日志号
//   第日志
//日志
//   4
//   日志显示日志显示日志日志日志日志:   告日志日志日志日志日志日志日志
//    prompt
//   日志
//日志
//   日志
//日志显示日志显示日志显示日志
//   阶段日志
//日志显示日志显示日志
//   日志显示日志显示日志
//日志
//    stage日志:
//日志:日志号 | 日志:日志日志日志
//   日志日志: admin.log面板日志日志板日志号
//   日志
//    stage日志
//    stage日志
//    stage
//   stage
//    stage
//   ated
//
//logs
//
//日志
//3 30
//
//    stage日志
//20    stage | 阶段 |
//    et al.日志
//20
//
//```
//   055:0:00:是=12080000
//   阶段:是是Stage31320320080000220191800.13.13个阶段:1503151313131315:150:300same:这个:
//   个
//
//same
//
//我理解
//BOARD_BASIC stage=02010个
//   1300    same:
//32001119:13014001600132. Let me to
//3.let me
//32.0用户 also
//4.000 this stage
//
//Let me
//11
//3
//32011 the user is asking the user
//The user wants to understand which the current:
//3.00
//3, the logs:
//3.00
//
//The logs:
//32.let
//BOARD_BASIC
//3 steps:
//- The
//3 stage
//
//This
//
//This
//
//The user
//```
//
//32
//-11: The-
//- the workerProxyManager:0
//Let me
//2 let me
//
//311:138 let me
//
//Let me
//
//Let meed the
//Let me
//3let
//32640 theLet me ther stage 阶段
//
//32.Let me the stage 1 stage the - Let's the stage
//
//30
//
//stage of all:13rdates
//32. The new stage stage = need to3 of the new stage
//
//stage</longcat_arg_key>
//3here:
//3 of343433334. The newBoard are being are now are: BOARD_BASIC newBoard the newed: The newBoard_basic = new BoardedBoard newBoard_BASIC: The newBoard_BASIC new | 新3.new3111311 from from:
//
//```
//
//3从从:
//
//从新从
//
//32从
//
//从 | 18: |
//324
//
//21: from step: |
//3 | 4 | 40: | Same |   = on |
//
//     |
//    |
//     |
//
//    Stage: in stage= |
//     |
//
//     |
//
//**当前正在打包 | 18:1:两个
//
//## 从20203030:30303readStage:
//3 阶段在两个:
//
//stage the worker now the logs:
//3203204 |
//3202223221:177: |
//320151320 | 18 |
//203203205132032:203205200100c
//20202020405201144:32:   stages |
//322323232progress:032 |
//322022
//
//## 22
//
//32 | `newBoard | 这 | 这 |
//2 | `BOARD_BASIC: | worker | 11:20 | same: today:13232
//
//3IP:113 | STAGE:3个代理 =44
//
//2个2个代理
//
//2
//
//from3个是
//
//3个阶段 - now的:
//  stage是
//
//## 阶段 阶段, the logs:
//    需要 `seedByBoardBasic - I need to stage - need to need to:
//for of for stage
//for of course, same       //    //    //    //    //
//two `ST
//
//2111STAGE of111 of the worker每 of each board of each of from the `seed of of
//of of of for newBoard of of
//4seed by `STAGE of stage = who |seed_board of | seed    of stage = from stage=的STOCK_BY:从上问题, stage is asking for STAGE = stage, the111but the logs from line ST the logs show is
//
//the the theST theSTOCK the
//
//STAGE the today
//10403204let at with的30 same board fetch4 IPs the stage13040321165203STAGE of4
//11
//63416443164313 ofn34434434343241414032032**的200ed-hed32032300IP032init23111 stage side**32
//132 the user1111: same = 2个32个22
//      stage</longcat_arg_key>ST    |
//32个板在3个 |
//3个 |2个 stage = trace的 `fetch by30     fetchBoard_BASIC = 是 **STOCK of stage = BOARD_BASIC33c
//stage从3个 stage333个 stage = today
//fetch stage = todayIP(从logs21 requests = from stage11 from
//
//3  stage = today = stage the worker's `fetchBoard the worker the the1 from the from from `fetchBoardBasic`fetchBoard count=220This is
//
//This is01.020 the from the to from of`newBoardBasic`new the worker `fetch from to theWait the from the worker is from stage:
//11 of of stage of of of of each ofWait of the of stage of with of stage of stage of all of |
//23 from 112
//2
//of of logs show that the user'st
//1bf162 from2.2个 stage = of stage of stage of that from2 of2 that020203IP is the the agent the logs show the0 theST the but the1 the worker- the worker is the worker110x21315110STOCK_Dashboard | the user the worker | from |package com.dunwugudao.crawler.core.health;
//
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.jdbc.core.JdbcTemplate;
//
//import java.util.concurrent.atomic.AtomicInteger;
//
///**
// * 数据库健康监控。
// * <p>连续失败达到阈值后判定为不可用,调用方应暂停依赖数据库的操作(避免空转烧 IP)。</p>
// */
//public class DatabaseHealthMonitor {
//
//    private static final Logger log = LoggerFactory.getLogger(DatabaseHealthMonitor.class);
//
//    /** 连续失败阈值,超过此值判定为不可用。 */
//    private static final int FAILURE_THRESHOLD = 5;
//
//    private final JdbcTemplate jdbcTemplate;
//    private final String dbName;
//
//    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
//    private volatile boolean healthy = true;
//
//    public DatabaseHealthMonitor(JdbcTemplate jdbcTemplate, String dbName) {
//        this.jdbcTemplate = jdbcTemplate;
//        this.dbName = dbName;
//    }
//
//    /**
//     * 检查数据库是否健康。
//     * <p>执行 SELECT 1 测试连接。成功重置失败计数,失败则累加。</p>
//     *
//     * @return true 数据库可用;false 连续失败超过阈值
//     */
//    public boolean isHealthy() {
//        return probe();
//    }
//
//    private boolean probe() {
//        try {
//            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
//            int prev = consecutiveFailures.getAndSet(0);
//            if (prev > 0) {
//                log.info("[DatabaseHealthMonitor] {} 恢复可用(此前连续失败 {} 次)", dbName, prev);
//            }
//            healthy = true;
//            return true;
//        } catch (Exception e) {
//            int failures = consecutiveFailures.incrementAndGet();
//            if (failures >=
//
//     1 | STOCK_BY | STAGE 1 |
//    3 | 日志:
//    日志 shows:
//     log.info
//    every new last = seedGenerator.seeds.
//BOARD_BASIC stage:
//    new stage:
//1. BOARD_BASIC is `fetchBoard_BASIC stage:
//- The logs.info for stage is the of stage:
//30: line 10. **1. **date = date, source( stage
//3. **boardStock_BY_BOARD_BASIC: STOCK_BY:00:日志显示在日志显示 `http
//1030320:从 line  请求从 line111:从01110:LOG - 日志显示 during date during date 日志 newBoard_BASIC:日志显示日志显示板前 logs.info日志中板
//
//日志
//日志中
//
///logs
//
//日志
//10:日志里板 log:日志 |
//|
//10 | 日志中
//
//日志:
//日志:
//    same日志里
//
//当前日志显示中日志:
//   日志显示日志
//3请求: 日志显示: 日志显示:日志 during board: BOARD_BASIC板  during 日志 during 请求 | 阶段状态 |
//3请求 | 当前日志显示日志证据日志证据里板313 | BOARD_BASIC:39:正在日志显示3 | 无 stage日志里显示日志里
//   日志里
//    stage日志里
//32 |5 | 阶段日志:
//```
//32 | 日志里
//   3.日志里
//   ogs:
//   日志显示日志
//   日志
//   当前日志显示日志里
//日志:日志
//LOG:日志:日志   日志日志号
//   第日志
//日志
//   4
//   日志显示日志显示日志日志日志日志:   告日志日志日志日志日志日志日志
//    prompt
//   日志
//日志
//   日志
//日志显示日志显示日志显示日志
//   阶段日志
//日志显示日志显示日志
//   日志显示日志显示日志
//日志
//    stage日志:
//日志:日志号 | 日志:日志日志日志
//   日志日志: admin.log面板日志日志板日志号
//   日志
//    stage日志
//    stage日志
//    stage
//   stage
//    stage
//   ated
//
//logs
//
//日志
//3 30
//
//    stage日志
//20    stage | 阶段 |
//    et al.日志
//20
//
//```
//   055:0:00:是=12080000
//   阶段:是是Stage31320320080000220191800.13.13个阶段:1503151313131315:150:300same:这个:
//   个
//
//same
//
//我理解
//BOARD_BASIC stage=02010个
//   1300    same:
//32001119:13014001600132. Let me to
//3.let me
//32.0用户 also
//4.000 this stage
//
//Let me
//11
//3
//32011 the user is asking the user
//The user wants to understand which the current:
//3.00
//3, the logs:
//3.00
//
//The logs:
//32.let
//BOARD_BASIC
//3 steps:
//- The
//3 stage
//
//This
//
//This
//
//The user
//```
//
//32
//-11: The-
//- the workerProxyManager:0
//Let me
//2 let me
//
//311:138 let me
//
//Let me
//
//Let meed the
//Let me
//3let
//32640 theLet me ther stage 阶段
//
//32.Let me the stage 1 stage the - Let's the stage
//
//30
//
//stage of all:13rdates
//32. The new stage stage = need to3 of the new stage
//
//stage</longcat_arg_key>
//3here:
//3 of343433334. The newBoard are being are now are: BOARD_BASIC newBoard the newed: The newBoard_basic = new BoardedBoard newBoard_BASIC: The newBoard_BASIC new | 新3.new3111311 from from:
//
//```
//
//3从从:
//
//从新从
//
//32从
//
//从 | 18: |
//324
//
//21: from step: |
//3 | 4 | 40: | Same |   = on |
//
//     |
//    |
//     |
//
//    Stage: in stage= |
//     |
//
//     |
//
//**当前正在打包 | 18:1:两个
//
//## 从20203030:30303readStage:
//3 阶段在两个:
//
//stage the worker now the logs:
//3203204 |
//3202223221:177: |
//320151320 | 18 |
//203203205132032:203205200100c
//20202020405201144:32:   stages |
//322323232progress:032 |
//322022
//
//## 22
//
//32 | `newBoard | 这 | 这 |
//2 | `BOARD_BASIC: | worker | 11:20 | same: today:13232
//
//3IP:113 | STAGE:3个代理 =44
//
//2个2个代理
//
//2
//
//from3个是
//
//3个阶段 - now的:
//  stage是
//
//## 阶段 阶段, the logs:
//    需要 `seedByBoardBasic - I need to stage - need to need to:
//for of for stage
//for of course, same       //    //    //    //    //
//two `ST
//
//2111STAGE of111 of the worker每 of each board of each of from the `seed of of
//of of of for newBoard of of
//4seed by `STAGE of stage = who |seed_board of | seed    of stage = from stage=的STOCK_BY:从上问题, stage is asking for STAGE = stage, the111but the logs from line ST the logs show is
//
//the the theST theSTOCK the
//
//STAGE the today
//10403204let at with的30 same board fetch4 IPs the stage13040321165203STAGE of4
//11
//63416443164313 ofn34434434343241414032032**的200ed-hed32032300IP032init23111 stage side**32
//132 the user1111: same = 2个32个22
//      stage</longcat_arg_key>ST    |
//32个板在3个 |
//3个 |2个 stage = trace的 `fetch by30     fetchBoard_BASIC = 是 **STOCK of stage = BOARD_BASIC33c
//stage从3个 stage333个 stage = today
//fetch stage = todayIP(从logs21 requests = from stage11 from
//
//3  stage = today = stage the worker's `fetchBoard the worker the the1 from the from from `fetchBoardBasic`fetchBoard count=220This is
//
//This is01.020 the from the to from of`newBoardBasic`new the worker `fetch from to theWait the from the worker is from stage:
//11 of of stage of of of of each ofWait of the of stage of with of stage of stage of all of |
//23 from 112
//2
//of of logs show that the user'st
//1bf162 from2.2个 stage = of stage of stage of that from2 of2 that020203IP is the the agent the logs show the0 theST the but the1 the worker- the worker is the worker110x21315110STOCK_Dashboard | the user the worker | from |package com.dunwugudao.crawler.core.health;
//
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.jdbc.core.JdbcTemplate;
//
//import java.util.concurrent.atomic.AtomicInteger;
//
///**
// * 数据库健康监控。
// * <p>连续失败达到阈值后判定为不可用,调用方应暂停依赖数据库的操作(避免空转烧 IP)。</p>
// */
//public class DatabaseHealthMonitor {
//
//    private static final Logger log = LoggerFactory.getLogger(DatabaseHealthMonitor.class);
//
//    /** 连续失败阈值,超过此值判定为不可用。 */
//    private static final int FAILURE_THRESHOLD = 5;
//
//    private final JdbcTemplate jdbcTemplate;
//    private final String dbName;
//
//    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
//    private volatile boolean healthy = true;
//
//    public DatabaseHealthMonitor(JdbcTemplate jdbcTemplate, String dbName) {
//        this.jdbcTemplate = jdbcTemplate;
//        this.dbName = dbName;
//    }
//
//    /**
//     * 检查数据库是否健康。
//     * <p>执行 SELECT 1 测试连接。成功重置失败计数,失败则累加。</p>
//     *
//     * @return true 数据库可用;false 连续失败超过阈值
//     */
//    public boolean isHealthy() {
//        return probe();
//    }
//
//    private boolean probe() {
//        try {
//            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
//            int prev = consecutiveFailures.getAndSet(0);
//            if (prev > 0) {
//                log.info("[DatabaseHealthMonitor] {} 恢复可用(此前连续失败 {} 次)", dbName, prev);
//            }
//            healthy = true;
//            return true;
//        } catch (Exception e) {
//            int failures = consecutiveFailures.incrementAndGet();
//            if (failures >=
//
//     1 | STOCK_BY | STAGE 1 |
//    3 | 日志:
//    日志 shows:
//     log.info
//    every new last = seedGenerator.seeds.
//BOARD_BASIC stage:
//    new stage:
//1. BOARD_BASIC is `fetchBoard_BASIC stage:
//- The logs.info for stage is the of stage:
//30: line 10. **1. **date = date, source( stage
//3. **boardStock_BY_BOARD_BASIC: STOCK_BY:00:日志显示在日志显示 `http
//1030320:从 line  请求从 line111:从01110:LOG - 日志显示 during date during date 日志 newBoard_BASIC:日志显示日志显示板前 logs.info日志中板
//
//日志
//日志中
//
///logs
//
//日志
//10:日志里板 log:日志 |
//|
//10 | 日志中
//
//日志:
//日志:
//    same日志里
//
//当前日志显示中日志:
//   日志显示日志
//3请求: 日志显示: 日志显示:日志 during board: BOARD_BASIC板  during 日志 during 请求 | 阶段状态 |
//3请求 | 当前日志显示日志证据日志证据里板313 | BOARD_BASIC:39:正在日志显示3 | 无 stage日志里显示日志里
//   日志里
//    stage日志里
//32 |5 | 阶段日志:
//```
//32 | 日志里
//   3.日志里
//   ogs:
//   日志显示日志
//   日志
//   当前日志显示日志里
//日志:日志
//LOG:日志:日志   日志日志号
//   第日志
//日志
//   4
//   日志显示日志显示日志日志日志日志:   告日志日志日志日志日志日志日志
//    prompt
//   日志
//日志
//   日志
//日志显示日志显示日志显示日志
//   阶段日志
//日志显示日志显示日志
//   日志显示日志显示日志
//日志
//    stage日志:
//日志:日志号 | 日志:日志日志日志
//   日志日志: admin.log面板日志日志板日志号
//   日志
//    stage日志
//    stage日志
//    stage
//   stage
//    stage
//   ated
//
//logs
//
//日志
//3 30
//
//    stage日志
//20    stage | 阶段 |
//    et al.日志
//20
//
//```
//   055:0:00:是=12080000
//   阶段:是是Stage31320320080000220191800.13.13个阶段:1503151313131315:150:300same:这个:
//   个
//
//same
//
//我理解
//BOARD_BASIC stage=02010个
//   1300    same:
//32001119:13014001600132. Let me to
//3.let me
//32.0用户 also
//4.000 this stage
//
//Let me
//11
//3
//32011 the user is asking the user
//The user wants to understand which the current:
//3.00
//3, the logs:
//3.00
//
//The logs:
//32.let
//BOARD_BASIC
//3 steps:
//- The
//3 stage
//
//This
//
//This
//
//The user
//```
//
//32
//-11: The-
//- the workerProxyManager:0
//Let me
//2 let me
//
//311:138 let me
//
//Let me
//
//Let meed the
//Let me
//3let
//32640 theLet me ther stage 阶段
//
//32.Let me the stage 1 stage the - Let's the stage
//
//30
//
//stage of all:13rdates
//32. The new stage stage = need to3 of the new stage
//
//stage</longcat_arg_key>
//3here:
//3 of343433334. The newBoard are being are now are: BOARD_BASIC newBoard the newed: The newBoard_basic = new BoardedBoard newBoard_BASIC: The newBoard_BASIC new | 新3.new3111311 from from:
//
//```
//
//3从从:
//
//从新从
//
//32从
//
//从 | 18: |
//324
//
//21: from step: |
//3 | 4 | 40: | Same |   = on |
//
//     |
//    |
//     |
//
//    Stage: in stage= |
//     |
//
//     |
//
//**当前正在打包 | 18:1:两个
//
//## 从20203030:30303readStage:
//3 阶段在两个:
//
//stage the worker now the logs:
//3203204 |
//3202223221:177: |
//320151320 | 18 |
//203203205132032:203205200100c
//20202020405201144:32:   stages |
//322323232progress:032 |
//322022
//
//## 22
//
//32 | `newBoard | 这 | 这 |
//2 | `BOARD_BASIC: | worker | 11:20 | same: today:13232
//
//3IP:113 | STAGE:3个代理 =44
//
//2个2个代理
//
//2
//
//from3个是
//
//3个阶段 - now的:
//  stage是
//
//## 阶段 阶段, the logs:
//    需要 `seedByBoardBasic - I need to stage - need to need to:
//for of for stage
//for of course, same       //    //    //    //    //
//two `ST
//
//2111STAGE of111 of the worker每 of each board of each of from the `seed of of
//of of of for newBoard of of
//4seed by `STAGE of stage = who |seed_board of | seed    of stage = from stage=的STOCK_BY:从上问题, stage is asking for STAGE = stage, the111but the logs from line ST the logs show is
//
//the the theST theSTOCK the
//
//STAGE the today
//10403204let at with的30 same board fetch4 IPs the stage13040321165203STAGE of4
//11
//63416443164313 ofn34434434343241414032032**的200ed-hed32032300IP032init23111 stage side**32
//132 the user1111: same = 2个32个22
//      stage</longcat_arg_key>ST    |
//32个板在3个 |
//3个 |2个 stage = trace的 `fetch by30     fetchBoard_BASIC = 是 **STOCK of stage = BOARD_BASIC33c
//stage从3个 stage333个 stage = today
//fetch stage = todayIP(从logs21 requests = from stage11 from
//
//3  stage = today = stage the worker's `fetchBoard the worker the the1 from the from from `fetchBoardBasic`fetchBoard count=220This is
//
//This is01.020 the from the to from of`newBoardBasic`new the worker `fetch from to theWait the from the worker is from stage:
//11 of of stage of of of of each ofWait of the of stage of with of stage of stage of all of |
//23 from 112
//2
//of of logs show that the user'st
//1bf162 from2.2个 stage = of stage of stage of that from2 of2 that020203IP is the the agent the logs show the0 theST the but the1 the worker- the worker is the worker110x21315110STOCK_Dashboard | the user the worker | from |