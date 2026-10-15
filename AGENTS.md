# AGENTS.md

减少常见 LLM 编码错误的行为准则。
根据需要与项目特定指令合并。

**权衡:**这些准则偏向谨慎而非速度。
对于简单任务,请自行判断。

## 1. 编码前先思考

**不要假设。不要隐藏困惑。把权衡摆出来。**

实现之前:

- 明确说出你的假设。如果不确定,就问。
- 如果存在多种理解方式,列出来。
- 如果有更简单的方案,说出来。
- 如果有什么不清楚,停下来。说明哪里困惑。

## 2. 简单优先

**能解决问题的最少代码。不要投机性功能。**

- 不要加没被要求的功能
- 单次使用的代码不要搞抽象
- 不要加没被要求的"灵活性"
- 不要为不可能的场景写错误处理
- 如果 200 行能缩成 50 行,就重写

## 3. 外科手术式修改

**只改必须改的。只清理自己弄乱的。**

- 不要"改进"相邻的代码或格式
- 不要重构没坏的东西
- 匹配现有风格,即使你不喜欢
- 如果发现死代码,提一句——但别删

## 4. 目标驱动执行

**定义成功标准。循环直到验证通过。**

把任务转化为可验证的目标:

- "加个验证" → "写测试,然后让测试通过"
- "修这个 bug" → "用测试复现,然后修复"
- "重构 X" → "确保重构前后测试都通过"

## Cursor Cloud specific instructions

- 这台环境没有 systemd。PostgreSQL 用 `pg_ctlcluster` 启动，ClickHouse 用 `clickhouse start`。开机脚本会拉起这两个库、导入仓库 schema，并在 **48080** 启动 `crawler-admin`。
- 本地库地址和账号以 `crawler-admin/src/main/resources/application.yml` 为准（`127.0.0.1` 上的 PostgreSQL 与 ClickHouse）。不要改去连 `docker-compose.yml` 里的远程主机。
- 管理接口端口是 `application.yml` 的 48080，不是 Dockerfile 注释里的 8081。健康检查：`curl -sf http://127.0.0.1:48080/api/crawl/stats`。
- 不访问外网行情、只写一条本地任务：`POST /api/crawl/seed-northbound`，body `{"tradeDate":"2026-10-15","source":1}`。再调一次 `inserted` 应为 0。
- 单元测试：`mvn -B -pl crawler-core test`。打包管理端：`mvn -B -pl crawler-admin -am -DskipTests package`。
- ClickHouse 需要 `allow_nullable_key`，否则 `clickhouse-schema.sql` 里 Nullable 排序键的表建不起来。
- 同花顺浏览器策略需要 Playwright Chromium，这里没有预装。`akshare-bridge` 默认关闭。