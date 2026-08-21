package com.dunwugudao.crawler.admin.controller;

import com.dunwugudao.crawler.admin.init.StockAnomalyInitService;
import com.dunwugudao.crawler.admin.init.StockBoardRelInitService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/init")
@RequiredArgsConstructor
public class StockBoardRelController {

    private final StockBoardRelInitService stockBoardRelInitService;
    private final StockAnomalyInitService stockAnomalyInitService;

    @PostMapping("/stock-board-rel")
    public Map<String, Object> initStockBoardRel(@RequestParam(required = false) String dir) {
        StockBoardRelInitService.InitResult result = stockBoardRelInitService.init(dir);
        Map<String, Object> r = new HashMap<>();
        r.put("status", "ok");
        r.put("table", "stock_board_rel");
        r.put("targetDir", result.getTargetDir());
        r.put("totalFiles", result.getTotalFiles());
        r.put("totalRows", result.getTotalRows());
        r.put("parseErrors", result.getParseErrors());
        r.put("dataSource", 0);
        r.put("dataSourceLabel", "同花顺");
        r.put("boardType", 3);
        r.put("boardTypeLabel", "概念");
        return r;
    }

    @PostMapping("/stock-anomaly")
    public Map<String, Object> initStockAnomaly(@RequestParam(required = false) String dir) {
        StockAnomalyInitService.InitResult result = stockAnomalyInitService.init(dir);
        Map<String, Object> r = new HashMap<>();
        r.put("status", "ok");
        r.put("table", "stock_anomaly");
        r.put("targetDir", result.getTargetDir());
        r.put("totalFiles", result.getTotalFiles());
        r.put("totalRows", result.getTotalRows());
        r.put("parseErrors", result.getParseErrors());
        r.put("dataSource", 0);
        r.put("dataSourceLabel", "同花顺");
        return r;
    }
}
