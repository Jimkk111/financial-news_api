package com.financial.news.dto.response.quote;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 分时 VO：价格线 + 均价线（avgPrice）+ 昨收基准线（prevClose）+ 成交量柱
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class QuoteTrendVO extends QuoteBaseVO {

    private Double prevClose;

    /** 分时轴总分钟数：CN 240 / HK 330 / US 390（PRD 口径，纯交易分钟） */
    private Integer timelineMinutes;

    private List<TrendPointVO> points;
}
