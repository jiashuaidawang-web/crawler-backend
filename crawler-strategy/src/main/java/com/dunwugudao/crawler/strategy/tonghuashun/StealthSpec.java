package com.dunwugudao.crawler.strategy.tonghuashun;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * 浏览器 stealth 配置（同花顺反爬）。
 * <p>每次 {@link #randomize()} 产出一组本次使用的 UA / viewport / locale / timezone，
 * 制造指纹随机性，配合 {@link BrowserContextFactory} 的 init script 规避基础检测。</p>
 */
@Data
@NoArgsConstructor
public class StealthSpec {

    /** 是否启用 stealth（默认 true）。 */
    private boolean enabled = true;

    private List<String> userAgents = Arrays.asList(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36");

    private int viewportMinW = 1280;
    private int viewportMinH = 800;
    private int viewportMaxW = 1920;
    private int viewportMaxH = 1080;

    private List<String> locales = Arrays.asList("zh-CN", "zh-CN");
    private List<String> timezones = Arrays.asList("Asia/Shanghai");

    private final Random random = new Random();

    /** 本次会话使用的指纹组合。 */
    @Data
    @AllArgsConstructor
    public static class Fingerprint {
        String userAgent;
        int width;
        int height;
        String locale;
        String timezone;
    }

    /** 随机产出一组指纹（enabled=false 时仍返回确定性默认值，不影响业务流程）。 */
    public Fingerprint randomize() {
        String ua = pick(userAgents);
        int w = rand(viewportMinW, viewportMaxW);
        int h = rand(viewportMinH, viewportMaxH);
        String locale = pick(locales);
        String tz = pick(timezones);
        return new Fingerprint(ua, w, h, locale, tz);
    }

    private String pick(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(random.nextInt(list.size()));
    }

    private int rand(int min, int max) {
        if (max <= min) {
            return min;
        }
        return min + random.nextInt(max - min + 1);
    }
}
