package com.financial.news.service.quote.sync;

import com.financial.news.config.QuoteProperties;
import com.financial.news.service.quote.MarketEnum;
import com.financial.news.service.quote.QuoteService;
import com.financial.news.service.quote.TradingSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 盘中缓存预热：各市场交易时段内每 30 秒预取指数卡 + 热门名单（每市场一次批量上游请求），
 * 保证用户首次进入行情 Tab 直接命中缓存（首开 ≤2s）。详情页为长尾不做预热。
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuoteCacheWarmupJob {

    private final QuoteService quoteService;
    private final TradingSessionService tradingSession;
    private final QuoteProperties properties;

    @Scheduled(fixedDelayString = "${quote.cache.warmup-interval-ms:30000}", initialDelay = 15000)
    public void warmup() {
        if (!properties.getCache().isWarmupEnabled()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(TradingSessionService.ZONE);
        for (MarketEnum market : MarketEnum.values()) {
            if (!tradingSession.isInSession(market, now)) {
                continue;
            }
            try {
                quoteService.getIndexCards(market.name());
                quoteService.getHotList(market.name(), 50);
            } catch (Exception e) {
                log.warn("[行情] {} 预热失败: {}", market, e.getMessage());
            }
        }
    }
}
