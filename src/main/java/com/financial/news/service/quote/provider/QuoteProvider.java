package com.financial.news.service.quote.provider;

import com.financial.news.service.quote.provider.model.KlineBar;
import com.financial.news.service.quote.provider.model.QuoteSnapshot;
import com.financial.news.service.quote.provider.model.TrendData;
import com.financial.news.service.quote.provider.model.UpstreamTarget;

import java.util.List;

/**
 * 行情数据源 SPI（实现只做协议解析，容错与选择由 QuoteProviderRouter 负责）
 *
 * @author financial-news
 * @since 1.0.0
 */
public interface QuoteProvider {

    /** 数据源标识（配置 quote.provider.primary 用） */
    String name();

    boolean supportsSnapshots();

    boolean supportsTrend();

    boolean supportsKline();

    /**
     * 批量快照。返回列表与入参顺序无关，按 target.secid 对应；
     * 上游未返回的 target 不出现在结果中。失败抛 UpstreamException。
     */
    List<QuoteSnapshot> getSnapshots(List<UpstreamTarget> targets);

    /** 当日分时（含昨收）。失败抛 UpstreamException。 */
    TrendData getTrend(UpstreamTarget target);

    /**
     * 日 K（前复权），按日期升序返回最近 fetchCount 根。
     * fetchCount 应大于展示数量（服务层预留均线窗口）。失败抛 UpstreamException。
     */
    List<KlineBar> getKline(UpstreamTarget target, int fetchCount);
}
