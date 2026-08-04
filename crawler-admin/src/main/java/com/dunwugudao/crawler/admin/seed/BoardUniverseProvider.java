package com.dunwugudao.crawler.admin.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 全市场板块列表提供方。
 * <p>每日从东财接口动态获取最新板块列表（地域+行业+概念），通过代理池访问，分页迭代，失败重试。</p>
 */
@Slf4j
@Service
public class BoardUniverseProvider {

    // 三种板块类型：地域(t:1) / 行业(t:2) / 概念(t:3)
    private static final List<String> BOARD_FILTERS = List.of(
            "m:90+t:1+f:!50",  // 地域
            "m:90+t:2+f:!50",  // 行业
            "m:90+t:3+f:!50"   // 概念
    );

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ProxyManager proxyManager;

    public BoardUniverseProvider(ProxyManager proxyManager) {
        this.proxyManager = proxyManager;
    }

    /**
     * 获取全市场板块代码列表（分页迭代，失败重试）。
     */
    public List<String> boardCodes() {
        List<String> codes = new ArrayList<>();
        for (String fs : BOARD_FILTERS) {
            int page = 1;
            while (true) {
                String url = "https://push2.eastmoney.com/api/qt/clist/get?pn=" + page + "&pz=100&po=1&np=1&fltt=2&invt=2&fid=f3"
                        + "&fs=" + fs + "&fields=f12";

                String resp = proxyManager.executeWithRetry(url);
                if (resp == null) {
                    log.warn("板块列表(fs={}, page={})获取失败，跳过", fs, page);
                    break;
                }

                try {
                    JsonNode root = objectMapper.readTree(resp);
                    JsonNode diff = root.path("data").path("diff");

                    if (!diff.isArray() || diff.size() == 0) {
                        break;
                    }

                    int pageCount = 0;
                    for (JsonNode node : diff) {
                        String code = node.path("f12").asText();
                        if (code != null && !code.isEmpty() && !codes.contains(code)) {
                            codes.add(code);
                            pageCount++;
                        }
                    }

                    if (pageCount < 100) {
                        break;
                    }
                    page++;
                } catch (Exception e) {
                    log.warn("解析板块列表失败(fs={}, page={})：{}", fs, page, e.getMessage());
                    break;
                }
            }
        }

        log.info("从接口获取到 {} 个板块", codes.size());
        return codes;
    }

    /**
     * 获取板块详细信息（代码+名称+类型，分页迭代，失败重试）。
     */
    public List<BoardInfo> boardInfos() {
        List<BoardInfo> boards = new ArrayList<>();
        int[] boardTypes = {1, 2, 3};

        for (int i = 0; i < BOARD_FILTERS.size(); i++) {
            String fs = BOARD_FILTERS.get(i);
            int boardType = boardTypes[i];
            int page = 1;
            while (true) {
                String url = "https://push2.eastmoney.com/api/qt/clist/get?pn=" + page + "&pz=100&po=1&np=1&fltt=2&invt=2&fid=f3"
                        + "&fs=" + fs + "&fields=f12,f14";

                String resp = proxyManager.executeWithRetry(url);
                if (resp == null) {
                    log.warn("板块信息(fs={}, page={})获取失败，跳过", fs, page);
                    break;
                }

                try {
                    JsonNode root = objectMapper.readTree(resp);
                    JsonNode diff = root.path("data").path("diff");

                    if (!diff.isArray() || diff.size() == 0) {
                        break;
                    }

                    int pageCount = 0;
                    for (JsonNode node : diff) {
                        String code = node.path("f12").asText();
                        String name = node.path("f14").asText();
                        if (code != null && !code.isEmpty()) {
                            boards.add(new BoardInfo(code, name, boardType));
                            pageCount++;
                        }
                    }

                    if (pageCount < 100) {
                        break;
                    }
                    page++;
                } catch (Exception e) {
                    log.warn("解析板块信息失败(fs={}, page={})：{}", fs, page, e.getMessage());
                    break;
                }
            }
        }

        log.info("从接口获取到 {} 个板块信息", boards.size());
        return boards;
    }

    /** 板块信息。 */
    public record BoardInfo(String boardCode, String boardName, int boardType) {
    }
}
