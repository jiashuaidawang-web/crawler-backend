package com.dunwugudao.crawler.admin.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 全市场股票/指数列表提供方（M3-2）。
 * <p>每日从东财接口动态获取最新股票/指数列表，通过代理池访问，分页迭代，失败重试。</p>
 */
@Slf4j
@Service
public class StockUniverseProvider {

    private static final String CLIST_URL_BASE =
            "https://push2.eastmoney.com/api/qt/clist/get?pz=100&po=1&np=1&fltt=2&invt=2&fid=f3"
                    + "&fs=m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23&fields=f12";

    // 7 只主要指数（硬编码，数量稳定）
    private static final List<String> INDICES = List.of(
            "000001.SH", "399001.SZ", "399006.SZ",
            "000300.SH", "000905.SH", "000852.SH", "932000.CSI"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ProxyManager proxyManager = new ProxyManager();

    /**
     * 获取全市场股票代码列表（分页迭代，失败重试）。
     */
    public List<String> stockCodes() {
        List<String> codes = new ArrayList<>();
        int page = 1;
        while (true) {
            String url = CLIST_URL_BASE + "&pn=" + page;
            String resp = proxyManager.executeWithRetry(url);

            if (resp == null) {
                log.warn("股票列表第 {} 页获取失败，跳过", page);
                break;
            }

            try {
                JsonNode root = objectMapper.readTree(resp);
                JsonNode diff = root.path("data").path("diff");

                if (!diff.isArray() || diff.size() == 0) {
                    break; // 没有更多数据
                }

                int pageCount = 0;
                for (JsonNode node : diff) {
                    String code = node.path("f12").asText();
                    if (code != null && !code.isEmpty() && !codes.contains(code)) {
                        codes.add(code);
                        pageCount++;
                    }
                }

                log.info("股票列表第 {} 页：获取 {} 条", page, pageCount);

                if (pageCount < 100) {
                    break; // 最后一页
                }
                page++;
            } catch (Exception e) {
                log.warn("解析股票列表第 {} 页失败：{}", page, e.getMessage());
                break;
            }
        }

        log.info("从接口获取到 {} 只股票代码（共 {} 页）", codes.size(), page);
        return codes;
    }

    /**
     * 获取指数代码列表（硬编码 7 只主要指数）。
     */
    public List<String> indexCodes() {
        return INDICES;
    }
}
