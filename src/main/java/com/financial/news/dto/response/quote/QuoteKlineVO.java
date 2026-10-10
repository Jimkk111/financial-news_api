package com.financial.news.dto.response.quote;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 日 K VO（复权口径随实际数据源返回）
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class QuoteKlineVO extends QuoteBaseVO {

    /** qfq 前复权；none 原始价格。 */
    private String fq;

    private List<KlineBarVO> bars;

    private KlineMaVO ma;
}
