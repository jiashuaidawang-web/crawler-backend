// hexin-v-extractor/JavaClient.java（设计说明）
//
// Java worker 调用 Node hexin-v 服务拿 token 的推荐方式：
//
// 1. 进程模式：admin/worker 启动时拉起 `node server.js 9090`，通过 HTTP 调用。
//    好处：浏览器实例长驻，token 可复用；chameleon 的 cookie 会话保在浏览器上下文里。
//
// 2. 调用示例（OkHttp）：
//
//    String json = "{\"url\":\"https://quote.10jqka.com.cn/center/\",\"proxy\":\"http://u:p@h:p\"}";
//    Request req = new Request.Builder()
//        .url("http://127.0.0.1:9090/extract")
//        .post(RequestBody.create(json, MediaType.parse("application/json")))
//        .build();
//    try (Response resp = httpClient.newCall(req).execute()) {
//        JsonObject r = JsonParser.parseString(resp.body().string()).getAsJsonObject();
//        String token = r.get("token").getAsString();
//        // 拿到 token 后，调用同花顺接口时附到请求：
//        //   - URL  query:  ?hexin-v=<token>
//        //   - 或 Header:   hexin-v: <token>    /   X-Antispider-Message: <token>
//    }
//
// 3. 重要约束：
//    - token 与浏览器会话绑定，服务端通过 X-Antispider-Message 响应头更新 token，
//      因此长流程应复用同一个 extractor 实例（不要每次 new 浏览器）。
//    - token 内含时间戳，有过期窗口，需按响应头刷新。
//    - 代理必须用住宅 IP；chameleon 会做自动化检测（webdriver/$cdc_/HeadlessChrome），
//      headless Chromium 已做基础 stealth，但仍建议用真实非 headless 或完善指纹。
