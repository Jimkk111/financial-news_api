package com.financial.news.dto.response.quote;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 快照 VO：指数卡、热门列表项与详情页报价区共用
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class QuoteSnapshotVO extends QuoteBaseVO {

    private Double latestPrice;

    private Double changeAmount;

    private Double changePercent;

    private Double open;

    private Double prevClose;

    private Double high;

    private Double low;

    /** 成交量（股） */
    private Long volume;

    /** 成交额（原币种元） */
    private Double turnover;
}
