package com.financial.news.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 行情模块配置（application.yml quote.*）
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "quote")
public class QuoteProperties {

    private Provider provider = new Provider();
    private Cache cache = new Cache();
    private RateLimit rateLimit = new RateLimit();
    private Sync sync = new Sync();
    /** 指数卡清单：market -> 有序 symbol 列表 */
    private Map<String, List<String>> indices;

    @Data
    public static class Provider {
        /** 主源：eastmoney | sina */
        private String primary = "eastmoney";
        private int connectTimeoutMs = 2000;
        private int readTimeoutMs = 4000;
        /** 熔断阈值：连续失败次数 */
        private int breakerFailThreshold = 5;
        /** 熔断打开持续时间（秒） */
        private int breakerOpenSeconds = 30;
        /** 上游地址可配置（联调/测试可指向本地 mock，生产保持默认） */
        private String eastMoneyBaseUrl = "https://push2.eastmoney.com";
        private String eastMoneyHisBaseUrl = "https://push2his.eastmoney.com";
        private String sinaBaseUrl = "https://hq.sinajs.cn";
    }

    @Data
    public static class Cache {
        private int snapshotFreshSeconds = 15;
        private int trendFreshSeconds = 60;
        private int klineFreshSeconds = 300;
        private int listFreshSeconds = 15;
        private boolean warmupEnabled = true;
        private long warmupIntervalMs = 30000;
        /** 休市缓存 TTL 上限（秒），防止日历缺失时缓存过久 */
        private long closedMaxTtlSeconds = 43200;
    }

    @Data
    public static class RateLimit {
        private boolean enabled = true;
        private int windowSeconds = 60;
        private int maxRequests = 60;
    }

    @Data
    public static class Sync {
        private String calendarCron = "0 0 3 * * *";
        private String securityCron = "0 10 3 * * *";
        /** 日历至少前瞻天数 */
        private int calendarForwardDays = 90;
        /** 退市判定：连续缺失同步次数 */
        private int delistMissingRounds = 3;
    }
}
