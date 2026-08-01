package com.dunwugudao.crawler.admin.service;

import com.dunwugudao.crawler.persistence.entity.CrawlAlert;
import com.dunwugudao.crawler.persistence.entity.CrawlNode;
import com.dunwugudao.crawler.persistence.mapper.CrawlAlertMapper;
import com.dunwugudao.crawler.persistence.mapper.CrawlNodeMapper;
import com.dunwugudao.crawler.persistence.mapper.CrawlTaskMapper;
import lombok.RequiredArgsConstructor;
import org.apache.http.HttpHost;
import org.apache.http.client.fluent.Executor;
import org.apache.http.client.fluent.Request;
import org.springframework.stereotype.Service;
// SSL相关
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.ssl.SSLContexts;
// 不需要额外引入其他包，之前添加的fluent-hc依赖已经包含这些类
import javax.net.ssl.SSLContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 监控聚合（能力6）：状态计数、成功率、按来源/节点分布、告警与节点列表。
 */
@Service
@RequiredArgsConstructor
public class MonitorService {

    private final CrawlTaskMapper crawlTaskMapper;
    private final CrawlAlertMapper crawlAlertMapper;
    private final CrawlNodeMapper crawlNodeMapper;

    /** 总体统计：各状态计数 + 成功率。 */
    public Map<String, Object> stats() {
        List<Map<String, Object>> byStatus = crawlTaskMapper.countByStatus();
        long total = 0;
        long success = 0;
        for (Map<String, Object> m : byStatus) {
            long c = ((Number) m.get("cnt")).longValue();
            total += c;
            if ("SUCCESS".equals(m.get("status"))) {
                success = c;
            }
        }
        double rate = total == 0 ? 0.0 : (success * 100.0 / total);
        Map<String, Object> r = new HashMap<>();
        r.put("byStatus", byStatus);
        r.put("total", total);
        r.put("successRate", rate);
        return r;
    }

    public List<Map<String, Object>> statsBySource() {
        return crawlTaskMapper.countBySource();
    }

    public List<Map<String, Object>> statsByNode() {
        return crawlTaskMapper.countByNode();
    }

    public List<CrawlAlert> alerts(int resolved) {
        return crawlAlertMapper.selectByResolved(resolved);
    }

    /** 标记某条告警为已处理（resolved=1），返回受影响行数。 */
    public int resolveAlert(Long alertId) {
        return crawlAlertMapper.updateResolved(alertId, 1);
    }

    public List<CrawlNode> nodes() {
        return crawlNodeMapper.selectList(null);
    }

//    public static void main(String[] args) throws Exception {
//
//
//
//        // 1. 配置代理信息
//        HttpHost proxy = new HttpHost("198.23.243.226", 6361);
//
//        // 2. 构建请求
////        String url = "https://push2ex.eastmoney.com/getTopicZTPool" +
////                "?cb=callbackdata4989295" +
////                "&ut=7eea3edcaed734bea9cbfc24409ed989" +
////                "&dpt=wz.ztzt" +
////                "&Pageindex=0" +
////                "&pagesize=20" +
////                "&sort=fbt%3Aasc" +
////                "&date=20260801" +
////                "&_=1785573582543";
//        String url = "https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=100&po=1&np=1&fltt=2&invt=2&fid=f3&fs=m:90+t:1+f:!50&fields=f12,f14";
//
//        // 3. 执行请求
//        String res = Executor.newInstance()
//                .auth(proxy, "yeifkhye", "dk9175nu6szk")
//                .execute(
//                        Request.Get(url)
//                                .viaProxy(proxy)
//                                // 可选：添加User-Agent头，模拟浏览器访问，避免被拒绝
//                                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
//                )
//                .returnContent()
//                .asString();
//
//        // 4. 输出结果
//        System.out.println(res);
//    }


public static void main(String[] args) throws Exception {
    // 1. 配置代理信息
    HttpHost proxy = new HttpHost("198.23.243.226", 6361);

    // 2. 请求地址
    String url = "https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=100&po=1&np=1&fltt=2&invt=2&fid=f3&fs=m:90+t:1+f:!50&fields=f12,f14";

    // 3. 执行请求
    String res = Executor.newInstance()
            .auth(proxy, "yeifkhye", "dk9175nu6szk")
            .execute(
                    Request.Get(url)
                            .viaProxy(proxy)
                            // 补全完整的浏览器请求头
                            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .addHeader("Referer", "https://quote.eastmoney.com/") // 关键头，说明是从东财官网跳转来的
                            .addHeader("Accept", "application/json, text/plain, */*")
                            .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                            .addHeader("Connection", "keep-alive")
            )
            .returnContent()
            .asString();

    // 4. 输出结果
    System.out.println(res);
}
}
